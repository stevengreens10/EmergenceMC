package dev.emergence.mod.client;

import dev.emergence.core.assembly.PartKind;
import dev.emergence.mod.EmergenceMod;
import dev.emergence.mod.block.AssemblyBlock;
import dev.emergence.mod.block.AssemblyBlockEntity;
import dev.emergence.mod.part.PartShapes;
import dev.emergence.mod.part.PlacedPart;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The showcase's hands: it works the demo world as a player does. To use a part it flies (creative) to a free spot
 * within reach that has a clear line of sight to it, turns to look at it, waits until the crosshair is on that very
 * part, and then presses the use or attack key — in-game key state, not OS input — so the click travels the same road
 * as a player's: the client's pick against the parts' outlines, the item in hand, the packet, the server's block and
 * item handlers. Holding a crank is holding the use key (the client repeats the click every four ticks). What it
 * cannot do — reach a part, or find the crosshair on it — is reported as a failure, not worked around.
 */
final class PlayerHand {
    private PlayerHand() {}

    /** A part to work: the block it is in, its kind (the first of that kind in the block), and optionally the face to click. */
    record Target(BlockPos block, PartKind kind, Direction face) {
        Target(BlockPos block, PartKind kind) {
            this(block, kind, null);
        }
    }

    /** What a click on a part should visibly do: nothing to check, flip it (a switch, valve or brake), or take it away. */
    enum Effect { NONE, TOGGLED, REMOVED }

    /** A client-side action run tick by tick; {@link #tick} returns true when it is over (done or failed). */
    interface Act {
        boolean tick(Minecraft mc);

        /** What the player is doing, for the showcase's caption (null: say nothing new). */
        default String what() {
            return null;
        }
    }

    /** A player's eye heights [blocks] and how far a creative player reaches (a little short of its 5 blocks). */
    static final double EYE_STANDING = 1.62, EYE_CROUCHING = 1.27, REACH = 4.5;

    /** Problems the hands met, for the report. */
    static final List<String> FAILURES = new ArrayList<>();

    /** The part the use key is being held on (null: none): the hands keep looking at it while held. */
    private static Vec3 heldAim;

    /** Called every client tick: a held key stays down and the eyes stay on what it is held on. */
    static void keepHolding(Minecraft mc) {
        if (heldAim == null || mc.player == null) return;
        face(mc, heldAim);
        mc.options.keyUse.setDown(true);
    }

    static void releaseAll(Minecraft mc) {
        heldAim = null;
        mc.options.keyUse.setDown(false);
        mc.options.keyShift.setDown(false);
        mc.options.keySprint.setDown(false);
    }

    /** Right-click the part once (a switch, valve or brake), with the main hand empty. */
    static Act click(String what, Target t) {
        return new Reach(what, t, ItemStack.EMPTY, false, mc -> KeyMapping.click(mc.options.keyUse.getKey())).expect(Effect.TOGGLED);
    }

    /** Right-click the part once holding an item (clip the data logger on, place a part on a face). */
    static Act clickWith(String what, Target t, ItemStack item) {
        return new Reach(what, t, item, false, mc -> KeyMapping.click(mc.options.keyUse.getKey()))
                .expect(t.kind == PartKind.SWITCH || t.kind == PartKind.VALVE || t.kind == PartKind.BRAKE ? Effect.TOGGLED : Effect.NONE);
    }

    /** Left-click the part (take it away). */
    static Act attack(String what, Target t) {
        return new Reach(what, t, ItemStack.EMPTY, false, mc -> KeyMapping.click(mc.options.keyAttack.getKey())).expect(Effect.REMOVED);
    }

    /** Hold the use key on the part (turn a crank, push a slide); sneaking turns or pushes the other way. Stays held until {@link #release}. */
    static Act hold(String what, Target t, boolean sneak) {
        return holdWith(what, t, sneak, ItemStack.EMPTY);
    }

    /** Hold the use key on the part with an item in hand (a crank turned with the data logger in the other hand, as it were). */
    static Act holdWith(String what, Target t, boolean sneak, ItemStack item) {
        return new Reach(what, t, item, sneak, mc -> {
            heldAim = mc.hitResult.getLocation();
            mc.options.keyUse.setDown(true);
        });
    }

    /** Let go of whatever is held. */
    static Act release() {
        return new Act() {
            @Override
            public boolean tick(Minecraft mc) {
                releaseAll(mc);
                return true;
            }

            @Override
            public String what() {
                return "Lets go";
            }
        };
    }

    /**
     * Right-click the air with the item in hand ({@code sneak}: sneaking) until {@code done} holds, at most
     * {@code max} times — e.g. stepping the data logger's channel until its screen shows the one wanted. {@code done}
     * answers null while it cannot tell yet (the screen's reading has not come back): the hand waits.
     */
    static Act useInAirUntil(String what, boolean sneak, java.util.function.Supplier<Boolean> done, int max) {
        int[] clicks = {0}, pause = {0}, ticks = {0};
        Vec3[] spot = {null};
        return named(what, mc -> {
            if (ticks[0]++ > 1800) { // never wait on anything for ever (a busy server answers slowly)
                fail(what + ": gave up after 90 s");
                mc.options.keyShift.setDown(false);
                return true;
            }
            if (spot[0] == null) {
                // first step out from under the machine to where there is open sky overhead, as a player would
                spot[0] = openSky(mc);
                if (spot[0] == null) {
                    fail(what + ": no open sky within reach to click into");
                    return true;
                }
                if (mc.player.position().distanceTo(spot[0]) > 0.3) {
                    var server = mc.getSingleplayerServer();
                    Vec3 to = spot[0];
                    server.execute(() -> {
                        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                        p.getAbilities().flying = false;
                        p.onUpdateAbilities();
                        p.teleportTo(server.overworld(), to.x, to.y, to.z, p.getYRot(), -90);
                    });
                }
                return false;
            }
            if (mc.player.position().distanceTo(spot[0]) > 0.3) return false;
            mc.player.setXRot(-90);
            mc.player.xRotO = -90;
            Boolean ok = done.get();
            if (Boolean.TRUE.equals(ok)) {
                mc.options.keyShift.setDown(false);
                return true;
            }
            if (ok == null || pause[0]-- > 0) return false;
            if (clicks[0] >= max) {
                fail(what + ": still not showing after " + max + " clicks");
                mc.options.keyShift.setDown(false);
                return true;
            }
            mc.options.keyShift.setDown(sneak);
            if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.MISS) return false; // the view not yet up
            KeyMapping.click(mc.options.keyUse.getKey());
            clicks[0]++;
            pause[0] = 6; // the server's answer comes back before the next click
            return false;
        });
    }

    /** The nearest place to stand (feet) within 8 blocks with nothing overhead within reach; null if none. */
    static Vec3 openSky(Minecraft mc) {
        Vec3 best = null;
        double bestD = Double.MAX_VALUE;
        Vec3 at = mc.player.position();
        int bx = (int) Math.floor(at.x), bz = (int) Math.floor(at.z), by = (int) Math.floor(at.y);
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                double x = bx + dx + 0.5, z = bz + dz + 0.5;
                for (int k = 0; k <= 12 * 16; k++) {
                    double fy = by + 4 - k / 16.0;
                    AABB body = new AABB(x - 0.3, fy, z - 0.3, x + 0.3, fy + 1.8, z + 0.3);
                    if (!mc.level.noCollision(mc.player, body)) continue;
                    if (mc.level.noCollision(mc.player, body.move(0, -1 / 16.0, 0))) continue;
                    Vec3 eye = new Vec3(x, fy + EYE_STANDING, z);
                    BlockHitResult up = mc.level.clip(new ClipContext(eye, eye.add(0, REACH + 1, 0), ClipContext.Block.OUTLINE,
                            ClipContext.Fluid.NONE, mc.player));
                    if (up.getType() == HitResult.Type.MISS) {
                        double d = new Vec3(x, fy, z).distanceTo(at);
                        if (d < bestD) {
                            bestD = d;
                            best = new Vec3(x, fy, z);
                        }
                    }
                    break;
                }
            }
        }
        return best;
    }

    /** An act with a description for the caption. */
    static Act named(String what, Act a) {
        return new Act() {
            @Override
            public boolean tick(Minecraft mc) {
                return a.tick(mc);
            }

            @Override
            public String what() {
                return what;
            }
        };
    }

    static void fail(String msg) {
        FAILURES.add(msg);
        EmergenceMod.LOG.warn("Showcase hand: {}", msg);
    }

    /** Turns the player's view to a world point (as moving the mouse does). */
    static void face(Minecraft mc, Vec3 at) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = at.x - eye.x, dy = at.y - eye.y, dz = at.z - eye.z, h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)), pitch = (float) -Math.toDegrees(Math.atan2(dy, h));
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
        mc.player.setYHeadRot(yaw);
    }

    /** The part a hit lands on, as the server will resolve it (the cell under the hit point). */
    static PlacedPart partHit(Minecraft mc, BlockHitResult hit) {
        if (!(mc.level.getBlockEntity(hit.getBlockPos()) instanceof AssemblyBlockEntity be)) return null;
        return be.partHit(hit.getLocation(), hit.getDirection());
    }

    /**
     * Goes within reach of a part and acts on it once the crosshair is on it: finds a free spot with a clear line
     * of sight (preferring the side the exhibit is viewed from), asks the server to put the player there (creative,
     * flying, the item in hand: what a player does by flying over and picking it from the inventory), then looks at
     * the part each tick until the client's own pick lands on it.
     */
    private static final class Reach implements Act {
        private final String what;
        private final Target t;
        private final ItemStack item;
        private final boolean sneak;
        private final java.util.function.Consumer<Minecraft> act;
        private int ticks;
        private Vec3 aim, stand; // stand: where the feet go
        private boolean acted, flying;
        private Effect effect = Effect.NONE;
        private long partId;
        private boolean wasClosed;
        private int attempts, since, waited, waitedForServer;
        /** Set once the server has moved the player (a busy server runs the move late; the waiting starts after it). */
        private final java.util.concurrent.atomic.AtomicBoolean moved = new java.util.concurrent.atomic.AtomicBoolean();

        @Override
        public String what() {
            return what;
        }

        Reach expect(Effect e) {
            effect = e;
            return this;
        }

        /** Whether the click has done what a click on this part does (as the player sees it, on the client). */
        private boolean took(Minecraft mc) {
            if (effect == Effect.NONE) return true;
            PlacedPart p = mc.level.getBlockEntity(t.block) instanceof AssemblyBlockEntity be ? be.byId(partId) : null;
            return effect == Effect.REMOVED ? p == null : p != null && p.closed != wasClosed;
        }

        Reach(String what, Target t, ItemStack item, boolean sneak, java.util.function.Consumer<Minecraft> act) {
            this.what = what;
            this.t = t;
            this.item = item;
            this.sneak = sneak;
            this.act = act;
        }

        @Override
        public boolean tick(Minecraft mc) {
            if (acted) {
                if (!took(mc)) {
                    // nothing happened (a click lost on its way): click again, as a player would, a few times
                    face(mc, aim);
                    if (++since < 60) return false; // a busy server answers late: give it three seconds
                    // click again only on that same part (if it has gone, the crosshair is on whatever was behind it)
                    if (!(mc.hitResult instanceof BlockHitResult bh) || partHit(mc, bh) == null || partHit(mc, bh).id != partId) {
                        since = 0;
                        if (++attempts > 6) {
                            fail(what + ": the " + t.kind.name().toLowerCase() + " at " + t.block.toShortString() + " is no longer under the crosshair");
                            return true;
                        }
                        return false;
                    }
                    if (attempts >= 3) {
                        fail(what + ": clicked " + attempts + " times on the " + t.kind.name().toLowerCase() + " at " + t.block.toShortString()
                                + " and it did not " + (effect == Effect.REMOVED ? "come away" : "change"));
                        return true;
                    }
                    EmergenceMod.LOG.info("Showcase hand: {}: no effect yet, clicking again", what);
                    attempts++;
                    since = 0;
                    act.accept(mc);
                    return false;
                }
                // a click is consumed on the next tick's key handling; then the sneak key may go (a hold keeps it)
                if (heldAim == null) mc.options.keyShift.setDown(false);
                return true;
            }
            if (ticks++ == 0) {
                releaseAll(mc);
                if (!plan(mc)) {
                    fail(what + ": no spot within reach with a clear view of the " + t.kind.name().toLowerCase() + " at " + t.block.toShortString());
                    return true;
                }
                var server = mc.getSingleplayerServer();
                Vec3 feet = stand;
                boolean fly = flying;
                server.execute(() -> {
                    ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                    p.setGameMode(GameType.CREATIVE);
                    p.getAbilities().flying = fly;
                    p.onUpdateAbilities();
                    p.getInventory().selected = 0;
                    p.setItemInHand(InteractionHand.MAIN_HAND, item.copy());
                    p.teleportTo(server.overworld(), feet.x, feet.y, feet.z, p.getYRot(), p.getXRot());
                    moved.set(true);
                });
                return false;
            }
            if (!moved.get()) {
                if (++waitedForServer > 6000) {
                    fail(what + ": the server never moved the player");
                    return true;
                }
                ticks = 1; // the crosshair's time starts once the player is there
                return false;
            }
            if (mc.player.position().distanceTo(stand) > 0.3) {
                if (++waited > 200) {
                    fail(what + ": never arrived at " + stand + " (stopped at " + mc.player.position() + (flying ? ", flying" : ", on foot") + ")");
                    return true;
                }
                return false;
            }
            face(mc, aim);
            mc.options.keyShift.setDown(sneak);
            if (mc.hitResult instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK && bh.getBlockPos().equals(t.block)
                    && (t.face == null || bh.getDirection() == t.face)) {
                PlacedPart p = partHit(mc, bh);
                boolean itemReady = ItemStack.isSameItemSameComponents(mc.player.getMainHandItem(), item)
                        && mc.gameMode.getPlayerMode() == GameType.CREATIVE;
                if (p != null && p.kind == t.kind && itemReady && ticks > 3) {
                    partId = p.id;
                    wasClosed = p.closed;
                    attempts = 1;
                    act.accept(mc);
                    acted = true;
                    return false;
                }
            }
            if (ticks > 100) {
                String on = mc.hitResult instanceof BlockHitResult bh ? bh.getBlockPos().toShortString() + " " + bh.getDirection()
                        + (partHit(mc, bh) == null ? "" : " (" + partHit(mc, bh).kind + ")") : String.valueOf(mc.hitResult == null ? null : mc.hitResult.getType());
                fail(what + ": the crosshair would not settle on the " + t.kind.name().toLowerCase() + " at " + t.block.toShortString() + " (it is on " + on + ")");
                return true;
            }
            return false;
        }

        /**
         * Chooses where to aim and where to stand: on the ground (or on the machine) where the part is in reach and in
         * plain view, as near the side the exhibit is watched from as may be — and, only if no such footing exists and
         * the job needs no sneaking (sneaking while flying sinks), hovering in creative flight.
         */
        private boolean plan(Minecraft mc) {
            if (!(mc.level.getBlockEntity(t.block) instanceof AssemblyBlockEntity be)) return false;
            PlacedPart part = null;
            for (PlacedPart p : be.parts()) if (p.kind == t.kind && part == null) part = p;
            if (part == null) return false;
            // aim at the middle of the part's biggest box (on the wanted face, if one is wanted)
            AABB best = null;
            for (AABB b : PartShapes.boxes(be, part)) if (best == null || b.getSize() > best.getSize()) best = b;
            if (best == null) return false;
            Vec3 c = best.getCenter();
            if (t.face != null) {
                c = new Vec3(t.face.getStepX() > 0 ? best.maxX : t.face.getStepX() < 0 ? best.minX : c.x,
                        t.face.getStepY() > 0 ? best.maxY : t.face.getStepY() < 0 ? best.minY : c.y,
                        t.face.getStepZ() > 0 ? best.maxZ : t.face.getStepZ() < 0 ? best.minZ : c.z);
            }
            aim = c.add(t.block.getX(), t.block.getY(), t.block.getZ());
            Vec3 cam = mc.player.getEyePosition().subtract(aim);
            Vec3 camDir = cam.horizontalDistanceSqr() > 1e-6 ? new Vec3(cam.x, 0, cam.z).normalize() : Vec3.ZERO;
            double eyeH = sneak ? EYE_CROUCHING : EYE_STANDING;
            // on foot: the highest footing in each column round the part
            double bestScore = Double.MAX_VALUE;
            int bx = (int) Math.floor(aim.x), bz = (int) Math.floor(aim.z), by = (int) Math.floor(aim.y);
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    double x = bx + dx + 0.5, z = bz + dz + 0.5;
                    for (int k = 0; k <= 7 * 16; k++) {
                        double fy = by + 2 - k / 16.0;
                        AABB body = body(x, fy, z);
                        if (!mc.level.noCollision(mc.player, body)) continue;
                        if (mc.level.noCollision(mc.player, body.move(0, -1 / 16.0, 0))) continue; // nothing underfoot
                        Vec3 eye = new Vec3(x, fy + eyeH, z);
                        double score = score(mc, eye, camDir);
                        if (score < bestScore) {
                            bestScore = score;
                            stand = new Vec3(x, fy, z);
                            flying = false;
                        }
                        break;
                    }
                }
            }
            if (stand != null) return true;
            if (sneak) return false;
            // in the air
            List<Vec3> dirs = new ArrayList<>();
            if (camDir != Vec3.ZERO) dirs.add(camDir);
            for (int k = 0; k < 8; k++) {
                double a = k * Math.PI / 4;
                dirs.add(new Vec3(Math.cos(a), 0, Math.sin(a)));
            }
            int level = dirs.size();
            for (int k = 0; k < level; k++) {
                Vec3 d = dirs.get(k);
                dirs.add(new Vec3(d.x * 0.8, 0.6, d.z * 0.8));
                dirs.add(new Vec3(d.x * 0.8, -0.6, d.z * 0.8));
            }
            dirs.add(new Vec3(0, 1, 0));
            dirs.add(new Vec3(0, -1, 0));
            for (double dist : new double[]{2.2, 3.0, 3.8, 1.6}) {
                for (Vec3 d : dirs) {
                    Vec3 eye = aim.add(d.normalize().scale(dist));
                    Vec3 feet = eye.subtract(0, eyeH, 0);
                    if (!mc.level.noCollision(mc.player, body(feet.x, feet.y, feet.z))) continue;
                    if (score(mc, eye, camDir) == Double.MAX_VALUE) continue;
                    stand = feet;
                    flying = true;
                    return true;
                }
            }
            return false;
        }

        /** How good a place to work from an eye position is (lower is better); MAX_VALUE if the part is out of reach or out of sight. */
        private double score(Minecraft mc, Vec3 eye, Vec3 camDir) {
            double dist = eye.distanceTo(aim);
            if (dist > REACH || dist < 1.0) return Double.MAX_VALUE;
            if (t.face != null && aim.subtract(eye).dot(new Vec3(t.face.getStepX(), t.face.getStepY(), t.face.getStepZ())) > -0.1) {
                return Double.MAX_VALUE; // the face looks away
            }
            BlockHitResult hit = mc.level.clip(new ClipContext(eye, aim.add(aim.subtract(eye).normalize().scale(0.05)),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(t.block)) return Double.MAX_VALUE;
            if (t.face != null && hit.getDirection() != t.face) return Double.MAX_VALUE;
            PlacedPart p = partHit(mc, hit);
            if (p == null || p.kind != t.kind) return Double.MAX_VALUE;
            Vec3 h = new Vec3(eye.x - aim.x, 0, eye.z - aim.z);
            double side = h.lengthSqr() > 1e-6 ? h.normalize().dot(camDir) : 0;
            return Math.abs(dist - 2.5) - side;
        }

        private static AABB body(double x, double feetY, double z) {
            return new AABB(x - 0.3, feetY, z - 0.3, x + 0.3, feetY + 1.8, z + 0.3);
        }
    }
}
