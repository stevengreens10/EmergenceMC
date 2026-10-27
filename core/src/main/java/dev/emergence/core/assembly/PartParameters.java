package dev.emergence.core.assembly;

import dev.emergence.core.materials.Material;
import dev.emergence.core.materials.MaterialCategory;
import dev.emergence.core.materials.MaterialDatabase;
import dev.emergence.core.materials.MaterialProperty;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What can be set on each kind of part: its parameters — their meaning, unit, kind of value, range and default — and the
 * materials it may sensibly be made of. The part editor builds its form from this, and every specification a player
 * makes (or the demo builder makes for them) is checked against it, so the editor offers exactly what the simulation
 * reads. Values are SI throughout; the editor chooses how to show them.
 */
public final class PartParameters {
    private PartParameters() {}

    /** How a parameter's value is chosen: on/off, one of a list, a whole number, or a quantity. */
    public enum Type { TOGGLE, CHOICE, INTEGER, REAL }

    /** One of a choice parameter's values and what it means. */
    public record Choice(int value, String label) {}

    /**
     * A parameter.
     *
     * @param unit       its SI unit ("" for none); the editor shows it with an SI prefix unless {@code shownIn} is set
     * @param shownIn    a fixed display unit and its factor from SI (e.g. "%" × 100, "mm²" × 10⁶, "V/µs" × 10⁻⁶), or null
     * @param min        least and greatest values a player may set (SI)
     * @param def        the default (SI), or NaN where it follows from the part's size or other parameters (see {@code auto})
     * @param auto       what the default is when it is not a fixed number ("60 % of the cell", "none")
     * @param log        whether a slider over its range should be logarithmic
     * @param advanced   shown only when the editor's advanced parameters are open (ratings, parasitics, fine tuning)
     * @param visible    when it applies, from the other parameters (a MOSFET's threshold only for MOSFETs)
     */
    public record Param(String key, String label, Type type, String unit, String shownIn, double shownFactor, double min, double max,
                        double def, String auto, boolean log, boolean advanced, List<Choice> choices, String help,
                        Predicate<Map<String, Double>> visible) {
        /** The default for these parameters' values (NaN: automatic). */
        public double defaultValue() {
            return def;
        }

        public boolean hasFixedDefault() {
            return !Double.isNaN(def);
        }

        public boolean appliesTo(Map<String, Double> params) {
            return visible == null || visible.test(params);
        }

        /** The label of a choice value, or null. */
        public String choiceLabel(double v) {
            for (Choice c : choices) if (c.value() == Math.round(v)) return c.label();
            return null;
        }
    }

    /**
     * A family of parameters keyed by a prefix and a member (a gas charge's mole fractions "x_CH4", a liquid's moles
     * "liquid_H2O", a ROM's words "w0"…"w15"): its members are fixed by the data, each one a {@link Param}.
     */
    public record Family(String prefix, String label, List<Param> members, String help, Predicate<Map<String, Double>> visible) {
        public boolean appliesTo(Map<String, Double> params) {
            return visible == null || visible.test(params);
        }
    }

    /** All of a kind's parameters and families. */
    public record Schema(PartKind kind, List<Param> params, List<Family> families, Predicate<Material> materials, String defaultMaterial) {
        public Param param(String key) {
            for (Param p : params) if (p.key().equals(key)) return p;
            for (Family f : families) for (Param p : f.members()) if (p.key().equals(key)) return p;
            return null;
        }

        /** The materials it may be made of, in the database's order. */
        public List<Material> allowedMaterials() {
            List<Material> out = new ArrayList<>();
            for (Material m : MaterialDatabase.standard().all()) if (materials.test(m)) out.add(m);
            return out;
        }
    }

    public static Schema of(PartKind kind) {
        return SCHEMAS.get(kind);
    }

    /**
     * Checks a specification: a material the kind may be made of, known parameters, each within its range (choices one
     * of theirs, whole numbers whole). Returns null if it is sound, else why not.
     */
    public static String check(PartKind kind, String material, Map<String, Double> params) {
        Schema s = of(kind);
        Material m = MaterialDatabase.standard().getOrNull(material);
        if (m == null) return "unknown material " + material;
        if (!s.materials().test(m)) return "a " + kind.name().toLowerCase().replace('_', ' ') + " cannot be made of " + m.name();
        for (var e : params.entrySet()) {
            Param p = s.param(e.getKey());
            if (p == null && kind == PartKind.CHIP && e.getKey().startsWith(DESIGN_PARAM)) {
                if (!Double.isFinite(e.getValue())) return e.getKey().substring(DESIGN_PARAM.length()) + " is not a number";
                continue;
            }
            if (p == null) return "a " + kind.name().toLowerCase().replace('_', ' ') + " has no parameter \"" + e.getKey() + "\"";
            String why = checkValue(p, e.getValue());
            if (why != null) return why;
        }
        return null;
    }

    /**
     * The prefix of a chip part's design parameters: "p_lo" overrides the {@code param lo} of the design written on it.
     * Which there are depends on the design, so the schema takes any.
     */
    public static final String DESIGN_PARAM = dev.emergence.core.digital.ChipTypes.DESIGN_PARAM;

    /** Null if the value is allowed for the parameter, else why not. */
    public static String checkValue(Param p, double v) {
        if (!Double.isFinite(v)) return p.label() + " is not a number";
        double tol = 1e-9 * Math.max(Math.abs(p.min()), Math.abs(p.max()));
        if (v < p.min() - tol || v > p.max() + tol) return p.label() + " must be between " + p.min() + " and " + p.max() + " " + p.unit();
        if (p.type() == Type.CHOICE && p.choiceLabel(v) == null) return p.label() + ": no such choice " + v;
        if ((p.type() == Type.INTEGER || p.type() == Type.CHOICE || p.type() == Type.TOGGLE) && Math.abs(v - Math.rint(v)) > 1e-9) {
            return p.label() + " must be a whole number";
        }
        return null;
    }

    // ------------------------------------------------------------------ the schema

    private static final Map<PartKind, Schema> SCHEMAS = new EnumMap<>(PartKind.class);

    /** The six directions, as parts' "face" parameters number them. */
    static final List<Choice> DIRECTIONS = List.of(new Choice(0, "down"), new Choice(1, "up"), new Choice(2, "north"),
            new Choice(3, "south"), new Choice(4, "west"), new Choice(5, "east"));
    static final List<Choice> AXES = List.of(new Choice(0, "x (east–west)"), new Choice(1, "y (up–down)"), new Choice(2, "z (north–south)"));

    /** A builder for one kind's list. */
    private static final class B {
        final PartKind kind;
        final List<Param> params = new ArrayList<>();
        final List<Family> families = new ArrayList<>();
        Predicate<Material> materials = m -> true;
        String defaultMaterial = "steel_1045";
        // the next parameter's modifiers
        boolean advanced, log;
        String shownIn;
        double shownFactor = 1;
        Predicate<Map<String, Double>> when;

        B(PartKind kind) {
            this.kind = kind;
        }

        B made(String defaultMaterial, Predicate<Material> materials) {
            this.defaultMaterial = defaultMaterial;
            this.materials = materials;
            return this;
        }

        B adv() {
            advanced = true;
            return this;
        }

        B log() {
            log = true;
            return this;
        }

        B shown(String unit, double factor) {
            shownIn = unit;
            shownFactor = factor;
            return this;
        }

        B when(Predicate<Map<String, Double>> w) {
            when = w;
            return this;
        }

        private B add(String key, String label, Type type, String unit, double min, double max, double def, String auto, List<Choice> choices, String help) {
            params.add(new Param(key, label, type, unit, shownIn, shownFactor, min, max, def, auto, log, advanced, choices == null ? List.of() : choices,
                    help, when));
            advanced = false;
            log = false;
            shownIn = null;
            shownFactor = 1;
            when = null;
            return this;
        }

        B real(String key, String label, String unit, double min, double max, double def, String help) {
            return add(key, label, Type.REAL, unit, min, max, def, null, null, help);
        }

        /** A quantity whose default follows from the part's size or other parameters. */
        B auto(String key, String label, String unit, double min, double max, String auto, String help) {
            return add(key, label, Type.REAL, unit, min, max, Double.NaN, auto, null, help);
        }

        B integer(String key, String label, double min, double max, double def, String help) {
            return add(key, label, Type.INTEGER, "", min, max, def, null, null, help);
        }

        B toggle(String key, String label, boolean def, String help) {
            return add(key, label, Type.TOGGLE, "", 0, 1, def ? 1 : 0, null, null, help);
        }

        B choice(String key, String label, double def, List<Choice> choices, String help) {
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            for (Choice c : choices) {
                lo = Math.min(lo, c.value());
                hi = Math.max(hi, c.value());
            }
            return add(key, label, Type.CHOICE, "", lo, hi, def, Double.isNaN(def) ? "from how it is placed" : null, choices, help);
        }

        /** A direction across or along the part (default fixed, or NaN: automatic). */
        B direction(String key, String label, double def, String help) {
            return add(key, label, Type.CHOICE, "", 0, 5, def, Double.isNaN(def) ? "from how it is placed" : null, DIRECTIONS, help);
        }

        B family(String prefix, String label, List<Param> members, String help, Predicate<Map<String, Double>> visible) {
            families.add(new Family(prefix, label, members, help, visible));
            return this;
        }

        /** The ratings and thermal contact of a manufactured component: lead axis, mass, surfaces, temperature limit. */
        B component(double mass, double maxTemperature) {
            add("lead_axis", "Leads come out along", Type.CHOICE, "", 0, 2, Double.NaN, "across the part, from how it is placed", AXES,
                    "Which grid axis the part's side leads (or terminals) leave along.");
            adv().shown("g", 1e3).real("mass", "Mass", "kg", 1e-4, 10, mass, "Its body's mass: how fast it warms.");
            adv().shown("cm²", 1e4).log().real("surface_area", "Cooling surface", "m²", 1e-5, 0.1, 3e-4,
                    "Its surface to the air: how fast it sheds heat by convection and radiation.");
            adv().shown("mm²", 1e6).log().real("contact_area", "Contact area when clamped", "m²", 1e-6, 0.01, 2e-4,
                    "The area through which it conducts heat into a structure it is pressed against (a heat sink).");
            if (!Double.isNaN(maxTemperature)) adv().real("max_temperature", "Temperature limit", "K", 300, 2500, maxTemperature,
                    "Hotter than this it is destroyed.");
            return this;
        }

        void build() {
            SCHEMAS.put(kind, new Schema(kind, List.copyOf(params), List.copyOf(families), materials, defaultMaterial));
        }
    }

    // material families
    private static Predicate<Material> in(MaterialCategory... cats) {
        Set<MaterialCategory> s = Set.of(cats);
        return m -> s.contains(m.category());
    }

    private static Predicate<Material> ids(String... ids) {
        Set<String> s = Set.of(ids);
        return m -> s.contains(m.id());
    }

    private static final Predicate<Material> SOLID = m -> m.category() != MaterialCategory.FLUID;
    private static final Predicate<Material> MECHANICAL = in(MaterialCategory.METAL, MaterialCategory.ALLOY, MaterialCategory.POLYMER,
            MaterialCategory.COMPOSITE, MaterialCategory.NATURAL, MaterialCategory.MAGNETIC);
    private static final Predicate<Material> CONDUCTOR = in(MaterialCategory.METAL, MaterialCategory.ALLOY);
    private static final Predicate<Material> SEMICONDUCTOR = in(MaterialCategory.SEMICONDUCTOR);
    private static final Predicate<Material> VESSEL = in(MaterialCategory.METAL, MaterialCategory.ALLOY, MaterialCategory.POLYMER,
            MaterialCategory.GLASS, MaterialCategory.COMPOSITE, MaterialCategory.CERAMIC);
    private static final Predicate<Material> TRANSPARENT = in(MaterialCategory.GLASS).or(ids("acrylic"));
    /** Permanent magnets: hard magnetic materials (coercivity over 10 kA/m). */
    private static final Predicate<Material> HARD_MAGNET = m -> m.get(MaterialProperty.COERCIVITY, 0) > 1e4;

    private static Predicate<Map<String, Double>> is(String key, double def, int... values) {
        return p -> {
            long v = Math.round(p.getOrDefault(key, def));
            for (int x : values) if (v == x) return true;
            return false;
        };
    }

    private static Predicate<Map<String, Double>> positive(String key) {
        return p -> p.getOrDefault(key, 0.0) > 0;
    }

    private static List<Choice> fluids() {
        List<Choice> out = new ArrayList<>();
        for (var f : dev.emergence.core.fluids.FluidDatabase.standard().all()) out.add(new Choice(f.code(), f.name()));
        out.sort(java.util.Comparator.comparingInt(Choice::value));
        return out;
    }

    private static List<Choice> nuclides() {
        List<Choice> out = new ArrayList<>();
        var nd = dev.emergence.core.nuclear.NuclearData.standard();
        for (int c = 0; c < 64; c++) {
            try {
                out.add(new Choice(c, nd.byCode(c).name()));
            } catch (IllegalArgumentException end) {
                break;
            }
        }
        return out;
    }

    /** A gas charge: a mole fraction for each gaseous species the chemistry knows. */
    private static List<Param> gasFractions() {
        List<Param> out = new ArrayList<>();
        for (var s : dev.emergence.core.chem.Chemistry.standard().allSpecies()) {
            if (!"gas".equals(s.phase())) continue;
            out.add(new Param("x_" + s.id(), s.name() + " (" + s.id() + ")", Type.REAL, "", "%", 100, 0, 1, 0, null, false, false, List.of(),
                    "Its share of the gas, by moles (the shares are normalised to add up to 100 %).", null));
        }
        return out;
    }

    /** A liquid charge: moles of each condensable species. */
    private static List<Param> liquids() {
        List<Param> out = new ArrayList<>();
        var chem = dev.emergence.core.chem.Chemistry.standard();
        for (var s : chem.allSpecies()) {
            if (dev.emergence.core.chem.VaporLiquid.of(s.id()) == null) continue;
            out.add(new Param("liquid_" + s.id(), s.name() + " (" + s.id() + ") liquid", Type.REAL, "mol", null, 1, 0, 1000, 0, null, false, false,
                    List.of(), "Moles of it held as liquid at the start (18 g of water is a mole).", null));
        }
        return out;
    }

    /** A ROM's sixteen bytes. */
    private static List<Param> romWords() {
        List<Param> out = new ArrayList<>();
        for (int w = 0; w < 16; w++) {
            out.add(new Param("w" + w, "Word " + w, Type.INTEGER, "", null, 1, 0, 255, 0, null, false, false, List.of(),
                    "The byte at address " + w + " (0–255).", null));
        }
        return out;
    }

    static {
        String cellNote = "of the cell";
        new B(PartKind.BLOCK).made("steel_1045", SOLID)
                .shown("%", 100).real("fill", "Thickness", "", 0.01, 1, 1,
                        "How much of the cell along the part's axis it fills: below 100 % it is a plate.")
                .adv().log().real("contact_resistance", "Sliding contact resistance", "Ω", 1e-4, 10, 0.02,
                        "Where a brush rubs on it: the electrical resistance of that contact.")
                .build();
        new B(PartKind.SHAFT).made("steel_1045", MECHANICAL)
                .log().real("diameter", "Diameter", "m", 5e-4, 0.059, PartGeometry.DEFAULT_SHAFT_DIAMETER, "The rod's diameter (at most 95 % " + cellNote + ").")
                .adv().real("stress_concentration", "Stress concentration", "", 1, 5, 1,
                        "How much a notch, shoulder or keyway multiplies its bending stress, for fatigue.")
                .build();
        new B(PartKind.BEARING).made("steel_1045", in(MaterialCategory.METAL, MaterialCategory.ALLOY, MaterialCategory.POLYMER, MaterialCategory.CERAMIC))
                .choice("type", "Kind", 0, List.of(new Choice(0, "ball bearing"), new Choice(1, "plain bushing"), new Choice(2, "viscous damper")),
                        "A ball bearing rolls with little friction; a plain bushing slides on an oil film; a viscous damper turns in silicone.")
                .when(is("type", 0, 1, 2)).log().auto("viscosity", "Lubricant viscosity", "Pa·s", 1e-3, 1e5, "0.1 Pa·s (oil); 10 Pa·s for a damper",
                        "How thick its oil or silicone is: thicker drags more, in proportion to speed.")
                .build();
        new B(PartKind.GEAR).made("steel_1045", MECHANICAL)
                .integer("teeth", "Teeth", 6, 300, PartGeometry.DEFAULT_GEAR_TEETH, "Two gears mesh when their tooth sizes (radius ÷ teeth) match.")
                .log().auto("pitch_radius", "Pitch radius", "m", 1e-3, 1, "half the cell", "The radius of its pitch circle: meshing gears' pitch circles touch.")
                .adv().auto("thickness", "Face width", "m", 1e-3, 0.0625, "80 % " + cellNote, "How wide its teeth are along its axis.")
                .toggle("lubricated", "Lubricated", true, "Oiled teeth lose far less to friction and wear slower.")
                .build();
        new B(PartKind.CRANK).made("steel_1045", MECHANICAL)
                .log().real("radius", "Arm length", "m", 0.01, 1, 0.15, "From the shaft to the handle: longer turns with more torque but slower.")
                .build();
        new B(PartKind.MAGNET).made("ndfeb_n42", HARD_MAGNET)
                .adv().auto("sx", "Size east–west", "m", 1e-3, 0.0625, "80 % " + cellNote, "The magnet's size along x.")
                .adv().auto("sy", "Size up–down", "m", 1e-3, 0.0625, "80 % " + cellNote, "The magnet's size along y.")
                .adv().auto("sz", "Size north–south", "m", 1e-3, 0.0625, "80 % " + cellNote, "The magnet's size along z.")
                .build();
        new B(PartKind.COIL).made("copper", CONDUCTOR)
                .log().integer("turns", "Turns", 1, 100_000, PartGeometry.DEFAULT_COIL_TURNS, "More turns: more EMF and flux per ampere, but more resistance.")
                .log().real("wire_diameter", "Wire diameter", "m", 2e-5, 5e-3, PartGeometry.DEFAULT_COIL_WIRE_DIAMETER,
                        "Thicker wire has less resistance but fewer turns fit.")
                .shown("%", 100).real("bore_fraction", "Bore", "", 0.05, 0.95, 0.35, "The hole through the winding, as a share of its outer diameter.")
                .toggle("core", "On an iron core", false, "Wound on electrical steel passing through it (a transformer or relay): its leads leave by the sides.")
                .choice("lead_axis", "Leads come out along", Double.NaN, AXES, "Which axis its leads leave along (on a core: by its sides).")
                .build();
        new B(PartKind.WIRE).made("copper", CONDUCTOR)
                .log().real("diameter", "Diameter", "m", 1e-5, 0.05, PartGeometry.DEFAULT_WIRE_DIAMETER, "Thicker wire: less resistance, more current before it melts.")
                .toggle("flexible", "Flexible lead", false, "A tinsel lead: it still conducts to a part that moves relative to it, and does not hold it.")
                .build();
        new B(PartKind.INSULATED_WIRE).made("copper", CONDUCTOR)
                .log().real("diameter", "Diameter", "m", 1e-5, 0.05, PartGeometry.DEFAULT_WIRE_DIAMETER, "The conductor's diameter.")
                .choice("color", "Insulation colour", 0, List.of(new Choice(0, "white"), new Choice(1, "orange"), new Choice(2, "magenta"),
                        new Choice(3, "light blue"), new Choice(4, "yellow"), new Choice(5, "lime"), new Choice(6, "pink"), new Choice(7, "grey"),
                        new Choice(8, "light grey"), new Choice(9, "cyan"), new Choice(10, "purple"), new Choice(11, "blue"), new Choice(12, "brown"),
                        new Choice(13, "green"), new Choice(14, "red"), new Choice(15, "black")),
                        "Wires of different colours side by side do not join: run separate circuits next to each other.")
                .adv().real("max_temperature", "Insulation rating", "K", 300, 1300, 433, "PVC chars above 160 °C; glass fibre takes 800 K.")
                .build();
        new B(PartKind.RESISTOR).made("alumina", in(MaterialCategory.CERAMIC))
                .log().real("resistance", "Resistance", "Ω", 1e-3, 1e9, 10_000, "At room temperature.")
                .real("ntc_beta", "Thermistor B", "K", 0, 6000, 0, "0: an ordinary resistor. Above 0 it is an NTC thermistor: its resistance falls as it warms.")
                .toggle("exposed", "Bare element", false, "A heating element: its heat goes straight into a vessel's gas or liquid it touches.")
                .component(0.002, 523).build();
        new B(PartKind.CAPACITOR).made("alumina", in(MaterialCategory.CERAMIC, MaterialCategory.POLYMER))
                .log().real("capacitance", "Capacitance", "F", 1e-13, 1e4, 1e-4, "Charge stored per volt.")
                .component(0.002, 398).build();
        new B(PartKind.DIODE).made("silicon", SEMICONDUCTOR)
                .adv().log().real("saturation_current", "Saturation current", "A", 1e-18, 1e-6, 1e-14, "Its reverse leakage: sets its forward voltage.")
                .adv().real("ideality", "Ideality factor", "", 1, 2, 1, "How far it is from the ideal junction law.")
                .component(0.002, 448).build();
        new B(PartKind.SWITCH).made("brass", CONDUCTOR).component(0.002, Double.NaN).build();
        new B(PartKind.BRUSH).made("graphite", in(MaterialCategory.CERAMIC, MaterialCategory.METAL, MaterialCategory.ALLOY))
                .log().real("contact_resistance", "Contact resistance", "Ω", 1e-4, 10, 0.02, "Resistance of its sliding contact.")
                .build();
        new B(PartKind.ELECTROLYTE).made("copper_sulfate_1m", in(MaterialCategory.FLUID))
                .shown("%", 100).real("fill", "Filled to", "", 0.05, 1, 0.8, "How full its glass vessel is.")
                .build();
        List<Choice> fluids = fluids();
        new B(PartKind.PIPE).made("copper", VESSEL)
                .log().real("bore", "Bore", "m", 1e-4, 0.056, PartGeometry.DEFAULT_PIPE_BORE, "Its inside diameter: a narrow pipe resists flow far more.")
                .log().real("wall", "Wall", "m", 2e-4, 0.0125, PartGeometry.DEFAULT_WALL, "Its wall's thickness: thicker holds more pressure.")
                .choice("fluid", "Fluid", 0, fluids, "What it holds (joined pipes and vessels must agree).")
                .adv().real("fins", "Inner fins", "", 0, 20, 0, "Fin area inside it as a multiple of its bore surface: better heat exchange, more drag.")
                .build();
        new B(PartKind.TANK).made("steel_1045", VESSEL)
                .choice("fluid", "Fluid", 0, fluids, "What it holds, unless it is charged with a gas mixture below.")
                .log().real("wall", "Wall", "m", 2e-4, 0.0125, PartGeometry.DEFAULT_WALL, "Its wall's thickness: thicker holds more pressure.")
                .log().auto("pressure", "Starting pressure", "Pa", 100, 3e7, "the air's", "Its contents' pressure as placed.")
                .when(is("fluid", 0, 1, 4, 7)).shown("%", 100).auto("fill", "Liquid level", "", 0, 1, "empty (water: full)", "How full of liquid it starts.")
                .adv().real("gauge_range", "Pressure gauge", "bar", 0, 1000, 0, "0: none. A dial reading up to this many bar on its face.")
                .adv().real("weir", "Outlet weir", "m", 0, 0.06, 0, "Raises its outlet to the vessel below this far above its floor: it holds a pool that deep (a column's tray).")
                .adv().toggle("condenser", "Wet walls (condenser)", false, "Vapour condenses on its walls as on a cold surface.")
                .family("x_", "Gas charge", gasFractions(), "Fill it with a gas mixture instead of a single fluid: each gas's share by moles.", null)
                .family("liquid_", "Liquid charge", liquids(), "Liquids it holds at the start, in moles (with a gas charge).", null)
                .build();
        new B(PartKind.VALVE).made("brass", VESSEL)
                .log().real("bore", "Seat bore", "m", 1e-5, 0.056, PartGeometry.DEFAULT_PIPE_BORE, "The opening at its seat when open.")
                .choice("fluid", "Fluid", 0, fluids, "What it passes.")
                .log().real("relief", "Relief pressure", "Pa", 0, 1e7, 0, "0: an ordinary valve. Above 0 it is a safety valve: it lifts by itself at this pressure.")
                .adv().real("plug_travel", "Plug travel", "m", 0, 1, 0, "With a rod on a slide pointing into its side, the slide works its plug: seated pushed in, open this far back (0: over the whole stroke).")
                .adv().shown("%", 100).real("lap", "Lap", "", 0, 1, 0, "Worked by a cam: how much of the cam's turn it stays shut.")
                .build();
        new B(PartKind.ROTOR).made("aluminium_6061", MECHANICAL)
                .real("blade_angle", "Blade angle", "°", 0, 80, 45, "Its blades' angle from the axis: steeper moves more fluid per turn.")
                .choice("pitch", "Hand", 1, List.of(new Choice(1, "right-hand"), new Choice(-1, "left-hand")), "Which way it pushes the fluid when turned.")
                .adv().real("blade_drag", "Blade drag", "", 0, 0.5, 0.02, "Its blades' profile drag coefficient.")
                .adv().real("solidity", "Solidity", "", 0.1, 2, 0.8, "Blade area to swept area: how closely the blades guide the flow.")
                .build();
        new B(PartKind.CYLINDER).made("steel_1045", MECHANICAL)
                .log().real("bore", "Bore", "m", 5e-3, 0.053, 0.04, "The piston's diameter.")
                .choice("fluid", "Fluid", 0, fluids, "What it works on.")
                .real("fins", "Cooling fins", "", 0, 20, 0, "Outside fin area as a multiple of its bare surface.")
                .adv().auto("clearance", "Clearance", "m", 1e-4, 0.05, "5 % of its length", "The gap left above the piston at the top of its stroke.")
                .adv().shown("%", 100).real("piston", "Piston starts at", "", 0, 1, 0.5, "Where along its stroke the piston is when placed.")
                .adv().real("ring_friction", "Ring friction", "N", 0, 50, 2, "The piston rings' friction against the bore.")
                .build();
        new B(PartKind.ECCENTRIC).made("steel_1045", MECHANICAL)
                .log().real("throw", "Throw", "m", 1e-3, 0.025, 0.015, "Its pin's offset from the shaft: the stroke is twice this.")
                .real("phase", "Phase", "°", -360, 360, 0, "Its angle on the shaft, relative to another throw.")
                .build();
        Predicate<Map<String, Double>> bjt = is("type", 0, 0, 1), mos = is("type", 0, 2, 3);
        new B(PartKind.TRANSISTOR).made("silicon", SEMICONDUCTOR)
                .choice("type", "Type", 0, List.of(new Choice(0, "NPN bipolar"), new Choice(1, "PNP bipolar"), new Choice(2, "n-channel MOSFET"),
                        new Choice(3, "p-channel MOSFET")), "Bipolar transistors are driven by base current, MOSFETs by gate voltage.")
                .when(bjt).log().real("beta", "Current gain β", "", 10, 1000, 150, "Collector current per unit of base current.")
                .when(bjt).adv().real("beta_r", "Reverse gain", "", 0.1, 20, 2, "Its gain run backwards.")
                .when(bjt).adv().log().real("early_voltage", "Early voltage", "V", 5, 1000, 75, "How much its collector current rises with collector voltage.")
                .when(bjt).adv().log().real("saturation_current", "Saturation current", "A", 1e-18, 1e-8, 1e-14, "Sets its base–emitter voltage.")
                .when(bjt).adv().log().real("c_be", "Base–emitter capacitance", "F", 1e-13, 1e-9, 18e-12, "Slows its switching.")
                .when(bjt).adv().log().real("c_bc", "Base–collector capacitance", "F", 1e-13, 1e-9, 4e-12, "Slows its switching (Miller effect).")
                .when(bjt).shown("mm²", 1e6).log().real("photo", "Light window", "m²", 0, 1e-3, 0,
                        "0: an ordinary transistor. Above 0: a phototransistor whose base is driven by light on a window this big.")
                .when(bjt.and(positive("photo"))).direction("face", "Window faces", 1, "Which way its light window looks.")
                .when(mos).real("threshold", "Threshold voltage", "V", 0.3, 10, 1.6, "Gate voltage at which it starts to conduct.")
                .when(mos).log().auto("transconductance", "Transconductance", "A/V²", 1e-3, 100, "0.1 (n), 0.05 (p)", "How hard it conducts per volt of gate drive.")
                .when(mos).adv().real("slope", "Subthreshold slope factor", "", 1, 3, 1.5, "How gradually it turns on below threshold.")
                .when(mos).adv().real("lambda", "Channel-length modulation", "1/V", 0, 0.2, 0.01, "How much its current rises with drain voltage.")
                .when(mos).adv().log().real("c_gs", "Gate–source capacitance", "F", 1e-13, 1e-8, 40e-12, "Gate charge: slows its switching.")
                .when(mos).adv().log().real("c_gd", "Gate–drain capacitance", "F", 1e-13, 1e-8, 8e-12, "Gate charge: slows its switching (Miller effect).")
                .component(0.002, 448).build();
        new B(PartKind.LED).made("silicon", SEMICONDUCTOR)
                .real("wavelength", "Wavelength", "nm", 380, 1100, 630, "Its colour: 470 blue, 525 green, 590 amber, 630 red; above 700 infrared.")
                .direction("face", "Faces", 1, "Which way it shines.")
                .real("beam", "Beam half-angle", "°", 1, 90, 6, "How wide its beam spreads.")
                .adv().shown("%", 100).real("efficiency", "Efficiency", "", 0.01, 0.6, 0.2, "The share of its electrical power that leaves as light.")
                .component(0.002, 398).build();
        new B(PartKind.LOGIC).made("silicon", SEMICONDUCTOR)
                .choice("function", "Function", 2, List.of(new Choice(0, "buffer"), new Choice(1, "NOT"), new Choice(2, "NAND"), new Choice(3, "NOR"),
                        new Choice(4, "AND"), new Choice(5, "OR"), new Choice(6, "XOR"), new Choice(7, "Schmitt NOT"), new Choice(8, "D flip-flop")),
                        "The gate it is: inputs A (D) and B (clock), output Q.")
                .adv().log().real("delay", "Propagation delay", "s", 1e-10, 1e-3, 10e-9, "How long its output takes to follow its inputs.")
                .adv().log().real("output_resistance", "Output resistance", "Ω", 1, 10_000, 50, "How stiffly its output holds its level under load.")
                .component(0.002, 448).build();
        new B(PartKind.BRAKE).made("oak", SOLID)
                .log().real("force", "Spring force", "N", 0.1, 10_000, 20, "How hard it presses when applied: friction torque μ·F·r.")
                .build();
        new B(PartKind.CHIP).made("silicon", SEMICONDUCTOR)
                .choice("function", "Chip", 0, List.of(new Choice(0, "4-bit counter"), new Choice(1, "4-bit register"), new Choice(2, "4-bit adder"),
                        new Choice(3, "16 × 8 ROM"), new Choice(4, "16 × 4 RAM"), new Choice(5, "your design (chip designer)"),
                        new Choice(6, "crystal clock"), new Choice(7, "10-bit SPI ADC"), new Choice(8, "128 × 64 display controller"),
                        new Choice(9, "256 × 8 ROM (contents in the ROM editor)")),
                        "Which chip this segment row belongs to. A design of your own is written in the chip designer.")
                .when(is("function", 0, 6)).log().real("frequency", "Frequency", "Hz", 1, 1e8, 10_000, "The crystal's frequency: the square wave on its output.")
                .when(is("function", 0, 6)).adv().real("ppm", "Crystal error", "ppm", -500, 500, 0, "How far off nominal this crystal runs, parts per million.")
                .when(is("function", 0, 0, 1, 2, 3, 4)).adv().log().real("delay", "Propagation delay", "s", 1e-10, 1e-3, 10e-9, "How long its outputs take to follow.")
                .when(is("function", 0, 9)).adv().log().real("access_time", "Access time", "s", 1e-9, 1e-5, 120e-9, "How long after its address changes its data follows.")
                .adv().log().real("output_resistance", "Output resistance", "Ω", 1, 10_000, 50, "How stiffly its outputs hold their level.")
                .family("w", "ROM contents", romWords(), "The ROM's sixteen bytes.", is("function", 0, 3))
                .component(0.002, Double.NaN).build();
        new B(PartKind.PHOTODIODE).made("silicon", SEMICONDUCTOR)
                .direction("face", "Faces", 1, "Which way its sensitive face looks.")
                .shown("mm²", 1e6).log().real("area", "Junction area", "m²", 1e-7, 0.01, 7.5e-6, "Larger collects more light: 7.5 mm² a photodiode, a whole face a solar cell.")
                .component(0.002, Double.NaN).build();
        new B(PartKind.POTENTIOMETER).made("nylon_66", in(MaterialCategory.POLYMER, MaterialCategory.CERAMIC))
                .log().real("resistance", "Track resistance", "Ω", 1, 1e7, 10_000, "End to end.")
                .real("travel", "Electrical angle", "°", 10, 360, 270, "How far the shaft turns from one end of the track to the other.")
                .shown("%", 100).real("wiper", "Wiper as fitted", "", 0, 1, 0.5, "Where on the track the wiper sits when placed: 0 % at end A.")
                .adv().log().real("drag", "Shaft drag", "N·m", 0, 1, 2e-3, "Friction of its wiper and bushing.")
                .adv().real("stop_stiffness", "End stop stiffness", "N·m/rad", 0, 1000, 20, "How hard its end stops are.")
                .component(0.03, Double.NaN).build();
        new B(PartKind.OPAMP).made("silicon", SEMICONDUCTOR)
                .log().real("gbw", "Gain–bandwidth", "Hz", 1e3, 1e9, 3e6, "Its gain times the frequency it holds it to.")
                .shown("V/µs", 1e-6).log().real("slew", "Slew rate", "V/s", 1e3, 1e10, 13e6, "How fast its output can swing.")
                .component(0.002, Double.NaN).build();
        new B(PartKind.SOURCE).made("stainless_304", in(MaterialCategory.METAL, MaterialCategory.ALLOY, MaterialCategory.GLASS))
                .choice("nuclide", "Nuclide", 0, nuclides(), "What it contains (sealed: only its gamma rays get out).")
                .log().real("activity", "Activity", "Bq", 1, 1e10, 3.7e5, "Decays a second; a school check source is about 370 kBq.")
                .adv().toggle("fresh", "Freshly separated", false, "Its short-lived daughters start from nothing rather than in equilibrium.")
                .adv().shown("g", 1e3).real("mass", "Mass", "kg", 1e-4, 1, 0.005, "Its mass.")
                .build();
        new B(PartKind.GM_TUBE).made("stainless_304", in(MaterialCategory.METAL, MaterialCategory.ALLOY, MaterialCategory.GLASS))
                .shown("cm²", 1e4).log().real("area", "Sensitive area", "m²", 1e-5, 0.01, 3e-4, "Its window facing the source.")
                .real("start", "Starting voltage", "V", 100, 2000, 350, "Below this it does not count.")
                .real("stop", "Extinction voltage", "V", 50, 2000, 300, "A discharge stops when its voltage falls to this.")
                .component(0.01, Double.NaN).build();
        new B(PartKind.LINEAR_BEARING).made("steel_1045", in(MaterialCategory.METAL, MaterialCategory.ALLOY, MaterialCategory.POLYMER))
                .auto("travel", "Stop collars at ±", "m", 0, 1, "none: to the shaft's ends", "Collars on the shaft this far each way from where it was placed.")
                .adv().auto("travel_fwd", "Forward collar", "m", 0, 1, "none", "A collar along the bushing's axis only (0: against the bushing).")
                .adv().auto("travel_back", "Backward collar", "m", 0, 1, "none", "A collar against the bushing's axis only.")
                .log().real("viscosity", "Damping", "N·s/m", 1e-4, 1e4, 0.05, "Its grease's drag per metre a second of sliding.")
                .build();
        new B(PartKind.SPRING).made("steel_1045", MECHANICAL)
                .log().real("wire", "Wire diameter", "m", 2e-4, 0.02, 0.002, "Thicker wire: much stiffer (as its fourth power).")
                .log().auto("coil", "Coil diameter", "m", 1e-3, 0.0625, "60 % of the cell", "Wider coils are softer (as the cube).")
                .log().real("turns", "Active turns", "", 1, 100, 10, "More turns: softer.")
                .real("preload", "Fitted shorter by", "m", 0, 0.05, 0, "Squeezed this much shorter than free when fitted: it pushes before it gives.")
                .build();
        new B(PartKind.RACK).made("steel_1045", MECHANICAL)
                .direction("face", "Teeth face", 1, "The face its teeth are cut on.")
                .adv().log().auto("module", "Tooth module", "m", 5e-4, 0.05, "that of a default gear", "Its tooth size: a gear meshes with it when theirs match.")
                .build();
        new B(PartKind.NUT).made("bronze", MECHANICAL)
                .log().auto("lead", "Lead", "m", 1e-4, 0.2, "a fifth of the shaft's diameter", "How far it moves along the screw per turn: fine leads hold loads by themselves.")
                .toggle("left", "Left-hand thread", false, "Screws the other way.")
                .toggle("lubricated", "Lubricated", true, "Oiled threads lose far less to friction (and slip back more easily).")
                .build();
        new B(PartKind.MIRROR).made("aluminium", in(MaterialCategory.METAL, MaterialCategory.ALLOY))
                .real("radius", "Curvature radius", "m", -10, 10, 0, "0: flat. Positive: concave, focusing at half this; negative: convex.")
                .real("tilt", "Tilt", "°", 0, 60, 0, "Leans its face over this far (45°: a periscope's mirror).")
                .when(positive("tilt")).direction("tilt_toward", "Tilted towards", 0, "Which way it leans.")
                .adv().real("aim_x", "Aim east", "", -10, 10, 0, "A facet canted on its mount: its face's direction (with the other two).")
                .adv().real("aim_y", "Aim up", "", -10, 10, 0, "A facet canted on its mount: its face's direction.")
                .adv().real("aim_z", "Aim south", "", -10, 10, 0, "A facet canted on its mount: its face's direction.")
                .build();
        new B(PartKind.LENS).made("glass_bk7", TRANSPARENT)
                .auto("radius", "First face radius", "m", -10, 10, "4 × its rim radius", "Positive: convex (converging). Its focal length is shown below.")
                .auto("radius2", "Second face radius", "m", -10, 10, "−first: equiconvex", "0: flat.")
                .adv().auto("thickness", "Thickness on the axis", "m", 1e-4, 0.0625, "just enough for a thin rim", "How thick it is at its centre.")
                .build();
        new B(PartKind.PRISM).made("glass_sf11", TRANSPARENT)
                .real("apex", "Apex angle", "°", 5, 90, 60, "A steeper apex bends light more, and spreads its colours more.")
                .direction("face", "Apex points", 1, "Which way its apex points (across its axis).")
                .build();
        new B(PartKind.BLADE).made("aluminium_6061", MECHANICAL)
                .real("pitch", "Pitch", "°", -60, 60, 0, "Turned about its span this far: sets its angle of attack.")
                .direction("face", "Chord lies along", Double.NaN, "Which way across its span its chord runs.")
                .auto("chord", "Chord", "m", 1e-3, 0.0625, "90 % of the cell", "Its width, leading to trailing edge.")
                .adv().auto("thickness", "Thickness", "m", 1e-4, 0.03, "10 % of the chord", "How thick it is.")
                .adv().real("drag", "Profile drag", "", 0.001, 0.5, 0.02, "Its drag coefficient at zero incidence.")
                .build();
        new B(PartKind.LINK).made("steel_1045", MECHANICAL)
                .auto("width", "Width", "m", 1e-3, 0.0625, "30 % of the cell", "The bar's width.")
                .adv().auto("thickness", "Thickness", "m", 1e-3, 0.0625, "half its width", "The bar's thickness.")
                .build();
        new B(PartKind.BATTERY).made("pvc", in(MaterialCategory.POLYMER))
                .choice("chemistry", "Chemistry", 0, List.of(new Choice(0, "lead–acid (2.1 V a cell)"), new Choice(1, "nickel–metal hydride (1.3 V a cell)")),
                        "Lead–acid: car and standby batteries. Nickel–metal hydride: rechargeable AA cells; four make a 5 V supply.")
                .integer("cells", "Cells in series", 1, 400, 6, "Its voltage is the cells' voltages added.")
                .log().real("capacity", "Capacity", "A·h", 0.05, 500, 7, "Charge it holds; bigger cells also have less internal resistance.")
                .shown("%", 100).real("charge", "Charged to", "", 0, 1, 1, "How full it is when placed.")
                .adv().shown("cm²", 1e4).log().real("surface_area", "Cooling surface", "m²", 1e-4, 1, 0.02, "Its case's surface to the air.")
                .adv().real("max_temperature", "Temperature limit", "K", 300, 400, 333, "Hotter than this it is ruined.")
                .build();
        new B(PartKind.DISPLAY).made("glass", TRANSPARENT).build();
        new B(PartKind.PRESSURE_SENSOR).made("stainless_304", VESSEL)
                .shown("bar", 1e-5).real("pmin", "Range from", "Pa", -1e5, 1e8, 0, "Gauge pressure at which its output is 10 % of its supply.")
                .shown("bar", 1e-5).real("pmax", "Range to", "Pa", 1e3, 1e8, 10e5, "Gauge pressure at which its output is 90 % of its supply.")
                .component(0.03, 400).build();
        for (PartKind k : PartKind.values()) if (!SCHEMAS.containsKey(k)) throw new IllegalStateException("no parameter schema for " + k);
    }

    /** The parameters by key (for documentation and tests). */
    public static Map<String, Param> byKey(PartKind kind) {
        Map<String, Param> m = new LinkedHashMap<>();
        for (Param p : of(kind).params()) m.put(p.key(), p);
        return m;
    }
}
