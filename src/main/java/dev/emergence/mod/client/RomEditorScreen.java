package dev.emergence.mod.client;

import dev.emergence.core.digital.ChipLibrary;
import dev.emergence.core.digital.MsiComponent;
import dev.emergence.mod.net.ChipProbePayload;
import dev.emergence.mod.net.ChipTextPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * The ROM editor: a 256-byte ROM's contents as a hex editor shows them — sixteen bytes a row, each row's address
 * beside it, the bytes as characters at the right — and under it the bytes as columns of pixels, bit 0 at the top
 * (or the bottom), so a font's glyphs are seen as shapes and drawn by clicking their pixels. Type hex digits to
 * change a byte; arrows, Page Up/Down, Home/End move; shift selects; Ctrl+C/V copy and paste hex; Ctrl+Z/Y undo and
 * redo. Opened on a placed ROM it also shows the address being read now. On a small screen the grid scrolls.
 */
public final class RomEditorScreen extends Screen {
    private static final int SIZE = 256, COLS = 16, ROW_H = 9, CELL_W = 14;

    private final Screen parent;
    private final Consumer<String> onDone;
    private final BlockPos pos;
    private final long partId;
    private final String originalText;
    private final int[] original;
    private int[] bytes;
    private String parseError;
    private int cursor, anchor = -1, nibble;
    private boolean bitZeroTop = true;
    private final List<int[]> undo = new ArrayList<>(), redo = new ArrayList<>();
    private ChipProbePayload live;
    private int ticks;
    // layout: the grid's corner, the character column, rows shown and the first, the info line, the pixel view
    private int gx, gy, ax, visRows, scrollRow, by, px, bitStart;
    private int dragging = -1;

    private RomEditorScreen(Screen parent, Consumer<String> onDone, BlockPos pos, long partId, String text) {
        super(Component.literal("ROM editor"));
        this.parent = parent;
        this.onDone = onDone;
        this.pos = pos;
        this.partId = partId;
        this.originalText = text == null ? "" : text;
        int[] b;
        try {
            b = MsiComponent.parseHex(originalText, SIZE);
        } catch (IllegalArgumentException e) {
            parseError = e.getMessage();
            b = new int[SIZE];
        }
        original = b;
        bytes = b.clone();
    }

    public static RomEditorScreen forItem(Screen parent, String text, Consumer<String> onDone) {
        return new RomEditorScreen(parent, onDone, null, 0, text);
    }

    public static RomEditorScreen forPlaced(BlockPos pos, long partId, String text) {
        return new RomEditorScreen(null, null, pos, partId, text);
    }

    boolean placed() {
        return pos != null;
    }

    long partId() {
        return partId;
    }

    void live(ChipProbePayload p) {
        live = p;
    }

    boolean livePowered() {
        return live != null && live.simulated() && live.powered();
    }

    int byteAt(int a) {
        return bytes[a];
    }

    /** The contents as text: as they came if unchanged (their comments kept), else freshly written out. */
    String text() {
        return parseError == null && Arrays.equals(bytes, original) ? originalText : MsiComponent.formatHex(bytes);
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        int gridW = 20 + COLS * CELL_W + 8 + COLS * 6;
        gx = Math.max(6, (width - gridW) / 2) + 20;
        gy = 30;
        ax = gx + COLS * CELL_W + 8;
        px = height >= 320 ? 6 : height >= 260 ? 5 : 4;
        visRows = Math.max(4, Math.min(SIZE / COLS, (height - gy - 30 - 14 - 14 - 8 * px) / ROW_H));
        by = gy + visRows * ROW_H + 5;
        reveal();
        int bt = height - 24, x = 6;
        List<String> presets = new ArrayList<>(List.of(ChipLibrary.ROMS));
        presets.add(0, "");
        addRenderableWidget(CycleButton.<String>builder(n -> Component.literal(n.isEmpty() ? "Load ▸" : ChipLibrary.romTitle(n)))
                .withValues(presets).withInitialValue("").displayOnlyValue()
                .withTooltip(n -> Tooltip.create(Component.literal("ROM images that come with the mod: pick one to load it (Ctrl+Z to undo).")))
                .create(x, bt, 84, 20, Component.literal("Load"), (b, n) -> {
                    if (!n.isEmpty()) {
                        push();
                        bytes = MsiComponent.parseHex(ChipLibrary.rom(n), SIZE);
                    }
                }));
        x += 88;
        addRenderableWidget(Button.builder(Component.literal("Clear"), b -> {
            push();
            Arrays.fill(bytes, 0);
        }).bounds(x, bt, 40, 20).tooltip(Tooltip.create(Component.literal("Every byte to 00 (Ctrl+Z to undo)."))).build());
        x += 44;
        addRenderableWidget(Button.builder(Component.literal("Copy"), b -> minecraft.keyboardHandler.setClipboard(MsiComponent.formatHex(bytes)))
                .bounds(x, bt, 40, 20).tooltip(Tooltip.create(Component.literal("All of it to the clipboard as hex text (Ctrl+C copies the selection)."))).build());
        x += 44;
        addRenderableWidget(Button.builder(Component.literal("Paste"), b -> pasteAll()).bounds(x, bt, 42, 20)
                .tooltip(Tooltip.create(Component.literal("Replace it all with hex text from the clipboard (\"@1C\" moves on to address 1C; // starts a comment). "
                        + "Ctrl+V pastes at the cursor."))).build());
        x += 46;
        addRenderableWidget(Button.builder(Component.literal(bitZeroTop ? "Bit 0 ↑" : "Bit 0 ↓"), b -> {
            bitZeroTop = !bitZeroTop;
            b.setMessage(Component.literal(bitZeroTop ? "Bit 0 ↑" : "Bit 0 ↓"));
        }).bounds(x, bt, 44, 20).tooltip(Tooltip.create(Component.literal("Which way up the pixel view draws each byte's bits."))).build());
        int rx = width - 128;
        addRenderableWidget(Button.builder(Component.literal(placed() ? "Write to ROM" : "Done"), b -> done()).bounds(rx, bt, 72, 20)
                .tooltip(Tooltip.create(Component.literal(placed() ? "Put these bytes in the ROM (Ctrl+S)." : "Back to the part editor with these bytes (Ctrl+S)."))).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(rx + 74, bt, 50, 20).build());
    }

    private void done() {
        if (placed()) {
            PacketDistributor.sendToServer(new ChipTextPayload(pos, partId, text()));
            minecraft.setScreen(null);
        } else {
            onDone.accept(text());
            minecraft.setScreen(parent);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void tick() {
        if (placed() && ticks++ % 4 == 0) PacketDistributor.sendToServer(new ChipProbePayload.Request(pos, partId));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Scrolls the grid to show the cursor's row. */
    private void reveal() {
        int r = cursor / COLS;
        if (r < scrollRow) scrollRow = r;
        if (r >= scrollRow + visRows) scrollRow = r - visRows + 1;
        scrollRow = Math.max(0, Math.min(SIZE / COLS - visRows, scrollRow));
    }

    // ------------------------------------------------------------------ editing

    private void push() {
        undo.add(bytes.clone());
        if (undo.size() > 300) undo.remove(0);
        redo.clear();
        parseError = null;
    }

    private void pasteAll() {
        try {
            int[] b = MsiComponent.parseHex(minecraft.keyboardHandler.getClipboard(), SIZE);
            push();
            bytes = b;
        } catch (IllegalArgumentException e) {
            parseError = "Paste: " + e.getMessage();
        }
    }

    private void moveTo(int c, boolean select) {
        if (select && anchor < 0) anchor = cursor;
        else if (!select) anchor = -1;
        cursor = Math.max(0, Math.min(SIZE - 1, c));
        nibble = 0;
        reveal();
    }

    private int selLo() {
        return anchor < 0 ? cursor : Math.min(anchor, cursor);
    }

    private int selHi() {
        return anchor < 0 ? cursor : Math.max(anchor, cursor);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        boolean shift = hasShiftDown(), ctrl = hasControlDown();
        switch (key) {
            case GLFW.GLFW_KEY_LEFT -> moveTo(cursor - 1, shift);
            case GLFW.GLFW_KEY_RIGHT -> moveTo(cursor + 1, shift);
            case GLFW.GLFW_KEY_UP -> moveTo(cursor - COLS, shift);
            case GLFW.GLFW_KEY_DOWN -> moveTo(cursor + COLS, shift);
            case GLFW.GLFW_KEY_PAGE_UP -> moveTo(cursor - visRows * COLS, shift);
            case GLFW.GLFW_KEY_PAGE_DOWN -> moveTo(cursor + visRows * COLS, shift);
            case GLFW.GLFW_KEY_HOME -> moveTo(ctrl ? 0 : cursor - cursor % COLS, shift);
            case GLFW.GLFW_KEY_END -> moveTo(ctrl ? SIZE - 1 : cursor - cursor % COLS + COLS - 1, shift);
            case GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_KEY_DELETE -> {
                push();
                for (int i = selLo(); i <= selHi(); i++) bytes[i] = 0;
                if (key == GLFW.GLFW_KEY_BACKSPACE && anchor < 0) moveTo(cursor - 1, false);
                anchor = -1;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> done();
            default -> {
                if (ctrl && key == GLFW.GLFW_KEY_C) {
                    minecraft.keyboardHandler.setClipboard(MsiComponent.formatHex(Arrays.copyOfRange(bytes, selLo(), selHi() + 1)).trim());
                } else if (ctrl && key == GLFW.GLFW_KEY_V) {
                    paste(minecraft.keyboardHandler.getClipboard());
                } else if (ctrl && key == GLFW.GLFW_KEY_A) {
                    anchor = 0;
                    cursor = SIZE - 1;
                } else if (ctrl && key == GLFW.GLFW_KEY_Z) {
                    swap(shift ? redo : undo, shift ? undo : redo);
                } else if (ctrl && key == GLFW.GLFW_KEY_Y) {
                    swap(redo, undo);
                } else if (ctrl && key == GLFW.GLFW_KEY_S) {
                    done();
                } else {
                    return super.keyPressed(key, scan, mods);
                }
            }
        }
        return true;
    }

    private void swap(List<int[]> from, List<int[]> to) {
        if (from.isEmpty()) return;
        to.add(bytes.clone());
        bytes = from.remove(from.size() - 1);
    }

    /** Hex bytes from the clipboard written from the cursor on. */
    private void paste(String clip) {
        try {
            int[] b = MsiComponent.parseHex(clip, SIZE);
            int n = 0;
            for (String tok : clip.replaceAll("//.*", "").replaceAll("#.*", "").trim().split("[\\s,]+")) {
                if (tok.isEmpty() || tok.startsWith("@")) continue;
                String t = tok.startsWith("0x") || tok.startsWith("0X") ? tok.substring(2) : tok;
                n += (t.length() + 1) / 2;
            }
            push();
            for (int i = 0; i < n && cursor + i < SIZE; i++) bytes[cursor + i] = b[i];
            moveTo(cursor + n, false);
        } catch (IllegalArgumentException e) {
            parseError = "Paste: " + e.getMessage();
        }
    }

    @Override
    public boolean charTyped(char c, int mods) {
        int d = Character.digit(c, 16);
        if (d < 0) return false;
        if (nibble == 0) {
            push();
            bytes[cursor] = (bytes[cursor] & 0x0F) | d << 4;
            nibble = 1;
            anchor = -1;
        } else {
            bytes[cursor] = (bytes[cursor] & 0xF0) | d;
            if (cursor < SIZE - 1) cursor++;
            nibble = 0;
            reveal();
        }
        return true;
    }

    /** The byte under the mouse in the grid or the character column, or −1. */
    private int cellAt(double mx, double my) {
        int r = (int) Math.floor((my - gy) / ROW_H);
        if (r < 0 || r >= visRows) return -1;
        r += scrollRow;
        if (mx >= gx && mx < gx + COLS * CELL_W) return r * COLS + (int) ((mx - gx) / CELL_W);
        if (mx >= ax && mx < ax + COLS * 6) return r * COLS + (int) ((mx - ax) / 6);
        return -1;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        int c = cellAt(mx, my);
        if (c >= 0) {
            moveTo(c, hasShiftDown());
            if (!hasShiftDown()) anchor = c;
            dragging = c;
            return true;
        }
        // a pixel of the bit view: flip it
        int col = (int) Math.floor((mx - bitX()) / px), row = (int) Math.floor((my - by - 12) / px);
        if (col >= 0 && col < bitCount() && row >= 0 && row < 8) {
            int a = bitStart + col, bit = bitZeroTop ? row : 7 - row;
            push();
            bytes[a] ^= 1 << bit;
            cursor = a;
            anchor = -1;
            nibble = 0;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging >= 0) {
            int c = cellAt(mx, my);
            if (c >= 0) cursor = c;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragging = -1;
        if (anchor == cursor) anchor = -1;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (my < by) {
            scrollRow = Math.max(0, Math.min(SIZE / COLS - visRows, scrollRow - (int) Math.signum(sy)));
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    private int bitCount() {
        return Math.min(SIZE, Math.max(16, (width - 24) / px / 16 * 16));
    }

    private int bitX() {
        return (width - bitCount() * px) / 2;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        g.drawString(font, "ROM editor — 256 × 8", 8, 6, 0xFFFFFFFF, true);
        String where = placed() ? "placed ROM at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() : "ROM in hand";
        g.drawString(font, where, width - 8 - font.width(where), 6, 0xFF8A949E, false);
        int reading = -1;
        if (live != null && live.simulated() && live.inputs().length >= 8 && live.powered()) {
            reading = 0;
            for (int i = 0; i < 8; i++) if (live.inputs()[i]) reading |= 1 << i;
        }
        // the grid
        g.fill(gx - 24, gy - 13, ax + COLS * 6 + 4, gy + visRows * ROW_H + 1, 0xD0101418);
        for (int c = 0; c < COLS; c++) g.drawString(font, Integer.toHexString(c).toUpperCase(), gx + c * CELL_W + 3, gy - 11, 0xFF5C6773, false);
        int lo = selLo(), hi = selHi();
        for (int vr = 0; vr < visRows; vr++) {
            int r = scrollRow + vr, y = gy + vr * ROW_H;
            g.drawString(font, String.format("%02X", r * COLS), gx - 20, y, 0xFF5C6773, false);
            for (int c = 0; c < COLS; c++) {
                int a = r * COLS + c, x = gx + c * CELL_W;
                if (anchor >= 0 && a >= lo && a <= hi) g.fill(x - 1, y - 1, x + CELL_W - 2, y + ROW_H - 1, 0x804A7FC0);
                if (a == cursor) g.fill(x - 1, y - 1, x + CELL_W - 2, y + ROW_H - 1, 0xA0306090);
                if (a == reading) g.renderOutline(x - 2, y - 2, CELL_W + 1, ROW_H + 1, 0xFF7CFF7C);
                int v = bytes[a];
                int colour = v == 0 ? 0xFF4A535C : v != original[a] ? 0xFFFFC66B : 0xFFD8DEE4;
                String h = String.format("%02X", v);
                if (a == cursor && nibble == 1) {
                    g.drawString(font, h.substring(0, 1) + "_", x, y, 0xFFFFFFFF, false);
                } else {
                    g.drawString(font, h, x, y, colour, false);
                }
                g.drawString(font, v >= 32 && v < 127 ? String.valueOf((char) v) : "·", ax + c * 6, y, a == cursor ? 0xFFFFFFFF : 0xFF7A8794, false);
            }
        }
        if (visRows < SIZE / COLS) {
            int barH = Math.max(6, visRows * ROW_H * visRows / (SIZE / COLS)), barY = gy + (visRows * ROW_H - barH) * scrollRow / (SIZE / COLS - visRows);
            g.fill(ax + COLS * 6 + 1, barY, ax + COLS * 6 + 3, barY + barH, 0x80FFFFFF);
        }
        // the selected byte, every way (or what went wrong)
        int v = bytes[cursor];
        if (parseError != null) {
            g.drawString(font, font.plainSubstrByWidth("✖ " + parseError, width - 16), gx - 20, by, 0xFFFF6B6B, false);
        } else {
            g.drawString(font, String.format("%02X:  0x%02X   %d   0b%s", cursor, v, v, String.format("%8s", Integer.toBinaryString(v)).replace(' ', '0')),
                    gx - 20, by, 0xFFE0E6EC, false);
        }
        if (reading >= 0) {
            String rd = String.format("being read: %02X", reading);
            g.drawString(font, rd, ax + COLS * 6 + 4 - font.width(rd), by, 0xFF7CFF7C, false);
        } else if (placed() && live != null && live.simulated() && !live.powered()) {
            g.drawString(font, "unpowered", ax + COLS * 6 + 4 - font.width("unpowered"), by, 0xFFFF9B5C, false);
        }
        // the pixel view: the bytes around the cursor as columns, bit 0 at the top (or bottom); click a pixel to flip it
        int n = bitCount();
        bitStart = Math.max(0, Math.min(SIZE - n, (cursor / COLS) * COLS - (n / COLS / 2) * COLS));
        int bx = bitX(), y0 = by + 12;
        g.fill(bx - 2, y0 - 2, bx + n * px + 2, y0 + 8 * px + 2, 0xFF0A0806);
        for (int i = 0; i < n; i++) {
            int a = bitStart + i, x = bx + i * px;
            if (a == cursor || anchor >= 0 && a >= lo && a <= hi) g.fill(x, y0 - 2, x + px, y0 + 8 * px + 2, a == cursor ? 0x60306090 : 0x404A7FC0);
            if (a == reading) g.fill(x, y0 + 8 * px, x + px, y0 + 8 * px + 2, 0xFF7CFF7C);
            for (int b = 0; b < 8; b++) {
                if (((bytes[a] >> b) & 1) == 0) continue;
                int yy = y0 + (bitZeroTop ? b : 7 - b) * px;
                g.fill(x, yy, x + px - 1, yy + px - 1, 0xFFE8F4FF);
            }
            if (a % 16 == 0) g.fill(x, y0 - 2, x + 1, y0, 0xFF5C6773);
        }
    }
}
