package io.hl7sender.core.hl7;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FieldDictionaryTest {

    private final FieldDictionary dict = FieldDictionary.shared();

    @Test
    void namesFieldsAndComponentsFromHapi() {
        FieldDictionary.FieldInfo pid5 = dict.field("2.5.1", "PID", 5).orElseThrow();
        assertThat(pid5.name()).isEqualTo("Patient Name");
        assertThat(pid5.dataType()).isEqualTo("XPN");
        assertThat(pid5.component(1)).contains("Family Name");
        assertThat(pid5.component(2)).contains("Given Name");
        assertThat(dict.describe("2.5.1", "PID", 5, 1)).isEqualTo("PID-5.1 Patient Name > Family Name (XPN)");
        assertThat(dict.describe("2.5.1", "PID", 3, 0)).isEqualTo("PID-3 Patient Identifier List (CX)");
        assertThat(dict.field("2.5.1", "PID", 3).orElseThrow().component(1)).contains("ID Number");
    }

    @Test
    void usesTheMessagesVersion() {
        assertThat(dict.field("2.3", "PID", 7).orElseThrow().name()).isEqualTo("Date of Birth");
        assertThat(dict.field("2.5.1", "PID", 7).orElseThrow().name()).isEqualTo("Date/Time of Birth");
        assertThat(dict.field("2.8.1", "MSH", 9).orElseThrow().name()).isEqualTo("Message Type");
    }

    @Test
    void unknownVersionFallsBackAndUnknownSegmentsAreUnnamed() {
        assertThat(dict.field("9.9", "PID", 5).orElseThrow().name()).isEqualTo("Patient Name");
        assertThat(dict.field("", "OBX", 5).orElseThrow().name()).isEqualTo("Observation Value");
        assertThat(dict.field("2.5.1", "ZZZ", 1)).isEmpty();
        assertThat(dict.field("2.5.1", "PID", 999)).isEmpty();
        assertThat(dict.describe("2.5.1", "ZPD", 2, 1)).isEqualTo("ZPD-2.1");
    }

    @Test
    void humanizesCamelCase() {
        assertThat(FieldDictionary.humanize("IDNumber")).isEqualTo("ID Number");
        assertThat(FieldDictionary.humanize("FamilyName")).isEqualTo("Family Name");
    }
}
