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

## Demo exhibits (`DemoExhibitsTest`)
Each sign's claim is asserted from the physics: generator current; gear speed ratios −1/3 and +1/5 (0.1 %);
capacitor discharge heats nichrome to 900–1600 K then cools below 500 K in 30 s; 0.1 mm nichrome melts;
copper bar end warms > 10× faster than stainless; ball bearing keeps > 50 of 60 rad/s while the bushing
stops (< 5 rad/s) and warms.

## In-world (`EmergenceGameTests`, GameTest server)
Hand-cranked generator inside a real world heats its resistor; part persistence round trip; the demo
capacitor exhibit placed through the real builder path flash-heats its wire.

## Visual (`runShowcaseClient`)
Automated tour of the demo world saving screenshots of every exhibit (including discharge and cranked
states) to `run/screenshots/showcase-*.png` for review.

## Known validation gaps
No tests yet for: diode temperature dependence, brush/commutator contacts, multi-coil mutual coupling on
rotors, thermal radiation exchange between parts, long-run energy drift under BE restarts.
