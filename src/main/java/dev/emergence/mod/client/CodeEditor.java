package dev.emergence.mod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A text editor for the chip language: line numbers, syntax colouring, the line with an error marked, a cursor and a
 * selection (mouse or shift + keys), clipboard (Ctrl+C/X/V, Ctrl+A), undo and redo (Ctrl+Z/Y), indentation kept on
 * Enter and Tab as four spaces, scrolling with the wheel or the keys.
 */
public final class CodeEditor extends AbstractWidget {
    static final int LINE_H = 10, GUTTER = 30, PAD = 4;
    static final int BG = 0xF0101418, GUTTER_BG = 0xF0181D23, CURRENT_LINE = 0x30FFFFFF, SELECTION = 0x804A7FC0,
            ERROR_LINE = 0x50FF3030, TEXT = 0xFFD8DEE4, NUMBER_FG = 0xFF5C6773, COMMENT = 0xFF6A9955, KEYWORD = 0xFFC586C0,
            LITERAL = 0xFFE5A55B, OPERATOR = 0xFF9CDCFE, BRACE = 0xFFFFD700;
    static final Set<String> KEYWORDS = Set.of("chip", "in", "out", "reg", "wire", "mem", "param", "assign", "on", "rise", "fall",
            "if", "else", "case", "default");

    private final Font font;
    private final List<String> lines = new ArrayList<>();
    private int line, col;            // the cursor
    private int anchorLine = -1, anchorCol;  // the selection's other end (−1: none)
    private int top, left;            // the first line shown, and pixels scrolled to the right
    private int errorLine = -1;
    private long blink;
    private final List<String[]> undo = new ArrayList<>(), redo = new ArrayList<>();
    private Consumer<String> onChange = s -> {
    };

    public CodeEditor(Font font, int x, int y, int w, int h, String text) {
        super(x, y, w, h, Component.literal("Design"));
        this.font = font;
        setText(text);
    }

    public void onChange(Consumer<String> c) {
        onChange = c;
    }

    public String text() {
        return String.join("\n", lines);
    }

    /** Replaces the whole text (undoable). */
    public void replaceText(String text) {
        pushUndo();
        setText(text);
        changed();
    }

    private void setText(String text) {
        lines.clear();
        for (String l : (text == null ? "" : text).replace("\r", "").replace("\t", "    ").split("\n", -1)) lines.add(l);
        line = Math.min(line, lines.size() - 1);
        col = Math.min(col, lines.get(line).length());
        anchorLine = -1;
    }

    /** Marks the line (0-based) with an error, or −1 for none. */
    public void errorLine(int l) {
        errorLine = l;
    }

    /** Puts the cursor at a line and column (0-based) and shows it. */
    public void goTo(int l, int c) {
        line = Math.max(0, Math.min(lines.size() - 1, l));
        col = Math.max(0, Math.min(lines.get(line).length(), c));
        anchorLine = -1;
        reveal();
    }

    private int rows() {
        return Math.max(1, (height - 2 * PAD) / LINE_H);
    }

    private void reveal() {
        if (line < top) top = line;
        if (line >= top + rows()) top = line - rows() + 1;
        int cx = font.width(lines.get(line).substring(0, col)), w = width - GUTTER - 2 * PAD - 6;
        if (cx - left > w) left = cx - w + 20;
        if (cx < left) left = Math.max(0, cx - 20);
        blink = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float pt) {
        int x0 = getX(), y0 = getY();
        g.fill(x0, y0, x0 + width, y0 + height, BG);
        g.fill(x0, y0, x0 + GUTTER, y0 + height, GUTTER_BG);
        g.renderOutline(x0, y0, width, height, isFocused() ? 0xFF4A7FC0 : 0xFF2A323C);
        int tx = x0 + GUTTER + PAD;
        g.enableScissor(x0 + 1, y0 + 1, x0 + width - 1, y0 + height - 1);
        int[] s = selStart(), e = selEnd();
        for (int r = 0; r < rows() && top + r < lines.size(); r++) {
            int l = top + r, y = y0 + PAD + r * LINE_H;
            if (l == errorLine) g.fill(x0 + GUTTER, y - 1, x0 + width, y + LINE_H - 1, ERROR_LINE);
            else if (l == line && isFocused()) g.fill(x0 + GUTTER, y - 1, x0 + width, y + LINE_H - 1, CURRENT_LINE);
            String num = String.valueOf(l + 1);
            g.drawString(font, num, x0 + GUTTER - 4 - font.width(num), y, l == errorLine ? 0xFFFF6060 : NUMBER_FG, false);
            String text = lines.get(l);
            // the selection on this line
            if (s != null && l >= s[0] && l <= e[0]) {
                int a = l == s[0] ? s[1] : 0, b = l == e[0] ? e[1] : text.length();
                int xa = tx - left + font.width(text.substring(0, a)), xb = tx - left + font.width(text.substring(0, b)) + (l < e[0] ? 4 : 0);
                g.fill(Math.max(xa, x0 + GUTTER), y - 1, xb, y + LINE_H - 1, SELECTION);
            }
            g.enableScissor(x0 + GUTTER, y0 + 1, x0 + width - 1, y0 + height - 1);
            drawColoured(g, text, tx - left, y);
            g.disableScissor();
            if (l == line && isFocused() && (System.currentTimeMillis() - blink) % 1000 < 550) {
                int cx = tx - left + font.width(text.substring(0, col));
                g.fill(cx, y - 1, cx + 1, y + LINE_H - 1, 0xFFFFFFFF);
            }
        }
        g.disableScissor();
        // a scroll bar when it does not all fit
        if (lines.size() > rows()) {
            int bh = Math.max(10, height * rows() / lines.size()), by = y0 + (height - bh) * top / Math.max(1, lines.size() - rows());
            g.fill(x0 + width - 3, by, x0 + width - 1, by + bh, 0x80FFFFFF);
        }
    }

    /** Draws a line in colour: comments, keywords, numbers, operators, braces. */
    private void drawColoured(GuiGraphics g, String text, int x, int y) {
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            int j = i + 1, colour = TEXT;
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                j = n;
                colour = COMMENT;
            } else if (Character.isLetter(c) || c == '_') {
                while (j < n && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_')) j++;
                if (KEYWORDS.contains(text.substring(i, j))) colour = KEYWORD;
            } else if (Character.isDigit(c)) {
                while (j < n && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '\'' || text.charAt(j) == '_')) j++;
                colour = LITERAL;
            } else if (c == '{' || c == '}' || c == '(' || c == ')' || c == '[' || c == ']') {
                colour = BRACE;
            } else if (c != ' ') {
                colour = OPERATOR;
            }
            String tok = text.substring(i, j);
            g.drawString(font, tok, x, y, colour, false);
            x += font.width(tok);
            i = j;
        }
    }

    // ------------------------------------------------------------------ selection

    private boolean hasSel() {
        return anchorLine >= 0 && (anchorLine != line || anchorCol != col);
    }

    private int[] selStart() {
        if (!hasSel()) return null;
        return anchorLine < line || anchorLine == line && anchorCol < col ? new int[]{anchorLine, anchorCol} : new int[]{line, col};
    }

    private int[] selEnd() {
        if (!hasSel()) return null;
        return anchorLine < line || anchorLine == line && anchorCol < col ? new int[]{line, col} : new int[]{anchorLine, anchorCol};
    }

    private String selected() {
        int[] s = selStart(), e = selEnd();
        if (s == null) return "";
        if (s[0] == e[0]) return lines.get(s[0]).substring(s[1], e[1]);
        StringBuilder sb = new StringBuilder(lines.get(s[0]).substring(s[1]));
        for (int l = s[0] + 1; l < e[0]; l++) sb.append('\n').append(lines.get(l));
        return sb.append('\n').append(lines.get(e[0]), 0, e[1]).toString();
    }

    private void deleteSelection() {
        int[] s = selStart(), e = selEnd();
        if (s == null) return;
        String head = lines.get(s[0]).substring(0, s[1]), tail = lines.get(e[0]).substring(e[1]);
        for (int l = e[0]; l > s[0]; l--) lines.remove(l);
        lines.set(s[0], head + tail);
        line = s[0];
        col = s[1];
        anchorLine = -1;
    }

    private void insert(String t) {
        deleteSelection();
        String cur = lines.get(line), head = cur.substring(0, col), tail = cur.substring(col);
        String[] parts = t.replace("\r", "").replace("\t", "    ").split("\n", -1);
        if (parts.length == 1) {
            lines.set(line, head + parts[0] + tail);
            col += parts[0].length();
        } else {
            lines.set(line, head + parts[0]);
            for (int k = 1; k < parts.length - 1; k++) lines.add(line + k, parts[k]);
            lines.add(line + parts.length - 1, parts[parts.length - 1] + tail);
            line += parts.length - 1;
            col = parts[parts.length - 1].length();
        }
    }

    // ------------------------------------------------------------------ editing

    private void pushUndo() {
        undo.add(new String[]{text(), String.valueOf(line), String.valueOf(col)});
        if (undo.size() > 300) undo.remove(0);
        redo.clear();
    }

    private void restore(List<String[]> from, List<String[]> to) {
        if (from.isEmpty()) return;
        to.add(new String[]{text(), String.valueOf(line), String.valueOf(col)});
        String[] st = from.remove(from.size() - 1);
        setText(st[0]);
        goTo(Integer.parseInt(st[1]), Integer.parseInt(st[2]));
        changed();
    }

    private void changed() {
        reveal();
        onChange.accept(text());
    }

    private void move(int l, int c, boolean select) {
        if (select && anchorLine < 0) {
            anchorLine = line;
            anchorCol = col;
        } else if (!select) {
            anchorLine = -1;
        }
        line = Math.max(0, Math.min(lines.size() - 1, l));
        col = Math.max(0, Math.min(lines.get(line).length(), c));
        reveal();
    }

    private static boolean word(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (!isFocused()) return false;
        boolean ctrl = Screen.hasControlDown(), shift = Screen.hasShiftDown();
        String cur = lines.get(line);
        switch (key) {
            case GLFW.GLFW_KEY_LEFT -> {
                if (!shift && hasSel()) {
                    int[] s = selStart();
                    move(s[0], s[1], false);
                } else if (col > 0) {
                    int c = col - 1;
                    if (ctrl) while (c > 0 && word(cur.charAt(c - 1)) == word(cur.charAt(c))) c--;
                    move(line, c, shift);
                } else if (line > 0) move(line - 1, lines.get(line - 1).length(), shift);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                if (!shift && hasSel()) {
                    int[] e = selEnd();
                    move(e[0], e[1], false);
                } else if (col < cur.length()) {
                    int c = col + 1;
                    if (ctrl) while (c < cur.length() && word(cur.charAt(c)) == word(cur.charAt(c - 1))) c++;
                    move(line, c, shift);
                } else if (line < lines.size() - 1) move(line + 1, 0, shift);
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                move(line - 1, colAt(line - 1, xOf(line, col)), shift);
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                move(line + 1, colAt(line + 1, xOf(line, col)), shift);
                return true;
            }
            case GLFW.GLFW_KEY_PAGE_UP -> {
                move(line - rows(), col, shift);
                top = Math.max(0, top - rows());
                return true;
            }
            case GLFW.GLFW_KEY_PAGE_DOWN -> {
                move(line + rows(), col, shift);
                top = Math.max(0, Math.min(lines.size() - rows(), top + rows()));
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                if (ctrl) move(0, 0, shift);
                else {
                    int ind = cur.length() - cur.stripLeading().length();
                    move(line, col == ind ? 0 : ind, shift);
                }
                return true;
            }
            case GLFW.GLFW_KEY_END -> {
                if (ctrl) move(lines.size() - 1, lines.get(lines.size() - 1).length(), shift);
                else move(line, cur.length(), shift);
                return true;
            }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                pushUndo();
                if (hasSel()) deleteSelection();
                else if (col > 0) {
                    // a run of indent spaces goes back to the previous tab stop
                    int c = col - 1;
                    if (cur.substring(0, col).isBlank()) c = Math.max(0, (col - 1) / 4 * 4);
                    lines.set(line, cur.substring(0, c) + cur.substring(col));
                    col = c;
                } else if (line > 0) {
                    int c = lines.get(line - 1).length();
                    lines.set(line - 1, lines.get(line - 1) + cur);
                    lines.remove(line);
                    line--;
                    col = c;
                }
                changed();
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                pushUndo();
                if (hasSel()) deleteSelection();
                else if (col < cur.length()) lines.set(line, cur.substring(0, col) + cur.substring(col + 1));
                else if (line < lines.size() - 1) {
                    lines.set(line, cur + lines.get(line + 1));
                    lines.remove(line + 1);
                }
                changed();
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                pushUndo();
                String ind = " ".repeat(cur.length() - cur.stripLeading().length());
                if (cur.substring(0, Math.min(col, cur.length())).stripTrailing().endsWith("{")) ind += "    ";
                insert("\n" + ind);
                changed();
                return true;
            }
            case GLFW.GLFW_KEY_TAB -> {
                pushUndo();
                if (hasSel()) {
                    // indent (or with shift, unindent) every selected line
                    int[] s = selStart(), e = selEnd();
                    for (int l = s[0]; l <= e[0]; l++) {
                        String t = lines.get(l);
                        lines.set(l, shift ? t.substring(Math.min(4, t.length() - t.stripLeading().length())) : "    " + t);
                    }
                } else if (!shift) insert(" ".repeat(4 - col % 4));
                changed();
                return true;
            }
            default -> { }
        }
        if (ctrl) {
            var kb = Minecraft.getInstance().keyboardHandler;
            switch (key) {
                case GLFW.GLFW_KEY_A -> {
                    anchorLine = 0;
                    anchorCol = 0;
                    line = lines.size() - 1;
                    col = lines.get(line).length();
                    return true;
                }
                case GLFW.GLFW_KEY_C -> {
                    if (hasSel()) kb.setClipboard(selected());
                    return true;
                }
                case GLFW.GLFW_KEY_X -> {
                    if (hasSel()) {
                        pushUndo();
                        kb.setClipboard(selected());
                        deleteSelection();
                        changed();
                    }
                    return true;
                }
                case GLFW.GLFW_KEY_V -> {
                    pushUndo();
                    insert(kb.getClipboard());
                    changed();
                    return true;
                }
                case GLFW.GLFW_KEY_Z -> {
                    if (shift) restore(redo, undo);
                    else restore(undo, redo);
                    return true;
                }
                case GLFW.GLFW_KEY_Y -> {
                    restore(redo, undo);
                    return true;
                }
                default -> { }
            }
        }
        return false;
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (!isFocused() || c < ' ' || c == 127) return false;
        pushUndo();
        // a closing brace typed on a line of only indentation goes back a level
        String cur = lines.get(line);
        if (c == '}' && cur.substring(0, col).isBlank() && col >= 4 && !hasSel()) {
            lines.set(line, cur.substring(4));
            col -= 4;
        }
        insert(String.valueOf(c));
        changed();
        return true;
    }

    // ------------------------------------------------------------------ mouse

    private int xOf(int l, int c) {
        String t = lines.get(Math.max(0, Math.min(lines.size() - 1, l)));
        return font.width(t.substring(0, Math.min(c, t.length())));
    }

    private int colAt(int l, int px) {
        if (l < 0 || l >= lines.size()) return col;
        String t = lines.get(l);
        for (int c = 0; c < t.length(); c++) {
            int mid = font.width(t.substring(0, c)) + font.width(t.substring(c, c + 1)) / 2;
            if (px < mid) return c;
        }
        return t.length();
    }

    private int[] cellAt(double mx, double my) {
        int l = Math.max(0, Math.min(lines.size() - 1, top + (int) Math.floor((my - getY() - PAD + 1) / LINE_H)));
        int px = (int) mx - (getX() + GUTTER + PAD) + left;
        return new int[]{l, colAt(l, px)};
    }

    private long lastClick;

    @Override
    public void onClick(double mx, double my) {
        int[] c = cellAt(mx, my);
        long now = System.currentTimeMillis();
        boolean dbl = now - lastClick < 300 && c[0] == line && Math.abs(c[1] - col) <= 1;
        lastClick = now;
        if (Screen.hasShiftDown()) {
            move(c[0], c[1], true);
            return;
        }
        anchorLine = c[0];
        anchorCol = c[1];
        line = c[0];
        col = c[1];
        if (dbl) {
            // a double click selects the word
            String t = lines.get(line);
            int a = col, b = col;
            while (a > 0 && word(t.charAt(a - 1))) a--;
            while (b < t.length() && word(t.charAt(b))) b++;
            anchorCol = a;
            col = b;
        }
        blink = now;
    }

    @Override
    protected void onDrag(double mx, double my, double dx, double dy) {
        int[] c = cellAt(mx, my);
        line = c[0];
        col = c[1];
        reveal();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (!isMouseOver(mx, my)) return false;
        top = Math.max(0, Math.min(Math.max(0, lines.size() - rows()), top - (int) Math.signum(sy) * 3));
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        out.add(NarratedElementType.TITLE, Component.literal("Chip design editor, line " + (line + 1)));
    }
}
