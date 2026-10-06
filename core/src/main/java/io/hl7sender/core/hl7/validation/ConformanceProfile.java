package io.hl7sender.core.hl7.validation;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.conf.ProfileException;
import ca.uhn.hl7v2.conf.check.DefaultValidator;
import ca.uhn.hl7v2.conf.parser.ProfileParser;
import ca.uhn.hl7v2.conf.spec.RuntimeProfile;
import ca.uhn.hl7v2.model.Message;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An HL7 v2 conformance profile (the XML format produced by the HL7 Messaging Workbench and used by many
 * interface specifications). It checks usage (R/RE/O/X), cardinality, length and data types for each
 * segment and field of one message type.
 */
public final class ConformanceProfile {

    private static final Map<Path, Cached> CACHE = new ConcurrentHashMap<>();

    private record Cached(long modified, ConformanceProfile profile) {
    }

    private final Path source;
    private final RuntimeProfile profile;
    private final HapiContext hapi = new DefaultHapiContext();

    private ConformanceProfile(Path source, RuntimeProfile profile) {
        this.source = source;
        this.profile = profile;
    }

    /** Parses a profile file. */
    public static ConformanceProfile load(Path file) throws IOException {
        try {
            String xml = Files.readString(file, StandardCharsets.UTF_8);
            return new ConformanceProfile(file, new ProfileParser(false).parse(xml));
        } catch (ProfileException | RuntimeException e) {
            throw new IOException("Not a valid HL7 conformance profile: " + e.getMessage(), e);
        }
    }

    /** Loads a profile, reusing the parsed copy until the file changes. */
    public static ConformanceProfile cached(Path file) throws IOException {
        Path key = file.toAbsolutePath().normalize();
        long modified = Files.getLastModifiedTime(key).toMillis();
        Cached c = CACHE.get(key);
        if (c != null && c.modified() == modified) {
            return c.profile();
        }
        ConformanceProfile loaded = load(key);
        CACHE.put(key, new Cached(modified, loaded));
        return loaded;
    }

    public Path source() {
        return source;
    }

    /** Profile name and message type, e.g. {@code Demo ADT A01 (ADT^A01)}. */
    public String describe() {
        var msg = profile.getMessage();
        return msg.getIdentifier() == null || msg.getIdentifier().isBlank()
                ? msg.getMsgType() + "^" + msg.getEventType()
                : msg.getIdentifier() + " (" + msg.getMsgType() + "^" + msg.getEventType() + ")";
    }

    /** Checks a parsed message against this profile. */
    List<String> check(Message message) throws HL7Exception {
        List<String> out = new ArrayList<>();
        synchronized (hapi) {
            HL7Exception[] problems;
            try {
                problems = new DefaultValidator(hapi).validate(message, profile.getMessage());
            } catch (ProfileException e) {
                throw new HL7Exception("Profile " + source.getFileName() + " could not be applied: "
                        + e.getMessage(), e);
            }
            for (HL7Exception problem : problems) {
                out.add(problem.getMessage());
            }
        }
        return out;
    }
}
