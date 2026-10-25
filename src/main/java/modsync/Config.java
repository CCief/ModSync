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
 * 配置：config/modsync.json（首次启动自动生成）。仅服务端运行。
 *
 * checkUrl         : 提示玩家去哪里下载完整整合包（网页/网盘/HTTP 任意链接）。
 * kickOnMissing    : 检测到缺失时是否直接踢出（false = 只发聊天提示，允许进入）。
 * kickMessage      : 踢出时显示的文案。
 * requireFabric    : 是否要求客户端是装了 fabric-api 的 Fabric 客户端
 *                    （依据客户端注册的 fabric:registry/sync 通道判定）。
 * requiredChannels : 客户端必须注册的通道列表（每个 mod 一个，如 "carpet:carpet"）。
 *                    通道缺失 = 该 mod 未安装。
 */
public class Config {
    public String checkUrl = "";
    public boolean kickOnMissing = false;
    public String kickMessage = "你的客户端缺少服务端要求的 mod，请先安装后再进入。详情见服务器公告。";
    public boolean requireFabric = true;
    public List<String> requiredChannels = new ArrayList<>();

    public static Config INSTANCE = new Config();

    public static void load() {
        Path p = FabricLoader.getInstance().getGameDir().resolve("config").resolve("modsync.json");
        if (Files.exists(p)) {
            try {
                Config loaded = new Gson().fromJson(
                        new String(Files.readAllBytes(p), StandardCharsets.UTF_8), Config.class);
                if (loaded != null) {
                    INSTANCE = loaded;
                }
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
        if (INSTANCE.requiredChannels == null) {
            INSTANCE.requiredChannels = new ArrayList<>();
        }
    }
}
