package dev.emergence.mod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.emergence.core.assembly.Dir;
import dev.emergence.core.assembly.PartKind;
import dev.emergence.core.materials.Material;
import dev.emergence.mod.block.AssemblyBlockEntity;
import dev.emergence.mod.part.PlacedPart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Renders the parts of an assembly block at their physical dimensions, rotating parts that belong to
 * a moving rigid body and tinting hot parts with their incandescent (black-body) colour.
 */
public final class AssemblyRenderer implements BlockEntityRenderer<AssemblyBlockEntity> {
    private static final float C = 1f / 16f;
    private TextureAtlasSprite sprite;

    public AssemblyRenderer(BlockEntityRendererProvider.Context ctx) {
    }

    private TextureAtlasSprite sprite() {
        if (sprite == null) {
            sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                    .apply(ResourceLocation.withDefaultNamespace("block/white_concrete"));
        }
        return sprite;
    }

    @Override
    public boolean shouldRenderOffScreen(AssemblyBlockEntity be) {
        return true;
    }

    /** Rotating parts (disks, crank arms) may sweep outside their own block. */
    @Override
    public net.minecraft.world.phys.AABB getRenderBoundingBox(AssemblyBlockEntity be) {
        return new net.minecraft.world.phys.AABB(be.getBlockPos()).inflate(3);
    }

    @Override
    public int getViewDistance() {
        return 96;
    }

    @Override
    public void render(AssemblyBlockEntity be, float partialTick, PoseStack ps, MultiBufferSource buffers, int light, int overlay) {
        Level level = be.getLevel();
        if (level == null) return;
        VertexConsumer vc = buffers.getBuffer(RenderType.solid());
        BlockPos pos = be.getBlockPos();
        double now = level.getGameTime() + partialTick;
        for (PlacedPart p : be.parts()) {
            ps.pushPose();
            ClientSimState.Pose pose = ClientSimState.pose(p.id);
            if (pose != null) {
                double ang = pose.angle() + pose.omega() * (now - pose.time()) / 20.0;
                float lx = (float) (pose.px() - pos.getX()), ly = (float) (pose.py() - pos.getY()), lz = (float) (pose.pz() - pos.getZ());
                ps.translate(lx, ly, lz);
                ps.mulPose(new Quaternionf().rotateAxis((float) ang, (float) pose.ax(), (float) pose.ay(), (float) pose.az()));
                ps.translate(-lx, -ly, -lz);
            }
            Material m = p.spec.resolveMaterial();
            float temp = ClientSimState.temperature(p.id, 293f);
            int rgb = m.color();
            int partLight = light;
            if (temp > 700) {
                rgb = glow(rgb, temp);
                if (temp > 798) partLight = LightTexture.FULL_BRIGHT;
            }
            drawPart(ps.last().pose(), vc, p, rgb, partLight, be);
            ps.popPose();
        }
    }

    private void drawPart(Matrix4f mat, VertexConsumer vc, PlacedPart p, int rgb, int light, AssemblyBlockEntity be) {
        float x0 = p.x0 * C, y0 = p.y0 * C, z0 = p.z0 * C, x1 = (p.x1 + 1) * C, y1 = (p.y1 + 1) * C, z1 = (p.z1 + 1) * C;
        float cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, cz = (z0 + z1) / 2;
        int a = p.axis.axis();
        var prm = p.spec.params();
        switch (p.kind) {
            case SHAFT -> {
                float r = (float) Math.max(prm.getOrDefault("diameter", 0.02) / 2, 0.006);
                float[] b = axialBox(a, x0, y0, z0, x1, y1, z1, cx, cy, cz, r);
                box(mat, vc, b, rgb, light);
            }
            case BEARING -> {
                box(mat, vc, shrink(x0, y0, z0, x1, y1, z1, 0.08f * C), darken(rgb, 0.7f), light);
            }
            case BLOCK -> {
                double fill = prm.getOrDefault("fill", 1.0);
                float[] b = {x0, y0, z0, x1, y1, z1};
                if (fill < 1) {
                    float len = b[a + 3] - b[a];
                    float th = (float) Math.max(len * fill, 0.01);
                    float mid = (b[a] + b[a + 3]) / 2;
                    b[a] = mid - th / 2;
                    b[a + 3] = mid + th / 2;
                }
                box(mat, vc, b, rgb, light);
            }
            case MAGNET -> {
                float[] b = shrink(x0, y0, z0, x1, y1, z1, 0.1f * (x1 - x0));
                float mid = (b[a] + b[a + 3]) / 2;
                float[] north = b.clone(), south = b.clone();
                boolean pos = p.axis.sign() > 0;
                if (pos) {
                    north[a] = mid;
                    south[a + 3] = mid;
                } else {
                    north[a + 3] = mid;
                    south[a] = mid;
                }
                box(mat, vc, north, mix(rgb, 0xC0392B, 0.6f), light);
                box(mat, vc, south, mix(rgb, 0x2E5FA8, 0.6f), light);
            }
            case COIL -> {
                float[] b = shrink(x0, y0, z0, x1, y1, z1, 0.05f * C);
                box(mat, vc, b, 0xB87333, light);
                // bobbin core visible at the ends
                float[] core = axialBox(a, x0, y0, z0, x1, y1, z1, cx, cy, cz, 0.15f * Math.min(x1 - x0, Math.min(y1 - y0, z1 - z0)));
                core[a] -= 0.002f;
                core[a + 3] += 0.002f;
                box(mat, vc, core, 0x3A2A1A, light);
            }
            case WIRE, INSULATED_WIRE -> {
                float r = (float) Math.max(prm.getOrDefault("diameter", 1.5e-3) / 2, 0.004);
                if (p.kind == PartKind.INSULATED_WIRE) r = Math.max(r * 1.8f, 0.006f);
                int col = p.kind == PartKind.INSULATED_WIRE ? 0x2F6FB0 : rgb;
                box(mat, vc, new float[]{cx - r, cy - r, cz - r, cx + r, cy + r, cz + r}, col, light);
                for (Dir d : Dir.values()) {
                    if (!connects(be, p, d)) continue;
                    float[] arm = {cx - r, cy - r, cz - r, cx + r, cy + r, cz + r};
                    int k = d.axis();
                    if (d.sign() > 0) arm[k + 3] = new float[]{x1, y1, z1}[k];
                    else arm[k] = new float[]{x0, y0, z0}[k];
                    box(mat, vc, arm, col, light);
                }
            }
            case RESISTOR, CAPACITOR, DIODE, SWITCH -> {
                float body = p.kind == PartKind.CAPACITOR ? 0.3f : 0.2f;
                float[] b = axialBox(a, x0, y0, z0, x1, y1, z1, cx, cy, cz, body * (x1 - x0));
                float len = b[a + 3] - b[a];
                float[] lead = axialBox(a, x0, y0, z0, x1, y1, z1, cx, cy, cz, 0.03f * (x1 - x0));
                box(mat, vc, lead, 0xB0B0B0, light);
                b[a] += len * 0.22f;
                b[a + 3] -= len * 0.22f;
                int col = switch (p.kind) {
                    case RESISTOR -> 0xD8C49A;
                    case CAPACITOR -> 0x2B5DA8;
                    case DIODE -> 0x1E1E1E;
                    default -> p.closed ? 0x3FA34D : 0xA33F3F;
                };
                box(mat, vc, b, col, light);
                if (p.kind == PartKind.DIODE) {
                    float[] band = b.clone();
                    boolean pos = p.axis.sign() > 0;
                    float bl = (b[a + 3] - b[a]) * 0.2f;
                    if (pos) band[a] = band[a + 3] - bl;
                    else band[a + 3] = band[a] + bl;
                    for (int k = 0; k < 3; k++) if (k != a) {
                        band[k] -= 0.001f;
                        band[k + 3] += 0.001f;
                    }
                    box(mat, vc, band, 0xD0D0D0, light);
                }
            }
            case GEAR -> {
                float rr = (float) (prm.getOrDefault("pitch_radius", 0.5 * (x1 - x0)) * 0.92);
                float[] disk = axialBox(a, x0, y0, z0, x1, y1, z1, cx, cy, cz, rr);
                float len = disk[a + 3] - disk[a];
                disk[a] += len * 0.1f;
                disk[a + 3] -= len * 0.1f;
                box(mat, vc, disk, rgb, light);
                int teeth = (int) Math.round(prm.getOrDefault("teeth", 24.0));
                float tw = (float) (Math.PI * rr / teeth) * 0.9f;
                for (int t = 0; t < teeth; t++) {
                    double ang = 2 * Math.PI * t / teeth;
                    toothBox(mat, vc, a, cx, cy, cz, disk[a], disk[a + 3], rr, tw, ang, darken(rgb, 0.85f), light);
                }
            }
            case CRANK -> {
                float rr = (float) (double) prm.getOrDefault("radius", 0.15);
                float w = 0.015f;
                int t1 = (a + 1) % 3;
                float[] arm = {cx - w, cy - w, cz - w, cx + w, cy + w, cz + w};
                arm[t1 + 3] = new float[]{cx, cy, cz}[t1] + rr;
                box(mat, vc, arm, rgb, light);
                float[] handle = {cx - w, cy - w, cz - w, cx + w, cy + w, cz + w};
                handle[t1] = new float[]{cx, cy, cz}[t1] + rr - 2 * w;
                handle[t1 + 3] = new float[]{cx, cy, cz}[t1] + rr;
                handle[a + (p.axis.sign() > 0 ? 3 : 0)] += (p.axis.sign() > 0 ? 1 : -1) * 0.08f;
                box(mat, vc, handle, 0x5A3A22, light);
            }
            case BRUSH -> box(mat, vc, shrink(x0, y0, z0, x1, y1, z1, 0.2f * (x1 - x0)), 0x2D2D30, light);
        }
    }

    private static boolean connects(AssemblyBlockEntity be, PlacedPart p, Dir d) {
        int nx = p.x0 + d.dx, ny = p.y0 + d.dy, nz = p.z0 + d.dz;
        AssemblyBlockEntity target = be;
        if (nx < 0 || ny < 0 || nz < 0 || nx > 15 || ny > 15 || nz > 15) {
            BlockPos np = be.getBlockPos().offset(d.dx, d.dy, d.dz);
            if (!(be.getLevel().getBlockEntity(np) instanceof AssemblyBlockEntity o)) return false;
            target = o;
            nx = Math.floorMod(nx, 16);
            ny = Math.floorMod(ny, 16);
            nz = Math.floorMod(nz, 16);
        }
        PlacedPart q = target.partAt(nx, ny, nz);
        if (q == null) return false;
        if (q.kind == PartKind.WIRE || q.kind == PartKind.INSULATED_WIRE) return true;
        if (q.kind.leaded) return true;
        return p.kind == PartKind.WIRE && q.spec.resolveMaterial().isConductor();
    }

    private static float[] axialBox(int a, float x0, float y0, float z0, float x1, float y1, float z1, float cx, float cy, float cz, float r) {
        float[] b = {cx - r, cy - r, cz - r, cx + r, cy + r, cz + r};
        float[] lo = {x0, y0, z0}, hi = {x1, y1, z1};
        b[a] = lo[a];
        b[a + 3] = hi[a];
        return b;
    }

    private static float[] shrink(float x0, float y0, float z0, float x1, float y1, float z1, float s) {
        return new float[]{x0 + s, y0 + s, z0 + s, x1 - s, y1 - s, z1 - s};
    }

    private void toothBox(Matrix4f mat, VertexConsumer vc, int a, float cx, float cy, float cz, float lo, float hi,
                          float r, float w, double ang, int rgb, int light) {
        int t1 = (a + 1) % 3, t2 = (a + 2) % 3;
        float c1 = (float) Math.cos(ang), s1 = (float) Math.sin(ang);
        float[] ctr = {cx, cy, cz};
        float rr = r + w * 0.5f;
        float[] b = new float[6];
        b[a] = lo;
        b[a + 3] = hi;
        float p1 = ctr[t1] + c1 * rr, p2 = ctr[t2] + s1 * rr;
        b[t1] = p1 - w / 2;
        b[t1 + 3] = p1 + w / 2;
        b[t2] = p2 - w / 2;
        b[t2 + 3] = p2 + w / 2;
        box(mat, vc, b, rgb, light);
    }

    private void box(Matrix4f m, VertexConsumer vc, float[] b, int rgb, int light) {
        TextureAtlasSprite s = sprite();
        float u0 = s.getU0(), u1 = s.getU1(), v0 = s.getV0(), v1 = s.getV1();
        float x0 = b[0], y0 = b[1], z0 = b[2], x1 = b[3], y1 = b[4], z1 = b[5];
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, bl = rgb & 255;
        // vanilla face shading: up 1.0, down 0.5, N/S 0.8, E/W 0.6
        quad(m, vc, r, g, bl, 1.0f, light, u0, v0, u1, v1, 0, 1, 0, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
        quad(m, vc, r, g, bl, 0.5f, light, u0, v0, u1, v1, 0, -1, 0, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        quad(m, vc, r, g, bl, 0.8f, light, u0, v0, u1, v1, 0, 0, -1, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        quad(m, vc, r, g, bl, 0.8f, light, u0, v0, u1, v1, 0, 0, 1, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        quad(m, vc, r, g, bl, 0.6f, light, u0, v0, u1, v1, -1, 0, 0, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        quad(m, vc, r, g, bl, 0.6f, light, u0, v0, u1, v1, 1, 0, 0, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
    }

    private static void quad(Matrix4f m, VertexConsumer vc, int r, int g, int b, float shade, int light,
                             float u0, float v0, float u1, float v1, float nx, float ny, float nz,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz) {
        int rr = (int) (r * shade), gg = (int) (g * shade), bb = (int) (b * shade);
        vc.addVertex(m, ax, ay, az).setColor(rr, gg, bb, 255).setUv(u0, v0).setLight(light).setNormal(nx, ny, nz);
        vc.addVertex(m, bx, by, bz).setColor(rr, gg, bb, 255).setUv(u0, v1).setLight(light).setNormal(nx, ny, nz);
        vc.addVertex(m, cx, cy, cz).setColor(rr, gg, bb, 255).setUv(u1, v1).setLight(light).setNormal(nx, ny, nz);
        vc.addVertex(m, dx, dy, dz).setColor(rr, gg, bb, 255).setUv(u1, v0).setLight(light).setNormal(nx, ny, nz);
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
     * Approximate visible colour of a black body at temperature T (Tanner Helland's fit to CIE
     * blackbody chromaticities), blended over the material colour from 700 K (barely visible dull red)
     * to full incandescence above ~1300 K.
     */
    static int glow(int base, float t) {
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
        // perceived brightness rises steeply with T (Stefan–Boltzmann + visible fraction)
        double intensity = Math.min(1.0, Math.pow(Math.max(0, (t - 700) / 900.0), 1.5));
        int bb = ((int) clamp(r) << 16) | ((int) clamp(g) << 8) | (int) clamp(b);
        return mix(base, bb, (float) intensity);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(255, v));
    }
}
