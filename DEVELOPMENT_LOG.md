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

**Trembler.** The relay's coil wired through its own normally-closed contacts, with a flyback diode: an electric
bell's interrupter. It needed no new physics — the same moving-iron inductance, travel contacts and stops oscillate at
7.9 Hz, set by the coil's L/R, the pull-in current and the armature's flight. Fed through the contacts and a brush the
coil drew just under the relay's pull-in current at 24 V, so the trembler runs on 36 V.

**Speed governor.** A brushed motor holding its speed against a brake through a tachogenerator (two magnets on a disc
past a coil), a diode peak detector, an op-amp PI loop and a Darlington drive. Building it found: the exhibit's router
had been created before some parts and laid wire through them; a supply wire lying on the rotor's wiring dragged that
wiring onto the frame (wire networks take one body — now in KNOWN_LIMITATIONS); a single transistor needed more base
current than an op-amp gives (its current limit made the op-amp stiff — the Darlington is what a real drive uses);
and the op-amp's internal state had a 1 µV error tolerance that turned Newton noise into 25× too many steps in any
closed loop.

**Spectrometer.** Light from hot circuit parts is now traced through mirrors, lenses and glass in 18 bands of 40 nm,
each at its own refractive index, and photodiodes take band-by-band blackbody sources. A collimated filament beam
through a 40° flint prism spreads violet to infrared across four cells 0.8 m on (a 45° prism sent the blue into total
internal reflection); three receivers show the glow shifting to the blue as the filament heats. Display rays carry a
wavelength each, drawn as bright as Planck's law makes that colour at the source's temperature.

**Acoustics, first slice.** Sound in air from compact dipoles: every body on a slide pushes air with a strength set
by its outline (a disc's added mass) and its volume; the retarded field (near, induction and far parts) acts on the
other bodies as ρM·∂u/∂t. Velocity histories per accepted step give the retarded derivatives by local cubic fits.
Checked against the potential flow round a sphere, an oscillating sphere's radiated power, propagation at c,
reciprocity, and a driven plate exciting a tuned one half a metre away.

**Loudspeaker and microphone.** Two moving-coil transducers from parts, 0.6 m apart: an oscillator switches the
speaker's coil, its plate pushes the air, the field moves the microphone's plate, and two op-amp stages, a peak
detector and a comparator light an LED. Moving coils needed flexible leads (a wire marked "flexible" conducts across
moving joints); a steel rod through a coil shorts its end faces (nylon rod) and bare leads beside a plate short to it.
Profiling it found natural convection taking most of every island's load time (now tabulated), a diagnostic string
built per Newton iteration (now lazy), and the op-amp state's error tolerance again (10 mV).

**Multirate.** The thermal network now steps apart from the rest, inside the same solver: a dry run sorts elements by
the rows they write; the fast partition holds temperatures and integrates the heat it delivers; the thermal network
catches up at synchronisations with that heat as a constant power. First tries leaked a few per cent of the energy —
the trapezoid carried the old input's derivative across each synchronisation, and the ledger integrated backward-Euler
steps with the trapezoid — fixed by restarting the thermal phase at each synchronisation and a method-consistent
ledger. Fluid temperatures had to stay fast (gas compression heats instantly). Gains are modest (20–25 %) until the
fast partition's own loads get cheaper.

**Hearing it.** Bodies on slides now sound in the game: each one's last 0.4 s of motion gives a fundamental
(autocorrelation) and a band-limited pressure waveform (Ü's harmonics), streamed to the client and synthesised at the
pressure the player's ear receives (dipole far field, physical distance). The trembler buzzes at 7.9 Hz; the
loudspeaker sings at 261 Hz. A lone body now keeps its motion history too (the acoustic element is built for one).

**Magnets attract iron.** First tried through the reluctance lattice (a magnet as a constant MMF): at cell resolution
it gave a 5 cm NdFeB cube a pull of 13 N against iron — fifty times too little. The method of images, with the
existing analytic magnet–magnet interaction, gives 533 N at 6 mm, falling to 14 N at 56 mm. The exhibit: two
identical loaded armatures, one held at its magnet, the other — 5 cm away — falling. (A weight touching a bearing
welds the slide to the frame: it sits clear of it now.)

**Liquids and distillation.** Gas vessels now hold liquids of condensable species, exchanging with the gas at a
rate set by how far each species' partial pressure is from x·γ·p_sat; the liquid's energy is the vapour's enthalpy
less the Clausius–Clapeyron latent heat, so equilibrium, evaporative cooling and energy conservation agree. Ethanol
joined the species. A still boils 10 % ethanol into a distillate of about half ethanol. Getting it to run: two pipe
corners vented upwards to the air (open pipe ends vent: capped now), and a gas passage's blend between its ends'
properties at flow reversal was so sharp (10⁻⁹ mol/s) that Newton flipped between them — it is 10⁻⁶ mol/s now.

**Fan cooling and the outside air's weight.** Duct outlets now blow jets (core, then 1/x decay) that cool the first
part they meet by cross-flow forced convection (Churchill–Bernstein). The exhibit: two warm plates on 5 W heaters,
a cranked fan blowing on one. Two bugs surfaced. The jet first read the vent's mass flow from an index the branch
allocates only at setup (it read a temperature: 293 "kg/s"). And with the fan still, the plate in its path cooled
faster anyway: the duct, open at two heights, drew 1.6 g/s through itself forever, because every vent of a
single-fluid network saw the same atmospheric pressure while the air inside weighed on its lower end. The gas
networks already gave each vent its height's pressure; now the single-fluid vents do too. That also showed an
older test (`crankedFanBlowsAirThroughADuct`) had been passing on the spurious draft, which ran backwards through
its fan: a fan turned at hand speed really moves 0.4 g/s.

**Bodies on moving bodies: rotors carried by slides.** A rotor whose bearings sit on a slide now resolves (it used to
lock): the slide carries its mass, and a cross-inertia element — the term m·s(θ)·ẋ·θ̇ of the pair's kinetic energy,
straight from Lagrange — couples them, so an unbalanced wheel shakes its platform and the platform's motion swings
the wheel. Tested against the textbook forced-vibration amplitude (to four figures) and for momentum and energy
conservation. The exhibit: a lopsided wheel on a sprung, damped platform coasting down from 30 rad/s — a little
shaking at speed, hard shaking as it passes √(k/M), calm below; the wheel is braked hardest at resonance, the
Sommerfeld effect, which nobody wrote. Everything that couples a rotor to the frame (gears, brushes, cams, coils…)
now checks that the two do not move past each other.

**Slides on rotors.** The reverse joint: a slide whose bushings ride on a turning body. Its Lagrangian adds the
rotor inertia it carries (m ρ²(x)), a cross term for guides off the radial line, and its weight in the rotating
frame; centrifugal force, Coriolis and the spinning skater all follow. Tested for angular momentum (10⁻⁹) and energy
(10⁻⁸) and for the spring-held radius k·x = m·ω²·r. The exhibit: two lead weights on a cranked beam, sliding out
against their springs. First try welded the beam to the bearing below it (touching blocks weld): the shaft now
runs a cell clear.

**Blades and wind.** A new part, the blade: a flat aerofoil whose every cell is a blade element in the air moving past
it, on any turning body or slide. A turning body's blades also get a momentum-theory inflow — one more solver unknown
per rotor, the induced velocity, closing blade thrust against Glauert's 2ρAv|V+v| (Buhl beyond a = 0.4) — so the Betz
limit holds without being written down. Minecraft has no wind, so there is a modelled one (weather, height, gusts,
veering). The wind turbine exhibit: four flat blades and the magnet generator on its side; 8 W at 8 m/s. The flat
plate's own drag holds C_p to ~0.48 at best (Betz 0.59), real enough for untwisted flat blades. An energy-ledger
residue of 3·10⁻³ in that exhibit is the generator's (the blades' own closes to 10⁻⁹): queued.

**The generator's ledger residue is accuracy, not a leak.** The magnet generator's energy ledger drifted ~6·10⁻⁴ of
the work at speed, the same with or without multirate and whatever the outer step. Capping the inner step showed it
converging as h² (16× smaller for 4× shorter steps): the trapezoidal rule's energy error on an AC waveform, where each
step's −h/4·Δv·Δi has one sign for a resistive load. Within the 10⁻³ local tolerance; recorded in VALIDATION.

**Iron saturates.** The iron's contribution to coil inductances is split into its eigenmodes (flux paths), each
saturating by a tanh curve whose level is set where the lattice says the iron's most loaded cell reaches its
saturation flux density; λ is the gradient of a co-energy, so the Jacobian stays symmetric and energy exact. The
Faraday ring turned out to be run past saturation by its own 3 A: its core levels off at 0.345 Wb, against N·B_sat·A
= 0.358 Wb by hand, and its make pulse is now cut short by it, as a real one would be. A saturable-reactor timer
exhibit was sketched and dropped: with electrical steel the magnetising current before saturation is as large as an
LED's; it wants permalloy.

**Phototransistors and an optocoupler.** A bipolar transistor with a "photo" window gets a photodiode across its
collector–base junction: the photocurrent is base current. Every light path that reached photodiodes (LEDs, ray
optics, daylight, hot bodies) now reaches a "receiver", photodiode or phototransistor alike. The optocoupler exhibit:
two circuits with no common conductor, an infrared LED against a phototransistor; 55 µA of photocurrent switches
7.7 mA. First wiring had both LEDs backwards (an LED's anode is its −axis end).

**Coloured glass.** Transparent materials may now absorb, with a spectral edge (two filter glasses after Schott RG630
and BG39); every traced segment inside them fades by Beer–Lambert, the loss warming the glass. Testing the glass's
heat turned up an old tracing bug: whatever a ray had deposited before reaching a glass surface was counted once in
each of the reflected and transmitted branches (mirror heating behind glass was overstated); it is now shared like
the light. The colour-filters exhibit: one filament, red glass one way and blue-green the other — red-hot only the red
side lights, white-hot both.

**Memory.** A 16 × 4 static RAM chip: 64 master–slave flip-flops behind the same multilinear address decode as the
ROM, written on the clock's rising edge while WE is high. Chips' stored bits are now part of an assembly's saved
state (keyed per chip in the state's keyed store), so counters, registers and memory survive recompiling (placing
a part nearby) and saving. The memory exhibit — address and data switches, a WRITE switch, four LEDs reading back —
first shorted its supply: single-cell parts in a row touch their neighbours with their side leads; those now face
away. (And the test first wrote nothing: `run(c, 0.02)` rounds to zero 50 ms steps.)

**Liquids flow.** Liquid pools in gas vessels now drain between connected vessels by their weight — levels, depths over
ports, the fall between ports — carrying composition and energy. Communicating vessels level out exactly. Fitting it
into the still took three things: a dry pipe must pass nothing (its smoothed depth alone let 5 % through, and pools
went negative); the gas pressure difference must not push the liquid (it coupled the liquid to millisecond gas swings
in small pipes); and the gas passages' reversal blend, 10⁻⁶ mol/s, had to widen to 10⁻⁵ — the drains nudged the
still's gas to and fro across zero, and Newton was flipping between the two ends' states (rejected steps 9451 → 18).

**A rendering pass.** A screenshot tour of the gallery turned up: tanks built of several blocks drew a complete little
tank (lid, rivets, gauge) in every block — the turbine's water tank was 48 gauges; joined tank blocks now draw one
shell running flush through their shared faces, a rim strip on the outside top edge, and one gauge on the top row's
west/north corner. A switch toggled before its island had compiled (just loaded) was forced open instead of flipped,
so the click did nothing — it now flips the part, which the island compiles from. The gas burner took minutes to
light: its chamber wall is now 1 mm (it lights about a minute after switch-on instead of two), and the tour no longer
waits on it (the burner costs about its whole real-time budget, so in the busy gallery it runs in slow motion). The
spinning-weights beam was made of upright slabs (a comb); its plates now lie flat. The "slanted" wires and posts in
some shots are perspective (a close, high, wide-angle camera).
Two more from the same pass: faces spanning a whole cell sampled one texel column (texture coordinates were
frac(x + ½), and frac(1) wraps to 0), streaking every flush face — the joined tanks showed it, one inset corner block
looking lighter than the rest; and a tour jumping far (showcaseOnly) photographed exhibits before their chunks had
drawn — the first look now waits for the section to be compiled and the island simulated.

**The burner, again.** Still too slow in the tour. A profile put two thirds of its time in the full sparse
factorisation (boxed hash maps), and counting showed why: the refactorisation's row-share backstop was absolute, so a
pivot ~10⁻¹⁵ of its row — the best its column offered — failed every solve and was chosen again by the factorisation
that followed. Judged relative to its share at factorisation, the burner runs 3× faster (whole suite unchanged). And
it lights sooner: a 2 Ω element (4.6 kW from the bank, peaking near 1500 K) brings the walls to light-off in 48 s
with igniter and gas switched on together, as the tour does; below 1.5 Ω its leads burn out. Its test now follows
the tour's schedule and checks that it lights within the minute.
The tour, meanwhile, had never seen it light: its conditions are checked on the client thread, and a server level hands
out no block entities off its own thread — the burner's "chamber above 1300 K" (and the governor's wheel speeds) read 0
until the step's cap ran out. They are now read on the server thread; the burner's shot comes 49 s after switch-on.

**A contact governor.** The first governor whose sensing is mechanical: a brass weight on a 2 mm slide on the DC
motor's rotor, pressed against a contact wire by a preloaded spring, the contact in series with the coils and a 47 Ω
resistor across it (the Teletype's motor governor). Two general additions made it: springs take a "preload" (fitted
shorter than free), and a travel contact may close between a slide and the rotor that carries it. Building it found
the usual traps of the grid: blocks beside a thin shaft do not grip it (a hub block on the shaft's cell does); a
linear bearing touching the weight it guides is mounted on that weight (the joint collapses, the weight welds to the
rotor); one wire cell touching a frame post votes the whole wiring net onto the frame (the circuit opens there).
It governs at 36.8 rad/s and holds within 0.3 % against a brake, drawing 0.4 → 5.9 A; the smooth 0.2 mm contact
ramp makes it a proportional regulator rather than a chattering one (recorded as a limitation).

**A reflux column, at last.** The first attempt (three wall-cooled stages) failed on near-empty pools; this one gives
the trays something to hold. Tanks take a "weir": their outlet to the vessel below stands that high over the floor.
Three things then had to change in the general models. (1) Liquid leaving over a port is weir flow, ∝ H^{3/2} over the
port's rim: the old depth factor H/(H + 1 mm), linear near zero, let a full-face passage drain a tray at 5 mL/s from
the 12 µm "depth" its smoothing left below the weir. (2) A pool's equilibrium pressure entered through max(0, l), a
kink a near-empty condenser hovers on; it is now smoothly floored. (3) A gas passage's weight term used the upwind
density: with a chilled condenser over a warm tray, flowing down the head is heavy and flowing up light — no
consistent flow near zero, and the step size collapsed as the flow reversed; it is now the mean of the two ends'.
Then two design lessons: the condenser's fins, sharing walls with the top tray, chilled the whole stack through the
steel (the pot cooled at 150 W) — a short vapour line between them fixed it; and the trays need ~200 W to warm through.
At 360 s the pools read 6 / 5 / 9 / 22 % ethanol up the column and the condensate 51 % (separation factor 16.5 against
the still's 11; after 15 minutes, 53).
The tour, too: its steps' conditions and waits started when the client asked the server to act, not when it had —
in the busy gallery the burner's first shot was taken from the previous exhibit, and a later exhibit's shots before
its own camera move (with the burner's logger still on screen). Each step's clock now starts once its action has run.

**A condensing steam engine, shelved.** Exhausting the steam engine into a sealed 40 °C condenser works physically —
the engine runs nearly three times as fast under the same brake (Watt's separate condenser, unprogrammed) — but the
low-pressure steam volumes sit on the dry edge of the saturation dome, where Newton two-cycles: 12× real time. Recorded
in NEXT_STEPS (item 6) with what was measured; the solver now reports its last non-converging unknown on request.

**The condensing engine, unshelved.** TRACE and a scan of the steam tables near the dry edge found the cause: the
C¹ join beyond the dome interpolated ρu and ρ separately from the edge, where u climbs steeply on the dome side and
lies flat beyond; the cubic overshot — at 150 °C u rose 110 kJ/kg and fell back within s = 1.00–1.06 — so mass and
energy had two states and Newton cycled between them. Straddling the edge and interpolating u itself made u monotone;
the condensing engine's first five seconds went from 277 s of CPU (50 000 Newton failures) to 6 s (10). The engines
then showed what had been hidden: both stalled within a minute — the fire was hot copper that cooled, and the
cylinder wall cooled below the steam's saturation temperature so each admission condensed. A heating element on mica
under the boiler and a jacket element on the cylinder fixed both (the jacket's history is Watt's). The condensing
engine now carries twice the atmospheric engine's brake at the same 107 rad/s.
A feed pump to close the cycle did not converge yet (open elbow ends, a kinked check-valve lift, flow reversal in
two-phase lines, a cavitating pump on 19 cm of suction head) — recorded in NEXT_STEPS item 6 and reverted.

**Power electronics begins: a bridge rectifier.** No AC source part exists (by design: alternating current comes from
machines), so the rectifier hangs off the hand generator, whose coils are now joined across the middle and their outer
leads routed to a bench of four diodes, a smoothing capacitor and an LED. Two wiring lessons: the router avoids only its
own nets, so its first try ran a wire over the coils' series link and shorted coil A; and an insulated segment is bare
only at its own two ends, so a hand-laid stub and a routed wire that turned a corner beside it never touched — the
router now takes the coil leads directly.
(One tour hung at shutdown — the integrated server spinning in vanilla's chunk-unload loop for ten minutes — and the
next run of the same tour closed normally: intermittent. Found after a second hang: see "Shutdown hang" below.)

**A boost converter.** Four red LEDs in series (7.6 V) lit from 5 V: a Schmitt-trigger RC oscillator switches a small
MOSFET across a 2000-turn coil; when it opens, the coil drives its current through a diode into the output capacitor.
It worked at the first build (7.8 V, LEDs at 4.6 mA; 4.4 V and dark without switching), but at 100 Hz it cost 16× real
time: the switch node rang at ~50 kHz between the coil and the semiconductors' picofarads, a real effect that the step
control duly resolved. An RC snubber (0.1 µF, 1.5 kΩ ≈ √(L/C)) — what a designer would fit — cut that threefold, and 26 Hz
with a larger output capacitor another fourfold: ≈ 4× real time, in slow motion in game like the engines.

**An H-bridge.** Two complementary MOSFET pairs, gates joined (each upright a CMOS inverter), the brushed motor across
their outputs; switches pull the inputs up. FORWARD and REVERSE turn it opposite ways; both open shorts it through
the P-channels and it brakes on its back-EMF. The first test expected the brake to stop it in a second and reverse to
match forward's speed within eight — the rotor's 14 s time constant says otherwise; the test now follows it.

**PWM speed control.** The H-bridge's input driven by pulses whose width a knob sets. The textbook diode-steered
oscillator (charge through one side of the pot, discharge through the other) worked but cost 12–20× real time, Newton
failing at its reverse-biased diode nodes; a ramp-and-comparator design (Schmitt RC ramp, op-amp against a knob
divider) failed far less, and at 5.6 Hz — the heavy rotor averages anyway — it runs at 2–3× real time. Knob down: −1.6
rad/s after 4 s; up: −5.9.

**Shutdown hang.** Twice a tour's integrated server spun forever in ChunkMap.processUnloads while stopping. The stack
showed scheduleUnload rescheduling itself — a chunk's save future kept being replaced, i.e. something kept requesting
chunks that were unloading. The level's save event (fired by the shutdown's own save) ran saveAll, which called
setChanged() on every assembly block entity; setChanged also updates comparator outputs, reading neighbours' block
states, and next to a chunk border that requests the neighbouring chunk — mid-unload. The periodic save now marks the
chunk unsaved directly, only if it is loaded (getChunkNow → setUnsaved). Three tours in a row closed cleanly after.
This could also have hung a player's quit-to-title.

**An inverter.** The H-bridge driven in antiphase by an oscillator and a second inverter alternates 9.5 V across the
primary of Faraday's ring: the secondary swings ±46 V and two LEDs back to back take turns. It also shows inrush — the
first half-cycle, starting at zero flux, saturates the core (8.9 A). Halving the frequency did not halve the cost:
Newton keeps failing at the logic chip's output whether or not it is switching, which is now the main open cost of the
power-electronics exhibits (recorded in NEXT_STEPS).

**A propeller cart.** Blade elements on a rotor that rides a slide: the element velocity gains the slide's, the force
splits into the rotor's torque and the slide's push, and the momentum-theory disc meets the air at the cart's speed.
Newton's third law holds to 10⁻⁹ and the cart's momentum matches its impulse to 2·10⁻⁵. The exhibit: a flywheel and
a two-bladed propeller on a light cart along a two-metre rail — it creeps off and runs the rail's length in about seven
seconds. (Earlier probes, in passing: the power-electronics edge cost is now profiled in NEXT_STEPS.)

**Hysteresis.** A play operator on each body's dominant iron mode: the flux follows a state that lags the
magnetising current by a coercive width, so cores keep remanence and lose their loop's area each cycle, as heat in
the iron. Getting it right took several corrections, each found by a test: the smoothed dead zone had a slope inside
and the remanence crept away (now flat inside); at τ = 0.1 ms a saturating core's state raced the current and lost
several times its loop (now 1 µs); a loop on every eigenmode counted the same iron's loss tenfold (now the dominant
mode only); and the coercive width, first taken from the iron's field per ampere at saturation, made an open relay
core's coercive current as large as its working current — at the coercive point the flux is zero and a gap takes no
mmf, so it is now ∮H_c dl per turn, Σ H_c·V·|B|/μ from the lattice's field. (A suspicion that g/τ amplified Newton's
residual was wrong — the test had sampled a bursty loss; it now integrates over the solver's steps.) The trembler
showed a model inconsistency: armature pull is computed from current, so a hysteretic relay core let go early; relay
cores stay anhysteretic until the pull is flux-based. Faraday's ring now keeps 0.69 Wb of remanence; the inverter's
core loses 0.39 J a cycle, matching its loop area to 1.5 %.

**A flaky in-world test, explained.** computerRunsItsProgramInWorld failed once in the busy demo world with ACC = 6 at
PC 4 — neither the 4 before nor the 8 after: the adder's carry still rippling at the single tick the test sampled. It
now checks every tick from 300 until the state holds (succeedWhen), as a logic analyser would wait for the bus to
settle.

**Link bars.** A new part, LINK: a rigid bar pinned at its end faces to whatever bodies touch them (a straight row of
link cells is one bar), enforced as a holonomic distance constraint with a Lagrange multiplier at velocity level and
Baumgarte drift correction. Ends may be fixed, turning or sliding, in the ground frame or on a carrying body. Tested
against a four-bar's and a slider-crank's closed-form kinematics; the four-bar exhibit's rocker swings exactly between
its toggle angles. The exhibit first showed a 0.095 J energy error that turned out to be physics: the rocker started
at rest while the crank turned, and the bar's first step is an inelastic impulse; started consistently, the error is
6·10⁻⁴ J. Rendered as a bar between the pins' synced positions, each cell drawing its share.

**Flyball governor.** Link bars became paths: link cells face to face make one bar running straight between the
path's outer faces, so slanting bars can be built from block-sized parts (every straight bar between a radial
weight and an axial sleeve sits at a dead point). A sleeve pinned on its spindle's axis may be linked off the
spindle (a thrust collar). With one-sided stop collars on linear bearings and a potentiometer fitted with its wiper
anywhere on the track (a rheostat starting at zero), Watt's governor is built from parts on the DC motor: weights out,
sleeve down, push rod up to a bell crank on the rheostat's shaft. It holds 30 rad/s within 2.6 % under the brake. Two
layout traps on the way: bushings touching their weights weld them to the spindle, and a commutator at angle 0 puts the
brushes in a gap.

**The demo as a player plays it.** The tour used the simulation's own API: switches toggled, cranks given a force
that never let go, parts placed and removed by block edits, the data logger attached by a call. It now has hands: it
stands within reach, aims the real crosshair at the part and presses or holds the in-game keys, so every click
travels a player's road. At once it found a crank a player could not turn — clicks were resolved by the cell, and a
crank sharing its cell with its shaft gave the shaft — and the clicked part is now the one whose outline is hit.
Each step now checks the physics it should cause, shown live on a caption (exhibit, purpose, action, measured
values, PASS/FAIL) and written to a report. The world itself is now built by player placement through a new part
editor (material and parameters set in game), which flushed out what a player could not build: linear bearings,
nuts and coils on shafts, a crank's facing, unreachable parts, and the wire router routing through components added
after it was made. A player can also now grip any turning body and turn it by hand (a flywheel given a turn, a motor
nudged off its dead point), and a held item takes the click instead of the machine.

**What froze the server in the tour.** The full tour stalled for two minutes at the gas burner and read its chamber as
0 K. Timing every rebuild, compile and island step over a second pointed past the burner to its neighbour, the still,
taking 30 s a tick — and reproduced in the core: idle in noon sunshine, after 58 s a vent pipe's gas went to −8 900 K
in a Newton iterate and the step shrank to 10⁻¹¹ s. A ledger of every vessel's water showed why: the total was
conserved, but two pipes' pools were negative. Both the gas passage (`MixtureBranch`) and the liquid passage
(`LiquidDrain`) carried the *blend* of their two ends' contents through zero flow (blended to keep Newton from flipping
at reversal), so a trickle out of a pipe holding no vapour or liquid took its neighbour's — the full pot's
concentration. Now each passage splits its flow smoothly into its two ways, each way carrying only its own end's gas or
liquid (and that end's enthalpy), so nothing is sent that is not there. The still now idles and boils with no Newton
failure at all, ten times faster than real time (it was five, until it failed); the whole core suite passes unchanged.
A wall-clock limit on one island's step per tick (60 ms; off where in-world tests lift the budget) keeps a burst of
costly switching — the chip-level inverter takes 1–3 s a tick near the player — to slow motion for that machine rather
than a stalled server.

**Tour checks that measured the wrong thing.** The governors' supply current comes in pulses (a contact governor's
contacts, a commutator's segments): one instant's reading said 3.04 → 0.024 A. Speeds and currents are now averaged
over five seconds before and after the brake; the core holds the electronic governor at −3 % braked with the battery
current doubled. Settling is judged on the means of consecutive 1.5 s windows (a rippling speed never looked settled
to two instants, and the flyball waited out its two-minute cap twice). The explosion's logger was showing the can's
wall, not its gas: the tour's crouch reached the server after its click, so a plain use in the air changed the channel
rather than the time base — it now crouches first and checks the screen's channel. The relay records its armature
before the click; the Geiger counter counts its counter's steps; the unbalanced rotor's platform swing is taken in
one-second windows over its whole coast-down; the H-bridge is turned backwards by hand for REVERSE; the press handle
is held until the pump has moved.

**Batteries for supplies, and the watched machine first.** Forty-four exhibits ran from capacitor banks charged before
the gallery opened — a state no player could set up, and one that faded with time. They now run from batteries: the
lead–acid element became `Battery` with a second chemistry, nickel–metal hydride, its voltage the Nernst curve of the
nickel electrode (four cells make the 5 V logic supply that lead–acid's 2.1 V steps could not). One exhibit test moved:
the spectrometer's 12.75 V lead–acid ran the filament red-hot enough to light the red channel early, so its series
resistors went from 1.5 to 1.7 Ω. In the world, every island within 48 blocks of a player used to share one pace, so a
costly neighbour slowed the exhibit being watched: the budget now goes first to each player's nearest machine. The
fan's windmill and the unbalanced rotor's resonance, both failing in the tour, now pass. The build check now compares
every rotor and slide with the definition (parts, mass, inertia, travel): all equal — so the press and propeller cart,
still failing identically every run, are not built differently; their hands are the next thing to trace.

**The part editor, made for people.** The creative tab offered some 150 variants; it now holds one of each kind of
part, and the editor (right-click the air with a part) makes any variant. Its form comes from a schema in the core
(`PartParameters`): for each kind every parameter the simulation reads — label, unit, kind of value, range, default
(or what decides it when automatic), a line on what it does, whether it is advanced, and when it applies (a MOSFET's
threshold only for MOSFETs, a ROM's bytes only for a ROM) — and the materials it may be made of (no magnet of copper,
no resistor of salt water). Switches are checkboxes, codes are named choices (fluids, nuclides, gates, directions),
quantities are sliders over their range (logarithmic where it spans decades) with a ✎ to type an exact value ("4.7k",
"220 mΩ", or a bare number in the unit shown); values read as people write them ("0.5 mm", "4.7 kΩ", "13 V/µs"). The
stock variants are named presets ("12 V lead–acid, 7 A·h", "Transformer secondary on a core"); a line at the top works
out what the settings come to (a spring's stiffness, a coil's resistance and how full its winding space is, a lens's
focal length, a battery's voltage and energy). The server checks every specification against the same schema, and a
test checks that all 72 exhibits and every preset pass it. A part with many possible materials (a block: forty) opens a picker that shows them all at
once, grouped by kind with their properties on hover; a slider at zero says what zero means ("0 (flat)", "0 (none)");
and pick block (middle click) on a placed part gives an item with all of its settings, to build another like it.

**Three exhibits that were wrong for the world they stood in.** The press and the propeller cart failed identically
on every tour while their core tests passed, and the build check found their bodies exactly as defined. A log of the
hand's pushes showed the press's pump being pushed all along; a log of its slides showed it already sinking before
anyone touched it: the pump rod's own 0.2 kg on its 1.2 cm piston makes 18 kPa, more than the 3.4 kg ram needed, so
left running for the length of a tour the pump lay on its stop. A second lead weight on the ram (31 kPa) and a test
that the press, left alone 30 s, still answers a push. The propeller cart ran along the west wind: the wind parked it
against its east stop before the tour arrived, and a hand-cranked propeller could not push it back. Its rail now runs
north–south, across the wind. And the governors were judged mid-recovery: two consecutive windows of speed agree at
the bottom of a dip as it turns, so settling now needs three to agree, and ten seconds after braking. Cut-short steps
(the 60 ms limit) are physically consistent: an engine cut to 1 ms a call reaches 148.2 rad/s at 20 s exactly as one
run whole. Part items' tooltips now give their settings as the editor names them and say how to edit and copy a part.

**Half an engine.** The gas engine stopped in the tour but ran alone, and ran for five minutes, through recompiles, cut
steps and slow motion in the core. Its island logged at its first tick showed why: it began at 8.7 rad/s, then (after
a fix) 1.7, instead of the 30 it is built turning at — it had already run as half of itself. The engine straddles a
chunk border; chunks arrive one by one, and the half in the first chunk (crankshaft, brake, flywheel) ran without the
cylinder and glow plug and braked to a stop. Three ways in: the demo build's check force-loaded the gallery for three
seconds while machines ran (now the simulation is held still meanwhile); a machine whose reach crosses into a chunk not
yet loaded is held as it is until it can be whole; and since a chunk counts as loaded a tick before its parts register,
a machine rebuilt as parts arrive waits half a second for the rest. Requiring every chunk a machine touches to tick was
tried and dropped: it froze the GameTests' structures, whose neighbouring chunks load but do not tick.

**Two more exhibits that only worked at first.** The Geiger counter ran its tube from a capacitor charged to 500 V:
it leaked below the tube's 350 V starting voltage in two minutes, and the gallery had stood for longer than that when
anyone arrived. It now has a battery of 240 small cells, 510 V — as survey meters had. The flyball governor held 30 rad/s
in its eight-second test, but over a minute it crept up until the weights lay on their stops: with a 6 Ω rheostat, even
fully turned in, the motor at this light load ran faster than the governed speed. With 150 Ω it holds 27.6 rad/s
indefinitely; braked (3 N) it settles 8 % lower — the droop of a governor without a reset, as its sign now says. Its test
now judges the steady state, and the tour allows that droop.

**Shelter from the wind.** The propeller cart still failed one full tour in two: the wind veers ±25° about west, and
a quartering wind on the propeller's disc made it a windmill that pinned the cart to the end of its rail before
anyone came — stronger than a hand-cranked propeller can pull. Wind now has shelter: what a machine's blocks see
upwind within 16 blocks, if it is a solid world block, puts that share of it in a lee where the wind is a quarter.
Exhibits can now have scenery — ordinary blocks such as a wall — and the cart has a windbreak along its west side.
The build check counts only an exhibit's own bodies (the periscope's mirrors take the engine next door into their
island by line of sight): all 72 exhibits now match their definitions with no difference. The tour's "LED lit" waits
read the server, as its checks do (a stale client gauge ended the loudspeaker's wait early).

**Starting cold.** The fan-and-windmill train, the electronic and contact governors and the unbalanced rotor no longer
open turning: a player starts them — cranks the fan, closes the switch and gives the motor a turn by hand (a
two-segment commutator can rest with its brushes in a gap), cranks the wheel up past resonance and lets it coast. Their
core tests start them the same way and judge the state they come to. The flyball governor stays turning: its heavy
rotor takes minutes of the world's slowed time to come up to speed. Its test brake is 2 N, a load the governor rides
with a few percent of droop.

**A latch balanced on its edge.** The Geiger counter stopped counting in the world on its 510 V battery, while in the
core it counted 150 a minute, cut steps or not. Its counter's first two bits sat at exactly ½ — the unstable balance
point of a latch's regeneration, where a symmetric blend of 0 and 1 leaves it and nothing ever moves it. A real
latch's halves are never matched; the chip's latches now balance a hair above the middle (1 %), so a state left at ½
falls to 0 and the counter counts again. The flywheels and the eddy brake now open at rest too: the tour
spins each flywheel by its rim and cranks each eddy disc, and their tests do the same (the eddy brake's energy check
counts the disc's heat from the moment it is let go — cranking against the magnets warms it first).

**A starting handle.** The gas engine was the last machine opening already turning. It now stands still with its
fuel shut, and a player starts it the way small hot-head engines were started: open the needle valve, swing the
starting handle until it fires, and put the brake on once it runs. The physics had things to say about each step.
Left open while it stood, the needle flooded the mixing box to 98 % methane, past what burns: hence the fuel shut.
On a 10 cm handle a hand turns it at 30 rad/s, slow enough that the hot wall lights each charge before the top of its
stroke and kicks the crank back — the hot-bulb engine's known habit; a 5 cm handle a hand can spin to 60 rad/s gets it
past that. And with the brake on it fires but cannot pick up, so it is started unloaded. The handle drives only while
the shaft turns no faster than a hand, so a running engine leaves it behind like a dog clutch. In the world the tour
opens the fuel, holds the handle until the engine outruns it, and checks that it then carries the brake (77 W).

**A shut valve that wasn't.** In the full tour, after half an hour standing, the engine would not catch. The mixing
box held more methane the longer it stood, the needle shut. The cause was in every gas passage: the smooth split of
a flow into its two ways (each carrying only its own end's gas) had a fixed width of 10⁻⁶ mol/s, so near zero flow
each way carried half of that — whatever the opening. A shut 25 µm needle traded bottle methane for box air as
freely as an open 6 mm pipe. The width stands for diffusion through the bore, so it now scales with the open area
below a 6 mm bore's: a shut valve passes a millionth of what an open one does (`MixtureFlowTest.aShutValveExchangesNothing`).
The leak had also been propping the engine up. Without it, on the 5 cm handle it caught only now and then: at 60 rad/s
the needle's fixed flow is rich, and a slowing engine gets richer. On a 4 cm handle a hand reaches 75 rad/s; it fires
while still being cranked and runs away to 225–273 rad/s, after standing a minute, ten or thirty.

**Flying, not jumping.** The tour's player used to teleport from viewpoint to viewpoint and to each spot where its
hand had a job. It now flies, as a player would: up clear of the tallest thing between, across at a creative
player's sprinting pace (capped at six seconds for the long gaps between halls), and down, easing in and out and
turning towards the new view on the way. It flies in spectator mode so it can pass through a crowded gallery, and
lands in creative for the hand's work. Steps start their timers on landing. Flying showed up one assumption: the
capacitor discharge was judged a moment after the click, which had been instant. With half a second's flight back
from the switch, the nichrome had already cooled to 530 K, so it is now judged on its sampled peak.

**Wöhler's rig by hand.** The fatigue rig was the last test machine that opened spinning, at 150 rad/s. A hand turns a
crank some 37 rad/s at the most, so each test shaft now carries an 8-tooth pinion driven by a 40-tooth gear on a
countershaft with a crank: five turns of the shaft for one of the hand, 176 rad/s in fifteen seconds. The first
layout locked: a gear or crank set directly against a bearing's cell is fixed to the bearing's housing,
which held both shafts to the frame. That was the compiler's error, not the design's: a wheel beside a bearing on
the shaft the bearing carries turns with the shaft, and now it does (the gas engine's handle had hit the same trap).
The aluminium shaft still cracks at exactly its Basquin life (2099 turns, 80 s), the hand's spin-up included.

**What the full tour found.** With flying, 137 of 139 checks passed over the 72 exhibits in 35 minutes. The
inverter's chip-level island ran at a tenth of real time while the server was busy, so a fixed 6 s wait saw less than
one of its 2.6 Hz cycles; it now waits until both LEDs have lit. The flyball governor, braked, did not take more
current: in the world it governs lower (27.4 rad/s at 0.23 A against 29.8 at 0.20 in the core, as if under more
drag), near where its springs hold the weights on their stops. Two islands failed and restarted at rest (the EM-4
after the tour left it, the hydraulic jack mid-stroke), neither reproducible in the core with the world's timings.
