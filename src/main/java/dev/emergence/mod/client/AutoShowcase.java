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
        final List<Sampler> samplers = new ArrayList<>();
        final List<java.util.function.DoubleSupplier> sampled = new ArrayList<>();

        /** Records a quantity through the step (every other tick, once the hands are done). */
        Step sample(Sampler s, java.util.function.DoubleSupplier read) {
            samplers.add(s);
            sampled.add(read);
            return this;
        }

        Step(int delayTicks, Runnable server, String shot) {
            this(delayTicks, server, shot, null);
        }

        Step(int delayTicks, Runnable server, String shot, java.util.function.BooleanSupplier ready) {
            this.delayTicks = delayTicks;
            this.server = server;
            this.shot = shot;
            this.ready = ready;
        }

        String title, description, exhibit;
        int number, total;

        /** The step opens an exhibit: the caption shows its title and what it demonstrates. */
        Step caption(int number, int total, String exhibit, String title, String description) {
            this.exhibit = exhibit;
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
        if (EditorShots.init()) return; // a look at the part editor instead of the tour
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> tick());
    }

    private static void plan(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        ServerLevel level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        var ex = Exhibits.forWorld();
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
                    String line = "FAIL tour: " + id + " has no part named " + name;
                    if (!REPORT.contains(line)) REPORT.add(line);
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
                    .act(PlayerHand.release()).caption(no, n, id, e.title, e.description));
            String key = id + ":";
            if (id.equals("station")) {
                // the power station, found running: its fire, its steam, its engine, its vacuum, its electricity
                BlockPos shaft = at.apply("shaft"), boiler = at.apply("boiler"), cnd = at.apply("condenser"), bat = at.apply("battery"),
                        box = at.apply("firebox");
                Sampler w0 = shaftSpeed(server, shaft), w1 = shaftSpeed(server, shaft), q = new Sampler(), t = new Sampler();
                java.util.Set<String> counts = java.util.concurrent.ConcurrentHashMap.newKeySet();
                java.util.function.Supplier<String> bits = () -> {
                    StringBuilder sb = new StringBuilder();
                    for (int k = 2; k >= 0; k--) sb.append(light(server, at.apply("bitLed" + k)) > 1e-4 ? '1' : '0');
                    return sb.toString();
                };
                // (judged over simulated time: the station runs far slower than real time in the game, and a few hundred
                // ticks were a third of a second of it, less than one turn of the crankshaft)
                double[] from = {Double.NaN}, braked = {Double.NaN};
                STEPS.add(new Step(6000, () -> from[0] = simTime(server, shaft), shot.apply("running"),
                        () -> onServer(server, key + "t", () -> simTime(server, shaft)) - from[0] > 5).sample(w0, () -> motion(server, shaft, PartKind.SHAFT))
                        .sample(q, () -> chargeDelivered(server, bat)).sample(t, () -> simTime(server, bat))
                        .sample(new Sampler(), () -> {
                            counts.add(bits.get()); // (samples are taken on the server thread)
                            return 0;
                        })
                        .check(key + " its firebox burns", () -> atLeast("firebox CO2 [%]", 100 * co2(server, box), 3))
                        .check(key + " its boiler holds steam", () -> atLeast("boiler pressure [bar]", 1e-5 * pressure(server, boiler, PartKind.TANK), 1.5))
                        .check(key + " the engine runs on it", () -> atLeast("mean crankshaft speed [rad/s]", w0.mean(), 8))
                        .check(key + " it exhausts into a partial vacuum", () -> atMost("condenser pressure [kPa]", 1e-3 * pressure(server, cnd, PartKind.TANK), 80))
                        .check(key + " its generator charges its battery", () -> atLeast("charge taken in [mA·h]", -q.change() / 3.6, 0.001))
                        .check(key + " its counter counts the engine's turns", () -> atLeast("counter states seen", counts.size(), 2)));
                // a load put on it by hand: the governor holds its speed
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " put the brake on", part.apply("brake", PartKind.BRAKE))));
                double[] on = {Double.NaN};
                STEPS.add(new Step(12000, () -> on[0] = simTime(server, shaft), null,
                        () -> onServer(server, key + "t", () -> simTime(server, shaft)) - on[0] > 20));
                STEPS.add(new Step(6000, () -> {
                    view.run();
                    braked[0] = simTime(server, shaft);
                }, shot.apply("braked"), () -> onServer(server, key + "t", () -> simTime(server, shaft)) - braked[0] > 5)
                        .sample(w1, () -> motion(server, shaft, PartKind.SHAFT))
                        .check(key + " braked, the governor holds its speed", () -> that(Math.abs(w1.mean() / w0.mean() - 1) <= 0.10,
                                String.format("mean speed %.2f → %.2f rad/s (%+.1f %%), expected within ±10 %%", w0.mean(), w1.mean(),
                                        100 * (w1.mean() / w0.mean() - 1)))));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " take the brake off", part.apply("brake", PartKind.BRAKE))));
            }
            if (id.equals("glow") || id.equals("fuse")) {
                BlockPos wire = at.apply("wire");
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " close the switch", part.apply("switch", PartKind.SWITCH))));
                // judged on its peak: the wire glows for a moment and cools while the eye flies back from the switch
                Sampler peak = new Sampler();
                STEPS.add(new Step(40, view, shot.apply("discharged"), () -> hottest(wire) > 950 || gone(wire))
                        .sample(peak, () -> has(server, wire, PartKind.WIRE) ? temperature(server, wire, PartKind.WIRE) : Double.NaN)
                        .check(key + " the discharge heats the wire to a glow (or blows it)",
                                () -> !has(server, wire, PartKind.WIRE) ? that(true, "the wire has blown") : atLeast("wire's peak temperature [K]", peak.max(), 900)));
            }
            if (id.equals("dcmotor") || id.equals("corrosion")) {
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " close the switch", part.apply("switch", PartKind.SWITCH))));
                if (id.equals("dcmotor")) nudge(key, part.apply("rotor", PartKind.SHAFT), false);
                Step s = new Step(id.equals("corrosion") ? 60 : 30, view, shot.apply("on"));
                if (id.equals("dcmotor")) {
                    s.check(key + " the motor turns", () -> atLeast("rotor speed [rad/s]", motion(server, at.apply("rotor"), PartKind.SHAFT), 2))
                            .check(key + " it draws current from its battery",
                                    () -> atLeast("battery current [A]", Math.abs(current(server, at.apply("battery"), null)), 0.02));
                } else {
                    s.check(key + " joined to copper, the zinc corrodes faster: a current flows between them",
                            () -> atLeast("current through the switch [mA]", 1e3 * Math.abs(current(server, at.apply("switch"), PartKind.SWITCH)), 0.01));
                }
                STEPS.add(s);
            }
            if (id.equals("turbine")) {
                double[] p0 = {Double.NaN};
                STEPS.add(new Step(0, () -> p0[0] = pressure(server, at.apply("receiver"), PartKind.TANK), null)
                        .act(PlayerHand.click(key + " open the valve", part.apply("valve", PartKind.VALVE))));
                STEPS.add(new Step(16, view, shot.apply("on")).check(key + " the air turns the turbine",
                                () -> atLeast("shaft speed [rad/s]", motion(server, at.apply("shaft"), PartKind.SHAFT), 0.5))
                        .check(key + " the air comes out of the receiver", () -> atLeast("receiver pressure drop [kPa]",
                                1e-3 * (p0[0] - pressure(server, at.apply("receiver"), PartKind.TANK)), 0.1)));
            }
            if (id.equals("transistor")) {
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " close the switch", part.apply("switch", PartKind.SWITCH))));
                STEPS.add(new Step(20, view, shot.apply("on")).check(key + " the transistor lights the LED", () -> lit(server, at.apply("led")))
                        .check(key + " a small base current switches a large one (current gain)", () -> atLeast("LED current ÷ base current",
                                Math.abs(current(server, at.apply("led"), PartKind.LED) / current(server, at.apply("baseResistor"), PartKind.RESISTOR)), 10)));
            }
            if (id.equals("nand")) {
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " input A high", part.apply("a", PartKind.SWITCH))));
                STEPS.add(new Step(20, view, shot.apply("a")).check(key + " NAND(1, 0) = 1: the LED is lit", () -> lit(server, at.apply("led"))));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " input B high", part.apply("b", PartKind.SWITCH))));
                STEPS.add(new Step(20, view, shot.apply("ab")).check(key + " NAND(1, 1) = 0: the LED is dark", () -> dark(server, at.apply("led"))));
            }
            if (id.equals("thermostat")) {
                Sampler plate = new Sampler();
                STEPS.add(new Step(200, NOTHING, shot.apply("later")).sample(plate, () -> temp(server, at.apply("plate"), PartKind.BLOCK))
                        .check(key + " the plate is held warm, between its thresholds", () -> that(plate.min() > 305 && plate.max() < 345,
                                String.format("plate %.1f–%.1f K over the watch, expected within 305–345 K", plate.min(), plate.max()))));
            }
            if (id.equals("counter")) {
                Sampler count = new Sampler();
                STEPS.add(new Step(110, NOTHING, shot.apply("later")).sample(count, () -> {
                    int v = 0;
                    for (int k = 0; k < 4; k++) if (current(server, at.apply("bit" + k), null) > 0.5) v |= 1 << k;
                    return v;
                }).check(key + " the clock steps the count", () -> that(count.range() >= 1,
                        String.format("count went %.0f–%.0f over the watch, expected it to change", count.min(), count.max()))));
            }
            if (id.equals("stirling")) {
                Sampler load = new Sampler();
                STEPS.add(new Step(100, NOTHING, shot.apply("running")).sample(load, () -> Math.abs(current(server, at.apply("load"), null)))
                        .check(key + " the engine runs on the hot block's heat", () -> atLeast("flywheel speed [rad/s]", motion(server, at.apply("shaft"), PartKind.SHAFT), 1))
                        .check(key + " and drives its generator", () -> atLeast("peak load current [mA]", 1e3 * load.max(), 1)));
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
                Sampler rotor = new Sampler();
                STEPS.add(new Step(200, NOTHING, shot.apply("running")).sample(rotor, () -> where(server, at.apply("rotor"), null))
                        .check(key + " the program steps the motor's rotor round", () -> atLeast("rotor turned [°]",
                                Math.toDegrees(rotor.range()), 30)));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " flip the sense switch", part.apply("sense", PartKind.SWITCH))));
                STEPS.add(new Step(200, view, shot.apply("reverse")));
            }
            if (id.equals("governed")) {
                // running on its governor (the weights out, the throttle mostly shut); take the brake off: it hardly
                // speeds up, the governor shutting the steam off; put it back
                BlockPos shaft = at.apply("shaft"), rod = at.apply("throttleRod");
                double[] rod0 = {Double.NaN}, off = {Double.NaN};
                // its speed as the mean over ten seconds of its own time, at every solver step: it surges and slows with
                // each stroke and as the governor hunts, and a moment's reading compares two points of that swing
                Sampler braked = shaftSpeed(server, shaft), free = shaftSpeed(server, shaft);
                STEPS.add(new Step(80, NOTHING, shot.apply("running"))
                        .check(key + " the engine runs on its boiler's steam", () -> atLeast("crankshaft speed [rad/s]", motion(server, shaft, PartKind.SHAFT), 8))
                        .check(key + " the governor's weights have flown out", () -> atLeast("weight out [mm]",
                                1e3 * Math.abs(where(server, at.apply("weightA"), PartKind.BLOCK)), 5)));
                STEPS.add(new Step(200, NOTHING, null).sample(braked, () -> motion(server, shaft, PartKind.SHAFT))
                        .after(() -> rod0[0] = where(server, rod, PartKind.SHAFT)));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " take the brake off", part.apply("brake", PartKind.BRAKE))));
                // the governor wins the first surge back over some ten seconds (to +6 % in the core): judged after 20 s
                STEPS.add(new Step(1200, () -> off[0] = simTime(server, shaft), null,
                        () -> onServer(server, key + "t", () -> simTime(server, shaft)) - off[0] > 20));
                STEPS.add(new Step(200, view, shot.apply("free")).sample(free, () -> motion(server, shaft, PartKind.SHAFT))
                        .check(key + " unloaded, it hardly speeds up", () -> that(Math.abs(free.mean() / braked.mean() - 1) <= 0.06,
                                String.format("mean speed %.2f → %.2f rad/s (%+.1f %%), expected within ±6 %%", braked.mean(), free.mean(),
                                        100 * (free.mean() / braked.mean() - 1))))
                        .check(key + " the throttle rod still on its seat's side of open", () -> atMost("throttle rod raised [mm]",
                                1e3 * (where(server, rod, PartKind.SHAFT) - rod0[0]), 1)));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " put the brake back", part.apply("brake", PartKind.BRAKE))));
            }
            if (id.equals("steam") || id.equals("condensing")) {
                BlockPos shaft = at.apply("shaft");
                double[] running = {Double.NaN};
                Step run = new Step(80, NOTHING, shot.apply("running")).check(key + " the engine runs on its boiler's steam",
                                () -> atLeast("crankshaft speed [rad/s]", motion(server, shaft, PartKind.SHAFT), 3))
                        .check(key + " the boiler is pressed", () -> atLeast("boiler pressure [bar]", 1e-5 * pressure(server, at.apply("boiler"), PartKind.TANK), 2))
                        .after(() -> running[0] = motion(server, shaft, PartKind.SHAFT));
                if (id.equals("condensing")) run.check(key + " the condenser holds a vacuum", () -> atMost("condenser pressure [kPa]",
                        1e-3 * pressure(server, at.apply("condenser"), PartKind.TANK), 20));
                STEPS.add(run);
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " shut the regulator", part.apply("regulator", PartKind.VALVE))));
                double[] boiler0 = {Double.NaN};
                STEPS.add(new Step(0, () -> boiler0[0] = pressure(server, at.apply("boiler"), PartKind.TANK), null));
                STEPS.add(new Step(200, view, shot.apply("shut")).check(key + " without steam it slows",
                                () -> atMost("crankshaft speed [rad/s]", motion(server, shaft, PartKind.SHAFT), 0.97 * running[0]))
                        .check(key + " and the boiler, no longer drawn on, presses up", () -> atLeast("boiler pressure rise [kPa]",
                                1e-3 * (pressure(server, at.apply("boiler"), PartKind.TANK) - boiler0[0]), 1)));
            }
            if (id.equals("servo")) {
                // turn the knob down to its stop: the arm rises against gravity to just above horizontal
                BlockPos knob = at.apply("crank");
                double[] before = {Double.NaN};
                STEPS.add(new Step(0, () -> before[0] = position(server, knob, PartKind.CRANK), null)
                        .act(PlayerHand.hold(key + " turn the knob down", part.apply("crank", PartKind.CRANK), true)));
                double[] arm0 = {Double.NaN};
                STEPS.add(new Step(0, () -> arm0[0] = where(server, at.apply("arm"), null), null));
                STEPS.add(new Step(220, NOTHING, shot.apply("raised")).check(key + " the knob turned",
                                () -> atLeast("knob turned [rad]", Math.abs(position(server, knob, PartKind.CRANK) - before[0]), 0.3))
                        .check(key + " the arm follows it up, against gravity", () -> atLeast("arm raised [°]",
                                Math.toDegrees(Math.abs(where(server, at.apply("arm"), null) - arm0[0])), 30)));
            }
            if (id.equals("explosion")) {
                // the data logger on the can's gas, then the heater switch: shoot when the gas runs away
                logOn(key, part.apply("can", PartKind.TANK), "fluid temperature", 2, server, logger);
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.clickWith(key + " close the heater switch", part.apply("switch", PartKind.SWITCH), logger)));
                // the spike lasts a few tenths of a second of simulated time: the logger's screen holds it for its time base
                Sampler screen = new Sampler();
                STEPS.add(new Step(4000, view, null, () -> onServer(server, "ch4", () -> gasShare(server, at.apply("can"), "CH4")) < 0.01).show()
                        .sample(screen, () -> SimulationManager.get(server.overworld()).loggerPeak(player(server))));
                STEPS.add(new Step(10, NOTHING, shot.apply("ignited")).show().sample(screen, () -> SimulationManager.get(server.overworld()).loggerPeak(player(server)))
                        .check(key + " the methane has burned", () -> atMost("methane left in the can [%]", 100 * gasShare(server, at.apply("can"), "CH4"), 1))
                        .check(key + " the methane–air runs away and ignites", () -> atLeast("peak gas temperature seen on the logger's screen [K]",
                                screen.max(), 1500)));
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
                Sampler load = new Sampler(), swing = new Sampler();
                STEPS.add(new Step(40, view, shot.apply("bouncing")).sample(load, () -> power(server, at.apply("load"), null))
                        .sample(swing, () -> where(server, magnet, PartKind.MAGNET))
                        .check(key + " the pushed magnet bounces on its spring", () -> atLeast("magnet's swing [mm]", 1e3 * swing.range(), 0.5))
                        .check(key + " its moving field drives power into the load", () -> atLeast("peak load power [µW]", 1e6 * load.max(), 1)));
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
            if (id.equals("waterwheel")) {
                // the stream, run down its channel from its springs, turns the wheel by its lowest paddle, and the
                // wheel's magnets drive a current through the load
                Sampler load = new Sampler();
                STEPS.add(new Step(200, view, null));
                STEPS.add(new Step(40, view, shot.apply("turning")).show().sample(load, () -> power(server, at.apply("load"), PartKind.RESISTOR))
                        .check(key + " the stream turns the wheel", () -> atLeast("wheel speed [rad/s]",
                                Math.abs(spin(server, at.apply("axle"), PartKind.SHAFT)), 5))
                        .check(key + " its generator powers the load", () -> atLeast("peak load power [mW]", 1e3 * load.max(), 1)));
            }
            if (id.equals("floats")) {
                // the water lifts each float by the weight of the water it pushes aside
                java.util.function.DoubleSupplier oak = () -> where(server, at.apply("oakRod"), PartKind.SHAFT),
                        steel = () -> where(server, at.apply("steelRod"), PartKind.SHAFT),
                        can = () -> where(server, at.apply("canRod"), PartKind.SHAFT);
                STEPS.add(new Step(100, view, shot.apply("afloat")).show()
                        .check(key + " the oak floats", () -> that(Math.abs(oak.getAsDouble()) < 0.029,
                                String.format("the oak block at %+.1f mm, within its rod's ±30 mm", 1e3 * oak.getAsDouble())))
                        .check(key + " the steel block sinks", () -> atMost("steel block's travel [mm]", 1e3 * steel.getAsDouble(), -25))
                        .check(key + " the steel can rides highest", () -> that(can.getAsDouble() > oak.getAsDouble() + 0.005,
                                String.format("the can at %+.1f mm, the oak at %+.1f mm", 1e3 * can.getAsDouble(), 1e3 * oak.getAsDouble()))));
            }
            if (id.equals("regulator")) {
                // the receiver blows down through the needle; the regulator between them holds the outlet
                BlockPos receiver = at.apply("receiver"), outlet = at.apply("outlet");
                double[] pr0 = {Double.NaN}, po0 = {Double.NaN};
                STEPS.add(new Step(60, view, null));
                STEPS.add(new Step(0, () -> {
                    pr0[0] = pressure(server, receiver, PartKind.TANK);
                    po0[0] = pressure(server, outlet, PartKind.TANK);
                }, null));
                STEPS.add(new Step(300, view, shot.apply("later")).show()
                        .check(key + " the receiver blows down", () -> atLeast("receiver pressure drop [bar]",
                                1e-5 * (pr0[0] - pressure(server, receiver, PartKind.TANK)), 1))
                        .check(key + " the outlet holds", () -> atMost("outlet pressure change [bar]",
                                1e-5 * Math.abs(pressure(server, outlet, PartKind.TANK) - po0[0]), 0.1))
                        .check(key + " at the spring's setting", () -> atLeast("outlet pressure [bar gauge]",
                                1e-5 * (pressure(server, outlet, PartKind.TANK) - 101_325), 1.5)));
            }
            if (id.equals("slidevalve")) {
                // draw the rod out by its knob: the plug leaves its seat and the air spins the rotor; push it home and the
                // receiver holds what is left (its pressure even rising as its walls warm the air the expansion cooled)
                BlockPos rod = at.apply("rod"), receiver = at.apply("receiver");
                PlayerHand.Target knob = part.apply("knob", PartKind.BLOCK);
                double[] p0 = {Double.NaN}, x0 = {Double.NaN}, pShut = {Double.NaN};
                STEPS.add(new Step(0, () -> {
                    p0[0] = pressure(server, receiver, PartKind.TANK);
                    x0[0] = where(server, rod, PartKind.SHAFT);
                }, null));
                STEPS.add(new Step(6, NOTHING, null).act(PlayerHand.hold(key + " draw the rod out", knob, false)));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.release()));
                STEPS.add(new Step(8, view, shot.apply("open"))
                        .check(key + " the rod is drawn back", () -> atLeast("rod's travel [mm]", 1e3 * Math.abs(where(server, rod, PartKind.SHAFT) - x0[0]), 20))
                        .check(key + " the air turns the rotor", () -> atLeast("shaft speed [rad/s]", Math.abs(spin(server, at.apply("shaft"), PartKind.SHAFT)), 5))
                        .check(key + " the receiver empties through the seat", () -> atLeast("receiver pressure drop [kPa]",
                                1e-3 * (p0[0] - pressure(server, receiver, PartKind.TANK)), 10)));
                STEPS.add(new Step(6, NOTHING, null).act(PlayerHand.hold(key + " push the rod home", knob, true)));
                STEPS.add(new Step(0, () -> pShut[0] = pressure(server, receiver, PartKind.TANK), null).act(PlayerHand.release()));
                STEPS.add(new Step(30, view, shot.apply("shut"))
                        .check(key + " the rod is home", () -> atMost("rod's travel [mm]", 1e3 * Math.abs(where(server, rod, PartKind.SHAFT) - x0[0]), 3))
                        .check(key + " the plug seated, the receiver holds its air", () -> atLeast("receiver pressure change [kPa]",
                                1e-3 * (pressure(server, receiver, PartKind.TANK) - pShut[0]), -1)));
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
                double[] arm0 = {Double.NaN};
                STEPS.add(new Step(0, () -> arm0[0] = where(server, at.apply("slide"), PartKind.SHAFT), null)
                        .act(PlayerHand.click(key + " close the coil's switch", part.apply("switch", PartKind.SWITCH))));
                STEPS.add(new Step(60, view, shot.apply("energised")).check(key + " the contacts light the lamp", () -> lit(server, at.apply("lamp")))
                        .check(key + " the coil pulls the armature up", () -> atLeast("armature travel [mm]",
                                1e3 * Math.abs(where(server, at.apply("slide"), PartKind.SHAFT) - arm0[0]), 0.2)));
            }
            if (id.equals("latch")) {
                // START on and off again: it holds itself in; STOP off and on again: it stays out
                double[] arm0 = {Double.NaN};
                java.util.function.DoubleSupplier arm = () -> where(server, at.apply("slide"), PartKind.SHAFT);
                PlayerHand.Target start = part.apply("start", PartKind.SWITCH), stop = part.apply("stop", PartKind.SWITCH);
                STEPS.add(new Step(0, () -> arm0[0] = arm.getAsDouble(), null).act(PlayerHand.click(key + " close START", start)));
                STEPS.add(new Step(40, view, shot.apply("pulled")).check(key + " START pulls it in", () -> atLeast(
                        "armature raised [mm]", 1e3 * (arm.getAsDouble() - arm0[0]), 2)));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " open START again", start)));
                STEPS.add(new Step(60, view, shot.apply("held")).check(key + " START let go, it holds itself in", () -> atLeast(
                        "armature raised [mm]", 1e3 * (arm.getAsDouble() - arm0[0]), 2)).check(key + " its LED stays lit", () -> lit(server, at.apply("lamp"))));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " open STOP", stop)));
                STEPS.add(new Step(40, NOTHING, null));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " close STOP again", stop)));
                STEPS.add(new Step(60, view, shot.apply("released")).check(key + " STOP broke the hold; closed again, it stays out", () -> that(
                        Math.abs(arm.getAsDouble() - arm0[0]) < 0.3e-3, String.format("armature %.2f mm from where it rested", 1e3 * (arm.getAsDouble() - arm0[0])))));
            }
            if (id.equals("still")) {
                // heater on: the warm pot boils and distillate gathers in the receiver
                BlockPos pot = at.apply("pot");
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " switch the heater on", part.apply("switch", PartKind.SWITCH))));
                STEPS.add(new Step(1200, view, shot.apply("boiling"), () -> onServer(server, "pot", () -> temperature(server, pot, PartKind.TANK)) > 345)
                        .check(key + " the pot heats towards boiling",
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
                    Step st = new Step(60, view, shot.apply(sw)).check(key + " the filament is hotter after " + sw,
                                    () -> atLeast("filament temperature [K]", temperature(server, fil, PartKind.WIRE), last[0] + 50))
                            .after(() -> last[0] = temperature(server, fil, PartKind.WIRE));
                    if (sw.equals("switch")) st.check(key + " red heat: the infrared channel lights", () -> lit(server, at.apply("lit_infrared")));
                    if (sw.equals("hotter")) st.check(key + " white heat: the blue channel lights", () -> lit(server, at.apply("lit_blue")));
                    STEPS.add(st);
                }
            }
            if (id.equals("memory")) {
                // write 1010 into word 1, look at word 0 (empty), then back at word 1
                for (String sw : new String[]{"a0", "d1", "d3"}) {
                    STEPS.add(new Step(2, NOTHING, null).act(PlayerHand.click(key + " set " + sw, part.apply(sw, PartKind.SWITCH))));
                }
                STEPS.add(new Step(2, NOTHING, null).act(PlayerHand.click(key + " write (press)", part.apply("write", PartKind.SWITCH))));
                STEPS.add(new Step(2, NOTHING, null).act(PlayerHand.click(key + " write (release)", part.apply("write", PartKind.SWITCH))));
                STEPS.add(new Step(20, view, shot.apply("written")));
                STEPS.add(new Step(2, NOTHING, null).act(PlayerHand.click(key + " address word 0", part.apply("a0", PartKind.SWITCH))));
                STEPS.add(new Step(30, view, shot.apply("word0")).check(key + " word 0 is empty", () -> {
                    boolean any = false;
                    for (int k = 0; k < 4; k++) any |= light(server, at.apply("q" + k)) > 1e-5;
                    return that(!any, "outputs " + bits(server, at) + ", expected 0000");
                }));
                STEPS.add(new Step(2, NOTHING, null).act(PlayerHand.click(key + " address word 1", part.apply("a0", PartKind.SWITCH))));
                STEPS.add(new Step(30, view, shot.apply("word1")).check(key + " word 1 reads back 1010", () ->
                        that(bits(server, at).equals("1010"), "outputs " + bits(server, at) + " (q3 q2 q1 q0), expected 1010")));
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
                BlockPos sw = at.apply("switch");
                STEPS.add(new Step(200, view, shot.apply("on"), () -> ledLit(led)).check(key + " the signal crosses as light", () -> {
                    Verdict v = lit(server, led);
                    PlacedPart p = any(server, sw, PartKind.SWITCH);
                    if (v.pass()) return v;
                    return new Verdict(false, v.measured() + (p == null ? "; no switch found" : p.closed ? "; its switch is closed" : "; its switch is still open")
                            + String.format("; IR LED %.3g mW, phototransistor %.3g mA", 1e3 * light(server, at.apply("ir")), 1e3 * Math.abs(current(server, at.apply("photo"), null))));
                }));
            }
            if (id.equals("windturbine")) {
                // the wind (from the west, by the weather) turns the rotor up to speed
                BlockPos shaftAt = at.apply("shaft");
                STEPS.add(new Step(2400, NOTHING, shot.apply("turning"), () -> Math.abs(wheelSpeed(server, shaftAt)) > 30)
                        .check(key + " the wind turns the rotor", () -> atLeast("rotor speed [rad/s]", motion(server, shaftAt, PartKind.SHAFT), 20)));
                // (time-averaged at every solver step: the output pulses as each magnet passes the coils)
                BlockPos loadAt = at.apply("load");
                Sampler load = new TimeAverage(() -> compiled(server, loadAt), () -> {
                    PlacedPart p = any(server, loadAt, null);
                    if (p == null) return null;
                    long loadId = p.id;
                    return c -> {
                        Probe pr = c.probe(loadId);
                        return pr == null ? Double.NaN : Math.abs(pr.power());
                    };
                });
                STEPS.add(new Step(100, NOTHING, null).sample(load, () -> power(server, loadAt, null))
                        // (its output goes roughly as the square of its speed: in a lull at 30 rad/s, a fraction of a watt —
                        // the check is that power arrives, not how strong the wind is)
                        .check(key + " its generator delivers power to the load", () -> atLeast("mean load power [W]", load.mean(), 0.05)));
            }
            if (id.equals("weights")) {
                // a hand on the crank: the beam spins up and the weights slide out against their springs
                BlockPos shaftAt = at.apply("shaft");
                STEPS.add(new Step(2, NOTHING, shot.apply("rest")));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.hold(key + " turn the crank", part.apply("crank", PartKind.CRANK), false)));
                STEPS.add(new Step(1200, NOTHING, shot.apply("spinning"), () -> Math.abs(wheelSpeed(server, shaftAt)) > 13)
                        .check(key + " cranking spins the beam up", () -> atLeast("beam speed [rad/s]", motion(server, shaftAt, PartKind.SHAFT), 10))
                        .check(key + " the weights slide out against their springs", () -> atLeast("weight's travel out [mm]",
                                1e3 * Math.abs(where(server, at.apply("rodA"), PartKind.SHAFT)), 5)));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.release()));
            }
            if (id.equals("fourbar")) {
                // the flywheel turns the crank round; the coupler swings the rocker one way, then the other. It has been
                // coasting down since the gallery opened: a few turns of its crank first, as a player would
                STEPS.add(new Step(60, NOTHING, null).act(PlayerHand.hold(key + " turn the crank", part.apply("crank", PartKind.CRANK), false)));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.release()));
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
                // the platform's swing in one-second windows, by the wheel's speed: well above resonance (the least,
                // once any start-up transient has died away), through it (the most), and well below (now)
                Swing fast = new Swing(10), res = new Swing(10), slow = new Swing(10);
                java.util.function.DoubleSupplier rod = () -> where(server, at.apply("rod"), PartKind.SHAFT);
                java.util.function.DoubleSupplier wheel = () -> Math.abs(motion(server, shaftAt, PartKind.SHAFT));
                Step coast = new Step(3000, NOTHING, shot.apply("resonance"), () -> Math.abs(wheelSpeed(server, shaftAt)) < 6)
                        .sample(fast, () -> wheel.getAsDouble() > 16 ? rod.getAsDouble() : Double.NaN)
                        .sample(res, () -> Math.abs(wheel.getAsDouble() - 11.5) < 3 ? rod.getAsDouble() : Double.NaN)
                        .sample(slow, () -> wheel.getAsDouble() < 8 ? rod.getAsDouble() : Double.NaN)
                        .check(key + " it coasts down through resonance", () -> atMost("wheel speed [rad/s]", motion(server, shaftAt, PartKind.SHAFT), 7))
                        .check(key + " near resonance the platform shakes hardest", () -> that(res.peak() > 1.5 * fast.least(),
                                String.format("swing %.2f mm near resonance, %.2f mm well above it, expected well over", 1e3 * res.peak(), 1e3 * fast.least())))
                        .check(key + " and calms again below it", () -> that(slow.last() < 0.7 * res.peak(),
                                String.format("swing now %.2f mm, %.2f mm near resonance, expected well under", 1e3 * slow.last(), 1e3 * res.peak())));
                // spin it up by its crank, as a player would, then let go and watch it coast down
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.hold(key + " spin the wheel up", part.apply("crank", PartKind.CRANK), false)));
                STEPS.add(new Step(800, NOTHING, null, () -> Math.abs(wheelSpeed(server, shaftAt)) > 22));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.release()));
                STEPS.add(new Step(10, NOTHING, shot.apply("fast")));
                STEPS.add(coast);
            }
            if (id.equals("fancooling")) {
                // a hand on the crank: the fan's jet cools its plate until it is well below its twin in still air
                BlockPos cooled = at.apply("cooled"), still = at.apply("still");
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.hold(key + " turn the fan's crank", part.apply("crank", PartKind.CRANK), false)));
                STEPS.add(new Step(3000, NOTHING, shot.apply("cranked"), () -> hottest(still) - hottest(cooled) > 5.5)
                        .check(key + " the jet cools its plate below its twin's", () -> atLeast("still − cooled plate [K]",
                                maxTemperature(server, still) - maxTemperature(server, cooled), 5)));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.release()));
            }
            if (id.equals("governor")) {
                // at rest, its switch on: a turn by hand starts it; then apply the brake: the speed sags, the drive rises
                // and brings it back
                nudge(key, part.apply("rotor", PartKind.SHAFT), false);
                governed(key, server, at.apply("rotor"), PartKind.SHAFT, part.apply("brake", PartKind.BRAKE), view, shot, null, 0.05, at.apply("battery"), 4);
            }
            if (id.equals("contactgov")) {
                // the rotor's speed on the logger; close the switch (it comes up to its governed speed and holds), then
                // brake it: the speed holds while the current rises
                logOn(key, part.apply("rotor", PartKind.SHAFT), "angular velocity", 2, server, logger);
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.clickWith(key + " close the switch", part.apply("switch", PartKind.SWITCH), logger)));
                nudge(key, part.apply("rotor", PartKind.SHAFT), false);
                governed(key, server, at.apply("rotor"), PartKind.SHAFT, part.apply("brake", PartKind.BRAKE), view, shot, logger, 0.05, at.apply("battery"), 4); // (the core test holds it within 2 %; five-second windows on its contact's limit cycle read coarser)
            }
            if (id.equals("flyball")) {
                // close the switch: the weights fly out and the push rod turns the rheostat in; then brake it
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " close the switch", part.apply("switch", PartKind.SWITCH))));
                nudge(key, part.apply("rotor", PartKind.SHAFT), false);
                governed(key, server, at.apply("rotor"), PartKind.SHAFT, part.apply("brake", PartKind.BRAKE), view, shot, null, 0.06, at.apply("battery"), 70); // (a governor without reset: it droops a few percent; a minute up from rest)
            }
            if (id.equals("column")) {
                // the heater on: the pot boils, the trays warm one by one and the condensate runs back down
                BlockPos pot = at.apply("pot");
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " switch the heater on", part.apply("switch", PartKind.SWITCH))));
                STEPS.add(new Step(2400, view, shot.apply("refluxing"), () -> onServer(server, "pot", () -> temperature(server, pot, PartKind.TANK)) > 350)
                        .check(key + " the pot heats up",
                        () -> atLeast("pot temperature [K]", temperature(server, pot, PartKind.TANK), 340)));
            }
            if (id.equals("trembler")) {
                // close the switch: the relay chatters, opening its own coil's circuit eight times a second
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " close the switch", part.apply("switch", PartKind.SWITCH))));
                Sampler arm = new Sampler(), lamp = new Sampler();
                STEPS.add(new Step(60, view, shot.apply("buzzing")).sample(arm, () -> where(server, at.apply("slide"), PartKind.SHAFT))
                        .sample(lamp, () -> light(server, at.apply("lamp")))
                        .check(key + " the armature chatters to and fro", () -> atLeast("armature swing [mm]", 1e3 * arm.range(), 0.1))
                        .check(key + " the lamp flickers with it", () -> that(lamp.max() > 1e-5 && lamp.min() < 0.5 * lamp.max(),
                                String.format("lamp light %.3g–%.3g mW, expected on and off", 1e3 * lamp.min(), 1e3 * lamp.max()))));
                STEPS.add(new Step(3, NOTHING, shot.apply("buzzing2")));
            }
            if (id.equals("eddy")) {
                // both discs at rest: crank each up and let go — the aluminium one between the magnets stops, the nylon coasts
                for (String d : new String[]{"nylon", "aluminium"}) {
                    STEPS.add(new Step(100, NOTHING, null).act(PlayerHand.hold(key + " crank the " + d + " disc", part.apply(d + "Crank", PartKind.CRANK), false)));
                    STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.release()));
                }
                STEPS.add(new Step(60, NOTHING, shot.apply("braked")).check(key + " the magnets brake the aluminium disc, not the nylon one", () -> {
                    double al = motion(server, at.apply("aluminiumShaft"), PartKind.SHAFT), ny = motion(server, at.apply("nylonShaft"), PartKind.SHAFT);
                    return that(al < 0.6 * ny, String.format("aluminium %.1f rad/s, nylon %.1f rad/s, expected aluminium well below", al, ny));
                }));
            }
            if (id.equals("fatigue")) {
                // both at rest: crank each up through its gears and let go; the aluminium shaft cracks after some two
                // thousand turns and drops its disc
                BlockPos root = at.apply("aluminiumRoot");
                for (String k : new String[]{"aluminium", "steel"}) {
                    BlockPos shaftAt = at.apply(k + "Root");
                    STEPS.add(new Step(600, NOTHING, null, () -> motion(server, shaftAt, PartKind.SHAFT) > 120)
                            .act(PlayerHand.hold(key + " crank the " + k + " shaft up to speed", part.apply(k + "Crank", PartKind.CRANK), false)));
                    STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.release()));
                }
                STEPS.add(new Step(40, view, shot.apply("spinning")).check(key + " the hand spins both shafts up through the gears", () -> {
                    double a = motion(server, at.apply("aluminiumRoot"), PartKind.SHAFT), st = motion(server, at.apply("steelRoot"), PartKind.SHAFT);
                    return that(a > 60 && st > 60, String.format("aluminium %.0f rad/s, steel %.0f rad/s, expected both above 60", a, st));
                }));
                STEPS.add(new Step(8000, NOTHING, shot.apply("cracked"), () -> gone(root, PartKind.SHAFT))
                        .check(key + " the aluminium shaft fails by fatigue", () -> that(!has(server, root, PartKind.SHAFT),
                                "aluminium shaft " + (has(server, root, PartKind.SHAFT) ? "still whole" : "cracked") + "; expected to crack")));
            }
            if (id.equals("lamp")) {
                // switch the lamp on: the filament glows white-hot and the electric eye lights its green LED
                BlockPos fil = at.apply("filament");
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " switch the lamp on", part.apply("switch", PartKind.SWITCH))));
                STEPS.add(new Step(600, view, shot.apply("on"), () -> hottest(fil) > 2300).check(key + " the filament glows white-hot",
                                () -> atLeast("filament temperature [K]", temperature(server, fil, PartKind.WIRE), 2000))
                        .check(key + " its light reaches the photodiode: the electric eye lights", () -> lit(server, at.apply("lit"))));
            }
            if (id.equals("furnace")) {
                // the plate heats in the dish's focus until it glows
                BlockPos plate = at.apply("target");
                STEPS.add(new Step(12000, NOTHING, shot.apply("hot"), () -> hottest(plate) > 810).check(key + " the focused sunlight heats the plate red-hot",
                        () -> atLeast("plate temperature [K]", maxTemperature(server, plate), 800)));
            }
            if (id.equals("engine")) {
                // standing, as a player finds it: the fuel opened, the handle swung until it fires, then the brake put on
                BlockPos shaft = at.apply("crankshaft");
                double handTop = dev.emergence.core.assembly.HumanEffort.MAX_HAND_SPEED / 0.04; // the 4 cm handle
                double[] free = {Double.NaN};
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " open the fuel", part.apply("needle", PartKind.VALVE)))
                        .after(() -> dumpIf(server, key, shaft)));
                // held until it fires and runs away from the hand (a hand cannot drive it faster than handTop): ten seconds of
                // its own time do it, but a busy full tour runs its island at a fifth of real time or less, so up to 3 minutes
                STEPS.add(new Step(3600, NOTHING, null, () -> motion(server, shaft, PartKind.SHAFT) > handTop + 5)
                        .act(PlayerHand.hold(key + " swing the starting handle until it fires", part.apply("handle", PartKind.CRANK), false)));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.release()));
                STEPS.add(new Step(200, view, shot.apply("running")).check(key + " it fires and runs on, faster than a hand can turn it",
                        () -> atLeast("crankshaft speed [rad/s]", motion(server, shaft, PartKind.SHAFT), handTop))
                        .after(() -> {
                            free[0] = motion(server, shaft, PartKind.SHAFT);
                            dumpIf(server, key, shaft);
                        }));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " put the brake on", part.apply("brake", PartKind.BRAKE))));
                STEPS.add(new Step(200, view, shot.apply("loaded")).check(key + " it carries the brake: still running, working against it", () -> {
                    double w = motion(server, shaft, PartKind.SHAFT), p = brakePower(server, at.apply("brake"));
                    return that(w > 20 && p > 5, String.format("crankshaft %.0f rad/s under the brake (%.0f free), the brake taking %.1f W; expected above 20 rad/s and 5 W",
                            w, free[0], p));
                }));
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
                double[] cold0 = {Double.NaN};
                STEPS.add(new Step(0, () -> cold0[0] = pressure(server, at.apply("coldTank"), PartKind.TANK), null));
                STEPS.add(new Step(200, NOTHING, shot.apply("later"))
                        .check(key + " the heated vessel's pressure rises", () -> atLeast("hot vessel over the cold one [kPa]",
                                1e-3 * (pressure(server, at.apply("hotTank"), PartKind.TANK) - pressure(server, at.apply("coldTank"), PartKind.TANK)), 5))
                        .check(key + " the cold one stays as it was", () -> atMost("cold vessel's change [kPa]",
                                1e-3 * Math.abs(pressure(server, at.apply("coldTank"), PartKind.TANK) - cold0[0]), 0.5)));
            }
            if (id.equals("generator")) {
                // the data logger clipped to the load resistor, then a hand on the crank: its screen shows the alternating EMF
                logOn(key, part.apply("load", PartKind.RESISTOR), "voltage", 1, server, logger);
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.holdWith(key + " turn the crank", part.apply("crank", PartKind.CRANK), false, logger)));
                Sampler load = new Sampler();
                STEPS.add(new Step(30, NOTHING, shot.apply("scope")).show().sample(load, () -> power(server, at.apply("load"), PartKind.RESISTOR))
                        .check(key + " the load takes power from it", () -> atLeast("peak load power [mW]", 1e3 * load.max(), 1))
                        .check(key + " cranking drives an alternating EMF across the load",
                        () -> atLeast("peak voltage on the logger's screen [V]", SimulationManager.get(server.overworld()).loggerPeak(player(server)), 0.05)));
            }
            if (id.equals("boost")) {
                // the logger on the switch: a square wave whose top stands above the 5 V supply
                logOn(key, part.apply("switch", PartKind.TRANSISTOR), "voltage", 0, server, logger);
                STEPS.add(new Step(100, view, shot.apply("switching")).show().check(key + " the boosted output lights its LED", () -> lit(server, at.apply("led1")))
                        .check(key + " its output stands above the 5 V supply", () -> atLeast("output capacitor [V]",
                                Math.abs(volts(server, at.apply("cout"), PartKind.CAPACITOR)), 5.5)));
            }
            if (id.equals("inverter")) {
                // the logger on the secondary: a square wave five times the supply, reversing each half-cycle
                logOn(key, part.apply("secondary", PartKind.COIL), "voltage", 1, server, logger);
                Sampler g = new Sampler(), r = new Sampler();
                // watched until each LED has lit (2.6 Hz in its own time, but its chip-level island can run at a tenth of
                // real time while the server is busy: a fixed wait may not see a whole cycle)
                STEPS.add(new Step(800, view, shot.apply("alternating"), () -> g.max() > 1e-5 && r.max() > 1e-5)
                        .show().sample(g, () -> light(server, at.apply("green")))
                        .sample(r, () -> light(server, at.apply("red")))
                        .check(key + " the alternating output lights both LEDs, each its half-cycle", () -> that(g.max() > 1e-5 && r.max() > 1e-5,
                                String.format("peak light green %.3g mW, red %.3g mW, expected both lit", 1e3 * g.max(), 1e3 * r.max())))
                        .check(key + " stepped up well above the supply", () -> atLeast("peak secondary voltage on the logger [V]",
                                SimulationManager.get(server.overworld()).loggerPeak(player(server)), 20)));
            }
            if (id.equals("propcart")) {
                // spin the propeller up by its crank, let go, and the cart runs along its rail on the propeller's thrust
                // the way the cart can go: a shifting wind may have pushed it to one end of its rail before anyone came
                double[] x0 = {Double.NaN};
                boolean[] back = {false};
                STEPS.add(new Step(0, () -> {
                    x0[0] = where(server, at.apply("cart"), null);
                    PlacedPart cp = any(server, at.apply("cart"), null);
                    CompiledAssembly c = compiled(server, at.apply("cart"));
                    var body = cp == null || c == null ? null : c.bodyOf(cp.id);
                    if (body != null) back[0] = x0[0] > 0.5 * (body.strokeLo() + body.strokeHi());
                    EmergenceMod.LOG.info("Propcart: cart at {} m (travel {}…{}), cranking {}", x0[0], body == null ? "?" : body.strokeLo(),
                            body == null ? "?" : body.strokeHi(), back[0] ? "backwards" : "forwards");
                }, null).act(PlayerHand.hold(key + " spin the propeller up (the way the cart can go)", part.apply("crank", PartKind.CRANK),
                        () -> back[0])));
                STEPS.add(new Step(40, NOTHING, null).check(key + " the crank spins the propeller", () ->
                        atLeast("propeller speed [rad/s]", motion(server, at.apply("shaft"), PartKind.SHAFT), 10)));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.release()));
                STEPS.add(new Step(120, view, shot.apply("moving")).check(key + " the propeller's thrust drives the cart along", () ->
                        atLeast("cart's travel [mm]", 1e3 * Math.abs(where(server, at.apply("cart"), null) - x0[0]), 20)));
            }
            if (id.equals("pwm")) {
                // the logger on the bridge's lower left transistor: the drive pulses, the motor running at their average
                logOn(key, part.apply("nA", PartKind.TRANSISTOR), "voltage", 1, server, logger);
                STEPS.add(new Step(160, view, shot.apply("pulses")).show()
                        .check(key + " the motor runs on the pulses' average", () -> atLeast("motor speed [rad/s]", motion(server, at.apply("rotor"), PartKind.SHAFT), 1))
                        .check(key + " the drive is pulsed", () -> atLeast("peak on the logger's screen [V]",
                                SimulationManager.get(server.overworld()).loggerPeak(player(server)), 1)));
            }
            if (id.equals("hbridge")) {
                // FORWARD, then REVERSE instead: the motor turns one way, then the other
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " close FORWARD", part.apply("forward", PartKind.SWITCH))));
                nudge(key, part.apply("rotor", PartKind.SHAFT), false);
                double[] fwd = {Double.NaN};
                STEPS.add(new Step(600, view, null, settledIn(server, at.apply("rotor"), () -> spin(server, at.apply("rotor"), PartKind.SHAFT), 2, key + "fwd")));
                STEPS.add(new Step(0, NOTHING, shot.apply("forward")).check(key + " forward: the motor turns", () ->
                        atLeast("motor speed [rad/s]", Math.abs(spin(server, at.apply("rotor"), PartKind.SHAFT)), 2))
                        .after(() -> fwd[0] = spin(server, at.apply("rotor"), PartKind.SHAFT)));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " open FORWARD", part.apply("forward", PartKind.SWITCH))));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.click(key + " close REVERSE", part.apply("reverse", PartKind.SWITCH))));
                nudge(key, part.apply("rotor", PartKind.SHAFT), true);
                STEPS.add(new Step(600, view, null, () -> onServer(server, "hbrev", () -> spin(server, at.apply("rotor"), PartKind.SHAFT)) * fwd[0] < 0));
                STEPS.add(new Step(600, NOTHING, null, settledIn(server, at.apply("rotor"), () -> spin(server, at.apply("rotor"), PartKind.SHAFT), 2, key + "rev")));
                STEPS.add(new Step(0, view, shot.apply("reverse")).check(key + " reverse: it turns the other way", () -> {
                    double w = spin(server, at.apply("rotor"), PartKind.SHAFT);
                    return that(w * fwd[0] < 0 && Math.abs(w) > 2, String.format("%.2f rad/s forward, %.2f rad/s now, expected opposite", fwd[0], w));
                }));
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
                Sampler sig = new Sampler();
                // the beam's current at every step of the island's solver: the vane cuts it for some 20 ms a pass, which
                // samples a tenth of a second apart can miss every time (the hand turns the crank at a steady 25 rad/s)
                BlockPos pdAt = id.equals("lightbarrier") ? at.apply("pd") : null;
                Sampler beam = pdAt == null ? sig : new TimeAverage(() -> compiled(server, pdAt), () -> {
                    PlacedPart p = any(server, pdAt, PartKind.PHOTODIODE);
                    if (p == null) return null;
                    long pdId = p.id;
                    return c -> {
                        Probe pr = c.probe(pdId);
                        return pr == null ? Double.NaN : Math.abs(pr.current());
                    };
                });
                // (the light barrier's vane must come round past the beam at a hand's pace: a longer watch)
                Step cranked = new Step(id.equals("lightbarrier") ? 100 : id.equals("fan") ? 300 : 40, NOTHING, shot.apply("cranked")).check(key + " the crank turns when held",
                        () -> atLeast("crank speed [rad/s]", motion(server, crank, PartKind.CRANK), 1));
                if (id.equals("gears")) cranked.check(key + " the gears turn the output a fifth as fast, the same way", () -> {
                    double in = spin(server, at.apply("input"), PartKind.SHAFT), out = spin(server, at.apply("output"), PartKind.SHAFT);
                    return that(Math.abs(out / in - 0.2) < 0.02, String.format("output ÷ input = %.3f, expected 0.200 (8:40)", out / in));
                });
                if (id.equals("fan")) cranked.check(key + " the fan's air turns the windmill", () -> {
                    double f = spin(server, at.apply("fanShaft"), PartKind.SHAFT), m = spin(server, at.apply("millShaft"), PartKind.SHAFT);
                    return that(Math.abs(m) > 1 && Math.signum(m) == Math.signum(f), String.format("fan %.0f rad/s, windmill %.1f rad/s, expected turning the same way", f, m));
                });
                if (id.equals("miniature") || id.equals("generator")) cranked.sample(sig, () -> Math.abs(current(server, at.apply("load"), PartKind.RESISTOR)))
                        .check(key + " the generator drives a current through its load", () -> atLeast("peak load current [mA]", 1e3 * sig.max(), 0.1));
                if (id.equals("lightbarrier")) cranked.sample(beam, () -> Math.abs(current(server, at.apply("pd"), PartKind.PHOTODIODE)))
                        .check(key + " the vane cuts the beam: the photodiode's current dips", () -> that(beam.max() > 0 && beam.min() < 0.5 * beam.max(),
                                String.format("photodiode %.3g–%.3g µA, expected to dip as the vane passes", 1e6 * beam.min(), 1e6 * beam.max())));
                STEPS.add(cranked);
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.release()));
            }
            if (id.equals("conduction")) {
                double[] cu0 = {Double.NaN}, ss0 = {Double.NaN};
                STEPS.add(new Step(0, () -> {
                    cu0[0] = temp(server, at.apply("copperEnd"), null);
                    ss0[0] = temp(server, at.apply("steelEnd"), null);
                }, null));
                STEPS.add(new Step(300, NOTHING, shot.apply("later")).check(key + " heat reaches the copper bar's far end far faster than the steel's", () -> {
                    double cu = temp(server, at.apply("copperEnd"), null) - cu0[0], ss = temp(server, at.apply("steelEnd"), null) - ss0[0];
                    return that(cu > 0.05 && cu > 3 * ss, String.format("far ends warmed: copper %.2f K, stainless %.2f K, expected copper several times more", cu, ss));
                }));
            }
            if (id.equals("flywheels")) {
                // both at rest: spin each by hand and let go — the ball bearing's runs on, the bushing's slows
                for (String w : new String[]{"ball", "plain"}) {
                    STEPS.add(new Step(80, NOTHING, null).act(PlayerHand.hold(key + " spin the " + (w.equals("ball") ? "ball-bearing" : "bushing")
                            + " flywheel by hand", part.apply(w + "Wheel", PartKind.BLOCK), false)));
                    STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.release()));
                }
                STEPS.add(new Step(200, view, shot.apply("later")).check(key + " the ball-bearing flywheel keeps turning, the bushing's slows", () -> {
                    double ball = motion(server, at.apply("ball"), null), plain = motion(server, at.apply("plain"), null);
                    return that(ball > 1.5 * plain + 1, String.format("ball bearing %.1f rad/s, bushing %.1f rad/s, expected the ball bearing's well ahead", ball, plain));
                }));
            }
            if (id.equals("daniell")) {
                STEPS.add(new Step(40, NOTHING, null).check(key + " the cell's voltage: copper positive, below its 1.10 V EMF", () -> {
                    double v = volts(server, at.apply("copper"), null) - volts(server, at.apply("zinc"), null);
                    return that(v > 0.4 && v < 1.11, String.format("copper − zinc = %.3f V, expected 0.4–1.11 V", v));
                }).check(key + " it drives a current through its load", () -> atLeast("load current [mA]",
                        1e3 * Math.abs(current(server, at.apply("load"), null)), 5)));
            }
            if (id.equals("plating")) {
                STEPS.add(new Step(200, NOTHING, shot.apply("later")).check(key + " copper plates out on the zinc", () -> atLeast("copper deposited on the zinc [µmol]",
                        1e6 * deposit(server, at.apply("zinc"), null), 0.1)));
            }
            if (id.equals("springmass")) {
                // a push on the weight sets it bouncing (its starting bounce has rung down by the time the tour comes:
                // every machine around a player runs at its full pace now)
                Sampler x = new Sampler();
                STEPS.add(new Step(4, NOTHING, null).act(PlayerHand.hold(key + " push the weight down", part.apply("weight", PartKind.BLOCK), true)));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.release()));
                STEPS.add(new Step(60, NOTHING, shot.apply("bouncing")).sample(x, () -> where(server, at.apply("rod"), PartKind.SHAFT))
                        .check(key + " the weight bounces on its spring", () -> atLeast("weight's swing [mm]", 1e3 * x.range(), 1)));
            }
            if (id.equals("press")) {
                // push the pump's handle down: the small piston's water lifts the big one
                double[] ram0 = {Double.NaN}, pump0 = {Double.NaN};
                STEPS.add(new Step(0, () -> {
                    ram0[0] = where(server, at.apply("ramRod"), PartKind.SHAFT);
                    pump0[0] = where(server, at.apply("pumpRod"), PartKind.SHAFT);
                }, null).act(PlayerHand.hold(key + " push the pump handle down", part.apply("pumpRod", PartKind.SHAFT), true)));
                STEPS.add(new Step(400, NOTHING, shot.apply("pushed"), () -> onServer(server, "pump", () -> pump0[0] - where(server, at.apply("pumpRod"), PartKind.SHAFT)) > 0.0025)
                        .check(key + " the pump piston goes down", () -> atLeast("pump piston's travel down [mm]",
                        1e3 * (pump0[0] - where(server, at.apply("pumpRod"), PartKind.SHAFT)), 2))
                        .check(key + " and the ram rises, a seventeenth as far", () -> atLeast("ram's rise [mm]",
                                1e3 * (where(server, at.apply("ramRod"), PartKind.SHAFT) - ram0[0]), 0.1)));
                STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.release()));
            }
            if (id.equals("solar")) {
                STEPS.add(new Step(40, NOTHING, null).check(key + " in sunshine the cells light the LED", () -> lit(server, at.apply("led"))));
            }
            if (id.equals("prism")) {
                STEPS.add(new Step(20, NOTHING, null).check(key + " the prism bends blue light more than red", () -> {
                    double red = beamTurn(server, at.apply("red")), blue = beamTurn(server, at.apply("blue"));
                    return that(Math.abs(blue) > Math.abs(red) + Math.toRadians(1),
                            String.format("red turned %.1f°, blue %.1f°, expected blue the more", Math.toDegrees(red), Math.toDegrees(blue)));
                }));
            }
            if (id.equals("geiger")) {
                Steps count = new Steps();
                STEPS.add(new Step(1200, NOTHING, shot.apply("counting"), () -> count.changes() >= 3).sample(count, () -> chip(server, at.apply("counter")))
                        .check(key + " the tube's clicks advance the counter", () -> atLeast("counts seen", count.changes(), 3)));
            }
            if (id.equals("thermocouple")) {
                STEPS.add(new Step(40, NOTHING, null).check(key + " the hot junction's EMF, amplified, appears at the output", () -> atLeast("output voltage [V]",
                        volts(server, at.apply("load"), null), 0.01)));
            }
            if (id.equals("equilibrium")) {
                STEPS.add(new Step(40, NOTHING, null).check(key + " the warm flask holds more NO2 (browner) than the cold", () -> {
                    double hot = gasShare(server, at.apply("hot"), "NO2"), cold = gasShare(server, at.apply("cold"), "NO2");
                    return that(hot > cold + 0.1, String.format("NO2 fraction: warm %.2f, cold %.2f, expected the warm well above", hot, cold));
                }));
            }
            if (id.equals("magnetpull")) {
                STEPS.add(new Step(80, NOTHING, shot.apply("later")).check(key + " the magnet holds the block at contact but cannot lift the far one", () -> {
                    double near = where(server, at.apply("near"), null), far = where(server, at.apply("far"), null);
                    return that(near > -0.001 && far < -0.005, String.format("near block moved %.1f mm, far %.1f mm, expected the near held and the far fallen",
                            1e3 * near, 1e3 * far));
                }));
            }
            if (id.equals("fourbar")) {
                STEPS.add(new Step(0, NOTHING, null).check(key + " the flywheel turns the crank right round", () ->
                        atLeast("crank speed [rad/s]", motion(server, at.apply("crankShaft"), PartKind.SHAFT), 1)));
            }
            if (!only.isEmpty()) STEPS.add(new Step(200, view, shot.apply("dwell")));
            // a second on the results before the next exhibit
            STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.pauseFor("Done here: the results", PlayerHand.PAUSE_NS)));
        }
    }

    private static final Runnable NOTHING = () -> { };

    /**
     * A turn of the rotor by hand: a two-segment commutator can come to rest with its brushes in the gap between its
     * segments, where no current flows and the motor cannot start itself — a player gives it a push.
     */
    private static void nudge(String key, PlayerHand.Target rotor, boolean backwards) {
        STEPS.add(new Step(6, NOTHING, null).act(PlayerHand.hold(key + " give the rotor a turn to start it", rotor, backwards)));
        STEPS.add(new Step(0, NOTHING, null).act(PlayerHand.release()));
    }

    /** The memory's four output LEDs as a word, q3 first. */
    private static String bits(net.minecraft.server.MinecraftServer server, java.util.function.Function<String, BlockPos> at) {
        StringBuilder b = new StringBuilder();
        for (int k = 3; k >= 0; k--) b.append(light(server, at.apply("q" + k)) > 1e-5 ? '1' : '0');
        return b.toString();
    }

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
        STEPS.add(new Step(0, NOTHING, null).check(key + " the logger's screen shows " + channel, () -> {
            String[] now = SimulationManager.get(server.overworld()).loggerShowing(player(server));
            return that(now != null && channel.equals(now[0]), "the screen shows " + (now == null ? "nothing" : now[0]));
        }));
    }

    /**
     * A governed motor: its speed held, then the brake applied — it must keep its speed within {@code droop}. Speed and
     * supply current are averaged over five seconds each side: a governor hunts, and a contact governor's current comes
     * in pulses, so one instant's reading says little.
     */
    private static void governed(String key, net.minecraft.server.MinecraftServer server, BlockPos shaftAt, PartKind kind, PlayerHand.Target brake,
                                 Runnable view, java.util.function.Function<String, String> shot, ItemStack inHand, double droop, BlockPos battery,
                                 double warmUp) {
        Sampler w0 = new Sampler(), a0 = new Sampler(), w1 = new Sampler(), a1 = new Sampler(), q0 = new Sampler(), t0 = new Sampler(),
                q1 = new Sampler(), t1 = new Sampler();
        // the window's mean supply current from the charge the battery gave up over it, in the island's own time: the
        // motor's current ripples at its commutation rate, and tick-by-tick samples of it alias
        java.util.function.BiFunction<Sampler, Sampler, Double> amps = (q, t) -> t.change() > 0.5 ? Math.abs(q.change() / t.change()) : Double.NaN;
        // up to speed first: at least warmUp seconds of its own time (a flyball governor's weights take half a minute)
        STEPS.add(new Step(3600, view, null, settledIn(server, shaftAt, () -> motion(server, shaftAt, kind), warmUp, key + "gov")).show(inHand != null));
        STEPS.add(new Step(100, NOTHING, shot.apply("governing")).show(inHand != null)
                .sample(w0, () -> motion(server, shaftAt, kind)).sample(a0, () -> Math.abs(current(server, battery, null)))
                .sample(q0, () -> chargeDelivered(server, battery)).sample(t0, () -> simTime(server, battery))
                .check(key + " it runs at its governed speed", () -> atLeast("mean speed [rad/s]", w0.mean(), 5)));
        STEPS.add(new Step(0, NOTHING, null).act(inHand == null ? PlayerHand.click(key + " apply the brake", brake)
                : PlayerHand.clickWith(key + " apply the brake", brake, inHand)));
        // judged once the speed has settled again, in simulated time
        // the controller takes some ten seconds to win the speed back (a slow one, two fifths of its warm-up: a flyball
        // sags gently, slower than the settling test can tell from a stop): judged once that is over
        STEPS.add(new Step(2400, view, null, settledIn(server, shaftAt, () -> motion(server, shaftAt, kind), Math.max(10, 0.4 * warmUp), key + "brk")).show(inHand != null));
        STEPS.add(new Step(100, NOTHING, shot.apply("braked")).show(inHand != null)
                .after(() -> dumpIf(server, key, shaftAt))
                .sample(w1, () -> motion(server, shaftAt, kind)).sample(a1, () -> Math.abs(current(server, battery, null)))
                .sample(q1, () -> chargeDelivered(server, battery)).sample(t1, () -> simTime(server, battery))
                .check(key + " braked, it keeps its speed", () -> {
                    double was = w0.mean(), now = w1.mean();
                    return that(Math.abs(now - was) <= droop * was, String.format("mean speed %.2f → %.2f rad/s (%+.1f %%), expected within ±%.0f %%",
                            was, now, 100 * (now - was) / was, 100 * droop));
                }).check(key + " by taking more current from its supply", () -> {
                    // (from the charge drawn where the island ran long enough in each window, else the samples' mean)
                    double was = amps.apply(q0, t0), now = amps.apply(q1, t1);
                    if (Double.isNaN(was) || Double.isNaN(now)) {
                        was = a0.mean();
                        now = a1.mean();
                    }
                    return that(now > 1.03 * was + 0.005, String.format("mean supply current %.3f → %.3f A (the brake taking %.2f W), expected to rise",
                            was, now, brakePower(server, brake.block())));
                }));
    }

    private static void look(net.minecraft.server.MinecraftServer server, double x, double y, double z, float yaw, float pitch) {
        if (server.getPlayerList().getPlayers().isEmpty()) return; // the game is closing
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        // the viewpoint is where the eye goes (y is the eye height); the player flies there, in spectator mode so that
        // the eye can be anywhere, a machine's insides included
        var feet = new net.minecraft.world.phys.Vec3(x, y - PlayerHand.EYE_STANDING, z);
        if (p.isSpectator() && p.position().distanceTo(feet) < 0.05 && Math.abs(p.getYRot() - yaw) < 0.5 && Math.abs(p.getXRot() - pitch) < 0.5) return;
        Flight.to(feet, yaw, pitch, false, null);
    }

    private static PlacedPart find(AssemblyBlockEntity be, PartKind k) {
        for (PlacedPart p : be.parts()) if (p.kind == k) return p;
        return null;
    }

    // ---- readings for the checks, taken on the server thread (a test's eyes, not a player's hands)

    /** The first part of that kind in the block (kind null: its first part that is not a wire). */
    private static PlacedPart partIn(net.minecraft.server.MinecraftServer s, BlockPos at, PartKind k) {
        if (k == null) return any(s, at, null);
        return s.overworld().getBlockEntity(at) instanceof AssemblyBlockEntity be ? find(be, k) : null;
    }

    private static CompiledAssembly compiled(net.minecraft.server.MinecraftServer s, BlockPos at) {
        return SimulationManager.get(s.overworld()).compiledAt(at);
    }

    private static boolean has(net.minecraft.server.MinecraftServer s, BlockPos at, PartKind k) {
        return partIn(s, at, k) != null;
    }

    /** Speed of the body carrying the part: |ω| [rad/s], or |v| [m/s] for a slide (NaN if it is not simulated). */
    /** The power a brake pad is absorbing [W] (NaN if there is none at the block). */
    private static double brakePower(net.minecraft.server.MinecraftServer s, BlockPos at) {
        PlacedPart p = any(s, at, PartKind.BRAKE);
        CompiledAssembly c = compiled(s, at);
        double[] b = p == null || c == null ? null : c.brake(p.id);
        return b == null ? Double.NaN : Math.abs(b[1]);
    }

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

    /** The first part of that kind in the block, or (kind null) its first part that is not a wire. */
    private static PlacedPart any(net.minecraft.server.MinecraftServer s, BlockPos at, PartKind k) {
        if (!(s.overworld().getBlockEntity(at) instanceof AssemblyBlockEntity be)) return null;
        for (PlacedPart p : be.parts()) if (k == null ? p.kind != PartKind.WIRE && p.kind != PartKind.INSULATED_WIRE : p.kind == k) return p;
        return null;
    }

    /** Signed angular velocity [rad/s] (velocity [m/s] for a slide) of the body carrying the part. */
    private static double spin(net.minecraft.server.MinecraftServer s, BlockPos at, PartKind k) {
        PlacedPart p = any(s, at, k);
        CompiledAssembly c = compiled(s, at);
        if (p == null || c == null) return Double.NaN;
        var b = c.bodyOf(p.id);
        return b == null || b.isStatic() ? 0 : c.angularVelocity(b);
    }

    /** Angle [rad] or displacement [m] of the body carrying the part (kind null: its first part). */
    private static double where(net.minecraft.server.MinecraftServer s, BlockPos at, PartKind k) {
        PlacedPart p = any(s, at, k);
        CompiledAssembly c = compiled(s, at);
        if (p == null || c == null) return Double.NaN;
        var b = c.bodyOf(p.id);
        return b == null || b.isStatic() ? 0 : c.angle(b);
    }

    /** A slide's state for diagnosis: position, velocity and travel stops [m, m/s]. */
    private static String slideInfo(net.minecraft.server.MinecraftServer s, BlockPos at) {
        PlacedPart p = any(s, at, PartKind.SHAFT);
        CompiledAssembly c = compiled(s, at);
        var b = p == null || c == null ? null : c.bodyOf(p.id);
        if (b == null) return "none";
        return String.format("at %.5f m moving %.5f m/s, stops %.5f…%.5f, %.3f kg%s", c.angle(b), c.angularVelocity(b), b.strokeLo(), b.strokeHi(), b.mass(),
                b.isStatic() ? " (static!)" : "");
    }

    /** Pressure of the fluid in a vessel or pipe [Pa]. */
    private static double pressure(net.minecraft.server.MinecraftServer s, BlockPos at, PartKind k) {
        PlacedPart p = any(s, at, k);
        CompiledAssembly c = compiled(s, at);
        double[] f = p == null || c == null ? null : c.fluid(p.id);
        return f == null ? Double.NaN : f[0];
    }

    private static double current(net.minecraft.server.MinecraftServer s, BlockPos at, PartKind k) {
        PlacedPart p = any(s, at, k);
        CompiledAssembly c = compiled(s, at);
        Probe pr = p == null || c == null ? null : c.probe(p.id);
        return pr == null ? Double.NaN : pr.current();
    }

    /** The charge the battery in block {@code at} has delivered since its island started [C]. */
    private static double chargeDelivered(net.minecraft.server.MinecraftServer s, BlockPos at) {
        PlacedPart p = any(s, at, PartKind.BATTERY);
        CompiledAssembly c = compiled(s, at);
        return p == null || c == null ? Double.NaN : c.batteryChargeDelivered(p.id);
    }

    private static double power(net.minecraft.server.MinecraftServer s, BlockPos at, PartKind k) {
        PlacedPart p = any(s, at, k);
        CompiledAssembly c = compiled(s, at);
        Probe pr = p == null || c == null ? null : c.probe(p.id);
        return pr == null ? Double.NaN : Math.abs(pr.power());
    }

    private static double volts(net.minecraft.server.MinecraftServer s, BlockPos at, PartKind k) {
        PlacedPart p = any(s, at, k);
        CompiledAssembly c = compiled(s, at);
        Probe pr = p == null || c == null ? null : c.probe(p.id);
        return pr == null ? Double.NaN : pr.voltage();
    }

    private static double temp(net.minecraft.server.MinecraftServer s, BlockPos at, PartKind k) {
        PlacedPart p = any(s, at, k);
        CompiledAssembly c = compiled(s, at);
        Probe pr = p == null || c == null ? null : c.probe(p.id);
        return pr == null ? Double.NaN : pr.temperature();
    }

    /** Mole fraction of the gas mixture's species {@code i} in a vessel. */
    private static double gasFraction(net.minecraft.server.MinecraftServer s, BlockPos at, int i) {
        PlacedPart p = any(s, at, PartKind.TANK);
        CompiledAssembly c = compiled(s, at);
        var g = p == null || c == null ? null : c.gas(p.id);
        return g == null ? Double.NaN : g.moleFraction(c.solverView(), i);
    }

    /** The island's simulated time [s] (NaN if not simulated). */
    private static double simTime(net.minecraft.server.MinecraftServer s, BlockPos at) {
        CompiledAssembly c = compiled(s, at);
        return c == null ? Double.NaN : c.solverView().time();
    }

    /** Mole fraction of the named species in a vessel's gas. */
    private static double gasShare(net.minecraft.server.MinecraftServer s, BlockPos at, String species) {
        PlacedPart p = any(s, at, PartKind.TANK);
        CompiledAssembly c = compiled(s, at);
        var g = p == null || c == null ? null : c.gas(p.id);
        if (g == null) return Double.NaN;
        var view = c.solverView();
        double total = 0, mine = 0;
        for (var sp : g.species()) {
            double n = g.moles(view, sp);
            total += n;
            if (sp.formula().equals(species) || sp.id().equals(species)) mine = n;
        }
        return total > 0 ? mine / total : Double.NaN;
    }

    /**
     * True once a quantity has settled in simulated time: at least {@code after} seconds of the island's own time
     * since the step began, and its means over the last three 1.5 s windows of simulated time all within 0.5 % of one
     * another. (Means, since a commutator motor's speed ripples and a governor hunts; three windows, since two agree at
     * the bottom of a dip as it turns — a governor recovering from a load was judged mid-recovery; simulated time,
     * since a costly machine runs slower than real time: judged by the clock on the wall it would look steady while
     * it is still moving.)
     */
    private static java.util.function.BooleanSupplier settledIn(net.minecraft.server.MinecraftServer server, BlockPos at,
                                                                java.util.function.DoubleSupplier read, double after, String key) {
        final double w = 1.5;
        java.util.ArrayDeque<double[]> hist = new java.util.ArrayDeque<>();
        double[] t0 = {Double.NaN};
        int[] tick = {0};
        return () -> {
            if (tick[0]++ % 3 != 0) return false;
            double t = onServer(server, key + "t", () -> simTime(server, at)), v = onServer(server, key + "v", read::getAsDouble);
            if (Double.isNaN(t) || Double.isNaN(v)) return false;
            if (Double.isNaN(t0[0]) || t < t0[0]) { // (the island recompiled: its clock restarted)
                t0[0] = t;
                hist.clear();
            }
            hist.addLast(new double[]{t, v});
            while (hist.size() > 2 && hist.peekFirst()[0] < t - 3 * w) hist.removeFirst();
            if (t - t0[0] < after || t - hist.peekFirst()[0] < 2.9 * w) return false;
            double[] s = new double[3];
            int[] n = new int[3];
            for (double[] h : hist) {
                int k = Math.min(2, (int) ((t - h[0]) / w)); // 0: the latest window
                s[k] += h[1];
                n[k]++;
            }
            if (n[0] < 3 || n[1] < 3 || n[2] < 3) return false;
            double m0 = s[0] / n[0], m1 = s[1] / n[1], m2 = s[2] / n[2], tol = 0.005 * Math.abs(m0);
            return Math.abs(m0 - m1) < tol && Math.abs(m1 - m2) < tol && Math.abs(m0 - m2) < tol;
        };
    }

    /** Foreign metal deposited on an electrode [mol]. */
    private static double deposit(net.minecraft.server.MinecraftServer s, BlockPos at, PartKind k) {
        PlacedPart p = any(s, at, k);
        CompiledAssembly c = compiled(s, at);
        return p == null || c == null ? Double.NaN : c.depositMoles(p.id);
    }

    /** A chip's state (its register or counter value first). */
    private static double chip(net.minecraft.server.MinecraftServer s, BlockPos at) {
        PlacedPart p = any(s, at, PartKind.CHIP);
        CompiledAssembly c = compiled(s, at);
        double[] st = p == null || c == null ? null : c.chipState(p.id);
        return st == null || st.length == 0 ? Double.NaN : st[0];
    }

    /** How far the last leg of an LED's traced beam is turned from east, in its horizontal plane [rad]. */
    private static double beamTurn(net.minecraft.server.MinecraftServer s, BlockPos at) {
        PlacedPart p = any(s, at, PartKind.LED);
        CompiledAssembly c = compiled(s, at);
        var rays = p == null || c == null ? null : c.beams(p.id);
        if (rays == null || rays.isEmpty() || rays.get(0).size() < 2) return Double.NaN;
        var chief = rays.get(0);
        var d = chief.get(chief.size() - 1).sub(chief.get(chief.size() - 2));
        return Math.atan2(d.z(), d.x());
    }

    private static double light(net.minecraft.server.MinecraftServer s, BlockPos at) {
        PlacedPart p = partIn(s, at, PartKind.LED);
        CompiledAssembly c = compiled(s, at);
        return p == null || c == null ? Double.NaN : c.lightOutput(p.id);
    }

    /**
     * With -Demergence.dumpIslands=flyball,engine,…: the island at {@code at} written out (island-dump-*.txt in the game
     * directory) when the exhibit's tour reaches this point, to replay it in the core (IslandDumpReplayTest).
     */
    private static void dumpIf(net.minecraft.server.MinecraftServer server, String key, BlockPos at) {
        if (java.util.Arrays.asList(System.getProperty("emergence.dumpIslands", "").split(",")).contains(key.replaceAll(":.*", "").trim()))
            server.execute(() -> dev.emergence.mod.sim.SimulationManager.get(server.overworld()).dumpIsland(at));
    }

    /** The CO₂ mole fraction in the gas vessel at {@code at} (NaN if none). */
    private static double co2(net.minecraft.server.MinecraftServer s, BlockPos at) {
        PlacedPart p = partIn(s, at, PartKind.TANK);
        CompiledAssembly c = compiled(s, at);
        var g = p == null || c == null ? null : c.gas(p.id);
        if (g == null) return Double.NaN;
        int k = g.indexOf(dev.emergence.core.chem.Chemistry.standard().species("CO2"));
        return k < 0 ? 0 : g.moleFraction(c.solverView(), k);
    }

    /** A shaft's |ω| averaged over its island's own time (see {@link TimeAverage}). */
    private static Sampler shaftSpeed(net.minecraft.server.MinecraftServer server, BlockPos at) {
        return new TimeAverage(() -> compiled(server, at), () -> {
            PlacedPart p = partIn(server, at, PartKind.SHAFT);
            if (p == null) return null;
            long id = p.id;
            return c -> {
                Probe pr = c.probe(id);
                return pr == null ? Double.NaN : Math.abs(pr.angularVelocity());
            };
        });
    }

    /** A quantity recorded through a step (every other tick, on the server): its least, greatest and mean values. */
    static class Sampler {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY, sum, first = Double.NaN, last = Double.NaN;
        int n;

        synchronized void add(double v) {
            if (Double.isNaN(v)) return;
            if (n == 0) first = v;
            last = v;
            min = Math.min(min, v);
            max = Math.max(max, v);
            sum += v;
            n++;
        }

        synchronized double min() {
            return n == 0 ? Double.NaN : min;
        }

        synchronized double max() {
            return n == 0 ? Double.NaN : max;
        }

        synchronized double mean() {
            return n == 0 ? Double.NaN : sum / n;
        }

        double range() {
            return max() - min();
        }

        /** The change from the first sample to the last. */
        synchronized double change() {
            return last - first;
        }
    }

    /**
     * The time average of a quantity over the island's own simulated time, integrated (trapezoidally) at every step the
     * solver accepts — as a wattmeter or an integrating ammeter reads — where tick-by-tick samples would alias a quantity
     * that pulses (a generator's output as each magnet passes its coils). It attaches to the island on its first
     * sample and lets go when read; the samples' own mean stands in if the island ran less than a millisecond meanwhile.
     */
    static final class TimeAverage extends Sampler {
        private final java.util.function.Supplier<CompiledAssembly> island;
        /** Made on the server thread when it attaches (it may look up parts); what it makes reads only the island. */
        private final java.util.function.Supplier<java.util.function.ToDoubleFunction<CompiledAssembly>> quantity;
        private dev.emergence.core.sim.TransientSolver solver;
        private java.util.function.Consumer<dev.emergence.core.sim.TransientSolver> listener;
        private double integral, span, lastT = Double.NaN, lastV;
        /** The least and greatest values at any of the island's steps (a dip lasting milliseconds falls between ticks). */
        private double stepMin = Double.POSITIVE_INFINITY, stepMax = Double.NEGATIVE_INFINITY;

        TimeAverage(java.util.function.Supplier<CompiledAssembly> island,
                    java.util.function.Supplier<java.util.function.ToDoubleFunction<CompiledAssembly>> quantity) {
            this.island = island;
            this.quantity = quantity;
        }

        @Override
        synchronized void add(double v) {
            super.add(v);
            if (solver != null) return;
            CompiledAssembly c = island.get();
            java.util.function.ToDoubleFunction<CompiledAssembly> q = c == null ? null : quantity.get();
            if (q == null) return;
            solver = c.solver();
            listener = s -> {
                double x;
                try {
                    x = q.applyAsDouble(c);
                } catch (RuntimeException e) {
                    return;
                }
                if (!Double.isFinite(x)) return;
                synchronized (this) {
                    stepMin = Math.min(stepMin, x);
                    stepMax = Math.max(stepMax, x);
                    double t = s.time();
                    if (Double.isFinite(lastT) && t > lastT) {
                        integral += 0.5 * (x + lastV) * (t - lastT);
                        span += t - lastT;
                    }
                    lastT = t;
                    lastV = x;
                }
            };
            solver.addStepListener(listener);
        }

        @Override
        synchronized double mean() {
            detach();
            return span > 1e-3 ? integral / span : super.mean();
        }

        @Override
        synchronized double min() {
            detach();
            return Math.min(super.min(), stepMin);
        }

        @Override
        synchronized double max() {
            detach();
            return Math.max(super.max(), stepMax);
        }

        private void detach() {
            if (solver != null) {
                solver.removeStepListener(listener);
                solver = null;
            }
        }
    }

    /** A quantity's swing (greatest less least) over a sliding window of samples: the first window's, the largest, the latest. */
    static final class Swing extends Sampler {
        private final double[] ring;
        private int k, seen;
        private double first = Double.NaN, peak = Double.NaN, last = Double.NaN, least = Double.NaN;

        Swing(int window) {
            ring = new double[window];
        }

        @Override
        synchronized void add(double v) {
            if (Double.isNaN(v)) return;
            super.add(v);
            ring[k] = v;
            k = (k + 1) % ring.length;
            if (++seen < ring.length) return;
            double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
            for (double x : ring) {
                lo = Math.min(lo, x);
                hi = Math.max(hi, x);
            }
            last = hi - lo;
            if (Double.isNaN(first)) first = last;
            if (Double.isNaN(peak) || last > peak) peak = last;
            if (Double.isNaN(least) || last < least) least = last;
        }

        synchronized double least() {
            return least;
        }

        synchronized double first() {
            return first;
        }

        synchronized double peak() {
            return peak;
        }

        synchronized double last() {
            return last;
        }
    }

    /** How many times a quantity has changed value (a counter's steps, wrapping or not). */
    static final class Steps extends Sampler {
        private double prev = Double.NaN;
        private int changes;

        @Override
        synchronized void add(double v) {
            if (Double.isNaN(v)) return;
            super.add(v);
            if (!Double.isNaN(prev) && v != prev) changes++;
            prev = v;
        }

        synchronized int changes() {
            return changes;
        }
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
        // the server's reading, as the check takes it (the client's synced gauge can be a moment stale: a step ended on
        // it while the server still had the LED dark)
        var s = Minecraft.getInstance().getSingleplayerServer();
        return s != null && onServer(s, "lit" + at, () -> light(s, at)) > 1e-5;
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

    /**
     * Frames for animated GIFs (the README's): {@code emergence.gifs=a,b,...} — while the tour plays those exhibits
     * (as it always does: their hands, their checks), a frame every {@code emergence.gifEvery} ticks (2: 10 a second),
     * at most {@code emergence.gifMax} each, into screenshots/gif/&lt;id&gt;/; tools/make_gifs.py makes the GIFs. Not
     * while the camera flies between viewpoints.
     */
    private static final java.util.Set<String> GIFS = new java.util.HashSet<>(java.util.Arrays.asList(System.getProperty("emergence.gifs", "").split(",")));
    private static final int GIF_EVERY = Integer.getInteger("emergence.gifEvery", 2), GIF_MAX = Integer.getInteger("emergence.gifMax", 600);
    private static String gifExhibit;
    private static int gifFrame;

    private static void gifFrame(Minecraft mc) {
        if (gifExhibit == null || !GIFS.contains(gifExhibit) || Flight.busy() || gifFrame >= GIF_MAX || joinedTicks % GIF_EVERY != 0) return;
        new java.io.File(mc.gameDirectory, "screenshots/gif/" + gifExhibit).mkdirs();
        Screenshot.grab(mc.gameDirectory, String.format("gif/%s/f%04d.png", gifExhibit, gifFrame++), mc.getMainRenderTarget(), msg -> {});
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
        Flight.tick(mc);
        if (index >= STEPS.size()) {
            if (wait == 1) report(mc);
            if (wait-- == 0) mc.stop();
            if (wait < -5) wait = 40;
            return;
        }
        Step s = STEPS.get(index);
        gifFrame(mc);
        if (wait >= 0 && ran) live(mc, s); // what the step's checks measure now, for the caption
        if (wait >= 0 && ran && actDone && !s.samplers.isEmpty() && joinedTicks % 2 == 0) {
            List<Sampler> ss = s.samplers;
            List<java.util.function.DoubleSupplier> rr = s.sampled;
            mc.getSingleplayerServer().execute(() -> {
                for (int k = 0; k < ss.size(); k++) {
                    try {
                        ss.get(k).add(rr.get(k).getAsDouble());
                    } catch (RuntimeException ignored) {
                        // not readable this tick (an island being rebuilt)
                    }
                }
            });
        }
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
            if (s.title != null) {
                lap(s.exhibit);
                gifExhibit = s.exhibit;
                gifFrame = 0;
                ShowcaseHud.exhibit(s.number, s.total, s.title, s.description);
            }
            // (an exhibit's opening step only lets go of what the last one held: it stays "looking at the exhibit")
            if (s.act != null && s.title == null) ShowcaseHud.doing(caption(s.act.what()));
            else if (s.delayTicks > 0 && s.title == null) ShowcaseHud.doing("Watching");
            ShowcaseHud.pending(s.checkNames.stream().map(nm -> new String[]{caption(nm), "measuring…"}).toList());
        } else if (!ran) {
            // a busy server runs the step's action some ticks late: its wait and condition start from the action
        } else if (Flight.busy()) {
            // on the way to the step's viewpoint (or a hand's spot): its wait and condition start on landing
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

    /** Time spent on each exhibit [s], in tour order. */
    private static final java.util.LinkedHashMap<String, Double> TIMES = new java.util.LinkedHashMap<>();
    private static String timing;
    private static long timingSince;

    /** Closes the time of the exhibit being shown and starts {@code next}'s (null: the tour is over). */
    private static void lap(String next) {
        long now = System.nanoTime();
        if (timing != null) {
            double sec = (now - timingSince) * 1e-9;
            TIMES.put(timing, sec);
            EmergenceMod.LOG.info("Showcase time: {} took {} s", timing, String.format("%.0f", sec));
        }
        timing = next;
        timingSince = now;
    }

    /** Writes the checks and the hands' failures to screenshots/showcase-report.txt and the log. */
    private static void report(Minecraft mc) {
        lap(null);
        List<String> lines = new ArrayList<>(REPORT);
        for (String f : PlayerHand.FAILURES) lines.add("FAIL (hands) " + f);
        long failed = lines.stream().filter(l -> l.startsWith("FAIL")).count();
        double total = TIMES.values().stream().mapToDouble(Double::doubleValue).sum();
        String summary = String.format("Showcase checks: %d passed, %d failed; %.0f min over %d exhibits", lines.size() - failed, failed,
                total / 60, TIMES.size());
        lines.add(0, summary);
        lines.add("");
        lines.add("Time per exhibit (slowest first):");
        TIMES.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .forEach(en -> lines.add(String.format("  %-14s %5.0f s", en.getKey(), en.getValue())));
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
