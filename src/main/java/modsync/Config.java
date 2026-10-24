package modsync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置：D:\MainServer\config\modsync.json（首次启动自动生成）。
 * serverOnly : 不推送给客户端的仅服务端 mod（性能类/服务端专用，如 carpet、lithium 等）。
 * chunkSize  : 单次网络包字节数（默认 30KB，远小于 MC 自定义载荷上限，安全）。
 * autoRestart: 同步完成后是否尝试自动重启客户端（官方启动器可能需手动重启一次）。
 */
public class Config {
    public List<String> serverOnly = new ArrayList<>();
    public int chunkSize = 30720;
    public boolean autoRestart = true;

    public static Config INSTANCE = new Config();

    public static void load() {
        Path p = FabricLoader.getInstance().getGameDir().resolve("config").resolve("modsync.json");
        if (Files.exists(p)) {
            try {
                INSTANCE = new Gson().fromJson(
                        new String(Files.readAllBytes(p), StandardCharsets.UTF_8), Config.class);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            try {
                Files.createDirectories(p.getParent());
                Files.write(p, new GsonBuilder().setPrettyPrinting().create()
                        .toJson(INSTANCE).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (INSTANCE.chunkSize <= 0) INSTANCE.chunkSize = 30720;
    }
}
