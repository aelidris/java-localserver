import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Produces an escaped, bounded HTML index of ordinary files and directories. */
final class DirectoryListing {
    private static final int MAX_ENTRIES = 10000;
    private static final int MAX_HTML_BYTES = 2 * 1024 * 1024;

    private static final class Entry {
        final String name;
        final boolean directory;

        Entry(String name, boolean directory) {
            this.name = name;
            this.directory = directory;
        }
    }

    static byte[] render(Path directory, String urlPath) throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(directory)) {
            int count = 0;
            for (Path child : stream) {
                if (++count > MAX_ENTRIES) {
                    throw new IOException("Directory listing exceeds entry limit");
                }
                boolean isDirectory = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS);
                String name = child.getFileName().toString();
                if ((isDirectory || Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS))
                        && !name.contains("\\") && name.chars().noneMatch(Character::isISOControl)) {
                    entries.add(new Entry(name, isDirectory));
                }
            }
        } catch (DirectoryIteratorException e) {
            throw e.getCause();
        }
        entries.sort(Comparator.comparing((Entry entry) -> !entry.directory).thenComparing(entry -> entry.name));
        String base = urlPath.endsWith("/") ? urlPath : urlPath + "/";
        String title = "Index of " + escape(base);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        append(output, "<!doctype html><html lang=\"en\"><head><meta charset=\"UTF-8\"><title>" + title
                + "</title></head><body><h1>" + title + "</h1>");
        if (entries.isEmpty()) {
            append(output, "<p>This directory is empty.</p>");
        } else {
            append(output, "<ul>");
            for (Entry entry : entries) {
                String name = entry.name + (entry.directory ? "/" : "");
                append(output, "<li><a href=\"" + escape(UrlPath.encode(base + name)) + "\">"
                        + escape(name) + "</a></li>");
            }
            append(output, "</ul>");
        }
        append(output, "</body></html>");
        return output.toByteArray();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static void append(ByteArrayOutputStream output, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_HTML_BYTES - output.size()) {
            throw new IOException("Directory listing exceeds response size limit");
        }
        output.write(bytes, 0, bytes.length);
    }
}
