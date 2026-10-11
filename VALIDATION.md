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
a cranked fan moves air through a duct (intake = discharge). Exhibits: the air turbine spins up as
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

Gas engine: the four-stroke engine fires every cycle near top dead centre (peak 7.7 MPa, compression ratio 9),
burns its methane to CO₂ (up to 9.5 %), and its power strokes take the crank from 30 to 115 rad/s in 10 s against
a Prony brake; unbraked, its fixed fuel flow governs it near 200 rad/s as the mixture leans out. Cam-worked gas
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

Trembler (`DemoExhibitsTest.tremblerOscillates`): the same relay feeding its coil through its own normally-closed
contacts, a flyback diode across the coil, on 36 V: it pulls up and drops back 31 times in 4 s (7.9 Hz) with the coil
current peaking at 0.84 A; no oscillator was programmed — the period is the coil's L/R rise to the pull-in current,
the armature's flight, and the current's decay through the diode. Energy error over 4 s: 3 mJ.

Speed governor (`DemoExhibitsTest.governorHoldsItsSpeedUnderLoad`): a brushed DC motor whose shaft carries a
two-magnet tachogenerator; a diode peak detector gives 0.17 V per rad/s, an op-amp PI loop (K_p 10, K_i 10 s⁻¹)
drives a Darlington emitter follower. Braked (≈ 0.3 N·m), the governed motor's speed after 10 s is within 0.5 % of
its free speed (26 rad/s; the drive rises from ≈ 9 V to ≈ 20 V); on a fixed 9.5 V supply that ran it at the same
free speed, the same brake slows it by 39 % and still falling.

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

## In-world (`EmergenceGameTests`, GameTest server)
Hand-cranked generator inside a real world heats its resistor; part persistence round trip; the demo
capacitor exhibit placed at block scale through the real builder path flash-heats its wire; the
block-scale gear train meshes across air gaps (island reach) with the 8:40 ratio within 1 %.

## Visual (`runShowcaseClient`)
Automated tour of the demo world saving screenshots of every exhibit (including discharge and cranked
states) to `run/screenshots/showcase-*.png` for review.

## Known validation gaps
No tests yet for: diode temperature dependence, brush/commutator contacts, multi-coil mutual coupling on
rotors, thermal radiation exchange between parts, long-run energy drift under BE restarts.
