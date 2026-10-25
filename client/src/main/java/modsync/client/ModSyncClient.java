package modsync.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import modsync.common.ModPayloads;

/**
 * ModSync 客户端伴生组件（仅客户端）。
 *
 * 职责很单纯：接收服务端下发的权威 mod 清单 → 比对本地 → 请求缺失项 → 接收字节 →
 * 校验 sha256 → 落盘到 mods/ → 重启游戏 → 自动重连回服务器。
 *
 * 它本身不含任何玩法逻辑，就是个"取件员"。
 */
public class ModSyncClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // 载荷编解码（双向注册，保证能编码也能解码）
        PayloadTypeRegistry.clientboundPlay().register(ModPayloads.ManifestChunk.TYPE, ModPayloads.ManifestChunk.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ModPayloads.FileMeta.TYPE, ModPayloads.FileMeta.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ModPayloads.FileChunk.TYPE, ModPayloads.FileChunk.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ModPayloads.FileEnd.TYPE, ModPayloads.FileEnd.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ModPayloads.ModRequest.TYPE, ModPayloads.ModRequest.CODEC);

        // ★ 探针：注册在 PLAY 阶段。服务端在玩家进世界（JOIN）时读 play 阶段的通道集合，
        //   那时通道协商一定已经完成；配置阶段过早，看到的集合是空的。
        PayloadTypeRegistry.clientboundPlay().register(ModPayloads.Probe.TYPE, ModPayloads.Probe.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ModPayloads.Probe.TYPE, ModPayloads.Probe.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(ModPayloads.Probe.TYPE, (payload, ctx) -> { });

        ClientSync.init();
    }
}
