public class SessionTest {
    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Session check failed");
    }

    public static void main(String[] args) {
        Object site = new Object();
        String id = Session.resolve(null, site);
        check(Session.isValid(id));
        check(Session.getSession(id).get("visits").equals(1));
        check(Session.resolve("SESSION_ID=" + id, site).equals(id));
        check(Session.getSession(id).get("visits").equals(2));
        check(!Session.resolve("SESSION_ID=" + id, new Object()).equals(id));
        check(!Session.resolve("SESSION_ID=forged", site).equals("forged"));
        Session.destroySession(id);
        check(!Session.isValid(id));
        String oldest = Session.createSession();
        String newest = oldest;
        for (int i = 0; i < 10000; i++) newest = Session.createSession();
        check(!Session.isValid(oldest));
        check(Session.isValid(newest));
        Session.expire();
        check(Session.isValid(newest));
        System.out.println("PASS: session persistence, isolation, deletion and bounded eviction");
    }
}
