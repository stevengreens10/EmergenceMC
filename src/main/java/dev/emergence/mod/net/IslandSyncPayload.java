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
 * for smooth interpolation between packets), temperatures of parts away from ambient (for glow) and
 * colours changed by chemistry (electrolyte concentrations, plated electrodes).
 * The server is authoritative; clients never simulate.
 */
public record IslandSyncPayload(long[] partIds, List<BodyState> bodies, long[] hotIds, float[] hotTemps, long gameTime,
                                float worldScale, long[] tintIds, int[] tints, long[] fluidIds, float[] gauges,
                                long[] pistonIds, float[] pistons, long[] springIds, float[] springEnds, float simRate,
                                long[] beamIds, float[] beams, long[] linkIds, float[] links)
        implements CustomPacketPayload {

    /**
     * A moving body: pivot and axis; angle and ω — or, for a slide, displacement [m] and velocity along the axis; for a
     * rotor riding a slide, a part of that slide (its pivot moves with it), else −1.
     */
    public record BodyState(double px, double py, double pz, double ax, double ay, double az,
                            double angle, double omega, long[] partIds, boolean slider, long carrier, float scale) {}

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
            buf.writeBoolean(b.slider);
            buf.writeLong(b.carrier);
            buf.writeFloat(b.scale);
        }
        buf.writeLongArray(p.hotIds);
        buf.writeVarInt(p.hotTemps.length);
        for (float t : p.hotTemps) buf.writeFloat(t);
        buf.writeLong(p.gameTime);
        buf.writeFloat(p.worldScale);
        buf.writeLongArray(p.tintIds);
        buf.writeVarIntArray(p.tints);
        buf.writeLongArray(p.fluidIds);
        buf.writeVarInt(p.gauges.length);
        for (float g : p.gauges) buf.writeFloat(g);
        buf.writeLongArray(p.pistonIds);
        buf.writeVarInt(p.pistons.length);
        for (float g : p.pistons) buf.writeFloat(g);
        buf.writeLongArray(p.springIds);
        buf.writeVarInt(p.springEnds.length);
        for (float g : p.springEnds) buf.writeFloat(g);
        buf.writeFloat(p.simRate);
        buf.writeLongArray(p.beamIds);
        buf.writeVarInt(p.beams.length);
        for (float g : p.beams) buf.writeFloat(g);
        buf.writeLongArray(p.linkIds);
        buf.writeVarInt(p.links.length);
        for (float g : p.links) buf.writeFloat(g);
    }

    private static IslandSyncPayload read(FriendlyByteBuf buf) {
        long[] all = buf.readLongArray();
        int n = buf.readVarInt();
        List<BodyState> bodies = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            bodies.add(new BodyState(buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readDouble(), buf.readFloat(), buf.readLongArray(), buf.readBoolean(), buf.readLong(),
                    buf.readFloat()));
        }
        long[] hot = buf.readLongArray();
        int m = buf.readVarInt();
        float[] temps = new float[m];
        for (int i = 0; i < m; i++) temps[i] = buf.readFloat();
        long time = buf.readLong();
        float scale = buf.readFloat();
        long[] tintIds = buf.readLongArray();
        int[] tints = buf.readVarIntArray();
        long[] fluidIds = buf.readLongArray();
        float[] gauges = new float[buf.readVarInt()];
        for (int i = 0; i < gauges.length; i++) gauges[i] = buf.readFloat();
        long[] pistonIds = buf.readLongArray();
        float[] pistons = new float[buf.readVarInt()];
        for (int i = 0; i < pistons.length; i++) pistons[i] = buf.readFloat();
        long[] springIds = buf.readLongArray();
        float[] springEnds = new float[buf.readVarInt()];
        for (int i = 0; i < springEnds.length; i++) springEnds[i] = buf.readFloat();
        float simRate = buf.readFloat();
        long[] beamIds = buf.readLongArray();
        float[] beams = new float[buf.readVarInt()];
        for (int i = 0; i < beams.length; i++) beams[i] = buf.readFloat();
        long[] linkIds = buf.readLongArray();
        float[] links = new float[buf.readVarInt()];
        for (int i = 0; i < links.length; i++) links[i] = buf.readFloat();
        return new IslandSyncPayload(all, bodies, hot, temps, time, scale, tintIds, tints, fluidIds, gauges, pistonIds, pistons,
                springIds, springEnds, simRate, beamIds, beams, linkIds, links);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
