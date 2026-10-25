package modsync.server;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import modsync.common.ModPayloads;

/**
 * ModSync 服务端入口（仅服务端）。
 *
 * 服务端持有权威的 mod 清单：
 *   - 玩家进服 → 下发清单（sha256 列表）
 *   - 客户端组件（modsync-client）比对本地后回「我需要这些」
 *   - 服务端逐个推送文件字节
 *
 * 若客户端没有装 modsync-client，则在聊天栏提示下载（checkUrl）或直接踢出（kickOnMissing）。
 */
public class ModSyncServer implements ModInitializer {
    public static final String MOD_ID = "modsync";

    @Override
    public void onInitialize() {
        Config.load();

        // 双向载荷注册（本模块只在服务端运行，注册 clientbound 类型用于下发）
        PayloadTypeRegistry.clientboundPlay().register(ModPayloads.ManifestChunk.TYPE, ModPayloads.ManifestChunk.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ModPayloads.FileMeta.TYPE, ModPayloads.FileMeta.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ModPayloads.FileChunk.TYPE, ModPayloads.FileChunk.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ModPayloads.FileEnd.TYPE, ModPayloads.FileEnd.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ModPayloads.ModRequest.TYPE, ModPayloads.ModRequest.CODEC);

        ServerSync.init();
    }
}
