package io.hl7sender.core.ack;

import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.hl7.MessageHeader;
import io.hl7sender.core.hl7.ParsedMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AckBuilderTest {

    private final AckBuilder builder =
            new AckBuilder(Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC));
    private final ParsedMessage original = ParsedMessage.parse(
            "MSH|^~\\&|SAPP|SFAC|RAPP|RFAC|20260101||ADT^A01^ADT_A01|CTRL9|T|2.5.1\rPID|1");

    @Test
    void buildsAcceptThatSwapsApplicationsAndEchoesControlId() throws AckParseException {
        String ack = builder.build(original, AckCode.AA, "", null);
        MessageHeader h = ParsedMessage.parse(ack).header();
        assertThat(h.sendingApplication()).isEqualTo("RAPP");
        assertThat(h.receivingApplication()).isEqualTo("SAPP");
        assertThat(h.messageType()).isEqualTo("ACK^A01");
        assertThat(h.messageStructure()).isEqualTo("ACK");
        assertThat(h.processingId()).isEqualTo("T");
        assertThat(h.version()).isEqualTo("2.5.1");
        assertThat(h.timestamp()).isEqualTo("20260102030405");
        ParsedAck parsed = AckParser.parse(ack);
        assertThat(parsed.code()).isEqualTo(AckCode.AA);
        assertThat(parsed.controlId()).isEqualTo("CTRL9");
        assertThat(parsed.errors()).isEmpty();
    }

    @Test
    void errorAckCarriesErrSegmentThatRoundTrips() throws AckParseException {
        ParsedAck parsed = AckParser.parse(builder.build(original, AckCode.AE, "Simulated failure", null));
        assertThat(parsed.text()).isEqualTo("Simulated failure");
        assertThat(parsed.errors()).singleElement().satisfies(e -> {
            assertThat(e.code()).isEqualTo("207");
            assertThat(e.text()).isEqualTo("Simulated failure");
            assertThat(e.severity()).isEqualTo("E");
        });
    }

    @Test
    void olderVersionsUseErr1() throws AckParseException {
        ParsedMessage v23 = ParsedMessage.parse("MSH|^~\\&|A|B|C|D|20260101||ORM^O01|X|P|2.3");
        ParsedAck parsed = AckParser.parse(builder.build(v23, AckCode.AR, "", null));
        assertThat(parsed.errors()).singleElement().satisfies(e -> {
            assertThat(e.code()).isEqualTo("207");
            assertThat(e.raw()).startsWith("ERR|^^^207&");
        });
    }

    @Test
    void canOverrideControlIdAndBuildForGarbage() throws AckParseException {
        assertThat(AckParser.parse(builder.build(original, AckCode.CA, "", "OTHER")).controlId()).isEqualTo("OTHER");
        ParsedAck reject = AckParser.parse(builder.buildForUnparseable(AckCode.AR, "Unable to parse"));
        assertThat(reject.code()).isEqualTo(AckCode.AR);
        assertThat(reject.controlId()).isEmpty();
    }

    @Test
    void versionComparison() {
        assertThat(AckBuilder.isAtLeast25("2.5")).isTrue();
        assertThat(AckBuilder.isAtLeast25("2.8.1")).isTrue();
        assertThat(AckBuilder.isAtLeast25("2.4")).isFalse();
        assertThat(AckBuilder.isAtLeast25("2.3.1")).isFalse();
    }
}
