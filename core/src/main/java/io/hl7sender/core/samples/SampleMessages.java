package io.hl7sender.core.samples;

import io.hl7sender.core.hl7.Hl7Text;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Built-in example messages with fictitious patient data. */
public final class SampleMessages {

    /**
     * One example message.
     *
     * @param name        display name, e.g. {@code ADT^A01 - Admit}
     * @param text        message text, one segment per line
     */
    public record Sample(String name, String text) {

        @Override
        public String toString() {
            return name;
        }
    }

    private static final List<String[]> INDEX = List.of(
            new String[] {"ADT^A01 - Admit/visit notification", "adt_a01.hl7"},
            new String[] {"ADT^A03 - Discharge", "adt_a03.hl7"},
            new String[] {"ADT^A04 - Register a patient", "adt_a04.hl7"},
            new String[] {"ADT^A08 - Update patient information", "adt_a08.hl7"},
            new String[] {"ORM^O01 - General order", "orm_o01.hl7"},
            new String[] {"ORU^R01 - Observation result", "oru_r01.hl7"},
            new String[] {"SIU^S12 - New appointment", "siu_s12.hl7"},
            new String[] {"MDM^T02 - Document notification", "mdm_t02.hl7"},
            new String[] {"DFT^P03 - Post detail financial transaction", "dft_p03.hl7"});

    private static final List<String[]> TEMPLATES = List.of(
            new String[] {"ADT^A01 - random patient (template)", "templates/adt_a01_random.hl7"},
            new String[] {"ORU^R01 - random result (template)", "templates/oru_r01_random.hl7"});

    private SampleMessages() {
    }

    /** Concrete example messages. */
    public static List<Sample> all() {
        return INDEX.stream().map(e -> new Sample(e[0], load(e[1]))).toList();
    }

    /** Built-in templates that use {@code ${...}} variables to generate unique messages. */
    public static List<Sample> templates() {
        return TEMPLATES.stream().map(e -> new Sample(e[0], load(e[1]))).toList();
    }

    private static String load(String resource) {
        try (InputStream in = SampleMessages.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing sample resource " + resource);
            }
            return Hl7Text.toDisplay(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
