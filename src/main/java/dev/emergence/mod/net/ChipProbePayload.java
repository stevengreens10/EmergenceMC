package dev.emergence.mod.net;

import dev.emergence.mod.EmergenceMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * A running chip seen from inside, for the chip designer and the ROM editor opened on a placed chip. The client asks
 * ({@link Request}, a few times a second while the screen is open); the server answers with what the simulation has:
 * whether it is powered, its supply, its pins' levels, its registers (a design's) or state words (a counter's, a
 * RAM's), its clock edges and timing violations.
 */
public record ChipProbePayload(long partId, boolean simulated, boolean powered, double supply, long edges, long violations,
                               boolean[] inputs, boolean[] outputs, List<String> names, long[] values, int[] widths)
        implements CustomPacketPayload {

    public static final Type<ChipProbePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(EmergenceMod.MODID, "chip_probe"));

    public static final StreamCodec<FriendlyByteBuf, ChipProbePayload> STREAM_CODEC = StreamCodec.of(ChipProbePayload::write, ChipProbePayload::read);

    private static void write(FriendlyByteBuf buf, ChipProbePayload p) {
        buf.writeLong(p.partId);
        buf.writeBoolean(p.simulated);
        buf.writeBoolean(p.powered);
        buf.writeDouble(p.supply);
        buf.writeVarLong(p.edges);
        buf.writeVarLong(p.violations);
        bits(buf, p.inputs);
        bits(buf, p.outputs);
        buf.writeVarInt(p.names.size());
        for (int i = 0; i < p.names.size(); i++) {
            buf.writeUtf(p.names.get(i), 64);
            buf.writeLong(p.values[i]);
            buf.writeVarInt(p.widths[i]);
        }
    }

    private static void bits(FriendlyByteBuf buf, boolean[] b) {
        buf.writeVarInt(b.length);
        for (boolean v : b) buf.writeBoolean(v);
    }

    private static boolean[] bits(FriendlyByteBuf buf) {
        boolean[] b = new boolean[Math.min(buf.readVarInt(), 256)];
        for (int i = 0; i < b.length; i++) b[i] = buf.readBoolean();
        return b;
    }

    private static ChipProbePayload read(FriendlyByteBuf buf) {
        long id = buf.readLong();
        boolean sim = buf.readBoolean(), pow = buf.readBoolean();
        double sup = buf.readDouble();
        long edges = buf.readVarLong(), viol = buf.readVarLong();
        boolean[] in = bits(buf), out = bits(buf);
        int n = Math.min(buf.readVarInt(), 512);
        List<String> names = new ArrayList<>(n);
        long[] values = new long[n];
        int[] widths = new int[n];
        for (int i = 0; i < n; i++) {
            names.add(buf.readUtf(64));
            values[i] = buf.readLong();
            widths[i] = buf.readVarInt();
        }
        return new ChipProbePayload(id, sim, pow, sup, edges, viol, in, out, names, values, widths);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client → server: what is the chip the part {@code partId} in block {@code pos} belongs to doing? */
    public record Request(BlockPos pos, long partId) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(EmergenceMod.MODID, "chip_probe_request"));

        public static final StreamCodec<FriendlyByteBuf, Request> STREAM_CODEC = StreamCodec.of(
                (buf, r) -> {
                    buf.writeBlockPos(r.pos);
                    buf.writeLong(r.partId);
                },
                buf -> new Request(buf.readBlockPos(), buf.readLong()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
