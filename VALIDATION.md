# Validation

`./gradlew coreTest` runs the scientific suite (no Minecraft). `./gradlew runGameTestServer` runs in-world
integration tests. All listed tests pass as of the latest commit.

## Units and data (`UnitsTest`, `MaterialDatabaseTest`)
* Unit parser: compound units, prefixes, °C, rpm, eV; power-conjugate dimension identities; mismatch errors.
* Database loads, cites sources, every material has ρ, c_p, k, T_m; copper ρ(100 °C) linear model; tungsten
  ρ(2800 K)/ρ(293 K) within the measured 14.5–17.5 band.

## Circuits (`CircuitValidationTest`)
| Test | Reference | Tolerance |
|---|---|---|
| Resistor divider | Ohm/Kirchhoff | 1e-6 V |
| RC charging | v = V(1 − e^{−t/RC}) | 0.2 % of V |
| RL rise | i = V/R(1 − e^{−tR/L}) | 0.2 % |
| LC tank energy (trapezoidal, 50 periods) | ½Li² conserved | 0.1 % |
| LC phase | cos ω0t after 10 periods | 1 % |
| Underdamped RLC | e^{−αt}(cos ω_d t − α/ω_d sin ω_d t) | 0.01 A; ledger closes to 0.2 % |
| Diode forward drop | V = nV_T ln(I/I_s); 0.6–0.72 V at 1 mA; reverse leakage < 1e-12 A | 1e-4 V |
| 300-section resistor ladder (sparse LU) | R_in → R(1+√5)/2 | 1e-6 relative |

## Mechanics and heat (`MechanicalThermalValidationTest`)
Constant-torque acceleration and work–energy theorem; viscous flywheel spin-down with heat closure;
Coulomb stop time; torsional oscillator frequency and energy; gear ratio and power balance; gear mesh
efficiency vs μπ(1/z₁+1/z₂); lumped cooling exponential with reservoir accounting; adiabatic Joule
heating; Churchill–Chu h̄ in handbook range.

## Electromagnetism (`FieldValidationTest`, `ElectromechanicalEmergenceTest`)
* Elliptic integrals vs Abramowitz & Stegun; block-magnet axial field vs closed form (1e-9 relative);
  far field vs dipole (0.2 %); long-rod/thin-plate demagnetisation limits; loop axial field; ∇·B = 0;
  coaxial mutual far-field limit; flux-integration vs Maxwell formula (1e-4); solenoid L vs Nagaoka (1 %).
* Emergence: rotating magnets induce EMF ∝ ω (2 %); loaded generator brakes the rotor (Lenz) with energy
  closure < 1 %; two quadrature coils driven by a rotating current pattern lock a magnet rotor
  synchronously (2 %).
* Periodic interpolants (`PeriodicFunctionTest`): Fourier and spline vs analytic data and derivatives.

## Assembly emergence (`AssemblyEmergenceTest`)
A generator built purely from grid parts (frame, bearings, shaft, crank, aluminium disk, NdFeB blocks,
coils, insulated wire, resistor): the compiler finds one rotor; cranking induces load current and heats the
resistor; the energy ledger closes (< 2 %); an electrical load brakes the rotor and the extracted energy
equals circuit heating within 10 %; state survives recompilation.

## DC motor (`DcMotorEmergenceTest`)
Stator magnets, rotor coils, two copper commutator segments on a nylon hub, two graphite brushes and a
capacitor bank: the rotor turns more than two revolutions in one direction without reversing — commutation
emerges from brush/segment coverage geometry. (Development found and fixed: thin shafts conducting to
neighbours they cannot touch; brushes welding rotor to frame; commutator phase at the dead point.)

## Demo exhibits (`DemoExhibitsTest`)
Each sign's claim is asserted from the physics: generator current; gear speed ratios −1/3 and +1/5 (0.1 %);
capacitor discharge heats nichrome to 900–1600 K then cools below 500 K in 30 s; 0.1 mm nichrome melts;
copper bar end warms > 10× faster than stainless; ball bearing keeps > 50 of 60 rad/s while the bushing
stops (< 5 rad/s) and warms; the DC motor idles with the switch open and runs > 1 revolution without
reversing when closed.

Electrochemistry exhibits: the Daniell cell gives 0.4–1.1 V with copper positive, drives > 5 mA through
10 Ω and consumes Cu²⁺ (solution starts visibly blue); displacement plates copper on zinc and silver on
copper with no circuit; connecting copper to zinc in acid speeds zinc corrosion > 10× and moves hydrogen
evolution onto the copper. Every block-scale exhibit is made of single-cell parts; the miniature generator
(true scale, one block) still produces current.

## Fluids (`FluidValidationTest`, `FluidAssemblyTest`)
Property tables (air ρ, c_p, μ, k, γ; helium γ = 5/3; water ρ, μ); Hagen–Poiseuille flow (1e-4);
turbulent flow vs the implicit Colebrook equation (2 %); zero flow in a hydrostatic column; isochoric
heating (first law to 1e-4, p ∝ T); two tanks equalising with mass and internal energy conserved (1e-6);
an adiabatic tank discharging along the isentrope T ∝ p^{(γ−1)/γ}; a driven rotor pumps with exact power
balance; a free rotor in a flow settles at zero incidence (2 %); an orifice chokes at the isentropic
critical flow (1e-3), independent of downstream pressure, and matches subcritical nozzle flow.
From parts: a valve holds and releases a compressed tank (vented mass = tank loss, 3 %); a hot block
heats a sealed tank at constant p/T; compressed air drives a turbine whose sense follows blade pitch;
a cranked fan moves air through a duct (intake = discharge); water under its vapour finds its level between two
vessels side by side, both less than half full (at least 60 % of the way in 30 s, mass to 1e-6). Exhibits: the air turbine spins up as
its receiver empties; the geared fan turns a downstream windmill through the air; the heated vessel's
pressure rises at constant p/T while the cold one stays at ambient.

Pistons: isentropic compression (entropy drift < 0.2 J/(kg·K)) with a closed ledger; an alpha Stirling
from raw elements accelerates its flywheel; the Stirling exhibit runs on its copper block for 20 s and
drives > 1 mA through its generator's load.

## Phase change (`PhaseChangeValidationTest`)
Steam tables (IAPWS-IF97): p_s at 0.01/100/120/180 °C, T_s(1 atm), ρ', ρ'', h', h'', h_fg, σ, liquid c_p
and k; Clapeyron identity to 1e-6. State continuity across both dome boundaries. Sealed vessel heated
at 400 W: mass exact, first law to 1e-4, p = p_s(T), T matches an independent (m, V, U) inversion to
0.05 K. Open kettle: boils at T_s(1 atm) ± 0.1 K with boil-off ṁ = Q(1 − ρ''/ρ')/h_fg ± 1 %.
Condensation on a 30 °C wall: initial rate = Nusselt film ± 3 %, final p → p_s(T_wall) ± 5 %, energy to
the wall exact. Rohsenow flux vs Incropera Ex. 10.1 (836 kW/m², ±12 % from property data); critical
heat flux 1.26 MW/m² ± 5 %. Check valve: no reverse flow, closed below cracking pressure.
Exhibit: the steam engine keeps running (mean ω > 5 rad/s), vents steam, delivers > 2 W to the brake,
and coasts to rest within 20 s of shutting the regulator; in-world GameTest checks the boiler sits on
the saturation curve and the engine turns.
Its fire is now a 600 W element on a mica block (the hot copper it replaced cooled, and the engine stalled within a
minute) and a 120 W jacket keeps its cylinder wall above the steam's saturation temperature (without it the wall
cooled from 406 to 379 K and the speed fell from 109 to 19 rad/s over two minutes as each admission condensed): over
15–30 s it averages 108 rad/s under its 8 N brake (32.5 W). The condensing engine
(`DemoExhibitsTest.condensingEngineCarriesTwiceTheLoad`): the same engine exhausting into a sealed copper condenser a
quarter full of water at 40 °C averages 107 rad/s under a 16 N brake (twice the load at the same speed); the condenser
stays at 8.8 kPa (43 °C) and no steam leaves.

## Semiconductors and logic (`SemiconductorValidationTest`, `LogicValidationTest`)
BJT collector current vs Ebers–Moll (0.1 %), gain with the Early factor, output conductance I_C/V_A; PNP;
MOSFET square law (3 %), subthreshold slope n·V_T·ln10 (3 %), triode law (2 %); LED forward voltage from
colour (±50 mV) and photon power; CMOS inverter rail-to-rail with mid-rail switching. Chip model vs the
transistor NAND (±50 mV levels), output sag under load, D flip-flop edge behaviour, Schmitt RC period
(8 %). Exhibits: transistor switch currents; NAND truth table; the counter's value equals its clock
edges.

Thermostat exhibit: the plate temperature cycles inside a band set by the Schmitt hysteresis (≥ 4
switchings, band > 5 K, inside 22–62 °C). Wiring check: insulated wires of different nets never touch.

## MSI chips and the computer (`MsiChipValidationTest`, `DemoExhibitsTest`)
Adder over 4 × 16 × 6 operand combinations (exact sums at valid logic levels); ROM reads a 16-word program;
counter counts 10 edges, wraps with TC, holds with EN low, loads with LOAD̄, clears with CLR̄; register loads
only when enabled; outputs drive 10 kΩ through the 50 Ω stage from the supply. EM-4 exhibit: the program
steps the rotor more than a full turn forward in 40 s and more than a full turn backward with SENSE on;
the in-world GameTest checks the PC advances and the accumulator follows the program.

## Structures (`FrameSolverTest`, `DemoExhibitsTest`)
Frame solver vs beam theory: cantilever tip deflection/rotation/root moment and shear (1e-5), simply
supported mid-span deflection, axial shortening, bending about either axis, portal-frame sway (1 %).
Every gallery exhibit holds its own weight (utilisation < 0.95). Shelves: aluminium root stress vs
6M/(bt²) (±5 %), steel shelf near but under yield; a second lead block breaks the steel and glass
shelves (and drops their loads) while the aluminium holds; the same in the world (GameTest).

## Light (`OpticsValidationTest`, `DemoExhibitsTest`)
Silicon responsivity (850 nm, 630 nm, band edge); LED optical power η·E·I and short-circuit photocurrent R·k·P;
solar-cell V_oc vs V_T ln(I_ph/I_s + 1); beam coupling vs (m+1)/2π·A/d² (1e-6), blocked by a steel plate,
passed by glass. Light barrier: the vane cuts the beam, the indicator and toggle follow; solar panel lit by
full and low sun, starved when one cell is shaded; in-world GameTest of the light barrier.

## Control and robotics (`RoboticsValidationTest`, `DemoExhibitsTest`)
Pendulum period against 2π√(d/g)(1 + θ₀²/16 + 11θ₀⁴/3072) to 0.1 % with energy conserved to 10⁻⁴; a lever held
level by exactly m g d; potentiometer divider ratio at three angles; op-amp non-inverting gain 1 + R₂/R₁ and its
closed-loop single-pole step (τ = gain/2π·GBW), rail clipping without wind-up, slew-rate-limited follower
(10 V/µs), energy closure. Servo exhibit: rests hanging at zero current; the arm follows the knob (lag ≤ 0.25 rad)
up past horizontal, settles at the expected proportional droop, the knob stops at 300°, returns without
oscillating; in-world GameTest of the arm rising.

## Instruments (`InstrumentsTest`)
Resampling a ramp offered at irregular steps (exact at the sample instants), clock rebase, ring-buffer
wrap, CSV layout and empty fields, envelope min/max; live capture of the hand generator: the logged EMF's
period (autocorrelation peak) equals 2π/ω of the logged speed (3 %), the logged angle equals the integral of
the logged speed (1 %). Every exhibit restarts from its own exported running state; servo fragments restart from a
running state (a hot transistor once sent Newton to NaN: device temperatures are now bounded to 50–1500 K).

## Gas chemistry (`ChemistryValidationTest`)
Molar masses; the C_p fit through the JANAF points; dh/dT = c_p and ds/dT = c_p/T numerically (including across
the clamps); element balance of every reaction; methane's lower heating value 802.3 kJ/mol; K_p(N₂O₄ ⇌ 2NO₂,
298 K) = 0.148 and van 't Hoff consistency; N₂O₄ relaxing to the analytical equilibrium extent at 298 and 330 K
(1e-5 mol); adiabatic constant-volume combustion of stoichiometric methane–air: complete, elements conserved to
1e-9, internal energy to 1e-6, final temperature equal to the energy-balance solution within 1 K; Semenov thermal
explosion: a vessel at 800 K smoulders (peak 802 K), at 950 K explodes (peak > 2000 K).

## Chemistry exhibits (`DemoExhibitsTest`, `EmergenceGameTests`)
Brown gas: each flask's reaction quotient equals K_p at its own temperature (2 %), the warm one holds > 20 points
more NO₂ and renders darker (also in-world). Thermal explosion: cold until switched on; ignites after 30–240 s of
heating, peak > 2000 K and > 6 bar, all methane burnt; the heater's bank fades before the can exceeds 1300 K.

## Gas-mixture flow (`MixtureFlowTest`)
Adiabatic filling of an evacuated tank: 417.4 K, equal to the energy-balance solution (≈ γT₀); equalising volumes
conserve moles, oxygen and energy (the emptying gas cools, the receiving one warms); a 10 bar orifice is choked at
the isentropic mass flow (0.3 %) independent of the downstream pressure; a hot column draws air up a chimney.
In-world GameTest: the heated can ignites. Every exhibit runs without a part failing (except those built to
fail); servo fragments split at every chunk boundary restart.

## Combustion with flow (`MixtureFlowTest`, `DemoExhibitsTest`)
A well-stirred reactor fed premixed methane–air (λ = 1.5) settles at the adiabatic flame temperature computed
from the enthalpy balance (1782.4 K, to 0.1 K). Gas burner: lit furnace-style (igniter, then fuel, then igniter
off) it burns on unaided — chamber > 1100 K, CO₂ > 2 % up the chimney, air drawn only by natural draft.

## Thermoelectricity (`ThermoelectricTest`, `DemoExhibitsTest`)
Copper–constantan junction 100 K above the open ends: 4.073 mV = 40.73 µV/K × ΔT; a current through an
isothermal junction warms it at exactly (S_Cu − S_con)·T·I and the reversed current cools it equally, with the
energy ledger closed; the thermocouple exhibit's amplified output equals 201·(S_Cu − S_con)·(T_hot − T_cold) to 0.3 %.

## Radiation (`RadiationTest`, `DemoExhibitsTest`, GameTest)
Counts in 60 s within 4σ of the expected Poisson rate; logic-level pulses on the cathode resistor; dead-time
losses at 20 000 interactions/s; Ba-137m half-life measured from counts per minute (158 s vs 153 s); Cs-137/Ba-137m
secular equilibrium; 1 cm of lead transmits e^{−μx} = 0.287 (exact); the counter chip stays in step with the
tube; in-world counting at the expected rate.

## Slides (`DemoExhibitsTest`)
Spring and mass: period equal to 2π√(m/k) (2 %), oscillating about the static sag m·g/k; magnet on a spring:
with the coils loaded the magnet loses more mechanical energy than open-circuit, and the extra is the electrical energy in the load and coils (Lenz); in-world GameTest of the bouncing slide.

Hydraulic press: pushing the 1.2 cm piston 28.43 mm raises the 5 cm ram 1.638 mm = A₁/A₂ × 28.43 mm (water volume
conserved to 0.1 %); the ram pressure carries the weight.

Hydraulic jack: three strokes of the 2 cm pump (150.7 mm in all) ratchet the 5 cm ram up 25.22 mm against
A₁/A₂ × 150.7 mm = 24.11 mm; the 4.6 % surplus is water drawn in through both check valves while the quickly pushed
ram coasts up over a vapour cavity (lowest ram pressure 3.1 kPa ≈ p_v); the outlet valve holds it and the release
valve lets it down.

Bevel gears (`BevelGearTest`): a cranked 32-tooth gear on a horizontal shaft drives a 16-tooth one on a vertical shaft
at 2.00000 times its speed (25.0 → 50.0 rad/s), the two pitch points' velocities at the contact equal (3.125 m/s along
+z); no other exhibit meshes gears on crossing axes by accident.

Governed steam engine (`DemoExhibitsTest.governorHoldsTheEngineSpeed`): the flyball governor on a spindle bevel-geared
to the crankshaft at 2 : 1 holds the engine at 14.23 rad/s under its brake, the weights 10.6 mm out and the 2 mm
throttle 7.6 % open on average; with the brake off it runs at 14.33 rad/s (+0.7 %, the governor's droop), the throttle
6.6 % open.

Slide valve (`DemoExhibitsTest.slideValveThrottlesWithItsRod`): built with its rod pushed home, the plug seated, the
10 bar receiver holds and the rotor stands. Drawn back 31.6 mm by hand, the seat passes 0.021 kg/s, the rotor reaches
74 rad/s, and the receiver runs down to 1.01 bar in 2 s. Pushed home again, the seat passes about 10⁻⁶ kg/s, the rod
resting 0.34 mm off its seat after its collar's rebound. The receiver's pressure then rises 1.12 → 1.30 bar as its
walls warm the air its expansion cooled (its mass, not its pressure, is what the seat holds).

Buoyancy (`BuoyancyTest`): three floats on 5 mm aluminium rods in a pond two cells deep. An oak block settles 4.46 mm
above where it was built, its draft (ρ_w·(A·d + a_rod·(d + cell)) = m) within 3 % of the prediction. A steel block
sinks to its rod's end, −30 mm. A closed steel can of 0.5 mm sheet floats highest, +15.2 mm, its draft also within
3 %. Pushed under and let go, the oak returns to within 0.5 mm in 6 s. Its energy balances to 0.012 J (the same as the
dry rig, the rounding of 1.8 MJ of the parts' heat). In the world, the `floats` exhibit gives the same three positions. A paddle wheel (four aluminium plates two cells
from its axle) over a 1 m/s stream reaches 7.14 rad/s, its paddles at 0.89 m/s, and −7.14 rad/s in a reversed stream.
The stream's work balances to 4×10⁻⁵ J. The `waterwheel` exhibit turns at 17.5 rad/s in the core and 16.9 in the world,
its paddles at 2.2 m/s in a 2.4 m/s stream.

Pressure regulator (`DemoExhibitsTest.regulatorHoldsItsOutletWhileTheReceiverEmpties`), built from parts: a plug valve
whose plug rides the piston rod of a 3 cm cylinder fed from the outlet, a preloaded spring (4 mm wire, 8 turns,
k = G·d⁴/(8·D³·n) ≈ 6.07 kN/m, fitted 24 mm short) pushing it open. Over two minutes the 20 bar receiver blows down
to 9.8 bar through a 1.2 mm needle, while the outlet stays between 2.010 and 1.987 bar gauge. The plug opens from 0.59 to
0.84 mm, and the outlet sits where the pressure on the piston balances the spring, k·(preload − x)/A = 2.009 bar,
within 2 % (checked). Its droop, k·Δx/A = 0.021 bar for the 0.25 mm the plug opened further, is the 0.023 bar seen.

Cavitation (`FluidValidationTest`): water drained from a closed 0.1 L vessel towards a 200 Pa sink holds at 3.32 kPa
(p_v = 3.16 kPa by Antoine at 25 °C, CRC 3.17 kPa) while 7 % of it leaves, mass conserved; ∂ρ/∂p and ∂ρ/∂T match
finite differences across the onset.

Lead screw (`MechanicalThermalValidationTest`): the nut advances lead/2π per radian (to 10⁻⁶); Tr 20×4 oiled
lifting 1 kN: η = 0.3434 = model, exact thread formula 0.3412. Self-locking: a 4 mm lead (tan λ = 0.073 <
μ' = 0.135) holds 10 kg (0.4 µm in 1 s); a 40 mm lead (tan λ = 0.73) runs back 3.6 m in 1 s. Exhibits: the rack
rises r_p·Δθ exactly (145.00 mm) and runs back when let go; the screws lift one lead per turn (15.78 mm for 3.94
turns of 4 mm; 154.47 mm for 3.86 turns of 40 mm), the fine one holds (0.4 µm in 1 s), the steep one runs back.

Burst test: the 1 mm copper box (Roark plate, β = 0.3078, b/t = 60.5) bursts 47 s after the heaters switch on at
1.944 bar gauge (predicted 1.953 bar, the step between), its water at 406.2 K = T_sat(p); the 3 mm steel box reaches
1.4 % of its strength. The 200 bar methane cylinder (6 mm wall) holds at 70 % of steel's.

Magnetic circuit (`MagneticCircuitTest`): a coil on a closed electrical-steel ring (7 cells a side) has
L = 0.168 H against N²/ℛ = 0.164 H from the cells' reluctances in series (3 %); L ∝ N² (16.005 for 4× the
turns); air 0.52 mH → open rod 5.1 mH → closed ring 168 mH; two coils on the ring couple with k = 0.965 (the rest
leaks across the ring's window). Faraday ring exhibit: k = 0.958, open-circuit EMF ratio M/L1 = 4.79 for
N2/N1 = 5; closing the switch flashes one LED (18 mW), opening it the other (0.18 W); nothing while the current
is steady.

Buckling (`FrameSolverTest`): a 12-member cantilever strip under end loads of ½ and 2 × π²EI/4L² gives
P/P_cr = 0.5000 and 2.0000 (Euler exactly), its top swaying most; a hanging (tension) column gives 0. Buckling
exhibit: the short strip at 0.568 of its critical load (Euler 0.53, plus self-weight and the base joint), the
tall strip with the same 27 N block at 1.743 (Euler 1.72): it fails, the short one stands.

Gas engine: the four-stroke engine fires near top dead centre (peak 4–5 MPa, compression ratio 9) and burns its
methane to CO₂. It starts as a player finds it: standing with its fuel shut (glow plug ~1330–1370 K, head cooling
from 900 K to 801 K in a minute, 582 K in ten, 503 K in thirty), the fuel opened, a few seconds at its 4 cm starting
handle (a hand turns it at up to 75 rad/s) and it fires while still being cranked, runs away from the hand to
225–273 rad/s, and carries the Prony brake at 100–124 W. What does not work, as in life: left with its fuel open
while it stands, the needle floods the mixing box (98 % methane, too rich to burn); on a 10 cm handle (30 rad/s) the
hot surfaces light each charge before top dead centre and it kicks back, running backwards; on a 5 cm one (60 rad/s)
it caught only now and then — the needle's fixed flow is rich at that speed, and a slowing engine only gets richer;
with the brake on from the start it fires but cannot pick up. Cam-worked gas
valve (`MixtureFlowTest`): open, a tank fills from 2 bar; shut, 1.3 Pa leaks in 2 s.

Ray optics (`OpticsTest`): Fresnel 4 % at normal incidence, R_p = 0 at Brewster's angle, total reflection past
the critical angle; Snell; Cauchy n_F − n_C = (n_d − 1)/V_d exactly, BK7 n_F within 3·10⁻⁴ of Schott's. A thick
equiconvex lens (R 250 mm, 20 mm, BK7) focuses a paraxial ray at the back focal distance f(1 − (n − 1)t/(nR₁))
to 10⁻⁵ of f; a marginal ray 45 mm out focuses 13.5 mm short (spherical aberration); F and C light focus
3.73 mm apart (f/V = 3.82 mm). A concave mirror focuses at R/2 to 10⁻⁶ m; a 45° mirror turns a beam 90°. A glass
plate transmits 0.9231 (2n/(n² + 1) = 0.9231, all internal reflections included). A glass rod guides 81 % of a
Lambertian source's light to its far end (TIR) against 0.2 % without it. A lens images a point source where
1/s + 1/s' = 1/f puts it (to the 1 cm sampling, the brightest plane 1 cm short: aberration). A point source seen in
a 45° mirror delivers I·A/d² to a 1 cm² window within 0.2 % for m = 1, 6, 30; every watt is accounted for.
`OpticsValidationTest`: a 6.25 mm glass plate in an LED beam passes 2n/(n² + 1) with the source brought nearer by
t(1 − 1/n), within 0.03 %. Exhibits: the periscope's photodiode gets 3.918 µW (analytic on-axis 3.923 µW through
two aluminium mirrors), none without the second; the lens gathers 383× the light of the bare photodiode (whose
direct value matches the analytic one exactly); the solar furnace's 169 canted facets put 230 W on the plate
(1 − R of steel ≈ 40 % of the dish's focused 600 W), which settles near 880 K, and a sun 20° off axis misses it.

Incandescence (`OpticsTest.blackbodyResponsivityOfSilicon`): silicon's response to black-body light is 0.133 A/W at
2856 K (Planck's band fraction below 1.1 µm, 0.29, times ~0.47 A/W), 0.243 A/W at 5778 K, nothing at 300 K, and rises
with T to 4000 K. Lamp exhibit: a 62.5 mm filament of 0.12 mm tungsten on 12 V settles at 2562 K (radiation and
convection in air balancing ~2.5 A × 12 V); the photodiode 3.5 cells away receives 215.46 µW against the Lambertian
g·σ(T⁴ − T_a⁴) = 215.40 µW, lights its receiver, and goes dark as the filament cools.

Fatigue (`FatigueTest`): Basquin's line passes 10³ cycles at 0.9 S_ut and 10⁷ at steel 1045's 280 MPa, 5·10⁸ at
aluminium 6061's 96.5 MPa; log-midpoint stress gives the log-midpoint life; steel below its endurance limit lasts for
ever, aluminium below its fatigue strength does not. Fatigue exhibit: the overhung disc's moment at the outer bearing
(15.3 N·m) gives 262.8 MPa analysed against 32M/(πd³) = 262.8 MPa; the aluminium shaft cracks after 2101 turns
against its Basquin life of 2099 and drops its disc; the steel shaft runs on with no damage.

PID servo: with a 10 µF capacitor in series with the 100 kΩ feedback resistor (integral time 1 s) and its twin in the
non-inverting divider, the arm holds the knob's end-stop demand to 1 mrad (−0.195 rad for −0.196) while drawing 1.84 A
against gravity — where proportional control drooped up to 0.15 rad — with tracking lag ≤ 0.045 rad, and settles
without oscillating. Without the twin the network is unbalanced at DC and the loop holds 91 % of the set-point.

Eddy currents (`EddyCurrentsTest`): a 4 mm magnet moving along a thin tube (radius 20 mm, wall 1 mm) is dragged by
1.9011·10⁻¹² N·s/m per S/m against the closed form 45 μ0² p² w/(1024 a⁴) = 1.9013·10⁻¹² (σ v B_r round the tube,
integrated over the dipole's field); motion along the field induces nothing; drag ∝ B². Eddy-current brake exhibit:
the aluminium disc (between two N42 magnets at its rim) falls from 60 to 4.5 rad/s in 2 s while the nylon one coasts
at 59.9; the kinetic energy lost, 50.5 J, is found as heat in the disc, 50.3 J.

Prism (`DemoExhibitsTest.prismSpreadsTheColours`): beams parallel to the base of a 45° N-SF11 prism are turned by
50.769° (red, n 1.7787), 53.551° (green, 1.7964) and 56.628° (blue, 1.8124), each equal to i + e − A from Snell's law
at both faces to 10⁻⁶ rad.

Relay (`DemoExhibitsTest.relayCoilPullsTheArmatureAndClosesTheContacts`): a 2000-turn cored coil (L 1.63 H, 2.55 H with
the armature closed) on 24 V pulls a 1.9 kg armature across its 3 mm gap once its current reaches ~0.79 A (0.15 s),
the current dipping as the gap closes (back EMF); the contacts close and the LED carries 12 mA; the energy error over
the pull-in is 3 mJ. Released, the armature drops to its stop and the circuit opens.

Relay memory (`DemoExhibitsTest.relayLatchHoldsItselfInUntilStopped`): the relay's coil fed through START or through its
own normally-open contacts. START closed: the armature rises to its upper stop (+1.52 mm), the LED across the coil at
9.6 mA; START opened again, it stays there on its own contacts' current; STOP opened, it drops to −1.54 mm; STOP closed
again, it stays down, the LED dark — a bit of memory from the magnetics, gravity and the wiring alone.

Trembler (`DemoExhibitsTest.tremblerOscillates`): the same relay feeding its coil through its own normally-closed
contacts, a flyback diode across the coil, on 36 V: it pulls up and drops back 31 times in 4 s (7.9 Hz) with the coil
current peaking at 0.84 A; no oscillator was programmed — the period is the coil's L/R rise to the pull-in current,
the armature's flight, and the current's decay through the diode. Energy error over 4 s: 3 mJ.

Bridge rectifier (`DemoExhibitsTest.rectifierLightsTheLedSteadily`): the hand generator (coils joined across the
middle, outer leads out) through four silicon diodes onto 10 mF, an LED on 470 Ω. Hand-cranked (20 rad/s) the
capacitor holds 3.02–3.05 V — the generator's peak less two diode drops — and the LED never draws under 2.6 mA; the
diodes' reverse current stays below 10⁻¹⁴ A; 2 s after letting go the capacitor still lights it at 2.4 mA.

Boost converter (`DemoExhibitsTest.boostConverterLightsLedsAboveItsSupply`): 5 V, a Schmitt RC oscillator (≈ 26 Hz)
switching a MOSFET across a 2000-turn coil, a diode into 1000 µF, four red LEDs and 100 Ω in series. With the gate held
low the output sits below the supply (4.4 V through coil and diode) and the LEDs draw nothing; switching, it holds 7.8 V
and they draw 4.6 mA. Cost: ≈ 7 300 steps a second (≈ 4× real time) — the gate's picofarads and the snubber resolve
each switching edge; without the RC snubber the switch node rang with the coil at ~50 kHz and needed 80 000.

H-bridge (`DemoExhibitsTest.hBridgeReversesTheMotor`): two complementary MOSFET pairs (K = 5 A/V², V_th 2 V) on
9.5 V, the brushed motor across their outputs. FORWARD: 3.55 A from rest, −8.85 rad/s after 6 s; both switches open
(both outputs high, the motor shorted through the P-channels) it slows to −7.26 rad/s in 6 s by its own back-EMF;
REVERSE then drives it through zero to +3.04 rad/s within 10 s. The heavy rotor's time constant (J·R/K² ≈ 14 s
spinning up) sets all three.

PWM speed control (`DemoExhibitsTest.pwmKnobSetsTheMotorSpeed`): the H-bridge's left input driven by an op-amp
comparing a Schmitt RC ramp (≈ 5.6 Hz, 0.4–0.6 of 9.5 V) with a knob divider (0.375–0.625 of the supply); from rest the
motor reaches −1.58 rad/s in 4 s with the knob down and −5.92 rad/s with it up (the rotor's 14 s time constant
averages the pulses). Cost ≈ 1 700 steps a second (2–3× real time): each switching edge of the inductive load takes
~130 steps; a first, diode-steered oscillator (the knob's track splitting charge and discharge paths) cost 12–20× real
time — its reverse-biased diode nodes kept Newton failing ~1 500 times a second — and at 26 Hz the comparator version
still cost 9×.

Inverter (`DemoExhibitsTest.inverterSteppedUpAcLightsBothLeds`): the H-bridge's two sides driven in antiphase (a
Schmitt RC oscillator at ≈ 2.6 Hz and a second inverter) across the 200-turn primary of the electrical-steel ring
(9.5 V); the 1000-turn secondary swings −45.8 … +45.8 V (5 × 9.5 less losses) and the back-to-back LEDs on 4.7 kΩ each
reach 9.3 mA in their half-cycle. The first half-cycle draws 8.9 A: switched on at zero flux, the core swings twice as
far and saturates (inrush). Cost ≈ 3 000 steps a second (≈ 4× real time), insensitive to frequency: Newton keeps
failing at the logic chip's output and state (≈ 700 a second) — a cost shared with the boost and PWM exhibits.

Propeller on a cart (`AerofoilsTest.propellerDrivesItsCartWithTheThrustItGivesTheAir`): two blades held at
120 rad/s on a 2 kg cart sliding along the axis: at rest the push on the cart is −0.3574 N against 0.3574 N of thrust
on the air; after 4 s the cart's momentum (−1.39935 N·s) matches the impulse (−1.39932 N·s), and the thrust has fallen
to 0.3408 N. The exhibit (`DemoExhibitsTest.propellerDrivesTheCartAlongItsRail`): a flywheel-driven propeller (70 rad/s,
blades pitched 8°) on an aluminium cart pushes it 0.48 m along its rail in 5 s (0.77 N at rest, 0.66 N at 0.17 m/s).

Hysteresis (`DemoExhibitsTest.faradayRingKeepsRemanentFlux`, `inverterCoreLosesTheLoopAreaEachCycle`): Faraday's
ring (electrical steel, H_c = 40 A/m) has a coercive width of 0.0545 A in its core mode's current; switched on and off
it keeps a remanent linkage of 0.69 Wb (ψ(w) = 0.70 Wb: p rests at w less the corner rounding) with no current
flowing. Driven round its loop by the inverter (±1.83 Wb, saturating each half-cycle), the iron dissipates 0.393 J per
cycle against a loop area 2w·Δψ = 0.399 J (the directly integrated ∮(m − p)dψ: 0.396 J). The trembler, whose relay
core pulls an armature, keeps its anhysteretic core and buzzes as before (32 pull-ins in 4 s).

Speed governor (`DemoExhibitsTest.governorHoldsItsSpeedUnderLoad`): a brushed DC motor whose shaft carries a
two-magnet tachogenerator; a diode peak detector gives 0.17 V per rad/s, an op-amp PI loop (K_p 10, K_i 10 s⁻¹)
drives a Darlington emitter follower. Braked (≈ 0.3 N·m), the governed motor's speed after 10 s is within 0.5 % of
its free speed (26 rad/s; the drive rises from ≈ 9 V to ≈ 20 V); on a fixed 9.5 V supply that ran it at the same
free speed, the same brake slows it by 39 % and still falling.

Contact governor (`DemoExhibitsTest.contactGovernorHoldsItsSpeed`): the same brushed motor on 24 V, a 0.6 kg brass
weight on a 2 mm slide on its rotor (19 cm out) pressed against a contact in series with the coils by a spring
(6 mm wire, k ≈ 24 kN/m) preloaded 8.7 mm; 47 Ω across the contact. Unloaded it settles at 36.8 rad/s on 0.41 A (the
weight 0.2 mm off its stop); braked (10 N pad) 36.7 rad/s on 5.9 A — within 0.3 %. The same motor with a spring too
stiff to lift (preload 50 mm) runs on past 70 rad/s.

Spectrometer (`DemoExhibitsTest.spectrometerSeesTheGlowShiftToBlue`): a tungsten filament at the focus of an
N-BK7 collimator (f = 0.25 m), a 40° N-SF11 prism; the three detectors' band couplings centre on 473, 643 and
931 nm (the dispersion orders them). With 680 kΩ receivers (≈ 4.4 µA thresholds): at 1652 K only the infrared
channel lights, at 2095 K the red too, at 2558 K the blue too — the photocurrent ratios follow Planck's law (blue
grows 20× from 2095 to 2558 K, infrared 4×).

Acoustics (`SoundFieldTest`, `AcousticCouplingTest`): close to a steadily moving sphere the field is the potential
flow round it (u_r = Ua³cosθ/r³, u_θ = Ua³sinθ/2r³, to 10⁻⁹); the intensity integrated over a sphere 30 wavelengths
out gives an oscillating sphere's radiated power within 1.8 % of (π/6)ρck⁴a⁶U₀²; a pulse takes Δr/c and its far
pressure falls as 1/r; the force A's motion puts on B equals B's on A. In the solver, a plate driven at 400 Hz
makes a tuned plate 0.5 m away vibrate at 5.74 mm/s against the analytic 5.84 mm/s.

Loudspeaker (`DemoExhibitsTest.loudspeakerIsHeardByTheMicrophone`): a moving-coil speaker (a 3×3-cell aluminium
plate on a sprung rod, 0.22 kg, resonant near 250 Hz) switched at 250 Hz moves at 15.7 mm/s (≈ 70 dB at the
microphone); an identical plate 0.6 m away is set moving at 54 µm/s by the air; its coil's tens of microvolts,
amplified ≈ 45 000 times, light the LED. Silent, the LED stays dark.

Multirate (`MultirateTest`): a tungsten filament on 6 V heating from cold (R(T) couples current and temperature)
ends at 2379.74 K both ways, energy error 5.4·10⁻⁹ J; an LC tank ringing at 5 kHz beside a resistor heated by DC
takes 20 595 steps while the thermal network takes 16, the resistor ending at the same temperature, energy error
7·10⁻¹⁰ J. The whole suite passes with multirate on (the default) and off.

Hearing (`ToneTest`, `DemoExhibitsTest`): a 440 Hz velocity sinusoid analyses to 440.3 Hz with a pure first harmonic
and the dipole's far-field pressure within 0.6 %; a square wave keeps its odd harmonics; a still body is silent; the
synthesiser plays 440 rising crossings a second at the level the pressure asks for. The trembler's armature sounds at
its cycling rate (7.91 Hz); the loudspeaker at 261 Hz.

Magnet and iron (`DemoExhibitsTest.magnetHoldsAtContactButCannotLiftFromAfar`): the image model's pull of a 5 cm
N42 cube on iron falls from 533 N at 6 mm to 14 N at 56 mm; an armature with a 3.2 kg load set against the magnet is
held, the same set 5 cm below falls to its stop.

Vapour–liquid equilibrium (`VaporLiquidTest`, `GasLiquidTest`): pure boiling points (373.1, 351.4 K), latent heats
at 25 °C, ethanol–water bubble points and vapour compositions within 0.2 °C and 0.3 % of DECHEMA data, a
minimum-boiling azeotrope at 91.5 %; a closed litre of air over water settles at the water's vapour pressure (2330 vs
2330 Pa) with hydrogen conserved to 10⁻⁹; an insulated vessel heated by 1 kJ warms by what is left after the latent
heat of what evaporated, energy error 3·10⁻⁸ J. The still (`DemoExhibitsTest.stillConcentratesEthanol`): 10 % ethanol
boiled for 300 s gives 0.20 mol of distillate at 44 % ethanol and leaves the pot at 6.7 % (separation factor ≈ 11).
The reflux column (`DemoExhibitsTest.columnSeparatesBeyondOneStage`): the same pot under three 4 mm-weir trays and a
finned condenser at total reflux, 200 W; after 360 s the pools hold 6.0 / 5.2 / 9.0 / 21.9 % ethanol up the column and
the condensate 51 % (separation factor 16.5; after 900 s, pot 3.1 %, condensate 63 %, factor ≈ 53).

Forced convection (`JetConvectionTest`): jet velocity through the core and the 1/x decay beyond it; h at Re 19 000
equal to the Churchill–Bernstein correlation evaluated by hand (31.4 vs 31.6 W/(m²·K)); none at rest or on suction.
Vents (`FluidAssemblyTest.idleDuctOpenAtTwoHeightsDrawsNoDraft`): an idle duct open at two heights carries under
10⁻⁵ kg/s. Fan cooling (`DemoExhibitsTest.fanJetCoolsItsPlate`): idle, the two warm plates cool alike (gap < 0.05 K);
cranked for a minute the fan blows 10.8 g/s, the jet takes 12.5 W (hA 0.363 W/K) and its plate falls 5.9 K below
its twin.

Carried rotors (`CarriedRotorTest`): an unbalanced rotor (m 0.5 kg, e 5 cm) on a free 2 kg slide keeps the slide's
momentum at 10⁻¹² and the centre of mass in place, the slide swinging 2me/(M+m) = 20.00 mm peak to peak (20.00);
energy drifts 1.4·10⁻⁶ in 120 turns at ¼ ms steps (6·10⁻⁴ at 1 ms: the trapezoidal rule's error on the turning
inertia). On a spring (5 Hz) and damper (ζ 0.05), turning at 8 Hz: steady amplitude 16.33 mm, the analytical
m e ω²/|k − Mω² + icω| 16.33 mm. The unbalanced-rotor exhibit (`DemoExhibitsTest.unbalancedRotorShakesItsPlatformHardestAtResonance`):
coasting down from 30 rad/s, the platform shakes ±4.9 mm at speed, ±15.3 mm as the wheel passes ω_n = √(k/M) =
12.2 rad/s (peak seen at 9.2 rad/s: a slowing sweep peaks just past resonance, its response lagging by ~1/(ζω_n)),
±0.5 mm below; the wheel loses speed fastest at resonance (the Sommerfeld effect).

Carried slides (`CarriedSlideTest`): a 0.5 kg weight on a damped spring along a radial guide of a freely spinning
turntable settles where k·x = m·ω²·r (8.792 N both) while the turntable slows from 15 to 11.05 rad/s at constant
angular momentum ((J₀ + J_s + m r²)ω = L₀ to 10⁻⁶; |ΔL| ≤ 10⁻⁹ throughout); an off-radial guide on a vertical turntable
in gravity conserves energy to 10⁻⁸ (and angular momentum without gravity, 10⁻⁹). Spinning-weights exhibit
(`DemoExhibitsTest.spinningWeightsGoOutUntilTheirSpringsHoldThem`): cranked to 15 rad/s, the weights go out 48.9 mm,
spring force 193.3 N = m·ω²·r 193.3 N.

Link bars (`RigidLinkTest`): a four-bar's rocker (crank 0.1, coupler 0.3, rocker 0.25, frame 0.3 m) follows the
closed-form circle-intersection angle to 3.6·10⁻⁵ rad over 1.6 crank turns, the link length held to 6.9·10⁻⁶ m; a
slider-crank's slide follows r cos θ + √(l² − r² sin² θ) to 7.4·10⁻⁶ m and its rod's peak force (2.09 N) is
m·r·ω²·(1 + r/l) = 2.00 N within the second-harmonic approximation. Four-bar exhibit
(`DemoExhibitsTest.fourBarRockerSwingsBetweenTheToggleAngles`): the rocker swings 44.5°, the angle between the two
toggle positions (crank and coupler in line) by the law of cosines, 44.5°; energy error 6·10⁻⁴ J of 0.69 J over 3 s.

Flyball governor (`DemoExhibitsTest.flyballGovernorReinsTheMotorIn`), judged in steady state (80 s up from the
gallery's start, 30 s braked): the weights (soft wide-coiled springs, k ≈ 1.9 kN/m, fitted 50 mm short: lift-off near
22 rad/s) ride 10.0 mm out at 23.7 rad/s and the push rod turns the 50 Ω, 30° rheostat 7.2°; the motor works a viscous
damper on its spindle all the time. Braked (0.64 W), the speed sags 4.4 % to 22.6 rad/s: the weights sink to 3.8 mm,
the rheostat backs off to 2.5° and the current rises from 0.72 to 1.50 A. The first build (stiff springs, no steady
load, a 24 V bank through 150 Ω) took minutes to settle — an idle motor runs through so much resistance that the
rheostat hardly moves it — and sagged a fifth under the brake in steady state; 40 s windows had read −6 %
(`SplitExhibitDiagTest` runs it for minutes from any start, and replays a world island's dump).

Aerodynamics (`AerofoilsTest`): a flat plate at 5° in a 10 m/s wind is lifted by exactly ½ρV²A·C_L (C_L 0.503 against
the thin-aerofoil 2πα 0.548); a windmill swept over tip-speed ratios 3–12 and pitches −6…6° never exceeds the
momentum bound C_p ≤ 4a(1 − a)² ≤ 16/27 at its own induction, the best C_p 0.483 at λ 8 (Betz 0.593, less the flat
blades' drag); a hovering propeller's induced velocity is exactly momentum theory's √(T/2ρA), figure of merit 0.58;
a windmill braked by a damper closes its energy ledger to 3·10⁻⁹. The wind turbine exhibit
(`DemoExhibitsTest.windTurbineTurnsInTheWindAndPowersItsLoad`): 8 m/s → 151 rad/s, 14.1 W from the wind (C_p 0.18),
7.7 W in the load; 5 m/s → 86 rad/s, 2.6 W; still air → nothing. Its ledger closes to 3·10⁻³: the trapezoidal
rule's energy error on the generator's AC (each step's energy differs from the trapezoid of the power by
−h/4·Δv·Δi, of one sign for a resistive load). It converges as h²: the hand generator cranked to 160 rad/s shows
2.4·10⁻⁴ of the work at the default tolerances, 7.3·10⁻⁵ with steps capped at 0.2 ms, 4.5·10⁻⁶ at 0.05 ms — accuracy,
set by the 10⁻³ local-error tolerance, not a leak.

Iron saturation (`DemoExhibitsTest.faradayRingCoreSaturatesAtTheSteelsSaturationFluxDensity`): the Faraday ring's core
mode levels off, for the primary, at 0.345 Wb, against N₁·B_sat·A_iron = 200 × 2.0 T × (the coil core's iron) =
0.358 Wb (4 %); the bank's 3 A would add 1.08 Wb unsaturated, the linkage stays at 0.43 Wb; energy error 1.6 mJ of the
bank's 360 J.

Optocoupler (`DemoExhibitsTest.optocouplerCarriesTheSignalAsLight`): a 940 nm LED at 16 mA emits 4.3 mW, 0.23 mW
reaches the phototransistor's window, 55 µA of photocurrent (silicon ≈ 0.24 A/W there after the window's geometry)
becomes 7.7 mA of collector current (×140, the transistor just saturated: V_CE 0.18 V), lighting the green LED; dark
with the switch open, and again when it opens.

Absorbing glass (`OpticsTest.tintedPlateFollowsBeerLambert`, `filterGlassEdges`): a plate with α t = 3.75 passes
0.0217 (analytic (1 − R)²τ/(1 − R²τ²) 0.0217) and absorbs 0.9383 (0.9383), every watt accounted for; RG630 passes
0.495 at 630 nm through 3 mm. Colour filters exhibit (`DemoExhibitsTest.colourFiltersSortTheFilamentsLightByColour`):
the same filament seen both ways, red-hot (1607 K) the red side gets 73× the blue-green side's light and only its
LED lights; white-hot (2748 K) the ratio falls to 7× and both light.

Memory chip (`DemoExhibitsTest.memoryChipStoresWordsByAddress`): words 1010, 0101, 1111 written by hand into words 1–3
read back at their addresses (word 0 stays 0000) on the LEDs, and word 3 still reads 1111 after the build is
recompiled from its exported state.

Liquid drains (`LiquidDrainTest`): two litre vessels joined at the floor, 36 mm of water in one, level out at
18.07 / 18.07 mm with hydrogen conserved to 10⁻⁹ and energy to 10⁻⁶ J; a vessel 0.3 m up drains completely into one
below, its liquid arriving at the ethanol fraction it left with (0.167). The still with drains: 0.19 mol of
distillate at 43 % ethanol.

## In-world (`EmergenceGameTests`, GameTest server)
Hand-cranked generator inside a real world heats its resistor; part persistence round trip; the demo
capacitor exhibit placed at block scale through the real builder path flash-heats its wire; the
block-scale gear train meshes across air gaps (island reach) with the 8:40 ratio within 1 %.

## Battery (`BatteryTest`, `BatteryPartTest`)
A full six-cell battery reads 12.753 V open circuit (Vinal: 12.75 V). Run down 300 s through 12 Ω, the state of charge
counted (0.841) equals 1 − ∫i dt / capacity to 10⁻³, the terminal voltage sags 11.57 → 11.31 V as the acid thins,
the battery warms by its i²r, and the energy ledger closes to 10⁻⁷ J. Charged at 14.4 V through 2 Ω it takes charge
back in (0.50 → 0.533 in 100 s at 0.6 A). As a part wired to a 10 Ω load it delivers 0.976 A (12.75 V over 13 Ω:
0.981 A), and its state of charge carries through a recompile. Four nickel–metal hydride cells (1 A·h) follow the
Nernst curve of their nickel electrode: E½ = 1.30 V at half charge, 1.401 V full, 1.266 V at a fifth (its reported
slope matches the curve's to 10⁻³); into 20 Ω they read 5.55 V, sagging to 5.32 V over 30 minutes as Faraday's count
(0.865) says, the ledger closing to 10⁻⁷ J.

## The part editor (`PartParametersTest`, `ParamTextTest`)
Every part of all 72 exhibits, and every stock preset, passes the editor's checks (a material the kind may be made of,
known parameters, each in range); every default is in range and every automatic default says what it is; bad
specifications are refused (a resistor of copper sulfate solution, a magnet of copper, a negative diameter, an unknown
key, a seventh transistor type, half a cell). Values are shown as people write them ("1.5 mm", "4.7 kΩ", "100 pF",
"80 %", "13 V/µs") and read back from what they type ("4.7k", "220 mΩ", a bare number in the unit shown).

## In the world, as a player (`generateDemoWorld`, `runShowcaseClient`)
The demo world is built by player placement (`PlayerBuilder`) and checked body by body against the definitions
(every rotor and slide: its parts, mass or inertia, a slide's travel): all 72 exhibits come out exactly as defined (0 build
problems). Building them so found exhibits a player could not have built — linear bearings, lead-screw nuts and
coils could not share a shaft's cell, a crank could only face along its shaft one way, parts with nothing to click
against, the wire router laying wire through the cells of components added after it was set up, a press column inside
its pipe — all fixed. The tour then works every exhibit with real clicks of the use and attack keys and checks what
should follow (speeds, temperatures, lit LEDs, broken parts, logger readings), writing `showcase-report.txt`. Its first
run found that a crank sharing its cell with its shaft could not be turned by a player (a click picked the part by
cell, not by the outline hit), that a switch clicked as soon as it was placed did not latch, and that brakes were never
applied by the old tour (its toggle found no switch in the brake's block).

The latest full tour (all 72 exhibits, 26 minutes, the gallery as it stands after minutes of running) passes all 137
checks; the build check finds every exhibit's bodies (parts, mass, inertia, travel) exactly as defined.

## Known validation gaps
No tests yet for: diode temperature dependence, brush/commutator contacts, multi-coil mutual coupling on
rotors, thermal radiation exchange between parts, long-run energy drift under BE restarts.

## Chips and monitors (`ChipDesignTest`, `DigitalDomainTest`, `DigitalPartsTest`, `ChartRecorderTest`, `ChartRecorderExhibitTest`)
- The chip language: a blinker toggles every half-period of edges. Registered bus outputs and combinational ones
  follow. Memories, `case`, slices and concatenation evaluate as written. Errors give their line and say what is
  wrong. Estimates grow with the logic, and a clock faster than the estimate's maximum is counted as violations
  while the registers hold. State saves and restores exactly.
- The digital domain: a clocked chip blinks an LED through the circuit without the solver stepping on every clock
  edge (only the LED's edges land steps). An analog input switches at half the supply with hysteresis. Supply
  current rises with the clock as (1.1·E_edge + C_net·V²)·Δf / V.
- The library parts over SPI, against a chip of the player's design: the ADC reads its input as a fraction of the
  supply; the display takes commands and data. The pressure transducer gives 10 % to 90 % of its supply over its range.
- End to end: a rising voltage on the ADC draws a rising trace on the display. The bus is a digital net, so the
  solver does not follow it.
- The chart-recorder exhibit (block scale, 45 simulated seconds, 3 s of CPU): copper at 500 K heats a sealed tank
  of air from 293 K to 457 K (101 → 158 kPa). The pressure channel's ADC goes from code 117 to 565, the
  thermistor's from 108 to 927. The recorder draws 114 columns at 2 kHz (a sweep is 128). Both traces climb, both
  screens are on, and 15 nets are digital.

## Counter display (`CounterDisplayExhibitTest`)
A 1 Hz crystal clocks a 74HC163 counter chip in the analog circuit. Its outputs light four LEDs and feed a chip of
the player's design (`counter_display`) across the analog–digital boundary. Once a second for 20 s, the test reads
the screen independently. It decodes the big digits against the font and the binary row underneath, and reads the
count off the LEDs' currents. All three agree at every reading, 1 to 15 and round to 0 again. Run at 500 kHz, it
costs 0.23 s of CPU per simulated second.

## MSI chips by events, ROM, contention, delay (`MsiComponentTest`, `CounterDisplayExhibitTest`, `ChipToolGameTests`)
- The counter clears, loads and counts like a 74HC163, TC at 15 with EN high. It wraps, and its state saves and
  restores.
- The 256-byte ROM shows the byte addressed after its access time. Its hex reads `@` addresses, comments and 0x
  prefixes, and formats back to the same bytes. Bad hex is refused, with its line given.
- CMOS delay is ×1 at 5 V, about ×4 at 2 V and under ×1 at 6 V.
- Two outputs fighting over a net for 1 ms burn 0.25 mJ (5 V across 100 Ω), shared equally between them.
- The EM-4 computer, the Geiger counter and the memory exhibit pass their core tests, now that their chips run by
  events.
- The counter display, with its font in an external ROM: the screen matches the LEDs through a full count.
  Rewriting the ROM's "1" glyph draws a 7 for a count of 1.
- In the world (GameTests):
  - A counter chip placed by a player facing north lies as nine blocks to the east and comes out whole.
  - A design written onto a placed chip lengthens its row to 9 blocks for 8 outputs, then shortens it back to 5.
    The first segment keeps its id; a draft that does not compile keeps the row as it was.
  - A wire run between two resistors goes round a third in the way, never beside it.
- The counter display's tour opens the chip designer and the ROM editor by right-clicking the placed chips. Both show
  the chip running (powered), and the ROM editor shows the font (byte 2D = 1F, the F's first column).

## Miniature counter display (`CounterDisplayExhibitTest.theMiniatureCountsOnItsScreenToo`, tour `minicounter`)
The whole counter display at true scale, in one block of 6.25 mm cells. It shows the LEDs' count on its 25 × 12.5 mm
screen every second through a full count, in decimal and binary. In the world it is built with the tweezers part
by part (0 problems, 0 differences), and the tour reads the screen as lit, its chip drawing the LEDs' count.
On the first try, the four LEDs and resistors side by side shorted Q0–Q3 together: their leads left across the
part, into their neighbours. Standing the leads up (`lead_axis` 1) fixed it; the model was right.

## A miniature controller drives a full-size screen (`ChipToolGameTests.miniatureControllerDrivesFullSizeScreen`)
The miniature counter display is placed in the world: its circuit in one miniature block, its 4 × 2 screen of
block-scale panels against the block's north face. Its island links the one screen (8 panels) to its controller,
which is on and drawing the count. The core test reads that controller's image against the LEDs through a full
count.

## Ribbon cable, and one machine across scales (`RibbonCableTest`, `CounterDisplayExhibitTest`, `ChipToolGameTests.ribbonCarriesTheCountToFullSizeLeds`)
- A battery on colour-1 and colour-0 wires into a three-block ribbon drives 51 mA through a 100 Ω resistor between
  colour-1 and colour-0 taps at the far end. A resistor on a colour-2 tap carries nothing.
- The miniature counter display sends its count out on miniature wires of colours 1–4 (and ground on 0) into a
  full-size ribbon, tapped to four full-size LEDs. In the core test they match the screen every second through a
  full count.
- In the world: the miniature block and the ribbon are one machine, with no warnings, and every full-size LED lights
  as the count goes round.
- Tour (2026-10-27): all four checks pass:
  - the controller draws;
  - the full-size screen shows its image;
  - the miniature and its ribbon are one machine;
  - the LEDs show the chip's count (12 = 1100).

## The EM-8 computer (`Em8Test`, `Em8ThermostatExhibitTest`, tour `em8thermostat`)
* The CPU at 250 kHz runs 3 × 7 by repeated addition (21), takes input-port branches (0x55 / 0xAA), and the
  assembler reports undefined and duplicate names with line numbers.
* The thermostat exhibit, 90 s from a 314 K start: the heater on, off at 318.6 K, on again at 318.1 K; the plate held
  at 318.1–318.7 K afterwards; the LEDs equal to the program's stored reading's top bits at every sample (8, the
  setpoint's); no timing violations.

## Across scales (`CrossScaleLinkTest`, `AcrossScalesExhibitTest`, `ChipToolGameTests.aFullSizeCrankTurnsAMiniatureGenerator`)
* A cranked shaft and one linked to it 40 cells away turn at the same speed within 2 %.
* Tanks at 3 and 1 bar through a linked pipe come to 1.855 bar each, with no vents, and the air's mass is conserved
  to 10⁻⁶.
* Two copper cells at 400 K and ambient through a linked contact come within 10 K in 150 s (τ = C/2G ≈ 38 s).
* The exhibit: the full-size crank and the miniature shaft both at 29.42 rad/s, the load at 15.5 mA peak. In the world,
  one island, one shaft coupling, the speeds within 5 %, and the load above 1 mA.
* Pipes and heat across scales (`AcrossScalesExhibitTest.airAndHeatCrossTheFaces`): the full-size tank at 3 bar and
  the miniature one at 1 bar come to 1.655 bar each in 60 s, with no vents. The miniature copper cell warms from
  293 to 354 K, and the full-size block falls from 450 to 387 K.

## Logic capture (`LogicCaptureTest`)
* On the counter display: the serial clock and data are digital channels, the 500 kHz clock recording over 10 000
  edges in 1.5 s, microseconds apart. Q0 through its LED is analog, swinging below 0.5 V and above 4 V. A single
  capture armed on Q0 rising triggers at the next count and holds.

## Fixes found by the full tour (`PwmHeatTest`, `DatumPlacementTest`)
* PWM: with the control circuit on its own decoupled supply, the hottest bridge transistor reaches 305 K in 120 s
  (473 K before, shooting through).
* Datum placement: the periscope compiled with its photodiode first lights its receiver (6.7 mA). Before, its sense
  line was the datum and stayed at 0.38 V.

## The station's dashboard (`GaugeTest`, `DashboardTest`, `EmbeddedMiniatureTest`, station tour)
* One gauge block fed over a ribbon: its value is the formula of its ADC's reading. Its label's first glyph, its
  units digit's top rows and its bar's fill and frame are drawn where the design puts them.
* The whole station, 30 s, each gauge against the simulation: fuel 189.0 bar (189.2), boiler 4.14 bar (4.141),
  engine 137 rpm (137.7), condenser 43.3 kPa (42.5), generator 63 mA (70.5 mean over the last second), battery
  5.59 V (5.573). There are no timing violations.
* A ribbon from a full-size battery lights an LED in an embedded miniature block (15.3 mA).

## The EM-8's calls and the analyser's decoders (`Em8Test`, `BusDecoderTest`, `LogicCaptureTest`)
* A program calls a subroutine, then another that calls the first. It keeps A on the stack across the inner call:
  5 → 15 → 30 → 120, as computed by hand.
* A pointer fills a table in RAM through STN and sums it through LDN with a 16-bit carry (ADCI): 200 + … + 207 = 1628,
  the high byte 6.
* UART at 9600 baud, found from the shortest pulse: "Hi", 0x55, 0x00 and 0xFF decoded. Read at 4800, the bytes come
  out wrong. A low stop bit is flagged as a framing error.
* SPI with a select: two bytes decoded; a stray clock while deselected and a partial byte cut off by the select are
  ignored. Without the select the stray edge shifts every bit.
* The counter display's real bus, recorded off the simulated nets: the controller's set-up commands come first,
  0xAF 0x20 0x00, as the design sends them, then the screen's bytes.
* The CSV has a row for every change, each channel's value at it.

## Chip fabrication (`FabricationTest`)
* Dry oxidation of (100) silicon, 1 h at 1100 °C in O₂: Deal–Grove's closed form 99 nm (textbook about 0.1 µm),
  the integrator with the thin-oxide term 107 nm. Deep in the parabolic regime the rate is Deal–Grove's to 0.1 %.
  At room temperature nothing grows in a year.
* Boron at 1100 °C: D = 1.52·10⁻¹³ cm²/s (Fair, intrinsic); after 1 h from a constant source x_j = 1.36 µm.
* erfc against tables to 10⁻⁶ relative; its inverse round-trips.
* The reference process gives exactly V_T = 0.75 V, delay and leakage ×1. A 150 nm oxide gives 2.2 V and ×4.5; too
  thin, too thick, too shallow and punched-through processes each fail with their reason.
* The furnace exhibit in the core: 1.4 kW from 24 lead–acid cells holds the wafer at 1460 K (1190 °C), and
  nothing burns out. The chips work from 180 s (oxide 14.2 nm, junctions 0.71 µm), 5.4× slower than the
  reference. At 900 s: 32 nm, 1.71 µm, 4.6×. The process survives a save and reload.
* Two steps: the wafer heated bare, then edited to carry its film (the same part, its state carried over the edit).
  Its 3 µm chips work, and leak 6× less than one step's fastest (see PHYSICS_MODELS).
* Short channels: the threshold rolls off and leakage rises as the junctions near the gates' middle; a halved
  channel gains less than the square law's 4× (velocity saturation).
* A counter design clocked at 1.5× its reference period keeps up on reference chips and misses edges on chips three
  times slower.
