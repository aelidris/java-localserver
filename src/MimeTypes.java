import java.util.Locale;

public class MimeTypes {
    public static String forPath(String path) {
        int dot = path.lastIndexOf('.');
        return switch (dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "html", "htm" -> "text/html; charset=UTF-8";
            case "css" -> "text/css; charset=UTF-8";
            case "js", "mjs" -> "text/javascript; charset=UTF-8";
            case "json" -> "application/json";
            case "txt" -> "text/plain; charset=UTF-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "ico" -> "image/x-icon";
            case "pdf" -> "application/pdf";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            default -> "application/octet-stream";
        };
    }
}
