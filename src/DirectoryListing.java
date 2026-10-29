import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class DirectoryListing {
    private static final int MAX_ENTRIES = 10000;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private record Entry(String name, boolean directory) { }

    public static byte[] create(Path directory, String urlPath) throws IOException {
        List<Entry> entries = new ArrayList<>();
        int scanned = 0;
        try (var stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                if (++scanned > MAX_ENTRIES) throw new IOException("Directory has too many entries");
                String name = path.getFileName().toString();
                if (name.contains("\\") || name.chars().anyMatch(c -> c < 32 || c == 127)) continue;
                var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isDirectory() || attributes.isRegularFile()) {
                    entries.add(new Entry(name, attributes.isDirectory()));
                }
            }
        }
        entries.sort(Comparator.comparing(Entry::directory).reversed().thenComparing(Entry::name));

        String base = urlPath.endsWith("/") ? urlPath : urlPath + "/";
        ByteArrayOutputStream html = new ByteArrayOutputStream();
        append(html, "<!doctype html><html><head><meta charset=\"UTF-8\"><title>Index of "
                + escape(base) + "</title></head><body><h1>Index of " + escape(base) + "</h1><ul>\n");
        for (Entry entry : entries) {
            String label = entry.name() + (entry.directory() ? "/" : "");
            append(html, "<li><a href=\"" + UrlPath.encode(base + label) + "\">" + escape(label) + "</a></li>\n");
        }
        append(html, "</ul></body></html>\n");
        return html.toByteArray();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static void append(ByteArrayOutputStream output, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (output.size() + bytes.length > MAX_BYTES) throw new IOException("Directory listing exceeds 2 MiB");
        output.writeBytes(bytes);
    }
}
