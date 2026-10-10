# Material database

Materials live in `core/src/main/resources/emergence/materials/*.json` (listed in `index.json`). Each property
value is a string with explicit units (`"8.94 g/cm^3"`, `"391 W/(m*K)"`) that is parsed and checked against the
property's SI dimension at load time (`MaterialProperty`). Unknown properties or wrong dimensions abort
loading with a message naming the material and file.

## Contents (41 materials)
* **Metals**: copper (C11000), aluminium (1350 EC), iron (commercially pure), tungsten, silver, gold, nickel,
  zinc, lead, tin.
* **Alloys**: aluminium 6061-T6, steel AISI 1045, stainless 304, nichrome 80/20, constantan, Sn63/Pb37 solder,
  brass C26000, phosphor bronze C51000, Ti-6Al-4V.
* **Magnetic**: NdFeB N42, hard ferrite C8, Alnico 5, Sm2Co17, M19 electrical steel.
* **Glasses and clear plastic**: soda-lime glass, N-BK7 borosilicate crown, N-SF11 dense flint, fused silica,
  acrylic (PMMA) — with refractive index n_d and Abbe number (Schott data sheets, Malitson 1965).
* **Non-metals**: alumina 99.5 %, PTFE, nylon 6/6, PVC, natural rubber, muscovite mica,
  electrographite, silicon, germanium, oak, granite, quasi-isotropic CFRP.

## Properties
density, Young's/shear modulus, Poisson ratio, yield/ultimate/fatigue strength, hardness, fracture toughness
(stored in Pa, implicit √m), thermal conductivity, specific heat, thermal expansion, melting/boiling point,
latent heat of fusion, electrical resistivity (20 °C) with linear coefficient or power-law exponent,
relative permittivity, dielectric strength, relative permeability, saturation flux density, remanence,
coercivity, recoil permeability, Curie temperature, remanence temperature coefficient, emissivity, Seebeck
coefficient, band gap, friction coefficient (vs steel, dry), standard electrode potential, molar mass;
optics: refractive index n_d, Abbe number V_d, polished normal-incidence reflectance at 550 and 1000 nm (metals:
Bennett & Ashley, Handbook of Optics vol. IV; alloys from their base metal).

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

## Seebeck coefficients
Absolute values near 300 K for the metals (Cusack & Kendall 1958; Roberts 1977 for lead; CRC), with the thermocouple
alloys set so pairs match the NIST ITS-90 sensitivities (type T Cu−constantan 40.7, type J Fe−constantan 51.7 µV/K).
New: p-type Bi₀.₅Sb₁.₅Te₃ (+210 µV/K) and n-type Bi₂Te₂.₇Se₀.₃ (−200 µV/K), ρ = 10 µΩ·m, k ≈ 1.4 W/m·K (Rowe 1995).

## Nuclear data (`nuclear/nuclear.json`)
Cs-137 (→ Ba-137m 94.4 %), Ba-137m (153.1 s, 661.7 keV 89.99 %), Co-60, Am-241, K-40: half-lives, branchings, gamma
lines and locally deposited energy from NNDC/ENSDF. Gamma mass attenuation coefficients at 60/662/1250 keV for air,
water, silica, aluminium, iron, copper, tungsten, lead (NIST XCOM); every material maps to one of these classes.
GM tube gamma efficiency 0.5/1.0/1.2 % at those energies; background 0.3 counts/s.
