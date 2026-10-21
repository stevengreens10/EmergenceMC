package dev.emergence.mod.part;

import dev.emergence.core.assembly.Dir;
import dev.emergence.core.assembly.PartKind;
import dev.emergence.core.materials.Material;
import dev.emergence.core.materials.MaterialProperty;
import dev.emergence.core.units.Units;
import dev.emergence.mod.block.AssemblyBlock;
import dev.emergence.mod.block.AssemblyBlockEntity;
import dev.emergence.mod.registry.ModRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * An item that places one primitive part.
 * <p>
 * <b>Block scale (default):</b> the part fills the block in front of the clicked face — one part per
 * block, each block a 6.25 cm cell of a magnified scale model. <b>Miniature</b> (precision tweezers in the
 * off-hand): the part goes into the 1/16-block cell grid at true scale. In both modes the part's axis is
 * the clicked face normal (or, while sneaking, the look direction), and bearings, gears, cranks and plates
 * clicked onto the side of a shaft are mounted coaxially on it.
 */
public final class PartItem extends Item {
    private final PartKind kind;

    public PartItem(PartKind kind, Properties props) {
        super(props);
        this.kind = kind;
    }

    public PartKind kind() {
        return kind;
    }

    public PartSpec spec(ItemStack stack) {
        PartSpec s = stack.get(ModRegistries.PART_SPEC.get());
        return s != null ? s : defaultSpec(kind);
    }

    /** A part as it comes before it is edited: the first stock variant of its kind ({@link dev.emergence.core.assembly.PartCatalog}). */
    public static PartSpec defaultSpec(PartKind k) {
        var p = dev.emergence.core.assembly.PartCatalog.of(k).get(0);
        return new PartSpec(p.material(), p.length(), 1, p.params());
    }

    @Override
    public Component getName(ItemStack stack) {
        PartSpec s = spec(stack);
        Material m = s.resolveMaterial();
        String size = s.cross() > 1 || (s.length() > 1 && kind != PartKind.SHAFT) ? " " + s.cross() + "×" + s.cross() + "×" + s.length() : "";
        return Component.translatable("item.emergence." + kind.name().toLowerCase())
                .append(Component.literal(" (" + m.name().split(" \\(")[0] + size + detail(s) + ")"));
    }

    /** A lens's focal length from the lensmaker's equation, for a lens one cell across. */
    private static String lensDetail(PartSpec s) {
        var core = new PlacedPart(0, PartKind.LENS, s, 0, 0, 0, 0, 0, 0, dev.emergence.core.assembly.Dir.UP, false)
                .toCore(net.minecraft.core.BlockPos.ZERO, 16);
        double n = s.resolveMaterial().get(dev.emergence.core.materials.MaterialProperty.REFRACTIVE_INDEX, 1.5);
        double f = dev.emergence.core.optics.Optics.focalLength(n, dev.emergence.core.assembly.PartGeometry.lensRadius1(core),
                dev.emergence.core.assembly.PartGeometry.lensRadius2(core), dev.emergence.core.assembly.PartGeometry.lensThickness(core));
        return String.format(", n %.3f, f %.0f mm", n, 1000 * f);
    }

    private String detail(PartSpec s) {
        var p = s.params();
        return switch (kind) {
            case COIL -> ", " + p.getOrDefault("turns", 200.0).intValue() + " turns" + (p.getOrDefault("core", 0.0) > 0 ? ", iron core" : "");
            case RESISTOR -> ", " + Units.formatSI(p.getOrDefault("resistance", 10.0), "Ω")
                    + (p.getOrDefault("ntc_beta", 0.0) > 0 ? String.format(" NTC thermistor, β %.0f K", p.get("ntc_beta")) : "");
            case CAPACITOR -> ", " + Units.formatSI(p.getOrDefault("capacitance", 1e-4), "F");
            case BATTERY -> String.format(", %s %.1f V, %.1f Ah", Math.round(p.getOrDefault("chemistry", 0.0)) == 1 ? "NiMH" : "lead–acid",
                    (Math.round(p.getOrDefault("chemistry", 0.0)) == 1 ? 1.2 : 2.0) * p.getOrDefault("cells", 6.0), p.getOrDefault("capacity", 7.0));
            case GEAR -> ", " + p.getOrDefault("teeth", 24.0).intValue() + " teeth";
            case WIRE, INSULATED_WIRE -> ", ⌀" + Units.formatSI(p.getOrDefault("diameter", 1.5e-3), "m")
                    + (p.getOrDefault("flexible", 0.0) > 0.5 ? ", flexible lead" : "");
            case BEARING -> p.getOrDefault("type", 0.0) >= 1 ? ", plain bushing" : ", ball";
            case SHAFT -> ", " + s.length() + " long";
            case PIPE -> ", ⌀" + Units.formatSI(p.getOrDefault("bore", 0.025), "m") + fluidLabel(p);
            case VALVE -> ", ⌀" + Units.formatSI(p.getOrDefault("bore", 0.025), "m") + fluidLabel(p)
                    + (p.containsKey("relief") ? String.format(", relief %.2f bar", p.get("relief") / 1e5) : "")
                    + (p.containsKey("lap") ? ", cam-worked" : "");
            case TANK -> fluidLabel(p) + (p.containsKey("pressure") && !p.keySet().stream().anyMatch(k -> k.startsWith("x_"))
                    ? String.format(", %.0f bar", p.get("pressure") / 1e5) : "")
                    + (p.containsKey("fill") ? String.format(", %.0f%% full", 100 * p.get("fill")) : "");
            case ROTOR -> String.format(", %.0f° blades%s", p.getOrDefault("blade_angle", 45.0), p.getOrDefault("pitch", 1.0) < 0 ? ", left-hand" : "");
            case CYLINDER -> ", ⌀" + Units.formatSI(p.getOrDefault("bore", 0.04), "m") + (p.getOrDefault("fins", 0.0) > 0 ? ", finned" : "");
            case ECCENTRIC -> ", throw " + Units.formatSI(p.getOrDefault("throw", 0.015), "m");
            case TRANSISTOR -> ", " + new String[]{"NPN", "PNP", "N-MOSFET", "P-MOSFET"}[(int) Math.max(0, Math.min(3, Math.round(p.getOrDefault("type", 0.0))))]
                    + (p.getOrDefault("photo", 0.0) > 0 ? " phototransistor" : "");
            case LED -> String.format(", %.0f nm", p.getOrDefault("wavelength", 630.0))
                    + (p.containsKey("face") ? ", facing " + dev.emergence.core.assembly.Dir.values()[(int) Math.round(p.get("face"))].name().toLowerCase() : "");
            case PHOTODIODE -> (p.getOrDefault("area", 7.5e-6) > 1e-3 ? ", solar cell " : ", ") + String.format("%.3g mm²", 1e6 * p.getOrDefault("area", 7.5e-6))
                    + ", facing " + dev.emergence.core.assembly.Dir.values()[(int) Math.round(p.getOrDefault("face", 1.0))].name().toLowerCase();
            case BRAKE -> String.format(", %.0f N spring", p.getOrDefault("force", 20.0));
            case POTENTIOMETER -> ", " + Units.formatSI(p.getOrDefault("resistance", 10_000.0), "Ω")
                    + String.format(", %.0f°", p.getOrDefault("travel", 270.0));
            case OPAMP -> ", " + Units.formatSI(p.getOrDefault("gbw", 3e6), "Hz") + " GBW";
            case SOURCE -> ", " + dev.emergence.core.nuclear.NuclearData.standard().byCode((int) Math.round(p.getOrDefault("nuclide", 0.0))).name()
                    + ", " + Units.formatSI(p.getOrDefault("activity", 3.7e5), "Bq");
            case GM_TUBE -> String.format(", %.0f V start", p.getOrDefault("start", 350.0));
            case SPRING -> String.format(", %.1f mm wire, %.0f turns", 1000 * p.getOrDefault("wire", 0.002), p.getOrDefault("turns", 10.0))
                    + (p.getOrDefault("preload", 0.0) > 0 ? String.format(", preloaded %.1f mm", 1000 * p.get("preload")) : "");
            case RACK -> ", teeth " + dev.emergence.core.assembly.Dir.values()[(int) Math.max(0, Math.min(5, Math.round(p.getOrDefault("face", 1.0))))].name().toLowerCase();
            case LINEAR_BEARING -> p.containsKey("travel") ? String.format(", stops ±%.1f mm", 1000 * p.get("travel")) : "";
            case NUT -> String.format(", %.0f mm lead%s", 1000 * p.getOrDefault("lead", 0.004), p.getOrDefault("left", 0.0) > 0 ? ", left-hand" : "");
            case MIRROR -> (p.getOrDefault("radius", 0.0) > 0 ? String.format(", concave, focus %.0f mm", 500 * p.get("radius"))
                    : p.getOrDefault("radius", 0.0) < 0 ? ", convex" : ", flat")
                    + (p.getOrDefault("tilt", 0.0) != 0 ? String.format(", leaning %.0f° %s", p.get("tilt"),
                    dev.emergence.core.assembly.Dir.values()[(int) Math.max(0, Math.min(5, Math.round(p.getOrDefault("tilt_toward", 0.0))))].name().toLowerCase()) : "");
            case LENS -> lensDetail(s);
            case BLADE -> String.format(", pitch %.0f°", p.getOrDefault("pitch", 0.0));
            case PRISM -> String.format(", apex %.0f°", p.getOrDefault("apex", 60.0))
                    + (p.containsKey("face") ? " pointing " + dev.emergence.core.assembly.Dir.values()[(int) Math.round(p.get("face"))].name().toLowerCase() : "");
            case CHIP -> ", " + (chipType(p) == dev.emergence.core.sim.elements.MsiChip.Type.RAM16X4 ? "RAM 16×4"
                    : chipType(p).name().toLowerCase().replace("rom16x8", "ROM 16×8").replace("4", " 4-bit"))
                    + (chipType(p) == dev.emergence.core.sim.elements.MsiChip.Type.ROM16X8 && p.containsKey("w0") ? ", programmed" : "");
            case LOGIC -> ", " + new String[]{"buffer", "NOT", "NAND", "NOR", "AND", "OR", "XOR", "Schmitt NOT", "D flip-flop"}[
                    (int) Math.max(0, Math.min(8, Math.round(p.getOrDefault("function", 2.0))))];
            default -> "";
        };
    }

    static dev.emergence.core.sim.elements.MsiChip.Type chipType(java.util.Map<String, Double> p) {
        var types = dev.emergence.core.sim.elements.MsiChip.Type.values();
        return types[(int) Math.max(0, Math.min(types.length - 1, Math.round(p.getOrDefault("function", 0.0))))];
    }

    private static String fluidLabel(java.util.Map<String, Double> p) {
        StringBuilder gas = new StringBuilder();
        double sum = 0;
        for (var e : p.entrySet()) if (e.getKey().startsWith("x_")) sum += e.getValue();
        for (var e : p.entrySet()) {
            if (!e.getKey().startsWith("x_") || e.getValue() <= 0) continue;
            gas.append(gas.length() == 0 ? ", " : " + ");
            if (e.getValue() < sum) gas.append(String.format("%.0f%% ", 100 * e.getValue() / sum));
            gas.append(e.getKey().substring(2));
        }
        if (gas.length() > 0) return gas + (p.containsKey("pressure") ? String.format(" at %.2f bar", p.get("pressure") / 1e5) : "");
        int code = (int) Math.round(p.getOrDefault("fluid", 0.0));
        return code == 0 ? "" : ", " + dev.emergence.core.fluids.FluidDatabase.standard().byCode(code).name().toLowerCase();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        PartSpec s = spec(stack);
        Material m = s.resolveMaterial();
        tooltip.add(Component.translatable("tooltip.emergence." + kind.name().toLowerCase()).withStyle(ChatFormatting.GRAY));
        if (kind == PartKind.CHIP) {
            var t = chipType(s.params());
            tooltip.add(Component.literal(t.length() + " segments in a row. Inputs (−side): " + String.join(" ", t.inputs)).withStyle(ChatFormatting.DARK_AQUA));
            tooltip.add(Component.literal("Outputs (+side): " + String.join(" ", t.outputs) + "; last segment: GND / V_DD").withStyle(ChatFormatting.DARK_AQUA));
        }
        tooltip.add(Component.literal(m.name()).withStyle(ChatFormatting.DARK_AQUA));
        if (m.has(MaterialProperty.ELECTRICAL_RESISTIVITY))
            tooltip.add(Component.literal("ρ = " + Units.formatSI(m.get(MaterialProperty.ELECTRICAL_RESISTIVITY), "Ω·m")).withStyle(ChatFormatting.DARK_GRAY));
        if (m.has(MaterialProperty.REMANENCE))
            tooltip.add(Component.literal("Bᵣ = " + Units.formatSI(m.get(MaterialProperty.REMANENCE), "T")).withStyle(ChatFormatting.DARK_GRAY));
        for (var e : s.params().entrySet()) {
            tooltip.add(Component.literal(e.getKey() + " = " + String.format("%.4g", e.getValue())).withStyle(ChatFormatting.DARK_GRAY));
        }
        tooltip.add(Component.translatable("tooltip.emergence.place_hint").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
    }

    /** Right-clicking the air with a part opens the part editor: its material, dimensions and ratings. */
    @Override
    public net.minecraft.world.InteractionResultHolder<ItemStack> use(Level level, Player player, net.minecraft.world.InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) dev.emergence.mod.client.ClientHooks.openPartEditor(kind, hand == net.minecraft.world.InteractionHand.OFF_HAND, spec(stack));
        return net.minecraft.world.InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        Player player = ctx.getPlayer();
        ItemStack stack = ctx.getItemInHand();
        PartSpec spec = spec(stack);
        Direction face = ctx.getClickedFace();
        Vec3 hit = ctx.getClickLocation();
        BlockPos clicked = ctx.getClickedPos();

        boolean miniature = player != null && player.getOffhandItem().is(ModRegistries.TWEEZERS.get());
        if (!miniature) return placeBlockScale(ctx, level, player, stack, spec, clicked, face);

        BlockPos pos;
        Dir axis;
        int cx, cy, cz; // anchor cell (local to pos)

        if (level.getBlockEntity(clicked) instanceof AssemblyBlockEntity cbe && cbe.resolution() == AssemblyBlockEntity.MINIATURE) {
            int[] c = AssemblyBlock.cellAt(cbe, hit, face);
            PlacedPart t = cbe.partAt(c[0], c[1], c[2]);
            boolean mountable = kind == PartKind.BEARING || kind == PartKind.POTENTIOMETER || kind == PartKind.GEAR || kind == PartKind.CRANK
                    || kind == PartKind.ROTOR || kind == PartKind.ECCENTRIC || (kind == PartKind.BLOCK && spec.cross() > 1);
            // clicking the side of a shaft mounts the part coaxially on that shaft cell
            if (t != null && t.kind == PartKind.SHAFT && mountable && face.getAxis().ordinal() != t.axis.axis()) {
                return place(ctx, level, player, stack, spec, clicked, t.axis, c[0], c[1], c[2], true);
            }
        }
        Vec3 p = hit.add(face.getStepX() * 0.01, face.getStepY() * 0.01, face.getStepZ() * 0.01);
        int gx = (int) Math.floor(p.x * 16), gy = (int) Math.floor(p.y * 16), gz = (int) Math.floor(p.z * 16);
        pos = new BlockPos(Math.floorDiv(gx, 16), Math.floorDiv(gy, 16), Math.floorDiv(gz, 16));
        cx = Math.floorMod(gx, 16);
        cy = Math.floorMod(gy, 16);
        cz = Math.floorMod(gz, 16);
        if (player != null && player.isShiftKeyDown()) {
            axis = Dir.values()[Direction.getNearest(player.getLookAngle().x, player.getLookAngle().y, player.getLookAngle().z).ordinal()];
        } else {
            axis = Dir.values()[face.ordinal()];
        }
        return place(ctx, level, player, stack, spec, pos, axis, cx, cy, cz, false);
    }

    private InteractionResult place(UseOnContext ctx, Level level, Player player, ItemStack stack, PartSpec spec, BlockPos pos,
                                    Dir axis, int cx, int cy, int cz, boolean mounted) {
        int a = axis.axis();
        int len = kind == PartKind.SHAFT || kind == PartKind.COIL || kind == PartKind.BLOCK ? spec.length() : 1;
        if (kind == PartKind.GEAR || kind == PartKind.BEARING || kind == PartKind.POTENTIOMETER || kind == PartKind.CRANK) len = 1;
        int cross = kind == PartKind.SHAFT || kind == PartKind.WIRE || kind == PartKind.INSULATED_WIRE ? 1 : spec.cross();
        int[] lo = new int[3], hi = new int[3];
        int[] c = {cx, cy, cz};
        for (int k = 0; k < 3; k++) {
            if (k == a) {
                if (axis.sign() > 0) {
                    lo[k] = c[k];
                    hi[k] = c[k] + len - 1;
                } else {
                    hi[k] = c[k];
                    lo[k] = c[k] - len + 1;
                }
            } else {
                lo[k] = c[k] - (cross - 1) / 2;
                hi[k] = lo[k] + cross - 1;
            }
            // shift into the block, keeping the anchor cell covered
            if (lo[k] < 0) {
                hi[k] -= lo[k];
                lo[k] = 0;
            }
            if (hi[k] > 15) {
                lo[k] -= hi[k] - 15;
                hi[k] = 15;
            }
            if (lo[k] < 0 || c[k] < lo[k] || c[k] > hi[k]) return fail(player, "message.emergence.does_not_fit");
        }
        BlockState state = level.getBlockState(pos);
        AssemblyBlockEntity be = level.getBlockEntity(pos) instanceof AssemblyBlockEntity e ? e : null;
        if (be == null && !state.canBeReplaced()) return fail(player, "message.emergence.blocked");
        if (be != null && (be.resolution() != AssemblyBlockEntity.MINIATURE || !be.canPlace(kind, lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]))) {
            return fail(player, "message.emergence.occupied");
        }
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (be == null) {
            level.setBlock(pos, ModRegistries.ASSEMBLY.get().defaultBlockState(), 3);
            be = level.getBlockEntity(pos) instanceof AssemblyBlockEntity e ? e : null;
            if (be == null) return InteractionResult.FAIL;
        }
        long id = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        be.addPart(new PlacedPart(id, kind, spec, lo[0], lo[1], lo[2], hi[0], hi[1], hi[2], axis, false));
        level.playSound(null, pos, SoundEvents.METAL_PLACE, SoundSource.BLOCKS, 0.6f, 1.4f);
        if (player == null || !player.getAbilities().instabuild) stack.shrink(1);
        return InteractionResult.CONSUME;
    }

    private boolean mountable(PartSpec spec) {
        return kind == PartKind.BEARING || kind == PartKind.POTENTIOMETER || kind == PartKind.MAGNET || kind == PartKind.GEAR
                || kind == PartKind.CRANK || kind == PartKind.BLOCK || kind == PartKind.ROTOR || kind == PartKind.ECCENTRIC
                || kind == PartKind.LINEAR_BEARING || kind == PartKind.NUT || kind == PartKind.COIL;
    }

    /** Block-scale placement: one part per block. */
    private InteractionResult placeBlockScale(UseOnContext ctx, Level level, Player player, ItemStack stack, PartSpec spec,
                                              BlockPos clicked, Direction face) {
        Dir axis = player != null && player.isShiftKeyDown()
                ? Dir.values()[Direction.getNearest(player.getLookAngle().x, player.getLookAngle().y, player.getLookAngle().z).ordinal()]
                : Dir.values()[face.ordinal()];
        BlockPos target;
        AssemblyBlockEntity be = null;
        // mount on the side of a block-scale shaft
        if (level.getBlockEntity(clicked) instanceof AssemblyBlockEntity cbe && cbe.resolution() == AssemblyBlockEntity.BLOCK_SCALE
                && mountable(spec)) {
            PlacedPart shaft = cbe.parts().stream().filter(p -> p.kind == PartKind.SHAFT).findFirst().orElse(null);
            if (shaft != null && face.getAxis().ordinal() != shaft.axis.axis() && cbe.canPlace(kind, 0, 0, 0, 0, 0, 0)) {
                // a magnet's poles, and (sneaking) a plate's face, point where chosen; anything else lies along the shaft,
                // facing whichever way along it the player looks (sneaking: a crank's handle on this side or that)
                boolean free = kind == PartKind.MAGNET || (kind == PartKind.BLOCK && player != null && player.isShiftKeyDown());
                Dir along = player != null && player.isShiftKeyDown() && axis.axis() == shaft.axis.axis() ? axis : shaft.axis;
                return finishBlockScale(level, player, stack, spec, clicked, cbe, free ? axis : along);
            }
        }
        target = level.getBlockState(clicked).canBeReplaced() && !(level.getBlockEntity(clicked) instanceof AssemblyBlockEntity)
                ? clicked : clicked.relative(face);
        if (level.getBlockEntity(target) instanceof AssemblyBlockEntity tbe) {
            if (tbe.resolution() != AssemblyBlockEntity.BLOCK_SCALE || !tbe.canPlace(kind, 0, 0, 0, 0, 0, 0)) {
                return fail(player, "message.emergence.occupied");
            }
            be = tbe;
            // a shaft placed into a block holding a mounted part follows that part's axis
            for (PlacedPart p : tbe.parts()) if (kind == PartKind.SHAFT) axis = p.axis;
        } else if (!level.getBlockState(target).canBeReplaced()) {
            return fail(player, "message.emergence.blocked");
        }
        return finishBlockScale(level, player, stack, spec, target, be, axis);
    }

    private InteractionResult finishBlockScale(Level level, Player player, ItemStack stack, PartSpec spec, BlockPos pos,
                                               AssemblyBlockEntity be, Dir axis) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (be == null) {
            level.setBlock(pos, ModRegistries.ASSEMBLY.get().defaultBlockState(), 3);
            be = level.getBlockEntity(pos) instanceof AssemblyBlockEntity e ? e : null;
            if (be == null) return InteractionResult.FAIL;
            be.setResolution(AssemblyBlockEntity.BLOCK_SCALE);
        }
        long id = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        be.addPart(new PlacedPart(id, kind, spec, 0, 0, 0, 0, 0, 0, axis, false));
        if (kind == PartKind.SHAFT) {
            // long shaft stock lays down one shaft cell per block along its axis, up to its length
            BlockPos next = pos;
            for (int i = 1; i < spec.length(); i++) {
                next = next.offset(axis.dx, axis.dy, axis.dz);
                if (!level.getBlockState(next).canBeReplaced() || level.getBlockEntity(next) != null) break;
                level.setBlock(next, ModRegistries.ASSEMBLY.get().defaultBlockState(), 3);
                if (!(level.getBlockEntity(next) instanceof AssemblyBlockEntity nbe)) break;
                nbe.setResolution(AssemblyBlockEntity.BLOCK_SCALE);
                nbe.addPart(new PlacedPart(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), kind, spec, 0, 0, 0, 0, 0, 0, axis, false));
            }
        }
        level.playSound(null, pos, SoundEvents.METAL_PLACE, SoundSource.BLOCKS, 0.7f, 1.0f);
        if (player == null || !player.getAbilities().instabuild) stack.shrink(1);
        return InteractionResult.CONSUME;
    }

    private static InteractionResult fail(Player player, String key) {
        if (player != null && player.level().isClientSide) player.displayClientMessage(Component.translatable(key), true);
        return InteractionResult.FAIL;
    }
}
