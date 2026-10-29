import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

// Interpret CGI headers without converting its binary body into text.
public class CGIResponse {
    private static final Set<String> CONNECTION_HEADERS = Set.of(
            "connection", "transfer-encoding", "keep-alive", "te", "trailer", "upgrade",
            "proxy-connection", "proxy-authenticate", "proxy-authorization");

    public static HttpResponseWriter parse(byte[] output) throws IOException {
        int end = -1;
        int bodyStart = -1;
        for (int i = 0; i < Math.min(output.length - 1, 16384); i++) {
            if (output[i] == '\n' && output[i + 1] == '\n') {
                end = i;
                bodyStart = i + 2;
                break;
            }
            if (i + 3 < output.length && output[i] == '\r' && output[i + 1] == '\n'
                    && output[i + 2] == '\r' && output[i + 3] == '\n') {
                end = i;
                bodyStart = i + 4;
                break;
            }
        }
        if (end < 0 || bodyStart > 16384) throw new IOException("Invalid CGI header section");
        String headerText = new String(output, 0, end, StandardCharsets.ISO_8859_1);
        StringBuilder headers = new StringBuilder();
        int status = 200;
        boolean hasStatus = false, hasType = false, hasLocation = false;
        String reason = "OK";
        for (String line : headerText.replace("\r\n", "\n").split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0 || !line.substring(0, colon).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                    || line.chars().anyMatch(c -> (c < 32 && c != '\t') || c == 127)) {
                throw new IOException("Invalid CGI header");
            }
            String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (CONNECTION_HEADERS.contains(name)) throw new IOException("CGI cannot set connection headers");
            if (name.equals("status")) {
                String[] parts = value.split("[ \\t]+", 2);
                if (hasStatus || !parts[0].matches("[2-5][0-9]{2}")) throw new IOException("Invalid CGI status");
                hasStatus = true;
                status = Integer.parseInt(parts[0]);
                reason = parts.length == 2 ? parts[1] : "CGI Response";
            } else if (!name.equals("content-length")) {
                if (name.equals("content-type")) {
                    if (hasType || value.isEmpty()) throw new IOException("Invalid CGI content type");
                    hasType = true;
                }
                if (name.equals("location")) {
                    if (hasLocation || value.isEmpty()) throw new IOException("Invalid CGI location");
                    hasLocation = true;
                }
                headers.append(line).append("\r\n");
            }
        }
        if (hasLocation && !hasStatus) { status = 302; reason = "Found"; }
        boolean noBody = status == 204 || status == 304;
        byte[] body = Arrays.copyOfRange(output, bodyStart, output.length);
        if (noBody && body.length != 0) throw new IOException("CGI status forbids a response body");
        if (!hasType && !hasLocation && !noBody) throw new IOException("CGI response needs Content-Type");
        if (!noBody) headers.append("Content-Length: ").append(body.length).append("\r\n");
        String response = "HTTP/1.1 " + status + " " + reason + "\r\n" + headers + "Connection: close\r\n\r\n";
        return new HttpResponseWriter(response.getBytes(StandardCharsets.ISO_8859_1), body);
    }
}
