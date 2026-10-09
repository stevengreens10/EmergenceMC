# Physics models

Every model below states its governing equations, assumptions, validity range and expected error.
SI units throughout. Tests that validate each model are listed in VALIDATION.md.

## Numerical integration (`sim.TransientSolver`)
* Residual DAE `F(x, d/dt q(x)) = 0`; Newton–Raphson, tolerance `|Δx| ≤ 1e-4·|x| + absTol`.
* **Trapezoidal rule** default: A-stable, second order, exactly conserves the quadratic energy of linear
  lossless oscillators. Error constant 1/12; phase error ≈ (ωh)²/12 per radian.
* **Backward Euler** for the first step after any discontinuity (switch, source step, structural change); the
  derivative of that step spans the jump and is discarded from the integration history.
* **Ringing suppression**: three consecutive sign alternations of Δq̇ above tolerance in any stored quantity
  trigger one damping backward-Euler step.
* **Step control**: LTE ≈ C·h³·q''' estimated from divided differences of q̇; tolerance
  1e-3·|q| + absTol(q), with absTol scaled to the physical signal (e.g. coil flux tolerance = 1e-4 of the
  coil's peak magnet-flux swing).
* **GMIN**: 1e-12 S shunt per node (relative 1e-12·|J_ii| for electrical nodes so floating conductive
  islands remain numerically well posed). Effect below any instrument resolution.

## Electrical
| Element | Model | Validity / error |
|---|---|---|
| `Resistor` | i = v/R; Joule heat v²/R into thermal node | exact |
| `Conductor` | R(T) = ρ(T)·L/A, ρ(T) = ρ20(1+α(T−293.15)) or ρ20(T/293.15)ⁿ (tungsten n = 1.215) | uniform current density (below skin-effect frequency); ρ(T) within ~5 % for 200–600 K (linear) / to 3000 K (power law) |
| `Capacitor` | q = C·v | ideal (no ESR/leakage yet) |
| `Inductor` | v = R·i + d(L·i)/dt | linear, no core saturation |
| `Diode` | Shockley i = I_s(T)(e^{v/nV_T} − 1), I_s(T) ∝ T^{3/n}·e^{(E_g/nV_T0)(T/T0−1)}; linear continuation beyond v = 40 nV_T for Newton robustness | no series R, junction capacitance or breakdown yet |
| `Switch` | R_on = 2 mΩ, R_off = 1 GΩ | contact bounce/arcing not modelled |
| `SlidingContact` | i = (G_max·c(θ) + G_off)·v; c(θ) = fraction of a 5×5 sample grid on the brush face inside the rotating conductor at angle θ (720-point periodic table) | constant contact resistance (0.02 Ω default) |
| `VoltageSource`, `FlowSource` | ideal sources (external reservoirs) | test/bench use |

## Electromagnetism (`em`)
* **Permanent magnets**: uniformly magnetised cuboids, M = B_r/μ0 rigid (recoil permeability ≈ 1.05
  neglected, error ≲ 5 % for NdFeB). Field from the equivalent magnetic surface charge σ = M·n̂ on faces,
  closed-form for rectangles (Furlani 2001 §4.3). Inside the magnet B = μ0(H + M).
* **Current filaments**: exact loop field via complete elliptic integrals (AGM, machine precision).
* **Coils**: winding discretised into filaments (n_a × n_r). Flux linkage by Gauss–Legendre (6 radial) ×
  trapezoidal (16 angular) quadrature over each filament's disk. Self-inductance from Maxwell's mutual
  inductance between filaments spaced ≤ 0.04·a plus Rosa's GMD self-term (≲ 1 % vs Nagaoka).
* **Coupling (`MagneticSystem`)**: co-energy W'(i, θ) = ½ iᵀL(θ)i + Σ Φ_j(θ) i_j − U_mm(θ).
  λ = ∂W'/∂i, τ = ∂W'/∂θ, stored energy W = λ·i − W'. This yields Faraday EMF, Lorentz/interaction torque,
  generator braking and motor action with exact power balance in continuous time.
  Angle-dependent couplings between coaxial bodies are tabulated at 256 angles and represented by a
  truncated Fourier series (smooth derivatives for the Jacobian and the error estimator).
  Magnet–magnet energy: U = −∫M_a·B_b dV by 4³ Gauss–Legendre per magnet.
* **Limitations**: no ferromagnetic cores (air-core only), no eddy currents in structural conductors, no
  non-coaxial moving bodies, magnets do not demagnetise with temperature yet. See KNOWN_LIMITATIONS.md.

## Mechanical
| Element | Model |
|---|---|
| `Inertia` | Σ τ = d(Jω)/dt (rotational) or d(mv)/dt |
| `Spring` | rate-form Hooke's law d(τ/k)/dt = Δω; shaft torsion k = G·J_p/L |
| `Damper` | τ = c·Δω |
| `CoulombFriction` | τ = τ_c·tanh(Δω/ω_s), ω_s = 1e-2 rad/s (regularised stiction) |
| `GearMesh` | Lagrange-multiplier constraint r_a ω_a ± r_b ω_b = 0 (lossless); tooth sliding loss f ≈ μπ(1/z_a + 1/z_b) (Buckingham) as heat |
| `HumanCrank` | Hill force–velocity relation F = F_max(1 − v/v_max), power cap; F_max 200 N, v_max 3 m/s, 100 W sustained / 250 W sprint |
| Bearings (compiler) | Ball: SKF simplified M = ½·μ·P·d, μ = 0.0015, plus grease drag. Plain bushing: boundary friction μ·P·d/2 (μ = 0.4·μ_material) plus Petroff hydrodynamic drag c = μ_oil·2πr³L/clearance (oil 0.1 Pa·s, clearance 0.1 %·d). Load P = rotor weight / bearing count. |

## Thermal
* `HeatCapacity`: U = m·c_p·T (constant c_p; lumped — valid for Biot < 0.1).
* `ThermalConductance`: Fourier conduction; part contacts use series half-body resistances plus contact
  conductance h_c = 5000 W/(m²K).
* `NaturalConvection`: Churchill–Chu (horizontal cylinder, vertical plate) and Churchill (sphere)
  correlations with air properties at film temperature; ±20 % typical.
* `Radiation`: grey body to large surroundings, εσA(T⁴ − T∞⁴).
* `FixedTemperature`: ambient reservoir; heat absorbed is recorded in the energy ledger.
* Air properties (`fluids.Air`): Sutherland viscosity/conductivity, ideal-gas density, polynomial c_p
  (< 3 % for 200–1000 K vs Incropera Table A.4).

## Assembly compiler (`assembly.AssemblyCompiler`)
* **Grid**: 1/16 m cells; topology from cells, physics from parametric dimensions. In the world a cell is
  either a whole block (block scale: a 16× magnified scale model, the default for building) or 1/16 of a
  block (miniature, true scale). The physics is identical; only the rendering and world mapping differ.
* **Rigid bodies**: structural parts join everything they touch except: shaft through a coaxial bearing
  (revolute joint), gear–gear contact (mesh), brush–conductor contact (sliding), thin shafts (< 0.9 cell)
  only touch at their end faces or where mounted parts overlap them; wires never carry load.
* **Kinematics**: groups touching the world are static; groups held only by collinear bearings on a
  static body or a coaxial rotor become rotors; free-standing machines use their heaviest group as frame;
  over-constrained groups lock (with a warning).
* **Electrical contact**: wires connect on all faces (insulated wire only to wires/leads); components have
  leads at their axial ends (end-face centre, plus side faces of end layers when ≥ 2 cells long; a
  single-cell component also brings its − and + leads out on the −L and +L side faces of its lead axis L,
  parameter `lead_axis`, as on a real coil former); bulk metal
  conducts; only brushes conduct across a moving joint. Contacts are split at the face so each half's
  Joule heat goes to its own part.
* **Failure**: parts are destroyed above their failure temperature (melting point; insulation, enamel,
  semiconductor and resistor limits).

## Environment (Minecraft layer)
* Ambient air temperature from biome climate: −5 °C (snowy, 0.0) … 20 °C (plains, 0.8) … 40 °C (desert, 2.0).
* Incandescence: block light from parts above the Draper point (798 K); rendered colour from a fit to
  CIE black-body chromaticities.

## Electrochemistry (`chem.Electrochemistry`, `sim.elements.ElectrochemicalCell`)
* An `ELECTROLYTE` part is a body of solution (80 % of its cells by volume); every conductor touching it is
  an electrode. Adjacent electrolytes share an ionic junction (no liquid-junction potential).
* **Electrode kinetics** (Butler–Volmer, every half-reaction in the database at every electrode):
  i = A·i0·[a_red·exp(α_a F η / RT) − (c_ox/c°)·exp(−α_c F η / RT)], η = (V_metal − φ_solution) − E_eq,
  E_eq from Nernst with the current concentrations. The anodic branch exists only where the electrode's own
  metal (or water, for O₂ evolution) can be oxidised. α_c is the transfer coefficient of the overall reaction
  (single-electron rate-determining step: α_c ≈ 0.5, ≈ 118 mV/decade) and α_a = n − α_c by detailed
  balance (Cu²⁺/Cu: α_a ≈ 1.5 measured). The mixed (corrosion) potential of an isolated electrode follows
  from zero net current.
* **Mass transport**: cathodic currents are limited by i_lim = nF·k_m·c·A (k_m = 1e-5 m/s, unstirred).
* **Bookkeeping**: Faraday's law moves moles between metal, solution and deposits; elements are conserved
  exactly; reaction heat (−i·E_eq chemical power, i·η losses, i²R ionic) goes to the thermal nodes.
* **Ionic resistance**: R = L/(κA) per electrode from the solution conductivity (CRC data).
* **Colour** (display): Beer–Lambert, transmitted fraction 10^(−A·c) per R/G/B band for 5 cm path with band
  absorbances estimated from the aqua-complex spectra (Cu²⁺ blue, Ni²⁺ green); plated electrodes take the
  deposit colour as the film reaches ~1e-4 mol/m². Gas evolution shows as bubbles at the electrode.
* **Validity**: dilute-solution activities (a = c), 25 °C reference data with Nernst T dependence, exchange
  currents order-of-magnitude, no passivation, no pH-dependent speciation, uniform current over a face.

## Fluids (`fluids`, `sim.elements.Fluid*`)
* **Domain**: FLUID nodes carry absolute pressure; the through quantity is mass flow (conserved). Each
  control volume pairs a pressure node with a thermal node for its fluid temperature; energy travels with
  the flow as enthalpy on the thermal nodes (the fluid domain is deliberately not power-conjugate). No GMIN
  on fluid nodes (it would leak mass).
* **Working fluids** (`fluids.json`): thermally perfect ideal gases p = ρRT, c_p(T) polynomial fitted to
  Incropera Table A.4 (air ±0.1 %, N₂ ±0.2 %, CO₂ ±0.6 %), u = ∫c_v dT; Sutherland viscosity and
  conductivity. Liquids (water, ISO VG 46 oil): ρ = ρ0[1 + (p − p0)/K − β(T − T0)], Vogel/Andrade viscosity.
* **`FluidVolume`**: d(ρV)/dt = Σṁ_in; d(m·u)/dt = Σṁ_in·h_in + Q̇ (first law for an open system, well mixed).
* **`FluidBranch`** (quasi-steady, ṁ unknown): p_a − p_b = Δp_friction + Δp_minor + ρgΔz − Δp_rotor.
  Darcy–Weisbach with f = 64/Re (Hagen–Poiseuille exactly) and Haaland's fit to Colebrook (±2 %), blended
  over 2000 < Re < 4000; minor losses Kρv|v|/2 (Borda–Carnot at section changes, 0.9 per tee/elbow,
  1.0 at a free outlet); enthalpy upwinded smoothly through ṁ = 0. Kinetic energy and fluid inertia are
  neglected (no acoustics or water hammer; Mach < ~0.3 in pipes).
* **Valves** are orifices of their seat area C_d·A·φ (C_d = 0.6): isentropic nozzle flow for gases,
  choking at the critical pressure ratio (flow independent of downstream pressure below it);
  ṁ = C_d A √(2ρΔp) for liquids. Regularised for |Δp| ≲ 1 Pa.
* **Bladed rotors** (blade-element theory at the mean radius): relative velocity w = (c_x, −U), angle of
  attack α to the chord (stagger β from the axis, handedness from the pitch);
  C_L = sin 2α·(1 + (π − 1)e^{−(sin α/0.25)²}) (2π slope before ~14° stall, flat plate after),
  C_D = C_D0 + 1.8 sin²α over blade area σA (σ = 0.8). Axial force → pressure rise, tangential force →
  shaft torque, shaft power → fluid energy. Lift does no work in the blade frame, so
  shaft power − QΔp = drag·|w| ≥ 0: energy conserved, second law satisfied, all four quadrants (fan,
  turbine, windmill, reverse flow, stall). Swirl leaving one rotor is not carried to the next.
* **Wall heat transfer** (`FluidWallConvection`): h = Nu·k/D_h with Nu = 3.66 (laminar) or Gnielinski
  (Re > 3000), Re from the flow through the volume. No separate natural convection inside vessels.
* **Assembly rules**: each pipe/tank/valve/rotor part is a control volume; volumes sharing a face connect
  (pipes along their axis or sideways as tees, tanks on all faces, rotors along their axis); open axial
  ends vent to an atmosphere reservoir unless the neighbouring cell is solid; a shaft passes through
  fluid parts in a gland (no rigid joint); a rotor's blade row sits on its +axis branch and acts through
  its rigid body. Air pressure follows the isothermal barometric formula with height in the world.

## Pistons and cranks (`SliderCrank`, `PistonCoupling`)
* **Kinematics**: s(ψ) = r + l − (r cos ψ + √(l² − r² sin² ψ)), V = A(c + s), exact for a rigid rod; rod and
  piston masses are neglected (small next to a flywheel), so the coupling is instantaneous.
* **Thermodynamics**: the cylinder is a `FluidVolume` whose volume follows the crank angle; the gas loses
  p·dV/dt as work. The shaft receives τ = (p − p_amb)·dV/dθ (open crankcase); p_amb·dV/dt is work on the
  atmosphere, booked as external power. Exact power balance. Ring friction F_ring·|ds/dθ| (regularised at
  ω = 0) heats the cylinder wall.
* **Inference from parts**: a cylinder's rod leaves its +axis face; following that line, the first part must
  be a crank throw (`ECCENTRIC`) on a rotor whose axis is perpendicular to it and crosses the line. Rod
  length = distance from the head to the crank axis − clearance − throw; TDC phase = the cylinder's angle
  around the crank axis minus the throw's phase. Several cylinders on one throw are phased by placement.
* **Validated**: a fast 9:1 compression stays on the thermally perfect isentrope (Δs < 0.2 J/(kg·K)); an
  alpha Stirling from raw elements and the exhibit built from parts both run on a temperature difference.
* **Limitations**: no piston/rod inertia or side thrust, no blow-by leakage, no valve gear yet (so no steam
  engine or internal-combustion cycle), gas spring forces against an unpressurised crankcase.

## Semiconductors (`Bjt`, `Mosfet`, `Led`)
* **BJT**: Ebers–Moll transport model with the Early effect, I_T = I_S(e^{V_BE/V_T} − e^{V_BC/V_T})(1 − V_BC/V_A);
  I_S(T) ∝ T³e^{−E_g/kT}; PNP by symmetry; parts add C_je = 18 pF, C_jc = 4 pF. No high injection, base
  resistance or breakdown.
* **MOSFET**: EKV charge-sheet interpolation I = I_spec[F((V_P−V_S)/V_T) − F((V_P−V_D)/V_T)](1 + λ|V_DS|),
  F(x) = ln²(1 + e^{x/2}), smooth from subthreshold (slope n·V_T·ln10) to the square law; V_th −2 mV/K,
  mobility ∝ T^−1.5; body tied to source (so a three-terminal device is not drain/source symmetric); parts
  add C_gs, C_gd.
* **LED**: band gap from wavelength; Shockley junction behind 10 Ω series resistance with SPICE p-n junction
  Newton limiting; optical power η·(E_photon/q)·I leaves the network, the rest heats the die.

## Logic chips (`LogicGate`) — the abstraction level above transistors
* Supply pins and an output stage of pull-up/pull-down on-resistances (50 Ω) weighted by an internal state
  s ∈ [0, 1]: output current comes from the supply; dissipation heats the chip.
* Inputs: logic level = sigmoid((V − V_DD/2)/(0.03 V_DD)); unpowered chips read inputs low.
* Delay: τ ds/dt = f(inputs) − s with τ = t_pd (10 ns), using continuous (fuzzy) Boolean functions.
* D flip-flop: master and slave relax in non-overlapping clock windows (below 35 % / above 65 % of the
  clock level) and regenerate toward the nearer rail while holding — rising-edge triggered, race-free,
  no lasting metastability. Schmitt inverter: threshold 0.4/0.6 V_DD switched sharply by its own output.
* **Validated** against the transistor-level NAND (logic levels), the matched CMOS inverter (mid-rail
  threshold) and the analytic Schmitt RC period. Fidelity note: thresholds are those of ratioed 74HC parts;
  a transistor NAND with equal devices switches near 0.65 V_DD.

## NTC thermistor (`Thermistor`)
β-model R(T) = R₂₅·exp(β(1/T − 1/298.15 K)), ±1 % over 0–100 °C for β ≈ 3950 K. Its temperature is its own
thermal node (the part and whatever it is clamped to), and its Joule heat is deposited there — so
self-heating error appears naturally. A RESISTOR part with an `ntc_beta` parameter compiles to this element.

## Water and steam with phase change (`fluids.WaterSteam`, two-phase `FluidVolume`)
* **Properties** from the IAPWS supplementary release on saturation properties (Wagner & Pruß): p_s(T)
  (±0.1 %), ρ'(T), ρ''(T), and h', h'' from the auxiliary α(T) so h_fg = T(v''−v')dp_s/dT holds exactly
  (Clapeyron). Liquid c_p = dh'/dT; liquid conductivity cubic fit to IAPWS 2011 (±0.4 %, 0–300 °C); steam
  viscosity and conductivity Sutherland/power fits (±5–8 %); surface tension IAPWS 1994.
* **State of a volume**: unknowns (p, T, s) with the constraint p = p(T, s). The phase coordinate s runs
  through compressed liquid (s < 0, p = p_s + |s|·K·L), the saturated dome (0 ≤ s ≤ 1, ρ = ρ'^{1−s}ρ''^{s},
  logarithmic so the Jacobian is well conditioned at both the nearly-full and nearly-dry ends) and
  superheated vapour (s > 1, p = p_s·e^{−L(s−1)}, ideal-gas extension of the saturated vapour with
  c_v = 1500 J/(kg·K)), L = ln(ρ'/ρ''). Mass and internal energy are the stored quantities, so boiling and
  condensation are not rules — they are what the balances do when heat or flow moves a volume across the
  dome. **C¹ joins**: dρ/ds is continuous at both dome boundaries by construction; the pressure's slope
  jump is replaced by monotone cubic Hermite joins just outside the dome (1 < s < 1.06, steam superheated
  by ≲ 10 K; −2·10⁻⁵ < s < 0), where the single-phase pressure deviates by a few per cent. Newton's method
  cycles on the raw slope jump when a volume sits on the boundary (dry saturated steam).
* **Ports** (`FluidVolume.Port`): gravity-separated phases; a connection below the liquid level draws
  liquid, above it vapour, mixed within ±5 % of the height (homogeneous flow, McAdams viscosity).
* **Wall heat transfer**: Rohsenow nucleate boiling (C_sf = 0.013) capped smoothly at Zuber's critical heat
  flux; Nusselt laminar film condensation on vapour-covered walls, and film evaporation from wetted walls
  above saturation (continuous through ΔT = 0).
* **Valves**: steam through a seat uses the compressible nozzle relation (γ = 1.3, R = 461.5 J/(kg·K)),
  liquid the incompressible one, mixtures the vapour-mass-weighted blend. All seats turn laminar below
  Δp ≈ 10 Pa (ṁ ∝ Δp, smoothly joined). Valve bodies have a chamber ≥ 40 % of the cell width around the seat.
  **Relief/check valves** (`relief` = cracking pressure): spring-loaded discs passing flow only along +axis,
  lift ∝ excess pressure. **Cam-worked valves**: a valve whose stem lines up with an eccentric on a turning
  shaft opens with it, opening = smoothstep((cos(θ − θ_c) − lap)/(1 − lap)); the eccentric's `phase` sets
  the timing, `lap` the open fraction of the revolution (valve-gear forces neglected).

## Brake pad (`BRAKE` part)
Dry Coulomb friction τ = μ·F·r between the turning body in front of the pad and the stationary frame
(tanh-regularised at 0.01 rad/s), r the contact point's distance from that body's axis, μ the pad
material's friction coefficient, F the spring force. The dissipated power heats the pad: a Prony-brake
dynamometer, a brake, or a constant-torque load.

## MSI chips (`MsiChip`, `CHIP` parts) — counter, register, adder, programmable ROM
Same pin physics as the logic gates (sigmoid input thresholds about V_DD/2, 50 Ω output stages fed from the
supply, 10 ns relaxation, master–slave flip-flops with non-overlapping windows and latch regeneration). The
internal logic is the **multilinear extension** of each Boolean function — exact at every logic corner,
smooth between — evaluated with finite-difference Jacobians in level space:
* COUNTER4 (74HC163): synchronous; CLR̄ low clears, else LOAD̄ low loads P, else EN counts; TC = Q0·Q1·Q2·Q3·EN.
* REGISTER4: loads D while EN is high.
* ADDER4 (74HC283): ripple of full adders, carry = ab + c(a⊕b).
* ROM16X8: word w is selected by Π(a_j or 1 − a_j) over the address bits; contents "w0".."w15" (mask PROM).
A chip is a row of single-block segments (one pin pair each: input on the −lead-axis side, output on the +;
the last segment carries GND/V_DD).

## Schmitt trigger input gain
The Schmitt inverter's input transition is 0.5 % of V_DD wide (gain ≈ 200, as for a 74HC14), so with its
hysteresis feedback the output edge stays fast however slowly the input moves. (At the 3 % width of the
plain gates, an RC oscillator's output sagged for seconds before switching, giving the clocked logic
seconds-long edges in which supply offsets between chips decided what was latched.)

## Rotary viscous damper (bearing `type` 2)
A rotor in a silicone-filled housing: Couette shear across a 0.5 mm gap, torque c·Δω with
c = μ·2π·r³·L/gap (r = 40 % of the cell, L = 80 % of its length, μ the fluid's viscosity, default 10 Pa·s);
no dry friction. It damps without the dead band that Coulomb friction gives (a stalled stepper).

## Insulated wire colours
Insulated wires of different insulation colours lying side by side do not make contact; wires of the same
colour are taken as spliced. Any wire still connects to a component terminal it touches. Bare wire joins
everything it touches.

## Wire runs (reduced-order wiring)
A chain of identical single-cell wires, each touching exactly two others, carries one current through every
cell: the compiler represents it as one electrical node and one thermal mass (summed heat capacity and
surface), with half of the run's total resistance at each end. Series resistance and Joule heat are exact;
only the temperature profile along the run is lumped.

## Integration method per island
Islands that contain switching electronics (CHIP, LOGIC, TRANSISTOR) integrate with L-stable BDF2: their
sub-nanosecond modes (input capacitance against output resistance) are far below the step size, and the
trapezoidal rule rings on them (amplification → −1), forcing tiny steps. Other islands keep the trapezoidal
rule, which conserves the energy of mechanical and electromagnetic oscillations. A time step that collapses
below 10⁻¹⁰ s (a Newton stall at round-off level) gets one rescue: a 1 µs backward-Euler step with Newton
tolerances relaxed 100×, accepted only if it converges, and counted (`TransientSolver.rescuedSteps`).

## Structural strength (`structure.FrameSolver`, `assembly.StructuralAnalysis`)
* **Model**: the stationary structure is a 3D frame solved by the direct stiffness method. Every structural
  part of an anchored frame is a node with six degrees of freedom; every face contact between two of them is an
  Euler–Bernoulli beam member (axial EA/L, two bending planes 12EI/L³…, St Venant torsion GJ/L) whose
  cross-section is the contact face — a plate's thickness (`fill`) sets its section, so thin plates are weak.
  The two halves of a member are in series (harmonic-mean E). Parts resting on the ground are clamped.
* **Loads**: gravity on every part, small parts carried by the frame, the weight of turning bodies shared
  between the bearings that hold them, and fluid held in vessels. Recomputed once per simulated second.
* **Strength**: extreme-fibre stresses σ = N/A ± M₁c₂/I₁ ± M₂c₁/I₂ plus shear from V and T at each contact
  face. Ductile materials (yield strength known): von Mises against yield. Brittle ones (UTS only — glass,
  ceramics, stone): largest tension against UTS (Rankine), compression allowed 8× more. Utilisation > 1 is
  failure: the part breaks and drops, and whatever is left with no load path to the ground falls with it
  (progressive collapse).
* **Validated** against the cantilever (δ = PL³/3EI, θ = PL²/2EI, M = PL), the simply supported beam
  (PL³/48EI), axial shortening (PL/EA), bending about both principal axes, and portal-frame sway (24EI/h³);
  the shelves exhibit's root stress against σ = 6M/(bt²).

## Light: photodiodes, optical links, daylight (`Photodiode`, `AssemblyCompiler.buildOptics`)
* **Photodiode / solar cell**: Shockley junction (I_s 10 fA per mm²) with photocurrent R(λ)·P_in flowing
  cathode → anode inside it; silicon responsivity R = η_q·λ/1240 nm, η_q 0.8 over 550–950 nm, falling in the
  blue and to zero past the 1107 nm band edge. Photovoltaic mode follows (V_oc = V_T ln(I_ph/I_s + 1)); large
  area makes a solar cell. Absorbed light is external power in; what is not delivered heats the part.
* **Optical links**: every LED–photodiode pair within 16 cells is coupled by radiant intensity × projected solid
  angle: (m+1)/2π·cos^m θ_s · A·cos θ_r/d² (generalised Lambertian, m = "beam"), times visibility along five rays
  sampled every 0.01 cell. Opaque parts block; glass, liquids, wires and thin shafts pass; a plate blocks only
  within its thickness. Parts on a turning body are tested in that body's frame, giving a 360-entry visibility
  table over its angle (a vane or slotted disk chopping the beam). The photocurrent's Jacobian couples to the LED's
  junction unknown and to the rotor angle.
* **Daylight**: 1000 W/m² × cos(zenith) × (1 − 0.75·rain − 0.15·thunder) along the sun's direction from the time
  of day, on photodiodes whose face turns to the sun and whose block sees the sky; ~0.5 A/W for sunlight on silicon.

## Control and robotics parts (`GravityTorque`, `Potentiometer`, `OpAmp`)
* **Gravity on rotating bodies**: a body turning about a non-vertical axis whose centre of mass is off the axis
  feels τ = n·(r(θ) × m g), r(θ) the centre-of-mass offset rotated by θ (Rodrigues). It derives from U = −m g·r(θ),
  reported as stored energy, so pendulums conserve energy and levers, unbalanced wheels and robot arms carry
  their weight. Vertical axes feel none (gravity is parallel to them).
* **Potentiometer**: a resistive track R between ends A and B with a wiper at fraction f = ½ + φ/travel of it
  (R_AW = R·f + 1 Ω, R_WB = R·(1 − f) + 1 Ω), φ the angle of the shaft through it relative to its housing;
  f is smoothly clamped at the track ends. End stops 15° past them (300° of rotation) push back with a C¹ spring
  (quadratic over the first degree, then linear; ≈ 20 N·m/rad × (size/25 mm)³) and contact damping. The housing
  is a journal for the shaft, with wiper/bushing drag 2 mN·m × (size/25 mm)³. Joule and stop heat go to the part.
* **Op-amp**: single-pole, slew-limited, rail-clamped. Internal voltage s (about mid-supply):
  τ·ds/dt = τ·SR·tanh(A₀v_d/(τ·SR)) − s − τ·K·c(s), A₀ = 10⁵, τ = A₀/(2π·GBW), SR the slew rate; c(s) is a
  soft clamp (10 mV knee, K = 10⁹ s⁻¹) holding s within the supply span less 0.1 V. Small signals see the
  ideal single pole (closed-loop bandwidth GBW/gain), large steps slew at SR, saturation recovers without wind-up.
  The output is s behind 75 Ω; sourced current is drawn from V₊, sunk current returned to V₋, plus 0.5 mA
  quiescent; all dissipation heats the part. Inputs draw no current (3 pF each).

## Instruments: data logger / oscilloscope (`DataLogger`, `CompiledAssembly.attachLogger`)
* Fed the probed quantities of one part (temperature, current, voltage, power, speed, angle, fluid pressure,
  fluid temperature, mass flow) after every accepted solver step; resampled onto a fixed 1 kHz clock of simulated
  time by linear interpolation between steps; stored as 32-bit floats in a 65 536-sample ring buffer (≈ 65 s).
  Bandwidth is half the sample rate: faster phenomena the solver resolves alias, as on a real instrument.
* Display: peak-detect envelope (min/max per column) of one channel over 0.1/1/10/60 s, auto-ranged. Export:
  `/emergence log export` writes CSV (time plus every channel, empty fields where a part lacks a quantity).
* A recompiled island restarts its clock; the logger rebases so its record runs on without a jump.

## Gas-phase chemistry (`Species`, `Reaction`, `Chemistry`, `GasReactor`)
* **Substances** (`substances.json`): formula (element counts → molar mass), ΔH_f°, S°, and C_p at 298.15, 1000
  and 2000 K (NIST-JANAF). C_p(T) = a + bT + cT² through those points, held constant outside 298.15–3000 K;
  h = ΔH_f° + ∫C_p dT, s° = S° + ∫C_p/T dT and g° = h − Ts° are integrated analytically, so equilibrium constants
  obey van 't Hoff exactly. Ideal gases: u = h − RT, c_v = c_p − R.
* **Reactions** (`reactions.json`): stoichiometry checked for element balance on load; forward rate
  r_f = A·T^b·e^{−Ea/RT}·Π c_i^{order_i} [mol/(m³·s)]; reversible reactions run backwards at
  r_f(products)/K_c, K_c = K_p·(P°/RT)^{Δν}, K_p = e^{−ΔG°/RT}, so the net rate vanishes at equilibrium (detailed
  balance). N₂O₄ ⇌ 2NO₂ (elementary, reversible); CH₄ + 2O₂ → CO₂ + 2H₂O (Westbrook–Dryer global step,
  [CH₄]^0.2[O₂]^1.3, irreversible).
* **Reacting gas volume** (`GasReactor`): a closed, well-mixed volume; unknowns are the moles of each species and the
  gas temperature (a thermal node — heaters, walls and coolers attach with ordinary thermal elements). Balances:
  dn_i/dt = V·Σν_ir·r_r and dU/dt = ΣQ̇_in with U = Σn_i·u_i(T) including formation energies, so reaction heat is
  a change of composition at constant energy (no source term) and energy and elements are conserved to solver
  tolerance. p = ΣnRT/V. Concentrations enter rate laws softly bounded at zero; a fractional order < 1 is multiplied
  by c/(c + 10⁻⁴ mol/m³) so a global law shuts off at trace level instead of driving a species negative.
* **Ignition** is homogeneous: the bulk runs away when heat release outgrows heat loss (Semenov thermal
  explosion). Flame fronts, dissociation (adiabatic temperatures come out ~100–200 K high at 2500 K+), condensation
  of water and intermediate species are not modelled.
* **Gas vessels in assemblies**: a TANK with "x_<species>" mole fractions (charge pressure "pressure" at its
  starting temperature) holds a `GasReactor` with the charged species plus every product the database's reactions
  can make from them. It is sealed (no pipe connections yet). The gas exchanges heat with the vessel wall by
  natural convection (h ≈ 10 W/m²K over the wetted area) and gas radiation (ε ≈ 0.05). Seen through glass, the
  gas colour is Beer–Lambert transmission over the vessel width with NO₂'s absorption cross-sections (Vandaele
  et al. 1998) at 610/545/465 nm.
