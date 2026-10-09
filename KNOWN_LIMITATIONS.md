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
* Lumped circuits only (no transmission-line effects); no skin effect.
* Capacitors have no ESR, leakage or dielectric breakdown; diodes have no junction capacitance, series
  resistance or reverse breakdown; no transistors yet.
* Brushes always belong to the stationary frame (brushes on rotating bodies are not supported); brush
  contact uses a fixed contact resistance (no current-dependent brush drop or arcing).
* Bulk metal parts are single electrical nodes (internal resistance neglected).

## Mechanical
* Rigid bodies rotate only about fixed axes (bearing joints). No free 6-DOF bodies, no translation,
  no linkages/cranks converting rotation to translation, no collisions. Gravity acts on each rotating body
  about its own axis only (no joint reactions on moving parents).
  Unsupported groups are treated as resting on the world.
* Gears: external spur meshes between parallel shafts only; no backlash, tooth compliance, internal/ring
  gears, racks or bevel gears.
* No structural stress/deflection, buckling, fatigue or fracture yet.

## Thermal
* Lumped bodies (one node per part); large parts have no internal gradients.
* Convection is natural convection only (no forced convection from rotation or fans); radiation exchanges
  only with the ambient, not between parts.
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
  gravity; no non-condensable gas mixed with steam (a steam vent's back-flow is steam, not air). Ice
  is not modelled (T clamped at the triple point). Slightly superheated (≲ 10 K) and very slightly
  compressed states pass through smoothing joins with a few per cent pressure error. One fluid per
  connected network; open vessels hold no free surface to the air.
* Two-phase flow through branches is homogeneous (no slip, no flashing choke); film boiling beyond the
  critical heat flux is not modelled (flux is capped instead).
* Rotors: one blade row at the mean radius; swirl is not passed to a downstream rotor; no tip leakage or
  radial (centrifugal) machines; blades have no camber.
* Gas properties are ideal-gas (Z ≈ 1); real-gas effects at high pressure are ignored.
* Internal natural convection in closed vessels is not modelled (laminar forced-convection Nu as floor).
* Pistons: massless piston and rod, no blow-by; crankcase always at ambient pressure. Cam-worked valves
  take no force from the cam (valve-gear work and inertia neglected).
* A single small blade row cannot make a self-sustaining gas turbine (consistent with real physics at this
  scale); jet kinetic energy is not modelled, so impulse turbines are under-powered.

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
* Light: only LEDs (and daylight) illuminate photodiodes — incandescent parts and torches do not; no lenses,
  mirrors, refraction or diffuse reflection; one turning body per optical path can chop the beam; spectra are
  monochromatic (LEDs) or a fixed sunlight responsivity. Daylight exposure is whole-block sky visibility.
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
* Visual minimum sizes: wires and shafts are drawn at least ~0.14–0.18 cell thick so thin filaments stay
  visible. Gear teeth are drawn with a simplified trapezoidal profile; their phase is cosmetic.

## Control parts
* A potentiometer's housing must sit on the static frame or its shaft must (one of the two sides is ground);
  a pot riding on one moving body and turned by another is not supported. No wiper noise, wear or contact bounce.
* Op-amp: one pole, no input offset/bias currents, no output current limit beyond the 75 Ω output resistance,
  no common-mode limits on the inputs; a single (not dual/quad) package per part.
