import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class error {
    static {
        System.out.println("Error handling class initialized.");
    }
    private final ConfigLoader configLoader;

    public error(ConfigLoader configLoader) {
        this.configLoader = configLoader;
    }

    public HttpResponseWriter createResponse(int statusCode) {
        String statusMessage = getStatusMessage(statusCode);
        String errorPagePath = configLoader.getErrorPagePath(statusCode);

        if (errorPagePath != null) {
            try {
                Path filePath = Paths.get(errorPagePath);
                if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                    byte[] fileBytes = Files.readAllBytes(filePath);
                    return new HttpResponseWriter(statusCode, statusMessage,
                            "text/html; charset=UTF-8", fileBytes);
                }
            } catch (IOException e) {
                System.out.println("⚠️ Could not read error page file: " + e.getMessage());
            }
        }

        // Fallback default HTML response if the error page file is missing or unreadable.
        String htmlContent = "<html><body><h1>" + statusCode + " " + statusMessage + "</h1></body></html>";
        return new HttpResponseWriter(statusCode, statusMessage,
                "text/html; charset=UTF-8", htmlContent.getBytes(StandardCharsets.UTF_8));
    }

    private String getStatusMessage(int code) {
        switch (code) {
            case 400:
                return "Bad Request";
            case 403:
                return "Forbidden";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 413:
                return "Payload Too Large";
            case 500:
                return "Internal Server Error";
            default:
                return "Internal Error";
        }
    }
}
