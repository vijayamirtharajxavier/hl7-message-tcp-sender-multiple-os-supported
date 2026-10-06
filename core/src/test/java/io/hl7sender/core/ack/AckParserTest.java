package io.hl7sender.core.ack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AckParserTest {

    @Test
    void parsesAcceptAck() throws AckParseException {
        ParsedAck ack = AckParser.parse("MSH|^~\\&|R|RF|S|SF|20260101||ACK^A01^ACK|A1|P|2.5.1\rMSA|AA|C1\r");
        assertThat(ack.code()).isEqualTo(AckCode.AA);
        assertThat(ack.isAccept()).isTrue();
        assertThat(ack.controlId()).isEqualTo("C1");
        assertThat(ack.messageType()).isEqualTo("ACK^A01^ACK");
        assertThat(ack.ackControlId()).isEqualTo("A1");
        assertThat(ack.errors()).isEmpty();
    }

    @Test
    void parsesV25ErrorSegments() throws AckParseException {
        ParsedAck ack = AckParser.parse(String.join("\n",
                "MSH|^~\\&|R|RF|S|SF|20260101||ACK|A1|P|2.5.1",
                "MSA|AE|C1|Patient not found",
                "ERR||PID^1^3|204^Unknown key identifier^HL70357|E||||MRN does not exist"));
        assertThat(ack.code()).isEqualTo(AckCode.AE);
        assertThat(ack.text()).isEqualTo("Patient not found");
        assertThat(ack.errors()).singleElement().satisfies(e -> {
            assertThat(e.code()).isEqualTo("204");
            assertThat(e.location()).isEqualTo("PID-3");
            assertThat(e.severity()).isEqualTo("E");
            assertThat(e.text()).isEqualTo("MRN does not exist");
            assertThat(e.describe()).isEqualTo("[E] 204 MRN does not exist at PID-3");
        });
    }

    @Test
    void parsesPre25ErrorSegments() throws AckParseException {
        ParsedAck ack = AckParser.parse("MSH|^~\\&|R|RF|S|SF|20260101||ACK|A1|P|2.3\rMSA|AR|C1\r"
                + "ERR|PV1^1^3^103&Table value not found&HL70357\r");
        assertThat(ack.code()).isEqualTo(AckCode.AR);
        assertThat(ack.errors()).singleElement().satisfies(e -> {
            assertThat(e.code()).isEqualTo("103");
            assertThat(e.text()).isEqualTo("Table value not found");
            assertThat(e.location()).isEqualTo("PV1-3");
        });
    }

    @Test
    void acceptsEnhancedModeAndLowercaseCodes() throws AckParseException {
        assertThat(AckParser.parse("MSH|^~\\&|R\rMSA|ca|C1").code()).isEqualTo(AckCode.CA);
        assertThat(AckCode.CA.isCommit()).isTrue();
    }

    @Test
    void rejectsResponsesThatAreNotAcks() {
        assertThatThrownBy(() -> AckParser.parse("garbage")).isInstanceOf(AckParseException.class);
        assertThatThrownBy(() -> AckParser.parse("MSH|^~\\&|R\rPID|1"))
                .isInstanceOf(AckParseException.class).hasMessageContaining("no MSA");
        assertThatThrownBy(() -> AckParser.parse("MSH|^~\\&|R\rMSA|XX|1"))
                .isInstanceOf(AckParseException.class).hasMessageContaining("'XX'");
    }
}
