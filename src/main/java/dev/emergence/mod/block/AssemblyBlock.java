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

    /** Cell (0..15)³ of the part under a hit location, nudged inside the surface that was hit. */
    public static int[] cellAt(BlockPos pos, Vec3 hit, net.minecraft.core.Direction face) {
        Vec3 p = hit.subtract(face.getStepX() * 0.01, face.getStepY() * 0.01, face.getStepZ() * 0.01);
        return new int[]{
                clamp((int) Math.floor((p.x - pos.getX()) * 16)),
                clamp((int) Math.floor((p.y - pos.getY()) * 16)),
                clamp((int) Math.floor((p.z - pos.getZ()) * 16))};
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(15, v));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof AssemblyBlockEntity be)) return InteractionResult.PASS;
        int[] c = cellAt(pos, hit.getLocation(), hit.getDirection());
        PlacedPart part = be.partAt(c[0], c[1], c[2]);
        if (part == null) return InteractionResult.PASS;
        if (part.kind == PartKind.CRANK || part.kind == PartKind.SWITCH) {
            if (level instanceof ServerLevel sl) {
                SimulationManager sim = SimulationManager.get(sl);
                if (part.kind == PartKind.CRANK) {
                    sim.turnCrank(pos, part.id, player);
                } else {
                    boolean closed = sim.toggleSwitch(pos, part.id);
                    player.displayClientMessage(Component.translatable(closed ? "message.emergence.switch_closed" : "message.emergence.switch_open"), true);
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }

    /** Removes the part the player is looking at; returns true if something was removed. */
    public static boolean removeTargetedPart(Level level, BlockPos pos, Player player) {
        if (!(level.getBlockEntity(pos) instanceof AssemblyBlockEntity be)) return false;
        var hr = player.pick(player.blockInteractionRange() + 1, 1.0f, false);
        if (!(hr instanceof BlockHitResult bhr) || !bhr.getBlockPos().equals(pos)) return false;
        int[] c = cellAt(pos, bhr.getLocation(), bhr.getDirection());
        PlacedPart part = be.partAt(c[0], c[1], c[2]);
        if (part == null) return false;
        be.removePart(part.id);
        if (!player.getAbilities().instabuild) {
            ItemStack drop = PartPresets.stack(part.kind, part.spec);
            if (!player.getInventory().add(drop)) Block.popResource(level, pos, drop);
        }
        if (be.parts().isEmpty()) level.removeBlock(pos, false);
        return true;
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
