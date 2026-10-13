package dev.emergence.mod.part;

import dev.emergence.core.assembly.Dir;
import dev.emergence.core.assembly.PartKind;
import dev.emergence.mod.block.AssemblyBlockEntity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

import static dev.emergence.mod.part.PartLinks.*;

/**
 * Hitboxes that follow what is drawn: a thin shaft, wire or pipe is thin to the crosshair and to the player, a plate
 * is as thick as its fill, a gear as wide as its teeth. Each part gives a few boxes in its block's coordinates (0–1),
 * from the same geometry the renderer uses — the part's frame (axis, lead axis) and the drawn sizes of its model —
 * clipped to the part's cells. Moving bodies keep the boxes of where they were placed.
 */
public final class PartShapes {
    private PartShapes() {}

    private static final float MIN_WIRE_RADIUS = 0.07f, MIN_SHAFT_RADIUS = 0.09f;

    /** Boxes of part p of block be, in block-local coordinates. */
    public static List<AABB> boxes(AssemblyBlockEntity be, PlacedPart p) {
        List<AABB> out = new ArrayList<>(4);
        float[] ext = {p.x1 - p.x0 + 1, p.y1 - p.y0 + 1, p.z1 - p.z0 + 1};
        int a = p.axis.axis(), l = leadAxis(p);
        float ez = ext[a], m = Math.min(ext[l], ext[3 - a - l]);
        var prm = p.spec.params();
        switch (p.kind) {
            case BLOCK -> {
                float fill = (float) (double) prm.getOrDefault("fill", 1.0), shift = 0;
                if (fill < 0.999f) {
                    int below = faceCover(be, p, p.axis.opposite()), above = faceCover(be, p, p.axis);
                    if (below != above) shift = (below > above ? -1 : 1) * ez * (1 - fill) / 2;
                }
                model(out, be, p, -ext[l] / 2, ext[l] / 2, -ext[3 - a - l] / 2, ext[3 - a - l] / 2, shift - ez * fill / 2, shift + ez * fill / 2);
            }
            case SHAFT -> {
                double d = prm.getOrDefault("diameter", 0.02) * 16.0;
                float r = (float) Math.max(Math.min(d / 2, 0.475 * m), MIN_SHAFT_RADIUS);
                model(out, be, p, -r, r, -r, r, -ez / 2, ez / 2);
            }
            case WIRE, INSULATED_WIRE -> {
                double d = prm.getOrDefault("diameter", 1.5e-3) * 16.0;
                float r = (float) Math.max(Math.min(d / 2, 0.45), MIN_WIRE_RADIUS);
                if (p.kind == PartKind.INSULATED_WIRE) r = Math.max(r * 1.7f, MIN_WIRE_RADIUS * 1.5f);
                r = Math.max(r * 1.05f, 0.1f); // the joint, and a little to aim at
                grid(out, be, p, -r, r, -r, r, -r, r);
                boolean any = false;
                for (Dir d2 : Dir.values()) {
                    if (neighbour(be, p, d2) == null) continue;
                    any = true;
                    arm(out, be, p, d2, r, ext[d2.axis()] / 2);
                }
                if (!any) {
                    arm(out, be, p, p.axis, r, ez / 2);
                    arm(out, be, p, p.axis.opposite(), r, ez / 2);
                }
            }
            case PIPE -> {
                double wall = prm.getOrDefault("wall", 0.002), bore = prm.getOrDefault("bore", 0.025);
                float ro = (float) Math.max((bore / 2 + wall) * 16 * 1.35, 0.1);
                model(out, be, p, -ro, ro, -ro, ro, -ez / 2, ez / 2);
                for (Dir d2 : Dir.values()) {
                    if (d2.axis() == a) continue;
                    if (across(be, p, d2).stream().anyMatch(q -> q.kind == PartKind.TANK || (q.kind.holdsFluid() && q.axis.axis() == d2.axis()))) {
                        arm(out, be, p, d2, ro, ext[d2.axis()] / 2);
                    }
                }
            }
            case GEAR -> {
                float rp = (float) (prm.getOrDefault("pitch_radius", 0.5 * m / 16.0) * 16.0);
                int z = (int) Math.max(4, Math.min(400, Math.round(prm.getOrDefault("teeth", 24.0))));
                float ra = rp + 2 * rp / z;
                float th = (float) Math.min(prm.getOrDefault("thickness", 0.8 * ez / 16.0) * 16.0, ez);
                model(out, be, p, -ra, ra, -ra, ra, -0.65f * th, 0.65f * th); // to the faces of the hub boss
            }
            case SPRING -> {
                double coil = prm.getOrDefault("coil", 0.6 / 16) * 16;
                float r = (float) Math.min(0.48, coil / 2) + 0.03f;
                model(out, be, p, -r, r, -r, r, -ez / 2, ez / 2);
            }
            case LINK -> {
                // the bar where it was built (it moves; the outline is for aiming at it)
                float w = (float) Math.max(0.06, Math.min(0.45, prm.getOrDefault("width", 0.3 / 16) * 8)) + 0.03f;
                model(out, be, p, -w, w, -w, w, -ez / 2, ez / 2);
            }
            case BEARING -> model(out, be, p, -0.48f * m, 0.48f * m, -0.48f * m, 0.48f * m, -0.21f * ez, 0.21f * ez);
            case LINEAR_BEARING -> model(out, be, p, -0.48f * m, 0.48f * m, -0.48f * m, 0.48f * m, -0.25f * ez, 0.25f * ez);
            case BRUSH -> model(out, be, p, -0.45f * m, 0.45f * m, -0.23f * m, 0.23f * m, -0.5f * ez, 0.48f * ez);
            case RESISTOR -> model(out, be, p, -0.14f * m, 0.14f * m, -0.14f * m, 0.14f * m, -0.5f * ez, 0.5f * ez);
            case CAPACITOR -> model(out, be, p, -0.27f * m, 0.27f * m, -0.27f * m, 0.27f * m, -0.5f * ez, 0.5f * ez);
            case DIODE -> model(out, be, p, -0.12f * m, 0.12f * m, -0.12f * m, 0.12f * m, -0.48f * ez, 0.48f * ez);
            case SWITCH -> model(out, be, p, -0.21f, 0.21f, -0.46f, 0.01f, -0.5f * ez, 0.5f * ez);
            case VALVE -> model(out, be, p, -0.21f, 0.21f, -0.2f, 0.5f, -0.485f * ez, 0.485f * ez);
            case CRANK -> {
                float cr = (float) (prm.getOrDefault("radius", 0.15) * 16.0);
                model(out, be, p, -0.2f, 0.0856f + cr, -0.2f, 0.2f, -0.5f, 0.5f);
            }
            case CYLINDER -> model(out, be, p, -0.42f * m, 0.42f * m, -0.42f * m, 0.42f * m, -0.5f * ez, 0.43f * ez);
            case ROTOR -> {
                float rt = 0.45f * m;
                model(out, be, p, -rt, rt, -rt, rt, -ez / 2, ez / 2);
            }
            case TRANSISTOR -> model(out, be, p, -0.5f, 0.5f, -0.33f, 0.095f, -0.5f, 0.5f);
            case LOGIC, OPAMP -> model(out, be, p, -0.48f, 0.48f, -0.33f, -0.12f, -0.48f, 0.48f);
            case LED -> model(out, be, p, -0.46f, 0.46f, -0.33f, 0.2f, -0.5f, 0.5f);
            case CHIP -> model(out, be, p, -0.5f, 0.5f, -0.5f, -0.18f, -0.5f, 0.5f);
            case SOURCE -> model(out, be, p, -0.3f, 0.3f, -0.5f, -0.3f, -0.3f, 0.3f);
            case GM_TUBE -> model(out, be, p, -0.16f, 0.16f, -0.16f, 0.16f, -0.5f, 0.5f);
            case POTENTIOMETER -> model(out, be, p, -0.36f, 0.36f, -0.36f, 0.36f, -0.5f * ez, 0.5f * ez);
            case MIRROR -> {
                if (prm.getOrDefault("tilt", 0.0) == 0 && prm.getOrDefault("radius", 0.0) == 0 && !prm.containsKey("aim_x")
                        && !prm.containsKey("aim_y") && !prm.containsKey("aim_z")) {
                    float t = (float) Math.max(prm.getOrDefault("thickness", 0.003) * 8, 0.04);
                    model(out, be, p, -ext[l] / 2, ext[l] / 2, -ext[3 - a - l] / 2, ext[3 - a - l] / 2, -t, t);
                } else grid(out, be, p, -ext[0] / 2, ext[0] / 2, -ext[1] / 2, ext[1] / 2, -ext[2] / 2, ext[2] / 2);
            }
            case LENS -> {
                var cp = p.toCore(net.minecraft.core.BlockPos.ZERO, 16);
                float rim = (float) (dev.emergence.core.assembly.PartGeometry.lensRim(cp) * 16);
                float h = (float) Math.min(ez / 2, Math.max(dev.emergence.core.assembly.PartGeometry.lensThickness(cp) * 8, 0.06));
                model(out, be, p, -rim, rim, -rim, rim, -h, h);
            }
            case NUT -> model(out, be, p, -0.45f * m, 0.45f * m, -0.45f * m, 0.45f * m, -0.42f * ez, 0.42f * ez);
            case MAGNET -> {
                String[] key = {"sx", "sy", "sz"};
                float[] h = new float[3];
                for (int k = 0; k < 3; k++) h[k] = (float) Math.min(prm.getOrDefault(key[k], 0.8 * ext[k] / 16.0) * 16.0, ext[k]) / 2;
                grid(out, be, p, -h[0], h[0], -h[1], h[1], -h[2], h[2]);
            }
            default -> grid(out, be, p, -ext[0] / 2, ext[0] / 2, -ext[1] / 2, ext[1] / 2, -ext[2] / 2, ext[2] / 2);
        }
        return out;
    }

    /** Lead axis (model X) of a part, as the renderer's frame. */
    static int leadAxis(PlacedPart p) {
        int a = p.axis.axis(), def = a == 0 ? 2 : 0;
        int l = (int) Math.round(p.spec.params().getOrDefault("lead_axis", (double) def));
        return l == a || l < 0 || l > 2 ? def : l;
    }

    /**
     * A box given in the part's model frame [cells, from the part's centre]: X the lead axis, Y = Z × X, Z the
     * (signed) part axis — with the renderer's rule that boards and switches off the vertical lie base down.
     */
    private static void model(List<AABB> out, AssemblyBlockEntity be, PlacedPart p, float x0, float x1, float y0, float y1, float z0, float z1) {
        float[] z = {p.axis.dx, p.axis.dy, p.axis.dz};
        int l = leadAxis(p);
        float[] x = {l == 0 ? 1 : 0, l == 1 ? 1 : 0, l == 2 ? 1 : 0};
        float[] y = cross(z, x);
        boolean lying = p.kind == PartKind.SWITCH || p.kind == PartKind.VALVE || p.kind == PartKind.LED || p.kind == PartKind.TRANSISTOR
                || p.kind == PartKind.LOGIC || p.kind == PartKind.CHIP || p.kind == PartKind.OPAMP;
        if (lying && p.axis.axis() != 1) {
            y = new float[]{0, 1, 0};
            x = cross(y, z);
        }
        float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (float mx : new float[]{x0, x1})
            for (float my : new float[]{y0, y1})
                for (float mz : new float[]{z0, z1})
                    for (int k = 0; k < 3; k++) {
                        float w = mx * x[k] + my * y[k] + mz * z[k];
                        lo[k] = Math.min(lo[k], w);
                        hi[k] = Math.max(hi[k], w);
                    }
        grid(out, be, p, lo[0], hi[0], lo[1], hi[1], lo[2], hi[2]);
    }

    /** An arm from the part's centre to its face d, of half-width r [cells]. */
    private static void arm(List<AABB> out, AssemblyBlockEntity be, PlacedPart p, Dir d, float r, float len) {
        float[] lo = {-r, -r, -r}, hi = {r, r, r};
        int k = d.axis();
        if (d.sign() > 0) hi[k] = len;
        else lo[k] = -len;
        grid(out, be, p, lo[0], hi[0], lo[1], hi[1], lo[2], hi[2]);
    }

    /** A box in grid axes [cells, from the part's centre], clipped to the part's cells, converted to block units. */
    private static void grid(List<AABB> out, AssemblyBlockEntity be, PlacedPart p, float x0, float x1, float y0, float y1, float z0, float z1) {
        double s = 1.0 / be.resolution();
        double cx = (p.x0 + p.x1 + 1) * 0.5, cy = (p.y0 + p.y1 + 1) * 0.5, cz = (p.z0 + p.z1 + 1) * 0.5;
        double ax = Math.max(cx + x0, p.x0), bx = Math.min(cx + x1, p.x1 + 1);
        double ay = Math.max(cy + y0, p.y0), by = Math.min(cy + y1, p.y1 + 1);
        double az = Math.max(cz + z0, p.z0), bz = Math.min(cz + z1, p.z1 + 1);
        if (bx - ax < 1e-4 || by - ay < 1e-4 || bz - az < 1e-4) return;
        out.add(new AABB(ax * s, ay * s, az * s, bx * s, by * s, bz * s));
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
}
