import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

public class UrlPath {
    private static final String HEX = "0123456789ABCDEF";

    // Decode once; unlike form data, a '+' in a URL path stays a '+'.
    public static String decode(String path) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c > 127) throw new IllegalArgumentException("URL path must be percent-encoded");
            if (c == '%') {
                if (i + 2 >= path.length()) throw new IllegalArgumentException("Incomplete URL escape");
                int high = HEX.indexOf(Character.toUpperCase(path.charAt(++i)));
                int low = HEX.indexOf(Character.toUpperCase(path.charAt(++i)));
                if (high < 0 || low < 0) throw new IllegalArgumentException("Invalid URL escape");
                bytes.write(high * 16 + low);
            } else {
                bytes.write(c);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid UTF-8 in URL path");
        }
    }

    public static String encode(String path) {
        StringBuilder result = new StringBuilder();
        for (byte value : path.getBytes(StandardCharsets.UTF_8)) {
            int c = value & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "-._~/".indexOf(c) >= 0) {
                result.append((char) c);
            } else {
                result.append('%').append(HEX.charAt(c >> 4)).append(HEX.charAt(c & 15));
            }
        }
        return result.toString();
    }
}
