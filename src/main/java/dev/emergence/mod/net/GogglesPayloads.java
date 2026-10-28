package dev.emergence.mod.net;

import dev.emergence.mod.EmergenceMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** The engineer's goggles: the client asks for the readings of the part looked at, the server answers. */
public final class GogglesPayloads {
    private GogglesPayloads() {
    }

    /** Client → server: the part in block {@code pos} the wearer is looking at. */
    public record Query(BlockPos pos, long partId) implements CustomPacketPayload {
        public static final Type<Query> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(EmergenceMod.MODID, "goggles_query"));
        public static final StreamCodec<FriendlyByteBuf, Query> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeBlockPos(p.pos);
                    buf.writeVarLong(p.partId);
                },
                buf -> new Query(buf.readBlockPos(), buf.readVarLong()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server → client: that part's readings (labels and values; none if its machine is not simulated yet). */
    public record Data(BlockPos pos, long partId, String[] labels, String[] values) implements CustomPacketPayload {
        public static final int MAX_LINES = 24;
        public static final Type<Data> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(EmergenceMod.MODID, "goggles_data"));
        public static final StreamCodec<FriendlyByteBuf, Data> STREAM_CODEC = StreamCodec.of(
                (buf, d) -> {
                    buf.writeBlockPos(d.pos);
                    buf.writeVarLong(d.partId);
                    buf.writeVarInt(d.labels.length);
                    for (int i = 0; i < d.labels.length; i++) {
                        buf.writeUtf(d.labels[i], 64);
                        buf.writeUtf(d.values[i], 160);
                    }
                },
                buf -> {
                    BlockPos pos = buf.readBlockPos();
                    long id = buf.readVarLong();
                    int n = Math.min(MAX_LINES, buf.readVarInt());
                    String[] l = new String[n], v = new String[n];
                    for (int i = 0; i < n; i++) {
                        l[i] = buf.readUtf(64);
                        v[i] = buf.readUtf(160);
                    }
                    return new Data(pos, id, l, v);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
