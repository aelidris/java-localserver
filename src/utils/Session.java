import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

// Only the server event loop accesses this bounded, expiring store.
public class Session {
    public static final long MAX_AGE_SECONDS = 1800;
    private static final int MAX_SESSIONS = 10000;
    private static final Map<String, Entry> sessions = new LinkedHashMap<>(16, 0.75f, true);

    private static class Entry {
        final Map<String, Object> data = new HashMap<>();
        long expires = System.nanoTime() + MAX_AGE_SECONDS * 1_000_000_000L;
    }

    public static String createSession() {
        if (sessions.size() >= MAX_SESSIONS) sessions.remove(sessions.keySet().iterator().next());
        String id = UUID.randomUUID().toString();
        sessions.put(id, new Entry());
        return id;
    }

    public static Map<String, Object> getSession(String id) {
        Entry entry = sessions.get(id);
        if (entry == null) return null;
        if (System.nanoTime() >= entry.expires) {
            sessions.remove(id);
            return null;
        }
        entry.expires = System.nanoTime() + MAX_AGE_SECONDS * 1_000_000_000L;
        return entry.data;
    }

    public static boolean isValid(String id) { return getSession(id) != null; }
    public static void destroySession(String id) { sessions.remove(id); }

    public static void expire() {
        long now = System.nanoTime();
        var entries = sessions.values().iterator();
        while (entries.hasNext()) {
            if (entries.next().expires > now) break;
            entries.remove();
        }
    }

    public static String resolve(String cookieHeader, Object server) {
        String id = Cookie.parseCookies(cookieHeader).get("SESSION_ID");
        Map<String, Object> data = getSession(id);
        if (data == null || data.get("server") != server) {
            id = createSession();
            data = getSession(id);
            data.put("server", server);
        }
        data.put("visits", (int) data.getOrDefault("visits", 0) + 1);
        return id;
    }
}
