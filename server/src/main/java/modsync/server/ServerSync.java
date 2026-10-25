package modsync.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import modsync.common.ModPayloads;
import modsync.common.Util;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端逻辑，就三件事：
 *
 *  1. 认人   —— CONFIGURE 阶段看客户端有没有注册 modsync:probe 通道。
 *                有 → 这台客户端能自动收，进 PLAY 阶段全自动化。
 *                无 → 这台客户端收不了（没装组件，或压根不是 Fabric 客户端），
 *                     按配置提示下载整合包或直接踢出。
 *
 *  2. 下清单 —— 玩家进服时，把服务端 mods/ 目录的权威清单（文件名+sha256+大小）发下去。
 *                **服务端不判断"玩家缺什么"** —— 那是客户端自己的活：它比对本地 mods/ 后
 *                才知道自己缺什么（包括 fabric-api 这种"引导依赖"，缺了也会被自动补上）。
 *
 *  3. 推文件 —— 客户端报出它需要的名单，服务端逐个把字节推过去。
 *
 * 传输全部走 Minecraft 自己的连接（server-port=25565），frp / NAT / 内网穿透自动透传。
 */
public final class ServerSync {
    private ServerSync() {}

    private static final Gson GSON = new Gson();
    private static final Path MODS_DIR = FabricLoader.getInstance().getGameDir().resolve("mods");

    /** 客户端组件注册的探针通道 —— 有它就说明这台客户端具备自动接收能力。 */
    static final Identifier PROBE_CHANNEL = Identifier.fromNamespaceAndPath("modsync", "probe");

    /** 能自动收的客户端（按玩家名记）。 */
    static final Set<String> autoCapable = ConcurrentHashMap.newKeySet();

    public static void init() {
        // 客户端组件发来的"我需要这些"请求
        ServerPlayNetworking.registerGlobalReceiver(ModPayloads.ModRequest.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> handleRequest(ctx.player(), payload)));

        // ★ 客户端组件进服时的报到：收到就说明它能自动收 → 立刻下发清单
        ServerPlayNetworking.registerGlobalReceiver(ModPayloads.Probe.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> onClientReady(ctx.player())));

        // 每 tick 推进「等待组件报到」的判定
        ServerTickEvents.END_SERVER_TICK.register(server -> tickChecks());

        // 记录在线玩家，供判定时取用
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer p = handler.getPlayer();
            onlinePlayers.put(p.getGameProfile().name(), p);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            String n = handler.getPlayer().getGameProfile().name();
            onlinePlayers.remove(n);
            pendingCheck.remove(n);
            autoCapable.remove(n);
        });

        // 进世界：先看通道集合里有没有探针（快路径），没有就等一会儿再判定
        ServerPlayConnectionEvents.JOIN.register((ServerGamePacketListenerImpl handler,
                                                  net.fabricmc.fabric.api.networking.v1.PacketSender sender,
                                                  net.minecraft.server.MinecraftServer server) -> {
            ServerPlayer player = handler.getPlayer();
            String name = player.getGameProfile().name();

            // 快路径：play 阶段的通道协商此时已完成，客户端若装了组件一定能看到探针
            boolean hasProbe = false;
            try {
                Set<Identifier> channels = ServerPlayNetworking.getReceived(player);
                hasProbe = channels != null && channels.contains(PROBE_CHANNEL);
            } catch (Exception e) {
                e.printStackTrace();
            }

            if (hasProbe) {
                autoCapable.add(name);
                log("玩家 " + name + " 已安装同步组件（通道探针可见），下发清单。");
                sendManifest(player);
                return;
            }

            // 慢路径：客户端可能稍后才发"报到"，等 2 秒（40 tick）再判定，避免误报"没有组件"
            pendingCheck.put(name, 40);
        });
    }

    /** 等待判定「到底有没有组件」的玩家 → 剩余 tick 数。 */
    private static final Map<String, Integer> pendingCheck = new ConcurrentHashMap<>();

    /** 每 tick 递减；到 0 时若仍没等到组件的报到，就提示玩家。 */
    private static void tickChecks() {
        if (pendingCheck.isEmpty()) return;
        List<String> expired = new ArrayList<>();
        pendingCheck.replaceAll((name, left) -> {
            int n = left - 1;
            if (n <= 0) expired.add(name);
            return n <= 0 ? 0 : n;
        });
        for (String name : expired) {
            pendingCheck.remove(name);
            if (autoCapable.contains(name)) continue;   // 期间收到报到了
            log("玩家 " + name + " 没有同步组件，无法自动同步。");
            ServerPlayer p = playerByName(name);
            if (p == null) continue;
            if (Config.INSTANCE.kickOnMissing) {
                p.connection.disconnect(net.minecraft.network.chat.Component.literal(Config.INSTANCE.kickMessage));
            } else {
                sendNotice(p);
            }
        }
    }

    private static final Map<String, ServerPlayer> onlinePlayers = new ConcurrentHashMap<>();

    private static ServerPlayer playerByName(String name) {
        return onlinePlayers.get(name);
    }

    // ───────────────────────── 1. 认人 ─────────────────────────

    /** 客户端组件报到：它能自动收，立刻下发清单。 */
    private static void onClientReady(ServerPlayer player) {
        String name = player.getGameProfile().name();
        if (autoCapable.add(name)) {
            log("玩家 " + name + " 已报到（具备自动同步能力），下发清单。");
        }
        sendManifest(player);
    }

    // ───────────────────────── 2. 下清单 ─────────────────────────

    /** 服务端 mods/ 目录 = 权威清单（排除仅服务端 mod 与同步组件自身）。 */
    static void sendManifest(ServerPlayer player) {
        try {
            JsonArray arr = new JsonArray();
            if (Files.exists(MODS_DIR)) {
                try (var stream = Files.list(MODS_DIR)) {
                    for (Path p : (Iterable<Path>) stream::iterator) {
                        if (!Files.isRegularFile(p)) continue;
                        String name = p.getFileName().toString();
                        if (!name.endsWith(".jar")) continue;
                        if (name.startsWith("modsync")) continue;                    // 同步组件自身不下发
                        if (Config.INSTANCE.serverOnly.contains(name)) continue;     // 仅服务端 mod 不下发
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
            log("已向 " + player.getGameProfile().name() + " 下发清单：" + arr.size() + " 个 mod");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ───────────────────────── 3. 推文件 ─────────────────────────

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
        log("玩家 " + player.getGameProfile().name() + " 请求 " + wanted.size() + " 个 mod");
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

    private static void sendNotice(ServerPlayer player) {
        player.sendSystemMessage(Component.literal(
                "§6[ModSync]§r 这台客户端还不能自动获取服务器 mod。"));
        String url = Config.INSTANCE.checkUrl;
        if (url != null && !url.isBlank()) {
            player.sendSystemMessage(Component.literal(
                    "§e请下载整合包并安装后重进: §b§n" + url));
        } else {
            player.sendSystemMessage(Component.literal(
                    "§e请向服主索取整合包，安装后重进。"));
        }
    }

    static void log(String s) {
        System.out.println("[ModSync] " + s);
    }
}
