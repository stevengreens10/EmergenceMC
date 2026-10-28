package dev.emergence.mod.item;

import dev.emergence.mod.block.AssemblyBlockEntity;
import dev.emergence.mod.part.PlacedPart;
import dev.emergence.mod.sim.PartReadout;
import dev.emergence.mod.sim.SimulationManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

import java.util.List;

/**
 * A combined multimeter / thermocouple / optical tachometer. Readings are taken from the simulated
 * physical state and quantised to the instrument's resolution (3½-digit display, ±(0.5 % + 2 counts)
 * style accuracy is not yet modelled: readings are ideal apart from display rounding).
 */
public final class MultimeterItem extends Item {
    public MultimeterItem(Properties props) {
        super(props);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.emergence.multimeter").withStyle(ChatFormatting.GRAY));
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        if (!(ctx.getLevel().getBlockEntity(ctx.getClickedPos()) instanceof AssemblyBlockEntity be)) return InteractionResult.PASS;
        PlacedPart part = be.partHit(ctx.getClickLocation(), ctx.getClickedFace());
        if (part == null) return InteractionResult.PASS;
        if (ctx.getLevel() instanceof ServerLevel sl && ctx.getPlayer() != null) {
            Player player = ctx.getPlayer();
            MutableComponent head = Component.literal("▶ ").withStyle(ChatFormatting.GOLD)
                    .append(Component.translatable("item.emergence." + part.kind.name().toLowerCase()).withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal(" (" + part.spec.resolveMaterial().name() + ")").withStyle(ChatFormatting.GRAY));
            player.sendSystemMessage(head);
            var lines = PartReadout.of(SimulationManager.get(sl), ctx.getClickedPos(), part);
            if (lines.isEmpty()) player.sendSystemMessage(Component.literal("  (not yet simulated)").withStyle(ChatFormatting.DARK_GRAY));
            for (var l : lines) line(player, l.label(), l.value());
        }
        return InteractionResult.sidedSuccess(ctx.getLevel().isClientSide);
    }

    private static void line(Player player, String label, String value) {
        player.sendSystemMessage(Component.literal("  " + label + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(ChatFormatting.WHITE)));
    }
}
