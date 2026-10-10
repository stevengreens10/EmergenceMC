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

**Gas chemistry core.** Substances with JANAF thermochemistry, reactions with Arrhenius kinetics and
Gibbs-energy equilibrium (detailed balance), and a reacting gas volume whose energy includes formation energies —
heat of reaction emerges from composition change, conserving energy exactly. Validated against analytical
equilibrium, energy-balance flame temperature, and Semenov's critical-temperature behaviour. A fractional rate
order kept eating a vanished species into negative amounts; it is now cut off at trace concentration. Also fixed:
the server hung at shutdown because unloading a chunk re-marked block entities in other unloading chunks as
changed, re-requesting them forever; the unload path now writes state without marking.

**Chemistry in builds.** Tanks charged with gas mixtures react: the brown-gas exhibit shows N₂O₄ ⇌ 2NO₂ shifting
with temperature (colour from Beer–Lambert absorption), and a heated can of methane–air self-ignites (Semenov).
Getting the explosion took physics, not tuning: alumina legs radiated the heater's power away (mica, an
insulator, instead), a small resistor's 2 cm² contact could not pass the heat (a cartridge heater pressed over
20 cm²), and slow heating let the fuel burn away quietly before runaway (heating must outpace consumption — a real
effect); 2.3 kW ignites it after about 70 s.

**Mixture flow and robustness.** Gas volumes can now connect: pipes and valves carry mixtures (species and
enthalpy upwind, gravity head, choking), open ends breathe the hydrostatic atmosphere, cylinders get piston-driven
gas — the groundwork for burners and engines. The in-world thermal explosion failed where the core test passed:
the mod destroys parts past their failure temperature, and the 2.3 kW element (rated 523 K by default) burned out,
then its PVC leads would have charred. It is now a silicon-carbide element (1800 K) on glass-fibre-insulated wire
(800 K); a new test runs every exhibit and fails if any part is lost. The servo's island still failed on reload when
a chunk boundary cut its negative supply: a Newton iterate drove part temperatures negative and the convection
correlation's fractional powers gave NaN. Convection and radiation now evaluate at bounded temperatures, and the
solver names the element that produced a non-finite value.

**A gas burner.** Methane from a 200 bar cylinder through a choked needle valve, air drawn in by the chimney's
natural draft, lit on hot walls. The physics found the design: a well-stirred reactor validated against the
adiabatic flame temperature exactly, but the first burner never lit — the gas passes through a small chamber in
0.1 s and barely warms from the walls, so ignition needed the hot-surface film model (reaction in the thermal
boundary layer at the wall temperature); the needle valve's bore had been applied at both of its faces, making
its body a second, filling chamber (valves now have one seat in mixture networks); too much air put it below the
lean flammability limit, too little left it smouldering rich; and a small flame in a cold steel box went out
because the walls radiate more than it makes — it needed ~0.8 kW. Lit furnace-style (igniter, then gas, then
igniter off) it burns on by itself near stoichiometric.

**Thermoelectricity and pace.** Every contact between dissimilar conductors now has its Seebeck EMF and Peltier
heat (energy-exact): a thermocouple exhibit amplifies a copper–constantan junction's 40.7 µV/K. Seebeck data were
a mix of absolute and platinum-relative values; now all absolute and checked against the type T/J tables. The
tour showed the whole gallery overloading the server (every island ticking everywhere): islands now tick only
within the players' simulation distance, under a shared time budget.

**A Geiger counter from parts.** Decay chains, gamma attenuation along rays, and a Geiger–Müller tube whose random
hits trigger discharges only above its starting voltage. The solver gained discrete events (landing steps on random
hit times). The first tube never pulsed into the counter: its capacitance was placed anode-to-cathode, where the
charge circulates inside the tube; the pulse comes from the anode node's capacitance to ground. Then it never
stopped: a discharge modelled as decaying towards its extinction voltage never quite reaches it — real tubes stop
when the current falls below the holding current, which is why they need a large quench resistor.

**Things that slide.** Linear bearings make shafts into slides (travel from geometry, end stops, gravity along
the guide), helical springs from wire geometry, magnet–coil couplings tabulated over the travel, and a hand that
pushes. Exhibits: a spring–mass oscillator and a magnet bouncing on a spring beside a coil — close the switch and
the coil's current brakes it (Lenz).

**Hydraulics.** Pistons generalised (crank or straight rod): a cylinder pushing a slide is a ram, and a hydraulic
press emerges — Pascal's force multiplication with volume conserved exactly. The rod first ran past the piston's
travel (the cylinder head was only a soft volume floor); the slide is now stopped where the piston meets the head.
The demo-world generation runs the GameTests with the global time budget lifted (`emergence.globalBudgetMs`), so
the gallery sharing the server cannot slow them into failing (a GameTest server will not start with no tests).

**Hydraulic jack and cavitation.** A hand pump between two check valves ratchets a ram up stroke by stroke; a release
valve lets it down. Its test found the liquid model taking tension: a quick push threw the light ram up past its
water and the pressure under it went to −61 kPa absolute. Liquids now cavitate below their vapour pressure (a void
fraction opens, the pressure stays near p_v); the ram's overshoot then draws ~4 % extra water in through both valves
from the reservoir — a real effect of a fast stroke.

**Smooth motion, joined-up visuals, real hitboxes, faster tests.** Moving parts jittered badly. Logging every
synced pose on the server and every drawn pose on the client (`-Demergence.poseLog=true`, a tour limited to some
exhibits with `-Demergence.showcaseOnly=…`) showed why: the client extrapolated with ω from the packet's game
time, but the client's game clock ran 9 ticks behind to 38 ticks ahead of the server's, and islands run at
0.3–100 % speed — the jack's slide, moving 1 block, was drawn swinging over 25 blocks, snapping up to 12 per
frame. Now each moving thing is interpolated between its last packets by their arrival times (residual jitter
0.42 → 0.008 block on the jack, 0.027 → 0.002 rad on the servo). Wires ended open next to components (and grew
stubs towards faces without a terminal): wire arms ending on a part are capped and the part draws a lead to each
wire that meets one of its terminals, using the compiler's own terminal rule. Springs are a helix stretched
between what their ends are fixed to (end positions synced); thin plates (the jack's handle) sit on what they are
fixed to instead of floating mid-cell; hitboxes follow the drawn geometry. The core suite runs in parallel and the
servo fragment sweep is deduplicated: ~11 min → ~2.5 min.

**Rotary to linear.** Racks (a toothed bar on a slide, meshing with any spur gear whose pitch circle touches its toothed
face) and lead-screw nuts (on a shaft turning in bearings, fixed to a slide) reuse the gear mesh's Lagrange constraint
between a rotor and a slide. The screw's thread friction follows the inclined-plane analysis, so self-locking emerges:
the exhibit's 4 mm-lead screw holds its weight when let go, the four-start 40 mm one runs back and spins its crank. Shafts
are rows of one-cell segments: the segments either side of a nut first welded it to the screw (locking the machine), and
the rack's travel was first limited to the one segment under the pinion.

**Pressure vessels burst.** Every fluid-holding part now knows its wall stress per pascal (Lamé for tubes, Roark's
fixed-edge plate for box tanks) and fails at its material's tensile strength. Burst test exhibit: two sealed boxes half
full of water on switched heaters; the water boils up its saturation curve until the 1 mm copper box tears at 1.94 bar,
as the plate formula predicts, while the 3 mm steel box holds. The heaters are switched so the demo-world generation does
not burst it before anyone looks. No existing exhibit bursts (the 200 bar fuel cylinder's 6 mm wall holds).

**Flaky in-world tests, two real causes.** Exhibits are bigger than the 12-block GameTest arena, so earlier tests'
machines stayed beside later ones and joined their islands (the shelf test once ran inside an 895-part island); each
exhibit test now has a batch of its own and clears assembly blocks around its footprint first. And an island stepped
only if an arbitrary one of its blocks was in a ticking chunk: a machine straddling the edge of simulation distance
(the two-plot computer beyond the test's forced chunks) ran or froze by chance. It now runs if any of its blocks ticks.

**Nearest machines first.** In the demo gallery every machine within simulation distance runs, and they shared one time
budget equally: the exhibit being watched ran in slow motion for machines out of sight. The budget now goes first to
islands within 48 blocks of a player; the rest share what is left and slow first (down to 1 %). Measured on a tour: the
gear train in view at 100 % (it was at ~4–30 % with the whole gallery running). The steam engine remains costly (77 ms
per tick at 13 % speed while the machine was also running a game) — next to profile.

**Iron.** Ferromagnetic parts now shape the coils' magnetic circuits: a reluctance lattice over their cells, solved with
and without the iron, adds what the iron contributes to the analytic air inductances. A closed electrical-steel ring
gives the textbook N²/ℛ within 3 %. Coils can be wound on a core (`core` = 1, leads out the sides). Faraday's 1831
ring is an exhibit: two coils on an iron ring, a switch on one, back-to-back LEDs on the other, flashing on make and
on break. Three things found on the way: a single-cell coil's end faces were terminals, so a conductive core touching
them shorted the windings together (cored coils now have none); a 0.5 F bank fading through the primary induced enough
to light an LED steadily (true — Faraday's battery held its current); and the servo fragment that cuts a coil's loop
failed on its first step. That last one is the intermittent world-load failure: a coil restored with current into a
broken circuit. The first step now starts such currents from rest instead of failing.

**Buckling.** The frame analysis now checks elastic stability: geometric stiffness from each member's axial force and
the critical load factor by inverse iteration (Euler's cantilever load to four digits). Buckling exhibit: two strips
of 1.25 mm steel on end; the short one carries a lead block, the same block on the twice-as-tall one buckles it at a
load its steel could take many times over in plain compression.

**A gas engine.** Otto's four strokes from parts: a cylinder over a crank, an overhead camshaft driven at half speed by a
16:32 gear pair, cam-worked gas valves (new: mixture valves follow cams), a mixing box fed from a methane bottle through
a needle valve, a glow plug (new: a heating element `exposed` to a vessel's gas lights it as a hot film). The valve
timing was worked out from the geometry (phases 225° and 135°). Getting it to run meant fixing four numerical problems:
a fuel order of −0.3 left an infinite slope at the end of each burn; Newton two-cycled a trace of methane through zero;
the flow through a wide passage at 160 bar could not be resolved below its pressure's rounding (its Jacobian column was
noise, and its tolerance unattainable); and the sparse LU's row-based pivot test failed sound pivots at nearly every
iteration — fixing that made the engine about 12× faster, and helps every island. It runs at 1100–2000 rpm, governed by
its fuel flow.

**Reloading an island whose saved motion no longer fits.** The demo world's screw jacks failed to load: the saved
state had the fine-screw carriage falling while its self-locking screw stood still, which only an impulse at t = 0
could reconcile (Newton two-cycled between thread-friction directions under a 40 MN constraint force). The first step
after a load now falls back, after restarting coil currents, to starting the bodies from rest where they stand
(`CompiledAssembly.motionReset`). `IslandDumpReplayTest` replays any dump the game writes with
`-Demergence.dumpFailures=true` (`-Ddiag.dump=<file>`).

**Ray optics.** Mirrors and lenses as parts, refraction by real surfaces. A new `optics` package traces ray tubes
through the assembly: reflection, Snell, Fresnel (both branches at every glass surface), total internal reflection,
Cauchy dispersion. The first version was off by exactly half on small windows (a polar parametrisation of the
emission pattern collapsed one triangle in two at the axis), then 11 % high (chords standing for arcs around the
axis): tubes now carry intensity × their exact solid angle, which makes mirror-folded paths deliver I·A/d² to
0.2 %. Another 13 % disagreement came from the direct LED–photodiode calculation receiving 0.3 cell inside the
photodiode; it now receives on its face, like the tubes, and a glass plate in a beam matches 2n/(n² + 1) with the
source's apparent shift. Sunlight is traced too and heats every part it lands on. A big dish cannot be one part at
block scale, so mirrors can be canted ("aim"): the solar furnace is 169 concave facets each aimed at one focus,
as real segmented concentrators are. Exhibits: periscope, lens, solar furnace.

**Optics in the world.** The solar furnace's plate stayed cold in-world although the core heated it red-hot: it was
its own island (twelve blocks above the dish), and each island traces only its own light. Islands now join along
optical parts' lines of sight. Then it heated at an eighth of real time: one big gear somewhere set every block's
reach to 6, merging three exhibits into one slow island; reach is now per block. Then a GameTest's capacitor bank
burned out: sunlight fell on whole cells round small components; they now absorb over their own size. LED beams are
drawn (the axis and a ring of rays through mirrors and lenses), and `-Demergence.debugIslands=true` reports traced
sunlight and the hottest part of sunlit islands.

**Incandescence.** Hot parts light photodiodes: Planck's spectrum weighted by silicon's response, tabulated, coupling
the photodiode's current to the body's temperature. Daylight now uses the same table (0.243 A/W for a 5778 K sun, not a
flat 0.5). The lamp exhibit took three tries: a router wire touching the bare filament's middle shorted two thirds of
it out; then a 187 mm, 0.1 mm filament on 12 V reached only 1260 K — in air a thin wire convects as much as it
radiates (why lamps are filled with inert gas) — so the filament became one 62.5 mm cell of 0.12 mm wire, ~31 W at
2560 K. The first version counted gross room-temperature infrared from every part (milliwatts on each photodiode);
radiation is now net of what the photodiode sends back.

**Metal fatigue.** Turning shafts now fatigue: a beam model of each shaft on its bearings gives the rotating-bending
stress, Basquin's S–N line from the materials' ultimate and fatigue strengths the life, Miner's rule the damage, which
persists with the world. Wöhler's axles exhibit: the same load on aluminium and steel shafts of 8.4 mm; aluminium
cracks after 2101 turns (its predicted life 2099) and drops its disc, steel runs on below its endurance limit.

**PID servo.** The servo arm's controller gained integral action: a capacitor in series with the op-amp's feedback
resistor. At first the arm drifted 0.19 rad off target: in a differential amplifier the inverting input's network had
lost its DC path while the non-inverting divider kept its, so the loop balanced at 91 % of the set-point; a twin
capacitor in the divider's ground leg keeps the two networks matched at every frequency. The droop (up to 0.15 rad
holding the arm up) is gone: 1 mrad.

**Eddy currents.** Conductors moving through magnetic fields are braked: σ·t·B_n²·v per unit of sheet, tabulated over
the moving body's pose from the cuboid-magnet field, heat to the conductor. Validated against the falling-magnet
tube problem's closed form (to 10⁻⁴ — once the formula quoted from memory, with a spurious π, was rederived). Arago's
disc exhibit: an aluminium disc stops in seconds between magnets, a nylon one coasts, and the energy balances to
0.4 %. Magnets weld to whatever structural part they touch, so the exhibit's magnets sit diagonally off the disc's
rim, on arms from behind.

**Prism.** Glass cut by planes (a convex polyhedron in the ray tracer) as a PRISM part. Exhibit: red, green and blue
LEDs into a 45° dense-flint prism, their drawn beams fanning out at 51°, 54° and 57° — Snell's law at both faces with
Cauchy's dispersion, to 10⁻⁶ rad. LEDs now show their beams through optics whether or not a photodiode is near.

**Relay.** Moving iron: an armature on a short slide under a cored coil, its inductance from the lattice closed and
open and the gap's reluctance in series; contacts that a slide's travel closes; stop collars on linear bearings. Getting
there exposed three general problems. A component touching two structures welded them (the coil glued the armature to
its bracket): components now mount on one. The relay's LED circuit stalled the solver at 10⁻⁷ s steps: with the contact
open, a cluster of micro-ohm copper and steel conductances (10⁷ S) hung on a 1 nS leak, its potential known only to
its roundoff — a volt of noise on the LED's junction capacitance; such solid contacts now share one node, which also
shrinks every island's network. And slides' end stops were soft (a millimetre of give): they now scale with the slide's
mass and stroke.
