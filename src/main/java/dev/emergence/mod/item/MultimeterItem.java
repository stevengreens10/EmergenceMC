package dev.emergence.mod.item;

import dev.emergence.core.assembly.Probe;
import dev.emergence.core.units.PhysicalConstants;
import dev.emergence.core.units.Units;
import dev.emergence.mod.block.AssemblyBlock;
import dev.emergence.mod.block.AssemblyBlockEntity;
import dev.emergence.mod.part.PlacedPart;
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
        int[] c = AssemblyBlock.cellAt(ctx.getClickedPos(), ctx.getClickLocation(), ctx.getClickedFace());
        PlacedPart part = be.partAt(c[0], c[1], c[2]);
        if (part == null) return InteractionResult.PASS;
        if (ctx.getLevel() instanceof ServerLevel sl && ctx.getPlayer() != null) {
            Probe pr = SimulationManager.get(sl).probe(ctx.getClickedPos(), part.id);
            report(ctx.getPlayer(), part, pr);
        }
        return InteractionResult.sidedSuccess(ctx.getLevel().isClientSide);
    }

    private static void report(Player player, PlacedPart part, Probe p) {
        MutableComponent head = Component.literal("▶ ").withStyle(ChatFormatting.GOLD)
                .append(Component.translatable("item.emergence." + part.kind.name().toLowerCase()).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" (" + part.spec.resolveMaterial().name() + ")").withStyle(ChatFormatting.GRAY));
        player.sendSystemMessage(head);
        if (p == null) {
            player.sendSystemMessage(Component.literal("  (not yet simulated)").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        line(player, "Temperature", String.format("%.1f °C (%.1f K)", p.temperature() - PhysicalConstants.T_ZERO_CELSIUS, p.temperature()));
        if (!Double.isNaN(p.voltage())) line(player, "Voltage", Units.formatSI(round(p.voltage()), "V"));
        if (!Double.isNaN(p.current())) line(player, "Current", Units.formatSI(round(p.current()), "A"));
        if (!Double.isNaN(p.power())) line(player, "Power", Units.formatSI(round(p.power()), "W"));
        if (p.bodyIndex() > 0) {
            line(player, "Speed", String.format("%.1f rpm (%.2f rad/s)", p.angularVelocity() * 60 / (2 * Math.PI), p.angularVelocity()));
        }
    }

    /** 4-significant-digit display. */
    private static double round(double v) {
        if (v == 0 || !Double.isFinite(v)) return v;
        double scale = Math.pow(10, 3 - Math.floor(Math.log10(Math.abs(v))));
        return Math.round(v * scale) / scale;
    }

    private static void line(Player player, String label, String value) {
        player.sendSystemMessage(Component.literal("  " + label + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(ChatFormatting.WHITE)));
    }
}
