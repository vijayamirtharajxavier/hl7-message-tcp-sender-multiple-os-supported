package io.hl7sender.core.hl7;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Hl7LocationTest {

    private static final String TEXT = "MSH|^~\\&|APP|FAC|||20260101||ADT^A01|C1|P|2.5.1\n"
            + "PID|1||MRN1^^^H^MR~ALT2^^^X||DOE^JANE&J\r\n"
            + "\n"
            + "PV1|1|I";

    private static Hl7Location at(String marker, int delta) {
        int i = TEXT.indexOf(marker);
        return Hl7Location.at(TEXT, i + delta).orElseThrow();
    }

    @Test
    void locatesFieldsComponentsRepetitionsAndSubcomponents() {
        Hl7Location family = at("DOE", 1);
        assertThat(family.segment()).isEqualTo("PID");
        assertThat(family.segmentOrdinal()).isEqualTo(2);
        assertThat(family.field()).isEqualTo(5);
        assertThat(family.component()).isEqualTo(1);
        assertThat(family.componentValue()).isEqualTo("DOE");
        assertThat(family.path()).isEqualTo("PID-5.1");

        assertThat(at("JANE", 0).component()).isEqualTo(2);
        assertThat(at("&J", 1).subcomponent()).isEqualTo(2);
        assertThat(at("&J", 1).path()).isEqualTo("PID-5.2.2");

        Hl7Location alt = at("ALT2", 2);
        assertThat(alt.field()).isEqualTo(3);
        assertThat(alt.repetition()).isEqualTo(2);
        assertThat(alt.path()).isEqualTo("PID-3[2].1");
        assertThat(at("MR~", 0).component()).isEqualTo(5);
    }

    @Test
    void mshNumberingAndSimpleFields() {
        assertThat(at("MSH", 1).field()).isZero();
        assertThat(at("MSH", 1).path()).isEqualTo("MSH");
        assertThat(at("|^~", 0).field()).isEqualTo(1);
        assertThat(at("^~\\&", 1).field()).isEqualTo(2);
        assertThat(at("APP", 1).field()).isEqualTo(3);
        assertThat(at("A01|", 0).path()).isEqualTo("MSH-9.2");
        assertThat(at("C1", 0).field()).isEqualTo(10);
        assertThat(at("C1", 0).path()).isEqualTo("MSH-10");
        assertThat(at("PV1|1|I", 6).path()).isEqualTo("PV1-2");
        assertThat(at("PV1|1|I", 6).segmentOrdinal()).isEqualTo(3);
    }

    @Test
    void blankLinesAndOutOfRange() {
        assertThat(Hl7Location.at(TEXT, TEXT.indexOf("\n\n") + 1)).isEmpty();
        assertThat(Hl7Location.at(TEXT, -1)).isEmpty();
        assertThat(Hl7Location.at(null, 0)).isEmpty();
    }
}
