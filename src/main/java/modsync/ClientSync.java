package modsync;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
 * 客户端逻辑：
 *  - 收 ManifestChunk → 拼回清单 → 计算差异(名+sha) → 发 ModRequest。
 *  - 收 FileMeta/Chunk/End → 落盘 .modsync_pending/ 并按 sha256 校验。
 *  - 全部完成 → 移入 mods/ → 自动重启。一致则仅提示放行。
 */
public class ClientSync {
    static final Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
    static final Path pendingDir = FabricLoader.getInstance().getGameDir().resolve(".modsync_pending");
    static final Gson GSON = new Gson();

    static final Map<Integer, byte[]> manifestChunks = new HashMap<>();
    static final Map<String, FileAssembly> assembling = new HashMap<>();
    static int filesRemaining = 0;

    static class FileAssembly {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int total = 0; // -1 = 服务端缺该文件(MISSING)
    }

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(ModPayloads.ManifestChunk.TYPE, (payload, ctx) -> {
            manifestChunks.put(payload.index(), payload.data());
            if (manifestChunks.size() == payload.total()) {
                ByteArrayOutputStream all = new ByteArrayOutputStream();
                for (int i = 0; i < payload.total(); i++) all.write(manifestChunks.get(i), 0, manifestChunks.get(i).length);
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
            fa.total = "MISSING".equals(payload.sha()) ? -1 : (int) Math.ceil((double) payload.size() / Config.INSTANCE.chunkSize);
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
                        Files.createDirectories(pendingDir);
                        Files.write(pendingDir.resolve(payload.name()), data);
                    } else {
                        Minecraft client = ctx.client();
                        client.execute(() -> msg(client, "§c[ModSync] " + payload.name() + " 校验失败，已跳过。"));
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
            if (Files.exists(modsDir)) {
                try (var s = Files.list(modsDir)) {
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
            msg(client, "§a[ModSync] 客户端 mod 与服务端一致，放行进入。");
            return;
        }
        filesRemaining = needed.size();
        try {
            byte[] namesJson = Util.gzip(GSON.toJson(needed).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ClientPlayNetworking.send(new ModPayloads.ModRequest(namesJson));
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }
        msg(client, "§e[ModSync] 检测到 " + needed.size() + " 个 mod 不一致，正在经 25565/内网穿透拉取…");
    }

    static void finishSync(Minecraft client) {
        try {
            if (Files.exists(pendingDir)) {
                Files.createDirectories(modsDir);
                try (var s = Files.list(pendingDir)) {
                    for (Path p : (Iterable<Path>) s::iterator) {
                        if (Files.isRegularFile(p)) {
                            Files.move(p, modsDir.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
                deleteDir(pendingDir);
            }
            msg(client, "§a[ModSync] mod 已更新完毕，即将自动重启…");
            if (Config.INSTANCE.autoRestart) Restart.restart();
            else msg(client, "§e[ModSync] 请手动重启游戏以加载新 mod。");
        } catch (Exception e) {
            e.printStackTrace();
            msg(client, "§c[ModSync] 更新完成但自动重启失败，请手动重启游戏。");
        }
    }

    static void msg(Minecraft client, String text) {
        if (client.player != null) client.player.sendSystemMessage(Component.literal(text));
    }

    static void deleteDir(Path d) throws Exception {
        if (!Files.exists(d)) return;
        try (var s = Files.walk(d)) {
            s.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        }
    }
}
