import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Reads JSON syntax. ConfigLoader checks the meaning of its values.
public class JsonParser {
    private static final Pattern NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");
    private final String text;
    private int position;

    private JsonParser(String text) { this.text = text; }

    public static Object parse(String text) {
        JsonParser parser = new JsonParser(text);
        Object result = parser.value(0);
        parser.whitespace();
        if (parser.position != text.length()) throw parser.error("Unexpected trailing content");
        return result;
    }

    private Object value(int depth) {
        whitespace();
        if (depth > 32) throw error("JSON nesting is too deep");
        if (position == text.length()) throw error("Expected a value");
        return switch (text.charAt(position)) {
            case '{' -> object(depth + 1);
            case '[' -> array(depth + 1);
            case '"' -> string();
            case 't' -> literal("true", true);
            case 'f' -> literal("false", false);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object(int depth) {
        position++;
        Map<String, Object> result = new LinkedHashMap<>();
        if (take('}')) return result;
        while (true) {
            whitespace();
            if (position == text.length() || text.charAt(position) != '"') throw error("Expected an object key");
            String key = string();
            if (result.containsKey(key)) throw error("Duplicate key: " + key);
            expect(':');
            result.put(key, value(depth));
            if (take('}')) return result;
            expect(',');
        }
    }

    private List<Object> array(int depth) {
        position++;
        List<Object> result = new ArrayList<>();
        if (take(']')) return result;
        while (true) {
            result.add(value(depth));
            if (take(']')) return result;
            expect(',');
        }
    }

    private String string() {
        position++; // Opening quote.
        StringBuilder result = new StringBuilder();
        while (position < text.length()) {
            char c = text.charAt(position++);
            if (c == '"') return result.toString();
            if (c < 32) throw error("Control character in string");
            if (c == '\\') {
                if (position == text.length()) throw error("Incomplete escape");
                c = switch (text.charAt(position++)) {
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case '/' -> '/';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'u' -> unicode();
                    default -> throw error("Invalid escape");
                };
            }
            result.append(c);
        }
        throw error("Unterminated string");
    }

    private char unicode() {
        if (position + 4 > text.length()) throw error("Incomplete Unicode escape");
        int code = 0;
        for (int i = 0; i < 4; i++) {
            char c = text.charAt(position++);
            int digit = "0123456789abcdef".indexOf(Character.toLowerCase(c));
            if (digit < 0) throw error("Invalid Unicode escape");
            code = code * 16 + digit;
        }
        return (char) code;
    }

    private Object literal(String word, Object result) {
        if (!text.startsWith(word, position)) throw error("Invalid value");
        position += word.length();
        return result;
    }

    private BigDecimal number() {
        Matcher match = NUMBER.matcher(text).region(position, text.length());
        if (!match.lookingAt()) throw error("Invalid number or value");
        position = match.end();
        try {
            return new BigDecimal(match.group());
        } catch (NumberFormatException e) {
            throw error("Number is too large");
        }
    }

    private void whitespace() {
        while (position < text.length() && " \t\r\n".indexOf(text.charAt(position)) >= 0) position++;
    }

    private boolean take(char expected) {
        whitespace();
        if (position < text.length() && text.charAt(position) == expected) {
            position++;
            return true;
        }
        return false;
    }

    private void expect(char expected) {
        if (!take(expected)) throw error("Expected '" + expected + "'");
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at character " + position);
    }
}
