package dev.emergence.core.digital;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The assembler for the EM-8 processor (the shipped design {@code em8}): a program's text into the bytes of its ROM.
 * <pre>
 * .asm em8                 ; marks a ROM's text as a program (its first line)
 * SET = 150                ; a constant
 * start:  LDI 0            ; a label, an instruction and its operand
 *         OUT
 *         JMP start
 * </pre>
 * Every instruction is two bytes, opcode then operand (0 for those without one). Operands: numbers (42, 0x2A, 0b101010,
 * 'c'), constants, labels, and sums and differences of them ({@code table+2}). {@code .org n} moves on to address n;
 * {@code .byte n, n, ...} places bytes. Comments run from ';' or "//" to the end of the line. Mnemonics and names are
 * not case-sensitive.
 */
public final class Em8Assembler {
    private Em8Assembler() {
    }

    /** The instruction set: mnemonic, opcode, whether it takes an operand, what it does. */
    public record Op(String name, int code, boolean operand, String help) {
    }

    public static final List<Op> OPS = List.of(
            new Op("NOP", 0x00, false, "do nothing"),
            new Op("LDI", 0x01, true, "A = n"),
            new Op("LD", 0x02, true, "A = mem[r]"),
            new Op("ST", 0x03, true, "mem[r] = A"),
            new Op("ADD", 0x04, true, "A += mem[r] (C: carry)"),
            new Op("ADDI", 0x05, true, "A += n"),
            new Op("SUB", 0x06, true, "A -= mem[r] (C: borrow)"),
            new Op("SUBI", 0x07, true, "A -= n"),
            new Op("AND", 0x08, true, "A &= mem[r]"),
            new Op("ANDI", 0x09, true, "A &= n"),
            new Op("OR", 0x0A, true, "A |= mem[r]"),
            new Op("ORI", 0x0B, true, "A |= n"),
            new Op("XOR", 0x0C, true, "A ^= mem[r]"),
            new Op("XORI", 0x0D, true, "A ^= n"),
            new Op("SHL", 0x0E, false, "A <<= 1 (C: bit 7)"),
            new Op("SHR", 0x0F, false, "A >>= 1 (C: bit 0)"),
            new Op("JMP", 0x10, true, "go to a"),
            new Op("JZ", 0x11, true, "go to a if Z"),
            new Op("JNZ", 0x12, true, "go to a if not Z"),
            new Op("JC", 0x13, true, "go to a if C"),
            new Op("JNC", 0x14, true, "go to a if not C"),
            new Op("IN", 0x15, false, "A = input port"),
            new Op("OUT", 0x16, false, "output port = A"),
            new Op("OUTI", 0x17, true, "output port = n"),
            new Op("CMP", 0x18, true, "flags of A - mem[r]"),
            new Op("CMPI", 0x19, true, "flags of A - n (C: A < n)"),
            new Op("INC", 0x1A, true, "mem[r] += 1"),
            new Op("DEC", 0x1B, true, "mem[r] -= 1"),
            new Op("CALL", 0x1C, true, "push the return address, go to a"),
            new Op("RET", 0x1D, false, "pop the return address, go back"),
            new Op("PUSH", 0x1E, false, "push A"),
            new Op("POP", 0x1F, false, "pop A"),
            new Op("LDN", 0x20, true, "A = mem[mem[r]]"),
            new Op("STN", 0x21, true, "mem[mem[r]] = A"),
            new Op("ADC", 0x22, true, "A += mem[r] + C"),
            new Op("ADCI", 0x23, true, "A += n + C"),
            new Op("SBC", 0x24, true, "A -= mem[r] + C"),
            new Op("SBCI", 0x25, true, "A -= n + C"),
            new Op("HLT", 0xFF, false, "stop"));

    private static final Map<String, Op> BY_NAME = new HashMap<>();

    static {
        for (Op o : OPS) BY_NAME.put(o.name, o);
    }

    /** An assembly error: its line (1-based) and what is wrong. */
    public static final class AsmError extends Exception {
        public final int line;

        AsmError(int line, String msg) {
            super("line " + line + ": " + msg);
            this.line = line;
        }
    }

    /** The program assembled: the ROM's 256 bytes, each label's address, and each instruction's line by address. */
    public record Program(int[] bytes, Map<String, Integer> labels, Map<Integer, Integer> lineAt) {
    }

    /** Whether a ROM's text is a program (a ".asm" line before anything else), not hex. */
    public static boolean isProgram(String text) {
        if (text == null) return false;
        for (String raw : text.split("\n")) {
            String l = strip(raw).trim();
            if (l.isEmpty()) continue;
            return l.toLowerCase(Locale.ROOT).startsWith(".asm");
        }
        return false;
    }

    private static String strip(String line) {
        int c = line.indexOf(';');
        if (c >= 0) line = line.substring(0, c);
        c = line.indexOf("//");
        if (c >= 0) line = line.substring(0, c);
        return line;
    }

    public static Program assemble(String text) throws AsmError {
        String[] lines = text.replace("\r", "").split("\n", -1);
        Map<String, Integer> consts = new LinkedHashMap<>(), labels = new LinkedHashMap<>();
        // pass 1: addresses of labels, values of constants (in order: a constant may use those before it)
        int addr = 0;
        for (int n = 0; n < lines.length; n++) {
            String l = strip(lines[n]).trim();
            if (l.isEmpty()) continue;
            int colon = labelEnd(l);
            if (colon > 0) {
                String name = l.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                checkName(n + 1, name);
                if (labels.containsKey(name) || consts.containsKey(name)) throw new AsmError(n + 1, "\"" + name + "\" is defined twice");
                labels.put(name, addr);
                l = l.substring(colon + 1).trim();
                if (l.isEmpty()) continue;
            }
            int eq = l.indexOf('=');
            if (eq > 0 && l.substring(0, eq).trim().matches("[A-Za-z_][A-Za-z0-9_]*")) {
                String name = l.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                checkName(n + 1, name);
                if (labels.containsKey(name) || consts.containsKey(name)) throw new AsmError(n + 1, "\"" + name + "\" is defined twice");
                consts.put(name, null); // its value in pass 2 (it may use labels defined later)
                continue;
            }
            String[] w = l.split("\\s+", 2);
            String m = w[0].toLowerCase(Locale.ROOT);
            if (m.equals(".asm")) continue;
            if (m.equals(".org")) {
                addr = value(n + 1, w.length > 1 ? w[1] : "", consts, labels, true);
                continue;
            }
            if (m.equals(".byte")) {
                addr += w.length > 1 ? w[1].split(",").length : 0;
                continue;
            }
            addr += 2;
        }
        // pass 2: the bytes
        int[] bytes = new int[256];
        boolean[] used = new boolean[256];
        Map<Integer, Integer> lineAt = new HashMap<>();
        addr = 0;
        for (int n = 0; n < lines.length; n++) {
            String l = strip(lines[n]).trim();
            if (l.isEmpty()) continue;
            int colon = labelEnd(l);
            if (colon > 0) {
                l = l.substring(colon + 1).trim();
                if (l.isEmpty()) continue;
            }
            int eq = l.indexOf('=');
            if (eq > 0 && l.substring(0, eq).trim().matches("[A-Za-z_][A-Za-z0-9_]*")) {
                String name = l.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                consts.put(name, value(n + 1, l.substring(eq + 1), consts, labels, false));
                continue;
            }
            String[] w = l.split("\\s+", 2);
            String m = w[0].toLowerCase(Locale.ROOT), rest = w.length > 1 ? w[1].trim() : "";
            if (m.equals(".asm")) {
                if (!rest.isEmpty() && !rest.equalsIgnoreCase("em8")) throw new AsmError(n + 1, "this assembler is for the em8, not " + rest);
                continue;
            }
            if (m.equals(".org")) {
                addr = value(n + 1, rest, consts, labels, false);
                if (addr < 0 || addr > 255) throw new AsmError(n + 1, ".org " + addr + " is outside the ROM (0-255)");
                continue;
            }
            if (m.equals(".byte")) {
                for (String b : rest.split(",")) put(n + 1, bytes, used, addr++, value(n + 1, b, consts, labels, false));
                continue;
            }
            Op op = BY_NAME.get(m.toUpperCase(Locale.ROOT));
            if (op == null) throw new AsmError(n + 1, "\"" + w[0] + "\" is not an instruction (see the table)");
            int operand = 0;
            if (op.operand) {
                if (rest.isEmpty()) throw new AsmError(n + 1, op.name + " needs an operand");
                operand = value(n + 1, rest, consts, labels, false);
                if (operand < -128 || operand > 255) throw new AsmError(n + 1, "operand " + operand + " does not fit a byte");
            } else if (!rest.isEmpty()) {
                throw new AsmError(n + 1, op.name + " takes no operand");
            }
            lineAt.put(addr, n + 1);
            put(n + 1, bytes, used, addr++, op.code);
            put(n + 1, bytes, used, addr++, operand & 0xFF);
        }
        return new Program(bytes, labels, lineAt);
    }

    private static int labelEnd(String l) {
        int c = l.indexOf(':');
        if (c <= 0) return -1;
        return l.substring(0, c).trim().matches("[A-Za-z_][A-Za-z0-9_]*") ? c : -1;
    }

    private static void checkName(int line, String name) throws AsmError {
        if (!name.matches("[a-z_][a-z0-9_]*")) throw new AsmError(line, "\"" + name + "\" is not a name");
        if (BY_NAME.containsKey(name.toUpperCase(Locale.ROOT))) throw new AsmError(line, "\"" + name + "\" is an instruction's name");
    }

    private static void put(int line, int[] bytes, boolean[] used, int addr, int b) throws AsmError {
        if (addr < 0 || addr > 255) throw new AsmError(line, "the program runs past the ROM's end (256 bytes)");
        if (used[addr]) throw new AsmError(line, String.format("address %02X is used twice", addr));
        used[addr] = true;
        bytes[addr] = b & 0xFF;
    }

    /** An operand: terms added and subtracted. Labels not yet placed count 0 in pass 1 (lenient). */
    private static int value(int line, String expr, Map<String, Integer> consts, Map<String, Integer> labels, boolean lenient) throws AsmError {
        String s = expr.trim();
        if (s.isEmpty()) throw new AsmError(line, "a value is missing");
        int total = 0, sign = 1, i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ') {
                i++;
                continue;
            }
            if (c == '+' || c == '-') {
                sign = c == '-' ? -sign : sign;
                i++;
                continue;
            }
            int j = i;
            if (c == '\'') {
                if (i + 2 >= s.length() || s.charAt(i + 2) != '\'') throw new AsmError(line, "a character is written 'c'");
                total += sign * s.charAt(i + 1);
                i += 3;
                sign = 1;
                continue;
            }
            while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
            if (j == i) throw new AsmError(line, "\"" + s + "\" is not a value");
            String t = s.substring(i, j).toLowerCase(Locale.ROOT);
            int v;
            try {
                if (t.startsWith("0x")) v = Integer.parseInt(t.substring(2), 16);
                else if (t.startsWith("0b")) v = Integer.parseInt(t.substring(2), 2);
                else if (Character.isDigit(t.charAt(0))) v = Integer.parseInt(t);
                else if (consts.containsKey(t)) {
                    Integer cv = consts.get(t);
                    if (cv == null) {
                        if (lenient) cv = 0;
                        else throw new AsmError(line, "\"" + t + "\" is used before it is given a value");
                    }
                    v = cv;
                } else if (labels.containsKey(t)) v = labels.get(t);
                else if (lenient) v = 0;
                else throw new AsmError(line, "\"" + t + "\" is not defined");
            } catch (NumberFormatException e) {
                throw new AsmError(line, "\"" + t + "\" is not a number");
            }
            total += sign * v;
            sign = 1;
            i = j;
        }
        return total;
    }

    /** A disassembly line for the instruction at {@code addr}: "LDI 12". */
    public static String disassemble(int[] bytes, int addr) {
        int code = bytes[addr & 255], arg = bytes[(addr + 1) & 255];
        for (Op o : OPS) if (o.code == code) return o.operand ? o.name + " " + arg : o.name;
        return String.format(".byte %02X", code);
    }
}
