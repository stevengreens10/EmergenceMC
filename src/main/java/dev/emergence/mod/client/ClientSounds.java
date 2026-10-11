package dev.emergence.mod.client;

import dev.emergence.core.acoustics.Tone;
import dev.emergence.core.acoustics.ToneSynth;
import dev.emergence.mod.net.SoundPayload;
import dev.emergence.mod.registry.ModRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Plays the sounding bodies the server reports: one streamed sound per body, synthesised from its tone at the pressure
 * the player's ear receives — the compact dipole's far field, p·|cos θ|/r, r the physical distance (world blocks times
 * the island's metres per block) — and placed where the body is (stereo); Minecraft's own distance fade is off, the
 * physics does it. Sounds not reported for two seconds fade out.
 */
public final class ClientSounds {
    private ClientSounds() {
    }

    private static final int RATE = 44_100;
    private static final Map<Long, Voice> VOICES = new HashMap<>();

    /** One sounding body's sound. */
    static final class Voice extends AbstractTickableSoundInstance {
        final ToneSynth synth = new ToneSynth(RATE);
        Tone tone;
        Vec3 axis = new Vec3(1, 0, 0);
        double metresPerBlock = 1;
        long lastHeard;

        Voice(double x, double y, double z) {
            super(ModRegistries.TONE.get(), SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
            this.x = x;
            this.y = y;
            this.z = z;
            this.looping = false;
            this.attenuation = Attenuation.NONE;
            this.relative = false;
            this.volume = 1;
            this.pitch = 1;
        }

        void update() {
            var mc = Minecraft.getInstance();
            if (mc.player == null || tone == null) {
                synth.set(null, 0);
                return;
            }
            Vec3 ear = mc.gameRenderer.getMainCamera().getPosition(), d = ear.subtract(x, y, z);
            double rBlocks = d.length(), r = Math.max(rBlocks * metresPerBlock, 0.05);
            double cos = rBlocks < 1e-6 ? 1 : Math.abs(d.dot(axis)) / rBlocks;
            synth.set(tone, tone.rmsPressure() * cos / r);
        }

        @Override
        public void tick() {
            boolean fresh = System.currentTimeMillis() - lastHeard < 2000;
            if (!fresh) synth.set(null, 0);
            else update();
            if (!fresh && synth.silent()) stop();
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean loop) {
            ToneSynth s = synth;
            return CompletableFuture.completedFuture(new AudioStream() {
                private final AudioFormat format = new AudioFormat(RATE, 16, 1, true, false);

                @Override
                public AudioFormat getFormat() {
                    return format;
                }

                @Override
                public ByteBuffer read(int size) {
                    byte[] b = new byte[Math.max(2, size & ~1)];
                    s.render(b);
                    ByteBuffer out = BufferUtils.createByteBuffer(b.length);
                    out.put(b);
                    out.flip();
                    return out;
                }

                @Override
                public void close() {
                }
            });
        }
    }

    public static void apply(SoundPayload p) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        long now = System.currentTimeMillis();
        for (SoundPayload.Source s : p.sources()) {
            long key = p.island() * 1024 + s.body();
            Voice v = VOICES.get(key);
            if (v == null || v.isStopped()) {
                v = new Voice(s.x(), s.y(), s.z());
                VOICES.put(key, v);
                mc.getSoundManager().play(v);
            }
            double[] amp = new double[s.amplitude().length], ph = new double[s.phase().length];
            for (int k = 0; k < amp.length; k++) {
                amp[k] = s.amplitude()[k];
                ph[k] = s.phase()[k];
            }
            v.tone = new Tone(s.frequency(), s.rmsPressure(), amp, ph);
            v.axis = new Vec3(s.ax(), s.ay(), s.az());
            v.metresPerBlock = p.metresPerBlock();
            v.lastHeard = now;
            v.update();
        }
        VOICES.values().removeIf(Voice::isStopped);
    }
}
