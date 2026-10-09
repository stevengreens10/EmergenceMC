# Material database

Materials live in `core/src/main/resources/emergence/materials/*.json` (listed in `index.json`). Each property
value is a string with explicit units (`"8.94 g/cm^3"`, `"391 W/(m*K)"`) that is parsed and checked against the
property's SI dimension at load time (`MaterialProperty`). Unknown properties or wrong dimensions abort
loading with a message naming the material and file.

## Contents (37 materials)
* **Metals**: copper (C11000), aluminium (1350 EC), iron (commercially pure), tungsten, silver, gold, nickel,
  zinc, lead, tin.
* **Alloys**: aluminium 6061-T6, steel AISI 1045, stainless 304, nichrome 80/20, constantan, Sn63/Pb37 solder,
  brass C26000, phosphor bronze C51000, Ti-6Al-4V.
* **Magnetic**: NdFeB N42, hard ferrite C8, Alnico 5, Sm2Co17, M19 electrical steel.
* **Non-metals**: soda-lime glass, alumina 99.5 %, PTFE, nylon 6/6, PVC, natural rubber, muscovite mica,
  electrographite, silicon, germanium, oak, granite, quasi-isotropic CFRP.

## Properties
density, Young's/shear modulus, Poisson ratio, yield/ultimate/fatigue strength, hardness, fracture toughness
(stored in Pa, implicit √m), thermal conductivity, specific heat, thermal expansion, melting/boiling point,
latent heat of fusion, electrical resistivity (20 °C) with linear coefficient or power-law exponent,
relative permittivity, dielectric strength, relative permeability, saturation flux density, remanence,
coercivity, recoil permeability, Curie temperature, remanence temperature coefficient, emissivity, Seebeck
coefficient, band gap, friction coefficient (vs steel, dry), standard electrode potential, molar mass.

## Sources
CRC Handbook of Chemistry and Physics (97th ed.); ASM Handbooks Vol. 1 and 2; Incropera et al.,
*Fundamentals of Heat and Mass Transfer* (7th ed.) Tables A.1–A.4; MatWeb datasheets; Arnold Magnetic
Technologies and MMPA 0100-00 for magnets; Sze, *Physics of Semiconductor Devices* (3rd ed.); USDA Wood
Handbook; manufacturer datasheets (Kanthal, Special Metals, DuPont, CoorsTek, Morgan, AK Steel) as cited
per entry in the JSON `sources` field.

## Conventions and caveats
* Values are typical room-temperature values for the stated temper/grade; real lots vary ±5–10 %.
* `melting_point` of alloys is the solidus; for glass it is the softening point; for wood/polymers it is the
  decomposition or melting temperature used as the failure limit.
* Emissivity is a single representative value (tungsten's 0.30 is chosen for incandescent temperatures).
* Fluids (air) are modelled in code (`fluids.Air`); a data-driven fluid/substance database is planned with
  the chemistry phase.

## Substances for gas chemistry (`chemistry/substances.json`)
N₂, O₂, Ar, H₂, H₂O(g), CO₂, CO, CH₄, NH₃, NO₂, N₂O₄: formula, ΔH_f°(298.15 K), S°(298.15 K), C_p at 298.15/1000/2000 K
from NIST-JANAF (Chase 1998) and CODATA key values, 4 significant figures. Atomic masses (IUPAC conventional) give
the molar masses. N₂O₄'s high-temperature C_p is an extrapolation (it is fully dissociated there).
Reactions (`chemistry/reactions.json`): N₂O₄ ⇌ 2NO₂ (Borrell et al. 1988 activation energy, rate scaled to the
1-bar fall-off), CH₄ + 2O₂ → CO₂ + 2H₂O (Westbrook & Dryer 1981 global step as used in Fluent's methane-air-1step).
