package dev.emergence.mod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.emergence.core.assembly.Dir;
import dev.emergence.core.assembly.PartKind;
import dev.emergence.core.materials.Material;
import dev.emergence.mod.block.AssemblyBlockEntity;
import dev.emergence.mod.client.model.ObjModel;
import dev.emergence.mod.client.model.PartModels;
import dev.emergence.mod.part.PlacedPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;

/**
 * Renders the parts of an assembly block from the Blender-authored part meshes (bearings, coils,
 * magnets, components...) and procedurally generated geometry (gears from their tooth count and pitch
 * radius, shafts and wires at their diameters). Works at both grid resolutions: one part per block at
 * block scale, 16³ cells per block in miniature mode.
 * <p>
 * Parts on a moving rigid body rotate with it; hot parts take their black-body colour; switch blades
 * show their state; resistors carry their colour code; electrolyte jars show the solution's colour.
 * Meshing gears are phased so their teeth interleave.
 */
public final class AssemblyRenderer implements BlockEntityRenderer<AssemblyBlockEntity> {
    /** Smallest drawn wire/shaft radius [cells], so fine conductors stay visible. */
    private static final float MIN_WIRE_RADIUS = 0.07f, MIN_SHAFT_RADIUS = 0.09f;

    public AssemblyRenderer(BlockEntityRendererProvider.Context ctx) {
    }

    @Override
    public boolean shouldRenderOffScreen(AssemblyBlockEntity be) {
        return true;
    }

    /** Rotating parts (gears, crank arms) may sweep well outside their own block. */
    @Override
    public AABB getRenderBoundingBox(AssemblyBlockEntity be) {
        return new AABB(be.getBlockPos()).inflate(be.resolution() == AssemblyBlockEntity.BLOCK_SCALE ? 4 : 1);
    }

    @Override
    public int getViewDistance() {
        return 96;
    }

    // ------------------------------------------------------------------ per-part setup

    /** Everything one draw call needs. */
    private static final class Ctx {
        PoseStack ps;
        VertexConsumer solid, glass;
        int light;
        int glowRgb = -1; // −1: not glowing
        float glowMix;
        int materialRgb;
        String materialTexture;
    }

    @Override
    public void render(AssemblyBlockEntity be, float partialTick, PoseStack ps, MultiBufferSource buffers, int light, int overlay) {
        Level level = be.getLevel();
        if (level == null) return;
        Ctx c = new Ctx();
        c.ps = ps;
        c.solid = buffers.getBuffer(Sheets.solidBlockSheet());
        BlockPos pos = be.getBlockPos();
        double now = level.getGameTime() + partialTick;
        int res = be.resolution();
        float C = 1f / res; // one grid cell in blocks
        boolean glassPending = false;
        for (int pass = 0; pass < 2; pass++) {
            // pass 0: opaque geometry; pass 1: translucent (jars) — keeps the buffer switches minimal
            if (pass == 1) {
                if (!glassPending) break;
                c.glass = buffers.getBuffer(Sheets.translucentCullBlockSheet());
            }
            for (PlacedPart p : be.parts()) {
                if (pass == 1 && p.kind != PartKind.ELECTROLYTE) continue;
                if (p.kind == PartKind.ELECTROLYTE) glassPending = true;
                ps.pushPose();
                ClientSimState.Pose pose = ClientSimState.pose(p.id);
                double bodyAngle = 0;
                if (pose != null) {
                    bodyAngle = pose.angle() + pose.omega() * (now - pose.time()) / 20.0;
                    float lx = (float) (pose.px() - pos.getX()), ly = (float) (pose.py() - pos.getY()), lz = (float) (pose.pz() - pos.getZ());
                    ps.translate(lx, ly, lz);
                    ps.mulPose(new Quaternionf().rotateAxis((float) bodyAngle, (float) pose.ax(), (float) pose.ay(), (float) pose.az()));
                    ps.translate(-lx, -ly, -lz);
                }
                Material m = p.spec.resolveMaterial();
                c.materialRgb = ClientSimState.colour(p.id, m.color());
                c.materialTexture = textureFor(m);
                float temp = ClientSimState.temperature(p.id, 293f);
                c.light = light;
                c.glowRgb = -1;
                if (temp > 700) {
                    c.glowRgb = blackBody(temp);
                    c.glowMix = (float) Math.min(1.0, Math.pow(Math.max(0, (temp - 700) / 600.0), 1.2));
                    if (temp > 798) c.light = LightTexture.FULL_BRIGHT;
                }
                // part frame: origin at the box centre, model Z = part axis, model X = lead/reference axis
                float cx = (p.x0 + p.x1 + 1) * 0.5f * C, cy = (p.y0 + p.y1 + 1) * 0.5f * C, cz = (p.z0 + p.z1 + 1) * 0.5f * C;
                ps.translate(cx, cy, cz);
                Frame f = frame(p);
                ps.last().pose().mul(f.m4);
                ps.last().normal().mul(f.m3);
                ps.scale(C, C, C);
                float[] ext = {p.x1 - p.x0 + 1, p.y1 - p.y0 + 1, p.z1 - p.z0 + 1};
                float ex = ext[f.xAxis], ey = ext[f.yAxis], ez = ext[f.zAxis]; // extents in model axes [cells]
                if (pass == 0) drawPart(c, be, p, f, ex, ey, ez, bodyAngle, pose);
                else drawModel(c, "electrolyte", p, ex, ey, ez, true);
                ps.popPose();
            }
        }
    }

    /** Orthonormal part frame: columns X (reference/lead axis), Y = Z × X, Z (signed part axis). */
    private record Frame(Matrix4f m4, Matrix3f m3, int xAxis, int yAxis, int zAxis, float zSign) {}

    private static Frame frame(PlacedPart p) {
        int a = p.axis.axis();
        int def = a == 0 ? 2 : 0;
        int l = (int) Math.round(p.spec.params().getOrDefault("lead_axis", (double) def));
        if (l == a || l < 0 || l > 2) l = def;
        Vector3f z = new Vector3f(p.axis.dx, p.axis.dy, p.axis.dz);
        Vector3f x = new Vector3f(l == 0 ? 1 : 0, l == 1 ? 1 : 0, l == 2 ? 1 : 0);
        Vector3f y = new Vector3f(z).cross(x);
        if (p.kind == PartKind.SWITCH && a != 1) {
            // a knife switch lying on its side: base down (model −Y), whatever the lead axis
            y.set(0, 1, 0);
            x.set(new Vector3f(y).cross(z));
            l = Math.abs(x.x) > 0.5f ? 0 : 2;
        }
        Matrix3f m3 = new Matrix3f(x, y, z);
        Matrix4f m4 = new Matrix4f().set3x3(m3);
        int yAxis = 3 - a - l;
        return new Frame(m4, m3, l, yAxis, a, p.axis.sign());
    }

    private void drawPart(Ctx c, AssemblyBlockEntity be, PlacedPart p, Frame f, float ex, float ey, float ez,
                          double bodyAngle, ClientSimState.Pose pose) {
        var prm = p.spec.params();
        switch (p.kind) {
            case BLOCK -> {
                float fill = (float) (double) prm.getOrDefault("fill", 1.0);
                drawModel(c, "plate", p, ex, ey, ez * fill, false);
            }
            case MAGNET -> {
                // magnet body at its physical size (default 80 % of its cells); the model is 0.8 cells
                float[] size = new float[3];
                String[] key = {"sx", "sy", "sz"};
                float[] ext = {p.x1 - p.x0 + 1, p.y1 - p.y0 + 1, p.z1 - p.z0 + 1};
                for (int k = 0; k < 3; k++) {
                    double s = prm.getOrDefault(key[k], 0.8 * ext[k] / 16.0) * 16.0;
                    size[k] = (float) Math.min(s, ext[k]) / 0.8f;
                }
                drawModel(c, "magnet", p, size[f.xAxis], size[f.yAxis], size[f.zAxis], false);
            }
            case BEARING, BRUSH, RESISTOR, CAPACITOR, DIODE -> drawModel(c, p.kind.name().toLowerCase(), p,
                    Math.min(ex, ey), Math.min(ex, ey), ez, false);
            case COIL -> drawModel(c, "coil", p, ex, ey, ez, false);
            case SWITCH -> drawModel(c, "switch", p, 1, 1, ez, false);
            case CRANK -> drawModel(c, "crank", p, 1, 1, 1, false);
            case SHAFT -> {
                double d = prm.getOrDefault("diameter", 0.02) * 16.0;
                float r = (float) Math.max(Math.min(d / 2, 0.475 * Math.min(ex, ey)), MIN_SHAFT_RADIUS);
                c.ps.pushPose();
                TextureAtlasSprite s = PartModels.sprite("metal_smooth");
                cylinder(c, s, tintOf(c, c.materialRgb), r, -ez / 2, ez / 2, 16, true);
                // keyway flat marks rotation
                box(c, PartModels.sprite("metal_cast"), tintOf(c, darken(c.materialRgb, 0.7f)),
                        r * 0.55f, -r * 0.18f, -ez / 2 + 0.02f, r * 1.02f, r * 0.18f, ez / 2 - 0.02f);
                c.ps.popPose();
            }
            case GEAR -> drawGear(c, be, p, f, ez, bodyAngle, pose);
            case WIRE, INSULATED_WIRE -> drawWire(c, be, p, f);
            case ELECTROLYTE -> {
                // the jar's glass and liquid go in the translucent pass
            }
        }
    }

    // ------------------------------------------------------------------ OBJ models

    private static final int[] RESISTOR_COLOURS = {0x1A1A1A, 0x7B4A1E, 0xC0281E, 0xE8781E, 0xF0D020, 0x2E9A3A,
            0x2050C0, 0x8040B0, 0x8A8A8A, 0xF0F0F0};
    private static final int GOLD = 0xC8A030, SILVER = 0xC0C0C8;

    /** Colours of the four resistor bands (two digits, multiplier, 5 % tolerance) for resistance R. */
    static int[] resistorBands(double ohms) {
        if (!(ohms > 0)) return new int[]{0x1A1A1A, 0x1A1A1A, 0x1A1A1A, GOLD};
        int e = (int) Math.floor(Math.log10(ohms)) - 1;
        long d = Math.round(ohms / Math.pow(10, e));
        if (d >= 100) {
            d /= 10;
            e++;
        }
        int mult = e >= 0 && e <= 9 ? RESISTOR_COLOURS[e] : e == -1 ? GOLD : SILVER;
        return new int[]{RESISTOR_COLOURS[(int) (d / 10) % 10], RESISTOR_COLOURS[(int) (d % 10)], mult, GOLD};
    }

    private void drawModel(Ctx c, String name, PlacedPart p, float sx, float sy, float sz, boolean translucentPass) {
        ObjModel model = PartModels.model(name);
        if (model == null) return;
        c.ps.pushPose();
        c.ps.scale(sx, sy, sz);
        int[] bands = p.kind == PartKind.RESISTOR ? resistorBands(p.spec.params().getOrDefault("resistance", 10.0)) : null;
        double crankR = p.kind == PartKind.CRANK ? p.spec.params().getOrDefault("radius", 0.15) * 16.0 : 1;
        for (ObjModel.Piece piece : model.pieces) {
            String[] mt = piece.material().split("__", 2);
            String flags = mt.length > 1 ? mt[1] : "";
            boolean glass = flags.contains("glass");
            if (glass != translucentPass) continue;
            String tex = mt[0].equals("material") ? c.materialTexture : mt[0];
            int rgb = 0xFFFFFF;
            if (flags.contains("tint")) rgb = c.materialRgb;
            if (piece.name().startsWith("resistor_band") && bands != null) {
                rgb = bands[Math.max(0, Math.min(3, piece.name().charAt(13) - '1'))];
            }
            if (mt[0].equals("material") && !tintable(c.materialTexture)) rgb = 0xFFFFFF;
            int alpha = glass ? (mt[0].equals("liquid") ? 200 : 110) : 255;
            c.ps.pushPose();
            switch (piece.name()) {
                case "crank_arm" -> c.ps.scale((float) crankR, 1, 1);
                case "crank_grip", "crank_knob", "crank_pin" -> c.ps.translate((float) crankR - 1, 0, 0);
                case "switch_blade", "switch_handle" -> {
                    if (!p.closed) {
                        c.ps.translate(0, -0.24f, -0.3f);
                        c.ps.mulPose(new Quaternionf().rotateX((float) Math.toRadians(-65)));
                        c.ps.translate(0, 0.24f, 0.3f);
                    }
                }
                default -> {
                }
            }
            emit(c, glass ? c.glass : c.solid, piece, PartModels.sprite(tex), tintOf(c, rgb), alpha);
            c.ps.popPose();
        }
        c.ps.popPose();
    }

    private static void emit(Ctx c, VertexConsumer vc, ObjModel.Piece piece, TextureAtlasSprite s, int rgb, int alpha) {
        PoseStack.Pose last = c.ps.last();
        Matrix4f m = last.pose();
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        float[] q = piece.quads();
        for (int i = 0; i < q.length; i += 8) {
            vc.addVertex(m, q[i], q[i + 1], q[i + 2]).setColor(r, g, b, alpha)
                    .setUv(s.getU(q[i + 6]), s.getV(1 - q[i + 7])).setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(c.light).setNormal(last, q[i + 3], q[i + 4], q[i + 5]);
        }
    }

    // ------------------------------------------------------------------ gears

    private void drawGear(Ctx c, AssemblyBlockEntity be, PlacedPart p, Frame f, float ez, double bodyAngle, ClientSimState.Pose pose) {
        var prm = p.spec.params();
        float transverse = Math.min(p.x1 - p.x0 + 1, Math.min(p.y1 - p.y0 + 1, p.z1 - p.z0 + 1));
        if (p.axis.axis() == 0) transverse = Math.min(p.y1 - p.y0 + 1, p.z1 - p.z0 + 1);
        else if (p.axis.axis() == 1) transverse = Math.min(p.x1 - p.x0 + 1, p.z1 - p.z0 + 1);
        else transverse = Math.min(p.x1 - p.x0 + 1, p.y1 - p.y0 + 1);
        float rp = (float) (prm.getOrDefault("pitch_radius", 0.5 * transverse / 16.0) * 16.0);
        int z = (int) Math.round(prm.getOrDefault("teeth", 24.0));
        z = Math.max(4, Math.min(z, 400));
        float th = (float) Math.min(prm.getOrDefault("thickness", 0.8 * ez / 16.0) * 16.0, ez);
        float mod = 2 * rp / z;
        float ra = rp + mod, rr = Math.max(rp - 1.25f * mod, rp * 0.3f);
        float phase = GearPhasing.phase(be, p, rp, z, bodyAngle, pose);
        int tint = tintOf(c, c.materialRgb);
        int tintDark = tintOf(c, darken(c.materialRgb, 0.82f));
        TextureAtlasSprite face = PartModels.sprite("metal_brushed"), cast = PartModels.sprite("metal_cast");
        float rim = Math.max(rr - Math.max(2.2f * mod, 0.08f * rp), 0);
        float hub = Math.min(Math.max(0.16f, 0.32f * rp), 0.42f);
        boolean webbed = rim > hub * 1.4f;
        float inner = webbed ? rim : 0;
        c.ps.pushPose();
        c.ps.mulPose(new Quaternionf().rotateZ(phase));
        // toothed ring (or solid toothed disk) at full face width
        int n = z * 4;
        float[] ox = new float[n + 1], oy = new float[n + 1];
        float pitch = (float) (2 * Math.PI / z);
        float[] fr = {-0.27f, -0.11f, 0.11f, 0.27f};
        for (int t = 0; t < z; t++) {
            for (int k = 0; k < 4; k++) {
                float ang = (t + fr[k]) * pitch;
                float rad = (k == 0 || k == 3) ? rr : ra;
                ox[t * 4 + k] = rad * (float) Math.cos(ang);
                oy[t * 4 + k] = rad * (float) Math.sin(ang);
            }
        }
        ox[n] = ox[0];
        oy[n] = oy[0];
        for (int i = 0; i < n; i++) {
            float a0 = (float) Math.atan2(oy[i], ox[i]), a1 = (float) Math.atan2(oy[i + 1], ox[i + 1]);
            float ix0 = inner * (float) Math.cos(a0), iy0 = inner * (float) Math.sin(a0);
            float ix1 = inner * (float) Math.cos(a1), iy1 = inner * (float) Math.sin(a1);
            // faces (±Z)
            quad(c, face, tint, ix0, iy0, th / 2, ox[i], oy[i], th / 2, ox[i + 1], oy[i + 1], th / 2, ix1, iy1, th / 2, 0, 0, 1);
            quad(c, face, tint, ix1, iy1, -th / 2, ox[i + 1], oy[i + 1], -th / 2, ox[i], oy[i], -th / 2, ix0, iy0, -th / 2, 0, 0, -1);
            // outer flank / tip / root land
            float nx = oy[i + 1] - oy[i], ny = ox[i] - ox[i + 1];
            float nl = (float) Math.sqrt(nx * nx + ny * ny) + 1e-9f;
            quad(c, cast, tintDark, ox[i], oy[i], -th / 2, ox[i + 1], oy[i + 1], -th / 2, ox[i + 1], oy[i + 1], th / 2, ox[i], oy[i], th / 2,
                    nx / nl, ny / nl, 0);
            if (webbed) {
                quad(c, cast, tintDark, ix1, iy1, -th / 2, ix0, iy0, -th / 2, ix0, iy0, th / 2, ix1, iy1, th / 2,
                        -(ix0 + ix1) / 2 / inner, -(iy0 + iy1) / 2 / inner, 0);
            }
        }
        if (webbed) {
            // thinner web with six lightening holes' worth of visual relief: a recessed disk
            float wt = th * 0.45f;
            annulus(c, cast, tint, hub, rim, wt / 2, 48);
            // spokes between hub and rim
            for (int k = 0; k < 6; k++) {
                c.ps.pushPose();
                c.ps.mulPose(new Quaternionf().rotateZ((float) (k * Math.PI / 3)));
                box(c, cast, tintDark, hub * 0.9f, -0.06f * rp - 0.02f, -th * 0.35f, rim + 0.01f, 0.06f * rp + 0.02f, th * 0.35f);
                c.ps.popPose();
            }
        }
        // hub boss
        cylinder(c, cast, tintDark, hub, -th * 0.65f, th * 0.65f, 20, true);
        c.ps.popPose();
    }

    private void annulus(Ctx c, TextureAtlasSprite s, int rgb, float r0, float r1, float halfT, int seg) {
        for (int i = 0; i < seg; i++) {
            double a0 = 2 * Math.PI * i / seg, a1 = 2 * Math.PI * (i + 1) / seg;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            quad(c, s, rgb, r0 * c0, r0 * s0, halfT, r1 * c0, r1 * s0, halfT, r1 * c1, r1 * s1, halfT, r0 * c1, r0 * s1, halfT, 0, 0, 1);
            quad(c, s, rgb, r0 * c1, r0 * s1, -halfT, r1 * c1, r1 * s1, -halfT, r1 * c0, r1 * s0, -halfT, r0 * c0, r0 * s0, -halfT, 0, 0, -1);
        }
    }

    // ------------------------------------------------------------------ wires

    private void drawWire(Ctx c, AssemblyBlockEntity be, PlacedPart p, Frame f) {
        boolean insulated = p.kind == PartKind.INSULATED_WIRE;
        double d = p.spec.params().getOrDefault("diameter", 1.5e-3) * 16.0;
        float r = (float) Math.max(Math.min(d / 2, 0.45), MIN_WIRE_RADIUS);
        if (insulated) r = Math.max(r * 1.7f, MIN_WIRE_RADIUS * 1.5f);
        // stranded-copper look for copper, plain drawn wire for resistance alloys and filaments; a glowing
        // wire shows its black-body colour on a light surface
        boolean copperLook = !insulated && p.spec.material().contains("copper") && c.glowRgb < 0;
        TextureAtlasSprite s = PartModels.sprite(insulated ? "pvc" : copperLook ? "wire_copper" : "metal_smooth");
        int rgb = tintOf(c, insulated ? 0x3A78C8 : copperLook ? (c.materialRgb | 0x202020) : c.materialRgb);
        float[] ext = {p.x1 - p.x0 + 1, p.y1 - p.y0 + 1, p.z1 - p.z0 + 1};
        // undo the part frame: wires are drawn in grid axes
        c.ps.pushPose();
        c.ps.last().pose().mul(new Matrix4f(f.m4).transpose());
        c.ps.last().normal().mul(new Matrix3f(f.m3).transpose());
        boolean any = false;
        for (Dir dir : Dir.values()) {
            if (!connects(be, p, dir)) continue;
            any = true;
            arm(c, s, rgb, dir, r, ext[dir.axis()] / 2);
        }
        if (!any) {
            arm(c, s, rgb, p.axis, r, ext[p.axis.axis()] / 2);
            arm(c, s, rgb, p.axis.opposite(), r, ext[p.axis.axis()] / 2);
        }
        // joint
        box(c, s, rgb, -r * 1.05f, -r * 1.05f, -r * 1.05f, r * 1.05f, r * 1.05f, r * 1.05f);
        c.ps.popPose();
    }

    private void arm(Ctx c, TextureAtlasSprite s, int rgb, Dir d, float r, float len) {
        c.ps.pushPose();
        // rotate the +Z cylinder onto direction d
        Vector3f to = new Vector3f(d.dx, d.dy, d.dz);
        c.ps.mulPose(new Quaternionf().rotationTo(new Vector3f(0, 0, 1), to));
        cylinder(c, s, rgb, r, 0, len, 8, false);
        c.ps.popPose();
    }

    private static boolean connects(AssemblyBlockEntity be, PlacedPart p, Dir d) {
        int res = be.resolution();
        int a = d.axis();
        int[] lo = {p.x0, p.y0, p.z0}, hi = {p.x1, p.y1, p.z1};
        int[] cell = {(lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2};
        cell[a] = d.sign() > 0 ? hi[a] + 1 : lo[a] - 1;
        AssemblyBlockEntity target = be;
        if (cell[a] < 0 || cell[a] >= res) {
            BlockPos np = be.getBlockPos().relative(net.minecraft.core.Direction.values()[d.ordinal()]);
            if (!(be.getLevel().getBlockEntity(np) instanceof AssemblyBlockEntity o) || o.resolution() != res) return false;
            target = o;
            cell[a] = Math.floorMod(cell[a], res);
        }
        for (PlacedPart q : target.parts()) {
            if (!q.contains(cell[0], cell[1], cell[2])) continue;
            if (q.kind == PartKind.WIRE || q.kind == PartKind.INSULATED_WIRE || q.kind.leaded || q.kind == PartKind.ELECTROLYTE) return true;
            if (p.kind == PartKind.WIRE && q.spec.resolveMaterial().isConductor()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ primitives (model units = cells)

    /** Cylinder along +Z from z0 to z1. */
    private void cylinder(Ctx c, TextureAtlasSprite s, int rgb, float r, float z0, float z1, int seg, boolean caps) {
        float len = z1 - z0;
        for (int i = 0; i < seg; i++) {
            double a0 = 2 * Math.PI * i / seg, a1 = 2 * Math.PI * (i + 1) / seg;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            float u0 = (float) i / seg, u1 = (float) (i + 1) / seg;
            float vmax = Math.min(1, len);
            vertex(c, c.solid, s, rgb, r * c0, r * s0, z0, u0, 0, c0, s0, 0);
            vertex(c, c.solid, s, rgb, r * c1, r * s1, z0, u1, 0, c1, s1, 0);
            vertex(c, c.solid, s, rgb, r * c1, r * s1, z1, u1, vmax, c1, s1, 0);
            vertex(c, c.solid, s, rgb, r * c0, r * s0, z1, u0, vmax, c0, s0, 0);
            if (caps) {
                quad(c, s, rgb, 0, 0, z1, r * c0, r * s0, z1, r * c1, r * s1, z1, 0, 0, z1, 0, 0, 1);
                quad(c, s, rgb, 0, 0, z0, r * c1, r * s1, z0, r * c0, r * s0, z0, 0, 0, z0, 0, 0, -1);
            }
        }
    }

    private void box(Ctx c, TextureAtlasSprite s, int rgb, float x0, float y0, float z0, float x1, float y1, float z1) {
        quad(c, s, rgb, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, 0, 1, 0);
        quad(c, s, rgb, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0, -1, 0);
        quad(c, s, rgb, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, 0, 0, -1);
        quad(c, s, rgb, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0, 0, 1);
        quad(c, s, rgb, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1, 0, 0);
        quad(c, s, rgb, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, 1, 0, 0);
    }

    /** Planar-mapped quad: UVs from the coordinates across the face's dominant plane. */
    private void quad(Ctx c, TextureAtlasSprite s, int rgb, float ax, float ay, float az, float bx, float by, float bz,
                      float cx, float cy, float cz, float dx, float dy, float dz, float nx, float ny, float nz) {
        int k = Math.abs(nx) >= Math.abs(ny) && Math.abs(nx) >= Math.abs(nz) ? 0 : Math.abs(ny) >= Math.abs(nz) ? 1 : 2;
        float[][] v = {{ax, ay, az}, {bx, by, bz}, {cx, cy, cz}, {dx, dy, dz}};
        int ui = k == 0 ? 1 : 0, vi = k == 2 ? 1 : 2;
        for (float[] p : v) {
            float u = frac(p[ui] + 0.5f), w = frac(p[vi] + 0.5f);
            vertex(c, c.solid, s, rgb, p[0], p[1], p[2], u, w, nx, ny, nz);
        }
    }

    private static float frac(float x) {
        float f = x - (float) Math.floor(x);
        return Math.max(0, Math.min(1, f));
    }

    private static void vertex(Ctx c, VertexConsumer vc, TextureAtlasSprite s, int rgb, float x, float y, float z,
                               float u, float v, float nx, float ny, float nz) {
        PoseStack.Pose last = c.ps.last();
        vc.addVertex(last.pose(), x, y, z).setColor((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, 255)
                .setUv(s.getU(u), s.getV(v)).setOverlay(OverlayTexture.NO_OVERLAY).setLight(c.light)
                .setNormal(last, nx, ny, nz);
    }

    // ------------------------------------------------------------------ colour

    private static final Map<String, String> CATEGORY_TEXTURE = Map.of(
            "METAL", "metal_brushed", "ALLOY", "metal_brushed", "MAGNETIC", "metal_cast", "CERAMIC", "ceramic",
            "POLYMER", "plastic", "COMPOSITE", "carbon_fibre", "SEMICONDUCTOR", "silicon", "GLASS", "glass",
            "INSULATOR", "plastic", "FLUID", "liquid");

    /** Surface texture for a structural block of the given material. */
    static String textureFor(Material m) {
        String id = m.id();
        if (id.contains("oak") || id.contains("wood") || id.contains("pine")) return "oak";
        if (id.contains("granite") || id.contains("stone") || id.contains("basalt") || id.contains("concrete")) return "granite";
        if (id.contains("graphite") || id.contains("carbon")) return "graphite";
        if (id.contains("copper")) return "metal_smooth";
        if (id.contains("ptfe") || id.contains("pvc")) return "pvc";
        return CATEGORY_TEXTURE.getOrDefault(m.category().name(), "metal_brushed");
    }

    /** Coloured natural textures are drawn as they are; neutral ones are tinted by the material colour. */
    private static boolean tintable(String texture) {
        return !(texture.equals("oak") || texture.equals("granite") || texture.equals("graphite") || texture.equals("silicon")
                || texture.equals("carbon_fibre"));
    }

    private static int tintOf(Ctx c, int rgb) {
        if (c.glowRgb < 0) return rgb;
        // incandescent surfaces emit rather than reflect: towards the (unshaded) black-body colour
        return mix(rgb, c.glowRgb, Math.min(1f, c.glowMix * 1.4f));
    }

    private static int darken(int rgb, float f) {
        return ((int) (((rgb >> 16) & 255) * f) << 16) | ((int) (((rgb >> 8) & 255) * f) << 8) | (int) ((rgb & 255) * f);
    }

    private static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /**
     * Approximate visible colour of a black body at temperature T (Tanner Helland's fit to CIE blackbody
     * chromaticities). Blended over the surface colour from 700 K (barely visible dull red) to full
     * incandescence above ~1300 K, since perceived brightness rises steeply with T.
     */
    static int blackBody(float t) {
        double k = t / 100.0;
        double r, g, b;
        if (k <= 66) {
            r = 255;
            g = Math.max(0, 99.4708025861 * Math.log(k) - 161.1195681661);
            b = k <= 19 ? 0 : 138.5177312231 * Math.log(k - 10) - 305.0447927307;
        } else {
            r = 329.698727446 * Math.pow(k - 60, -0.1332047592);
            g = 288.1221695283 * Math.pow(k - 60, -0.0755148492);
            b = 255;
        }
        return ((int) clamp(r) << 16) | ((int) clamp(g) << 8) | (int) clamp(b);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(255, v));
    }
}
