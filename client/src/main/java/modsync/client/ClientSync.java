package modsync.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import modsync.common.ModPayloads;
import modsync.common.Util;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端伴生组件逻辑：
 *  - 收 ManifestChunk → 拼回清单 → 与本地 mods/ 比对 sha256 → 发 ModRequest（缺失名单）
 *  - 收 FileMeta/Chunk/End → 落盘 .modsync_pending/ 并按 sha256 校验
 *  - 全部完成 → 移入 mods/ → 重启游戏 → 自动重连回服务器
 *  - 本地已一致 → 只提示，不传输、不重启
 */
public class ClientSync {
    private static final Gson GSON = new Gson();

    private static final Path MODS_DIR = FabricLoader.getInstance().getGameDir().resolve("mods");
    private static final Path PENDING_DIR = FabricLoader.getInstance().getGameDir().resolve(".modsync_pending");

    /** 单包字节数，须与服务端一致（服务端 config.chunkSize 默认 30720）。 */
    private static final int CHUNK_SIZE = 30720;

    private static final Map<Integer, byte[]> manifestChunks = new HashMap<>();
    private static final Map<String, FileAssembly> assembling = new HashMap<>();
    private static int filesRemaining = 0;

    /** 重启后需要提示玩家重新连接的服务器地址（MC 26.3 没有可用的自动 connect API）。 */
    private static String pendingReconnect = null;

    static class FileAssembly {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int total = 0;   // -1 = 服务端没有该文件
    }

    public static void init() {
        // ★ 进服时主动向服务端打招呼：告诉它"这台客户端能自动收"。
        //   服务端据此才下发清单 —— 比让服务端去猜通道集合更可靠。
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            try {
                ClientPlayNetworking.send(new ModPayloads.Probe());
                System.out.println("[ModSync] 已向服务端报到（具备自动同步能力）。");
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        // 重启后提示玩家重新连接：启动时若发现标记文件，记下地址，等进世界后提示
        try {
            String target = Restart.readReconnectTarget();
            if (target != null) {
                pendingReconnect = target;
                System.out.println("[ModSync] 检测到重启标记，请重新连接: " + target);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        ClientPlayNetworking.registerGlobalReceiver(ModPayloads.ManifestChunk.TYPE, (payload, ctx) -> {
            manifestChunks.put(payload.index(), payload.data());
            if (manifestChunks.size() == payload.total()) {
                ByteArrayOutputStream all = new ByteArrayOutputStream();
                for (int i = 0; i < payload.total(); i++) {
                    byte[] c = manifestChunks.get(i);
                    if (c != null) all.write(c, 0, c.length);
                }
                manifestChunks.clear();
                String json;
                try {
                    json = new String(Util.gunzip(all.toByteArray()), java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) {
                    e.printStackTrace();
                    return;
                }
                Minecraft client = ctx.client();
                client.execute(() -> onManifest(client, json));
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(ModPayloads.FileMeta.TYPE, (payload, ctx) -> {
            FileAssembly fa = new FileAssembly();
            fa.total = "MISSING".equals(payload.sha())
                    ? -1
                    : (int) Math.ceil((double) payload.size() / CHUNK_SIZE);
            assembling.put(payload.name(), fa);
        });

        ClientPlayNetworking.registerGlobalReceiver(ModPayloads.FileChunk.TYPE, (payload, ctx) -> {
            FileAssembly fa = assembling.get(payload.name());
            if (fa != null && fa.total >= 0) fa.bos.write(payload.data(), 0, payload.data().length);
        });

        ClientPlayNetworking.registerGlobalReceiver(ModPayloads.FileEnd.TYPE, (payload, ctx) -> {
            FileAssembly fa = assembling.remove(payload.name());
            if (fa != null && fa.total >= 0) {
                byte[] data = fa.bos.toByteArray();
                try {
                    if (Util.sha256(data).equals(payload.sha())) {
                        Files.createDirectories(PENDING_DIR);
                        Files.write(PENDING_DIR.resolve(payload.name()), data);
                    } else {
                        Minecraft client = ctx.client();
                        client.execute(() -> notifyUser("§c[ModSync] " + payload.name() + " 校验失败，已跳过。"));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            filesRemaining--;
            if (filesRemaining <= 0) {
                Minecraft client = ctx.client();
                client.execute(() -> finishSync(client));
            }
        });
    }

    static void onManifest(Minecraft client, String json) {
        JsonArray arr = GSON.fromJson(json, JsonArray.class);
        Map<String, String> serverMods = new HashMap<>();
        if (arr != null) {
            for (var e : arr) {
                JsonObject o = (JsonObject) e;
                serverMods.put(o.get("name").getAsString(), o.get("sha").getAsString());
            }
        }

        Map<String, String> local = new HashMap<>();
        try {
            if (Files.exists(MODS_DIR)) {
                try (var s = Files.list(MODS_DIR)) {
                    for (Path p : (Iterable<Path>) s::iterator) {
                        if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")) {
                            local.put(p.getFileName().toString(), Util.sha256(p));
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        List<String> needed = new ArrayList<>();
        for (var en : serverMods.entrySet()) {
            String name = en.getKey();
            String sha = en.getValue();
            if (!local.containsKey(name) || !local.get(name).equals(sha)) needed.add(name);
        }

        if (needed.isEmpty()) {
            notifyUser("§a[ModSync] 客户端 mod 与服务端一致，无需更新。");
            showReconnectHintIfAny();
            return;
        }

        filesRemaining = needed.size();
        try {
            byte[] namesJson = Util.gzip(Util.utf8(GSON.toJson(needed)));
            ClientPlayNetworking.send(new ModPayloads.ModRequest(namesJson));
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }
        notifyUser("§e[ModSync] 检测到 " + needed.size() + " 个 mod 需要更新，正在下载…");
    }

    static void finishSync(Minecraft client) {
        try {
            if (Files.exists(PENDING_DIR)) {
                Files.createDirectories(MODS_DIR);
                try (var s = Files.list(PENDING_DIR)) {
                    for (Path p : (Iterable<Path>) s::iterator) {
                        if (Files.isRegularFile(p)) {
                            Files.move(p, MODS_DIR.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
                deleteDir(PENDING_DIR);
            }
            notifyUser("§a[ModSync] mod 已更新完毕，即将自动重启…");

            // 记录当前服务器地址，重启后提示玩家重新连接
            String addr = null;
            try {
                if (client.getCurrentServer() != null) {
                    addr = client.getCurrentServer().ip;
                }
            } catch (Exception ignored) { }

            Restart.restart(addr);
        } catch (Exception e) {
            e.printStackTrace();
            notifyUser("§c[ModSync] 更新完成但出错，请手动重启游戏。");
        }
    }

    static void notifyUser(String text) {
        try {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal(text));
            } else {
                System.out.println("[ModSync] " + text);
            }
        } catch (Exception ignored) { }
    }

    /** 重启后如果还站在服务器里，提醒玩家重新连一次。 */
    static void showReconnectHintIfAny() {
        if (pendingReconnect != null) {
            notifyUser("§e[ModSync] mod 已就绪，请重新连接服务器: §b" + pendingReconnect);
            pendingReconnect = null;
        }
    }

    static void deleteDir(Path d) throws Exception {
        if (!Files.exists(d)) return;
        try (var s = Files.walk(d)) {
            s.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { }
            });
        }
    }
}
