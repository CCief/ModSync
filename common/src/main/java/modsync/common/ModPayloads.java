package modsync.common;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * ModSync 协议载荷定义（common 模块，被 server 与 client 各自打包）。
 *
 * 通道约定（mods 命名空间）：
 *   S2C  modsync:manifest_chunk   服务端 → 客户端：gzip 后的 mod 清单，分块
 *   C2S  modsync:mod_request      客户端 → 服务端：gzip 后的「我需要这些 mod」名单
 *   S2C  modsync:file_meta        服务端 → 客户端：文件元信息（名/大小/sha256）
 *   S2C  modsync:file_chunk       服务端 → 客户端：文件字节分块
 *   S2C  modsync:file_end         服务端 → 客户端：文件结束 + 期望 sha256
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

    /** String 编解码（UTF-8）。 */
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

    /** S2C：mod 清单的 gzip 分块。 */
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

    /**
     * 配置阶段探针（空载荷）。
     *
     * 客户端组件在 CONFIGURATION 阶段注册它，服务端在该阶段读「客户端注册的通道集合」时
     * 就能看到 modsync:probe，从而知道这台客户端具备自动同步能力。
     * 必须走配置阶段：play 阶段的通道在配置阶段尚未协商，服务端看不到。
     */
    public record Probe() implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<Probe> TYPE =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("modsync", "probe"));

        /** 空载荷：不带任何数据，手写编解码（ByteBufCodecs 里没有 VOID）。 */
        public static final StreamCodec<FriendlyByteBuf, Probe> CODEC = new StreamCodec<>() {
            @Override
            public Probe decode(FriendlyByteBuf buf) {
                return new Probe();
            }

            @Override
            public void encode(FriendlyByteBuf buf, Probe v) {
                // 空载荷，无需写入
            }
        };

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
