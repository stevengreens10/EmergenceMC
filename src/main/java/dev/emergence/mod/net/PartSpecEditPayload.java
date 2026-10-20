package dev.emergence.mod.net;

import dev.emergence.mod.EmergenceMod;
import dev.emergence.mod.part.PartSpec;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: the player set the part item in their hand ({@code offhand}: the off hand) to this specification in
 * the part editor. The server checks it ({@link dev.emergence.mod.part.PartSpecEdits}) before it changes the item.
 */
public record PartSpecEditPayload(boolean offhand, PartSpec spec) implements CustomPacketPayload {

    public static final Type<PartSpecEditPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(EmergenceMod.MODID, "part_spec_edit"));

    public static final StreamCodec<FriendlyByteBuf, PartSpecEditPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeBoolean(p.offhand);
                PartSpec.STREAM_CODEC.encode(buf, p.spec);
            },
            buf -> new PartSpecEditPayload(buf.readBoolean(), PartSpec.STREAM_CODEC.decode(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
