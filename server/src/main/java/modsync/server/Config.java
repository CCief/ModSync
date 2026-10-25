package modsync.server;

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
 * 服务端配置：config/modsync.json（首次启动自动生成）。
 *
 * serverOnly    : 仅服务端运行的 mod 文件名，不下发给客户端（如 carpet、lithium）。
 * chunkSize     : 单个网络包字节数（默认 30KB）。
 * checkUrl      : 客户端没法自动收（没装组件 / 不是 Fabric 客户端）时，提示去哪里下载整合包。
 * kickOnMissing : 上述情况下是否直接踢出（false = 只提示，允许进入）。
 * kickMessage   : 踢出文案。
 */
public class Config {
    public List<String> serverOnly = new ArrayList<>();
    public int chunkSize = 30720;
    public String checkUrl = "";
    public boolean kickOnMissing = false;
    public String kickMessage = "请先安装整合包（fabric-api + modsync-client）后再进入。";

    public static Config INSTANCE = new Config();

    public static void load() {
        Path p = FabricLoader.getInstance().getGameDir().resolve("config").resolve("modsync.json");
        if (Files.exists(p)) {
            try {
                Config loaded = new Gson().fromJson(
                        new String(Files.readAllBytes(p), StandardCharsets.UTF_8), Config.class);
                if (loaded != null) INSTANCE = loaded;
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
        if (INSTANCE.serverOnly == null) INSTANCE.serverOnly = new ArrayList<>();
        if (INSTANCE.chunkSize <= 0) INSTANCE.chunkSize = 30720;
    }
}
