package modsync.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
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

        // ★ 配置阶段探针：注册后服务端在 CONFIGURE 阶段就能看到本客户端具备自动同步能力。
        //   必须走 configuration 阶段 —— play 阶段的通道那时还没协商，服务端看不到。
        PayloadTypeRegistry.clientboundConfiguration().register(ModPayloads.Probe.TYPE, ModPayloads.Probe.CODEC);
        PayloadTypeRegistry.serverboundConfiguration().register(ModPayloads.Probe.TYPE, ModPayloads.Probe.CODEC);
        ClientConfigurationNetworking.registerGlobalReceiver(ModPayloads.Probe.TYPE, (payload, ctx) -> { });

        ClientSync.init();
    }
}
