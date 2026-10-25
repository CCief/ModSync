package modsync;

import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 仅服务端逻辑（客户端零依赖 —— 玩家什么都不用装）。
 *
 * 检测原理：
 *   Minecraft 登录时会进入 CONFIGURATION 阶段，双方在这里协商各自支持的自定义网络通道。
 *   服务端可以从 ServerConfigurationNetworking.getReceived(listener) 读到「客户端注册了哪些通道」。
 *   Fabric 客户端只要装了 fabric-api 就一定注册 fabric:registry/sync —— 可用来判断对方是不是
 *   Fabric 客户端；具体某个 mod 若注册了自己的通道（如 "carpet:carpet"），就能精确判断它装没装。
 *
 * 诚实边界：纯服务端**无法枚举客户端的 mod 文件列表**（Fabric 没有把 mod 清单上报给服务端的协议），
 *   所以检测基于「可观测特征」（通道），不是文件 sha256 比对。sha 比对需要客户端代码配合，
 *   那属于 v0.1.0 那种双端方案。
 *
 * 缺失时的处理：按 config 决定「聊天栏提示下载」或「直接踢出」。
 * 下载链接是普通 URL —— 内网穿透、自制 mod、网盘都一样能用。
 */
public final class ServerSync {
    private ServerSync() {}

    /** Fabric 客户端（含 fabric-api）必然注册的通道，用作「是不是 Fabric 客户端」的判据。 */
    static final Identifier FABRIC_REGISTRY_SYNC =
            Identifier.fromNamespaceAndPath("fabric", "registry/sync");

    /** 等待进入世界后要提示的玩家名（CONFIGURE 阶段发不了聊天消息）。 */
    static final Set<String> pendingNotice = ConcurrentHashMap.newKeySet();

    /** 待提示内容：玩家名 → 缺失项列表。 */
    static final Map<String, List<String>> pendingMissing = new ConcurrentHashMap<>();

    public static void init() {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) {
            // 万一被误装进客户端：不注册任何东西，也不报错（无副作用）。
            ServerSync.log("ModSync 运行在客户端环境，本 mod 仅服务端生效，已跳过。");
            return;
        }

        // CONFIGURE 阶段：通道协商已完成，可读客户端注册的通道集合
        ServerConfigurationConnectionEvents.CONFIGURE.register(ServerSync::onConfigure);

        // PLAY 阶段 JOIN：给缺 mod 的玩家发聊天提示（此处可以发消息了）
        ServerPlayConnectionEvents.JOIN.register((ServerGamePacketListenerImpl handler,
                                                  net.fabricmc.fabric.api.networking.v1.PacketSender sender,
                                                  MinecraftServer server) -> {
            ServerPlayer player = handler.getPlayer();
            String name = player.getGameProfile().name();
            if (pendingNotice.remove(name)) {
                List<String> missing = pendingMissing.remove(name);
                sendNotice(player, missing);
            }
        });
    }

    private static void onConfigure(ServerConfigurationPacketListenerImpl listener, MinecraftServer server) {
        Set<Identifier> clientChannels;
        try {
            clientChannels = ServerConfigurationNetworking.getReceived(listener);
        } catch (Exception e) {
            e.printStackTrace();
            clientChannels = null;
        }

        String name;
        try {
            name = listener.getOwner().name();
        } catch (Exception e) {
            name = "?";
        }

        List<String> missing = new ArrayList<>();

        if (clientChannels == null) {
            missing.add("无法读取客户端通道信息");
        } else {
            if (Config.INSTANCE.requireFabric && !clientChannels.contains(FABRIC_REGISTRY_SYNC)) {
                missing.add("fabric-api (未检测到 Fabric 客户端)");
            }
            for (String raw : Config.INSTANCE.requiredChannels) {
                if (raw == null || raw.isBlank()) continue;
                Identifier id = parseChannel(raw);
                if (id == null) {
                    log("requiredChannels 里的 \"" + raw + "\" 不是合法通道标识，已跳过");
                    continue;
                }
                if (!clientChannels.contains(id)) {
                    missing.add(raw);
                }
            }
        }

        if (missing.isEmpty()) {
            return; // 一切齐备，放行
        }

        if (Config.INSTANCE.kickOnMissing) {
            listener.disconnect(Component.literal(buildKickMessage(missing)));
            log("已断开缺少 mod 的玩家: " + name + " -> " + missing);
        } else {
            log("玩家 " + name + " 缺少: " + missing + "（已放行，进入后提示）");
            pendingNotice.add(name);
            pendingMissing.put(name, missing);
        }
    }

    /** "ns:path" → Identifier；不带冒号则按 minecraft: 解析。 */
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

    private static String buildKickMessage(List<String> missing) {
        StringBuilder sb = new StringBuilder(Config.INSTANCE.kickMessage);
        sb.append("\n\n缺失: ").append(String.join(", ", missing));
        String url = Config.INSTANCE.checkUrl;
        if (url != null && !url.isBlank()) {
            sb.append("\n下载地址: ").append(url);
        }
        return sb.toString();
    }

    private static void sendNotice(ServerPlayer player, List<String> missing) {
        player.sendSystemMessage(Component.literal(
                "§6[ModSync]§r 检测到你的客户端缺少服务端要求的 mod：§c"
                        + String.join("§c, §c", missing)));
        String url = Config.INSTANCE.checkUrl;
        if (url != null && !url.isBlank()) {
            player.sendSystemMessage(Component.literal(
                    "§e请从这里下载完整整合包，安装后重启游戏再进来: §b§n" + url));
        } else {
            player.sendSystemMessage(Component.literal(
                    "§e请在服务器公告/群内获取整合包下载地址，安装后重启游戏再进来。"));
        }
    }

    static void log(String s) {
        System.out.println("[ModSync] " + s);
    }
}
