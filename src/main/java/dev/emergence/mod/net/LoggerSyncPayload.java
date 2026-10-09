package dev.emergence.mod.net;

import dev.emergence.mod.EmergenceMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → one player: the trace of the player's data logger for the oscilloscope display — the part it is
 * clipped to, the shown channel, the time span, and the peak-detect envelope (minimum and maximum per column)
 * of the newest samples.
 */
public record LoggerSyncPayload(String title, String channel, String unit, float span, float rate, float latest,
                                float[] lo, float[] hi) implements CustomPacketPayload {

    public static final Type<LoggerSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EmergenceMod.MODID, "logger_sync"));

    public static final StreamCodec<FriendlyByteBuf, LoggerSyncPayload> STREAM_CODEC = StreamCodec.of(
            LoggerSyncPayload::write, LoggerSyncPayload::read);

    private static void write(FriendlyByteBuf buf, LoggerSyncPayload p) {
        buf.writeUtf(p.title);
        buf.writeUtf(p.channel);
        buf.writeUtf(p.unit);
        buf.writeFloat(p.span);
        buf.writeFloat(p.rate);
        buf.writeFloat(p.latest);
        buf.writeVarInt(p.lo.length);
        for (int i = 0; i < p.lo.length; i++) {
            buf.writeFloat(p.lo[i]);
            buf.writeFloat(p.hi[i]);
        }
    }

    private static LoggerSyncPayload read(FriendlyByteBuf buf) {
        String title = buf.readUtf(), channel = buf.readUtf(), unit = buf.readUtf();
        float span = buf.readFloat(), rate = buf.readFloat(), latest = buf.readFloat();
        int n = buf.readVarInt();
        float[] lo = new float[n], hi = new float[n];
        for (int i = 0; i < n; i++) {
            lo[i] = buf.readFloat();
            hi[i] = buf.readFloat();
        }
        return new LoggerSyncPayload(title, channel, unit, span, rate, latest, lo, hi);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
