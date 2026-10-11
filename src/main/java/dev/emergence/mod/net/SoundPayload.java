package dev.emergence.mod.net;

import dev.emergence.mod.EmergenceMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client: what one island's sounding bodies sound like now (see {@code dev.emergence.core.acoustics.Tone}).
 * An island that falls silent is sent once with no sounds.
 *
 * @param island the island's key
 * @param metresPerBlock the island's scale: physical metres per world block (1/16 at block scale)
 */
public record SoundPayload(long island, float metresPerBlock, List<Source> sources) implements CustomPacketPayload {

    /** A sounding body: world position [blocks], the direction it moves along, its tone. */
    public record Source(int body, double x, double y, double z, float ax, float ay, float az, float frequency,
                         float rmsPressure, float[] amplitude, float[] phase) {}

    public static final Type<SoundPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(EmergenceMod.MODID, "sound"));

    public static final StreamCodec<FriendlyByteBuf, SoundPayload> STREAM_CODEC = StreamCodec.of(SoundPayload::write, SoundPayload::read);

    private static void write(FriendlyByteBuf buf, SoundPayload p) {
        buf.writeLong(p.island);
        buf.writeFloat(p.metresPerBlock);
        buf.writeVarInt(p.sources.size());
        for (Source s : p.sources) {
            buf.writeVarInt(s.body);
            buf.writeDouble(s.x);
            buf.writeDouble(s.y);
            buf.writeDouble(s.z);
            buf.writeFloat(s.ax);
            buf.writeFloat(s.ay);
            buf.writeFloat(s.az);
            buf.writeFloat(s.frequency);
            buf.writeFloat(s.rmsPressure);
            buf.writeVarInt(s.amplitude.length);
            for (int k = 0; k < s.amplitude.length; k++) {
                buf.writeFloat(s.amplitude[k]);
                buf.writeFloat(s.phase[k]);
            }
        }
    }

    private static SoundPayload read(FriendlyByteBuf buf) {
        long island = buf.readLong();
        float mpb = buf.readFloat();
        int n = buf.readVarInt();
        List<Source> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int body = buf.readVarInt();
            double x = buf.readDouble(), y = buf.readDouble(), z = buf.readDouble();
            float ax = buf.readFloat(), ay = buf.readFloat(), az = buf.readFloat(), f = buf.readFloat(), p = buf.readFloat();
            int h = buf.readVarInt();
            float[] amp = new float[h], ph = new float[h];
            for (int k = 0; k < h; k++) {
                amp[k] = buf.readFloat();
                ph[k] = buf.readFloat();
            }
            out.add(new Source(body, x, y, z, ax, ay, az, f, p, amp, ph));
        }
        return new SoundPayload(island, mpb, out);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
