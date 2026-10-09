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
