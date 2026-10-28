package dev.emergence.mod.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.emergence.mod.EmergenceMod;
import dev.emergence.mod.net.DisplaySyncPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * The client's copy of each display panel's image, as a texture drawn on the panel's face (see AssemblyRenderer). The
 * texture is rebuilt on the render thread when a new image has come.
 */
public final class ClientDisplays {
    /** An OLED's colour when lit (a cool white). */
    static final int LIT = 0xFFE8F4FF;

    private static final class Entry {
        byte[] image;
        int brightness;
        float[] tile;
        boolean dirty = true;
        long updates;
        DynamicTexture tex;
        ResourceLocation loc;
    }

    private static final Map<Long, Entry> DISPLAYS = new HashMap<>();

    private ClientDisplays() {
    }

    public static void apply(DisplaySyncPayload p) {
        Entry e = DISPLAYS.computeIfAbsent(p.partId(), k -> new Entry());
        e.image = p.image();
        e.updates++;
        e.brightness = p.brightness();
        e.tile = p.tile();
        e.dirty = true;
    }

    /** Panel {@code partId}'s share of its screen's image (u left, v top, u right, v bottom), or null. */
    public static float[] tile(long partId) {
        Entry e = DISPLAYS.get(partId);
        return e == null ? null : e.tile;
    }

    /** A digest of panel {@code partId}'s image as this client last received it (0 if none), and how many it has had. */
    public static long[] received(long partId) {
        Entry e = DISPLAYS.get(partId);
        return e == null || e.image == null ? new long[]{0, 0} : new long[]{java.util.Arrays.hashCode(e.image), e.updates};
    }

    /** The texture showing panel {@code partId}'s image, or null if none has come yet. */
    public static ResourceLocation texture(long partId) {
        Entry e = DISPLAYS.get(partId);
        if (e == null || e.image == null || e.image.length < 1024) return null;
        if (e.tex == null) {
            e.tex = new DynamicTexture(128, 64, false);
            e.loc = Minecraft.getInstance().getTextureManager().register(EmergenceMod.MODID + "_display_" + Long.toHexString(partId), e.tex);
        }
        if (e.dirty) {
            NativeImage img = e.tex.getPixels();
            if (img != null) {
                float b = 0.35f + 0.65f * e.brightness / 255f;
                int r = Math.round(((LIT >> 16) & 0xff) * b), g = Math.round(((LIT >> 8) & 0xff) * b), bl = Math.round((LIT & 0xff) * b);
                int lit = 0xFF000000 | (bl << 16) | (g << 8) | r; // NativeImage is ABGR
                int dark = 0xFF0A0806;
                for (int y = 0; y < 64; y++) {
                    for (int x = 0; x < 128; x++) {
                        boolean on = ((e.image[(y >> 3) * 128 + x] >> (y & 7)) & 1) != 0;
                        img.setPixelRGBA(x, y, on ? lit : dark);
                    }
                }
                e.tex.upload();
            }
            e.dirty = false;
        }
        return e.loc;
    }
}
