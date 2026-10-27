package dev.emergence.mod.net;

import dev.emergence.mod.EmergenceMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → players near: what a display panel shows — 128×64 pixels as 8 pages of 128 column bytes (bit 0 at the top
 * of each), already as seen (off, inverted or all-on applied), and its brightness (the controller's contrast, 0–255).
 * Sent when the image changes, at most every other tick per panel, and now and then regardless (for players who came
 * since). {@code tile}: this panel's share of its screen's image — u at its left edge, v at its top, u at its right,
 * v at its bottom (panels side by side tile one screen).
 */
public record DisplaySyncPayload(long partId, byte[] image, int brightness, float[] tile) implements CustomPacketPayload {
    public static final Type<DisplaySyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EmergenceMod.MODID, "display_sync"));

    public static final StreamCodec<FriendlyByteBuf, DisplaySyncPayload> STREAM_CODEC = StreamCodec.of(
            DisplaySyncPayload::write, DisplaySyncPayload::read);

    private static void write(FriendlyByteBuf buf, DisplaySyncPayload p) {
        buf.writeLong(p.partId);
        buf.writeByteArray(p.image);
        buf.writeVarInt(p.brightness);
        for (int i = 0; i < 4; i++) buf.writeFloat(p.tile[i]);
    }

    private static DisplaySyncPayload read(FriendlyByteBuf buf) {
        long id = buf.readLong();
        byte[] img = buf.readByteArray(2048);
        int b = buf.readVarInt();
        float[] t = new float[4];
        for (int i = 0; i < 4; i++) t[i] = buf.readFloat();
        return new DisplaySyncPayload(id, img, b, t);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
