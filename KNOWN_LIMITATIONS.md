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

## Electrochemistry
* Activities equal concentrations; no passivation films, no pH-dependent speciation or complexation.
* Exchange current densities are order-of-magnitude literature values; impurities (which make commercial
  zinc corrode much faster in acid than pure zinc) are not represented.
* Solution colour uses three-band absorbance estimates (display only); deposits are not drawn as
  crystals, and dissolving electrodes do not visibly shrink.

## Not yet started (see ROADMAP.md)
Fluids/hydraulics, phase change, combustion/general chemistry, semiconductors beyond
the diode, digital logic, optics, acoustics, structural analysis, manufacturing, sensors beyond the
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
