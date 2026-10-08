package dev.emergence.mod.net;

import dev.emergence.mod.EmergenceMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client snapshot of one simulation island: rigid-body poses (for rendering rotation, with ω
 * for smooth interpolation between packets) and temperatures of parts away from ambient (for glow).
 * The server is authoritative; clients never simulate.
 */
public record IslandSyncPayload(long[] partIds, List<BodyState> bodies, long[] hotIds, float[] hotTemps, long gameTime)
        implements CustomPacketPayload {

    public record BodyState(double px, double py, double pz, double ax, double ay, double az,
                            double angle, double omega, long[] partIds) {}

    public static final Type<IslandSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EmergenceMod.MODID, "island_sync"));

    public static final StreamCodec<FriendlyByteBuf, IslandSyncPayload> STREAM_CODEC = StreamCodec.of(
            IslandSyncPayload::write, IslandSyncPayload::read);

    private static void write(FriendlyByteBuf buf, IslandSyncPayload p) {
        buf.writeLongArray(p.partIds);
        buf.writeVarInt(p.bodies.size());
        for (BodyState b : p.bodies) {
            buf.writeDouble(b.px);
            buf.writeDouble(b.py);
            buf.writeDouble(b.pz);
            buf.writeFloat((float) b.ax);
            buf.writeFloat((float) b.ay);
            buf.writeFloat((float) b.az);
            buf.writeDouble(b.angle);
            buf.writeFloat((float) b.omega);
            buf.writeLongArray(b.partIds);
        }
        buf.writeLongArray(p.hotIds);
        buf.writeVarInt(p.hotTemps.length);
        for (float t : p.hotTemps) buf.writeFloat(t);
        buf.writeLong(p.gameTime);
    }

    private static IslandSyncPayload read(FriendlyByteBuf buf) {
        long[] all = buf.readLongArray();
        int n = buf.readVarInt();
        List<BodyState> bodies = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            bodies.add(new BodyState(buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readDouble(), buf.readFloat(), buf.readLongArray()));
        }
        long[] hot = buf.readLongArray();
        int m = buf.readVarInt();
        float[] temps = new float[m];
        for (int i = 0; i < m; i++) temps[i] = buf.readFloat();
        return new IslandSyncPayload(all, bodies, hot, temps, buf.readLong());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
