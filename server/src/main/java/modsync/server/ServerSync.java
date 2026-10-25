package modsync.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import modsync.common.ModPayloads;
import modsync.common.Util;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端逻辑：
 *
 * 1) CONFIGURE 阶段读客户端注册的通道集合，判断客户端是否装了 modsync-client：
 *      - 有 modsync:probe  → 客户端带自动下载组件 → 进服后自动推送清单
 *      - 没有              → 按 config 提示下载 / 踢出
 *    同时校验 fabric:registry/sync（是不是 Fabric 客户端）与 requiredChannels（额外的 mod 检查）。
 *
 * 2) PLAY 阶段 JOIN：给装了组件的客户端下发权威 mod 清单（sha256 列表）。
 *
 * 3) 收到客户端的 ModRequest → 逐个文件推送（FileMeta → FileChunk* → FileEnd）。
 */
public final class ServerSync {
    private ServerSync() {}

    private static final Gson GSON = new Gson();
    private static final Path MODS_DIR = FabricLoader.getInstance().getGameDir().resolve("mods");

    /** 客户端组件注册的探针通道。 */
    static final Identifier PROBE_CHANNEL = Identifier.fromNamespaceAndPath("modsync", "probe");
    /** Fabric 客户端（含 fabric-api）必然注册的通道。 */
    static final Identifier FABRIC_REGISTRY_SYNC = Identifier.fromNamespaceAndPath("fabric", "registry/sync");

    /** 装了组件、等待接收清单的玩家。 */
    static final Set<String> syncReady = ConcurrentHashMap.newKeySet();
    /** 没装组件、待提示的玩家。 */
    static final Set<String> pendingNotice = ConcurrentHashMap.newKeySet();
    static final java.util.Map<String, List<String>> pendingMissing = new ConcurrentHashMap<>();

    public static void init() {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) {
            log("本 mod 仅服务端生效，客户端环境已跳过。");
            return;
        }

        // 客户端组件会回一个 ModRequest；同时它注册了 modsync:probe 通道
        ServerPlayNetworking.registerGlobalReceiver(ModPayloads.ModRequest.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> handleRequest(ctx.player(), payload)));

        ServerConfigurationConnectionEvents.CONFIGURE.register(ServerSync::onConfigure);

        ServerPlayConnectionEvents.JOIN.register((ServerGamePacketListenerImpl handler,
                                                  net.fabricmc.fabric.api.networking.v1.PacketSender sender,
                                                  net.minecraft.server.MinecraftServer server) -> {
            ServerPlayer player = handler.getPlayer();
            String name = player.getGameProfile().name();
            if (pendingNotice.remove(name)) {
                sendNotice(player, pendingMissing.remove(name));
            } else if (syncReady.contains(name)) {
                sendManifest(player);
            }
        });
    }

    // ───────────────────────── 配置阶段：探测客户端能力 ─────────────────────────

    private static void onConfigure(ServerConfigurationPacketListenerImpl listener,
                                    net.minecraft.server.MinecraftServer server) {
        Set<Identifier> channels;
        try {
            channels = ServerConfigurationNetworking.getReceived(listener);
        } catch (Exception e) {
            e.printStackTrace();
            channels = null;
        }

        String name;
        try {
            name = listener.getOwner().name();
        } catch (Exception e) {
            name = "?";
        }

        List<String> missing = new ArrayList<>();
        boolean hasClient = false;

        if (channels == null) {
            missing.add("无法读取客户端通道信息");
        } else {
            hasClient = channels.contains(PROBE_CHANNEL);
            if (Config.INSTANCE.requireFabric && !channels.contains(FABRIC_REGISTRY_SYNC)) {
                missing.add("fabric-api (未检测到 Fabric 客户端)");
            }
            for (String raw : Config.INSTANCE.requiredChannels) {
                if (raw == null || raw.isBlank()) continue;
                Identifier id = parseChannel(raw);
                if (id == null) {
                    log("requiredChannels 里的 \"" + raw + "\" 不是合法通道标识，已跳过");
                    continue;
                }
                if (!channels.contains(id)) missing.add(raw);
            }
        }

        if (!hasClient) {
            missing.add("modsync-client (自动同步组件)");
        }

        if (missing.isEmpty()) {
            syncReady.add(name);
            log("玩家 " + name + " 检测通过，准备推送 mod 清单。");
            return;
        }

        if (Config.INSTANCE.kickOnMissing) {
            listener.disconnect(Component.literal(buildKickMessage(missing)));
            log("已断开缺少组件的玩家: " + name + " -> " + missing);
        } else {
            log("玩家 " + name + " 缺少: " + missing + "（已放行，进入后提示）");
            pendingNotice.add(name);
            pendingMissing.put(name, missing);
        }
    }

    private static Identifier parseChannel(String raw) {
        try {
            if (raw.contains(":")) {
                int i = raw.indexOf(':');
                return Identifier.fromNamespaceAndPath(raw.substring(0, i), raw.substring(i + 1));
            }
            return Identifier.fromNamespaceAndPath("minecraft", raw);
        } catch (Exception e) {
            return null;
        }
    }

    // ───────────────────────── 清单下发 ─────────────────────────

    /** 扫描服务端 mods/ 目录，生成权威清单（排除 serverOnly 与 modsync 自身）。 */
    static void sendManifest(ServerPlayer player) {
        try {
            JsonArray arr = new JsonArray();
            if (Files.exists(MODS_DIR)) {
                try (var stream = Files.list(MODS_DIR)) {
                    for (Path p : (Iterable<Path>) stream::iterator) {
                        if (!Files.isRegularFile(p)) continue;
                        String name = p.getFileName().toString();
                        if (!name.endsWith(".jar")) continue;
                        if (name.startsWith("modsync")) continue;           // 同步组件自身不下发
                        if (Config.INSTANCE.serverOnly.contains(name)) continue;
                        JsonObject o = new JsonObject();
                        o.addProperty("name", name);
                        o.addProperty("sha", Util.sha256(p));
                        o.addProperty("size", Files.size(p));
                        arr.add(o);
                    }
                }
            }
            byte[] data = Util.gzip(Util.utf8(GSON.toJson(arr)));
            int chunk = Config.INSTANCE.chunkSize;
            int total = (int) Math.ceil((double) data.length / chunk);
            for (int i = 0; i < total; i++) {
                int len = Math.min(chunk, data.length - i * chunk);
                byte[] c = java.util.Arrays.copyOfRange(data, i * chunk, i * chunk + len);
                ServerPlayNetworking.send(player, new ModPayloads.ManifestChunk(i, total, c));
            }
            log("已向 " + player.getGameProfile().name() + " 下发清单，共 " + arr.size() + " 个 mod。");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ───────────────────────── 文件推送 ─────────────────────────

    static void handleRequest(ServerPlayer player, ModPayloads.ModRequest payload) {
        List<String> wanted = new ArrayList<>();
        try {
            String json = new String(Util.gunzip(payload.namesJson()), java.nio.charset.StandardCharsets.UTF_8);
            JsonArray arr = GSON.fromJson(json, JsonArray.class);
            if (arr != null) for (var e : arr) wanted.add(e.getAsString());
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }
        for (String name : wanted) streamFile(player, name);
    }

    private static void streamFile(ServerPlayer player, String name) {
        Path p = MODS_DIR.resolve(name);
        String sha = "MISSING";
        byte[] data = null;
        if (Files.isRegularFile(p)) {
            try {
                data = Files.readAllBytes(p);
                sha = Util.sha256(data);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        ServerPlayNetworking.send(player, new ModPayloads.FileMeta(name, data == null ? 0 : data.length, sha));
        if (data == null) {
            ServerPlayNetworking.send(player, new ModPayloads.FileEnd(name, sha));
            return;
        }
        int chunk = Config.INSTANCE.chunkSize;
        int total = (int) Math.ceil((double) data.length / chunk);
        for (int i = 0; i < total; i++) {
            int len = Math.min(chunk, data.length - i * chunk);
            byte[] c = java.util.Arrays.copyOfRange(data, i * chunk, i * chunk + len);
            ServerPlayNetworking.send(player, new ModPayloads.FileChunk(name, i, total, c));
        }
        ServerPlayNetworking.send(player, new ModPayloads.FileEnd(name, sha));
    }

    // ───────────────────────── 提示 ─────────────────────────

    private static String buildKickMessage(List<String> missing) {
        StringBuilder sb = new StringBuilder(Config.INSTANCE.kickMessage);
        sb.append("\n\n缺失: ").append(String.join(", ", missing));
        String url = Config.INSTANCE.checkUrl;
        if (url != null && !url.isBlank()) sb.append("\n下载地址: ").append(url);
        return sb.toString();
    }

    private static void sendNotice(ServerPlayer player, List<String> missing) {
        player.sendSystemMessage(Component.literal(
                "§6[ModSync]§r 检测到你的客户端缺少必需组件：§c" + String.join("§c, §c", missing)));
        String url = Config.INSTANCE.checkUrl;
        if (url != null && !url.isBlank()) {
            player.sendSystemMessage(Component.literal(
                    "§e请从这里下载整合包，安装后重启游戏再进来: §b§n" + url));
        } else {
            player.sendSystemMessage(Component.literal(
                    "§e请在服务器公告/群内获取整合包下载地址，安装后重启游戏再进来。"));
        }
    }

    static void log(String s) {
        System.out.println("[ModSync] " + s);
    }
}
