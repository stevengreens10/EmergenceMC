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
* **Grid**: 1/16 m cells; topology from cells, physics from parametric dimensions.
* **Rigid bodies**: structural parts join everything they touch except: shaft through a coaxial bearing
  (revolute joint), gear–gear contact (mesh), brush–conductor contact (sliding), thin shafts (< 0.9 cell)
  only touch at their end faces or where mounted parts overlap them; wires never carry load.
* **Kinematics**: groups touching the world are static; groups held only by collinear bearings on a
  static body or a coaxial rotor become rotors; free-standing machines use their heaviest group as frame;
  over-constrained groups lock (with a warning).
* **Electrical contact**: wires connect on all faces (insulated wire only to wires/leads); components have
  leads at their axial ends (end-face centre, plus side faces of end layers when ≥ 2 cells long); bulk metal
  conducts; only brushes conduct across a moving joint. Contacts are split at the face so each half's
  Joule heat goes to its own part.
* **Failure**: parts are destroyed above their failure temperature (melting point; insulation, enamel,
  semiconductor and resistor limits).

## Environment (Minecraft layer)
* Ambient air temperature from biome climate: −5 °C (snowy, 0.0) … 20 °C (plains, 0.8) … 40 °C (desert, 2.0).
* Incandescence: block light from parts above the Draper point (798 K); rendered colour from a fit to
  CIE black-body chromaticities.
