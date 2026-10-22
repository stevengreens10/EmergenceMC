package dev.emergence.mod.block;

import com.mojang.serialization.MapCodec;
import dev.emergence.core.assembly.PartKind;
import dev.emergence.mod.part.PartPresets;
import dev.emergence.mod.part.PlacedPart;
import dev.emergence.mod.sim.SimulationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Invisible container block for sub-block parts. Its shape is the union of its parts; it is
 * rendered entirely by the block-entity renderer. Its light level follows incandescent parts.
 */
public final class AssemblyBlock extends BaseEntityBlock {
    public static final MapCodec<AssemblyBlock> CODEC = simpleCodec(AssemblyBlock::new);
    public static final IntegerProperty LIGHT = IntegerProperty.create("light", 0, 15);

    public AssemblyBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(LIGHT, 0));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(LIGHT);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AssemblyBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return level.getBlockEntity(pos) instanceof AssemblyBlockEntity be ? be.shape() : Shapes.block();
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return 1.0f;
    }

    /** Cell of the part under a hit location (in the block's own resolution), nudged inside the hit surface. */
    public static int[] cellAt(BlockPos pos, Vec3 hit, net.minecraft.core.Direction face, int res) {
        Vec3 p = hit.subtract(face.getStepX() * 0.01, face.getStepY() * 0.01, face.getStepZ() * 0.01);
        return new int[]{
                clamp((int) Math.floor((p.x - pos.getX()) * res), res),
                clamp((int) Math.floor((p.y - pos.getY()) * res), res),
                clamp((int) Math.floor((p.z - pos.getZ()) * res), res)};
    }

    public static int[] cellAt(AssemblyBlockEntity be, Vec3 hit, net.minecraft.core.Direction face) {
        return cellAt(be.getBlockPos(), hit, face, be.resolution());
    }

    private static int clamp(int v, int res) {
        return Math.max(0, Math.min(res - 1, v));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof AssemblyBlockEntity be)) return InteractionResult.PASS;
        PlacedPart part = be.partHit(hit.getLocation(), hit.getDirection());
        if (part == null) return InteractionResult.PASS;
        // with an empty hand, a part on a slide is pushed along its guide, a part on a turning body turns it (sneak:
        // the other way); with something in hand the click is the item's (a logger clipped on, a meter's probe)
        boolean bare = player.getMainHandItem().isEmpty();
        if (bare && level instanceof ServerLevel sl0 && SimulationManager.get(sl0).pushSlider(pos, part.id, player)) {
            return InteractionResult.SUCCESS;
        }
        if (part.kind == PartKind.CRANK || part.kind == PartKind.SWITCH || part.kind == PartKind.VALVE || part.kind == PartKind.BRAKE) {
            if (level instanceof ServerLevel sl) {
                SimulationManager sim = SimulationManager.get(sl);
                if (part.kind == PartKind.CRANK) {
                    sim.turnCrank(pos, part.id, player);
                } else {
                    boolean closed = sim.toggleSwitch(pos, part.id);
                    String key = part.kind == PartKind.VALVE ? (closed ? "message.emergence.valve_open" : "message.emergence.valve_shut")
                            : part.kind == PartKind.BRAKE ? (closed ? "message.emergence.brake_on" : "message.emergence.brake_off")
                            : (closed ? "message.emergence.switch_closed" : "message.emergence.switch_open");
                    player.displayClientMessage(Component.translatable(key), true);
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (bare && level instanceof ServerLevel sl1 && SimulationManager.get(sl1).turnBody(pos, part.id, player)) {
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    /**
     * Pick block (middle click): the part under the crosshair as an item, with all its settings — to build another
     * like it without setting it up again in the part editor.
     */
    @Override
    public ItemStack getCloneItemStack(BlockState state, net.minecraft.world.phys.HitResult target, net.minecraft.world.level.LevelReader level,
                                       BlockPos pos, Player player) {
        if (level.getBlockEntity(pos) instanceof AssemblyBlockEntity be && target instanceof BlockHitResult bh) {
            PlacedPart part = be.partHit(bh.getLocation(), bh.getDirection());
            if (part != null) return PartPresets.stack(part.kind, part.spec);
        }
        return ItemStack.EMPTY;
    }

    /** Removes the part the player is looking at; returns true if something was removed. */
    public static boolean removeTargetedPart(Level level, BlockPos pos, Player player) {
        if (!(level.getBlockEntity(pos) instanceof AssemblyBlockEntity be)) return false;
        var hr = player.pick(player.blockInteractionRange() + 1, 1.0f, false);
        if (Boolean.getBoolean("emergence.autoShowcase")) dev.emergence.mod.EmergenceMod.LOG.info("left click at {}: the server's pick from {} is on {}",
                pos, player.getEyePosition(), hr instanceof BlockHitResult b ? b.getBlockPos() + " " + b.getDirection() + " " + b.getLocation() : hr.getType());
        if (!(hr instanceof BlockHitResult bhr) || !bhr.getBlockPos().equals(pos)) return false;
        PlacedPart part = be.partHit(bhr.getLocation(), bhr.getDirection());
        if (part == null) return false;
        if (Boolean.getBoolean("emergence.autoShowcase")) dev.emergence.mod.EmergenceMod.LOG.info("{} took the {} out of {}", player.getName().getString(), part.kind, pos);
        be.removePart(part.id);
        if (!player.getAbilities().instabuild) {
            ItemStack drop = PartPresets.stack(part.kind, part.spec);
            if (!player.getInventory().add(drop)) Block.popResource(level, pos, drop);
        }
        if (be.parts().isEmpty()) level.removeBlock(pos, false);
        return true;
    }

    @Override
    protected BlockState updateShape(BlockState state, net.minecraft.core.Direction direction, BlockState neighborState,
                                     net.minecraft.world.level.LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (level instanceof net.minecraft.server.level.ServerLevel sl) {
            dev.emergence.mod.sim.SimulationManager.get(sl).neighbourChanged(pos, neighborPos, direction.getOpposite());
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof AssemblyBlockEntity be && !level.isClientSide) {
                for (PlacedPart p : be.parts()) Block.popResource(level, pos, PartPresets.stack(p.kind, p.spec));
            }
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
