package io.hl7sender.core.transport;

import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.send.AckMode;
import io.hl7sender.core.send.PreparedMessage;
import io.hl7sender.core.send.SendOutcome;
import io.hl7sender.core.send.SendResult;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Writes each message to a file in a folder, for receivers that pick files up: an interface engine's file reader,
 * or an SFTP/SMB share mounted on this computer. Each file is written under a temporary name and then renamed, so
 * a reader never sees half a message. There is no acknowledgment: a written file completes the message as "sent".
 */
final class FileTransport implements Transport {

    static final String ID = "file";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")
            .withZone(ZoneId.systemDefault());

    private final Path folder;
    private final String pattern;
    private final Charset charset;
    private final String lineEnding;

    private FileTransport(Path folder, String pattern, Charset charset, String lineEnding) {
        this.folder = folder;
        this.pattern = pattern;
        this.charset = charset;
        this.lineEnding = lineEnding;
    }

    @Override
    public SendResult send(PreparedMessage message, AckMode ackMode) {
        Instant start = Instant.now();
        long t0 = System.nanoTime();
        String name = fileName(pattern, message, start);
        Path target = folder.resolve(name);
        String where = target.toString();
        try {
            Files.createDirectories(folder);
            if (Files.exists(target)) {
                String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
                String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
                for (int i = 2; Files.exists(target); i++) {
                    target = folder.resolve(base + "-" + i + ext);
                }
                where = target.toString();
            }
            Path tmp = folder.resolve("." + target.getFileName() + ".tmp");
            String text = message.wire().replace("\r", lineEnding);
            Files.writeString(tmp, text, charset);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target);
            }
        } catch (IOException | InvalidPathException e) {
            return Transports.result(SendOutcome.SEND_FAILED, where, message, null, null,
                    "Cannot write " + where + ": " + e.getMessage(), start, Duration.ZERO);
        }
        return Transports.result(SendOutcome.SENT_NO_ACK, where, message, null, null, "Written to " + where, start,
                Duration.ofNanos(System.nanoTime() - t0));
    }

    /** {@code pattern} with {CONTROL_ID}, {TYPE} and {TIMESTAMP} filled in, made safe for file names. */
    static String fileName(String pattern, PreparedMessage m, Instant at) {
        String name = pattern
                .replace("{CONTROL_ID}", m.controlId().isEmpty() ? "message" : m.controlId())
                .replace("{TYPE}", m.messageType().replace('^', '_'))
                .replace("{TIMESTAMP}", STAMP.format(at));
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** Creates file transports. */
    static final class Factory implements TransportFactory {

        @Override
        public String id() {
            return ID;
        }

        @Override
        public String displayName() {
            return "File (folder)";
        }

        @Override
        public List<TransportOption> options() {
            return List.of(
                    TransportOption.required("folder", "Folder", "where message files are written"),
                    TransportOption.optional("fileName", "File name",
                            "with {CONTROL_ID}, {TYPE} and {TIMESTAMP}", "{TIMESTAMP}-{CONTROL_ID}.hl7"),
                    TransportOption.optional("lineEnding", "Line ending", "CR (HL7), CRLF or LF", "CR"));
        }

        @Override
        public void validate(Map<String, String> options) {
            TransportFactory.super.validate(options);
            try {
                Path.of(options.get("folder").trim());
            } catch (InvalidPathException e) {
                throw new IllegalArgumentException("File: '" + options.get("folder") + "' is not a valid folder", e);
            }
            lineEnding(options.getOrDefault("lineEnding", "CR"));
        }

        @Override
        public String describe(Map<String, String> options) {
            return options.getOrDefault("folder", "file");
        }

        @Override
        public Transport open(DestinationConfig d, TransportContext context) {
            Map<String, String> o = d.transportOptions();
            String pattern = o.getOrDefault("fileName", "").isBlank() ? "{TIMESTAMP}-{CONTROL_ID}.hl7"
                    : o.get("fileName").trim();
            return new FileTransport(Path.of(o.get("folder").trim()), pattern, Charset.forName(d.charset()),
                    lineEnding(o.getOrDefault("lineEnding", "CR")));
        }

        static String lineEnding(String name) {
            return switch (name.isBlank() ? "CR" : name.trim().toUpperCase(java.util.Locale.ROOT)) {
                case "CR" -> "\r";
                case "CRLF" -> "\r\n";
                case "LF" -> "\n";
                default -> throw new IllegalArgumentException("File: the line ending must be CR, CRLF or LF");
            };
        }
    }
}
