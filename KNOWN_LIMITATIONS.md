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
* Brushes conduct to the conductor in front of their face at the θ = 0 geometry; angle-dependent
  commutation (segment overlap) is not implemented yet, so commutators do not work.
* Bulk metal parts are single electrical nodes (internal resistance neglected).

## Mechanical
* Rigid bodies rotate only about fixed axes (bearing joints). No free 6-DOF bodies, no translation,
  no linkages/cranks converting rotation to translation, no gravity torque/unbalance, no collisions.
  Unsupported groups are treated as resting on the world.
* Gears: external spur meshes between parallel shafts only; no backlash, tooth compliance, internal/ring
  gears, racks or bevel gears.
* No structural stress/deflection, buckling, fatigue or fracture yet.

## Thermal
* Lumped bodies (one node per part); large parts have no internal gradients.
* Convection is natural convection only (no forced convection from rotation or fans); radiation exchanges
  only with the ambient, not between parts.
* Ambient is a fixed reservoir per island (biome-based); world blocks are not thermal masses.

## Not yet started (see ROADMAP.md)
Fluids/hydraulics, phase change, combustion/chemistry, electrochemistry (batteries), semiconductors beyond
the diode, digital logic, optics, acoustics, structural analysis, manufacturing, sensors beyond the
multimeter, oscilloscope/data logging.

## Gameplay / UI
* Parts are obtained from the creative tab; there are no survival recipes or manufacturing yet.
* No in-game reference book; signs in the demo world and tooltips only.
* Parts must fit within a single block (multi-block parts are built from segments).
* Simulation stops in unloaded chunks (state is saved and resumes on load).
* Visual minimum sizes: wires render at least 8 mm thick so thin filaments stay visible.
