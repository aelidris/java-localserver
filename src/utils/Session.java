import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Session {
    private static final Map<String, Map<String, Object>> sessions = new ConcurrentHashMap<>();

    // Create a new session and return Session ID (UUID)
    public static String createSession() {
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, new ConcurrentHashMap<>());
        return sessionId;
    }

    // Get session data map by ID
    public static Map<String, Object> getSession(String sessionId) {
        if (sessionId == null) return null;
        return sessions.get(sessionId);
    }

    // Check if session exists
    public static boolean isValid(String sessionId) {
        return sessionId != null && sessions.containsKey(sessionId);
    }

    // Destroy session
    public static void destroySession(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }
}