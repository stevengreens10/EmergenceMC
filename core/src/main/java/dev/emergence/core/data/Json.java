package dev.emergence.core.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, strict JSON reader/writer so the simulation core has no third-party dependencies.
 * Objects become {@link LinkedHashMap}, arrays {@link ArrayList}, numbers {@link Double},
 * plus {@link String}, {@link Boolean} and {@code null}.
 */
public final class Json {
    private final String s;
    private int p;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        Object v = j.value();
        j.ws();
        if (j.p != j.s.length()) throw j.error("Trailing content");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object o = parse(text);
        if (!(o instanceof Map)) throw new IllegalArgumentException("JSON root is not an object");
        return (Map<String, Object>) o;
    }

    private Object value() {
        if (p >= s.length()) throw error("Unexpected end");
        char c = s.charAt(p);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        p++;
        ws();
        if (peek() == '}') {
            p++;
            return m;
        }
        while (true) {
            ws();
            String k = string();
            ws();
            expect(':');
            ws();
            m.put(k, value());
            ws();
            char c = s.charAt(p++);
            if (c == '}') return m;
            if (c != ',') throw error("Expected , or }");
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        p++;
        ws();
        if (peek() == ']') {
            p++;
            return l;
        }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = s.charAt(p++);
            if (c == ']') return l;
            if (c != ',') throw error("Expected , or ]");
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(p++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                char e = s.charAt(p++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(s.substring(p, p + 4), 16));
                        p += 4;
                    }
                    default -> sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Double number() {
        int start = p;
        while (p < s.length() && "+-0123456789.eE".indexOf(s.charAt(p)) >= 0) p++;
        if (start == p) throw error("Unexpected character '" + s.charAt(p) + "'");
        return Double.parseDouble(s.substring(start, p));
    }

    private Object literal(String word, Object v) {
        if (!s.startsWith(word, p)) throw error("Expected " + word);
        p += word.length();
        return v;
    }

    private void expect(char c) {
        if (p >= s.length() || s.charAt(p) != c) throw error("Expected '" + c + "'");
        p++;
    }

    private char peek() {
        return p < s.length() ? s.charAt(p) : 0;
    }

    private void ws() {
        while (p < s.length()) {
            char c = s.charAt(p);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                p++;
            } else if (c == '/' && p + 1 < s.length() && s.charAt(p + 1) == '/') {
                // tolerate // comments in hand-written data files
                while (p < s.length() && s.charAt(p) != '\n') p++;
            } else {
                break;
            }
        }
    }

    private IllegalArgumentException error(String msg) {
        int line = 1;
        for (int i = 0; i < Math.min(p, s.length()); i++) if (s.charAt(i) == '\n') line++;
        return new IllegalArgumentException(msg + " at line " + line);
    }

    // ---------------------------------------------------------------- writing

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        write(o, sb);
        return sb.toString();
    }

    private static void write(Object o, StringBuilder sb) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String str) {
            sb.append('"');
            for (char c : str.toCharArray()) {
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
            sb.append('"');
        } else if (o instanceof Number || o instanceof Boolean) {
            sb.append(o);
        } else if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                write(String.valueOf(e.getKey()), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (o instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object x : it) {
                if (!first) sb.append(',');
                first = false;
                write(x, sb);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("Cannot serialize " + o.getClass());
        }
    }
}
