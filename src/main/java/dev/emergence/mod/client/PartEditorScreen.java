package dev.emergence.mod.client;

import dev.emergence.core.assembly.PartKind;
import dev.emergence.core.materials.Material;
import dev.emergence.core.materials.MaterialDatabase;
import dev.emergence.mod.net.PartSpecEditPayload;
import dev.emergence.mod.part.PartSpec;
import dev.emergence.mod.part.PartSpecEdits;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * The part editor: what a part in hand is made of and its dimensions and ratings — the material, then each parameter
 * (blank: the part's default). Opened by right-clicking the air with a part. Done sends the specification to the
 * server, which checks it before the item changes.
 */
public final class PartEditorScreen extends Screen {
    private final PartKind kind;
    private final boolean offhand;
    private final PartSpec start;
    private final List<Material> materials;
    private int materialIndex;
    private EditBox materialBox, newKey, newValue;
    private final List<PartSpecEdits.Param> params;
    private final List<EditBox> values = new ArrayList<>();
    private String error;

    public PartEditorScreen(PartKind kind, boolean offhand, PartSpec spec) {
        super(Component.translatable("item.emergence." + kind.name().toLowerCase()));
        this.kind = kind;
        this.offhand = offhand;
        this.start = spec;
        this.materials = new ArrayList<>(MaterialDatabase.standard().all());
        materials.sort(Comparator.comparing(Material::id));
        for (int i = 0; i < materials.size(); i++) if (materials.get(i).id().equals(spec.material())) materialIndex = i;
        this.params = PartSpecEdits.params(kind, spec);
    }

    @Override
    protected void init() {
        values.clear();
        int x0 = width / 2 - 150, y = 34;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> cycle(-1)).bounds(x0, y, 20, 20).build());
        materialBox = new EditBox(font, x0 + 24, y, 252, 20, Component.literal("material"));
        materialBox.setMaxLength(48);
        materialBox.setValue(start.material());
        addRenderableWidget(materialBox);
        addRenderableWidget(Button.builder(Component.literal(">"), b -> cycle(1)).bounds(x0 + 280, y, 20, 20).build());
        y += 30;
        // the parameters, in two columns when there are many
        int perColumn = Math.max(1, (height - y - 80) / 22), col = 0, row = 0;
        int colWidth = params.size() > perColumn ? 150 : 300;
        for (PartSpecEdits.Param p : params) {
            int x = params.size() > perColumn ? (col == 0 ? width / 2 - 155 : width / 2 + 5) : x0;
            EditBox box = new EditBox(font, x + colWidth - 70, y + row * 22, 66, 18, Component.literal(p.key()));
            Double v = start.params().get(p.key());
            box.setValue(v == null ? "" : fmt(v));
            box.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                    (p.meaning().isEmpty() ? p.key() : p.meaning()) + (p.unit().isEmpty() ? "" : " [" + p.unit() + "]") + " — blank: the default")));
            values.add(addRenderableWidget(box));
            if (++row >= perColumn) {
                row = 0;
                col++;
            }
        }
        int yNew = height - 72;
        newKey = addRenderableWidget(new EditBox(font, x0, yNew, 180, 18, Component.literal("new parameter")));
        newKey.setHint(Component.literal("another parameter…"));
        newValue = addRenderableWidget(new EditBox(font, x0 + 186, yNew, 114, 18, Component.literal("value")));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> done()).bounds(width / 2 - 154, height - 44, 150, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(width / 2 + 4, height - 44, 150, 20).build());
    }

    private void cycle(int d) {
        if (materials.isEmpty()) return;
        materialIndex = Math.floorMod(materialIndex + d, materials.size());
        materialBox.setValue(materials.get(materialIndex).id());
    }

    private static String fmt(double v) {
        String s = String.format(Locale.ROOT, "%.6g", v);
        if (s.contains("e")) return s;
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }

    private void done() {
        TreeMap<String, Double> m = new TreeMap<>();
        for (int i = 0; i < params.size(); i++) {
            String text = values.get(i).getValue().trim();
            if (text.isEmpty()) continue;
            try {
                m.put(params.get(i).key(), Double.parseDouble(text));
            } catch (NumberFormatException ex) {
                error = params.get(i).key() + ": not a number";
                return;
            }
        }
        if (!newKey.getValue().isBlank()) {
            try {
                m.put(newKey.getValue().trim(), Double.parseDouble(newValue.getValue().trim()));
            } catch (NumberFormatException ex) {
                error = newKey.getValue().trim() + ": not a number";
                return;
            }
        }
        PartSpec spec = new PartSpec(materialBox.getValue().trim(), start.length(), start.cross(), m);
        String why = PartSpecEdits.validate(spec);
        if (why != null) {
            error = why;
            return;
        }
        PacketDistributor.sendToServer(new PartSpecEditPayload(offhand, spec));
        onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        Material m = MaterialDatabase.standard().getOrNull(materialBox.getValue().trim());
        g.drawCenteredString(font, m == null ? "unknown material" : m.name(), width / 2, 24, m == null ? 0xFF6060 : 0xA0A0A0);
        int perColumn = Math.max(1, (height - 64 - 80) / 22), colWidth = params.size() > perColumn ? 150 : 300;
        for (int i = 0; i < params.size(); i++) {
            EditBox box = values.get(i);
            PartSpecEdits.Param p = params.get(i);
            int x = box.getX() - colWidth + 70;
            g.drawString(font, p.key() + (p.unit().isEmpty() ? "" : " [" + p.unit() + "]"), x, box.getY() + 5, 0xE0E0E0);
        }
        if (error != null) g.drawCenteredString(font, error, width / 2, height - 56, 0xFF6060);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
