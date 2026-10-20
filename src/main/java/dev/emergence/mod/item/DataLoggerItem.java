package dev.emergence.mod.item;

import dev.emergence.mod.block.AssemblyBlock;
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
 * Data logger / oscilloscope. Clipped onto a part (use on it), it samples that part's temperature, current,
 * voltage, power, speed, angle and fluid state every millisecond of simulated time, keeping the last 65 s; while
 * held, its screen shows one channel against time (peak-detect, auto-ranged). Use in the air to change the
 * channel, sneak-use in the air to change the time base; {@code /emergence log export} saves the record as CSV.
 */
public final class DataLoggerItem extends Item {
    public DataLoggerItem(Properties props) {
        super(props);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.emergence.data_logger").withStyle(ChatFormatting.GRAY));
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        if (!(ctx.getLevel().getBlockEntity(ctx.getClickedPos()) instanceof AssemblyBlockEntity be)) return InteractionResult.PASS;
        PlacedPart part = be.partHit(ctx.getClickLocation(), ctx.getClickedFace());
        if (part == null) return InteractionResult.PASS;
        if (ctx.getLevel() instanceof ServerLevel sl && ctx.getPlayer() instanceof ServerPlayer sp) {
            String title = Component.translatable("item.emergence." + part.kind.name().toLowerCase()).getString()
                    + " (" + part.spec.resolveMaterial().name().split(" \\(")[0] + ")";
            SimulationManager.get(sl).attachLogger(sp, ctx.getClickedPos(), part.id, title);
            sp.displayClientMessage(Component.literal("Logging " + title + " at 1 kHz").withStyle(ChatFormatting.AQUA), true);
        }
        return InteractionResult.sidedSuccess(ctx.getLevel().isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp) {
            String msg = player.isShiftKeyDown() ? SimulationManager.get(sl).cycleLoggerSpan(sp) : SimulationManager.get(sl).cycleLoggerChannel(sp);
            if (msg != null) sp.displayClientMessage(Component.literal(msg).withStyle(ChatFormatting.AQUA), true);
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }
}
