import java.util.HashMap;
import java.util.Map;

public class Cookie {
    
    // Parse Cookie header string (e.g. "session_id=12345; user=Ahmed") into a Map
    public static Map<String, String> parseCookies(String cookieHeader) {
        Map<String, String> cookies = new HashMap<>();      
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            return cookies;
        }
        String[] pairs = cookieHeader.split(";");
        for (String pair : pairs) {
            String[] parts = pair.trim().split("=", 2);
            if (parts.length == 2) {
                cookies.put(parts[0].trim(), parts[1].trim());
            }
        }
        return cookies;
    }

    // Build Set-Cookie header string
    public static String buildCookieHeader(String name, String value, long maxAgeSeconds) {
        return "Set-Cookie: " + name + "=" + value + "; Path=/; HttpOnly; Max-Age=" + maxAgeSeconds + "\r\n";
    }
}