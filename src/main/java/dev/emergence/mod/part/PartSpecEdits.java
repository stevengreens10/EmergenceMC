package dev.emergence.mod.part;

import dev.emergence.core.assembly.PartKind;
import dev.emergence.core.assembly.PartParameters;
import dev.emergence.mod.registry.ModRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * The part editor's server side: a player sets the material and parameters of the part item in their hand (what they
 * would make it of, and to what dimensions or ratings). Every way into a part's specification goes through
 * {@link #apply}, the player's screen and the demo builder alike, so whatever an exhibit is made of can be made by a
 * player — and checked against {@link PartParameters}, so only what the part can be is accepted.
 */
public final class PartSpecEdits {
    private PartSpecEdits() {}

    /** Sets the part item in the player's hand to {@code spec} if it is a valid one. Returns null if it was applied, else why not. */
    public static String apply(ServerPlayer player, InteractionHand hand, PartSpec spec) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof PartItem item)) return "not holding a part";
        String why = validate(item.kind(), spec);
        if (why != null) return why;
        stack.set(ModRegistries.PART_SPEC.get(), spec);
        return null;
    }

    /** Null if the specification is one this kind of part can have (a material it may be made of, its parameters in range). */
    public static String validate(PartKind kind, PartSpec spec) {
        return PartParameters.check(kind, spec.material(), spec.params());
    }
}
