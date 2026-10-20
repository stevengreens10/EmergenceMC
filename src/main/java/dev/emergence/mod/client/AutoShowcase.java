package dev.emergence.mod.client;

import dev.emergence.core.assembly.PartKind;
import dev.emergence.core.demo.Exhibits;
import dev.emergence.mod.EmergenceMod;
import dev.emergence.mod.block.AssemblyBlockEntity;
import dev.emergence.mod.demo.DemoBuilder;
import dev.emergence.mod.part.PlacedPart;
import dev.emergence.mod.sim.SimulationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import dev.emergence.core.assembly.CompiledAssembly;
import dev.emergence.core.assembly.Probe;
import dev.emergence.mod.part.PartPresets;
import dev.emergence.mod.part.PartSpec;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.List;

/**
 * Automated verification of the demo world (enabled with {@code -Demergence.autoShowcase=true}, see the
 * {@code showcaseClient} run). After joining it visits each exhibit and works it as a player would — every switch,
 * crank, valve, brake, data logger and added or removed part through a click of the use or attack key with the
 * crosshair on the part ({@link PlayerHand}), never through the simulation's own API — saves screenshots to
 * {@code screenshots/showcase-*.png}, checks the physics it should have caused (speeds, temperatures, lit LEDs,
 * broken parts: read from the server, as a test's eyes) and writes the results to
 * {@code screenshots/showcase-report.txt}, then quits. It sends no OS-level input, so it is safe to run while
 * someone is using the computer.
 */
public final class AutoShowcase {
    private AutoShowcase() {}

    /**
     * A server action (the camera's move, or a reading kept for later), then the hands' work if any (a player's
     * clicks), then a screenshot after {@code delayTicks} or as soon as {@code ready} holds, and checks of the physics
     * at that moment.
     */
    private static final class Step {
        final int delayTicks;
        final Runnable server;
        final String shot;
        final java.util.function.BooleanSupplier ready;
        PlayerHand.Act act;
        boolean show;
        final List<String> checkNames = new ArrayList<>();
        final List<java.util.function.Supplier<Verdict>> checks = new ArrayList<>();
        /** Run on the server after the step's checks (readings kept for later steps). */
        Runnable after;

        Step(int delayTicks, Runnable server, String shot) {
            this(delayTicks, server, shot, null);
        }

        Step(int delayTicks, Runnable server, String shot, java.util.function.BooleanSupplier ready) {
            this.delayTicks = delayTicks;
            this.server = server;
            this.shot = shot;
            this.ready = ready;
        }

        String title, description;
        int number, total;

        /** The step opens an exhibit: the caption shows its title and what it demonstrates. */
        Step caption(int number, int total, String title, String description) {
            this.number = number;
            this.total = total;
            this.title = title;
            this.description = description;
            return this;
        }

        Step after(Runnable r) {
            after = r;
            return this;
        }

        Step act(PlayerHand.Act a) {
            act = a;
            return this;
        }

        /** Show the data logger's screen in the screenshot. */
        Step show() {
            return show(true);
        }

        Step show(boolean on) {
            show = on;
            return this;
        }

        /** A check run on the server when the step ends (and shown live while it runs): what it measures and whether it holds. */
        Step check(String name, java.util.function.Supplier<Verdict> c) {
            checkNames.add(name);
            checks.add(c);
            return this;
        }
    }

    /** Check results (PASS / FAIL lines) for the report. */
    private static final List<String> REPORT = java.util.Collections.synchronizedList(new ArrayList<>());

    private static final List<Step> STEPS = new ArrayList<>();
    private static int index, wait = -1, joinedTicks;
    private static boolean actDone;

    public static void init() {
        if (!Boolean.getBoolean("emergence.autoShowcase")) return;
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> tick());
    }

    private static void plan(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        ServerLevel level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        var ex = Exhibits.all();
        int n = ex.size();
        BlockPos origin = spawn.offset(-8, 0, -(DemoBuilder.SIGN_Z + 3));
        STEPS.add(new Step(80, () -> look(server, origin.getX() + 26, origin.getY() + 16, origin.getZ() + 38, 160, 28), "showcase-0-overview"));
        // emergence.showcaseOnly=a,b,...: visit only those exhibits, and dwell on each (motion diagnostics)
        java.util.Set<String> only = new java.util.HashSet<>(java.util.Arrays.asList(System.getProperty("emergence.showcaseOnly", "").split(",")));
        only.remove("");
        ItemStack logger = new ItemStack(dev.emergence.mod.registry.ModRegistries.DATA_LOGGER.get());
        ItemStack lead = PartPresets.stack(PartKind.BLOCK, PartSpec.of("lead", 1, 1));
        for (int i = 0; i < n; i++) {
            BlockPos plot = origin.offset(DemoBuilder.plotOffset(ex, i), 0, 0);
            var e = ex.get(i);
            String id = e.id;
            if (!only.isEmpty() && !only.contains(id)) continue;
            int no = i + 1;
            java.util.function.Function<String, String> shot = s -> "showcase-" + no + "-" + id + (s.isEmpty() ? "" : "-" + s);
            java.util.function.Function<String, BlockPos> at = name -> {
                if (!e.miniature && !e.named.containsKey(name)) {
                    REPORT.add("FAIL tour: " + id + " has no part named " + name);
                    return plot;
                }
                return DemoBuilder.partBlock(plot, e, name);
            };
            java.util.function.BiFunction<String, PartKind, PlayerHand.Target> part = (name, kind) -> new PlayerHand.Target(at.apply(name), kind);
            Runnable view = () -> look(server, plot.getX() + e.viewX, plot.getY() + e.viewHeight, plot.getZ() + e.viewZ,
                    (float) e.viewYaw, (float) e.viewPitch);
            // the first look: at least 50 ticks, and on until the exhibit is drawn and simulated (a tour jumping far, as
            // with showcaseOnly, otherwise photographs an empty plot)
            // (unset until the server has run the step's action: a busy server runs it a few ticks late, and the
            // condition must not hold before the camera has even moved)
            long[] since = {Long.MAX_VALUE / 2};
            var first = e.parts.get(0);
            BlockPos firstBlock = plot.offset(first.x0(), first.y0(), first.z0());
            STEPS.add(new Step(600, () -> {
                LoggerHud.showcase = false;
                since[0] = server.overworld().getGameTime();
                view.run();
            }, shot.apply(""), () -> server.overworld().getGameTime() - since[0] >= 50 && shown(server, firstBlock))
                    .act(PlayerHand.release()).caption(no, n, e.title, e.description));
            String key = id + ":";
            if (id.equals("glow") || id.equals("fuse")) {
                BlockPos wire = at.apply("wire");
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " close the switch", part.apply("switch", PartKind.SWITCH))));
                STEPS.add(new Step(40, view, shot.apply("discharged"), () -> hottest(wire) > 950 || gone(wire))
                        .check(key + " the discharge heats the wire to a glow (or blows it)",
                                () -> !has(server, wire, PartKind.WIRE) ? that(true, "the wire has blown") : atLeast("wire temperature [K]", temperature(server, wire, PartKind.WIRE), 900)));
            }
            if (id.equals("dcmotor") || id.equals("corrosion")) {
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " close the switch", part.apply("switch", PartKind.SWITCH))));
                Step s = new Step(id.equals("corrosion") ? 60 : 30, view, shot.apply("on"));
                if (id.equals("dcmotor")) s.check(key + " the motor turns", () -> atLeast("rotor speed [rad/s]", motion(server, at.apply("rotor"), PartKind.SHAFT), 2));
                STEPS.add(s);
            }
            if (id.equals("turbine")) {
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " open the valve", part.apply("valve", PartKind.VALVE))));
                STEPS.add(new Step(16, view, shot.apply("on")).check(key + " the air turns the turbine",
                        () -> atLeast("shaft speed [rad/s]", motion(server, at.apply("shaft"), PartKind.SHAFT), 0.5)));
            }
            if (id.equals("transistor")) {
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " close the switch", part.apply("switch", PartKind.SWITCH))));
                STEPS.add(new Step(20, view, shot.apply("on")).check(key + " the transistor lights the LED", () -> lit(server, at.apply("led"))));
            }
            if (id.equals("nand")) {
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " input A high", part.apply("a", PartKind.SWITCH))));
                STEPS.add(new Step(20, view, shot.apply("a")).check(key + " NAND(1, 0) = 1: the LED is lit", () -> lit(server, at.apply("led"))));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " input B high", part.apply("b", PartKind.SWITCH))));
                STEPS.add(new Step(20, view, shot.apply("ab")).check(key + " NAND(1, 1) = 0: the LED is dark", () -> dark(server, at.apply("led"))));
            }
            if (id.equals("thermostat")) {
                STEPS.add(new Step(60, NOTHING, shot.apply("later")));
            }
            if (id.equals("counter")) {
                STEPS.add(new Step(110, NOTHING, shot.apply("later")));
            }
            if (id.equals("stirling")) {
                STEPS.add(new Step(100, NOTHING, shot.apply("running")).check(key + " the engine runs on the hot block's heat",
                        () -> atLeast("flywheel speed [rad/s]", motion(server, at.apply("shaft"), PartKind.SHAFT), 1)));
            }
            if (id.equals("shelves")) {
                // a second lead block on the steel shelf's load and on the glass shelf's: past yield / fracture they break
                for (int k : new int[]{1, 3}) {
                    STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.clickWith(key + " stack a lead block on shelf " + k,
                            new PlayerHand.Target(at.apply("load" + k), PartKind.BLOCK, net.minecraft.core.Direction.UP), lead)));
                }
                BlockPos steelLoad = at.apply("load1"), glassLoad = at.apply("load3"), alLoad = at.apply("load0");
                STEPS.add(new Step(60, view, shot.apply("overloaded"))
                        .check(key + " the steel and glass shelves fail under two blocks", () -> {
                            boolean steel = has(server, steelLoad, PartKind.BLOCK), glass = has(server, glassLoad, PartKind.BLOCK);
                            return that(!steel && !glass, "steel shelf's load " + (steel ? "still up" : "fell") + ", glass shelf's load " + (glass ? "still up" : "fell")
                                    + "; expected both to fall");
                        })
                        .check(key + " the aluminium shelf still holds its one block", () -> that(has(server, alLoad, PartKind.BLOCK),
                                "aluminium shelf's load " + (has(server, alLoad, PartKind.BLOCK) ? "still up" : "fell") + "; expected to stay")));
            }
            if (id.equals("buckling")) {
                // the short strip's load on the tall one: it buckles within a simulated second
                BlockPos top = at.apply("tallTop");
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.clickWith(key + " put a lead block on the tall strip",
                        new PlayerHand.Target(top, PartKind.BLOCK, net.minecraft.core.Direction.UP), lead)));
                STEPS.add(new Step(60, view, shot.apply("loaded")).check(key + " the tall strip buckles under it",
                        () -> that(!has(server, top, PartKind.BLOCK), "tall strip " + (has(server, top, PartKind.BLOCK) ? "still standing" : "buckled") + "; expected to buckle")));
            }
            if (id.equals("computer")) {
                STEPS.add(new Step(200, NOTHING, shot.apply("running")));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " flip the sense switch", part.apply("sense", PartKind.SWITCH))));
                STEPS.add(new Step(200, view, shot.apply("reverse")));
            }
            if (id.equals("steam") || id.equals("condensing")) {
                BlockPos shaft = at.apply("shaft");
                double[] running = {Double.NaN};
                STEPS.add(new Step(80, NOTHING, shot.apply("running")).check(key + " the engine runs on its boiler's steam",
                        () -> atLeast("crankshaft speed [rad/s]", motion(server, shaft, PartKind.SHAFT), 3))
                        .after(() -> running[0] = motion(server, shaft, PartKind.SHAFT)));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " shut the regulator", part.apply("regulator", PartKind.VALVE))));
                STEPS.add(new Step(200, view, shot.apply("shut")).check(key + " without steam it slows",
                        () -> atMost("crankshaft speed [rad/s]", motion(server, shaft, PartKind.SHAFT), 0.9 * running[0])));
            }
            if (id.equals("servo")) {
                // turn the knob down to its stop: the arm rises against gravity to just above horizontal
                BlockPos knob = at.apply("crank");
                double[] before = {Double.NaN};
                STEPS.add(new Step(0, () -> before[0] = position(server, knob, PartKind.CRANK), null)
                        .act(PlayerHand.hold(key + " turn the knob down", part.apply("crank", PartKind.CRANK), true)));
                STEPS.add(new Step(220, NOTHING, shot.apply("raised")).check(key + " the knob turned",
                        () -> atLeast("knob turned [rad]", Math.abs(position(server, knob, PartKind.CRANK) - before[0]), 0.3)));
            }
            if (id.equals("explosion")) {
                // the data logger on the can's gas, then the heater switch: shoot when the gas runs away
                logOn(key, part.apply("can", PartKind.TANK), "fluid temperature", 2, server, logger);
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.clickWith(key + " close the heater switch", part.apply("switch", PartKind.SWITCH), logger)));
                STEPS.add(new Step(4000, view, shot.apply("ignited"), () -> onServer(server, "logger", () -> loggerNow(server)) > 1500).show()
                        .check(key + " the methane–air runs away and ignites", () -> atLeast("peak gas temperature on the logger's screen [K]",
                                SimulationManager.get(server.overworld()).loggerPeak(player(server)), 1000)));
            }
            if (id.equals("burner")) {
                // igniter and fuel together (the methane lights on the walls as they come up to temperature, ~50 s), the
                // chamber on the logger; one shot when it burns, or after two minutes whatever the server's pace
                BlockPos chamberAt = at.apply("chamber");
                logOn(key, part.apply("chamber", PartKind.TANK), "fluid temperature", 3, server, logger);
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.clickWith(key + " switch the igniter on", part.apply("switch", PartKind.SWITCH), logger)));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.clickWith(key + " open the fuel valve", part.apply("valve", PartKind.VALVE), logger)));
                STEPS.add(new Step(4800, view, shot.apply("burning"), () -> chamber(server, chamberAt, true) > 1300).show().check(key + " the methane burns in the chamber", () -> atLeast("chamber gas temperature [K]", chamberNow(server, chamberAt, true), 1000)));
            }
            if (id.equals("harvester")) {
                // a push sets the magnet bouncing between the coils; the switch connects the load
                BlockPos magnet = at.apply("magnet");
                STEPS.add(new Step(3, NOTHING, null).act(PlayerHand.hold(key + " push the magnet", part.apply("magnet", PartKind.MAGNET), false)));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.release()));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " connect the load", part.apply("switch", PartKind.SWITCH))));
                STEPS.add(new Step(10, view, shot.apply("bouncing")).check(key + " the pushed magnet bounces on its spring",
                        () -> atLeast("magnet's speed [m/s]", motion(server, magnet, PartKind.MAGNET), 0.005)));
            }
            if (id.equals("jack")) {
                // four strokes of the pump handle (no shots in between), then the raised ram
                BlockPos ram = at.apply("ramRod");
                double[] before = {Double.NaN};
                STEPS.add(new Step(0, () -> before[0] = position(server, ram, PartKind.SHAFT), null));
                for (int k = 0; k < 4; k++) {
                    STEPS.add(new Step(15, NOTHING, null).act(PlayerHand.hold(key + " pump stroke down", part.apply("pumpRod", PartKind.SHAFT), true)));
                    STEPS.add(new Step(15, NOTHING, null).act(PlayerHand.hold(key + " pump stroke up", part.apply("pumpRod", PartKind.SHAFT), false)));
                }
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.release()));
                STEPS.add(new Step(10, view, shot.apply("pumped")).check(key + " pumping raises the ram",
                        () -> atLeast("ram's rise [mm]", 1e3 * (position(server, ram, PartKind.SHAFT) - before[0]), 0.5)));
            }
            if (id.equals("burst")) {
                // switch the heaters on: the copper box bursts within a minute of simulated time (longer in the tour,
                // where the whole gallery in view shares the time budget)
                BlockPos thin = at.apply("thin");
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " switch the heaters on", part.apply("switch", PartKind.SWITCH))));
                STEPS.add(new Step(6000, view, shot.apply("burst"), () -> gone(thin, PartKind.TANK))
                        .check(key + " the thin copper vessel bursts", () -> that(!has(server, thin, PartKind.TANK),
                                "thin vessel " + (has(server, thin, PartKind.TANK) ? "still holds" : "has burst") + "; expected to burst")));
            }
            if (id.equals("faraday")) {
                // make, then break: one LED flashes as the flux grows, the other as it collapses
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " make the circuit", part.apply("switch", PartKind.SWITCH))));
                STEPS.add(new Step(2, view, shot.apply("make")));
                STEPS.add(new Step(40, NOTHING, null));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " break the circuit", part.apply("switch", PartKind.SWITCH))));
                STEPS.add(new Step(2, view, shot.apply("break")));
            }
            if (id.equals("periscope") || id.equals("lens")) {
                // the green LED is lit by the light the mirrors (or the lens) bring; take one away and it goes out
                String name = id.equals("lens") ? "lens" : "mirrorB";
                PartKind kind = id.equals("lens") ? PartKind.LENS : PartKind.MIRROR;
                BlockPos led = at.apply("lit"), optic = at.apply(name); // the receiver's LED
                STEPS.add(new Step(40, NOTHING, shot.apply("lit")).check(key + " the light reaches the photodiode and lights the LED", () -> lit(server, led)));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.attack(key + " take the " + name + " away", part.apply(name, kind))));
                STEPS.add(new Step(60, view, shot.apply("dark")).check(key + " without it the LED goes out", () -> has(server, optic, kind)
                        ? that(false, "the " + name + " is still there") : dark(server, led)));
            }
            if (id.equals("relay")) {
                // close the coil's switch: the armature snaps up and the contacts light the LED
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " close the coil's switch", part.apply("switch", PartKind.SWITCH))));
                STEPS.add(new Step(60, view, shot.apply("energised")).check(key + " the contacts light the lamp", () -> lit(server, at.apply("lamp"))));
            }
            if (id.equals("still")) {
                // heater on: the warm pot boils and distillate gathers in the receiver
                BlockPos pot = at.apply("pot");
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " switch the heater on", part.apply("switch", PartKind.SWITCH))));
                STEPS.add(new Step(1200, view, shot.apply("boiling")).check(key + " the pot heats towards boiling",
                        () -> atLeast("pot temperature [K]", temperature(server, pot, PartKind.TANK), 340)));
            }
            if (id.equals("loudspeaker")) {
                // switch the loudspeaker on; the simulation resolves every vibration, so give the microphone time
                BlockPos led = at.apply("lit");
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " switch the loudspeaker on", part.apply("switch", PartKind.SWITCH))));
                STEPS.add(new Step(3000, view, shot.apply("heard"), () -> ledLit(led)).check(key + " the microphone hears it: the LED lights", () -> lit(server, led)));
            }
            if (id.equals("spectrometer")) {
                // red heat, orange, white: the infrared channel lights first, then the red, then the blue
                BlockPos fil = at.apply("filament");
                double[] last = {0};
                for (String sw : new String[]{"switch", "warmer", "hotter"}) {
                    STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " close " + sw, part.apply(sw, PartKind.SWITCH))));
                    STEPS.add(new Step(60, view, shot.apply(sw)).check(key + " the filament is hotter after " + sw,
                            () -> atLeast("filament temperature [K]", temperature(server, fil, PartKind.WIRE), last[0] + 50))
                            .after(() -> last[0] = temperature(server, fil, PartKind.WIRE)));
                }
            }
            if (id.equals("memory")) {
                // write 1010 into word 1, look at word 0 (empty), then back at word 1
                for (String sw : new String[]{"a0", "d1", "d3"}) {
                    STEPS.add(new Step(10, NOTHING, null).act(PlayerHand.click(key + " set " + sw, part.apply(sw, PartKind.SWITCH))));
                }
                STEPS.add(new Step(20, NOTHING, null).act(PlayerHand.click(key + " write (press)", part.apply("write", PartKind.SWITCH))));
                STEPS.add(new Step(20, NOTHING, null).act(PlayerHand.click(key + " write (release)", part.apply("write", PartKind.SWITCH))));
                STEPS.add(new Step(20, view, shot.apply("written")));
                STEPS.add(new Step(30, NOTHING, null).act(PlayerHand.click(key + " address word 0", part.apply("a0", PartKind.SWITCH))));
                STEPS.add(new Step(30, view, shot.apply("word0")));
                STEPS.add(new Step(30, NOTHING, null).act(PlayerHand.click(key + " address word 1", part.apply("a0", PartKind.SWITCH))));
                STEPS.add(new Step(30, view, shot.apply("word1")));
            }
            if (id.equals("filters")) {
                // red-hot: only the red side lights; white-hot: both
                BlockPos red = at.apply("litRed"), blue = at.apply("litBlue");
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " switch the filament on", part.apply("switch", PartKind.SWITCH))));
                STEPS.add(new Step(400, view, shot.apply("redhot"), () -> ledLit(red)).check(key + " red-hot: the red channel lights", () -> lit(server, red)));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " run it hotter", part.apply("hotter", PartKind.SWITCH))));
                STEPS.add(new Step(400, view, shot.apply("whitehot"), () -> ledLit(blue)).check(key + " white-hot: the blue channel lights too", () -> lit(server, blue)));
            }
            if (id.equals("optocoupler")) {
                // close the input's switch: the invisible infrared crosses to the phototransistor; the green LED lights
                BlockPos led = at.apply("green");
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " close the input's switch", part.apply("switch", PartKind.SWITCH))));
                STEPS.add(new Step(200, view, shot.apply("on"), () -> ledLit(led)).check(key + " the signal crosses as light", () -> lit(server, led)));
            }
            if (id.equals("windturbine")) {
                // the wind (from the west, by the weather) turns the rotor up to speed
                BlockPos shaftAt = at.apply("shaft");
                STEPS.add(new Step(2400, NOTHING, shot.apply("turning"), () -> Math.abs(wheelSpeed(server, shaftAt)) > 30)
                        .check(key + " the wind turns the rotor", () -> atLeast("rotor speed [rad/s]", motion(server, shaftAt, PartKind.SHAFT), 20)));
            }
            if (id.equals("weights")) {
                // a hand on the crank: the beam spins up and the weights slide out against their springs
                BlockPos shaftAt = at.apply("shaft");
                STEPS.add(new Step(2, NOTHING, shot.apply("rest")));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.hold(key + " turn the crank", part.apply("crank", PartKind.CRANK), false)));
                STEPS.add(new Step(1200, NOTHING, shot.apply("spinning"), () -> Math.abs(wheelSpeed(server, shaftAt)) > 13)
                        .check(key + " cranking spins the beam up", () -> atLeast("beam speed [rad/s]", motion(server, shaftAt, PartKind.SHAFT), 10)));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.release()));
            }
            if (id.equals("fourbar")) {
                // the flywheel turns the crank round; the coupler swings the rocker one way, then the other
                BlockPos rocker = at.apply("rocker");
                double[] lo = {Double.MAX_VALUE}, hi = {-Double.MAX_VALUE};
                for (String when : new String[]{"a", "b", "c"}) {
                    STEPS.add(new Step(6, () -> {
                        double q = position(server, rocker, PartKind.BLOCK);
                        lo[0] = Math.min(lo[0], q);
                        hi[0] = Math.max(hi[0], q);
                    }, shot.apply(when)));
                }
                STEPS.add(new Step(0, NOTHING, null).check(key + " the rocker swings, within its 44.5°", () -> {
                    double sw = Math.toDegrees(hi[0] - lo[0]);
                    return that(sw > 5 && sw < 46, String.format("rocker swept %.1f° between the samples, expected 5–46°", sw));
                }));
            }
            if (id.equals("unbalance")) {
                // the lopsided wheel coasts down: a little shaking at speed, hard shaking at resonance, calm below
                BlockPos shaftAt = at.apply("shaft");
                STEPS.add(new Step(20, NOTHING, shot.apply("fast")));
                STEPS.add(new Step(3000, NOTHING, shot.apply("resonance"), () -> Math.abs(wheelSpeed(server, shaftAt)) < 10.5)
                        .check(key + " it coasts down to resonance", () -> atMost("wheel speed [rad/s]", motion(server, shaftAt, PartKind.SHAFT), 11)));
                STEPS.add(new Step(3000, NOTHING, shot.apply("calm"), () -> Math.abs(wheelSpeed(server, shaftAt)) < 6));
            }
            if (id.equals("fancooling")) {
                // a hand on the crank: the fan's jet cools its plate until it is well below its twin in still air
                BlockPos cooled = at.apply("cooled"), still = at.apply("still");
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.hold(key + " turn the fan's crank", part.apply("crank", PartKind.CRANK), false)));
                STEPS.add(new Step(3000, NOTHING, shot.apply("cranked"), () -> hottest(still) - hottest(cooled) > 8)
                        .check(key + " the jet cools its plate below its twin's", () -> atLeast("still − cooled plate [K]",
                                maxTemperature(server, still) - maxTemperature(server, cooled), 5)));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.release()));
            }
            if (id.equals("governor")) {
                // apply the brake: the speed sags, the drive rises and brings it back
                governed(key, server, at.apply("rotor"), PartKind.SHAFT, part.apply("brake", PartKind.BRAKE), view, shot, null, 0.05);
            }
            if (id.equals("contactgov")) {
                // the rotor's speed on the logger; close the switch (it comes up to its governed speed and holds), then
                // brake it: the speed holds while the current rises
                logOn(key, part.apply("rotor", PartKind.SHAFT), "angular velocity", 2, server, logger);
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.clickWith(key + " close the switch", part.apply("switch", PartKind.SWITCH), logger)));
                governed(key, server, at.apply("rotor"), PartKind.SHAFT, part.apply("brake", PartKind.BRAKE), view, shot, logger, 0.03);
            }
            if (id.equals("flyball")) {
                // close the switch: the weights fly out and the push rod turns the rheostat in; then brake it
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " close the switch", part.apply("switch", PartKind.SWITCH))));
                governed(key, server, at.apply("rotor"), PartKind.SHAFT, part.apply("brake", PartKind.BRAKE), view, shot, null, 0.06);
            }
            if (id.equals("column")) {
                // the heater on: the pot boils, the trays warm one by one and the condensate runs back down
                BlockPos pot = at.apply("pot");
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " switch the heater on", part.apply("switch", PartKind.SWITCH))));
                STEPS.add(new Step(2400, view, shot.apply("refluxing")).check(key + " the pot heats up",
                        () -> atLeast("pot temperature [K]", temperature(server, pot, PartKind.TANK), 340)));
            }
            if (id.equals("trembler")) {
                // close the switch: the relay chatters, opening its own coil's circuit eight times a second
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " close the switch", part.apply("switch", PartKind.SWITCH))));
                STEPS.add(new Step(40, view, shot.apply("buzzing")));
                STEPS.add(new Step(3, NOTHING, shot.apply("buzzing2")));
            }
            if (id.equals("eddy")) {
                STEPS.add(new Step(60, NOTHING, shot.apply("braked")));
            }
            if (id.equals("fatigue")) {
                // both shafts spin; the aluminium one cracks after some two thousand turns and drops its disc
                BlockPos root = at.apply("aluminiumRoot");
                STEPS.add(new Step(8000, NOTHING, shot.apply("cracked"), () -> gone(root, PartKind.SHAFT))
                        .check(key + " the aluminium shaft fails by fatigue", () -> that(!has(server, root, PartKind.SHAFT),
                                "aluminium shaft " + (has(server, root, PartKind.SHAFT) ? "still whole" : "cracked") + "; expected to crack")));
            }
            if (id.equals("lamp")) {
                // switch the lamp on: the filament glows white-hot and the electric eye lights its green LED
                BlockPos fil = at.apply("filament");
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " switch the lamp on", part.apply("switch", PartKind.SWITCH))));
                STEPS.add(new Step(600, view, shot.apply("on"), () -> hottest(fil) > 2300).check(key + " the filament glows white-hot",
                        () -> atLeast("filament temperature [K]", temperature(server, fil, PartKind.WIRE), 2000)));
            }
            if (id.equals("furnace")) {
                // the plate heats in the dish's focus until it glows
                BlockPos plate = at.apply("target");
                STEPS.add(new Step(12000, NOTHING, shot.apply("hot"), () -> hottest(plate) > 860).check(key + " the focused sunlight heats the plate red-hot",
                        () -> atLeast("plate temperature [K]", maxTemperature(server, plate), 800)));
            }
            if (id.equals("engine")) {
                STEPS.add(new Step(200, NOTHING, shot.apply("running")).check(key + " the engine runs on its own",
                        () -> atLeast("crankshaft speed [rad/s]", motion(server, at.apply("crankshaft"), PartKind.SHAFT), 5)));
            }
            if (id.equals("rack")) {
                // crank the rack up, then let go: the weight runs it back down
                BlockPos rack = at.apply("rack");
                double[] x = {Double.NaN, Double.NaN};
                STEPS.add(new Step(0, () -> x[0] = position(server, rack, PartKind.RACK), null)
                        .act(PlayerHand.hold(key + " turn the crank", part.apply("crank", PartKind.CRANK), false)));
                STEPS.add(new Step(30, NOTHING, shot.apply("lifted")).check(key + " cranking lifts the rack",
                        () -> atLeast("rack's rise [mm]", 1e3 * (position(server, rack, PartKind.RACK) - x[0]), 5))
                        .after(() -> x[1] = position(server, rack, PartKind.RACK)));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.release()));
                STEPS.add(new Step(30, view, shot.apply("released")).check(key + " let go, the weight runs it back down",
                        () -> atLeast("rack's fall [mm]", 1e3 * (x[1] - position(server, rack, PartKind.RACK)), 2)));
            }
            if (id.equals("screws")) {
                // crank each screw up (clockwise from above), then let go: the fine one holds, the steep one runs back
                for (String s : new String[]{"fine", "steep"}) {
                    BlockPos rod = at.apply(s + "Rod");
                    double[] x = {Double.NaN, Double.NaN};
                    STEPS.add(new Step(0, () -> x[0] = position(server, rod, PartKind.SHAFT), null)
                            .act(PlayerHand.hold(key + " turn the " + s + " screw's crank", part.apply(s + "Crank", PartKind.CRANK), true)));
                    STEPS.add(new Step(40, NOTHING, null).check(key + " cranking lifts the " + s + " carriage",
                            () -> atLeast(s + " carriage's rise [mm]", 1e3 * (position(server, rod, PartKind.SHAFT) - x[0]), s.equals("fine") ? 1 : 5))
                            .after(() -> x[1] = position(server, rod, PartKind.SHAFT)));
                    STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.release()));
                    STEPS.add(new Step(30, view, shot.apply(s + "-released")).check(key + (s.equals("fine") ? " let go, the fine screw holds"
                            : " let go, the steep screw runs back"), () -> {
                        double fall = 1e3 * (x[1] - position(server, rod, PartKind.SHAFT));
                        return s.equals("fine") ? atMost("fine carriage's fall [mm]", fall, 0.2) : atLeast("steep carriage's fall [mm]", fall, 2);
                    }));
                }
            }
            if (id.equals("pressure")) {
                STEPS.add(new Step(200, NOTHING, shot.apply("later")));
            }
            if (id.equals("generator")) {
                // the data logger clipped to the load resistor, then a hand on the crank: its screen shows the alternating EMF
                logOn(key, part.apply("load", PartKind.RESISTOR), "voltage", 1, server, logger);
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.holdWith(key + " turn the crank", part.apply("crank", PartKind.CRANK), false, logger)));
                STEPS.add(new Step(30, NOTHING, shot.apply("scope")).show().check(key + " cranking drives an alternating EMF across the load",
                        () -> atLeast("peak voltage on the logger's screen [V]", SimulationManager.get(server.overworld()).loggerPeak(player(server)), 0.05)));
            }
            if (id.equals("boost")) {
                // the logger on the switch: a square wave whose top stands above the 5 V supply
                logOn(key, part.apply("switch", PartKind.TRANSISTOR), "voltage", 0, server, logger);
                STEPS.add(new Step(100, view, shot.apply("switching")).show().check(key + " the boosted output lights its LED", () -> lit(server, at.apply("led1"))));
            }
            if (id.equals("inverter")) {
                // the logger on the secondary: a square wave five times the supply, reversing each half-cycle
                logOn(key, part.apply("secondary", PartKind.COIL), "voltage", 1, server, logger);
                STEPS.add(new Step(120, view, shot.apply("alternating")).show());
            }
            if (id.equals("propcart")) {
                STEPS.add(new Step(120, NOTHING, shot.apply("moving")));
            }
            if (id.equals("pwm")) {
                // the logger on the bridge's lower left transistor: the drive pulses, the motor running at their average
                logOn(key, part.apply("nA", PartKind.TRANSISTOR), "voltage", 1, server, logger);
                STEPS.add(new Step(160, view, shot.apply("pulses")).show());
            }
            if (id.equals("hbridge")) {
                // FORWARD, then REVERSE instead: the motor turns one way, then the other
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " close FORWARD", part.apply("forward", PartKind.SWITCH))));
                STEPS.add(new Step(200, view, shot.apply("forward")));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " open FORWARD", part.apply("forward", PartKind.SWITCH))));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " close REVERSE", part.apply("reverse", PartKind.SWITCH))));
                STEPS.add(new Step(300, view, shot.apply("reverse")));
            }
            if (id.equals("rectifier")) {
                // the logger on the smoothing capacitor while the crank turns: a steady voltage, the LED lit; then let go
                BlockPos cap = at.apply("cap");
                logOn(key, part.apply("cap", PartKind.CAPACITOR), "voltage", 1, server, logger);
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.holdWith(key + " turn the crank", part.apply("crank", PartKind.CRANK), false, logger)));
                STEPS.add(new Step(160, NOTHING, shot.apply("smoothed")).show()
                        .check(key + " the rectified output charges the capacitor", () -> atLeast("capacitor voltage [V]",
                                Math.abs(probe(server, cap, PartKind.CAPACITOR).voltage()), 1))
                        .check(key + " and lights the LED", () -> lit(server, at.apply("led"))));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.release()));
                STEPS.add(new Step(40, view, shot.apply("let-go")).show());
            }
            if (id.equals("generator") || id.equals("gears") || id.equals("miniature") || id.equals("fan") || id.equals("lightbarrier")) {
                BlockPos crank = at.apply("crank");
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.hold(key + " turn the crank", part.apply("crank", PartKind.CRANK), false)));
                STEPS.add(new Step(40, NOTHING, shot.apply("cranked")).check(key + " the crank turns when held",
                        () -> atLeast("crank speed [rad/s]", motion(server, crank, PartKind.CRANK), 1)));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.release()));
            }
            if (!only.isEmpty()) STEPS.add(new Step(200, view, shot.apply("dwell")));
        }
    }

    private static final Runnable NOTHING = () -> { };

    /**
     * Clip the data logger onto a part and step its screen (clicks in the air) to the channel and time base wanted, as a
     * player reading its screen would.
     */
    private static void logOn(String key, PlayerHand.Target t, String channel, int span, net.minecraft.server.MinecraftServer server, ItemStack logger) {
        STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.clickWith(key + " clip the data logger on", t, logger)));
        STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.useInAirUntil(key + " show " + channel, false,
                () -> {
                    String v = loggerShowing(server, 0);
                    return v == null ? null : channel.equals(v);
                }, 12)));
        STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.useInAirUntil(key + " time base " + span, true,
                () -> {
                    String v = loggerShowing(server, 1);
                    return v == null ? null : String.valueOf(span).equals(v);
                }, 6)));
    }

    /** A governed motor: its speed held, then the brake applied — it must keep its speed within {@code droop}. */
    private static void governed(String key, net.minecraft.server.MinecraftServer server, BlockPos shaftAt, PartKind kind, PlayerHand.Target brake,
                                 Runnable view, java.util.function.Function<String, String> shot, ItemStack inHand, double droop) {
        double[] w = {Double.NaN};
        STEPS.add(new Step(400, view, shot.apply("governing")).show(inHand != null).check(key + " it runs at its governed speed",
                () -> atLeast("speed [rad/s]", motion(server, shaftAt, kind), 5)).after(() -> w[0] = motion(server, shaftAt, kind)));
        STEPS.add(new Step(0, NOTHING, null).act(inHand == null ? PlayerHand.click(key + " apply the brake", brake)
                : PlayerHand.clickWith(key + " apply the brake", brake, inHand)));
        STEPS.add(new Step(400, view, shot.apply("braked")).show(inHand != null).check(key + " braked, it keeps its speed", () -> {
            double now = motion(server, shaftAt, kind);
            return that(Math.abs(now - w[0]) <= droop * w[0],
                    String.format("speed %.2f → %.2f rad/s (%+.1f %%), expected within ±%.0f %%", w[0], now, 100 * (now - w[0]) / w[0], 100 * droop));
        }));
    }

    private static void look(net.minecraft.server.MinecraftServer server, double x, double y, double z, float yaw, float pitch) {
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        // spectator: no collision, so the eye can be placed anywhere (y is the eye height)
        p.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
        p.teleportTo(server.overworld(), x, y - p.getEyeHeight(), z, yaw, pitch);
    }

    private static PlacedPart find(AssemblyBlockEntity be, PartKind k) {
        for (PlacedPart p : be.parts()) if (p.kind == k) return p;
        return null;
    }

    // ---- readings for the checks, taken on the server thread (a test's eyes, not a player's hands)

    private static PlacedPart partIn(net.minecraft.server.MinecraftServer s, BlockPos at, PartKind k) {
        return s.overworld().getBlockEntity(at) instanceof AssemblyBlockEntity be ? find(be, k) : null;
    }

    private static CompiledAssembly compiled(net.minecraft.server.MinecraftServer s, BlockPos at) {
        return SimulationManager.get(s.overworld()).compiledAt(at);
    }

    private static boolean has(net.minecraft.server.MinecraftServer s, BlockPos at, PartKind k) {
        return partIn(s, at, k) != null;
    }

    /** Speed of the body carrying the part: |ω| [rad/s], or |v| [m/s] for a slide (NaN if it is not simulated). */
    private static double motion(net.minecraft.server.MinecraftServer s, BlockPos at, PartKind k) {
        PlacedPart p = partIn(s, at, k);
        CompiledAssembly c = compiled(s, at);
        if (p == null || c == null) return Double.NaN;
        var b = c.bodyOf(p.id);
        return b == null || b.isStatic() ? 0 : Math.abs(c.angularVelocity(b));
    }

    /** Angle [rad] (or a slide's displacement [m]) of the body carrying the part. */
    private static double position(net.minecraft.server.MinecraftServer s, BlockPos at, PartKind k) {
        PlacedPart p = partIn(s, at, k);
        CompiledAssembly c = compiled(s, at);
        if (p == null || c == null) return Double.NaN;
        var b = c.bodyOf(p.id);
        return b == null || b.isStatic() ? 0 : c.angle(b);
    }

    private static Probe probe(net.minecraft.server.MinecraftServer s, BlockPos at, PartKind k) {
        PlacedPart p = partIn(s, at, k);
        CompiledAssembly c = compiled(s, at);
        Probe pr = p == null || c == null ? null : c.probe(p.id);
        return pr != null ? pr : new Probe(-1, k, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, -1);
    }

    private static double temperature(net.minecraft.server.MinecraftServer s, BlockPos at, PartKind k) {
        return probe(s, at, k).temperature();
    }

    private static double maxTemperature(net.minecraft.server.MinecraftServer s, BlockPos at) {
        double t = Double.NaN;
        CompiledAssembly c = compiled(s, at);
        if (c != null && s.overworld().getBlockEntity(at) instanceof AssemblyBlockEntity be) {
            for (PlacedPart p : be.parts()) {
                Probe pr = c.probe(p.id);
                if (pr != null && !(pr.temperature() <= t)) t = pr.temperature();
            }
        }
        return t;
    }

    private static double light(net.minecraft.server.MinecraftServer s, BlockPos at) {
        PlacedPart p = partIn(s, at, PartKind.LED);
        CompiledAssembly c = compiled(s, at);
        return p == null || c == null ? Double.NaN : c.lightOutput(p.id);
    }

    /** A check's outcome: whether it held, and what was measured against what was expected. */
    record Verdict(boolean pass, String measured) {}

    /** The LED in the block lights (it gives out light). */
    private static Verdict lit(net.minecraft.server.MinecraftServer s, BlockPos at) {
        double w = light(s, at);
        return new Verdict(w > 1e-5, String.format("LED light output = %.3g mW, expected > 0.01 mW (lit)", 1e3 * w));
    }

    private static Verdict dark(net.minecraft.server.MinecraftServer s, BlockPos at) {
        double w = light(s, at);
        return new Verdict(w <= 1e-5, String.format("LED light output = %.3g mW, expected ≤ 0.01 mW (dark)", 1e3 * w));
    }

    private static Verdict atLeast(String what, double v, double min) {
        return new Verdict(v >= min, String.format("%s = %.4g, expected ≥ %.4g", what, v, min));
    }

    private static Verdict atMost(String what, double v, double max) {
        return new Verdict(v <= max, String.format("%s = %.4g, expected ≤ %.4g", what, v, max));
    }

    private static Verdict that(boolean pass, String measured) {
        return new Verdict(pass, measured);
    }

    private static ServerPlayer player(net.minecraft.server.MinecraftServer s) {
        return s.getPlayerList().getPlayers().get(0);
    }

    /** The newest value on the player's data logger screen (server thread). */
    private static double loggerNow(net.minecraft.server.MinecraftServer s) {
        return SimulationManager.get(s.overworld()).loggerLatest(player(s));
    }

    /**
     * What the player's logger screen shows ({@code k} = 0: its channel, 1: its time base), as the player reads it off
     * the screen: the latest reading (null until one has come back from the server).
     */
    private static String loggerShowing(net.minecraft.server.MinecraftServer s, int k) {
        return onServerText(s, "logger" + k, () -> {
            String[] now = SimulationManager.get(s.overworld()).loggerShowing(player(s));
            return now == null ? "" : now[k];
        });
    }

    /** The burner chamber's wall (or, with {@code gas}, its gas) temperature, read from the integrated server. */
    private static double chamber(net.minecraft.server.MinecraftServer server, BlockPos at, boolean gas) {
        return onServer(server, "chamber" + gas + at, () -> chamberNow(server, at, gas));
    }

    private static double chamberNow(net.minecraft.server.MinecraftServer server, BlockPos at, boolean gas) {
        if (!(server.overworld().getBlockEntity(at) instanceof AssemblyBlockEntity be)) return 0;
        PlacedPart ch = find(be, PartKind.TANK);
        var c = ch == null ? null : SimulationManager.get(server.overworld()).compiledAt(at);
        if (c == null) return 0;
        if (gas) return c.gas(ch.id) == null ? 0 : c.gas(ch.id).temperature(c.solverView());
        var pr = c.probe(ch.id);
        return pr == null ? 0 : pr.temperature();
    }

    /** Angular velocity of the body turning the shaft in this block, read from the integrated server [rad/s]. */
    private static double wheelSpeed(net.minecraft.server.MinecraftServer server, BlockPos at) {
        return onServer(server, "wheel" + at, () -> wheelSpeedNow(server, at));
    }

    private static double wheelSpeedNow(net.minecraft.server.MinecraftServer server, BlockPos at) {
        if (!(server.overworld().getBlockEntity(at) instanceof AssemblyBlockEntity be)) return 0;
        PlacedPart sh = find(be, PartKind.SHAFT);
        var c = sh == null ? null : SimulationManager.get(server.overworld()).compiledAt(at);
        if (c == null || c.bodyOf(sh.id) == null) return 0;
        return c.angularVelocity(c.bodyOf(sh.id));
    }

    /** Whether the server has run the current step's action yet. */
    private static volatile boolean ran;
    private static final java.util.Map<String, Double> READINGS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> PENDING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * A reading of server state for a step's condition, which the tour checks on the client thread: a server level
     * hands out no block entities off its own thread (they read as null — the burner's condition never held, and the
     * tour sat out its whole wait), so the reading is taken on the server thread. Returns the latest finished reading
     * (NaN before the first: no comparison holds) and queues a fresh one.
     */
    private static double onServer(net.minecraft.server.MinecraftServer server, String key, java.util.function.DoubleSupplier read) {
        if (server.isSameThread()) return read.getAsDouble();
        if (PENDING.add(key)) server.execute(() -> {
            try {
                READINGS.put(key, read.getAsDouble());
            } finally {
                PENDING.remove(key);
            }
        });
        return READINGS.getOrDefault(key, Double.NaN);
    }

    private static final java.util.Map<String, String> TEXTS = new java.util.concurrent.ConcurrentHashMap<>();

    /** As {@link #onServer}, for a text reading (null before the first). */
    private static String onServerText(net.minecraft.server.MinecraftServer server, String key, java.util.function.Supplier<String> read) {
        if (server.isSameThread()) return read.get();
        if (PENDING.add(key)) server.execute(() -> {
            try {
                TEXTS.put(key, read.get());
            } finally {
                PENDING.remove(key);
            }
        });
        return TEXTS.get(key);
    }

    /** True once the block's chunk section is drawn on the client and its machine is compiled on the server. */
    private static boolean shown(net.minecraft.server.MinecraftServer server, BlockPos at) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.isLoaded(at) || !mc.levelRenderer.isSectionCompiled(at)) return false;
        return SimulationManager.get(server.overworld()).compiledAt(at) != null;
    }

    private static double hottest(BlockPos at) {
        double t = 0;
        if (Minecraft.getInstance().level.getBlockEntity(at) instanceof AssemblyBlockEntity be) {
            for (PlacedPart p : be.parts()) t = Math.max(t, ClientSimState.temperature(p.id, 0f));
        }
        return t;
    }

    /** True once an LED in the block at {@code at} is glowing. */
    private static boolean ledLit(BlockPos at) {
        return Minecraft.getInstance().level.getBlockEntity(at) instanceof AssemblyBlockEntity be
                && be.parts().stream().anyMatch(p -> p.kind == PartKind.LED && ClientSimState.gauge(p.id) > 1e-5f);
    }

    /** True once the fuse wire has been destroyed. */
    private static boolean gone(BlockPos at) {
        return gone(at, PartKind.WIRE);
    }

    /** True once the block at {@code at} holds no part of that kind (it burned out, broke or burst). */
    private static boolean gone(BlockPos at, PartKind kind) {
        return !(Minecraft.getInstance().level.getBlockEntity(at) instanceof AssemblyBlockEntity be)
                || be.parts().stream().noneMatch(p -> p.kind == kind);
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
        if (joinedTicks++ == 0) {
            mc.options.hideGui = true;
            mc.options.pauseOnLostFocus = false; // the tour runs in the background while people use the computer
            mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            plan(mc);
        }
        PlayerHand.keepHolding(mc);
        if (index >= STEPS.size()) {
            if (wait == 1) report(mc);
            if (wait-- == 0) mc.stop();
            if (wait < -5) wait = 40;
            return;
        }
        Step s = STEPS.get(index);
        if (wait >= 0 && ran) live(mc, s); // what the step's checks measure now, for the caption
        if (wait < 0) {
            ran = false;
            actDone = s.act == null;
            mc.getSingleplayerServer().execute(() -> {
                try {
                    s.server.run();
                } finally {
                    ran = true;
                }
            });
            wait = s.delayTicks;
            if (actDone && s.show) LoggerHud.showcase = true;
            if (s.title != null) ShowcaseHud.exhibit(s.number, s.total, s.title, s.description);
            if (s.act != null) ShowcaseHud.doing(caption(s.act.what()));
            else if (s.delayTicks > 0 && s.title == null) ShowcaseHud.doing("Watching");
            ShowcaseHud.pending(s.checkNames.stream().map(nm -> new String[]{caption(nm), "measuring…"}).toList());
        } else if (!ran) {
            // a busy server runs the step's action some ticks late: its wait and condition start from the action
        } else if (!actDone) {
            if (mc.screen != null) mc.setScreen(null); // a player's clicks go to the world, not a menu
            actDone = s.act.tick(mc);
            if (actDone && s.show) LoggerHud.showcase = true;
        } else if (s.ready != null && wait > 2 && wait < s.delayTicks - 1 && s.ready.getAsBoolean()) {
            wait = 2; // condition met: grab after a couple of frames have rendered the new state
        } else if (wait-- <= 0) {
            if (mc.screen != null) mc.setScreen(null); // never photograph a menu
            ShowcaseHud.pending(List.of());
            if (s.shot != null) {
                String at = String.format("%.1f %.1f %.1f", mc.player.getX(), mc.player.getY(), mc.player.getZ());
                Screenshot.grab(mc.gameDirectory, s.shot + ".png", mc.getMainRenderTarget(),
                        msg -> EmergenceMod.LOG.info("Showcase screenshot: {} (eye at {})", msg.getString(), at));
            }
            if (!s.checks.isEmpty() || s.after != null) {
                List<String> names = s.checkNames;
                List<java.util.function.Supplier<Verdict>> checks = s.checks;
                Runnable after = s.after;
                mc.getSingleplayerServer().execute(() -> {
                    for (int k = 0; k < checks.size(); k++) {
                        Verdict v = evaluate(checks.get(k));
                        String line = (v.pass() ? "PASS " : "FAIL ") + names.get(k) + " — " + v.measured();
                        REPORT.add(line);
                        EmergenceMod.LOG.info("Showcase check: {}", line);
                        ShowcaseHud.result(caption(names.get(k)), v.measured(), v.pass());
                    }
                    if (after != null) after.run();
                });
            }
            index++;
            wait = -1;
            READINGS.clear(); // a step's condition starts from fresh readings
            TEXTS.clear();
        }
    }

    /** A step's name for the caption: without its "exhibit:" prefix, starting with a capital. */
    static String caption(String s) {
        if (s == null) return null;
        int c = s.indexOf(": ");
        String t = c >= 0 && c < 20 ? s.substring(c + 2) : s;
        return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    /** A check's verdict, or why it could not be read. */
    private static Verdict evaluate(java.util.function.Supplier<Verdict> c) {
        try {
            return c.get();
        } catch (RuntimeException ex) {
            return new Verdict(false, "could not be read: " + ex);
        }
    }

    private static int liveTicks;

    /** Once a second while a step with checks runs, what they measure now (for the caption). */
    private static void live(Minecraft mc, Step s) {
        if (s.checks.isEmpty() || liveTicks++ % 20 != 0) return;
        List<String> names = s.checkNames;
        List<java.util.function.Supplier<Verdict>> checks = s.checks;
        mc.getSingleplayerServer().execute(() -> {
            List<String[]> rows = new ArrayList<>();
            for (int k = 0; k < checks.size(); k++) rows.add(new String[]{caption(names.get(k)), evaluate(checks.get(k)).measured()});
            ShowcaseHud.pending(rows);
        });
    }

    /** Writes the checks and the hands' failures to screenshots/showcase-report.txt and the log. */
    private static void report(Minecraft mc) {
        List<String> lines = new ArrayList<>(REPORT);
        for (String f : PlayerHand.FAILURES) lines.add("FAIL (hands) " + f);
        long failed = lines.stream().filter(l -> l.startsWith("FAIL")).count();
        String summary = String.format("Showcase checks: %d passed, %d failed", lines.size() - failed, failed);
        lines.add(0, summary);
        try {
            java.nio.file.Path dir = mc.gameDirectory.toPath().resolve("screenshots");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.write(dir.resolve("showcase-report.txt"), lines);
        } catch (java.io.IOException ex) {
            EmergenceMod.LOG.warn("Showcase report not written: {}", ex.toString());
        }
        EmergenceMod.LOG.info(summary);
    }
}
