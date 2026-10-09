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
