package dev.emergence.mod.client;

import dev.emergence.core.digital.ChipLibrary;
import dev.emergence.core.digital.Em8Assembler;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The program editor: a ROM's program in EM-8 assembly, assembled as one types — its errors (click to go to one), its
 * size, and a listing of what it became (address, bytes, instruction, the line under the cursor highlighted); the
 * instruction set at a glance. Done gives the program back to the ROM editor, which writes it to the ROM: the ROM then
 * holds the program's text and is assembled whenever its machine is built.
 */
public final class ProgramEditorScreen extends Screen {
    private static final String[] TABS = {"Listing", "Instructions"};
    private static final int TAB_H = 14;

    private final Screen parent;
    private final Consumer<String> onDone;
    private final String original;
    private CodeEditor editor;
    private String text;
    private Em8Assembler.Program program;
    private Em8Assembler.AsmError error;
    private int tab, panelW, panelX, listScroll;

    static final String TEMPLATE = """
            ; A program for the EM-8 processor (two bytes an instruction: see Instructions)
            .asm em8

            count = 0           ; a memory address
                    LDI 0
                    ST count
            loop:   INC count
                    LD count
                    OUT         ; the count on the output port
                    JMP loop
            """;

    ProgramEditorScreen(Screen parent, String text, Consumer<String> onDone) {
        super(Component.literal("Program editor"));
        this.parent = parent;
        this.onDone = onDone;
        this.original = text == null || text.isBlank() ? TEMPLATE : text;
        this.text = this.original;
        assemble();
    }

    private void assemble() {
        try {
            program = Em8Assembler.assemble(text);
            error = null;
        } catch (Em8Assembler.AsmError e) {
            program = null;
            error = e;
        }
        if (editor != null) editor.errorLine(error == null ? -1 : error.line - 1);
    }

    @Override
    protected void init() {
        panelW = Math.max(170, Math.min(250, width * 2 / 5));
        panelX = width - panelW - 4;
        editor = new CodeEditor(font, 6, 20, panelX - 10, height - 20 - 30, text);
        Set<String> kw = new HashSet<>();
        for (Em8Assembler.Op o : Em8Assembler.OPS) kw.add(o.name());
        kw.add(".ASM");
        kw.add(".ORG");
        kw.add(".BYTE");
        editor.syntax(kw, true, ";", "//");
        editor.onChange(t -> {
            text = t;
            assemble();
        });
        editor.errorLine(error == null ? -1 : error.line - 1);
        addRenderableWidget(editor);
        setInitialFocus(editor);
        int by = height - 25, ew = panelX - 10;
        addRenderableWidget(Button.builder(Component.literal("Example: thermostat"), b -> editor.replaceText(ChipLibrary.rom("thermostat")))
                .bounds(6, by, Math.max(80, ew / 2), 20).tooltip(Tooltip.create(Component.literal("The shipped thermostat program (Ctrl+Z to undo)."))).build());
        addRenderableWidget(Button.builder(Component.literal("Revert"), b -> editor.replaceText(original)).bounds(10 + Math.max(80, ew / 2), by,
                ew - Math.max(80, ew / 2) - 4, 20).build());
        int w1 = (panelW - 4) * 3 / 5;
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> done()).bounds(panelX, by, w1, 20)
                .tooltip(Tooltip.create(Component.literal("Back to the ROM editor with this program (Ctrl+S)."))).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(panelX + w1 + 4, by, panelW - w1 - 4, 20).build());
    }

    /** The program's size in bytes once assembled, or −1 while it does not assemble. */
    int assembledBytes() {
        return program == null ? -1 : program.bytes().length;
    }

    /** Whether the program's text holds {@code s}. */
    boolean shows(String s) {
        return text.contains(s);
    }

    private void done() {
        if (error != null) return; // only a program that assembles goes into a ROM
        onDone.accept(text);
        minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_S && hasControlDown()) {
            done();
            return true;
        }
        if (key != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && editor.isFocused() && editor.keyPressed(key, scan, mods)) return true;
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (my >= 20 && my < 20 + TAB_H && mx >= panelX && mx < panelX + panelW) {
            tab = (int) ((mx - panelX) * TABS.length / panelW);
            return true;
        }
        if (error != null && my >= 20 + TAB_H + 2 && my < 20 + TAB_H + 24 && mx >= panelX) {
            editor.goTo(error.line - 1, 0);
            setFocused(editor);
            editor.setFocused(true);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (mx >= panelX) {
            listScroll = Math.max(0, listScroll - (int) Math.signum(sy) * 3);
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        g.drawString(font, "Program editor — EM-8 assembly", 8, 6, 0xFFFFFFFF, true);
        int x = panelX, top = 20, bottom = height - 30;
        g.fill(x, top, x + panelW, bottom, 0xD0101418);
        g.renderOutline(x, top, panelW, bottom - top, 0xFF2A323C);
        int tw = panelW / TABS.length;
        for (int t = 0; t < TABS.length; t++) {
            int tx = x + t * tw;
            g.fill(tx, top, tx + tw, top + TAB_H, t == tab ? 0xFF2A3A4C : 0xFF161B21);
            g.drawString(font, TABS[t], tx + (tw - font.width(TABS[t])) / 2, top + 3, t == tab ? 0xFFFFFFFF : 0xFF9AA6B2, false);
        }
        int cx = x + 5, y = top + TAB_H + 4;
        if (error != null) {
            List<FormattedCharSequence> ls = font.split(Component.literal("✖ " + error.getMessage()), panelW - 10);
            for (int i = 0; i < Math.min(2, ls.size()); i++) g.drawString(font, ls.get(i), cx, y + 10 * i, 0xFFFF6B6B, false);
            y += 10 * Math.min(2, ls.size()) + 3;
        } else {
            int used = program.lineAt().size() * 2;
            g.drawString(font, "✔ Assembles: " + used + " of 256 bytes", cx, y, 0xFF6BD66B, false);
            y += 13;
        }
        g.enableScissor(x + 1, y - 1, x + panelW - 1, bottom - 1);
        if (tab == 0) drawListing(g, cx, y, bottom);
        else drawOps(g, cx, y);
        g.disableScissor();
    }

    private void drawListing(GuiGraphics g, int x, int y, int bottom) {
        if (program == null) return;
        var addrs = program.lineAt().keySet().stream().sorted().toList();
        int start = Math.min(listScroll, Math.max(0, addrs.size() - 1));
        for (int i = start; i < addrs.size() && y < bottom - 4; i++) {
            int a = addrs.get(i), b0 = program.bytes()[a], b1 = program.bytes()[(a + 1) & 255];
            String label = "";
            for (var en : program.labels().entrySet()) if (en.getValue() == a) label = en.getKey() + ":";
            String s = String.format("%02X  %02X %02X  %s", a, b0, b1, Em8Assembler.disassemble(program.bytes(), a));
            g.drawString(font, s, x, y, 0xFFD8DEE4, false);
            if (!label.isEmpty()) g.drawString(font, label, x + panelW - 14 - font.width(label), y, 0xFFFFD27A, false);
            y += 10;
        }
    }

    private void drawOps(GuiGraphics g, int x, int y) {
        for (Em8Assembler.Op o : Em8Assembler.OPS) {
            g.drawString(font, String.format("%02X", o.code()), x, y, 0xFF5C6773, false);
            g.drawString(font, o.name() + (o.operand() ? " n" : ""), x + 18, y, 0xFFC586C0, false);
            g.drawString(font, font.plainSubstrByWidth(o.help(), panelW - 80), x + 66, y, 0xFFD8DEE4, false);
            y += 10;
        }
    }
}
