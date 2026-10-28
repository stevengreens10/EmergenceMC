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
* **Datums**: nothing a player builds connects to the electrical reference, so every circuit floats. The solver
  grounds one node of each galvanically connected circuit through 1 S. By Kirchhoff nothing flows there but the
  circuit's share of the GMIN shunts, so no current or stored charge changes. Without it, the circuit's absolute
  potential was held only by those shunts: a direction of the matrix 10¹² times softer than the rest, along which
  Newton's updates were noise (a PWM motor's whole ground net drifting volts an iteration). Circuits are found from
  the Jacobian's pattern, without thermal nodes. Every real coupling has an entry (a contact that closes adds one
  when it does), so circuits can only be over-joined, never split: one circuit never gets two datums.

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
| `CoulombFriction` | Dahl/LuGre bristles: dτ_b/dt = (τ_c·Δω − |Δω|·τ_b)/θ₀, τ = τ_b + (θ₀/ω_s)·dτ_b/dt; θ₀ = ω_s·1 ms, |Δω| as √(Δω² + (10⁻⁴ω_s)²) — a held load stays held, sliding saturates at τ_c (`frictionHoldsALoadBelowItsLimitAndBreaksAwayAboveIt`) |
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
  static body, a coaxial rotor or a slide become rotors (on a slide: carried by it); groups held only by parallel
  linear bearings on the frame or on one rotor become slides (on a rotor: carried by it); one level of carrying
  (no slide on a rotor on a slide); free-standing machines use their heaviest group as frame; over-constrained
  groups lock (with a warning).
* **Electrical contact**: wires connect on all faces (insulated wire only to wires/leads); components have
  leads at their axial ends (end-face centre, plus side faces of end layers when ≥ 2 cells long; a
  single-cell component also brings its − and + leads out on the −L and +L side faces of its lead axis L,
  parameter `lead_axis`, as on a real coil former); bulk metal
  conducts; only brushes conduct across a moving joint. Contacts are split at the face so each half's
  Joule heat goes to its own part: one element per contact, the two halves R_p(T_p) + R_q(T_q) in series, each half's
  I²R into its part, the Seebeck EMF of each half and the junction's Peltier heat (S_p − S_q)·T_c·I at the contact
  temperature, exactly as two conductors meeting at a contact node, without the node (`ContactConductor`).
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
  **Cavitation**: below the vapour pressure p_v(T) (Antoine; water 3.17 kPa at 25 °C) a vapour cavity opens rather
  than the liquid taking tension: ρ = ρ_l·e^{−φ}, φ = ¼·ln(1 + e^{4(p_v − p)/B_c}), B_c = 0.2·max(p_v, 500 Pa) — the
  pressure falls only B_c per e-fold of liquid lost, so it stays near p_v while the void grows, and the void
  refills when the pressure returns. Cavity vapour mass and latent heat neglected (ρ_v/ρ_l ~ 10⁻⁵).
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
  its rigid body. Air pressure follows the isothermal barometric formula with height in the world. Each vent
  sees the outside air at its own height, p_atm − ρ_air·g·(z − z_ref), so an idle duct at ambient temperature
  open at two heights holds still and a duct of warm air draws as a chimney does.

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
  jump is replaced by monotone cubic Hermite joins: across the dry edge (0.97 < s < 1.03, interpolating ρ, p and
  the specific energy u — not ρu) and just outside the full edge (−2·10⁻⁵ < s < 0), where the states deviate by a few
  per cent. Newton's method cycles on the raw slope jump when a volume sits on the boundary (dry saturated steam).
  The dry join first lay wholly outside the dome (1 < s < 1.06) and interpolated ρu: leaving the edge with the
  dome's steep slope of u, it overshot — u rose ~110 kJ/kg above the saturated vapour's and fell back — so a
  volume's mass and energy had two states, and low-pressure steam (a condensing engine's cylinder) two-cycled Newton
  ~50 000 times in five seconds. Straddling the edge, the secant slope is about half the steep one, inside the
  cubic's monotone range (α² + β² ≤ 9), and u rises monotonically into the superheated region.
* **Ports** (`FluidVolume.Port`): gravity-separated phases; a connection below the liquid level draws
  liquid, above it vapour, mixed within ±5 % of the height (homogeneous flow, McAdams viscosity). A volume's
  pressure is that over its liquid: a port under the level also feels the liquid standing over it,
  ρ_l·g·H·(level − z) (H the depth liquid can stand: a vessel between its walls, an upright tube's length, a lying
  tube's bore; the positive part rounded over 1 % of H), and the column between two ports is taken between their
  own heights, z_b − z_a. Two vessels side by side join through the upper and lower halves of their common face,
  each drawing at its own height: steam crosses over the top while water returns underneath (stratified,
  counter-current flow; one passage at mid-height passes one phase at a time). Before both, water did not find its
  level between vessels side by side at all, and a boiler of three tanks over a fire starved its two heated cells
  while the third, its steam riser's, ran full and sent water into the engine.
* **Wall heat transfer**: Rohsenow nucleate boiling (C_sf = 0.013) capped smoothly at Zuber's critical heat
  flux; Nusselt laminar film condensation on vapour-covered walls, and film evaporation from wetted walls
  above saturation (continuous through ΔT = 0).
* **Valves**: steam through a seat uses the compressible nozzle relation (γ = 1.3, R = 461.5 J/(kg·K)),
  liquid the incompressible one, mixtures the vapour-mass-weighted blend. All seats turn laminar below
  Δp ≈ 10 Pa (ṁ ∝ Δp, smoothly joined). Valve bodies have a chamber ≥ 40 % of the cell width around the seat.
  **Relief/check valves** (`relief` = cracking pressure): spring-loaded discs passing flow only along +axis,
  lift ∝ excess pressure. **Cam-worked valves**: a valve whose stem lines up with an eccentric on a turning
  shaft opens with it, opening = smoothstep((cos(θ − θ_c) − lap)/(1 − lap)); the eccentric's `phase` sets
  the timing, `lap` the open fraction of the revolution (valve-gear forces neglected). **Slide-worked (plug)
  valves**: a valve with a rod on a slide pointing into its side (the first part looking out across its axis, through
  up to 12 empty cells, riding a slider along that line) has its plug on the rod. Pushed in to the stroke's end nearest
  the valve the plug is seated; drawn back it opens as smoothstep(travel / `plug_travel`), over the whole stroke unless
  `plug_travel` is shorter. In steam, water, air and gas-mixture passages alike. The plug is balanced: the fluid's force
  on it is not modelled, so no work passes between flow and slide. A throttle any moving thing can work: a hand, a
  governor's sleeve, a float.

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
* RAM16X4 (function 4): 64 master–slave flip-flops, synchronous write — on a rising CLK each bit's data target is
  WE·sel_w·D_k + (1 − WE·sel_w)·q (the word selected by the same multilinear decode), so only the addressed word
  takes the data; Q_k = Σ_w sel_w·q_{w,k} reads the addressed word continuously. Chips' stored bits (counters,
  registers, memory) are saved with the build's state and restored into both latches when it is recompiled.
A chip is a row of single-block segments (one pin pair each: input on the −lead-axis side, output on the +;
the last segment carries GND/V_DD).

## Schmitt trigger input gain
The Schmitt inverter's input transition is 0.5 % of V_DD wide (gain ≈ 200, as for a 74HC14), so with its
hysteresis feedback the output edge stays fast however slowly the input moves. (At the 3 % width of the
plain gates, an RC oscillator's output sagged for seconds before switching, giving the clocked logic
seconds-long edges in which supply offsets between chips decided what was latched.)

The threshold in force is a discrete state, not a function of the output. After every accepted step the trigger reads
its input (as a fraction of its supply) and predicts by secant where it will reach the threshold; the solver steps onto
that time, and when the input is within 10⁻⁴ of it the threshold flips (0.6 ↔ 0.4) as an event and the integration
restarts. Between flips the output heads for the state the last crossing set, whatever the input does, and slews
there through the output stage with the propagation delay. That is an ideal Schmitt trigger: its internal
regeneration is taken as instant against the output stage. The continuous form, a threshold that folded with the
output through a 5 %-wide sigmoid, cost Newton dozens of failures an edge. On the inverter it cost 59.5 s of CPU for
10 s simulated, with 26 700 Newton failures; it now costs 3.0 s with none. Its 470 kΩ / 1 µF oscillator ran at 2.77 Hz
and now runs at 2.70 Hz, against 2.62 Hz for the ideal 2RC·ln 1.5. The gate's input stage is not used once the
threshold is known. Kept in the first form, it began switching the output as the input neared the threshold, the
timing capacitor stalled just short of it, and the gate sat as an amplifier creeping there: 1.2 Hz and 450 s of CPU.

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
  of day, traced through the assembly (see Ray optics); silicon's responsivity to it is its response to a 5778 K
  black body, 0.243 A/W with the model's 0.8 quantum efficiency.
* **Incandescence** (`Photodiode.addHotSource`, `AssemblyCompiler.buildIncandescence`): every part within 16 cells
  in front of a photodiode is a grey Lambertian radiator; the radiation reaching the window, net of what the window
  sends back at the surroundings' temperature, is g·σ(T⁴ − T_a⁴) with g = ε·(A_s/4)·A·cos θ_r/(π d²) × visibility (a
  convex body's mean projected area is a quarter of its surface — Cauchy); the photocurrent is g·(f(T) − f(T_a)),
  f(T) = ∫R(λ)·πB(λ, T)dλ, Planck's spectrum weighted by silicon's responsivity (tabulated every 10 K to 6000 K; its
  Jacobian couples the photodiode to the body's temperature). Warm bodies' infrared lies beyond silicon's band:
  f(300 K)/σT⁴ ≈ 2·10⁻¹⁶ A/W; a red-hot body (1000 K) 3·10⁻⁴; illuminant A (2856 K) 0.133. Pairs that could not
  drive 0.1 nA even at the body's failure temperature are dropped.
* **Hot parts seen through optics** (`Photodiode.addSpectralHotSource`, `AssemblyCompiler.buildSpectralIncandescence`):
  conductors in a circuit that survive 1200 K (filaments, heating elements), when mirrors, lenses or glass are within
  16 cells, are traced as isotropic point sources (two opposite hemispheres of uniform intensity, each carrying half)
  in 18 bands of 40 nm from 380 to 1100 nm, each at its centre wavelength — so a prism sends each band its own way.
  Band b delivers g_b·(F_b(T) − F_b(T_a)) [W] with g_b = ε·A_s × (the fraction of the source's light the tubes bring
  to the window), F_b(T) = ∫_b πB dλ (tabulated every 10 K) and the photocurrent g_b·∫_b R·πB dλ. Direct paths stay
  with the grey calculation above. Display rays: the central ray through the nearest optic in seven colours, drawn
  as bright as B(λ, T)/B(λ, 2800 K).

## Control and robotics parts (`GravityTorque`, `Potentiometer`, `OpAmp`)
* **Gravity on rotating bodies**: a body turning about a non-vertical axis whose centre of mass is off the axis
  feels τ = n·(r(θ) × m g), r(θ) the centre-of-mass offset rotated by θ (Rodrigues). It derives from U = −m g·r(θ),
  reported as stored energy, so pendulums conserve energy and levers, unbalanced wheels and robot arms carry
  their weight. Vertical axes feel none (gravity is parallel to them).
* **Potentiometer**: a resistive track R between ends A and B with a wiper at fraction f = f₀ + φ/travel of it
  (f₀ = ½ unless fitted elsewhere: a rheostat starting at zero; R_AW = R·f + r₀, R_WB = R·(1 − f) + r₀, contact
  resistance r₀ = 1 Ω or 0.2 % of R if less), φ the angle of the shaft through it relative to its housing;
  f is smoothly clamped at the track ends. End stops 15° past them push back with a C¹ spring
  (quadratic over the first degree, then linear; ≈ 20 N·m/rad × (size/25 mm)³) and contact damping. The housing
  is a journal for the shaft, with wiper/bushing drag 2 mN·m × (size/25 mm)³. Joule and stop heat go to the part.
* **Op-amp**: single-pole, slew-limited, rail-clamped. Internal voltage s (about mid-supply):
  τ·ds/dt = τ·SR·tanh(A₀v_d/(τ·SR)) − s − τ·K·c(s), A₀ = 10⁵, τ = A₀/(2π·GBW), SR the slew rate; c(s) is a
  soft clamp (10 mV knee, K = 10⁹ s⁻¹) holding s within the supply span less 0.1 V. Small signals see the
  ideal single pole (closed-loop bandwidth GBW/gain), large steps slew at SR, saturation recovers without wind-up.
  The output is s behind 75 Ω; sourced current is drawn from V₊, sunk current returned to V₋, plus 0.5 mA
  quiescent; all dissipation heats the part. Inputs draw no current (3 pF each). The truncation-error tolerance on
  s is 10 mV absolute (0.1 % of a 12 V swing) plus the solver's relative tolerance: microvolt Newton residuals at the
  inputs, multiplied by A₀/τ ≈ 2·10⁷ s⁻¹, are noise in ds/dt, and a tighter tolerance let that noise set the step size
  of every closed loop (an output resting at mid-supply has s ≈ 0, so the relative part does not help there).

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

## Flowing gas mixtures (`MixtureBranch`, mixture networks)
* A `GasReactor` may carry a pressure unknown (p·V = ΣnRT) and a piston-driven volume V(θ); `PistonCoupling` then
  takes p·dV/dt from its energy and turns the crank, as for single fluids.
* `MixtureBranch`: molar flow ṅ driven by Δp against K·ṁ|ṁ|/(2ρA²) + 32μLṁ/(ρAD²) (ρ, M, T, composition upstream,
  blended smoothly through ṅ = 0), plus the weight of the gas standing in the passage, ½(ρ_a + ρ_b)·gΔz (a hot
  chimney draws; the mean of the ends', not the upwind density — with heavy gas above light an upwind head has no
  consistent flow near zero and Newton chatters), and choking (the
  downstream pressure felt is never below the critical ratio (2/(γ+1))^{γ/(γ−1)} of the upstream; with K ≈ 2 an
  orifice reproduces the isentropic choked mass flow within 0.5 %). It carries species ṅ·x_i and enthalpy ṅ·h(T_up)
  (formation enthalpy included); flow to or from a reservoir is the system's external power. What it carries is split
  smoothly into the flow's two ways, ṅ = ṅ⁺ − ṅ⁻ (width 10⁻⁶ mol/s for a 6 mm bore or wider, about what diffusion
  exchanges through it, and in proportion to the open area below that, so a shut valve exchanges next to nothing),
  each carrying only its own end's gas at that end's temperature: a volume never sends what it does not hold. (The
  momentum balance keeps its blended properties.) Carrying the blend instead let a trickle out of a pipe holding no
  vapour take its neighbour's: the pipe's moles went negative, then its energy, and the solver failed.
* In builds, every fluid part connected to a gas-charged one joins one mixture network over a shared species list
  (charged species, air, reaction products); uncharged parts hold air; open ends breathe the atmosphere at the
  hydrostatic pressure of their height; valves scale their passage.
* **Wall film (hot-surface ignition)**: in mixture networks each gas also reacts in a 2 mm thermal boundary layer
  at its wall's temperature (concentrations scaled by T/T_w at equal pressure, over A·δ); the converted species
  belong to the bulk, so the heat appears there and energy stays exact. A hot wall thus ignites a mixture the bulk
  temperature alone would not; at a wall no hotter than the gas it only adds the film's share of the rate.
* **Valves in mixture networks** have one seat (their bore) on the +axis face; their other faces open into the body.

## Thermoelectricity (`Conductor.withSeebeck`)
* Each conductor half-segment between a part's centre (its temperature) and a contact (at the conductance-weighted
  mean of the two parts' temperatures) obeys I·R = V_a − V_b − S·(T_b − T_a) and carries Peltier heat S·T·I from
  end a to end b; Joule heat stays in the part. Around a loop the EMFs sum to ∮S dT, so any two dissimilar
  conductors at different temperatures form a thermocouple, and a current pumps heat across a junction; the EMF's
  work equals the heat moved, so energy is conserved. S is the absolute Seebeck coefficient, constant (no Thomson
  heat). Applied wherever two bulk or wire conductors touch (component leads, all copper, are not split).

## Radioactivity and detection (`RadioactiveSource`, `GeigerTube`, `NuclearData`, `EventSource`)
* **Decay**: atoms of a nuclide and its descendants obey the Bateman equations (ENSDF half-lives and branchings);
  short-lived daughters start in secular equilibrium. Alpha and mean beta energy stays in the source as heat
  (nuclear energy entering the system, counted as external power); gamma rays leave.
* **Gamma transport**: straight rays from the source's centre to each detector; per photon, the probability of
  reaching and interacting is A·ε(E)·e^{−Σμℓ}/(4πd²) with NIST XCOM mass attenuation coefficients (log–log in energy)
  for the parts the ray crosses (sampled every 0.01 cell: plates only within their thickness, other parts with their
  mass spread over their cells) and the tube's intrinsic efficiency ε(E). Only gammas (no betas, no scatter build-up).
* **Geiger–Müller tube**: interactions are a Poisson process (seeded random stream) of rate background (0.3 /s) +
  Σ activity × coupling. A hit starts a discharge only if the anode is above the starting voltage (350 V); the
  discharge holds the tube near its sustaining voltage (300 V) passing I = (V − V_ext)/100 kΩ and dies when its
  current falls below the holding current (100 µA) — so the anode resistor quenches it, and too small a resistor
  lets it run on. Hits during recovery are lost: dead time follows from the circuit.
* **Events in the solver**: elements implementing `EventSource` declare their next discontinuity; the solver lands a
  step exactly on it, lets the element switch, and restarts the integration there.

## Slides and springs (prismatic joints, `PositionSpring`, `TravelStops`)
* A shaft through **linear bearings** on the frame makes its rigid group a slide: one translational degree of freedom
  along the shaft axis (mass on a velocity node, displacement tracked). Its travel ends where a bearing reaches the
  end of the shaft through it (stiff C¹ stops with contact damping). Gravity acts along the guide (m·g·axis), ball
  bushings add rolling friction μ ≈ 0.003 of the transverse load and grease drag.
* **Helical springs** join the bodies touching their end faces: k = G·d⁴/(8·D³·N) (wire d, coil diameter D, N
  active turns); the force acts on the ends' tracked positions (F = −k·e, energy ½k·e²), so a reloaded machine
  resumes with the force its positions imply. A spring fitted δ shorter than its free length ("preload") starts from
  e = −δ: it presses its ends apart with kδ where they were placed — a governor's spring setting its lift-off speed.
  The spring's own mass is neglected.
* **Link bars** (`RigidLink`, part LINK; a straight run of link cells is one bar): pinned at its end faces' centres to
  the bodies touching them — fixed, turning about a fixed axis (pin at P(θ) = p + R(n, θ)(P₀ − p)) or sliding
  (P(x) = P₀ + x·a), all in one frame (the ground, or a turning body or slide carrying both). The holonomic constraint
  |P_A − P_B| = L is enforced at velocity level with Baumgarte stabilisation, ċ + c/τ = 0 (τ = 10 ms), by a
  Lagrange multiplier λ, the bar's tension: generalised forces Q_A = −λ d̂·∂P_A/∂q_A, Q_B = +λ d̂·∂P_B/∂q_B. The bar
  does no work (its power −λċ = λc/τ vanishes with the drift), so it passes power exactly from one body to the
  other. Pins are frictionless; the bar's own mass is carried by the frame it is drawn in, not by its ends. A
  mechanism started with inconsistent speeds takes the impulse of an inelastic collision in its first step (the
  ledger books that loss as error; exhibits start consistently). Link cells laid face to face make one bar, a path
  whose two outer faces are the pins: a bar can be built at a slant. A sleeve sliding along the axis of the spindle
  that carries it, pinned on that axis, moves only along the axis however the spindle turns, so a bar from it may
  reach a body off the spindle (its pin rides in a groove: a thrust collar; the force on it has no moment about the
  axis).
* **Stop collars** on a linear bearing's shaft: "travel" either way, or "travel_fwd"/"travel_back" on one side (0: a
  collar against the bushing, which a preloaded spring can hold the slide against).
* **Magnetics on slides**: couplings between a slide and the frame (coil flux linkage, mutual inductance, magnet
  energy) are tabulated over the travel (plus 10 %) and represented by a natural cubic spline; force = i·dΨ/dx etc.
* **Pushing**: a hand on a slide follows the same force–speed–power limits as on a crank (200 N at rest, 3 m/s).
* **Rams**: a cylinder whose rod side touches a slide moving along its bore has a straight-rod piston
  (`LinearPiston`): s = s₀ + sign·x, V = A·(c + s); the slide's travel is also stopped where the piston meets the
  head or the rod end. `PistonCoupling` passes (p − p_atm)·A to the slide, as it passes torque to a crank.
* **Bevel gears** (`GearMesh.bevel`): two gears on perpendicular axes that cross mesh where each one's pitch circle
  passes through the other's: gear 2's centre r₁ from gear 1's along gear 2's axis and r₂ along gear 1's. Their pitch
  points roll together at the contact, so r₁ω₁ + s·r₂ω₂ = 0, with the sign from which way each turns past it. Same
  constraint, lossless transmission and Buckingham tooth friction as a spur pair; the cones' axial thrust is not
  modelled. A right-angle drive: a governor's upright spindle off a crankshaft, a differential.
* **Rack and pinion** (`GearMesh.rackAndPinion`, part RACK): a spur gear whose pitch circle touches a rack's toothed
  face (same module) constrains r_p·ω + s·v = 0 (Lagrange multiplier = tooth force; s = ±1 from the geometry), lossless
  but for tooth sliding friction f ≈ μπ/z (Buckingham, the rack's 1/z = 0). The rack's slide is stopped before the
  pinion would leave the run of rack segments.
* **Lead screw** (`GearMesh.leadScrew`, part NUT on a SHAFT): (l/2π)·ω + s·v = 0 for lead l (right- or left-hand). The
  axial force F presses the trapezoidal (30°) flanks with N ≈ |F|/(cos α cos λ) at sliding speed ω·r_m/cos λ, so the
  friction torque is μ'|F| r_m/cos²λ (μ' = μ/cos α; mean diameter d_m = d − ½·min(l, d/4)); efficiency
  η = sin λ cos λ/(sin λ cos λ + μ'), within 1 % of the exact tan λ(1 − μ' tan λ)/(tan λ + μ') for μ' ≲ 0.2. Back-driven,
  the Coulomb friction (tanh-regularised) holds the load when μ' > tan λ: self-locking emerges for fine leads. μ: 0.13
  oiled steel on bronze (Shigley Table 8-5), dry the nut material's coefficient.
* **Pressure vessels burst** (`CompiledAssembly.burstUtilisation`): every fluid- or gas-holding part's peak wall
  stress per pascal of over-pressure — tubes (pipes, valves, cylinders) by Lamé's thick-wall hoop stress
  Δp·(r_o² + r_i²)/(r_o² − r_i²) (→ Barlow's Δp·r/t when thin); box tanks by their flat walls bending as plates with
  fixed edges under uniform pressure, σ = β(a/b)·Δp·(b/t)² at the middle of the long edge (Roark Table 11.4 case 8,
  β = 0.3078 square … 0.5 long). A part whose stress reaches its material's ultimate tensile strength bursts (it is
  removed; its contents escape through the opening).
* **Ferromagnetic cores** (`em.ReluctanceLattice`): a magnetic equivalent circuit on the cell lattice. Each cell is a
  node of magnetic scalar potential; neighbours are joined by the permeance of the two half-cells in series,
  μ0·h·2μa·μb/(μa + μb); a coil drives N·i across a face inside its winding and links N times the flux through it;
  flux is conserved at every node and no flux leaves the box (iron within 6 cells of a coil, 3 cells of air round it).
  Unit currents give the inductance matrix; solved with the iron and with air, the difference is added to the coils'
  analytic air inductances (so air-cored behaviour is unchanged and the coarse lattice only supplies what the iron
  adds). Parts with μr ≥ 2 count (electrical steel 5000, iron 2000, nickel 300, structural steel 100; a plate by its
  fill, a shaft by its share of the cell); a coil with `core` = 1 has electrical steel in its bore (μ weighted by
  the bore's share of the cell) and its leads leave at the sides. Coils and iron on the same rigid body only.
* **Saturation**: the iron's inductance ΔL is split into eigenmodes, ΔL = Σ μ_k u_k u_kᵀ (for a closed core,
  essentially one: the core flux); each mode's flux linkage follows ψ_k = ψ_s·tanh(μ_k m_k/ψ_s), m_k = u_k·i, with
  ψ_s = μ_k/r_k where r_k is the lattice's peak |B|/B_sat per ampere of the pattern (a part-filled cell — a shaft,
  a thin plate, a coil's core — saturating at fill·B_sat, its iron carrying the cell's mean flux alone). The coils'
  linkage is the gradient of the co-energy Σ ∫ψ_k dm: dλ/di symmetric, field energy
  Σ (m ψ − (ψ_s²/μ) ln cosh(μm/ψ_s)) exact.
* **Hysteresis** (iron with a coercive field H_c: electrical steel 40 A/m, iron 80, nickel 100, 1045 steel 800): the
  dominant mode's flux follows a state p that lags its magnetising current m by at most w (a play operator — domain
  walls pinned until the field moves them by H_c), ψ = ψ_s·tanh(μp/ψ_s). w is the iron's coercive mmf per turn,
  ∮H_c dl/N = Σ H_c·V·|B| / μ_k over the lattice's iron cells (B, μ_k per ampere of the pattern); at the coercive point
  the flux is zero, so an air gap takes none of it — a gapped core's loop is sheared, its remanence small. The
  electrical work m·dψ splits exactly into the stored energy's change (the same ln cosh expression in p) and
  (m − p)·ψ'(p)·ṗ ≥ 0, the loss — the loop's area 2w·Δψ per cycle — which heats the iron (computed with the
  integrator's own ṗ). The play's corners are rounded over ±2 % of w (flat inside, or the remanence would creep away);
  p relaxes to it with τ = 1 µs (at 0.1 ms a saturating core's p lagged far behind and lost several times its loop).
  Only each body's dominant mode is hysteretic (the minor modes correct the same iron's linkage; a loop on each
  counted its loss again), and not a core that pulls an armature (the pull is ½i²·dL/dx, from the current: a core
  holding remanence as the current died let its relay drop early). Not modelled: the loop's real shape (a
  parallelogram in m), minor-loop accommodation, rate-dependent eddy losses in the iron.
* **Inconsistent coil currents on restart**: if the first step of a newly compiled assembly fails, the restored coil
  currents could not flow in the circuit as built (its loop was broken — a chunk loaded without the rest of the
  circuit); they start from rest, everything else kept, as an inductor's current collapses when its circuit opens.
* **Buckling** (`FrameSolver.bucklingFactor`): linear elastic stability of the loaded static frame,
  (K + K_G/μ)·φ = 0, with each member's consistent geometric stiffness from its axial force (Przemieniecki's cubic
  beam element: 6P/5L, P/10, 2PL/15, −PL/30). μ is the loads' fraction of the critical load; at μ ≥ 1 the structure
  buckles and fails where the buckled shape deflects most. Inverse power iteration on K⁻¹(−K_G), shifted past tension
  modes; skipped when every compressed member is far from even a 20-member column's Euler load. Elastic (no
  imperfections or plasticity interaction: stocky members fail by the strength check first).
* **Glow plugs** (`exposed` = 1 on a heating element): an element set into a vessel meets its gas directly — a
  reacting film at the element's temperature (as a wall film) and convection to the bulk. Rates rise with pressure,
  so a small hot surface lights a charge as it is squeezed rather than as it is drawn in.
* **Numerics for fast burns**: a rate order p < 1 (methane's −0.3) is cut off at trace level by (c/(c + c_cut))^(1−p),
  linear in c with a finite slope; a Newton update that would take the last of a species just below zero takes it to a
  tenth of what is left; a gas passage reports the noise floor of its flow (a few ulps of its pressure over the flow
  equation's slope), below which Newton is not asked to converge; its flow column is a central difference. The sparse
  LU keeps its pivot order while no pivot collapses below 10⁻⁸ of its value when the order was chosen (it was a row
  test, which rejected sound pivots in rows mixing units and forced a full refactorisation at nearly every iteration).

## Ray optics: mirrors, lenses, glass, sunlight (`optics.OpticalScene`, `optics.TubeTracer`, `AssemblyOptics`)
* **Surfaces**: mirrors (flat, leant by "tilt", canted by "aim", or spherical of signed radius, clipped to their part's
  box; polished-metal reflectance from the material at 550 and 1000 nm, interpolated in wavelength, the rest
  absorbed), blocks and lenses of transparent material (lenses: two spherical faces on a disc, a blackened rim;
  thickness on the axis from "thickness" or just enough for a 1 mm rim), prisms (a box of glass cut by two planes
  through its base's corners and its apex — a convex polyhedron), photodiodes (an absorber with a square
  window of the junction's area on its sensitive face), and every other opaque part as an absorber of its
  absorptance (emissivity as a grey body, at least 1 − R of the polished material; the rest is scattered diffusely
  and not followed). Thin wires and shafts, liquids and transparent vessels let light by. Parts on moving bodies sit
  in their body's frame; rays walk each frame's cell grid (Amanatides–Woo).
* **At a surface**: the law of reflection; Snell's law n₁ sin θ₁ = n₂ sin θ₂; unpolarised Fresnel reflectance
  (R_s + R_p)/2 (4 % at normal incidence on glass), total internal reflection past the critical angle; dispersion
  by Cauchy's n(λ) = A + B/λ² fitted to n_d and the Abbe number. Every glass surface splits a ray into reflected and
  transmitted branches (so multiple reflections in a plate, ghost images and light pipes follow); branches below
  10⁻⁴ of the tube's power are cut and accounted as such.
* **Ray tubes**: a source's light is cut into triangular tubes; the three corner rays are traced through every
  branch, and a tube whose corners follow the same sequence of surfaces is carried whole — its power lands on a
  window in proportion to the window's share of its footprint (clipped by Sutherland–Hodgman), so focusing
  concentrates it. A point source (an LED: radiant intensity (m + 1)/2π · cos^m θ) is sampled through Shirley and
  Chiu's concentric map; each tube carries the intensity (edge-midpoint average) times its solid angle (Van
  Oosterom–Strackee), so a straight or mirror-folded path delivers exactly I·A/d². A parallel beam (sunlight) is
  sampled on a square grid across the scene, each tube carrying irradiance × cross-section. Tubes split in four
  where their corners part company (only where one reaches a window, for LEDs), where a mirror or lens they look at
  is less than three times their width, or where the intensity across them varies threefold; up to a set depth.
* **Coupling**: each LED's light that reaches a photodiode by way of at least one mirror or glass surface is traced
  at compile time (the LED's wavelength, bodies in their compiled poses) and added to the direct, analytic
  coupling (which now treats mirrors and glass as stopping the straight path and receives on the junction's face).
  Sunlight (`CompiledAssembly.setDaylight`) is traced at 700 nm whenever the sun moves half a degree or a body with
  a mirror, lens or glass moves: what each part absorbs heats it (`AbsorbedLight`, external power), what reaches a
  junction is the photodiode's daylight. Parts whose block does not see the sky get none. A small leaded component
  (resistor, capacitor, chip) shades and absorbs over a cube of its own volume, not its whole cell.
* **Display**: each visible LED whose axis meets a mirror or lens has its axis and a ring of six rays (filling that
  first optic) followed along the stronger branch at every surface; the client draws them as faint ribbons of the
  LED's colour while it shines — a visual aid (a beam in clean air is invisible from the side).

## Metal fatigue of turning shafts (`ShaftFatigue`)
* **Rotating bending**: each turning body with a shaft along its axis in two or more bearings is a continuous
  Euler–Bernoulli beam (cubic elements between cell centres, EI from the shaft's diameter and modulus) pinned at its
  bearings and loaded by the weight across the axis of every part of the body at its place along it. A section's
  bending stress σ = 32M/(πd³) (× "stress_concentration") reverses once a revolution: its amplitude.
* **Life**: Basquin's line in log–log through (10³ cycles, 0.9 S_ut) and (N_e, S_e) — the material's fatigue
  strength; N_e = 10⁷ for steels, irons and titanium, which have an endurance limit (unlimited life below S_e), and
  5·10⁸ for other metals, which have none. Without fatigue data S_e = S_ut/2.
* **Damage**: Miner's rule, D += n/N_f per step with n the revolutions turned (from the body's angle); D persists with
  the world. At D = 1 the section cracks through and the part breaks; whatever of the body lies beyond it with no
  bearing left to hold it falls.

## Eddy currents (`em.EddyCurrents`, `EddyCurrentDrag`, `AssemblyCompiler.buildEddyCurrents`)
* A conducting sheet (thickness t, conductivity σ) moving with velocity v through a field whose component normal to it
  is B_n carries J = σ v × B and is dragged with σ·t·B_n²·v_⊥ per unit area (v_⊥ its velocity in its own plane). Where
  the currents close on themselves (round a tube moving along its axis) this is exact; under a pole in a plate their
  return paths through the plate leave about half (shape factor 0.5).
* Every conducting plate (sampled 4 × 4 across its face, thickness its fill) or pipe wall (12 × 8, normals radial)
  near a magnet, the two on different bodies at least one of which moves, couples them with a viscous drag
  τ = c(x)·(ω_a − ω_b), c tabulated over the moving body's angle (180 steps) or travel (64) as Σ η σ t A B_n² |v_⊥|²
  per unit speed; two bodies on one axis (a drag cup) use its average over their relative angle. σ follows the
  conductor's temperature; the dissipated c·Δω² heats it.
* Low-speed regime (magnetic Reynolds number μ0σvt ≪ 1): the induced currents' own field, and the drag's peak and
  fall at high speed, are not modelled; two independently moving bodies (not coaxial) are not coupled.

## Moving iron, relay contacts (`MagneticSystem.addArmatures`, `TravelContact`)
* **Armatures**: iron on a slide moving along a fixed coil's axis. The reluctance lattice gives the coil's inductance
  with the armature against its pole (L_closed) and without it (L_open); the slide's travel sets the air gap g — zero
  at the end of its travel towards the coil — whose reluctance g/(μ0A) (A the coil's bore) adds in series to the iron
  path: ΔL(g) = N²/(N²/(L_closed − L_open) + g/(μ0A)), a pose-dependent self-inductance. Flux linkage L(x)·i gives the
  back EMF i·dL/dx·v and the pull ½i²·dL/dx from one co-energy (energy conserved to 0.3 % over a pull-in).
* **Travel contacts**: conductors on a slide and on another body touching across the slide's direction of travel
  meet only at the end of its travel towards one another: their conductance rises smoothly (smoothstep) from 1 nS to
  1/R_contact (0.02 Ω) over the last 0.2 mm — a relay's or limit switch's contacts; I²R heats the moving contact.
  The other body may be the frame or the turning body a slide rides on: a centrifugal switch's weight against its
  contact on the rotor (the travel is relative to the rotor, which carries both).
* **Stops**: a slide's travel ends at its shaft's ends, or at stop collars ("travel" on a linear bearing, either way
  from where it was placed); the stop is a firm contact (150 Hz on the slide's mass, damping ratio 0.7, a quadratic
  onset over 2 % of the stroke up to 1 mm).
* **Circuit numerics**: solid metal pieces in contact whose resistance is below 10⁻⁴ Ω, of metals whose Seebeck
  coefficients differ by under 20 µV/K, share one electrical node (otherwise 10⁷ S conductances left a circuit's
  floating potential known only to its roundoff, where a nearly open element held it). Components mount on one
  structure (a coil touching both its bracket and an armature does not weld them together).
  A part fixed to the shaft a bearing carries and sitting against the bearing along its axis (a gear, crank, hub or
  flywheel beside it) turns with the shaft: it is not also fixed to the bearing's housing, which would lock the shaft
  to the frame (`AssemblyEmergenceTest.aGearAndCrankAgainstTheBearingsTurnWithTheShaft`).

## Acoustics (`core.acoustics`: `DipoleRadiator`, `SoundField`, `AcousticCoupling`)
* **Air**: ρ = pM/(RT), c = √(γRT/M) (dry air, γ = 1.4) at the ambient temperature and 101.325 kPa: 1.204 kg/m³ and
  343 m/s at 20 °C. Linear acoustics, free field (no walls, no ground reflection), no absorption.
* **Sources**: every body on a slide is a compact dipole (small against the wavelength) of strength m = M·U along its
  slide, M = (8/3)(S/π)^{3/2} + V — the added mass of a disc of its outline S seen along the slide (Lamb) plus its
  volume; a sphere's 2πa³ gives the exact potential flow round it. Its field, with τ = t − r/c:
  φ = −(ŝ·r̂)[m(τ)/r² + ṁ(τ)/(cr)]/4π, u = ∇φ, p = −ρ∂φ/∂t = ρ(ŝ·r̂)[ṁ/r² + m̈/(cr)]/4π — near (1/r³), induction
  (1/r²) and radiated (1/r) parts; radiated power ρM²Ü²/(12πc³).
* **Receivers**: each such body feels F = ρM·(∂u/∂t·ŝ) from the others' fields (Froude–Krylov plus added mass on a
  small body in unsteady potential flow); reciprocal by the Green's function's symmetry.
* **Flexible leads**: a bare wire marked "flexible" (a loudspeaker's tinsel lead) conducts to a part on another,
  moving body; it carries no force.
* **Retardation**: velocities are kept per accepted step; derivatives at τ from a least-squares cubic through the
  eight nearest samples. The force within a step is known from history (the delay outlasts the step; otherwise the
  last values are held). The air is a reservoir: its work on receivers is external power; the radiation reaction on
  a source (≈ ρM²Ü²/(12πc³), orders of magnitude below mechanical losses) is not applied.

## Numerical coupling: multirate (`TransientSolver.multirate`)
* The thermal network lags the electrical and mechanical state by at most one synchronisation interval (≤ one game
  tick, ≤ half the shortest C/G); temperature-dependent properties (resistance, thermistors, Seebeck EMFs) use the
  temperatures of the last synchronisation. Heat crosses as the integral of what the fast elements delivered, so energy
  is exact to the integrators' error: a tungsten filament heating from cold ends within 0.01 K of single-rate stepping,
  energy error 5·10⁻⁹ J of 0.42 J.

## Hearing it (`Tone`, `ToneSynth`, `ClientSounds`)
* Every body on a slide that moves is analysed every fourth tick over its last 0.4 s of simulated motion: its
  fundamental is the first strong peak of its velocity's autocorrelation (5 Hz – 5 kHz); its pressure waveform is the
  Fourier series of up to 64 harmonics below 4 kHz of Ü (the velocity's harmonics, from a DFT over whole periods,
  weighted by (2πkf)²: the far field follows Ü); its level the far-field rms pressure 1 m away on its axis,
  ρ·M·Ü_rms/(4πc). A 440 Hz sinusoid gives 440.3 Hz and its dipole pressure to 0.6 %.
* The client plays each as a streamed sound synthesised from that waveform (1024-point table, 16-bit, 44.1 kHz) at the
  pressure the listener's ear receives — the dipole's p·|cos θ|/r with r the physical distance (blocks × the island's
  metres per block) — placed at the body for stereo, Minecraft's distance fade off; 94 dB plays at half full scale.
  It is a sonification at the true simulated pitch, not a recording: a machine simulated in slow motion still sounds
  at its real frequency. The trembler buzzes at 7.9 Hz (47 dB at a metre), the loudspeaker sings at 261 Hz (54 dB).

## Magnets attracting iron (`MagneticSystem.addMagnetArmatures`)
* Method of images: iron of permeability μr facing a magnet acts on it as the magnet's mirror image behind the iron's
  face, strength k = (μr − 1)/(μr + 1) (0.98 for structural steel, ≈ 1 for iron). The energy is half the magnet's
  interaction with its image, U(g) = ½·k·c·U_int(magnet, image at 2g + l) — the existing analytic magnet–magnet
  interaction (Gauss quadrature of −M·B) — so the pull equals the magnet–image force. c is the fraction of the
  magnet's face the iron's nearest face covers. Applies to a magnet and iron one of which rides a slide along the
  magnet's axis (either way round); tabulated over the travel, conservative.
* A 5 cm N42 cube on iron: 533 N at a 6 mm gap, 195 N at 16 mm, 14 N at 56 mm, 1 N at 126 mm (catalogue pulls for
  such a cube at contact are about 1 kN). The face is taken at the iron's cell boundary (a thinner plate's own face is
  not resolved); saturation is not modelled (pulls from within a few millimetres are overstated); coils sharing the
  path do not add their MMF to the magnet's (no polarised relays or electro-permanent releases yet).

## Liquids in gas vessels; vapour–liquid equilibrium (`VaporLiquid`, `GasReactor.withLiquid`)
* Condensable species (water, ethanol) have a liquid pool in every vessel of a gas network ("liquid_<species>" moles
  to start). Each evaporates or condenses at κ·(φ·x·γ·p_sat(T) − p)/(RT) [mol/s]: κ = k_G·A (k_G = 0.05 m/s, a stirred
  or boiling liquid; A ≈ V^(2/3), the vessel's section), x the liquid's mole fractions, γ its activity coefficients,
  φ = l/(l + 10⁻⁴ mol) (no liquid, no equilibrium pressure — a dry vessel's undersaturated vapour forms at most a
  negligible dew film), p the vapour's partial pressure. The liquid's volume is taken from the gas's.
* Saturation pressures: Antoine (water 1–100 °C, ethanol −57–80 °C; extrapolated beyond): water boils at 373.1 K,
  ethanol at 351.4 K (1 atm). Heat of vaporisation from the same curve (Clausius–Clapeyron, ideal vapour): 44.1 and
  42.9 kJ/mol at 25 °C (tables: 44.0, 42.3). The liquid's internal energy is h_g(T) − L(T), so evaporative cooling,
  latent heat and energy conservation follow without a separate phase-change term.
* Activity: van Laar for ethanol–water (A₁₂ 1.6798, A₂₁ 0.9227); ideal (Raoult) otherwise. A 10 mol % solution boils
  at 86.6 °C with a 44.0 % vapour (DECHEMA: 86.4 °C, 43.7 %); the azeotrope falls at 91.5 % (measured 89 %), 78.2 °C.
* A vessel holding liquid, or marked "condenser", exchanges heat with its wall at 200 W/(m²·K) (boiling, film
  condensation) instead of a gas's 10.

## Forced convection from jets (`JetConvection`, `AssemblyCompiler.buildJets`)
* Air leaving a duct's open end (outflow ṁ, exit area A₀, diameter D) is a free jet: it keeps its exit speed
  U₀ = ṁ/(ρA₀) through its potential core and slows as a round turbulent jet beyond x = 6.2·D, u = U₀·6.2·D/x
  (Rajaratnam; ±15 %). A duct drawing air in makes no jet (the intake draws from all around).
* Along the vent's axis, up to 16 cells out, the first solid part in the way is cooled (what stands behind it is
  sheltered): Q̇ = h·A·(T_part − T_air), A its surface, h = Nu·k/L with L its size across the flow and the
  Churchill–Bernstein cross-flow correlation, Nu = 0.3 + 0.62 Re^½ Pr^⅓/[1 + (0.4/Pr)^⅔]^¼ · [1 + (Re/282 000)^⅝]^⅘
  (±20 % for cylinders; a plate face-on or a block, ±50 %). The 0.3 (conduction through still air) is left to
  natural convection, which still acts; √Re is taken as √(Re + 1) − 1, the same above Re ≈ 100, so h has a finite
  slope as the fan starts. The air is the ambient reservoir; the jet's heat is not returned to the duct's air.
* A 5 cm duct blowing 10.8 g/s (4.6 m/s) on a 6.25 cm aluminium plate: Re 19 000, h 31 W/(m²·K), hA 0.36 W/K,
  three times the plate's natural convection.

## Rotors carried by slides (`CarriedRotor`)
* A body turning in bearings mounted on a slide is a revolute joint carried by a prismatic one: two degrees of freedom,
  the slide's displacement x (along unit axis a) and the rotor's angle θ (about its axis n). With the rotor's centre
  of mass a distance e off its axis, r(θ) = e₀ cos θ + (n × e₀) sin θ, the kinetic energy is
  T = ½(M + m)ẋ² + ½Jθ̇² + m·s(θ)·ẋ·θ̇,  s(θ) = a·(n × r(θ)),
  J about the rotor's axis (with m e²). The slide's inertia carries M + m (and its weight, its stops' stiffness and its
  bushings' load all count the rotor); `CarriedRotor` is the cross term. Lagrange's equations put d(m s θ̇)/dt on the
  slide (the unbalance's centrifugal and tangential forces, m e θ̇² and m e θ̈) and d(m s ẋ)/dt − m s′ ẋ θ̇ on the rotor
  (the slide's acceleration acting on the offset mass); their power is exactly d(m s ẋ θ̇)/dt, so energy and the
  slide's momentum (absent other forces) are conserved. A balanced rotor (e = 0) just adds its mass.
* What a carried rotor touches: its bearings (friction against the slide, which does not turn), meshes with gears on
  the same slide, cranks, brakes and cams on the same slide. Things it moves past — gears, brushes, cams, cylinders,
  light barriers, coils and magnets on the frame or elsewhere — are not coupled to it (a warning says so for gears,
  coils, magnets, cylinders and fan blades); see KNOWN_LIMITATIONS.
* Rotating unbalance on a sprung slide (M on springs k, damping c): the steady amplitude is
  X = m e ω²/√((k − (M+m)ω²)² + (cω)²), largest near ω_n = √(k/(M+m)) and → m e/(M+m) far above it (isolation).

## Slides carried by rotors (`CarriedSlide`)
* A slide whose linear bearings sit on a turning body: the rotor's angle θ (axis n through pivot p) and the slide's
  displacement x along its guide u₀ (in the rotor's frame). Its centre of mass is at q = c₀ + x·u₀ from the pivot in
  that frame; ρ² = |q⊥|² its squared distance from the axis; σ = u₀·(n × c₀) the guide's offset from a radial line
  (constant). Kinetic energy T = ½mẋ² + ½(J₀ + J_s + mρ²)θ̇² + mσẋθ̇ (J_s the slide's own inertia about a parallel axis
  through its centre of mass); gravity V = −m g·R(θ)q.
* Lagrange: on the slide d(mσθ̇)/dt − ½mθ̇²·dρ²/dx − m g·R u₀ (the centrifugal force m ω² ρ along the guide, the tilt of
  an off-radial guide, the weight along the guide); on the rotor d((J_s + mρ²)θ̇ + mσẋ)/dt − m g·(n × R q) (the
  rotor's inertia growing as the weight moves out, so it slows at constant angular momentum; Coriolis; the weight's
  torque). The power balance is exact: energy, and with no outside torque angular momentum, are conserved.
* The slide's springs, travel stops and bushing friction act on the relative coordinate x, and are only coupled to
  the rotor that carries it (a spring from the slide to the frame warns and is left out). Rams, racks, lead screws,
  electrical contacts, magnetics and sound do not act on carried slides.

## Aerodynamics of blades (`Aerofoils`, part BLADE) and the wind
* **Blade elements**: every cell of a BLADE part on a moving body (a rotor, or a slide) is a flat-plate aerofoil
  element (chord c, plan area c·cell, span along the part's axis, chord in its "face" direction turned "pitch").
  It meets the air at w = u_air − v_body (on a rotor v_body = ω n × (r − p), its pose turning with the rotor),
  less the spanwise part. Angle of attack α = atan2(w·(s × c), w·c); lift ⟂ w along s × ŵ, drag along ŵ, each
  ½ρ|w|²A times C_L = sin 2α·(1 + (π − 1)e^{−(sin α/0.25)²}) (2π slope to ~14°, flat-plate sin 2α beyond) and
  C_D = C_D0 + 1.8 sin²α (C_D0 "drag", default 0.02). All quadrants (reversed flow, stall) are covered. No finite-span
  (tip vortex) correction; the polar is a flat plate's (L/D ≈ 15–50), not a cambered aerofoil's.
* **Inflow** (momentum theory, uniform actuator disc): a rotor's blades together push the air through their disc
  (A = πR², R the outermost blade element's radius) with thrust T along the axis; the air through it moves at
  u_n + v_i with v_i a solver unknown closed by Glauert's T = 2ρA·v_i·|u_n + v_i| (propeller and windmill states), and
  for windmills slowing the air by more than 40 % Buhl's C_T = 8/9 + (4 − 40/9)a + (50/9 − 4)a² (C¹ with 4a(1 − a)).
  Drag-free, the shaft power is −T(u_n + v_i): C_p = 4a(1 − a)² ≤ 16/27, the Betz limit. Neglected: wake rotation,
  tip loss, radial variation of the inflow, induction across the axis (a rotor edge-on to the wind, a vertical-axis
  turbine, gets none: overpredicted), and blades shading each other.
* **A propeller on a cart** (a rotor riding a slide along its axis): each element moves at ω n × (r − p) + v_s·a,
  so the air's force is a torque on the rotor and a force a·F on the slide; the disc meets the axial wind less the
  cart's own speed. At rest the cart's push equals the thrust given to the air (to 10⁻⁹); the cart's momentum equals
  the impulse it received (2·10⁻⁵); the thrust falls with the advance ratio.
* **Energy**: the air is an outside reservoir; the work it does on the body (torque × ω, force × v) is external
  power in the ledger.
* **The wind** (`Assembly.ambientWind`, uniform over a machine). In the world (Minecraft has none) it is modelled:
  4.5 m/s at 10 m in clear weather, 8 in rain, 12 in thunder, from the west, veering ±25° over 20 minutes and gusting
  ±15 % over tens of seconds, reduced near the ground by the neutral log profile u(h) = u₁₀ ln(h/z₀)/ln(10/z₀),
  z₀ = 0.1 m, h the machine's middle above the ground beneath it (≥ 2 m). Shelter: rays from the machine's blocks
  run upwind 16 blocks; the share that meets a solid block that is no part of a machine (a wall, a hedge, a hill) is in
  its lee, where the wind is down to a quarter (a wall's wake is calm for several times its height downwind). Looked
  for again as the wind veers.

## Phototransistors (TRANSISTOR with "photo")
* An NPN (or PNP) bipolar transistor whose collector–base junction lies under a window ("photo": its area [m²],
  "face": the side it looks out of) is a photodiode across base and collector (`Photodiode`, the same responsivity,
  LED, daylight and hot-body couplings as the PHOTODIODE part) in parallel with the Ebers–Moll transistor: the
  photocurrent, collector → base, is base current, so the collector carries β times it until the transistor
  saturates. The enlarged junction's capacitance (≈ 70 pF per 7.5 mm²) sits base–collector, Miller-multiplied: what
  makes phototransistors slow. An LED facing one is an optocoupler: the two circuits share no conductor.

## Absorbing glass (Beer–Lambert)
* Glass, lenses and prisms of a material with an absorption coefficient α(λ) (`Material.absorptionCoefficient`,
  coloured filter glasses) attenuate every traced segment inside them as e^{−α·path}, at each band's wavelength; the
  light lost is deposited in the glass, which warms. A plate passes (1 − R)²τ/(1 − R²τ²), τ = e^{−αt}, with the
  Fresnel reflections bouncing inside.
* Tracing fix: light deposited on its way to a glass surface (absorbed by a mirror, or now by tinted glass) was
  counted once in each of the surface's reflected and transmitted branches; it is now shared between them as the
  light is, so it counts once.

## Liquid flows between gas vessels (`LiquidDrain`)
* Wherever two gas-holding parts of a network with condensables meet, their liquid pools are joined beside the gas
  passage. Each pool stands at h = floor + V_l/A (a tank between its walls; an upright tube over its length; a lying
  tube over its bore); the passage leaves each part at a port: an upward joint at the lower part's top and the upper
  part's floor, a side joint at the passage's bottom.
* The liquid moves by its weight: Δ = ρ_a g H_a − ρ_b g H_b + ρg(z_a − z_b) (H the depth above a port, z the ports'
  heights), through the passage as an orifice, Q = 0.6·A·√(2|Δ|/ρ)·sgn Δ, joined to a laminar Q ∝ Δ below 30 Pa; only
  what is there flows: the liquid reaches the port over its rim, at most the weir flow Q_w = ⅔·C_d·b·√(2g)·H^{3/2}
  (Francis; b = 2√(πA) the rim, H the upstream depth above the port), combined as Q·Q_w/(|Q| + Q_w), and a dry vessel
  passes nothing (× V_l/(V_l + 10 µL)), each way of the flow (split smoothly, Q = Q⁺ − Q⁻, width 10⁻¹² m³/s) carrying
  only its own source pool's liquid, so a pool is never drained below empty. A tank's "weir" raises its outlet to the vessel below above its floor, so it
  holds a pool that deep: a column's tray. It carries its pool's
  composition and internal energy (elements and energy conserved; its potential energy falling is not tracked). A
  shut valve stops it with the gas.
* The gas pressure difference does not push the liquid: the gas passage sharing the way equalises it, and gas and
  liquid pass independently — counting it coupled the liquid stiffly to the gas's pressure swings in small pipes.

## Batteries (`Battery`, part BATTERY: "chemistry" 0 lead–acid, 1 nickel–metal hydride)
* Lead–acid: a manufactured stack of N cells, PbO₂ | H₂SO₄ | Pb. Discharge, Pb + PbO₂ + 2 H₂SO₄ → 2 PbSO₄ + 2 H₂O, thins the acid
  linearly in the charge drawn (Faraday: the state of charge is integrated from the current): specific gravity 1.28
  full, 1.10 flat.
* EMF per cell by Vinal's rule, E ≈ SG + 0.845 V (the Nernst equation over a working cell's acid concentrations, valid
  SG 1.10–1.30): 2.125 V full, 1.945 V flat. An exhaustion knee (−0.3 V over the last 3 %) and a gassing knee on
  overcharge (+10 mV a cell per percent past 103 %) hold the charge in range without clamping it.
* Nickel–metal hydride, NiOOH | KOH | MH: the alkaline electrolyte is not consumed; the voltage is set by the nickel
  electrode's redox couple, whose oxidised share is the state of charge s — Nernst, E = 1.30 V + (RT/F)·ln((s + 0.02)/(1 − s
  + 0.02)) at 298 K (1.27 V at 20 %, 1.35 V at 90 %, 1.40 V full; the 2 % pinch keeps it finite, continued straight
  past the ends). Four cells make a 5 V logic supply.
* Internal resistance per cell r = k ÷ capacity (k = 0.1 Ω·Ah lead–acid, 0.05 Ω·Ah NiMH), 60 % higher flat, steeply
  higher in the exhaustion knee; both chemistries share the exhaustion and overcharge knees.
* Energy: the chemistry is an external reservoir delivering N·E·i; N·i²·r heats the battery. Its state of charge is
  saved with the assembly (in the capacitors' voltage channel). Not modelled: entropic heat, Peukert capacity loss
  beyond the resistance, temperature dependence, self-discharge, sulfation.

## Buoyancy in still water (`Buoyancy`, `Assembly.waterWhen`)
Parts on a slide standing in still water (in Minecraft: water blocks, the machine's own blocks waterlogged) feel
Archimedes' upthrust ρ·g·V_sub along the slide's guide. Each part is a prism of its displaced volume over its box
height h: its material volume (a block's fill, a shaft's cylinder), or for a closed vessel (tank, cylinder) its whole
outer box. The weight of what a vessel holds is not counted. The submerged depth is the water's surface less the
part's bottom, clamped to [0, h] with C¹ corners over 1 mm. The surface lies where the column of water over the part
ends, 8/9 up its top block (a source block's level). The upthrust is conservative while the level is fixed, an
external reservoir: its potential ρ·g·A·∫depth is the element's stored energy. A moving part loses energy two ways:

- quadratic drag over its wetted fraction (C_d = 1);
- the waves it makes while the surface cuts it. This is the radiation damping of a small body heaving in deep water,
  B = ρ·ω³·A_w²/(2π·g) (Newman, *Marine Hydrodynamics* §6.18), at its heave frequency ω = √(ρ·g·A_w/m). It settles a
  bobbing float within a few bobs; drag alone at millimetres a second let it ring for twenty seconds.

Validated in `BuoyancyTest`: oak floats at its draft within 3 %, steel sinks, and a thin steel vessel floats. A float
pushed under settles, its energy balancing. Not modelled:

- the added mass of the water a part accelerates;
- waves reflected by a pond's walls;
- currents;
- the water level falling as floats displace it (the pond is a reservoir).

Turning bodies (`WaterOnRotor`): each part carried round by a body turning about a fixed axis feels, at its turned
place, the upthrust over its submerged fraction and the water's drag against its motion relative to the water's own
velocity. A thin block is a plate with normal drag only, ½·ρ·2·A·|v_n|·v_n; any other part is a bluff body over a
frontal area of V^⅔. The torque is n·Σ r × F, and the water's work is external (a pond, a stream). A stream's current
comes from its slope and depth by Manning's formula, v = R^⅔·S^½/n with n = 0.015, the hydraulic radius taken as the
depth. A Minecraft stream falls a ninth of a block per block of run (S = 1/9), so a block-scale stream at 5/9 of a
block deep runs at 2.4 m/s. A part standing in flowing water is not waterlogged, since a waterlogged block would be a
still source; it takes the current and level of the stream beside it. Validated in `BuoyancyTest`: a four-paddle wheel
in a 1 m/s stream reaches 7.1 rad/s, its paddles at 0.89 m/s, and turns the other way in a reversed stream, its energy
balancing. Not modelled: the stream slowing as a paddle takes its momentum (it keeps its speed), and splashing.

## Chips by events, and monitors (`core.digital`, parts CHIP functions 5–8, DISPLAY, PRESSURE_SENSOR)
* **The chip language** (`ChipDesign`) describes registers and the logic between them: `in`/`out` pins and buses,
  `reg`, `wire`, `mem`, `param`, `assign`, and `on rise|fall clk { … }` blocks of non-blocking assignments with
  `if`/`case`; unsigned arithmetic up to 64 bits. A part's design parameters are its `p_<name>` parameters.
* **Synthesis estimate**, 74HC-class: gates counted from the operators (an n-bit adder or comparator 5n, a multiplier
  6n², a divider 8n², a bitwise operator n, a variable shift 3n·log₂n, …); logic depth along the longest path from a
  register to a register; gate delay 3 ns, setup 5 ns, clock-to-output 8 ns, so f_max = 1/(depth·t_g + t_setup + t_cq).
  An edge sooner than 1/f_max after the last is counted as a timing violation and the registers hold (a real chip
  would latch garbage; holding is the deterministic stand-in). Energy per clock edge: a sixth of the gates switching,
  (gates + 8·flip-flops)/6 × 30 fF × V²; edges of other inputs cost a tenth of that; leakage 2 nA a gate (memory bits
  and flip-flops counted as gates).
* **Pins in the circuit.** Supply: a conductance drawing leakage plus switching power (smoothed over 1 ms); its I·V
  heats the package. Outputs on analog nets: a 50 Ω switch to either rail. Inputs on analog nets: high above 0.55,
  low below 0.45 of the supply (hysteresis), 5 pF each, the crossing time interpolated within the step. Up at 2 V,
  off below 1.5 V. A net joining only chip pins is ideal apart from its capacitance, charged on each edge (½CV²
  from the supply): 5 pF a pin, its wire not yet counted.
* **Crystal clock module** (function 6): a square wave at "frequency" × (1 + ppm·10⁻⁶), 1 mA at 5 V whatever the
  frequency, starting 1 ms after power.
* **10-bit SPI ADC** (function 7, cs/sclk/vin → dout): samples on CS falling and presents the MSB; each SCLK falling
  edge presents the next bit. Its reading is ratiometric, code = round(1023·V_in/V_DD) with ±½ LSB noise. Its
  maximum SCLK is 1 MHz; 300 µA.
* **Display controller** (function 8, cs/dc/sclk/din/rst) with display panels (part DISPLAY): the SSD1306 command
  subset that matters for drawing (display on/off, contrast, inverse, all-on, page/horizontal/vertical addressing,
  column and page ranges); data bytes are 8 rows of a column. Current 300 µA plus 2.5 µA a lit pixel × contrast/255.
  Its maximum SCLK is 10 MHz.
* **Pressure transducer** (PRESSURE_SENSOR): a strain-gauge diaphragm and amplifier on a vessel port; output
  10 %…90 % of its supply over its gauge range (ratiometric, like the ADC: the supply cancels), 100 Ω output
  resistance, 1.5 mA, a smooth clamp at the ends of its range.
* Validity: logic levels and timing of 74HC/AHC-class CMOS at 2–6 V. Not modelled inside chips: glitches,
  metastability, ground bounce, per-pin drive strength beyond 50 Ω, analog behaviour between thresholds.

## MSI chips by events, the 256-byte ROM, contention, CMOS delay against supply (`MsiComponent`, `DigitalDomain`)
* The counter (74HC163), register, adder (74HC283), 16 × 8 ROM and 16 × 4 RAM have the same logic as the analog model
  they replace (`MsiChip`, kept for its validation tests). Supply current is the quiescent current (2 µA at 5 V) plus
  C_pd·V² per cycle of switching, from datasheet-class power-dissipation capacitances (counter 35 pF, register 25 pF,
  adder 80 pF, ROMs 40/60 pF, RAM 50 pF).
* The 256 × 8 ROM (function 9; an EEPROM of the 28C family's kind): D0–D7 follow A0–A7 after its access time (120 ns
  by default). Its contents are the part's text in hex: `@addr` sets the address; `//` and `#` start comments. No
  chip-enable or tri-state outputs.
* Contention: when chips' outputs on one digital net disagree, the pull-ups and pull-downs form a divider across the
  supply. V²/(R_up + R_down) is burnt for as long as they disagree, shared among them as switching energy and heat.
  The net's level is the last driver's (a stand-in for the undefined level in between).
* Delay against supply: an output's delay scales as v/(v − V_T)² (long-channel square law, V_T 0.75 V), normalised
  at 5 V. That is ×4.0 at 2 V (74HC data: 4–5×) and ×0.75 at 6 V (data: ×0.9). Not temperature-dependent.

## Ribbon cable (part RIBBON)
* Sixteen insulated conductors, colour-coded 0–15 as insulated wire is. Each conductor is one node along the cable,
  joining the next ribbon block's same conductor and the insulated wires of its colour touching the ribbon. Each
  conductor in use is a resistor between adjacent ribbon blocks: ρ·L/A with copper, 6.25 cm, 0.081 mm² (28 AWG),
  13 mΩ a cell. Nothing else connects to it; its
  insulation keeps other parts' leads off.
* Across scales: against a miniature block, each conductor joins the miniature insulated wires of its colour that touch
  that face from inside. Physically consistent: a full-size part is the same 6.25 cm part drawn larger.

## Across scales: shafts, pipes and heat (`Assembly.Link`)
* A cell is 6.25 cm at both scales, so a miniature part and a full-size part touching across a block face are
  physically in contact. The link is the contact model the compiler uses within one grid.
* Shafts end to end are rigidly coupled: ω₁·a₁ = ω₂·a₂, a lossless bevel constraint of ratio 1 (μ = 0). Torque passes
  through the constraint's reaction, and energy is conserved.
* Pipes and tanks meeting at the face share a passage: a `FluidBranch` with the smaller end's bore. Mass is conserved
  exactly through it (CrossScaleLinkTest: 1.02552 g before and after). Two tanks equalising end at 266 K and 322 K,
  one gas expanding adiabatically and the other compressed, so their pressures' sum is not conserved.
* Heat flows through G = 1/(L/2kA + L/2kA + 1/hA) with A one cell's face shared among the miniature parts on it.

## The EM-8 processor (`emergence/chips/em8.chip`, `Em8Assembler`)
* A chip design simulated by events like any other: an accumulator, Z and C flags, 32 bytes of RAM, a program
  counter into an external 256-byte ROM, 2-byte instructions in four clocks. A stack grows down from the top of the
  RAM (SP starts at 0, the first push lands in byte 31): CALL pushes the return address, RET pops it, PUSH and POP
  keep A. LDN and STN load and store through a pointer in memory. ADC and SBC carry between bytes. Its outputs are driven through the
  chip's output pins, with the supply-dependent CMOS delay of every chip. Its synthesis estimate is 1825 gates, good
  to about 5.9 MHz at 5 V. A faster clock is flagged as a timing violation.
* The thermostat program: a plate (10 kΩ NTC thermistor over 5.1 kΩ) is read through the 10-bit ADC over its serial
  bus, bit-banged by the program, and the heater's MOSFET is switched on below code 138 (318 K). The plate holds
  318.1–318.7 K, the band set by the plate's thermal lag and the program's loop time.

## The station's dashboard (`DashboardExhibits`, `gauge.chip`)
* Each gauge is a measurement chain of parts: a sensor, a front end, a 10-bit SPI ADC on the gauge's supply, and the
  gauge chip, which averages eight readings and scales them in integer arithmetic.
  * **Pressures.** Amplified transducers (10–90 % of their supply over their range) read by the ADC on the same
    supply are ratiometric: the supply's level cancels.
  * **Battery voltage.** Two silicon diodes fed through 1 kΩ, read against the supply: V = k / code, with
    k = V_ref·1023·100 (V_ref = 1.41 V at the diodes' 4 mA).
  * **Generator current.** It flows through a full-size 0.5 Ω shunt beside the station's bridge. Its two ends are
    sensed on ribbon conductors of their own, which carry no current (Kelvin sensing), into an inverting amplifier in
    the gauge's block (gain 50, 1 s low-pass: the current comes in pulses, 0 to 670 mA at each magnet's pass, and a
    0.2 s filter left a single reading anywhere from 63 to 105 mA against a 74 mA mean): 40 mA a volt. The ADC's reference is the supply, so this one scales
    with it (set for 5.6 V).
  * **Engine speed.** The gauge counts its 10 kHz clock between the rev counter's pulses: rpm = 600 000 / count.
  * **Condenser pressure.** Shown absolute: its transducer's gauge reading plus a fixed 2.8 kPa (the world's air,
    102.8 kPa, less the 100 kPa of its range).
* Division (k / x) is done by repeated subtraction over clocks, and decimal digits likewise. A reading and its frame
  take about 2 s at 10 kHz. The trend holds the last 128 readings (about 4 minutes).

## Chip fabrication (`WaferProcessing`, `ChipProcess`)
* **Hot silicon.** Every silicon block oxidises in the air and, with a dopant film (a wafer), takes the dopant in.
  Both run on the block's own simulated temperature, integrated each step and saved with the machine. Whatever heats
  it is the furnace.
  * *Oxide* (dry, on a (100) face): dx/dt = B/(2x + A) + C·e^(−x/L).
    * The first term is Deal–Grove: B = 772 µm²/h·e^(−1.23 eV/kT), B/A = 3.71·10⁶ µm/h·e^(−2.0 eV/kT).
    * The second is Massoud's fast thin-oxide growth: C = 3.6·10⁸ µm/h·e^(−2.32 eV/kT), L = 7 nm (an
      order-of-magnitude fit).
    * Both are for 1 atm of oxygen, scaled by air's 0.21 atm. The native oxide is 1.5 nm.
  * *Dopant*: a constant surface source at the solid solubility (boron 2.5·10²⁰, phosphorus 10²¹ cm⁻³) into a
    10¹⁶ cm⁻³ background. The junction depth is x_j = 2√(Dt)·erfc⁻¹(N_B/N_s), with Dt = ∫D dt and
    D = D₀·e^(−Ea/kT): boron 0.76 cm²/s and 3.46 eV, phosphorus 3.85 cm²/s and 3.66 eV.
* **The chips a process makes** (long-channel MOS, against the 74HC-class reference: 50 nm oxide, 0.5 µm junctions,
  3 µm gates, n-channel).
  * Effective channel: L_eff = L − 1.6·x_j (sideways diffusion).
  * Threshold: V_T = V_FB + 2φ_F + (Q_d·t_ox/ε_ox)·(1 − (x_j/L_eff)·(√(1 + 2W_d/x_j) − 1)), with
    Q_d = √(2qε_Si·N_A·2φ_F), N_A = 10¹⁶ cm⁻³, W_d = 0.31 µm, and V_FB set so the reference has 0.75 V. The bracket
    is Yau's charge sharing: a short channel's source and drain hold part of the charge under the gate, and the
    threshold falls. The oxide adds about 0.63 V for every 50 nm at the reference's lengths.
  * Drive: I ∝ μ·(1/t_ox)·(1/L_eff)·(V − V_T)²/(1 + (V − V_T)/(E_sat·L_eff)), the denominator velocity saturation
    (E_sat = 2v_sat/μ_surface: electrons about 4 V/µm, holes 9). The delay is load/I, the load half gates
    (∝ L_eff/t_ox) and half wiring. Holes are about 2.5× slower than electrons (their mobility a third, their
    saturation later).
  * Subthreshold leakage scales as e^((0.75 − V_T)/(1.5·kT/q)).
  * A chip works for 0.3 V ≤ V_T ≤ 4.5 V, x_j ≥ 0.1 µm (shallower, the contacts spike through) and L_eff ≥ 1 µm
    (shorter, punch-through).
  * A custom chip's delays, its clock limit (timing violations) and its leakage scale with these. A chip from a failed
    process holds its outputs low.
* **The process window.** Oxide (2.0–2.3 eV) and diffusion (3.5–3.7 eV) have different activation energies.
  * A hotter furnace drives the junctions deeper for the same oxide.
  * A finer mask makes faster chips but leaves less room for the junctions.
  * At 1190 °C in air with a 10 µm mask, the chips work after 3 minutes and run about 5× slower than the
    ready-made ones. They leak 10⁴–10⁵× at first, less as the oxide thickens.
  * With a 3 µm mask, one step works between 190 s and 484 s; its fastest chips (0.26× the reference's delay)
    come just before punch-through and leak 1.8·10⁴×.
  * Two steps, as a player runs them, give an easier window. First heat the bare wafer for 8 minutes (24 nm of
    oxide). Then edit the part to carry the film and heat 40 s more (0.36 µm junctions). The chips: 0.76×,
    leaking 2.9·10³×.
