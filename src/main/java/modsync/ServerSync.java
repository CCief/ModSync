package modsync;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * 服务端逻辑：
 *  1) 玩家加入(JOIN) → 扫描 mods/（排除 serverOnly）→ 清单 gzip 分块下发。
 *  2) 收到客户端 ModRequest → 逐个发送 FileMeta + FileChunk* + FileEnd。
 */
public class ServerSync {
    static final Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
    static final Gson GSON = new Gson();

    public static void sendManifest(ServerPlayer player) {
        try {
            JsonArray arr = new JsonArray();
            if (Files.exists(modsDir)) {
                try (var stream = Files.list(modsDir)) {
                    for (Path p : (Iterable<Path>) stream::iterator) {
                        if (!Files.isRegularFile(p)) continue;
                        String name = p.getFileName().toString();
                        if (!name.endsWith(".jar")) continue;
                        if (Config.INSTANCE.serverOnly.contains(name)) continue;
                        JsonObject o = new JsonObject();
                        o.addProperty("name", name);
                        o.addProperty("sha", Util.sha256(p));
                        o.addProperty("size", Files.size(p));
                        arr.add(o);
                    }
                }
            }
            byte[] data = Util.gzip(GSON.toJson(arr).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            int chunk = Config.INSTANCE.chunkSize;
            int total = (int) Math.ceil((double) data.length / chunk);
            for (int i = 0; i < total; i++) {
                int len = Math.min(chunk, data.length - i * chunk);
                byte[] c = java.util.Arrays.copyOfRange(data, i * chunk, i * chunk + len);
                ServerPlayNetworking.send(player, new ModPayloads.ManifestChunk(i, total, c));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void handleRequest(ServerPlayer player, ModPayloads.ModRequest payload) {
        List<String> wanted = new ArrayList<>();
        try {
            String json = new String(Util.gunzip(payload.namesJson()), java.nio.charset.StandardCharsets.UTF_8);
            JsonArray arr = GSON.fromJson(json, JsonArray.class);
            if (arr != null) {
                for (var e : arr) wanted.add(e.getAsString());
            }
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }
        for (String name : wanted) {
            streamFile(player, name);
        }
    }

    private static void streamFile(ServerPlayer player, String name) {
        Path p = modsDir.resolve(name);
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
        try {
            ServerPlayNetworking.send(player, new ModPayloads.FileMeta(
                    name, data == null ? 0 : data.length, sha));
            if (data != null) {
                int chunk = Config.INSTANCE.chunkSize;
                int total = (int) Math.ceil((double) data.length / chunk);
                for (int i = 0; i < total; i++) {
                    int len = Math.min(chunk, data.length - i * chunk);
                    byte[] c = java.util.Arrays.copyOfRange(data, i * chunk, i * chunk + len);
                    ServerPlayNetworking.send(player, new ModPayloads.FileChunk(name, i, total, c));
                }
            }
            ServerPlayNetworking.send(player, new ModPayloads.FileEnd(name, sha));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
