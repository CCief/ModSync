package modsync;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * ModSync 全部网络载荷（26.x 新 CustomPacketPayload API，mojmap）。
 * 传输全部走 Minecraft 游戏连接（server-port=25565，内网穿透自动透传）。
 */
public final class ModPayloads {
    private ModPayloads() {}

    /** byte[] 编解码：varint 长度前缀 + 原始字节。 */
    public static final StreamCodec<FriendlyByteBuf, byte[]> BYTE_ARRAY = new StreamCodec<>() {
        @Override
        public byte[] decode(FriendlyByteBuf buf) {
            int len = buf.readVarInt();
            byte[] b = new byte[len];
            buf.readBytes(b);
            return b;
        }

        @Override
        public void encode(FriendlyByteBuf buf, byte[] b) {
            buf.writeVarInt(b.length);
            buf.writeBytes(b);
        }
    };

    /** String 编解码（UTF-8，上限 32767）。 */
    public static final StreamCodec<FriendlyByteBuf, String> STRING = new StreamCodec<>() {
        @Override
        public String decode(FriendlyByteBuf buf) {
            return buf.readUtf(32767);
        }

        @Override
        public void encode(FriendlyByteBuf buf, String s) {
            buf.writeUtf(s, 32767);
        }
    };

    /** long 编解码。 */
    public static final StreamCodec<FriendlyByteBuf, Long> LONG = new StreamCodec<>() {
        @Override
        public Long decode(FriendlyByteBuf buf) {
            return buf.readLong();
        }

        @Override
        public void encode(FriendlyByteBuf buf, Long v) {
            buf.writeLong(v);
        }
    };

    /** S2C：mod 清单的 gzip 分块（index/total/data）。 */
    public record ManifestChunk(int index, int total, byte[] data) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ManifestChunk> TYPE =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("modsync", "manifest_chunk"));

        public static final StreamCodec<FriendlyByteBuf, ManifestChunk> CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, ManifestChunk::index,
                ByteBufCodecs.INT, ManifestChunk::total,
                BYTE_ARRAY, ManifestChunk::data,
                ManifestChunk::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S：客户端请求的 mod 名列表（gzip 后的 JSON 数组字节）。 */
    public record ModRequest(byte[] namesJson) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ModRequest> TYPE =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("modsync", "mod_request"));

        public static final StreamCodec<FriendlyByteBuf, ModRequest> CODEC = StreamCodec.composite(
                BYTE_ARRAY, ModRequest::namesJson,
                ModRequest::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C：单个文件元信息。sha 为 "MISSING" 表示服务端没有该文件。 */
    public record FileMeta(String name, long size, String sha) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<FileMeta> TYPE =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("modsync", "file_meta"));

        public static final StreamCodec<FriendlyByteBuf, FileMeta> CODEC = StreamCodec.composite(
                STRING, FileMeta::name,
                LONG, FileMeta::size,
                STRING, FileMeta::sha,
                FileMeta::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C：单个文件的分块。 */
    public record FileChunk(String name, int index, int total, byte[] data) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<FileChunk> TYPE =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("modsync", "file_chunk"));

        public static final StreamCodec<FriendlyByteBuf, FileChunk> CODEC = StreamCodec.composite(
                STRING, FileChunk::name,
                ByteBufCodecs.INT, FileChunk::index,
                ByteBufCodecs.INT, FileChunk::total,
                BYTE_ARRAY, FileChunk::data,
                FileChunk::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C：单个文件传输结束 + sha256 校验值。 */
    public record FileEnd(String name, String sha) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<FileEnd> TYPE =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("modsync", "file_end"));

        public static final StreamCodec<FriendlyByteBuf, FileEnd> CODEC = StreamCodec.composite(
                STRING, FileEnd::name,
                STRING, FileEnd::sha,
                FileEnd::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
