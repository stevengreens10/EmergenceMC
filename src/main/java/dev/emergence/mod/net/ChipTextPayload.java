package dev.emergence.mod.net;

import dev.emergence.mod.EmergenceMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: write {@code text} (a design, a ROM's contents) onto the placed chip the part {@code partId} in the
 * block {@code pos} belongs to — every segment of it, its row lengthened or shortened if its pins changed (see
 * {@link dev.emergence.mod.part.ChipEdits}).
 */
public record ChipTextPayload(BlockPos pos, long partId, String text) implements CustomPacketPayload {
    /** The longest text a chip carries [characters]. */
    public static final int MAX = 32_000;

    public static final Type<ChipTextPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(EmergenceMod.MODID, "chip_text"));

    public static final StreamCodec<FriendlyByteBuf, ChipTextPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeBlockPos(p.pos);
                buf.writeLong(p.partId);
                buf.writeUtf(p.text, MAX);
            },
            buf -> new ChipTextPayload(buf.readBlockPos(), buf.readLong(), buf.readUtf(MAX)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
