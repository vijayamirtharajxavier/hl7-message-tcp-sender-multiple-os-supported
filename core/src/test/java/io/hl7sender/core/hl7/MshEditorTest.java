package io.hl7sender.core.hl7;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MshEditorTest {

    @Test
    void replacesFieldAndPreservesEverythingElse() {
        String msg = "MSH|^~\\&|APP|FAC|||OLD||ADT^A01|OLDID|P|2.5\nPID|1||X\nZXX|keep^me";
        String out = MshEditor.setField(msg, 10, "NEWID");
        assertThat(out).isEqualTo("MSH|^~\\&|APP|FAC|||OLD||ADT^A01|NEWID|P|2.5\rPID|1||X\rZXX|keep^me\r");
        assertThat(ParsedMessage.parse(out).header().controlId()).isEqualTo("NEWID");
    }

    @Test
    void padsMissingFields() {
        String out = MshEditor.setField("MSH|^~\\&|APP", 10, "ID");
        assertThat(ParsedMessage.parse(out).msh().field(10)).isEqualTo("ID");
        assertThat(ParsedMessage.parse(out).msh().field(3)).isEqualTo("APP");
    }

    @Test
    void refusesDelimiterFields() {
        assertThatThrownBy(() -> MshEditor.setField("MSH|^~\\&|A", 2, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
