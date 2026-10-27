package dev.emergence.mod.item;

import dev.emergence.mod.block.AssemblyBlockEntity;
import dev.emergence.mod.part.PlacedPart;
import dev.emergence.mod.sim.SimulationManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Logic analyser and oscilloscope: eight probes and a ground lead, clipped onto wires of one machine (use on a wire;
 * sneak-use: the ground lead). A probe on a net only chips touch records its level as it switches, to the nanosecond; on
 * any other node, its voltage at every step of the simulation. Use in the air: its screen — the traces against time,
 * zoom and pan, a trigger for a single capture, cursors.
 */
public final class LogicAnalyzerItem extends Item {
    /** Insulation colours by "color" 0–15, as drawn (AssemblyRenderer.INSULATION). */
    private static final String[] COLOURS = {"Blue", "Red", "Black", "Yellow", "Green", "White", "Orange", "Purple", "Brown",
            "Cyan", "Pink", "Grey", "Lime", "Navy", "Dark red", "Olive"};

    public LogicAnalyzerItem(Properties props) {
        super(props);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.emergence.logic_analyzer").withStyle(ChatFormatting.GRAY));
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        if (!(ctx.getLevel().getBlockEntity(ctx.getClickedPos()) instanceof AssemblyBlockEntity be)) return InteractionResult.PASS;
        PlacedPart part = be.partHit(ctx.getClickLocation(), ctx.getClickedFace());
        if (part == null) return InteractionResult.PASS;
        if (ctx.getLevel() instanceof ServerLevel sl && ctx.getPlayer() instanceof ServerPlayer sp) {
            String title = Component.translatable("item.emergence." + part.kind.name().toLowerCase()).getString();
            // an insulated wire by its colour: what tells one probed wire from another
            if (part.kind == dev.emergence.core.assembly.PartKind.INSULATED_WIRE || part.kind == dev.emergence.core.assembly.PartKind.RIBBON) {
                int k = (int) Math.round(part.spec.params().getOrDefault("color", 0.0));
                title = COLOURS[Math.floorMod(k, COLOURS.length)] + " wire";
            }
            String msg = SimulationManager.get(sl).clipAnalyzer(sp, ctx.getClickedPos(), part.id, title, sp.isShiftKeyDown());
            sp.displayClientMessage(Component.literal(msg).withStyle(ChatFormatting.AQUA), true);
        }
        return InteractionResult.sidedSuccess(ctx.getLevel().isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) dev.emergence.mod.client.ClientHooks.openAnalyzer();
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }
}
