# Development log

## 2026-10-08 — Session 1
**Foundation.** Chose MC 1.21.1 / NeoForge 21.1.219 / ModDevGradle 2.0.107 / Java 21 (cached locally,
mature API). Built the Minecraft-free core: SI units with dimension checks, JSON loader, 37-material
database, sparse/dense LU, unified multi-domain network with adaptive trapezoidal/BE/BDF2 integration,
Newton, LTE control, energy ledger, and electrical/mechanical/thermal elements with analytical tests.

**Solver lessons.** BDF2's numerical damping (~0.15 %/cycle) would kill clocks and pendulums → trapezoidal
default with ringing suppression. Inconsistent initial conditions poisoned trapezoidal history → the first
step after a restart is discarded from history. Floating conductive islands made pivots exactly zero in
floating point → relative GMIN for electrical nodes.

**Electromagnetics.** Surface-charge cuboid magnets, elliptic-integral loops, filament coils, co-energy
`MagneticSystem`. Generator (EMF ∝ ω, Lenz braking, energy closure) and synchronous motor emerge in tests.

**Assembly layer.** Grid parts → physics compiler (bodies, bearings, gears, contacts, heat, magnetics).
Bugs found by emergence tests: shafts welded to frames they merely pass (thin shafts now touch only at end
faces), component leads conducting through their alumina bodies (leads are copper).

**Minecraft integration.** Assembly block/entity, part items with data-component specs, placement and
removal, simulation manager with islands, renderer, multimeter, sync. GameTests run headless in-world.
Fixed a server deadlock (block-entity lookups during chunk unload).

**Demo world.** Exhibits defined in the core and validated by physics tests; builder, Gradle generation
pipeline, quick-play client, and an automated spectator screenshot tour that verified every exhibit visually
(found: square gears, invisible thin wires, missing switch sync, timing of glow capture, single-cell heat
allocation → all fixed).

**Performance.** Generator island 52 → 1.8 ms/tick (signal-scaled tolerances, primitive sparse index,
in-solver human effort model). Large linear networks remain over budget (see PERFORMANCE.md).

## 2026-10-08 — Session 2
**Electrochemistry.** Butler–Volmer/Nernst electrodes on any conductor touching an electrolyte; Daniell
cell, displacement plating, corrosion and electrolysis emerge. Exhibit testing exposed a kinetics error:
the transfer coefficient was applied to all n electrons, giving hydrogen evolution a 59 mV/decade Tafel
slope and zinc a self-corrosion rate ~10⁴× too high. Now per-reaction α_c (single-electron
rate-determining step) with α_a = n − α_c.

**Block scale.** At the user's request building now defaults to one part per block (a 16× scale model,
1 block = 6.25 cm cell); the true-scale miniature grid stays available with precision tweezers in the
off-hand. Blocks carry their grid resolution; islands at block scale take in every block within
interaction reach so gears and magnets couple across air gaps (found by a new GameTest).

**Visual overhaul.** Part models built in Blender through the MCP bridge (scripted:
`tools/blender/build_parts.py`) — flange bearings with balls, bobbin-wound coils with side leads, magnets
with painted poles, knife switches, electrolytic capacitors, colour-coded resistors, glass jars of
solution — exported as OBJ and rendered by a small loader; gears are generated with real teeth, spokes and
hubs and phased to mesh; item icons rendered from the same models. Chemistry is now visible: solutions
colour by concentration, plated electrodes change colour, gas bubbles rise.

**Demo world** rebuilt at block scale (11 exhibits, three new electrochemistry plots, a miniature
generator), verified by the screenshot tour.

## 2026-10-08 — Session 2 (continued): fluids
**Fluid networks.** New FLUID domain (pressure, mass flow) with control volumes that pair a pressure node
and a temperature node, quasi-steady branches (Darcy–Weisbach/Haaland, minor losses, gravity), choked
orifices for valves, atmosphere vents and wall convection; a fluids database (ideal gases with fitted
c_p(T), compressible liquids). Validated against Hagen–Poiseuille, Colebrook, isentropic blowdown,
choked-orifice flow and conservation of mass and energy. Found and fixed on the way: FlowSource counted
heat × temperature as power in the thermal domain.

**Rotors.** A first Euler-equation rotor model failed in the demo: in reversed flow a windmill would not
reverse, and the zero-flow pumping direction was undefined. Replaced with a blade-element model (lift and
drag on a staggered blade row) that conserves energy exactly and covers all four quadrants. Exhibit
design was then dominated by honest scale effects: a 5.6 cm rotor in 8 m/s of air makes ~1 mN·m, so the
turbine got a light disk and a bigger receiver, and the fan two 5:1 gear stages.

**Parts and world.** Pipes, pressure vessels (riveted, with a live Bourdon gauge), globe valves (stem rises
when open) and bladed rotors; shafts pass through pipes in glands; open pipe ends vent (with plumes) unless
capped by world blocks; air pressure falls with altitude.

**Heat engine milestone.** A Brayton experiment (compressor and turbine rotors on one shaft, finned heater
between) never self-sustained: a single 5.6 cm blade row reaches a pressure ratio of only ~1.01 — real
micro gas turbines need ≥ 3 — so friction and heater losses win. That is physics, not a bug, and is kept
as a documented result. Pistons came next: slider-crank kinematics with variable-volume control volumes
and an exactly power-conserving gas/shaft coupling, then CYLINDER and ECCENTRIC parts whose linkage,
rod length and timing the compiler infers from placement. An alpha Stirling engine assembled from parts
runs on a hot copper block and drives a magnet-and-coil generator. Along the way: charging it to 4 bar
made it rock instead of turn (the gas spring against an unpressurised crankcase outweighed the flywheel —
real high-pressure Stirlings pressurise the crankcase), and an open cold-cylinder head vented the charge.
In the world, two exhibits within gear reach merged into one island; plots are now 20 blocks apart and
fluid unknowns have Newton step limits.

**Electronics.** Ebers–Moll BJTs, EKV MOSFETs and wavelength-defined LEDs; transistor and LED parts. A CMOS
NAND from four MOSFETs passed its truth table first time. Bugs found by the exhibits: leaded parts conducted
through their silicon bodies (copper leads now general); a capacitor truncation-error floor below Newton's
resolution collapsed the time step on floating CMOS stack nodes (~100× fix); a floating transistor base
needed a pull-down (as in real design). Then a validated chip-level logic model (gates, Schmitt trigger, D
flip-flop) as the next fidelity level: getting a ripple counter to count exposed metastability at power-up
(fixed by latch regeneration), race-through on slow clock edges (non-overlapping master/slave windows), and
LED Newton divergence (internal junction unknown with SPICE pnjlim limiting via a new solver hook). The
4-bit counter now counts exactly with its clock.

**Closed-loop control.** NTC thermistor element and a thermostat exhibit built from a thermistor, a Schmitt
trigger chip, a power MOSFET and a heater on an aluminium plate. Getting it to run exposed (1) heater-to-plate
thermal isolation — clamped component bodies now conduct heat through their contact area — and (2) step-size
underflow after ringing from stale truncation-error history, fixed by a restart rule in the solver. A net
labelling check now catches accidental shorts between touching insulated wires in exhibit layouts.

**Phase change and a steam engine.** IAPWS water/steam with a phase coordinate that spans compressed
liquid, the saturated dome and superheated vapour; Rohsenow boiling, Nusselt condensation, phase-aware
ports, relief and cam-worked valves, and a brake pad as a load. Getting a steam engine to run from parts
was mostly numerical work, each problem found with a new finite-difference Jacobian check in the solver:
a condensation equilibrium limit-cycled because a reservoir's heat-flow tolerance (1e-9 W) was below what a
6 kW/K film conductance can resolve; a volume-fraction phase coordinate was ill-conditioned near dryness
(now logarithmic in density); dry saturated pipes sit exactly on the dome boundary, where the pressure's
slope jumps (now C¹ Hermite joins outside the dome); the phase FD step was as wide as the liquid-side join;
mass-flow tolerances were 10⁴ times finer than the pressure tolerance allows (now from each branch's laminar
conductance); valve seats had zero conductance at zero flow (now laminar below 10 Pa); and a throttle's
seat-sized body held 0.07 cm³ of steam (valves now have a chamber). The engine first flooded its cold
cylinder with condensate — hydraulic lock, a real failure — so the gallery starts it warm. Profiling then
took it from 3.8× to 1.5× real time, and islands over budget now run in slow motion rather than stall the
server.

**A programmable computer controls a machine (Phase 4 milestone).** MSI chips (counter, register, adder,
programmable ROM) as multi-block DIP packages with the logic gates' pin physics and multilinear internal
logic, validated at the pins. The EM-4 — PC, ROM program, adder and accumulator, with add/jump/jump-if-SENSE —
drives a stepper through four MOSFETs. Building it took: colour-coded insulation (adjacent pins of a chip are
adjacent cells, and touching insulated wires used to short); an exhibit maze router with rip-and-retry; wire
runs in the compiler (2639 → 722 unknowns); BDF2 for electronics islands (trapezoidal ringing on 5 pF input
nodes cost 20× the steps); and a high-gain Schmitt input — the RC clock's seconds-long edges let supply
offsets between chips corrupt the accumulator (ACC = 4 + 7 when it should have been 8). The motor was the
hardest part: a radial air-core stepper had too little torque at a quarter-turn offset, and Coulomb friction
stalled it in the weak zone; an axial-flux layout (magnet sweeping directly under the coils, inner leads at a
star point) with a silicone rotary damper (new bearing type) steps cleanly. The thermostat test also exposed
real behaviour: as its supercapacitor ran down, the heater could no longer reach the upper threshold.

**Structures that can fail.** A 3D frame solver (direct stiffness, Euler–Bernoulli members, validated against
beam theory) analyses every anchored structure once per simulated second: members from contact faces (so a
thin plate is a thin beam), gravity, held fluid and rotor weights through their bearings; von Mises against
yield or Rankine against fracture. Overloaded parts break and drop, and anything left without a load path
falls with them. The shelves exhibit puts the same lead block on four shelves of aluminium, steel, oak and
glass plate; the showcase stacks a second block and the steel and glass shelves break. GameTests now run large
exhibits in their own batches (a 40-block exhibit had merged with its neighbour's arena).

**Light that is received.** Photodiodes and solar cells: a junction with responsivity-weighted photocurrent,
coupled to LEDs by beam geometry and ray-sampled occlusion (with angle tables when a rotor's parts can cut the
beam) and to daylight from the sun's position and the weather. A light-barrier exhibit counts a cranked vane with
a Schmitt trigger and a toggle; a solar panel of eight cells in series lights an LED by day. The first ray
sampling (every 0.2 cell) stepped over a thin steel plate; and the panel was first wired backwards — a lit cell
drives current out of its anode, so the string's positive end is the first cell's anode.

**A servo from parts.** Gravity now acts on unbalanced rotating bodies (a potential-derived torque, energy
conserving); potentiometers (a journal for the shaft plus a wiper on a track, with end stops) and op-amps (single
pole, slew limit, rail clamp) are new parts. The servo exhibit closes a position loop: knob pot → op-amp difference
amplifier (×10, with a capacitor for derivative action) → complementary transistor pair → a moving-magnet torque
motor (a magnet on the shaft inside four coils) → the arm, whose pot feeds back. Lessons: saturating the op-amp's
target before its integrator capped its slew at lim/τ ≈ 370 V/s — slew must limit the input stage, the clamp act on
the state; a single coil pair gives no torque where the magnet aligns with it (the hanging rest position), so two
pairs at right angles make τ ∝ cos(θ − 45°); and the knob's wiper is scaled by divider resistors to ask only for
angles the motor can reach.

**Data logger.** An instrument that samples a part at 1 kHz of simulated time, hooked to the solver's accepted
steps and resampled by interpolation, so it sees waveforms the game tick (50 ms) could not: the hand generator's
alternating EMF appears on its screen once per turn. Trace on a HUD screen while held; CSV export by command. The
servo arm moved to the shaft's outboard end so it can be seen, and exhibits can now set a camera heading.

**Restart robustness.** The tour showed the servo island failing on reload: a running state (2.4 A in the coils, a
hot power transistor) restarted from zero node voltages let a Newton iterate drive the transistor's heat node to
infinity and its saturation current to NaN. Device equations now see a temperature bounded to 50–1500 K, every
exhibit is tested to restart from its own running state, and a failed island restarts at rest keeping its charges,
temperatures and positions instead of being wiped.
