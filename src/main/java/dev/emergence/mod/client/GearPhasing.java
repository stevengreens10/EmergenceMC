package dev.emergence.mod.client;

import dev.emergence.core.assembly.PartKind;
import dev.emergence.mod.block.AssemblyBlockEntity;
import dev.emergence.mod.part.PlacedPart;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

/**
 * Visual tooth phasing of meshing gears. The simulation couples meshing gears' speeds through the
 * gear mesh but has no notion of where an individual tooth is, so each gear is drawn with an extra
 * rotation (less than one tooth pitch) that places its teeth in the gaps of the lower-id gear it
 * meshes with. Purely cosmetic.
 * <p>
 * Angles are measured in a canonical frame per axis (Z along +axis, X along the default reference
 * axis). With the partner's teeth at canonical angles T_p + k·p_p and α the direction from the partner
 * to this gear, rolling contact (r_p·θ_p = −r_u·θ_u along the common tangent) puts this gear's gaps at
 * φ − r_p (T_p − α) / r_u with φ = α + π.
 */
final class GearPhasing {
    private GearPhasing() {}

    private record Partner(PlacedPart part, BlockPos pos, int res, float rp, int z, double alpha) {}

    private record Cached(Partner partner, long time) {}

    private static final Map<Long, Cached> PARTNERS = new HashMap<>();

    /** Extra model-frame rotation [rad] about the gear axis for gear {@code p} in block {@code be}. */
    static float phase(AssemblyBlockEntity be, PlacedPart p, float rp, int z, double bodyAngle, ClientSimState.Pose pose) {
        return (float) phase(be.getLevel(), be.getBlockPos(), be.resolution(), p, rp, z, rotation(p, bodyAngle, pose), 0);
    }

    private static double phase(Level level, BlockPos pos, int res, PlacedPart p, float rp, int z, double rotC, int depth) {
        Partner q = depth > 8 ? null : partner(level, pos, res, p, rp, z);
        if (q == null) return 0;
        double pitchU = 2 * Math.PI / z, pitchP = 2 * Math.PI / q.z;
        ClientSimState.Pose qp = ClientSimState.pose(q.part.id);
        double qBody = qp == null ? 0 : ClientSimState.bodyAngle(qp);
        double qRot = rotation(q.part, qBody, qp);
        double qPhase = phase(level, q.pos, q.res, q.part, q.rp, q.z, qRot, depth + 1);
        double tP = q.part.axis.sign() * qPhase + qRot;
        double phi = q.alpha + Math.PI;
        double desired = phi - q.rp * (tP - q.alpha) / rp + pitchU / 2;
        double m = p.axis.sign() * (desired - rotC);
        m = m - Math.floor(m / pitchU) * pitchU;
        return m;
    }

    /** Canonical-frame rotation of the gear's rigid body. */
    private static double rotation(PlacedPart p, double bodyAngle, ClientSimState.Pose pose) {
        if (pose == null) return 0;
        int a = p.axis.axis();
        double dot = a == 0 ? pose.ax() : a == 1 ? pose.ay() : pose.az();
        return bodyAngle * Math.signum(dot);
    }

    private static Partner partner(Level level, BlockPos pos, int res, PlacedPart p, float rp, int z) {
        long now = level.getGameTime();
        Cached c = PARTNERS.get(p.id);
        if (c != null && now - c.time < 20 && now >= c.time) return c.partner;
        Partner found = search(level, pos, res, p, rp, z);
        if (PARTNERS.size() > 4096) PARTNERS.clear();
        PARTNERS.put(p.id, new Cached(found, now));
        return found;
    }

    private static Partner search(Level level, BlockPos pos, int res, PlacedPart p, float rp, int z) {
        int a = p.axis.axis();
        double[] cu = centre(pos, res, p);
        double mod = 2 * rp / z;
        int reach = (int) Math.ceil((rp + 3.0) / res) + 1;
        for (int dx = -reach; dx <= reach; dx++)
            for (int dy = -reach; dy <= reach; dy++)
                for (int dz = -reach; dz <= reach; dz++) {
                    BlockPos np = pos.offset(dx, dy, dz);
                    if (!(level.getBlockEntity(np) instanceof AssemblyBlockEntity o) || o.resolution() != res) continue;
                    for (PlacedPart q : o.parts()) {
                        if (q.kind != PartKind.GEAR || q.id >= p.id || q.axis.axis() != a) continue;
                        double[] cq = centre(np, res, q);
                        if (Math.abs(cq[a] - cu[a]) > 0.75) continue; // not in the same plane
                        var prm = q.spec.params();
                        float rq = (float) (prm.getOrDefault("pitch_radius", 0.5 / 16.0) * 16.0);
                        int zq = (int) Math.round(prm.getOrDefault("teeth", 24.0));
                        double ex = cu[0] - cq[0], ey = cu[1] - cq[1], ez = cu[2] - cq[2];
                        double[] d = {ex, ey, ez};
                        d[a] = 0;
                        double dist = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
                        if (Math.abs(dist - (rp + rq)) > 0.35 * mod) continue;
                        // canonical frame: X = reference axis, Y = Z × X
                        int xa = a == 0 ? 2 : 0;
                        int ya = 3 - a - xa;
                        // Z × X is −y for (Z = x, X = z), −z for (Z = y, X = x) and +y for (Z = z, X = x)
                        double ySign = a == 2 ? 1 : -1;
                        double alpha = Math.atan2(ySign * d[ya], d[xa]);
                        return new Partner(q, np, res, rq, zq, alpha);
                    }
                }
        return null;
    }

    /** Gear centre in global cell units. */
    private static double[] centre(BlockPos pos, int res, PlacedPart p) {
        return new double[]{pos.getX() * res + (p.x0 + p.x1 + 1) * 0.5, pos.getY() * res + (p.y0 + p.y1 + 1) * 0.5,
                pos.getZ() * res + (p.z0 + p.z1 + 1) * 0.5};
    }
}
