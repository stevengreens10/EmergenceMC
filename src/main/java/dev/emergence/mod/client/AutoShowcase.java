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
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.List;

/**
 * Automated visual verification of the demo world (enabled with {@code -Demergence.autoShowcase=true},
 * see the {@code showcaseClient} run). After joining it flies the player to each exhibit, triggers the
 * capacitor discharge, saves screenshots to {@code screenshots/showcase-*.png} and quits. It sends no
 * OS-level input, so it is safe to run while someone is using the computer.
 */
public final class AutoShowcase {
    private AutoShowcase() {}

    /** A server action, then a screenshot after {@code delayTicks} or as soon as {@code ready} holds. */
    private record Step(int delayTicks, Runnable server, String shot, java.util.function.BooleanSupplier ready) {
        Step(int delayTicks, Runnable server, String shot) {
            this(delayTicks, server, shot, null);
        }
    }

    private static final List<Step> STEPS = new ArrayList<>();
    private static int index, wait = -1, joinedTicks;

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
        for (int i = 0; i < n; i++) {
            BlockPos plot = origin.offset(DemoBuilder.plotOffset(ex, i), 0, 0);
            var e = ex.get(i);
            String id = e.id;
            if (!only.isEmpty() && !only.contains(id)) continue;
            Runnable view = () -> look(server, plot.getX() + e.viewX, plot.getY() + e.viewHeight, plot.getZ() + e.viewZ,
                    (float) e.viewYaw, (float) e.viewPitch);
            STEPS.add(new Step(50, () -> {
                LoggerHud.showcase = false;
                view.run();
            }, "showcase-" + (i + 1) + "-" + id));
            if (id.equals("glow") || id.equals("fuse")) {
                BlockPos wire = DemoBuilder.partBlock(plot, e, "wire");
                STEPS.add(new Step(40, () -> toggle(server.overworld(), DemoBuilder.partBlock(plot, e, "switch")),
                        "showcase-" + (i + 1) + "-" + id + "-discharged", () -> hottest(wire) > 950 || gone(wire)));
            }
            if (id.equals("dcmotor") || id.equals("corrosion")) {
                STEPS.add(new Step(id.equals("corrosion") ? 60 : 30, () -> toggle(server.overworld(), DemoBuilder.partBlock(plot, e, "switch")),
                        "showcase-" + (i + 1) + "-" + id + "-on"));
            }
            if (id.equals("turbine")) {
                STEPS.add(new Step(16, () -> toggle(server.overworld(), DemoBuilder.partBlock(plot, e, "valve")),
                        "showcase-" + (i + 1) + "-" + id + "-on"));
            }
            if (id.equals("transistor")) {
                STEPS.add(new Step(20, () -> toggle(server.overworld(), DemoBuilder.partBlock(plot, e, "switch")),
                        "showcase-" + (i + 1) + "-" + id + "-on"));
            }
            if (id.equals("nand")) {
                STEPS.add(new Step(20, () -> toggle(server.overworld(), DemoBuilder.partBlock(plot, e, "a")),
                        "showcase-" + (i + 1) + "-" + id + "-a"));
                STEPS.add(new Step(20, () -> toggle(server.overworld(), DemoBuilder.partBlock(plot, e, "b")),
                        "showcase-" + (i + 1) + "-" + id + "-ab"));
            }
            if (id.equals("thermostat")) {
                STEPS.add(new Step(60, () -> { }, "showcase-" + (i + 1) + "-" + id + "-later"));
            }
            if (id.equals("counter")) {
                STEPS.add(new Step(110, () -> { }, "showcase-" + (i + 1) + "-" + id + "-later"));
            }
            if (id.equals("stirling")) {
                STEPS.add(new Step(100, () -> { }, "showcase-" + (i + 1) + "-" + id + "-running"));
            }
            if (id.equals("shelves")) {
                // a second lead block on the steel and the glass shelf: past yield / fracture they break
                STEPS.add(new Step(60, () -> {
                    DemoBuilder.addPart(server.overworld(), plot, 5, 5, 5, dev.emergence.core.assembly.PartKind.BLOCK, "lead",
                            dev.emergence.core.assembly.Dir.UP);
                    DemoBuilder.addPart(server.overworld(), plot, 5, 5, 11, dev.emergence.core.assembly.PartKind.BLOCK, "lead",
                            dev.emergence.core.assembly.Dir.UP);
                }, "showcase-" + (i + 1) + "-" + id + "-overloaded"));
            }
            if (id.equals("buckling")) {
                // the short strip's load on the tall one: it buckles within a simulated second
                STEPS.add(new Step(60, () -> DemoBuilder.addPart(server.overworld(), plot, 10, 9, 8, dev.emergence.core.assembly.PartKind.BLOCK,
                        "lead", dev.emergence.core.assembly.Dir.UP), "showcase-" + (i + 1) + "-" + id + "-loaded"));
            }
            if (id.equals("computer")) {
                STEPS.add(new Step(200, () -> { }, "showcase-" + (i + 1) + "-" + id + "-running"));
                STEPS.add(new Step(200, () -> toggle(server.overworld(), DemoBuilder.partBlock(plot, e, "sense")),
                        "showcase-" + (i + 1) + "-" + id + "-reverse"));
            }
            if (id.equals("steam")) {
                STEPS.add(new Step(80, () -> { }, "showcase-" + (i + 1) + "-" + id + "-running"));
                STEPS.add(new Step(80, () -> toggle(server.overworld(), DemoBuilder.partBlock(plot, e, "regulator")),
                        "showcase-" + (i + 1) + "-" + id + "-shut"));
            }
            if (id.equals("servo")) {
                // turn the knob down to its stop: the arm rises against gravity to just above horizontal
                STEPS.add(new Step(220, () -> crank(server.overworld(), DemoBuilder.partBlock(plot, e, "crank"), -1),
                        "showcase-" + (i + 1) + "-" + id + "-raised"));
            }
            if (id.equals("explosion")) {
                // close the heater switch with the data logger on the can's gas: shoot when the gas runs away
                STEPS.add(new Step(4000, () -> {
                    ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                    p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                            new net.minecraft.world.item.ItemStack(dev.emergence.mod.registry.ModRegistries.DATA_LOGGER.get()));
                    BlockPos at = DemoBuilder.partBlock(plot, e, "can");
                    if (server.overworld().getBlockEntity(at) instanceof AssemblyBlockEntity be) {
                        PlacedPart can = find(be, PartKind.TANK);
                        if (can != null) SimulationManager.get(server.overworld()).attachLogger(p, at, can.id, "Can (methane-air)");
                    }
                    SimulationManager.get(server.overworld()).showLogger(p, "fluid temperature", 2);
                    toggle(server.overworld(), DemoBuilder.partBlock(plot, e, "switch"));
                    LoggerHud.showcase = true;
                }, "showcase-" + (i + 1) + "-" + id + "-ignited", () -> {
                    var players = server.getPlayerList().getPlayers();
                    if (players.isEmpty()) return false;
                    double t = SimulationManager.get(server.overworld()).loggerLatest(players.get(0));
                    return t > 1500;
                }));
            }
            if (id.equals("burner")) {
                // igniter on; two minutes later the fuel; a minute later the burning chamber on the logger
                BlockPos chamberAt = DemoBuilder.partBlock(plot, e, "chamber");
                STEPS.add(new Step(9000, () -> toggle(server.overworld(), DemoBuilder.partBlock(plot, e, "switch")),
                        "showcase-" + (i + 1) + "-" + id + "-glowing", () -> chamber(server, chamberAt, false) > 1000));
                STEPS.add(new Step(6000, () -> {
                    ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                    p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                            new net.minecraft.world.item.ItemStack(dev.emergence.mod.registry.ModRegistries.DATA_LOGGER.get()));
                    BlockPos at = DemoBuilder.partBlock(plot, e, "chamber");
                    if (server.overworld().getBlockEntity(at) instanceof AssemblyBlockEntity be) {
                        PlacedPart ch = find(be, PartKind.TANK);
                        if (ch != null) SimulationManager.get(server.overworld()).attachLogger(p, at, ch.id, "Combustion chamber");
                    }
                    SimulationManager.get(server.overworld()).showLogger(p, "fluid temperature", 3);
                    toggle(server.overworld(), DemoBuilder.partBlock(plot, e, "valve"));
                    LoggerHud.showcase = true;
                }, "showcase-" + (i + 1) + "-" + id + "-burning", () -> chamber(server, chamberAt, true) > 1300));
            }
            if (id.equals("harvester")) {
                // a push sets the magnet bouncing between the coils; the switch connects the load
                BlockPos magnetAt = DemoBuilder.partBlock(plot, e, "magnet");
                STEPS.add(new Step(3, () -> push(server.overworld(), magnetAt, 1), "showcase-" + (i + 1) + "-" + id + "-push"));
                STEPS.add(new Step(30, () -> {
                    push(server.overworld(), magnetAt, 0);
                    toggle(server.overworld(), DemoBuilder.partBlock(plot, e, "switch"));
                }, "showcase-" + (i + 1) + "-" + id + "-bouncing"));
            }
            if (id.equals("jack")) {
                // four strokes of the pump handle (no shots in between), then the raised ram
                BlockPos rodAt = DemoBuilder.partBlock(plot, e, "pumpRod");
                for (int k = 0; k < 4; k++) {
                    STEPS.add(new Step(15, () -> pushPart(server.overworld(), rodAt, PartKind.SHAFT, -1), null));
                    STEPS.add(new Step(15, () -> pushPart(server.overworld(), rodAt, PartKind.SHAFT, 1), null));
                }
                STEPS.add(new Step(10, () -> pushPart(server.overworld(), rodAt, PartKind.SHAFT, 0),
                        "showcase-" + (i + 1) + "-" + id + "-pumped"));
            }
            if (id.equals("burst")) {
                // switch the heaters on: the copper box bursts within a minute of simulated time (longer in the tour,
                // where the whole gallery in view shares the time budget)
                STEPS.add(new Step(6000, () -> toggle(server.overworld(), DemoBuilder.partBlock(plot, e, "switch")),
                        "showcase-" + (i + 1) + "-" + id + "-burst", () -> gone(DemoBuilder.partBlock(plot, e, "thin"), PartKind.TANK)));
            }
            if (id.equals("faraday")) {
                // make, then break: one LED flashes as the flux grows, the other as it collapses
                BlockPos sw = DemoBuilder.partBlock(plot, e, "switch");
                STEPS.add(new Step(2, () -> toggle(server.overworld(), sw), "showcase-" + (i + 1) + "-" + id + "-make"));
                STEPS.add(new Step(40, () -> { }, null));
                STEPS.add(new Step(2, () -> toggle(server.overworld(), sw), "showcase-" + (i + 1) + "-" + id + "-break"));
            }
            if (id.equals("periscope") || id.equals("lens")) {
                // the green LED is lit by the light the mirrors (or the lens) bring; take one away and it goes out
                BlockPos part = DemoBuilder.partBlock(plot, e, id.equals("lens") ? "lens" : "mirrorB");
                PartKind kind = id.equals("lens") ? PartKind.LENS : PartKind.MIRROR;
                STEPS.add(new Step(40, () -> { }, "showcase-" + (i + 1) + "-" + id + "-lit"));
                STEPS.add(new Step(400, () -> server.overworld().removeBlock(part, false), null, () -> gone(part, kind)));
                STEPS.add(new Step(60, () -> { }, "showcase-" + (i + 1) + "-" + id + "-dark"));
            }
            if (id.equals("relay")) {
                // close the coil's switch: the armature snaps up and the contacts light the LED
                BlockPos sw = DemoBuilder.partBlock(plot, e, "switch");
                STEPS.add(new Step(60, () -> toggle(server.overworld(), sw), "showcase-" + (i + 1) + "-" + id + "-energised"));
            }
            if (id.equals("still")) {
                // heater on: the warm pot boils and distillate gathers in the receiver
                BlockPos sw = DemoBuilder.partBlock(plot, e, "switch");
                STEPS.add(new Step(1200, () -> toggle(server.overworld(), sw), "showcase-" + (i + 1) + "-" + id + "-boiling"));
            }
            if (id.equals("loudspeaker")) {
                // switch the loudspeaker on; the simulation resolves every vibration, so give the microphone time
                BlockPos sw = DemoBuilder.partBlock(plot, e, "switch"), led = DemoBuilder.partBlock(plot, e, "lit");
                STEPS.add(new Step(3000, () -> toggle(server.overworld(), sw), "showcase-" + (i + 1) + "-" + id + "-heard",
                        () -> ledLit(led)));
            }
            if (id.equals("spectrometer")) {
                // red heat, orange, white: the infrared channel lights first, then the red, then the blue
                for (String sw : new String[]{"switch", "warmer", "hotter"}) {
                    BlockPos at = DemoBuilder.partBlock(plot, e, sw);
                    STEPS.add(new Step(60, () -> toggle(server.overworld(), at), "showcase-" + (i + 1) + "-" + id + "-" + sw));
                }
            }
            if (id.equals("memory")) {
                // write 1010 into word 1, look at word 0 (empty), then back at word 1
                java.util.function.Function<String, BlockPos> at = name -> DemoBuilder.partBlock(plot, e, name);
                for (String sw : new String[]{"a0", "d1", "d3"}) STEPS.add(new Step(10, () -> toggle(server.overworld(), at.apply(sw)), null));
                STEPS.add(new Step(20, () -> toggle(server.overworld(), at.apply("write")), null));
                STEPS.add(new Step(20, () -> toggle(server.overworld(), at.apply("write")), "showcase-" + (i + 1) + "-" + id + "-written"));
                STEPS.add(new Step(30, () -> toggle(server.overworld(), at.apply("a0")), "showcase-" + (i + 1) + "-" + id + "-word0"));
                STEPS.add(new Step(30, () -> toggle(server.overworld(), at.apply("a0")), "showcase-" + (i + 1) + "-" + id + "-word1"));
            }
            if (id.equals("filters")) {
                // red-hot: only the red side lights; white-hot: both
                BlockPos sw = DemoBuilder.partBlock(plot, e, "switch"), hot = DemoBuilder.partBlock(plot, e, "hotter");
                BlockPos red = DemoBuilder.partBlock(plot, e, "litRed"), blue = DemoBuilder.partBlock(plot, e, "litBlue");
                STEPS.add(new Step(400, () -> toggle(server.overworld(), sw), "showcase-" + (i + 1) + "-" + id + "-redhot", () -> ledLit(red)));
                STEPS.add(new Step(400, () -> toggle(server.overworld(), hot), "showcase-" + (i + 1) + "-" + id + "-whitehot", () -> ledLit(blue)));
            }
            if (id.equals("optocoupler")) {
                // close the input's switch: the invisible infrared crosses to the phototransistor; the green LED lights
                BlockPos sw = DemoBuilder.partBlock(plot, e, "switch"), led = DemoBuilder.partBlock(plot, e, "green");
                STEPS.add(new Step(200, () -> toggle(server.overworld(), sw), "showcase-" + (i + 1) + "-" + id + "-on", () -> ledLit(led)));
            }
            if (id.equals("windturbine")) {
                // the wind (from the west, by the weather) turns the rotor up to speed
                BlockPos shaftAt = DemoBuilder.partBlock(plot, e, "shaft");
                STEPS.add(new Step(2400, () -> { }, "showcase-" + (i + 1) + "-" + id + "-turning",
                        () -> Math.abs(wheelSpeed(server, shaftAt)) > 30));
            }
            if (id.equals("weights")) {
                // a hand on the crank: the beam spins up and the weights slide out against their springs
                BlockPos crankAt = DemoBuilder.partBlock(plot, e, "crank"), shaftAt = DemoBuilder.partBlock(plot, e, "shaft");
                STEPS.add(new Step(2, () -> { }, "showcase-" + (i + 1) + "-" + id + "-rest"));
                STEPS.add(new Step(1200, () -> crank(server.overworld(), crankAt), "showcase-" + (i + 1) + "-" + id + "-spinning",
                        () -> Math.abs(wheelSpeed(server, shaftAt)) > 13));
                STEPS.add(new Step(1, () -> crank(server.overworld(), crankAt, 0), null));
            }
            if (id.equals("unbalance")) {
                // the lopsided wheel coasts down: a little shaking at speed, hard shaking at resonance, calm below
                BlockPos shaftAt = DemoBuilder.partBlock(plot, e, "shaft");
                STEPS.add(new Step(20, () -> { }, "showcase-" + (i + 1) + "-" + id + "-fast"));
                STEPS.add(new Step(3000, () -> { }, "showcase-" + (i + 1) + "-" + id + "-resonance",
                        () -> Math.abs(wheelSpeed(server, shaftAt)) < 10.5));
                STEPS.add(new Step(3000, () -> { }, "showcase-" + (i + 1) + "-" + id + "-calm",
                        () -> Math.abs(wheelSpeed(server, shaftAt)) < 6));
            }
            if (id.equals("fancooling")) {
                // a hand on the crank: the fan's jet cools its plate until it is well below its twin in still air
                BlockPos crankAt = DemoBuilder.partBlock(plot, e, "crank");
                BlockPos cooled = DemoBuilder.partBlock(plot, e, "cooled"), still = DemoBuilder.partBlock(plot, e, "still");
                STEPS.add(new Step(3000, () -> crank(server.overworld(), crankAt), "showcase-" + (i + 1) + "-" + id + "-cranked",
                        () -> hottest(still) - hottest(cooled) > 8));
                STEPS.add(new Step(1, () -> crank(server.overworld(), crankAt, 0), null));
            }
            if (id.equals("governor")) {
                // apply the brake: the speed sags, the drive rises and brings it back
                BlockPos brake = DemoBuilder.partBlock(plot, e, "brake");
                STEPS.add(new Step(200, () -> toggle(server.overworld(), brake), "showcase-" + (i + 1) + "-" + id + "-braked"));
            }
            if (id.equals("trembler")) {
                // close the switch: the relay chatters, opening its own coil's circuit eight times a second
                BlockPos sw = DemoBuilder.partBlock(plot, e, "switch");
                STEPS.add(new Step(40, () -> toggle(server.overworld(), sw), "showcase-" + (i + 1) + "-" + id + "-buzzing"));
                STEPS.add(new Step(3, () -> { }, "showcase-" + (i + 1) + "-" + id + "-buzzing2"));
            }
            if (id.equals("eddy")) {
                STEPS.add(new Step(60, () -> { }, "showcase-" + (i + 1) + "-" + id + "-braked"));
            }
            if (id.equals("fatigue")) {
                // both shafts spin; the aluminium one cracks after some two thousand turns and drops its disc
                BlockPos root = DemoBuilder.partBlock(plot, e, "aluminiumRoot");
                STEPS.add(new Step(8000, () -> { }, "showcase-" + (i + 1) + "-" + id + "-cracked", () -> gone(root, PartKind.SHAFT)));
            }
            if (id.equals("lamp")) {
                // switch the lamp on: the filament glows white-hot and the electric eye lights its green LED
                BlockPos sw = DemoBuilder.partBlock(plot, e, "switch"), fil = DemoBuilder.partBlock(plot, e, "filament");
                STEPS.add(new Step(600, () -> toggle(server.overworld(), sw), "showcase-" + (i + 1) + "-" + id + "-on", () -> hottest(fil) > 2300));
            }
            if (id.equals("furnace")) {
                // the plate heats in the dish's focus until it glows
                BlockPos plate = DemoBuilder.partBlock(plot, e, "target");
                STEPS.add(new Step(12000, () -> { }, "showcase-" + (i + 1) + "-" + id + "-hot", () -> hottest(plate) > 860));
            }
            if (id.equals("engine")) {
                STEPS.add(new Step(200, () -> { }, "showcase-" + (i + 1) + "-" + id + "-running"));
            }
            if (id.equals("rack")) {
                // crank the rack up, then let go: the weight runs it back down
                BlockPos crankAt = DemoBuilder.partBlock(plot, e, "crank");
                STEPS.add(new Step(30, () -> crank(server.overworld(), crankAt, 1), "showcase-" + (i + 1) + "-" + id + "-lifted"));
                STEPS.add(new Step(30, () -> crank(server.overworld(), crankAt, 0), "showcase-" + (i + 1) + "-" + id + "-released"));
            }
            if (id.equals("screws")) {
                // crank both screws up (clockwise from above), then let go: the fine one holds, the steep one runs back
                BlockPos fineAt = DemoBuilder.partBlock(plot, e, "fineCrank"), steepAt = DemoBuilder.partBlock(plot, e, "steepCrank");
                STEPS.add(new Step(40, () -> {
                    crank(server.overworld(), fineAt, -1);
                    crank(server.overworld(), steepAt, -1);
                }, "showcase-" + (i + 1) + "-" + id + "-lifted"));
                STEPS.add(new Step(30, () -> {
                    crank(server.overworld(), fineAt, 0);
                    crank(server.overworld(), steepAt, 0);
                }, "showcase-" + (i + 1) + "-" + id + "-released"));
            }
            if (id.equals("pressure")) {
                STEPS.add(new Step(200, () -> { }, "showcase-" + (i + 1) + "-" + id + "-later"));
            }
            if (id.equals("generator")) {
                // the data logger clipped to the load resistor: its screen shows the alternating EMF
                STEPS.add(new Step(30, () -> {
                    ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                    p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                            new net.minecraft.world.item.ItemStack(dev.emergence.mod.registry.ModRegistries.DATA_LOGGER.get()));
                    BlockPos at = DemoBuilder.partBlock(plot, e, "load");
                    if (server.overworld().getBlockEntity(at) instanceof AssemblyBlockEntity be) {
                        PlacedPart load = find(be, PartKind.RESISTOR);
                        if (load != null) SimulationManager.get(server.overworld()).attachLogger(p, at, load.id, "Resistor (load, 20 Ω)");
                    }
                    crank(server.overworld(), DemoBuilder.partBlock(plot, e, "crank"));
                    LoggerHud.showcase = true;
                }, "showcase-" + (i + 1) + "-" + id + "-scope"));
            }
            if (id.equals("generator") || id.equals("gears") || id.equals("miniature") || id.equals("fan") || id.equals("lightbarrier")) {
                STEPS.add(new Step(40, () -> crank(server.overworld(), DemoBuilder.partBlock(plot, e, "crank")),
                        "showcase-" + (i + 1) + "-" + id + "-cranked"));
            }
            if (!only.isEmpty()) STEPS.add(new Step(200, () -> { }, "showcase-" + (i + 1) + "-" + id + "-dwell"));
        }
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

    private static void toggle(ServerLevel level, BlockPos at) {
        if (level.getBlockEntity(at) instanceof AssemblyBlockEntity be) {
            PlacedPart s = find(be, PartKind.SWITCH);
            if (s == null) s = find(be, PartKind.VALVE);
            if (s != null) EmergenceMod.LOG.info("Showcase: switch at {} now closed={}", at, SimulationManager.get(level).toggleSwitch(at, s.id));
        }
    }

    /** A hand on the slide carrying the magnet: pushing (1) or let go (0). */
    private static void push(ServerLevel level, BlockPos at, int dir) {
        if (level.getBlockEntity(at) instanceof AssemblyBlockEntity be) {
            PlacedPart m = find(be, PartKind.MAGNET);
            var c = SimulationManager.get(level).compiledAt(at);
            if (m != null && c != null) c.setPushEffort(m.id, dir);
        }
    }

    /** A hand on the slide carrying the first part of the given kind in that block: pushing (±1) or let go (0). */
    private static void pushPart(ServerLevel level, BlockPos at, PartKind kind, int dir) {
        if (level.getBlockEntity(at) instanceof AssemblyBlockEntity be) {
            PlacedPart m = find(be, kind);
            var c = SimulationManager.get(level).compiledAt(at);
            if (m != null && c != null) c.setPushEffort(m.id, dir);
        }
    }

    private static void crank(ServerLevel level, BlockPos at) {
        crank(level, at, 1);
    }

    /** Leaves a hand on the crank, turning it in direction {@code dir} (it keeps pushing until the island rebuilds). */
    private static void crank(ServerLevel level, BlockPos at, int dir) {
        if (level.getBlockEntity(at) instanceof AssemblyBlockEntity be) {
            PlacedPart c = find(be, PartKind.CRANK);
            var compiled = SimulationManager.get(level).compiledAt(at);
            if (c != null && compiled != null) compiled.setCrankEffort(c.id, dir, dir > 0);
        }
    }

    /** The burner chamber's wall (or, with {@code gas}, its gas) temperature, read from the integrated server. */
    private static double chamber(net.minecraft.server.MinecraftServer server, BlockPos at, boolean gas) {
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
        if (!(server.overworld().getBlockEntity(at) instanceof AssemblyBlockEntity be)) return 0;
        PlacedPart sh = find(be, PartKind.SHAFT);
        var c = sh == null ? null : SimulationManager.get(server.overworld()).compiledAt(at);
        if (c == null || c.bodyOf(sh.id) == null) return 0;
        return c.angularVelocity(c.bodyOf(sh.id));
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
        if (index >= STEPS.size()) {
            if (wait-- == 0) mc.stop();
            if (wait < -5) wait = 40;
            return;
        }
        Step s = STEPS.get(index);
        if (wait < 0) {
            mc.getSingleplayerServer().execute(s.server);
            wait = s.delayTicks;
        } else if (s.ready != null && wait > 2 && wait < s.delayTicks - 1 && s.ready.getAsBoolean()) {
            wait = 2; // condition met: grab after a couple of frames have rendered the new state
        } else if (wait-- == 0) {
            if (mc.screen != null) mc.setScreen(null); // never photograph a menu
            if (s.shot != null) {
                Screenshot.grab(mc.gameDirectory, s.shot + ".png", mc.getMainRenderTarget(),
                        msg -> EmergenceMod.LOG.info("Showcase screenshot: {}", msg.getString()));
            }
            index++;
            wait = -1;
        }
    }
}
