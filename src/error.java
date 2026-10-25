import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
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

    public void sendError(SocketChannel client, SelectionKey key, int statusCode) {
        String statusMessage = getStatusMessage(statusCode);
        String errorPagePath = configLoader.getErrorPagePath(statusCode);

        boolean fileSent = false;

        if (errorPagePath != null) {
            try {
                Path filePath = Paths.get(errorPagePath);
                if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                    byte[] fileBytes = Files.readAllBytes(filePath);
                    String headers = "HTTP/1.1 " + statusCode + " " + statusMessage + "\r\n" +
                            "Content-Type: text/html; charset=UTF-8\r\n" +
                            "Content-Length: " + fileBytes.length + "\r\n\r\n";

                    ByteBuffer buffer = ByteBuffer
                            .allocate(headers.getBytes(StandardCharsets.UTF_8).length + fileBytes.length);
                    buffer.put(headers.getBytes(StandardCharsets.UTF_8));
                    buffer.put(fileBytes);
                    buffer.flip();

                    client.write(buffer);
                    fileSent = true;
                }
            } catch (IOException e) {
                System.out.println("⚠️ Could not read error page file: " + e.getMessage());
            }
        }

        if (!fileSent) {
            // Fallback default HTML response if error page file is missing
            String htmlContent = "<html><body><h1>" + statusCode + " " + statusMessage + "</h1></body></html>";
            String response = "HTTP/1.1 " + statusCode + " " + statusMessage + "\r\n" +
                    "Content-Type: text/html; charset=UTF-8\r\n" +
                    "Content-Length: " + htmlContent.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" +
                    htmlContent;
            try {
                client.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));
            } catch (IOException e) {
                System.out.println("⚠️ Could not send default error response: " + e.getMessage());
            }
        }
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