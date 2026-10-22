package dev.emergence.core.demo;

import dev.emergence.core.assembly.AssemblyCompiler;
import dev.emergence.core.assembly.CompiledAssembly;
import dev.emergence.core.assembly.Dir;
import dev.emergence.core.math.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.emergence.core.assembly.PartKind.*;
import static org.junit.jupiter.api.Assertions.*;

/** Bevel gears: a pair on crossing axes turns the drive through a right angle at the ratio of their teeth. */
class BevelGearTest {
    private static final double CELL = 1.0 / 16;

    /** A 32-tooth gear on a cranked horizontal shaft drives a 16-tooth one on a vertical shaft: twice as fast. */
    @Test
    void aBevelPairTurnsTheDriveThroughARightAngle() {
        Exhibit e = new Exhibit("bevel", "Bevel gears", List.of("BEVEL"), "test");
        String st = "steel_1045";
        // the horizontal input: a crank, a shaft in two bearings on posts, the 32-tooth gear at its end
        e.name("crank", e.add(CRANK, st, 0, 3, 8, Dir.EAST, "radius", 0.12));
        e.name("input", e.fill(SHAFT, st, 1, 3, 8, 5, 3, 8, Dir.EAST, "diameter", 0.012));
        e.add(BEARING, st, 2, 3, 8, Dir.EAST);
        e.add(BEARING, st, 4, 3, 8, Dir.EAST);
        e.fill(BLOCK, st, 2, 0, 8, 2, 2, 8, Dir.UP);
        e.fill(BLOCK, st, 4, 0, 8, 4, 2, 8, Dir.UP);
        e.name("big", e.add(GEAR, st, 5, 3, 8, Dir.EAST, "teeth", 32, "pitch_radius", 2 * CELL, "thickness", 0.01));
        // the vertical output: its 16-tooth gear r₁ = 2 cells above the big gear's centre and r₂ = 1 cell along its axis
        e.name("small", e.add(GEAR, st, 6, 5, 8, Dir.UP, "teeth", 16, "pitch_radius", CELL, "thickness", 0.01));
        e.name("output", e.fill(SHAFT, st, 6, 5, 8, 6, 9, 8, Dir.UP, "diameter", 0.012));
        e.add(BEARING, st, 6, 6, 8, Dir.UP);
        e.add(BEARING, st, 6, 9, 8, Dir.UP);
        e.fill(BLOCK, st, 6, 6, 9, 6, 6, 10, Dir.UP);
        e.fill(BLOCK, st, 6, 9, 9, 6, 9, 10, Dir.UP);
        e.fill(BLOCK, st, 6, 0, 11, 6, 9, 11, Dir.UP);
        Exhibits.Built b = Exhibits.build(e, 293.15);
        CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
        assertTrue(c.warnings().isEmpty(), "warnings: " + c.warnings());
        assertEquals(1, c.bevelMeshes(), "one bevel mesh");
        long crank = b.id(e, "crank");
        for (int i = 0; i < 40; i++) {
            c.setCrankEffort(crank, 1, false);
            c.step(0.05);
        }
        var bi = c.bodyOf(b.id(e, "input"));
        var bo = c.bodyOf(b.id(e, "output"));
        double wi = c.angularVelocity(bi), wo = c.angularVelocity(bo);
        // the pitch points' velocities at the contact (on the big gear's top, the small gear's −x side)
        Vec3 vi = bi.axis().mul(wi).cross(new Vec3(0, 2 * CELL, 0)), vo = bo.axis().mul(wo).cross(new Vec3(-CELL, 0, 0));
        System.out.printf("bevel pair: input %.3f rad/s, output %.3f rad/s (ratio %.5f); contact velocities %s, %s%n", wi, wo, wo / wi, vi, vo);
        assertTrue(Math.abs(wi) > 1, "the crank turns the input: " + wi);
        assertEquals(2.0, Math.abs(wo / wi), 1e-6, "16 teeth against 32: twice as fast");
        assertEquals(0, vi.sub(vo).length(), 1e-6 * vi.length(), "the pitch points roll together at the contact");
    }

    /** No exhibit meshes gears on crossing axes by accident (one built for it may). */
    @Test
    void noStrayBevelMeshesInTheExhibits() {
        for (Exhibit e : Exhibits.all()) {
            Exhibits.Built b = Exhibits.build(e, 293.15);
            CompiledAssembly c = AssemblyCompiler.compile(b.assembly(), b.state());
            assertEquals(e.id.equals("governed") ? 1 : 0, c.bevelMeshes(), e.id + ": bevel meshes");
        }
    }
}
