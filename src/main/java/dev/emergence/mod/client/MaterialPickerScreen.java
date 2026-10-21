package dev.emergence.mod.client;

import dev.emergence.core.materials.Material;
import dev.emergence.core.materials.MaterialCategory;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Choosing a part's material from all it may be made of at once, grouped by kind (metals, alloys, polymers…), each
 * with its properties on hover — rather than stepping through a long list one at a time.
 */
final class MaterialPickerScreen extends Screen {
    private static final int COL_W = 150, ROW = 22;

    private final Screen parent;
    private final List<Material> materials;
    private final String current;
    private final Consumer<Material> chosen;
    private final Function<Material, String> note;
    private final List<Object[]> headings = new ArrayList<>();
    private int scroll, rows;

    MaterialPickerScreen(Screen parent, List<Material> materials, String current, Function<Material, String> note, Consumer<Material> chosen) {
        super(Component.literal("Material"));
        this.parent = parent;
        this.materials = materials;
        this.current = current;
        this.note = note;
        this.chosen = chosen;
    }

    @Override
    protected void init() {
        headings.clear();
        Map<MaterialCategory, List<Material>> by = new EnumMap<>(MaterialCategory.class);
        for (Material m : materials) by.computeIfAbsent(m.category(), k -> new ArrayList<>()).add(m);
        int cols = Math.max(1, Math.min(3, (width - 40) / COL_W)), x0 = width / 2 - cols * COL_W / 2;
        int top = 30, bottom = height - 34, visible = Math.max(1, (bottom - top) / ROW);
        // the groups row by row: a heading, then its materials in columns
        List<Object[]> cells = new ArrayList<>(); // {row, column, material or category}
        int row = 0;
        for (var e : by.entrySet()) {
            cells.add(new Object[]{row++, 0, e.getKey()});
            int col = 0;
            for (Material m : e.getValue()) {
                cells.add(new Object[]{row, col, m});
                if (++col == cols) {
                    col = 0;
                    row++;
                }
            }
            if (col != 0) row++;
        }
        rows = row;
        scroll = Math.max(0, Math.min(scroll, rows - visible));
        for (Object[] c : cells) {
            int r = (int) c[0] - scroll;
            if (r < 0 || r >= visible) continue;
            int y = top + r * ROW, x = x0 + (int) c[1] * COL_W;
            if (c[2] instanceof MaterialCategory cat) {
                headings.add(new Object[]{name(cat), x0 + 2, y + 8});
            } else {
                Material m = (Material) c[2];
                String label = (m.id().equals(current) ? "▶ " : "") + m.name().split(" \\(")[0];
                addRenderableWidget(Button.builder(Component.literal(label), b -> {
                    chosen.accept(m);
                    minecraft.setScreen(parent);
                }).bounds(x + 2, y, COL_W - 4, 20).tooltip(Tooltip.create(Component.literal(note.apply(m)))).build());
            }
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> minecraft.setScreen(parent))
                .bounds(width / 2 - 50, height - 28, 100, 20).build());
    }

    private static String name(MaterialCategory c) {
        return switch (c) {
            case METAL -> "Metals";
            case ALLOY -> "Alloys";
            case CERAMIC -> "Ceramics";
            case POLYMER -> "Polymers";
            case COMPOSITE -> "Composites";
            case SEMICONDUCTOR -> "Semiconductors";
            case GLASS -> "Glasses";
            case MAGNETIC -> "Magnetic materials";
            case INSULATOR -> "Insulators";
            case NATURAL -> "Natural materials";
            case FLUID -> "Solutions";
        };
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        int before = scroll;
        scroll = Math.max(0, scroll - (int) Math.signum(dy));
        if (scroll != before) rebuildWidgets();
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, "Choose a material (hover for its properties" + (rows * ROW > height - 64 ? ", scroll for more" : "") + ")",
                width / 2, 12, 0xFFFFFF);
        for (Object[] h : headings) g.drawString(font, (String) h[0], (int) h[1], (int) h[2], 0xFFE080);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
