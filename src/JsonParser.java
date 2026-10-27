import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small JSON parser for configuration files, using only Java Core. */
final class JsonParser {
    private static final Pattern NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");
    private final String text;
    private int position;

    private JsonParser(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        JsonParser parser = new JsonParser(text);
        Object result = parser.value(0);
        parser.whitespace();
        if (parser.position != text.length()) {
            throw parser.error("unexpected trailing content");
        }
        return result;
    }

    private Object value(int depth) {
        whitespace();
        if (depth > 32) {
            throw error("nesting exceeds 32 levels");
        }
        if (position == text.length()) {
            throw error("expected a value");
        }
        switch (text.charAt(position)) {
            case '{': return object(depth + 1);
            case '[': return array(depth + 1);
            case '"': return string();
            case 't': return literal("true", Boolean.TRUE);
            case 'f': return literal("false", Boolean.FALSE);
            case 'n': return literal("null", null);
            default:
                Matcher matcher = NUMBER.matcher(text).region(position, text.length());
                if (!matcher.lookingAt()) {
                    throw error("expected a JSON value");
                }
                String number = matcher.group();
                position = matcher.end();
                try {
                    return new BigDecimal(number);
                } catch (NumberFormatException e) {
                    throw error("number is out of range");
                }
        }
    }

    private Map<String, Object> object(int depth) {
        position++;
        Map<String, Object> result = new LinkedHashMap<>();
        if (take('}')) {
            return result;
        }
        do {
            whitespace();
            if (position == text.length() || text.charAt(position) != '"') {
                throw error("expected an object key");
            }
            String key = string();
            if (result.containsKey(key)) {
                throw error("duplicate key '" + key + "'");
            }
            require(':');
            result.put(key, value(depth));
        } while (take(','));
        require('}');
        return result;
    }

    private List<Object> array(int depth) {
        position++;
        List<Object> result = new ArrayList<>();
        if (take(']')) {
            return result;
        }
        do {
            result.add(value(depth));
        } while (take(','));
        require(']');
        return result;
    }

    private String string() {
        position++;
        StringBuilder result = new StringBuilder();
        while (position < text.length()) {
            char c = text.charAt(position++);
            if (c == '"') {
                return result.toString();
            }
            if (c < 32) {
                throw error("unescaped control character in string");
            }
            if (c != '\\') {
                result.append(c);
                continue;
            }
            if (position == text.length()) {
                throw error("unfinished string escape");
            }
            switch (text.charAt(position++)) {
                case '"': result.append('"'); break;
                case '\\': result.append('\\'); break;
                case '/': result.append('/'); break;
                case 'b': result.append('\b'); break;
                case 'f': result.append('\f'); break;
                case 'n': result.append('\n'); break;
                case 'r': result.append('\r'); break;
                case 't': result.append('\t'); break;
                case 'u':
                    int code = 0;
                    for (int i = 0; i < 4; i++) {
                        if (position == text.length()) {
                            throw error("unfinished unicode escape");
                        }
                        char digit = text.charAt(position++);
                        int hex = "0123456789abcdef".indexOf(Character.toLowerCase(digit));
                        if (hex < 0) {
                            throw error("invalid unicode escape");
                        }
                        code = code * 16 + hex;
                    }
                    result.append((char) code);
                    break;
                default: throw error("invalid string escape");
            }
        }
        throw error("unterminated string");
    }

    private Object literal(String expected, Object value) {
        if (!text.startsWith(expected, position)) {
            throw error("expected " + expected);
        }
        position += expected.length();
        return value;
    }

    private boolean take(char expected) {
        whitespace();
        if (position < text.length() && text.charAt(position) == expected) {
            position++;
            return true;
        }
        return false;
    }

    private void require(char expected) {
        if (!take(expected)) {
            throw error("expected '" + expected + "'");
        }
    }

    private void whitespace() {
        while (position < text.length() && " \t\r\n".indexOf(text.charAt(position)) >= 0) {
            position++;
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("Invalid JSON at character " + (position + 1) + ": " + message);
    }
}
