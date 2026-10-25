package modsync;

import net.fabricmc.api.ModInitializer;

/**
 * ModSync 入口（仅服务端）。
 *
 * 客户端无需安装任何东西。工作方式：
 *  - 每个装有 ModSync 的服务端在 CONFIGURE 阶段通过 fabric 网络层的通道协商，
 *    探测客户端是否也装有 ModSync（识别标记 channel modsync:probe）。
 *  - 装了  → 视为「同版本客户端」，直接放行。
 *  - 没装  → 按 config 决定：发聊天栏提示（含下载地址）或直接踢出。
 * 自制 mod / 内网穿透与此无关：下载链接是普通 URL，什么环境都能用。
 */
public class ModSync implements ModInitializer {
    public static final String MOD_ID = "modsync";

    @Override
    public void onInitialize() {
        Config.load();
        ServerSync.init();
    }
}
