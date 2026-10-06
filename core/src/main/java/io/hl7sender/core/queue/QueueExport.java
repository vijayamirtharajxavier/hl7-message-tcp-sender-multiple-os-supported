package io.hl7sender.core.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Exports messages.
 *
 * <ul>
 *   <li>{@link #writeJson JSON}: metadata, full attempt history and payload, for tickets or reprocessing.
 *       It contains message content (possibly PHI).</li>
 *   <li>{@link #writeCsv CSV}: metadata only, with no message content, for spreadsheets and reports.</li>
 * </ul>
 */
public final class QueueExport {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private QueueExport() {
    }

    /** Writes {@code messages} to {@code file} (replaced atomically) and returns how many were written. */
    public static int writeJson(QueueStore store, DestinationConfig destination, List<QueuedMessage> messages,
                                Path file) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("exportedAt", Instant.now().toString());
        ObjectNode dest = root.putObject("destination");
        dest.put("name", destination.name());
        dest.put("address", destination.address());
        ArrayNode items = root.putArray("messages");
        for (QueuedMessage m : messages) {
            ObjectNode item = items.addObject();
            item.put("id", m.id());
            item.put("controlId", m.controlId());
            item.put("messageType", m.messageType());
            item.put("status", m.status().name());
            item.put("attempts", m.attempts());
            item.put("possibleDuplicate", m.possibleDuplicate());
            item.put("lastOutcome", m.lastOutcome().orElse(null));
            item.put("lastError", m.lastError().orElse(null));
            item.put("createdAt", m.createdAt().toString());
            item.put("completedAt", m.completedAt().map(Instant::toString).orElse(null));
            ArrayNode attempts = item.putArray("attemptHistory");
            for (AttemptRecord a : store.attempts(m.id())) {
                ObjectNode at = attempts.addObject();
                at.put("attempt", a.attemptNo());
                at.put("startedAt", a.startedAt().toString());
                at.put("outcome", a.outcome().orElse(null));
                at.put("ackCode", a.ackCode().orElse(null));
                at.put("detail", a.detail().orElse(null));
            }
            // Segments one per array entry for readability; join with CR to rebuild the wire format.
            ArrayNode segments = item.putArray("segments");
            for (String segment : m.payload().split("\r")) {
                if (!segment.isEmpty()) {
                    segments.add(segment);
                }
            }
        }
        Path dir = file.toAbsolutePath().getParent();
        if (dir == null) {
            throw new IOException("Export file has no parent directory: " + file);
        }
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "export", ".tmp");
        try {
            MAPPER.writeValue(tmp.toFile(), root);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return messages.size();
    }

    /**
     * Writes message metadata (no content) as CSV with a header row. Cells that a spreadsheet could read
     * as formulas are prefixed with {@code '} to prevent formula injection.
     *
     * @param destinationNames destination ID to name, for the destination column
     */
    public static int writeCsv(List<QueuedMessage> messages, Map<Long, String> destinationNames, Path file)
            throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir == null) {
            throw new IOException("Export file has no parent directory: " + file);
        }
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "export", ".tmp");
        try {
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                w.write(csvRow("id", "destination", "enqueued", "message_type", "control_id", "status", "attempts",
                        "possible_duplicate", "last_outcome", "last_error", "completed", "source", "batch_id"));
                for (QueuedMessage m : messages) {
                    w.write(csvRow(String.valueOf(m.id()), destinationNames.getOrDefault(m.destinationId(), ""),
                            m.createdAt().toString(), m.messageType(), m.controlId(), m.status().name(),
                            String.valueOf(m.attempts()), String.valueOf(m.possibleDuplicate()),
                            m.lastOutcome().orElse(""), m.lastError().orElse(""),
                            m.completedAt().map(Instant::toString).orElse(""), m.source().orElse(""),
                            m.batchId().orElse("")));
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return messages.size();
    }

    static String csvRow(String... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            String c = cells[i] == null ? "" : cells[i];
            if (!c.isEmpty() && "=+-@\t\r".indexOf(c.charAt(0)) >= 0) {
                c = "'" + c;
            }
            if (c.contains(",") || c.contains("\"") || c.contains("\n") || c.contains("\r")) {
                c = "\"" + c.replace("\"", "\"\"") + "\"";
            }
            sb.append(c);
        }
        return sb.append("\r\n").toString();
    }
}
