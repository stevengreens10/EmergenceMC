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
