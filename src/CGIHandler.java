import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class CGIHandler {

    public static String executeCGI(String scriptPath, String method, String queryString, String requestBody, Map<String, String> envVariables) {
        try {
            File scriptFile = new File(scriptPath);
            if (!scriptFile.exists()) {
                return "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\n\r\nCGI Script Not Found";
            }

            ProcessBuilder pb;
            String extension = scriptPath.substring(scriptPath.lastIndexOf(".") + 1);

            // Determine interpreter based on extension
            if (extension.equals("py")) {
                pb = new ProcessBuilder("python3", scriptFile.getAbsolutePath());
            } else if (extension.equals("pl")) {
                pb = new ProcessBuilder("perl", scriptFile.getAbsolutePath());
            } else {
                pb = new ProcessBuilder(scriptFile.getAbsolutePath());
            }

            // Set Environment Variables per CGI specs
            Map<String, String> env = pb.environment();
            env.put("REQUEST_METHOD", method);
            env.put("QUERY_STRING", queryString != null ? queryString : "");
            env.put("CONTENT_LENGTH", String.valueOf(requestBody != null ? requestBody.getBytes(StandardCharsets.UTF_8).length : 0));
            env.put("PATH_INFO", scriptFile.getAbsolutePath());
            
            if (envVariables != null) {
                env.putAll(envVariables);
            }

            pb.directory(scriptFile.getParentFile());
            Process process = pb.start();

            // Write POST request body to CGI stdin if method is POST
            if (method.equals("POST") && requestBody != null && !requestBody.isEmpty()) {
                try (OutputStream stdin = process.getOutputStream()) {
                    stdin.write(requestBody.getBytes(StandardCharsets.UTF_8));
                    stdin.flush();
                }
            }

            // Read raw output (bytes) from CGI stdout to support both text and binary safely
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream stdout = process.getInputStream()) {
                byte[] chunk = new byte[4096];
                int bytesRead;
                while ((bytesRead = stdout.read(chunk)) != -1) {
                    buffer.write(chunk, 0, bytesRead);
                }
            }

            process.waitFor();

            // Return response as string (UTF-8)
            return buffer.toString(StandardCharsets.UTF_8.name());

        } catch (Exception e) {
            return "HTTP/1.1 500 Internal Server Error\r\nContent-Type: text/plain\r\n\r\nCGI Execution Error: " + e.getMessage();
        }
    }
}