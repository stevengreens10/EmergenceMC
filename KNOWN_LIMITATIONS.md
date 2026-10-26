# Known limitations

Honest list of what is approximated, missing, or placeholder. Nothing here is hidden behind scripted
behaviour; where physics is missing the effect is simply absent.

## Electromagnetism
* **No ferromagnetic materials in the field model** (air-core only). Iron cores, transformer cores and
  reluctance/cogging from steel are not simulated; machines are weaker than iron-cored real ones.
* No eddy currents in structural conductors (no magnetic braking of a copper disk yet).
* Magnetic coupling only between static bodies and rotors that share one axis line; non-coaxial moving
  bodies are not coupled (warning issued).
* Magnet remanence does not yet depend on temperature, and magnets do not demagnetise under reverse field;
  magnets are destroyed at the Curie temperature only.
* No electric field / capacitance from geometry yet: capacitors are manufactured parts with a parameter.

## Electrical
* Ferromagnetic cores saturate (each eigenmode of the iron's inductance by a tanh curve: modes saturating
  independently, not the material's B–H knee) and show hysteresis only in the dominant mode, as a parallelogram loop
  (play operator: remanence and loop loss, no minor-loop shape, no eddy loss), and not in cores pulling armatures
  (whose pull is computed from the current, not the flux) and are resolved at the cell scale; iron only
  affects coils on the same rigid body (no reluctance torque, no magnet–iron attraction, no eddy currents in iron).
* Lumped circuits only (no transmission-line effects); no skin effect.
* Capacitors have no ESR, leakage or dielectric breakdown; diodes have no junction capacitance, series
  resistance or reverse breakdown; no transistors yet.
* Brushes always belong to the stationary frame (brushes on rotating bodies are not supported); brush
  contact uses a fixed contact resistance (no current-dependent brush drop or arcing).
* Bulk metal parts are single electrical nodes (internal resistance neglected).

## Mechanical
* Dry friction is a Dahl bristle model: no Stribeck dip (static and kinetic friction are equal) and no
  pre-sliding hysteresis beyond the bristles' 1 ms × ω_s deflection. A held load relaxes at 0.1/s of its smoothing, so
  a part held at a fraction f of its friction limit still creeps at ≈ 10⁻⁴·ω_s·f/(1 − f), 10⁴ times slower than the
  speed-only law it replaced (which let the flyball governor drift ~2 rad/s across its dead band in five minutes).
* Rigid bodies rotate about fixed axes (bearing joints) or slide along them (linear bearings on the frame); a rotor
  may ride on a slide, or a slide on a rotor (two degrees of freedom each), but no deeper chains (no slide on a rotor
  on a slide). A carried slide's bushings feel only its weight, not the centrifugal and Coriolis loads. A carried slide can close a
  contact on its rotor (a centrifugal switch) and be linked by link bars to other bodies on that rotor, and reach off it only
  from a sleeve pinned on the spin axis (a thrust collar, frictionless). Link bars have frictionless
  pins and no mass of their own at their ends (the bar's mass rides with the frame), and do not bend, buckle or break;
  a linkage driven through a dead point (crank and coupler in line with nothing to carry it through) can lock or jump
  branch. A travel contact's conductance is a smooth ramp over 0.2 mm, so a contact governor
  modulates like a variable resistor rather than chattering on and off (no contact bounce or arcing). No free 6-DOF bodies, no collisions. A rotor riding a slide is coupled only to that slide and what
  rides on it: frame-mounted gears, brushes, cams, cylinders, light barriers, coils and magnets ignore it (gears,
  coils, magnets, cylinders and fan blades warn). Gravity acts on each rotating body about its own axis only (no
  joint reactions on moving parents); a carried rotor's bearings are not loaded by its unbalance's spinning force.
  Unsupported groups are treated as resting on the world.
* Gears: external spur meshes between parallel shafts only; no backlash, tooth compliance, internal/ring
  gears, racks or bevel gears.
* No structural stress/deflection, buckling, fatigue or fracture yet.
* Lead screws need the screw turning in bearings and the nut on a slide: a nut turning on a fixed screw (or
  a screw turning in a fixed nut) is a helical joint, not modelled. Thread efficiency is first order in μ'.
  A self-locked screw creeps at ~0.4 µm/s (regularised Coulomb friction).
* A rack does not disengage: its slide is stopped where the pinion would leave its teeth.
* Vessel bursting: room-temperature strength (no creep or hot weakening), each part's own cells as its walls
  (a tank of several parts is judged part by part), no fatigue, no plastic bulging before the tear, no collapse
  under external pressure; the wall stress is checked every step.

## Thermal
* Lumped bodies (one node per part); large parts have no internal gradients.
* Forced convection only from the free jets of duct outlets onto the first part in their path (one
  correlation for all shapes; a part partly in the jet gets the jet's h over all its surface; the jet's spread
  past the part, wakes, and air stirred by spinning parts are not modelled); otherwise natural convection.
  Radiation exchanges only with the ambient, not between parts.
* Ambient is a fixed reservoir per island (biome-based); world blocks are not thermal masses.

## Electrochemistry
* Activities equal concentrations; no passivation films, no pH-dependent speciation or complexation.
* Exchange current densities are order-of-magnitude literature values; impurities (which make commercial
  zinc corrode much faster in acid than pure zinc) are not represented.
* Solution colour uses three-band absorbance estimates (display only); deposits are not drawn as
  crystals, and dissolving electrodes do not visibly shrink.

## Fluids
* Quasi-steady branches: no fluid inertia (no water hammer, acoustics, Helmholtz resonance) and no flow
  kinetic energy — a jet's velocity is not carried into the next volume, so impulse turbines fed by a
  nozzle are under-powered (the throttling loss stays in the gas as heat).
* Phase change for water only (`water_steam`); other fluids are single-phase. Phases are in equilibrium
  in each volume (no superheated liquid, no subcooled vapour, no nucleation delay) and separated by
  gravity; no non-condensable gas mixed with steam (a steam vent's back-flow is steam, not air: a brim-full water
  standpipe overflowing at a trickle, inside the upwind blend's 1 mm/s band, carries a blend of water's and steam's
  enthalpy — some 0.1 W of spurious heat in the sunlit jack). Ice
  is not modelled (T clamped at the triple point). Slightly superheated (≲ 10 K) and very slightly
  compressed states pass through smoothing joins with a few per cent pressure error. One fluid per
  connected network; open vessels hold no free surface to the air.
* Combustion in a cylinder is well mixed (one zone): no flame propagation, no lean or rich flammability limits (a hot
  surface burns any proportion), no knock. A glow plug's temperature creeps up slowly while the engine runs (about
  2 K/s at 1300 K): over a long run it nears its 1800 K limit.
* Two-phase flow through branches is homogeneous (no slip, no flashing choke); film boiling beyond the
  critical heat flux is not modelled (flux is capped instead).
* Rotors: one blade row at the mean radius; swirl is not passed to a downstream rotor; no tip leakage or
  radial (centrifugal) machines; blades have no camber.
* Gas properties are ideal-gas (Z ≈ 1); real-gas effects at high pressure are ignored.
* Cavitation is lumped (a void fraction per volume, pressure held near p_v by a regularising cavity modulus); no
  bubble dynamics, collapse pressure pulses, erosion, or dissolved-gas release (oil, which has no vapour-pressure
  data here, cavitates near 0 Pa where real oil releases air at tens of kPa).
* Internal natural convection in closed vessels is not modelled (laminar forced-convection Nu as floor).
* Pistons: massless piston and rod, no blow-by; crankcase always at ambient pressure. Cam-worked valves
  take no force from the cam (valve-gear work and inertia neglected).
* A single small blade row cannot make a self-sustaining gas turbine (consistent with real physics at this
  scale); jet kinetic energy is not modelled, so impulse turbines are under-powered.

- Water acts on slides and on bodies turning about a fixed axis. A stream keeps its speed whatever a wheel takes from
  it, so a heavily loaded wheel in a narrow race is too strong. The water's added mass and reflected waves are
  ignored, and a pond's level does not rise as floats displace it. A stream's speed comes from Manning's formula at
  Minecraft's fixed slope, not from how much water feeds it.

## Structures
* Linear statics: no buckling, dynamic or impact loads, vibration, fatigue or creep; first yield is failure
  (no plastic redistribution). Large deflections (a 1.25 mm steel shelf sags 70 mm) are computed linearly.
* Slender-beam members even for cube-shaped cells (no shear deformation); contact faces fully bonded (no
  separation, sliding or fasteners); only blocks and vessels are checked for failure, other structural
  parts act as stiff connectors; pressure-vessel wall stress (bursting) is not yet checked.
* Broken parts drop as items; unsupported remnants are removed rather than falling as bodies.

## Electronics
* Transistors: no breakdown, high-injection or short-channel effects; MOSFET body tied to source.
* Logic chips are a reduced-order model (thresholds of ratioed 74HC parts, fixed 10 ns delay, no input
  leakage or ESD diodes); large digital systems still run in the analog solver (no event-driven engine).
* Light: LEDs, daylight and hot parts illuminate photodiodes (hot parts directly only: not by way of mirrors and
  lenses, and not lighting other parts); torches and Minecraft light do not. Tungsten does not oxidise in air (a real
  filament needs a bulb of inert gas). Diffuse
  reflection is absorbed or lost (only polished mirrors and glass redirect light); glass is lossless (no bulk
  absorption); metal reflectance is unpolarised and independent of angle; sunlight is one wavelength (700 nm) and a
  point at infinity (no 0.53° solar disc: a perfect focus is sharper than a real one). LED light by way of mirrors
  and lenses is traced once per compile with moving bodies frozen in their compiled pose (a spinning mirror does
  not sweep it; the direct path's chopping by one turning body is modelled). A small object lying wholly inside a
  coarse tube seen only after a reflection can be missed. Sky exposure is the receiving part's block, not the path.
  Light couples only parts in one simulation island: islands join along optical parts' lines of sight (a 60° cone,
  15 blocks for mirrors, 8 for lenses, LEDs and photodiodes), so a mirror aimed at something further, or outside
  the cone, does not light it. Shapes: boxes, plates, spherical lenses and prisms (no aspheres); a canted mirror's square plate overhangs its cell
  slightly in the drawing. Mirrors' aim and tilt are set by presets or exhibits (no in-game aiming tool yet).
  The LED model is drawn dome-up whatever its "face" direction.
* MSI chips are behavioural (multilinear logic, single propagation delay, no setup/hold violation model
  beyond the master–slave windows). A ROM's contents are set when it is made (item parameters/presets);
  there is no in-game programmer yet.
* The stepper is air-cored (no ferromagnetic teeth are modelled), so its torque is peaked near alignment and a
  quarter-turn step takes ~3 s; two-coil ("two-phase on") drive gives no midway detent with this torque curve.
* `Router` is an exhibit-building aid (maze routing with insulation colouring), not an in-game tool.

## Not yet started (see ROADMAP.md)
Combustion/general chemistry, CPUs and memory arrays, optics, acoustics, structural analysis, manufacturing, sensors beyond the
multimeter, oscilloscope/data logging.

## Gameplay / UI
* Parts are obtained from the creative tab; there are no survival recipes or manufacturing yet.
* No in-game reference book; signs in the demo world and tooltips only.
* Block scale (default): one part per block (plus a part mounted on a shaft); long parts are rows of
  single-cell parts. Miniature (tweezers): parts must fit within a single block.
* Block-scale islands include every block within interaction reach (2 blocks, up to ~6 around large
  gears): separate machines built closer than that are simulated as one island (correct, but costlier).
* Simulation stops in unloaded chunks (state is saved and resumes on load).
* Moving parts are drawn ~75 ms behind the simulation (snapshot interpolation between packets); a server
  hitch (an island compiling) holds them until packets resume.
* Hitboxes follow the drawn geometry where the part was placed: a slide or rotor that has moved keeps its
  hitbox at the placed position.
* Visual minimum sizes: wires and shafts are drawn at least ~0.14–0.18 cell thick so thin filaments stay
  visible. Gear teeth are drawn with a simplified trapezoidal profile; their phase is cosmetic.

## Control parts
* A potentiometer's housing must sit on the static frame or its shaft must (one of the two sides is ground);
  a pot riding on one moving body and turned by another is not supported. No wiper noise, wear or contact bounce.
* Op-amp: one pole, no input offset/bias currents, no output current limit beyond the 75 Ω output resistance,
  no common-mode limits on the inputs; a single (not dual/quad) package per part.

## Instruments
* The data logger records one part per player, one channel on screen at a time; it loads nothing (ideal,
  infinite input impedance) and adds no noise or quantisation beyond float storage; its record lives only in
  memory (lost when the server stops) until exported. The multimeter's readings are ideal apart from rounding.

## Gas chemistry
* Gases only (no condensed phases, solutions or surface reactions in `GasReactor`); well mixed (no flame
  propagation or stratification); global methane kinetics without CO, radicals or dissociation, so flame
  temperatures are high by ~100–200 K and no CO forms in rich mixtures; ideal-gas mixing (no real-gas effects at
  high pressure: a 200 bar methane cylinder holds ~20 % more than the ideal gas law says). Flames are
  well-stirred volumes, not propagating fronts (no flame speed, no flashback). Vessel walls do not burst
  under pressure. Parts radiate to the surroundings whatever encloses them (no view factors), so an enclosure
  does not insulate radiatively.

## Thermoelectricity
* Constant Seebeck coefficients (no Thomson effect); contacts through component leads (all tinned copper) are not
  split, so a resistor's own thermocouple EMFs are ignored. Block-scale Bi₂Te₃ legs are 6 cm long, so a Peltier
  couple at that scale needs hundreds of amperes; real modules (mm legs) are a miniature-scale build.
## Simulation pace
* Machines advance only within the simulation distance of a player (like Minecraft's block ticking), and not while
  part of them may still be unloaded (their reach crosses into a chunk not loaded) or for half a second after parts
  arrive — so a machine placed or loaded pauses briefly before it runs; all islands
  together are held to ~35 ms per tick by slowing every island in proportion when the world holds too many.
* One island's step is cut off after 60 ms of wall time: how far a costly machine gets each tick then depends on the
  machine running it, so in the world its slow motion is not reproducible tick for tick (the physics of each accepted
  step is; core tests and in-world tests run without the limit).

## Radiation
* Gamma rays only (betas and alphas deposit locally in the source); straight-line attenuation without scattered
  build-up; static geometry (coupling computed when the island compiles — a moving source or shield needs a
  rebuild, which happens whenever parts are placed or removed). Energy deposited by gammas in absorbers is not
  added as heat (microwatts for these sources). The counter chip may miss the very first pulse while it powers up.
* Fatigue: only turning shafts in rotating bending under the weight they carry (not gear-mesh, belt or unbalance
  forces, not the static frame under vibration); no mean-stress (torque), size or surface-finish corrections; linear
  (Miner) damage; the crack is all-or-nothing (no crack growth, no stiffness loss before it parts).
* Eddy currents: low-speed (no self-field, no high-speed fall-off), plates and pipe walls only (not shafts or solid
  blocks' volume currents), a fixed shape factor for plates; not between two independently moving bodies. Eddy
  losses in coil cores and iron (core loss) are not modelled.
* Moving iron: only an armature on a slide moving along a fixed coil's axis (not hinged clapper armatures, not
  plungers entering a bore, not permanent magnets pulling iron); the gap is the slide's travel, not resolved by the
  lattice (cell-sized); no saturation (the pull grows as i² without limit).
* Wire networks take a body as a whole: a wire of one circuit lying against a rotor's wiring (even insulated, of
  another colour) joins the rotor's wiring to the frame and stops its circuit turning with it. Keep static wiring
  clear of moving wiring (the exhibits' router keeps a margin round rotors).
* Light from hot parts through optics: only conductors in a circuit (filaments, heaters) are traced, as point
  sources at their centres (an extended source's image size is not resolved), in 40 nm bands; a single-cell
  component also takes contacts on its side faces along its lead axis, so leaded parts standing side by side
  connect (set "lead_axis" across the row).
* Acoustics: only bodies on slides radiate and hear (not turning bodies, not vibrating structures); each is a compact
  dipole at its outline's centroid (no baffles, enclosures or cabinets — a plate radiates from both faces in
  antiphase), in a free field (no walls, ground, rooms or absorption); its position is fixed at compile time (slides
  move millimetres); the radiation reaction on a source is not applied; bodies hear one another only within one
  island (slide bearings within 8 blocks are joined). Nothing is played as sound in the game yet.
* The loudspeaker exhibit (and any machine switched at audio rates) runs far slower than real time: every edge is
  resolved, and its thermal network is stepped with the electrical one.
* In-game sound is a sonification: each sounding body's recent periodic motion is played as a looping tone at its
  simulated pitch and level (not a recording of the simulated pressure, which would slow with the simulation);
  aperiodic motion (a single impact) is silent; no near field, reflections or occlusion in what the player hears.
* Magnets attract iron only where one of them rides a slide along the magnet's axis (not across it, not on turning
  bodies), by the method of images (a wide iron face, unsaturated); coils on the same magnetic path do not interact
  with that pull.
* Liquids in gas vessels: only water and ethanol are condensable; activity coefficients only for that pair (others
  ideal); liquid flows between vessels by its weight only (a pressurised vessel vents its gas through a liquid-filled
  pipe rather than pushing the liquid; no liquid out of open pipe ends); one temperature for liquid and vapour in a
  vessel; mass transfer at a fixed coefficient (no bubble nucleation, no superheating delays); liquid compressibility
  and vapour non-ideality ignored.
* Aerodynamics: flat-plate polars only; no tip vortices or finite-span correction; a rotor's inflow is a uniform
  actuator disc (no wake rotation, tip loss or radial variation), axial only (vertical-axis or yawed rotors get no
  induction across their axis); blades on carried bodies and on the frame do nothing; blades do not shade each
  other or other parts; the wind is uniform over a machine, modelled (not Minecraft's), and does not blow on
  anything but blades (no drag on other parts, no wind loads on structures).
* Column trays: the vapour from below mixes into a tray's gas space and exchanges with its pool at the lumped rate
  κ = k_G·A (it does not bubble through the liquid), so a tray is somewhat less than an equilibrium stage. A passage
  carries one gas flow, so two stacked vessels cannot exchange gas both ways at once (no counter-current convection
  through a wide opening); a chilled vessel above a warm one just stratifies.
