package dev.emergence.mod.client;

import static dev.emergence.mod.part.PartLinks.*;

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

import java.util.List;
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
        /** Where box()/quad() draw (null: the opaque buffer) and with what alpha. */
        VertexConsumer quadTarget;
        int quadAlpha = 255;
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
        ClientSimState.frameTime();
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
                boolean glassy = p.kind == PartKind.ELECTROLYTE || p.kind == PartKind.LED || isFlask(p);
                if (pass == 1 && !glassy) continue;
                if (glassy) glassPending = true;
                ps.pushPose();
                ClientSimState.Pose pose = ClientSimState.pose(p.id);
                double bodyAngle = 0;
                if (pose != null) {
                    bodyAngle = ClientSimState.bodyAngle(pose);
                    if (dev.emergence.mod.debug.PoseLog.ENABLED && p.id == pose.key() && pass == 0) {
                        dev.emergence.mod.debug.PoseLog.client(System.nanoTime() + "," + now + "," + pose.key() + "," + pose.time() + ","
                                + pose.angle() + "," + pose.omega() + "," + bodyAngle);
                    }
                    if (pose.slider()) {
                        ps.translate((float) (bodyAngle * pose.ax()), (float) (bodyAngle * pose.ay()), (float) (bodyAngle * pose.az()));
                        bodyAngle = 0;
                    } else {
                        float lx = (float) (pose.px() - pos.getX()), ly = (float) (pose.py() - pos.getY()), lz = (float) (pose.pz() - pos.getZ());
                        ps.translate(lx, ly, lz);
                        ps.mulPose(new Quaternionf().rotateAxis((float) bodyAngle, (float) pose.ax(), (float) pose.ay(), (float) pose.az()));
                        ps.translate(-lx, -ly, -lz);
                    }
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
                if (p.kind == PartKind.LED) ledState(c, p);
                if (pass == 0) {
                    drawPart(c, be, p, f, ex, ey, ez, bodyAngle, pose);
                    if (p.kind.leaded && !isWire(p)) drawLeads(c, be, p, f);
                }
                else if (isFlask(p)) drawFlask(c, p);
                else drawModel(c, p.kind == PartKind.LED ? "led" : "electrolyte", p, ex, ey, ez, true);
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
        if ((p.kind == PartKind.SWITCH || p.kind == PartKind.VALVE || p.kind == PartKind.LED || p.kind == PartKind.TRANSISTOR
                || p.kind == PartKind.LOGIC || p.kind == PartKind.CHIP || p.kind == PartKind.OPAMP) && a != 1) {
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
                // a plate thinner than its cells lies against the side it is fixed to (the side more of its face
                // touches: a handle on its rod, a disc on its magnets), not floating in the middle
                float shift = 0;
                if (fill < 0.999f) {
                    int below = faceCover(be, p, p.axis.opposite()), above = faceCover(be, p, p.axis);
                    if (below != above) shift = (below > above ? -1 : 1) * ez * (1 - fill) / 2;
                }
                c.ps.pushPose();
                c.ps.translate(0, 0, shift);
                drawModel(c, "plate", p, ex, ey, ez * fill, false);
                c.ps.popPose();
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
            case LINEAR_BEARING -> drawModel(c, "bearing", p, Math.min(ex, ey), Math.min(ex, ey), ez * 1.2f, false);
            case SPRING -> drawSpring(c, be, p, ez);
            case RACK -> drawRack(c, p, f, ex, ey, ez);
            case NUT -> {
                // a hexagon-ish bronze block round the screw, chamfered at its ends
                TextureAtlasSprite s = PartModels.sprite("metal_cast");
                box(c, s, tintOf(c, c.materialRgb), -0.45f * ex, -0.4f * ey, -0.38f * ez, 0.45f * ex, 0.4f * ey, 0.38f * ez);
                box(c, s, tintOf(c, darken(c.materialRgb, 0.85f)), -0.4f * ex, -0.45f * ey, -0.42f * ez, 0.4f * ex, 0.45f * ey, 0.42f * ez);
            }
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
            case TANK -> {
                if (!isFlask(p)) drawModel(c, "tank", p, ex, ey, ez, false);
            }
            case VALVE -> drawModel(c, "valve", p, 1, 1, ez, false);
            case PIPE -> drawPipe(c, be, p, f);
            case ROTOR -> drawRotor(c, p, ex, ey, ez);
            case TRANSISTOR -> drawModel(c, "transistor", p, 1, 1, 1, false);
            case LOGIC -> drawModel(c, "logic", p, 1, 1, 1, false);
            case LED -> drawModel(c, "led", p, 1, 1, 1, false);
            case CYLINDER -> {
                drawModel(c, "cylinder", p, Math.min(ex, ey), Math.min(ex, ey), ez, false);
                drawPiston(c, p, f, ez);
            }
            case ECCENTRIC -> drawEccentric(c, p, f);
            case BRAKE -> drawBrake(c, p);
            case PHOTODIODE -> drawPhotodiode(c, p, f);
            case POTENTIOMETER -> drawPotentiometer(c, p, f);
            case SOURCE -> drawSource(c);
            case GM_TUBE -> drawGmTube(c);
            case OPAMP -> drawModel(c, "logic", p, 1, 1, 1, false);
            case CHIP -> drawChipSegment(c, p);
        }
    }

    /** One pin pair of a DIP package: epoxy body continuous along the axis, a tinned leg on each lead-axis side. */
    private void drawChipSegment(Ctx c, PlacedPart p) {
        int fn = (int) Math.round(p.spec.params().getOrDefault("function", 0.0));
        int body = fn == 3 ? 0x2A2630 : 0x1C1C1E; // the ROM's ceramic package is a little lighter
        box(c, PartModels.sprite("epoxy"), tintOf(c, body), -0.3f, -0.42f, -0.5f, 0.3f, -0.18f, 0.5f);
        for (float side : new float[]{-1, 1}) {
            box(c, PartModels.sprite("tinned"), tintOf(c, 0xC8CCD0), side > 0 ? 0.3f : -0.5f, -0.3f, -0.09f,
                    side > 0 ? 0.5f : -0.3f, -0.24f, 0.09f);
            box(c, PartModels.sprite("tinned"), tintOf(c, 0xC8CCD0), side > 0 ? 0.42f : -0.5f, -0.5f, -0.09f,
                    side > 0 ? 0.5f : -0.42f, -0.3f, 0.09f);
        }
        // a stripe of markings across the top
        box(c, PartModels.sprite("epoxy"), tintOf(c, 0x8A8A8A), -0.16f, -0.18f, -0.06f, 0.16f, -0.175f, 0.06f);
    }

    /**
     * Potentiometer: a dark housing round the shaft with a metal bushing on each face it passes through, and three
     * tinned tabs where its terminals are (track ends on the lead-axis sides, the wiper on the −side of the remaining
     * axis). Drawn in grid axes.
     */
    private void drawPotentiometer(Ctx c, PlacedPart p, Frame f) {
        int a = p.axis.axis();
        int def = a == 0 ? 2 : 0;
        int l = (int) Math.round(p.spec.params().getOrDefault("lead_axis", (double) def));
        if (l == a || l < 0 || l > 2) l = def;
        int rem = 3 - a - l;
        c.ps.pushPose();
        c.ps.last().pose().mul(new Matrix4f(f.m4).transpose());
        c.ps.last().normal().mul(new Matrix3f(f.m3).transpose());
        potBox(c, PartModels.sprite("epoxy"), tintOf(c, 0x1E2A44), a, l, rem, -0.3f, 0.3f, -0.36f, 0.36f, -0.36f, 0.36f);
        potBox(c, PartModels.sprite("metal_smooth"), tintOf(c, 0xB8BCC2), a, l, rem, -0.5f, -0.3f, -0.12f, 0.12f, -0.12f, 0.12f);
        potBox(c, PartModels.sprite("metal_smooth"), tintOf(c, 0xB8BCC2), a, l, rem, 0.3f, 0.5f, -0.12f, 0.12f, -0.12f, 0.12f);
        TextureAtlasSprite tin = PartModels.sprite("tinned");
        int tint = tintOf(c, 0xC8CCD0);
        potBox(c, tin, tint, a, l, rem, -0.06f, 0.06f, -0.5f, -0.36f, -0.03f, 0.03f);
        potBox(c, tin, tint, a, l, rem, -0.06f, 0.06f, 0.36f, 0.5f, -0.03f, 0.03f);
        potBox(c, tin, tint, a, l, rem, -0.06f, 0.06f, -0.03f, 0.03f, -0.5f, -0.36f);
        c.ps.popPose();
    }

    /** A box given its extent along the part axis a, the lead axis l and the remaining axis r (grid axes). */
    private void potBox(Ctx c, TextureAtlasSprite s, int rgb, int a, int l, int rem, float a0, float a1, float l0, float l1, float r0, float r1) {
        float[] lo = new float[3], hi = new float[3];
        lo[a] = a0;
        hi[a] = a1;
        lo[l] = l0;
        hi[l] = l1;
        lo[rem] = r0;
        hi[rem] = r1;
        box(c, s, rgb, lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
    }

    /** A gear rack: a bar along its axis with straight teeth (module from "module") cut on its "face" side. */
    private void drawRack(Ctx c, PlacedPart p, Frame f, float ex, float ey, float ez) {
        var prm = p.spec.params();
        Dir face = Dir.values()[(int) Math.max(0, Math.min(5, Math.round(prm.getOrDefault("face", 1.0))))];
        org.joml.Vector3f loc = new Matrix3f(f.m3).transpose().transform(new org.joml.Vector3f(face.dx, face.dy, face.dz));
        boolean onX = Math.abs(loc.x) > 0.5f;
        if (!onX && Math.abs(loc.y) < 0.5f) {
            box(c, PartModels.sprite("metal_cast"), tintOf(c, c.materialRgb), -ex / 2, -ey / 2, -ez / 2, ex / 2, ey / 2, ez / 2);
            return; // teeth on an end face: nothing meshes there
        }
        float sg = Math.signum(onX ? loc.x : loc.y), half = (onX ? ex : ey) / 2, side = 0.8f * (onX ? ey : ex) / 2;
        float m = (float) (prm.getOrDefault("module", Math.min(ex, ey) / 16.0 / 24) * 16.0); // [cells]
        float root = half - 1.25f * m, tip = half + m, pitch = (float) Math.PI * m;
        int tint = tintOf(c, c.materialRgb), dark = tintOf(c, darken(c.materialRgb, 0.82f));
        TextureAtlasSprite brushed = PartModels.sprite("metal_brushed"), cast = PartModels.sprite("metal_cast");
        // the bar up to the tooth roots, then a tooth every pitch along it
        rackBox(c, brushed, tint, onX, sg, -half, root, -(onX ? ey : ex) / 2, (onX ? ey : ex) / 2, -ez / 2, ez / 2);
        int n = (int) Math.min(400, Math.floor(ez / pitch));
        float z0 = -n * pitch / 2;
        for (int k = 0; k < n; k++) {
            float za = z0 + k * pitch + 0.25f * pitch;
            rackBox(c, cast, dark, onX, sg, root, tip, -side, side, za, za + 0.5f * pitch);
        }
    }

    /** A box spanning [a0, a1] along the rack's toothed axis (mirrored by sg), [b0, b1] across it and [z0, z1] along it. */
    private void rackBox(Ctx c, TextureAtlasSprite s, int rgb, boolean onX, float sg, float a0, float a1, float b0, float b1, float z0, float z1) {
        float lo = sg > 0 ? a0 : -a1, hi = sg > 0 ? a1 : -a0;
        if (onX) box(c, s, rgb, lo, b0, z0, hi, b1, z1);
        else box(c, s, rgb, b0, lo, z0, b1, hi, z1);
    }

    /**
     * Helical spring along its axis: stacked rings of wire between its two end faces, which follow what they are
     * fixed to (a slide moving along the spring stretches or squeezes it).
     */
    private void drawSpring(Ctx c, AssemblyBlockEntity be, PlacedPart p, float len) {
        double coil = p.spec.params().getOrDefault("coil", 0.6 / 16) * 16, wire = p.spec.params().getOrDefault("wire", 0.002) * 16;
        int turns = (int) Math.round(p.spec.params().getOrDefault("turns", 10.0));
        float r = (float) Math.min(0.48, coil / 2), w = (float) Math.max(0.035, wire / 2);
        float[] ends = ClientSimState.spring(p.id);
        float z0 = -len / 2, z1 = len / 2;
        if (ends != null) {
            // world blocks → cells
            z0 += ends[0] * be.resolution();
            z1 += ends[1] * be.resolution();
        }
        float span = Math.max(z1 - z0, 2 * w * (turns + 1)); // solid length: the coils touching
        TextureAtlasSprite s = PartModels.sprite("metal_smooth");
        int rgb = tintOf(c, c.materialRgb);
        // a helix of wire: half a closed (flat) turn at each end, sitting on what the spring joins, the active turns
        // rising evenly between
        int perTurn = 12, n = (turns + 1) * perTurn;
        float zLo = z0 + w, zHi = z0 + span - w;
        float px = r, py = 0, pz = zLo;
        for (int i = 1; i <= n; i++) {
            double ang = 2 * Math.PI * i / perTurn;
            float u = (float) i / n, end = 0.5f / (turns + 1);
            float frac = u < end ? 0 : u > 1 - end ? 1 : (u - end) / (1 - 2 * end);
            float x = (float) (r * Math.cos(ang)), y = (float) (r * Math.sin(ang)), z = zLo + frac * (zHi - zLo);
            segment(c, s, rgb, px, py, pz, x, y, z, w);
            px = x;
            py = y;
            pz = z;
        }
    }

    /** A round rod of radius w from point a to point b (model units), slightly overrunning to close the joints. */
    private void segment(Ctx c, TextureAtlasSprite s, int rgb, float ax, float ay, float az, float bx, float by, float bz, float w) {
        Vector3f d = new Vector3f(bx - ax, by - ay, bz - az);
        float len = d.length();
        if (len < 1e-5f) return;
        c.ps.pushPose();
        c.ps.translate(ax, ay, az);
        c.ps.mulPose(new Quaternionf().rotationTo(new Vector3f(0, 0, 1), d.normalize()));
        cylinder(c, s, rgb, w, -w * 0.5f, len + w * 0.5f, 6, false);
        c.ps.popPose();
    }

    /** A sealed check source: a small steel capsule in a yellow holder disc with a dark trefoil mark. */
    private void drawSource(Ctx c) {
        box(c, PartModels.sprite("plastic"), tintOf(c, 0xE8C21E), -0.3f, -0.5f, -0.3f, 0.3f, -0.42f, 0.3f);
        box(c, PartModels.sprite("metal_smooth"), tintOf(c, 0xB0B4B8), -0.08f, -0.42f, -0.08f, 0.08f, -0.3f, 0.08f);
        for (int k = 0; k < 3; k++) {
            float a = (float) (k * 2 * Math.PI / 3), ca = (float) Math.cos(a) * 0.17f, sa = (float) Math.sin(a) * 0.17f;
            box(c, PartModels.sprite("plastic"), tintOf(c, 0x1A1A1A), ca - 0.06f, -0.42f, sa - 0.06f, ca + 0.06f, -0.415f, sa + 0.06f);
        }
    }

    /** Geiger–Müller tube: a steel cylinder along its axis with a mica end window and two terminals. */
    private void drawGmTube(Ctx c) {
        cylinder(c, PartModels.sprite("metal_smooth"), tintOf(c, 0xA8ACB0), 0.16f, -0.42f, 0.42f, 12, true);
        box(c, PartModels.sprite("plastic"), tintOf(c, 0x5A4A30), -0.12f, -0.12f, 0.42f, 0.12f, 0.12f, 0.45f);
        box(c, PartModels.sprite("tinned"), tintOf(c, 0xC8CCD0), -0.02f, -0.02f, 0.45f, 0.02f, 0.02f, 0.5f);
        box(c, PartModels.sprite("tinned"), tintOf(c, 0xC8CCD0), -0.02f, -0.02f, -0.5f, 0.02f, 0.02f, -0.42f);
    }

    /** A glass tank: drawn see-through in the translucent pass, with its gas in the colour chemistry gives it. */
    private static boolean isFlask(PlacedPart p) {
        return p.kind == PartKind.TANK && p.spec.resolveMaterial().id().equals("glass");
    }

    /** Glass shell and, if the gas inside is coloured, a tinted volume of gas within it. */
    private void drawFlask(Ctx c, PlacedPart p) {
        c.quadTarget = c.glass;
        TextureAtlasSprite gl = PartModels.sprite("glass");
        int gas = ClientSimState.colour(p.id, -1);
        if (gas >= 0) {
            c.quadAlpha = 175;
            box(c, PartModels.sprite("liquid"), gas, -0.43f, -0.43f, -0.43f, 0.43f, 0.43f, 0.43f);
        }
        c.quadAlpha = 80;
        box(c, gl, 0xE8F4F8, -0.46f, -0.46f, -0.46f, 0.46f, 0.46f, 0.46f);
        c.quadTarget = null;
        c.quadAlpha = 255;
    }

    /** Photodiode: a small black body with a clear window on its sensitive face; a large-area one is a blue solar
     *  cell with silver busbars. Drawn in grid axes, turned to face along "face". */
    private void drawPhotodiode(Ctx c, PlacedPart p, Frame f) {
        int fi = (int) Math.max(0, Math.min(5, Math.round(p.spec.params().getOrDefault("face", 1.0))));
        Dir face = Dir.values()[fi];
        boolean solar = p.spec.params().getOrDefault("area", 7.5e-6) > 1e-3;
        c.ps.pushPose();
        c.ps.last().pose().mul(new Matrix4f(f.m4).transpose());
        c.ps.last().normal().mul(new Matrix3f(f.m3).transpose());
        c.ps.mulPose(new Quaternionf().rotationTo(new Vector3f(0, 0, 1), new Vector3f(face.dx, face.dy, face.dz)));
        if (solar) {
            box(c, PartModels.sprite("metal_cast"), tintOf(c, 0x8A9096), -0.48f, -0.48f, -0.5f, 0.48f, 0.48f, -0.38f);
            box(c, PartModels.sprite("silicon"), tintOf(c, 0x1F3C8C), -0.46f, -0.46f, -0.38f, 0.46f, 0.46f, -0.33f);
            for (float x : new float[]{-0.25f, 0f, 0.25f})
                box(c, PartModels.sprite("tinned"), tintOf(c, 0xD8DCE0), x - 0.012f, -0.46f, -0.33f, x + 0.012f, 0.46f, -0.325f);
        } else {
            box(c, PartModels.sprite("epoxy"), tintOf(c, 0x161618), -0.2f, -0.2f, -0.2f, 0.2f, 0.2f, 0.12f);
            box(c, PartModels.sprite("silicon"), tintOf(c, 0x5A6A80), -0.12f, -0.12f, 0.12f, 0.12f, 0.12f, 0.16f);
            for (float x : new float[]{-0.1f, 0.1f})
                box(c, PartModels.sprite("tinned"), tintOf(c, 0xC8CCD0), x - 0.02f, -0.02f, -0.5f, x + 0.02f, 0.02f, -0.2f);
        }
        c.ps.popPose();
    }

    /** Brake block: a shoe of the pad material with a dark friction lining on its +axis face, sprung from a base
     *  plate; released, it stands back from what it presses on. */
    private void drawBrake(Ctx c, PlacedPart p) {
        float back = p.closed ? 0f : 0.08f;
        int pad = tintOf(c, tintable(c.materialTexture) ? c.materialRgb : 0xFFFFFF);
        box(c, PartModels.sprite("metal_cast"), tintOf(c, 0x5E6266), -0.45f, -0.45f, -0.5f, 0.45f, 0.45f, -0.42f);
        for (float x : new float[]{-0.25f, 0.25f}) {
            c.ps.pushPose();
            c.ps.translate(x, 0, 0);
            cylinder(c, PartModels.sprite("metal_smooth"), tintOf(c, 0x9AA0A6), 0.06f, -0.42f, -0.08f - back, 10, true);
            c.ps.popPose();
        }
        box(c, PartModels.sprite(c.materialTexture), pad, -0.42f, -0.42f, -0.08f - back, 0.42f, 0.42f, 0.4f - back);
        box(c, PartModels.sprite("rubber"), tintOf(c, 0x2B2724), -0.4f, -0.4f, 0.4f - back, 0.4f, 0.4f, 0.5f - back);
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
            if (flags.contains("green")) rgb = 0x1E6B33; // circuit-board green
            if (flags.contains("yellow")) rgb = 0xF0C020;
            if (flags.contains("blue")) rgb = 0x3570C0;
            int alpha = glass ? (mt[0].equals("liquid") ? (p.kind == PartKind.LED ? 235 : 200) : 110) : 255;
            c.ps.pushPose();
            switch (piece.name()) {
                case "crank_arm" -> c.ps.scale((float) crankR, 1, 1);
                case "crank_grip", "crank_knob", "crank_pin" -> c.ps.translate((float) crankR - 1, 0, 0);
                case "valve_stem", "valve_wheel", "valve_wheel_spoke", "valve_wheel_hub" -> {
                    if (p.closed) c.ps.translate(0, 0.09f, 0); // open: the stem has risen
                }
                case "tank_needle" -> {
                    // Bourdon gauge over 270°, pivoting at the dial centre; full scale "gauge_range" bar (default 2.5)
                    double range = p.spec.params().getOrDefault("gauge_range", 2.5) * 1e5;
                    float frac = (float) Math.max(0, Math.min(1, ClientSimState.gauge(p.id) / range));
                    c.ps.translate(0.2f, 0, 0.2f);
                    c.ps.mulPose(new Quaternionf().rotateY((float) Math.toRadians((frac - 0.5) * 270)));
                    c.ps.translate(-0.2f, 0, -0.2f);
                }
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
        int rgb = tintOf(c, insulated ? insulationRgb(p) : copperLook ? (c.materialRgb | 0x202020) : c.materialRgb);
        float[] ext = {p.x1 - p.x0 + 1, p.y1 - p.y0 + 1, p.z1 - p.z0 + 1};
        // undo the part frame: wires are drawn in grid axes
        c.ps.pushPose();
        c.ps.last().pose().mul(new Matrix4f(f.m4).transpose());
        c.ps.last().normal().mul(new Matrix3f(f.m3).transpose());
        boolean any = false;
        for (Dir dir : Dir.values()) {
            PlacedPart q = neighbour(be, p, dir);
            if (q == null) continue;
            any = true;
            // wire into wire runs on through the face; a wire ending on a part stops at the face in a closed end,
            // where the part's lead takes over (drawLeads)
            arm(c, s, rgb, dir, r, ext[dir.axis()] / 2, !isWire(q));
        }
        if (!any) {
            arm(c, s, rgb, p.axis, r, ext[p.axis.axis()] / 2, true);
            arm(c, s, rgb, p.axis.opposite(), r, ext[p.axis.axis()] / 2, true);
        }
        // joint
        box(c, s, rgb, -r * 1.05f, -r * 1.05f, -r * 1.05f, r * 1.05f, r * 1.05f, r * 1.05f);
        c.ps.popPose();
    }

    /** Insulation colours (parameter "color" 0–15): blue (default), then the usual wiring colours. */
    static final int[] INSULATION = {0x3A78C8, 0xC83A3A, 0x2A2A2A, 0xE0C030, 0x3AA040, 0xE8E8E8, 0xE07A20, 0x8A4AC0,
            0x7A4A2A, 0x40B8C0, 0xE070A0, 0x808080, 0x90C840, 0x1E3C8C, 0xB01E1E, 0x5A5A20};

    static int insulationRgb(PlacedPart p) {
        int k = (int) Math.round(p.spec.params().getOrDefault("color", 0.0));
        return INSULATION[Math.floorMod(k, INSULATION.length)];
    }

    private void arm(Ctx c, TextureAtlasSprite s, int rgb, Dir d, float r, float len) {
        arm(c, s, rgb, d, r, len, false);
    }

    private void arm(Ctx c, TextureAtlasSprite s, int rgb, Dir d, float r, float len, boolean capped) {
        c.ps.pushPose();
        // rotate the +Z cylinder onto direction d
        Vector3f to = new Vector3f(d.dx, d.dy, d.dz);
        c.ps.mulPose(new Quaternionf().rotationTo(new Vector3f(0, 0, 1), to));
        cylinder(c, s, rgb, r, 0, len, 8, capped);
        c.ps.popPose();
    }

    /**
     * A leaded part's leads to the wires that meet it: a tinned lead from the part out to each face where a wire
     * ends on it, so every wire visibly reaches what it connects to (the models carry only their own two leads).
     */
    private void drawLeads(Ctx c, AssemblyBlockEntity be, PlacedPart p, Frame f) {
        float[] ext = {p.x1 - p.x0 + 1, p.y1 - p.y0 + 1, p.z1 - p.z0 + 1};
        TextureAtlasSprite s = PartModels.sprite("tinned");
        int rgb = tintOf(c, 0xC8CCD0);
        c.ps.pushPose();
        c.ps.last().pose().mul(new Matrix4f(f.m4).transpose());
        c.ps.last().normal().mul(new Matrix3f(f.m3).transpose());
        for (Dir dir : Dir.values()) {
            PlacedPart q = neighbour(be, p, dir);
            if (q != null && isWire(q)) arm(c, s, rgb, dir, 0.035f, ext[dir.axis()] / 2, true);
        }
        c.ps.popPose();
    }

    /**
     * LED colour from its wavelength (CIE-like piecewise fit to the visible spectrum); when it emits, the dome
     * is drawn full-bright and washed towards white in proportion to its light output.
     */
    private static void ledState(Ctx c, PlacedPart p) {
        double nm = p.spec.params().getOrDefault("wavelength", 630.0);
        int rgb = wavelengthRgb(nm);
        float watts = ClientSimState.gauge(p.id);
        if (watts > 1e-5f) {
            float k = (float) Math.min(1, Math.log10(watts / 1e-5) / 3);
            rgb = mix(darken(rgb, 0.55f), mix(rgb, 0xFFFFFF, 0.35f), k);
            c.light = LightTexture.FULL_BRIGHT;
        } else {
            rgb = darken(rgb, 0.5f);
        }
        c.materialRgb = rgb;
    }

    /** Approximate sRGB of monochromatic light (Bruton's fit), 380–780 nm. */
    static int wavelengthRgb(double nm) {
        double r, g, b;
        if (nm < 440) { r = -(nm - 440) / 60; g = 0; b = 1; }
        else if (nm < 490) { r = 0; g = (nm - 440) / 50; b = 1; }
        else if (nm < 510) { r = 0; g = 1; b = -(nm - 510) / 20; }
        else if (nm < 580) { r = (nm - 510) / 70; g = 1; b = 0; }
        else if (nm < 645) { r = 1; g = -(nm - 645) / 65; b = 0; }
        else { r = 1; g = 0; b = 0; }
        return ((int) (255 * Math.max(0, Math.min(1, r))) << 16) | ((int) (255 * Math.max(0, Math.min(1, g))) << 8)
                | (int) (255 * Math.max(0, Math.min(1, b)));
    }

    // ------------------------------------------------------------------ fluid parts

    private static final int[] FLUID_BANDS = {-1, 0x2E6FD0, 0xE070B0, 0x7FA0C0, 0xC08A20, 0xA0A0FF, 0x606060};

    /**
     * A pipe: a tube along its axis at its real bore and wall, flanges where it joins another fluid part,
     * side arms where it tees into a neighbour, a dark mouth at an open end, and a colour band for
     * anything but air.
     */
    private void drawPipe(Ctx c, AssemblyBlockEntity be, PlacedPart p, Frame f) {
        var prm = p.spec.params();
        double wall = prm.getOrDefault("wall", 0.002), bore = prm.getOrDefault("bore", 0.025);
        float ro = (float) Math.max((bore / 2 + wall) * 16, 0.06), ri = (float) (bore / 2 * 16);
        TextureAtlasSprite s = PartModels.sprite(c.materialTexture);
        int rgb = tintOf(c, tintable(c.materialTexture) ? c.materialRgb : 0xFFFFFF);
        int dark = tintOf(c, darken(c.materialRgb, 0.75f));
        float[] ext = {p.x1 - p.x0 + 1, p.y1 - p.y0 + 1, p.z1 - p.z0 + 1};
        c.ps.pushPose();
        c.ps.last().pose().mul(new Matrix4f(f.m4).transpose());
        c.ps.last().normal().mul(new Matrix3f(f.m3).transpose());
        int axis = p.axis.axis();
        for (Dir d : Dir.values()) {
            List<PlacedPart> nb = across(be, p, d);
            boolean fluidNb = nb.stream().anyMatch(q -> q.kind.holdsFluid());
            float len = ext[d.axis()] / 2;
            if (d.axis() == axis) {
                arm(c, s, rgb, d, ro, len);
                if (fluidNb) flange(c, s, dark, d, ro, len);
                else if (nb.isEmpty()) {
                    // open end: show the bore
                    c.ps.pushPose();
                    c.ps.mulPose(new Quaternionf().rotationTo(new Vector3f(0, 0, 1), new Vector3f(d.dx, d.dy, d.dz)));
                    cylinder(c, PartModels.sprite("epoxy"), 0x202020, ri, len - 0.002f, len + 0.001f, 16, true);
                    c.ps.popPose();
                    flange(c, s, dark, d, ro, len);
                }
            } else if (nb.stream().anyMatch(q -> q.kind == PartKind.TANK || (q.kind.holdsFluid() && q.axis.axis() == d.axis()))) {
                arm(c, s, rgb, d, ro, len);
                flange(c, s, dark, d, ro, len);
            }
        }
        int code = (int) Math.round(prm.getOrDefault("fluid", 0.0));
        if (code > 0 && code < FLUID_BANDS.length) {
            c.ps.pushPose();
            c.ps.mulPose(new Quaternionf().rotationTo(new Vector3f(0, 0, 1), new Vector3f(p.axis.dx, p.axis.dy, p.axis.dz)));
            cylinder(c, PartModels.sprite("dark_paint"), tintOf(c, FLUID_BANDS[code]), ro * 1.04f, -0.08f, 0.08f, 20, false);
            c.ps.popPose();
        }
        c.ps.popPose();
    }

    private void flange(Ctx c, TextureAtlasSprite s, int rgb, Dir d, float ro, float at) {
        c.ps.pushPose();
        c.ps.mulPose(new Quaternionf().rotationTo(new Vector3f(0, 0, 1), new Vector3f(d.dx, d.dy, d.dz)));
        cylinder(c, s, rgb, ro * 1.35f, at - 0.07f, at - 0.005f, 20, true);
        c.ps.popPose();
    }

    /**
     * A bladed rotor: hub, a row of blades set at the blade angle (handedness from the pitch) and a
     * light shroud ring. Rotates with its shaft.
     */
    private void drawRotor(Ctx c, PlacedPart p, float ex, float ey, float ez) {
        var prm = p.spec.params();
        float rt = 0.45f * Math.min(ex, ey), rh = 0.35f * rt;
        float beta = (float) Math.toRadians(prm.getOrDefault("blade_angle", 45.0)) * (prm.getOrDefault("pitch", 1.0) < 0 ? -1 : 1);
        TextureAtlasSprite s = PartModels.sprite("metal_brushed"), cast = PartModels.sprite("metal_cast");
        int rgb = tintOf(c, c.materialRgb), dark = tintOf(c, darken(c.materialRgb, 0.8f));
        float hubLen = 0.55f * ez;
        cylinder(c, cast, dark, rh, -hubLen / 2, hubLen / 2, 20, true);
        int n = 7;
        float chord = Math.min(0.5f * ez, 1.6f * rt * (float) Math.PI / n);
        for (int k = 0; k < n; k++) {
            double phi = 2 * Math.PI * k / n;
            float cr = (float) Math.cos(phi), sr = (float) Math.sin(phi);
            // chord direction: along the axis tilted by β towards the tangential direction
            float tx = -sr, ty = cr;
            float ax = (float) Math.sin(beta) * tx, ay = (float) Math.sin(beta) * ty, az = (float) Math.cos(beta);
            float h = chord / 2;
            float[][] v = {
                    {rh * cr - h * ax, rh * sr - h * ay, -h * az}, {rt * cr - h * ax, rt * sr - h * ay, -h * az},
                    {rt * cr + h * ax, rt * sr + h * ay, h * az}, {rh * cr + h * ax, rh * sr + h * ay, h * az}};
            // normal of the blade surface: radial × chord
            float nx = sr * az - 0, ny = -cr * az, nz = cr * ay - sr * ax;
            twoSided(c, s, rgb, v, nx, ny, nz);
        }
        // shroud ring (two thin bands)
        cylinder(c, s, dark, rt + 0.02f, -0.12f * ez, 0.12f * ez, 32, false);
    }

    private void twoSided(Ctx c, TextureAtlasSprite s, int rgb, float[][] v, float nx, float ny, float nz) {
        quadRaw(c, s, rgb, v[0], v[1], v[2], v[3], nx, ny, nz);
        quadRaw(c, s, rgb, v[3], v[2], v[1], v[0], -nx, -ny, -nz);
    }

    private void quadRaw(Ctx c, TextureAtlasSprite s, int rgb, float[] a, float[] b, float[] d, float[] e, float nx, float ny, float nz) {
        float[][] q = {a, b, d, e};
        float[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int i = 0; i < 4; i++) vertex(c, c.solid, s, rgb, q[i][0], q[i][1], q[i][2], uv[i][0], uv[i][1], nx, ny, nz);
    }

    /**
     * Piston and connecting rod of a cylinder, placed from the synced crank state: the piston crown sits at
     * clearance + displacement below the head and the rod runs to the crank pin at throw r, angle ψ from TDC.
     */
    private void drawPiston(Ctx c, PlacedPart p, Frame f, float ez) {
        float[] st = ClientSimState.piston(p.id);
        float bore = (float) (p.spec.params().getOrDefault("bore", 0.04) * 16);
        float s = st == null ? 0.01f : st[0] * 16, psi = st == null ? 0 : st[1];
        float r = st == null ? 0 : st[2] * 16, l = st == null ? 0 : st[3] * 16, cl = st == null ? 0 : st[4] * 16;
        float head = -ez / 2;
        float crown = head + cl + s;
        TextureAtlasSprite metal = PartModels.sprite("metal_smooth"), cast = PartModels.sprite("metal_cast");
        int grey = tintOf(c, 0xC8CCD0), dark = tintOf(c, 0x8A8E94);
        // piston (in the part frame, Z from the head towards the crank)
        cylinder(c, metal, grey, Math.min(bore / 2 * 0.98f, 0.48f), crown, crown + 0.22f, 20, true);
        for (int k = 0; k < 2; k++) cylinder(c, cast, dark, Math.min(bore / 2, 0.49f), crown + 0.03f + 0.05f * k, crown + 0.05f + 0.05f * k, 20, false);
        if (st == null) return;
        // crank pin in the part frame: the shaft axis lies at head + cl + r + l along Z; the pin moves through
        // TDC along the synced lateral direction, expressed in the part frame
        Vector3f lat = new Vector3f(st[5], st[6], st[7]);
        new Matrix3f(f.m3).transpose().transform(lat);
        float zShaft = head + cl + r + l;
        Vector3f pin = new Vector3f(lat).mul(r * (float) Math.sin(psi)).add(0, 0, zShaft - r * (float) Math.cos(psi));
        Vector3f wrist = new Vector3f(0, 0, crown + 0.1f);
        Vector3f d = new Vector3f(pin).sub(wrist);
        float len = d.length();
        c.ps.pushPose();
        c.ps.translate(wrist.x, wrist.y, wrist.z);
        c.ps.mulPose(new Quaternionf().rotationTo(new Vector3f(0, 0, 1), d.normalize()));
        box(c, metal, grey, -0.05f, -0.035f, 0, 0.05f, 0.035f, len);
        cylinder(c, cast, dark, 0.07f, len - 0.05f, len + 0.05f, 12, true);
        c.ps.popPose();
    }

    /** A crank throw: hub, web and pin at its throw radius, set by its phase; turns with the shaft. */
    private void drawEccentric(Ctx c, PlacedPart p, Frame f) {
        var prm = p.spec.params();
        float r = (float) (prm.getOrDefault("throw", 0.015) * 16);
        double phase = Math.toRadians(prm.getOrDefault("phase", 0.0));
        int a = p.axis.axis();
        // the compiler's reference: crank axis n (positive), ref = next grid axis, angle towards n × ref
        Vector3f n = new Vector3f(a == 0 ? 1 : 0, a == 1 ? 1 : 0, a == 2 ? 1 : 0);
        int ra = (a + 1) % 3;
        Vector3f ref = new Vector3f(ra == 0 ? 1 : 0, ra == 1 ? 1 : 0, ra == 2 ? 1 : 0);
        Vector3f pinDir = new Vector3f(ref).mul((float) Math.cos(phase)).add(new Vector3f(n).cross(ref).mul((float) Math.sin(phase)));
        TextureAtlasSprite s = PartModels.sprite("metal_brushed"), cast = PartModels.sprite("metal_cast");
        int rgb = tintOf(c, c.materialRgb), dark = tintOf(c, darken(c.materialRgb, 0.8f));
        c.ps.pushPose();
        c.ps.last().pose().mul(new Matrix4f(f.m4).transpose());
        c.ps.last().normal().mul(new Matrix3f(f.m3).transpose());
        // hub along the shaft
        c.ps.pushPose();
        c.ps.mulPose(new Quaternionf().rotationTo(new Vector3f(0, 0, 1), n));
        cylinder(c, cast, dark, 0.16f, -0.25f, 0.25f, 20, true);
        c.ps.popPose();
        // web: from the axis out past the pin, in the plane of rotation
        c.ps.pushPose();
        Vector3f side = new Vector3f(n).cross(pinDir);
        Matrix3f basis = new Matrix3f(side, pinDir, n);
        c.ps.last().pose().mul(new Matrix4f().set3x3(basis));
        c.ps.last().normal().mul(basis);
        box(c, s, rgb, -0.14f, -0.12f, -0.12f, 0.14f, r + 0.12f, 0.12f);
        c.ps.translate(0, r, 0);
        cylinder(c, PartModels.sprite("chrome"), tintOf(c, 0xE0E0E0), 0.07f, -0.35f, 0.35f, 14, true);
        c.ps.popPose();
        c.ps.popPose();
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
        VertexConsumer vc = c.quadTarget != null ? c.quadTarget : c.solid;
        for (float[] p : v) {
            float u = frac(p[ui] + 0.5f), w = frac(p[vi] + 0.5f);
            vertex(c, vc, s, rgb, p[0], p[1], p[2], u, w, nx, ny, nz);
        }
    }

    private static float frac(float x) {
        float f = x - (float) Math.floor(x);
        return Math.max(0, Math.min(1, f));
    }

    private static void vertex(Ctx c, VertexConsumer vc, TextureAtlasSprite s, int rgb, float x, float y, float z,
                               float u, float v, float nx, float ny, float nz) {
        PoseStack.Pose last = c.ps.last();
        vc.addVertex(last.pose(), x, y, z).setColor((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, c.quadAlpha)
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
