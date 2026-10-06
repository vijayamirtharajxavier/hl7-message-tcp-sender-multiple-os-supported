package io.hl7sender.core.hl7;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ParsedMessageTest {

    private static final String MSG = String.join("\r",
            "MSH|^~\\&|APP|FAC|RAPP|RFAC|20260101120000||ADT^A01^ADT_A01|CTRL1|P|2.5.1|||AL|NE||UNICODE UTF-8",
            "PID|1||MRN1^^^HOSP^MR~ALT1^^^OTHER||DOE^JANE",
            "ZZZ|custom");

    @Test
    void numbersMshFieldsLikeHl7() {
        ParsedMessage m = ParsedMessage.parse(MSG);
        Segment msh = m.msh();
        assertThat(msh.field(1)).isEqualTo("|");
        assertThat(msh.field(2)).isEqualTo("^~\\&");
        assertThat(msh.field(3)).isEqualTo("APP");
        assertThat(msh.field(9)).isEqualTo("ADT^A01^ADT_A01");
        assertThat(msh.field(10)).isEqualTo("CTRL1");
        assertThat(msh.field(99)).isEmpty();
    }

    @Test
    void readsHeaderSummary() {
        MessageHeader h = ParsedMessage.parse(MSG).header();
        assertThat(h.messageType()).isEqualTo("ADT^A01");
        assertThat(h.messageStructure()).isEqualTo("ADT_A01");
        assertThat(h.controlId()).isEqualTo("CTRL1");
        assertThat(h.version()).isEqualTo("2.5.1");
        assertThat(h.acceptAckType()).isEqualTo("AL");
        assertThat(h.applicationAckType()).isEqualTo("NE");
        assertThat(h.characterSet()).isEqualTo("UNICODE UTF-8");
        assertThat(h.receivingApplication()).isEqualTo("RAPP");
    }

    @Test
    void readsComponentsOfFirstRepetition() {
        Segment pid = ParsedMessage.parse(MSG).first("PID").orElseThrow();
        assertThat(pid.component(3, 1)).isEqualTo("MRN1");
        assertThat(pid.component(3, 4)).isEqualTo("HOSP");
        assertThat(pid.component(5, 2)).isEqualTo("JANE");
        assertThat(pid.component(5, 9)).isEmpty();
    }

    @Test
    void honoursCustomDelimiters() {
        ParsedMessage m = ParsedMessage.parse("MSH#*~\\&#APP##########2.5\rPID#1##A*B");
        assertThat(m.delimiters().field()).isEqualTo('#');
        assertThat(m.first("PID").orElseThrow().component(3, 2)).isEqualTo("B");
    }

    @Test
    void acceptsLineFeeds() {
        assertThat(ParsedMessage.parse(MSG.replace('\r', '\n')).segments()).hasSize(3);
    }

    @Test
    void rejectsNonHl7() {
        assertThatThrownBy(() -> ParsedMessage.parse("")).isInstanceOf(Hl7FormatException.class);
        assertThatThrownBy(() -> ParsedMessage.parse("PID|1")).isInstanceOf(Hl7FormatException.class);
    }
}
