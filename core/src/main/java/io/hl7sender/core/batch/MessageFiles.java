package io.hl7sender.core.batch;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/** Finds and reads HL7 message files. */
public final class MessageFiles {

    /** File extensions treated as HL7 message files, compared case-insensitively. */
    public static final Set<String> EXTENSIONS = Set.of("hl7", "txt", "msg", "dat", "er7");

    private MessageFiles() {
    }

    public static boolean isMessageFile(Path file) {
        Path fileName = file.getFileName();
        String name = fileName == null ? "" : fileName.toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(name.substring(dot + 1)) && !name.startsWith(".");
    }

    /** Message files directly inside {@code dir} (not recursive), sorted by name. */
    public static List<Path> list(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).filter(MessageFiles::isMessageFile).sorted().toList();
        }
    }

    /** Reads a file and splits it into messages. */
    public static SplitResult read(Path file, Charset charset) throws IOException {
        return MessageSplitter.split(Files.readString(file, charset));
    }
}
