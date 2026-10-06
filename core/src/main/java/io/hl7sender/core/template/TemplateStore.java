package io.hl7sender.core.template;

import io.hl7sender.core.hl7.Hl7Text;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * User templates saved as {@code <name>.hl7} files (one segment per line) in a directory, so they can
 * be edited with any text editor, backed up or shared.
 */
public final class TemplateStore {

    /**
     * A saved template.
     *
     * @param name file name without extension
     * @param text message text, one segment per line
     */
    public record Template(String name, String text) {

        @Override
        public String toString() {
            return name;
        }
    }

    private final Path dir;

    public TemplateStore(Path dir) {
        this.dir = dir;
    }

    public Path dir() {
        return dir;
    }

    public List<Template> list() throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Template> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> String.valueOf(f.getFileName()).toLowerCase(Locale.ROOT).endsWith(".hl7"))
                    .sorted().toList()) {
                String file = String.valueOf(p.getFileName());
                out.add(new Template(file.substring(0, file.length() - 4),
                        Hl7Text.toDisplay(Files.readString(p, StandardCharsets.UTF_8))));
            }
        }
        return out;
    }

    /** Saves (or replaces) a template and returns it with its stored name. */
    public Template save(String name, String text) throws IOException {
        String safe = safeName(name);
        Files.createDirectories(dir);
        String display = Hl7Text.toDisplay(text);
        Files.writeString(dir.resolve(safe + ".hl7"), display + "\n", StandardCharsets.UTF_8);
        return new Template(safe, display);
    }

    public boolean delete(String name) throws IOException {
        return Files.deleteIfExists(dir.resolve(safeName(name) + ".hl7"));
    }

    /** Makes a name safe as a file name on every OS. */
    static String safeName(String name) {
        String s = name == null ? "" : name.trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_");
        s = s.replaceAll("^\\.+", "");
        if (s.isBlank()) {
            throw new IllegalArgumentException("Template name is required");
        }
        return s.length() > 80 ? s.substring(0, 80) : s;
    }
}
