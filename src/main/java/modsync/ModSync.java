package modsync;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * ModSync 通用入口（服务端/客户端都跑，按 EnvType 分流）。
 * 在通用入口注册全部 payload 编解码（双向），保证任一端都能编/解码。
 */
public class ModSync implements ModInitializer {
    public static final String MOD_ID = "modsync";

    @Override
    public void onInitialize() {
        Config.load();

        // 双向编解码注册（两端的 common init 都会执行，各自具备编码与解码能力）
        PayloadTypeRegistry.clientboundPlay().register(ModPayloads.ManifestChunk.TYPE, ModPayloads.ManifestChunk.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ModPayloads.FileMeta.TYPE, ModPayloads.FileMeta.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ModPayloads.FileChunk.TYPE, ModPayloads.FileChunk.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ModPayloads.FileEnd.TYPE, ModPayloads.FileEnd.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ModPayloads.ModRequest.TYPE, ModPayloads.ModRequest.CODEC);

        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
            // 客户端请求差异 mod 列表
            ServerPlayNetworking.registerGlobalReceiver(ModPayloads.ModRequest.TYPE, (payload, ctx) ->
                    ctx.server().execute(() -> ServerSync.handleRequest(ctx.player(), payload)));
            // 玩家加入即下发服务端 mod 清单
            ServerPlayConnectionEvents.JOIN.register((ServerGamePacketListenerImpl handler,
                                                      net.fabricmc.fabric.api.networking.v1.PacketSender sender,
                                                      net.minecraft.server.MinecraftServer server) -> {
                ServerPlayer player = handler.getPlayer();
                ServerSync.sendManifest(player);
            });
        } else {
            ClientSync.init();
        }
    }
}
