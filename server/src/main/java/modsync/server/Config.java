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
 * serverOnly      : 仅服务端运行的 mod 文件名，不下发给客户端（如 carpet、lithium）。
 * chunkSize       : 单个网络包字节数（默认 30KB，远小于 MC 自定义载荷上限）。
 * checkUrl        : 客户端没装 modsync-client 时，提示去哪里下载整合包。
 * kickOnMissing   : 客户端没装 modsync-client 时是否直接踢出。
 * kickMessage     : 踢出文案。
 * requireFabric   : 要求客户端是装了 fabric-api 的 Fabric 客户端。
 * requiredChannels: 客户端必须注册的通道（额外校验用，缺一个即视为少对应 mod）。
 */
public class Config {
    public List<String> serverOnly = new ArrayList<>();
    public int chunkSize = 30720;
    public String checkUrl = "";
    public boolean kickOnMissing = false;
    public String kickMessage = "你的客户端缺少同步组件，无法自动获取服务器 mod。请安装整合包后重试。";
    public boolean requireFabric = true;
    public List<String> requiredChannels = new ArrayList<>();

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
        if (INSTANCE.requiredChannels == null) INSTANCE.requiredChannels = new ArrayList<>();
        if (INSTANCE.chunkSize <= 0) INSTANCE.chunkSize = 30720;
    }
}
