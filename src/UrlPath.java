import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

/** URI path encoding, without form-encoding's special treatment of '+'. */
final class UrlPath {
    static String decode(String raw) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%') {
                if (i + 2 >= raw.length()) {
                    throw new IllegalArgumentException("Incomplete percent escape");
                }
                int high = hex(raw.charAt(++i));
                int low = hex(raw.charAt(++i));
                if (high < 0 || low < 0) {
                    throw new IllegalArgumentException("Invalid percent escape");
                }
                bytes.write(high * 16 + low);
            } else {
                if (c > 127) {
                    throw new IllegalArgumentException("Non-ASCII URI characters must be percent-encoded");
                }
                bytes.write(c);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid UTF-8 path", e);
        }
    }

    static String encode(String path) {
        StringBuilder result = new StringBuilder();
        for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '/' || c == '-' || c == '.' || c == '_' || c == '~') {
                result.append((char) c);
            } else {
                result.append('%').append("0123456789ABCDEF".charAt(c >> 4))
                        .append("0123456789ABCDEF".charAt(c & 15));
            }
        }
        return result.toString();
    }

    private static int hex(char c) {
        return "0123456789abcdef".indexOf(Character.toLowerCase(c));
    }
}
