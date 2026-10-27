package dev.emergence.mod.client;

import dev.emergence.core.assembly.PartCatalog;
import dev.emergence.core.assembly.PartKind;
import dev.emergence.core.assembly.PartParameters;
import dev.emergence.core.assembly.PartParameters.Param;
import dev.emergence.core.assembly.ParamText;
import dev.emergence.core.materials.Material;
import dev.emergence.core.materials.MaterialProperty;
import dev.emergence.mod.net.PartSpecEditPayload;
import dev.emergence.mod.part.PartSpec;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The part editor: what the part in hand is made of and its dimensions and ratings. Opened by right-clicking the air
 * with a part. Every setting the simulation reads for this kind of part is listed ({@link PartParameters}), each with a
 * control that suits it — a switch, a list of choices, a slider over its sensible range with the exact value typed
 * beside it — in readable units, with a line saying what it does. Stock variants (presets) are a click away; the
 * materials offered are those the part can be made of. Done sends it to the server, which checks it again.
 */
public final class PartEditorScreen extends Screen {
    private static final int ROW = 22, LABEL_W = 150, CONTROL_W = 210;

    private final PartKind kind;
    private final boolean offhand;
    private final PartParameters.Schema schema;
    private final List<Material> materials;
    private final List<PartCatalog.Preset> presets;
    // the form's state (rebuilt widgets read from here)
    private String material;
    private int length;
    private final TreeMap<String, Double> values = new TreeMap<>();
    private boolean advanced;
    private int scroll;
    private String error;
    private Button done;
    /** Rows shown this layout: label text and tooltip by y, for render. */
    private final List<Object[]> labels = new ArrayList<>();
    private int listTop, listBottom, rowsTotal;

    /** The part's text (a chip's design), kept as it is: the chip designer edits it, not this form. */
    private final String text;

    public PartEditorScreen(PartKind kind, boolean offhand, PartSpec spec) {
        super(Component.translatable("item.emergence." + kind.name().toLowerCase()));
        this.kind = kind;
        this.offhand = offhand;
        this.schema = PartParameters.of(kind);
        this.materials = schema.allowedMaterials();
        this.presets = PartCatalog.of(kind);
        this.material = spec.material();
        this.text = spec.text();
        this.length = spec.length();
        values.putAll(spec.params());
        for (String k : values.keySet()) {
            Param p = schema.param(k);
            if (p != null && p.advanced()) advanced = true; // open what the part already uses
        }
    }

    /** Opens a parameter's exact-value box (as its ✎ button does). */
    void startTyping(String key) {
        editing = key;
    }

    /** Opens the advanced settings (as the checkbox does). */
    void showAdvanced() {
        advanced = true;
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        labels.clear();
        int x0 = width / 2 - (LABEL_W + CONTROL_W) / 2, cx = x0 + LABEL_W;
        int y = 30;
        // presets
        List<Object> presetChoices = new ArrayList<>();
        presetChoices.add("custom");
        presetChoices.addAll(presets);
        addRenderableWidget(CycleButton.<Object>builder(o -> Component.literal(o instanceof PartCatalog.Preset p ? p.name() : "— choose a stock part —"))
                .withValues(presetChoices).withInitialValue(matchingPreset()).displayOnlyValue()
                .withTooltip(o -> Tooltip.create(Component.literal("Stock variants: pick one to start from, then adjust it.")))
                .create(cx, y, CONTROL_W, 20, Component.literal("Preset"), (b, o) -> {
                    if (o instanceof PartCatalog.Preset p) applyPreset(p);
                }));
        labels.add(new Object[]{"Start from", y, "Stock variants of this part."});
        y += ROW;
        // material
        Material now = materials.stream().filter(m -> m.id().equals(material)).findFirst().orElse(null);
        if (now == null && !materials.isEmpty()) {
            now = materials.stream().filter(m -> m.id().equals(schema.defaultMaterial())).findFirst().orElse(materials.get(0));
            material = now.id();
        }
        Material shownMaterial = now;
        // all the choices at once, grouped, when there are many; stepping through them when there are few
        if (materials.size() > 6) {
            addRenderableWidget(Button.builder(Component.literal(now == null ? "?" : now.name() + "  ▼"), b -> minecraft.setScreen(
                    new MaterialPickerScreen(this, materials, material, this::materialNote, m -> {
                        material = m.id();
                        validate();
                    }))).bounds(cx, y, CONTROL_W, 20).tooltip(Tooltip.create(Component.literal(shownMaterial == null ? "" : materialNote(shownMaterial)))).build());
        } else {
            addRenderableWidget(CycleButton.<Material>builder(m -> Component.literal(m.name()))
                    .withValues(materials).withInitialValue(now).displayOnlyValue()
                    .withTooltip(m -> Tooltip.create(Component.literal(materialNote(m))))
                    .create(cx, y, CONTROL_W, 20, Component.literal("Material"), (b, m) -> {
                        material = m.id();
                        changed(false);
                    }));
        }
        labels.add(new Object[]{"Material", y, "What it is made of (only materials this part can sensibly be made of)."});
        y += ROW + 6;
        listTop = y;
        listBottom = height - 46;
        // the parameter rows
        List<Runnable> rows = new ArrayList<>();
        List<String[]> rowLabels = new ArrayList<>();
        if (kind == PartKind.SHAFT || kind == PartKind.RACK) {
            Param len = new Param("#length", "Length", PartParameters.Type.INTEGER, "", "cells", 1, 1, 16, 4, null, false, false, List.of(),
                    "How many cells long it is placed.", null);
            rowLabels.add(new String[]{"Length", len.help()});
            rows.add(null);
        }
        List<Param> shown = new ArrayList<>();
        int hidden = 0;
        for (Param p : schema.params()) {
            if (!p.appliesTo(values)) continue;
            if (p.advanced() && !advanced) {
                hidden++;
                continue;
            }
            shown.add(p);
        }
        for (Param p : shown) rowLabels.add(new String[]{p.label(), help(p)});
        // families (a gas charge, a ROM's contents)
        List<PartParameters.Family> families = new ArrayList<>();
        for (var f : schema.families()) if (f.appliesTo(values)) families.add(f);
        int familyRows = 0;
        for (var f : families) familyRows += 1 + familyMembers(f).size();
        rowsTotal = rowLabels.size() + familyRows + 1;
        int visibleRows = Math.max(1, (listBottom - listTop) / ROW);
        scroll = Math.max(0, Math.min(scroll, rowsTotal - visibleRows));
        int index = 0;
        // length
        if (kind == PartKind.SHAFT || kind == PartKind.RACK) {
            int ry = rowY(index++);
            if (ry >= 0) {
                labels.add(new Object[]{"Length", ry, "How many cells long it is placed."});
                addRenderableWidget(new ValueSlider(cx, ry, CONTROL_W - 20, new Param("#length", "Length", PartParameters.Type.INTEGER, "", "cells", 1,
                        1, 16, 4, null, false, false, List.of(), "", null), length, v -> {
                    length = (int) Math.round(v);
                    changed(false);
                }));
            }
        }
        for (Param p : shown) {
            int ry = rowY(index++);
            if (ry < 0) continue;
            labels.add(new Object[]{p.label(), ry, help(p)});
            addControl(p, cx, ry);
        }
        for (var f : families) {
            int ry = rowY(index++);
            if (ry >= 0) addFamilyHeader(f, cx, ry);
            for (Param m : familyMembers(f)) {
                int my = rowY(index++);
                if (my < 0) continue;
                labels.add(new Object[]{"  " + m.label(), my, m.help()});
                addControl(m, cx, my);
                if (!f.prefix().equals("w")) {
                    addRenderableWidget(Button.builder(Component.literal("✕"), b -> {
                        values.remove(m.key());
                        changed(true);
                    }).bounds(cx + CONTROL_W + 4, my, 16, 20).tooltip(Tooltip.create(Component.literal("Take it out of the charge"))).build());
                }
            }
        }
        // the advanced settings switch
        int ay = rowY(index);
        if (ay >= 0 && (hidden > 0 || advanced)) {
            addRenderableWidget(Checkbox.builder(Component.literal(advanced ? "Advanced settings shown" : "Show advanced settings (" + hidden + ")"), font)
                    .pos(x0, ay).selected(advanced).onValueChange((c, v) -> {
                        advanced = v;
                        rebuildWidgets();
                    }).tooltip(Tooltip.create(Component.literal("Ratings, parasitics and fine tuning: thermal limits, masses, capacitances…")))
                    .build());
        }
        // the footer
        addRenderableWidget(Button.builder(Component.literal("Reset all"), b -> {
            values.clear();
            material = schema.defaultMaterial();
            changed(true);
        }).bounds(width / 2 - 154, height - 28, 96, 20).tooltip(Tooltip.create(Component.literal("Back to the plain default part"))).build());
        done = addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> done()).bounds(width / 2 - 52, height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(width / 2 + 54, height - 28, 100, 20).build());
        validate();
    }

    /** The screen y of the index-th row of the scrolling list, or −1 if it is scrolled out of view. */
    private int rowY(int index) {
        int y = listTop + (index - scroll) * ROW;
        return index < scroll || y + 20 > listBottom ? -1 : y;
    }

    private List<Param> familyMembers(PartParameters.Family f) {
        List<Param> out = new ArrayList<>();
        for (Param m : f.members()) if (f.prefix().equals("w") || values.containsKey(m.key())) out.add(m);
        return out;
    }

    private void addFamilyHeader(PartParameters.Family f, int cx, int y) {
        labels.add(new Object[]{"§e" + f.label(), y, f.help()});
        if (f.prefix().equals("w")) return;
        List<Param> absent = new ArrayList<>();
        for (Param m : f.members()) if (!values.containsKey(m.key())) absent.add(m);
        if (absent.isEmpty()) return;
        // "add a gas": pick from those not yet in the charge
        Param[] pick = {absent.get(0)};
        addRenderableWidget(CycleButton.<Param>builder(m -> Component.literal(m.label())).withValues(absent).withInitialValue(pick[0]).displayOnlyValue()
                .create(cx, y, CONTROL_W - 44, 20, Component.literal("Add"), (b, m) -> pick[0] = m));
        addRenderableWidget(Button.builder(Component.literal("Add"), b -> {
            values.put(pick[0].key(), f.prefix().equals("x_") ? 0.1 : 1.0);
            changed(true);
        }).bounds(cx + CONTROL_W - 40, y, 40, 20).build());
    }

    /** The control for a parameter, by what kind of value it takes. */
    private void addControl(Param p, int cx, int y) {
        Double set = values.get(p.key());
        switch (p.type()) {
            case TOGGLE -> {
                boolean on = (set != null ? set : p.defaultValue()) > 0.5;
                addRenderableWidget(Checkbox.builder(Component.literal(on ? "on" : "off"), font).pos(cx, y).selected(on).onValueChange((c, v) -> {
                    put(p, v ? 1 : 0);
                    changed(true);
                }).tooltip(Tooltip.create(Component.literal(help(p)))).build());
            }
            case CHOICE -> {
                List<Integer> opts = new ArrayList<>();
                if (!p.hasFixedDefault()) opts.add(Integer.MIN_VALUE); // automatic
                for (var c : p.choices()) opts.add(c.value());
                Integer now = set != null ? (int) Math.round(set) : p.hasFixedDefault() ? (int) Math.round(p.defaultValue()) : Integer.MIN_VALUE;
                if (!opts.contains(now)) now = opts.get(0);
                addRenderableWidget(CycleButton.<Integer>builder(v -> Component.literal(v == Integer.MIN_VALUE ? "auto: " + p.auto() : p.choiceLabel(v)))
                        .withValues(opts).withInitialValue(now).displayOnlyValue()
                        .withTooltip(v -> Tooltip.create(Component.literal(help(p))))
                        .create(cx, y, CONTROL_W - 20, 20, Component.literal(p.label()), (b, v) -> {
                            if (v == Integer.MIN_VALUE) values.remove(p.key());
                            else put(p, v);
                            changed(true);
                        }));
                addReset(p, cx, y);
            }
            case INTEGER, REAL -> {
                boolean auto = !p.hasFixedDefault() && set == null;
                int sx = cx, sw = CONTROL_W - 20 - 22;
                if (!p.hasFixedDefault()) {
                    addRenderableWidget(Checkbox.builder(Component.empty(), font).pos(cx, y).selected(auto).onValueChange((c, v) -> {
                        if (v) values.remove(p.key());
                        else put(p, startingValue(p));
                        changed(true);
                    }).tooltip(Tooltip.create(Component.literal("Ticked: automatic — " + p.auto() + ". Untick to set it yourself."))).build());
                    sx += 22;
                    sw -= 22;
                }
                double v = set != null ? set : p.hasFixedDefault() ? p.defaultValue() : startingValue(p);
                if (p.key().equals(editing)) {
                    // typing an exact value in place of the slider: Enter (or ✓) keeps it, Escape drops it
                    EditBox box = new EditBox(font, sx + 1, y + 1, sw - 2, 18, Component.literal(p.label()));
                    box.setMaxLength(24);
                    box.setValue(format(p, v));
                    box.setTooltip(Tooltip.create(Component.literal("e.g. \"" + format(p, v) + "\"" + (prefixed(p) ? ", or with a prefix: \"4.7k\", \"220 m\"" : "")
                            + " — a bare number is taken in the unit shown")));
                    box.setResponder(text -> {
                        Double parsed = parse(p, text, v);
                        String why = parsed == null ? "not a number" : PartParameters.checkValue(p, parsed);
                        box.setTextColor(why == null ? 0xE0E0E0 : 0xFF6060);
                        typed = why == null ? parsed : null;
                    });
                    addRenderableWidget(box);
                    setInitialFocus(box);
                    typed = v;
                    addRenderableWidget(Button.builder(Component.literal("✓"), b -> finishTyping(true)).bounds(cx + CONTROL_W - 40, y, 20, 20)
                            .tooltip(Tooltip.create(Component.literal("Keep it (Enter)"))).build());
                } else {
                    ValueSlider slider = new ValueSlider(sx, y, sw, p, v, nv -> {
                        put(p, nv);
                        changed(false);
                    });
                    slider.active = !auto;
                    if (auto) slider.setMessage(Component.literal("auto: " + p.auto()));
                    slider.setTooltip(Tooltip.create(Component.literal(help(p))));
                    addRenderableWidget(slider);
                    Button type = addRenderableWidget(Button.builder(Component.literal("✎"), b -> {
                        editing = p.key();
                        rebuildWidgets();
                    }).bounds(cx + CONTROL_W - 40, y, 20, 20).tooltip(Tooltip.create(Component.literal("Type an exact value"))).build());
                    type.active = !auto;
                }
                addReset(p, cx, y);
            }
        }
    }

    /** The parameter whose value is being typed (its slider swapped for a text box), and what it reads now. */
    private String editing;
    private Double typed;

    private void finishTyping(boolean keep) {
        if (editing == null) return;
        Param p = schema.param(editing);
        if (keep && typed != null && p != null) put(p, typed);
        editing = null;
        typed = null;
        changed(true);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (editing != null && (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)) {
            finishTyping(true);
            return true;
        }
        if (editing != null && key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            finishTyping(false);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    /** Back to the default: active only where the value is set. */
    private void addReset(Param p, int cx, int y) {
        Button b = addRenderableWidget(Button.builder(Component.literal("R"), x -> {
            values.remove(p.key());
            changed(true);
        }).bounds(cx + CONTROL_W - 18, y, 18, 20).tooltip(Tooltip.create(Component.literal("Reset to the default: " + defaultText(p)))).build());
        b.active = values.containsKey(p.key());
    }

    private void put(Param p, double v) {
        if (p.key().startsWith("#")) return;
        values.put(p.key(), v);
    }

    /** A value to start from when a parameter is taken off automatic: the middle of its range (geometric for a log slider). */
    private static double startingValue(Param p) {
        double lo = p.min(), hi = p.max();
        double v = p.log() && lo > 0 ? Math.sqrt(lo * hi) : 0.5 * (lo + hi);
        return p.type() == PartParameters.Type.INTEGER ? Math.round(v) : niceRound(v, 2);
    }

    private Object matchingPreset() {
        for (var p : presets) if (p.material().equals(material) && p.params().equals(values) && (p.length() == length || !(kind == PartKind.SHAFT || kind == PartKind.RACK))) return p;
        return "custom";
    }

    private void applyPreset(PartCatalog.Preset p) {
        material = p.material();
        values.clear();
        values.putAll(p.params());
        if (kind == PartKind.SHAFT || kind == PartKind.RACK) length = p.length();
        changed(true);
    }

    /** Something changed: check it, and lay the form out again if what is shown may differ (a choice, a switch). */
    private void changed(boolean relayout) {
        if (relayout) rebuildWidgets();
        else validate();
    }

    private void validate() {
        error = PartParameters.check(kind, material, values);
        if (error == null && values.keySet().stream().anyMatch(k -> k.startsWith("x_"))) {
            double sum = values.entrySet().stream().filter(e -> e.getKey().startsWith("x_")).mapToDouble(Map.Entry::getValue).sum();
            if (sum <= 0) error = "the gas charge's shares add up to nothing";
        }
        if (done != null) done.active = error == null;
    }

    private void done() {
        validate();
        if (error != null) return;
        PacketDistributor.sendToServer(new PartSpecEditPayload(offhand, new PartSpec(material, length, 1, new TreeMap<>(values), text)));
        onClose();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (my >= listTop && my <= listBottom) {
            int before = scroll;
            scroll = Math.max(0, scroll - (int) Math.signum(dy));
            if (scroll != before) rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x0 = width / 2 - (LABEL_W + CONTROL_W) / 2;
        g.drawCenteredString(font, title, width / 2, 6, 0xFFFFFF);
        String summary = PartSummary.of(kind, new PartSpec(material, length, 1, values));
        g.drawCenteredString(font, summary, width / 2, 17, 0x9AD0FF);
        for (Object[] l : labels) {
            int y = (int) l[1];
            String text = (String) l[0];
            g.drawString(font, font.plainSubstrByWidth(text, LABEL_W - 6), x0, y + 6, 0xE0E0E0);
            if (mouseX >= x0 && mouseX < x0 + LABEL_W - 6 && mouseY >= y && mouseY < y + 20 && l[2] != null) {
                g.renderTooltip(font, font.split(Component.literal((String) l[2]), 240), mouseX, mouseY);
            }
        }
        int visibleRows = Math.max(1, (listBottom - listTop) / ROW);
        if (rowsTotal > visibleRows) {
            // a scroll bar beside the list
            int bx = x0 + LABEL_W + CONTROL_W + 24, h = listBottom - listTop, bh = Math.max(12, h * visibleRows / rowsTotal);
            int by = listTop + (h - bh) * scroll / Math.max(1, rowsTotal - visibleRows);
            g.fill(bx, listTop, bx + 3, listBottom, 0x40FFFFFF);
            g.fill(bx, by, bx + 3, by + bh, 0xC0FFFFFF);
        }
        if (error != null) g.drawCenteredString(font, error, width / 2, height - 40, 0xFF6060);
        else g.drawCenteredString(font, rowsTotal > Math.max(1, (listBottom - listTop) / ROW) ? "Hover a name for what it does · scroll for more"
                : "Hover a name for what it does", width / 2, height - 40, 0x808080);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ values as text

    private static String help(Param p) {
        StringBuilder s = new StringBuilder(p.help());
        if (p.type() == PartParameters.Type.REAL || p.type() == PartParameters.Type.INTEGER) {
            s.append("\nRange ").append(format(p, p.min())).append(" to ").append(format(p, p.max())).append('.');
        }
        s.append("\nDefault: ").append(defaultText(p)).append('.');
        return s.toString();
    }

    private static String defaultText(Param p) {
        if (!p.hasFixedDefault()) return "automatic (" + p.auto() + ")";
        return switch (p.type()) {
            case TOGGLE -> p.defaultValue() > 0.5 ? "on" : "off";
            case CHOICE -> p.choiceLabel(p.defaultValue());
            default -> format(p, p.defaultValue());
        };
    }

    private static boolean prefixed(Param p) {
        return ParamText.prefixed(p);
    }

    static String format(Param p, double v) {
        return ParamText.format(p, v);
    }

    static Double parse(Param p, String text, double shownValue) {
        return ParamText.parse(p, text, shownValue);
    }

    private static double niceRound(double v, int digits) {
        return ParamText.niceRound(v, digits);
    }

    /** What matters about a material for choosing it, in a line or two. */
    private String materialNote(Material m) {
        StringBuilder s = new StringBuilder(m.name()).append(" (").append(m.category().name().toLowerCase()).append(")");
        Object[][] props = {{MaterialProperty.DENSITY, "density", "kg/m³", 1.0}, {MaterialProperty.ELECTRICAL_RESISTIVITY, "resistivity", "Ω·m", 1.0},
                {MaterialProperty.THERMAL_CONDUCTIVITY, "thermal conductivity", "W/(m·K)", 1.0}, {MaterialProperty.YIELD_STRENGTH, "yield strength", "MPa", 1e-6},
                {MaterialProperty.REMANENCE, "remanence", "T", 1.0}, {MaterialProperty.REFRACTIVE_INDEX, "refractive index", "", 1.0},
                {MaterialProperty.MELTING_POINT, "melts at", "K", 1.0}};
        for (Object[] p : props) {
            MaterialProperty mp = (MaterialProperty) p[0];
            if (!m.has(mp)) continue;
            double v = m.get(mp) * (double) p[3];
            s.append("\n").append(p[1]).append(": ").append(String.format(Locale.ROOT, Math.abs(v) < 1e-2 || Math.abs(v) >= 1e5 ? "%.3g" : "%.4g", v))
                    .append(((String) p[2]).isEmpty() ? "" : " " + p[2]);
        }
        return s.toString();
    }

    // ------------------------------------------------------------------ the slider

    /** A slider over a parameter's range (logarithmic where it spans decades), its value shown in readable units. */
    private static final class ValueSlider extends AbstractSliderButton {
        private final Param p;
        private final java.util.function.DoubleConsumer onChange;
        private double current;

        ValueSlider(int x, int y, int w, Param p, double v, java.util.function.DoubleConsumer onChange) {
            super(x, y, w, 20, Component.empty(), 0);
            this.p = p;
            this.onChange = onChange;
            this.current = v;
            this.value = toT(v);
            updateMessage();
        }

        /** Below this share of a log slider whose range starts at 0, the value is 0 ("off"). */
        private static final double ZERO_T = 0.03;

        private double lo() {
            return p.min() > 0 ? p.min() : p.max() * 1e-4;
        }

        private double toT(double v) {
            if (p.log() && p.max() > 0) {
                if (v <= 0 && p.min() <= 0) return 0;
                double t = Math.log(Math.max(v, lo()) / lo()) / Math.log(p.max() / lo());
                return p.min() <= 0 ? ZERO_T + (1 - ZERO_T) * Math.max(0, t) : Math.max(0, Math.min(1, t));
            }
            return p.max() > p.min() ? Math.max(0, Math.min(1, (v - p.min()) / (p.max() - p.min()))) : 0;
        }

        private double fromT(double t) {
            double v;
            if (p.log() && p.max() > 0) {
                if (p.min() <= 0) {
                    if (t < ZERO_T) return 0;
                    t = (t - ZERO_T) / (1 - ZERO_T);
                }
                v = niceRound(lo() * Math.pow(p.max() / lo(), t), 2);
            } else {
                v = p.min() + t * (p.max() - p.min());
                double step = Math.pow(10, Math.floor(Math.log10(Math.max(1e-12, p.max() - p.min()))) - 2);
                v = Math.round(v / step) * step;
            }
            if (p.type() == PartParameters.Type.INTEGER) v = Math.round(v);
            return Math.max(p.min(), Math.min(p.max(), v));
        }

        void setFromValue(double v) {
            current = v;
            value = toT(v);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            // where 0 stands for something ("0: flat", "0: none"), say what
            String zero = p.help().startsWith("0: ") ? p.help().substring(3).split("[.;]")[0] : p.log() && p.min() <= 0 ? "off" : null;
            setMessage(Component.literal(current == 0 && zero != null ? "0 (" + zero + ")" : format(p, current)));
        }

        @Override
        protected void applyValue() {
            current = fromT(value);
            onChange.accept(current);
        }
    }
}
