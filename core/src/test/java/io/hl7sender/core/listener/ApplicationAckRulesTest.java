package io.hl7sender.core.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hl7sender.core.ack.AckCode;
import org.junit.jupiter.api.Test;

/** When a receiver in enhanced mode sends an application ACK, by MSH-16 (HL7 table 0155). */
class ApplicationAckRulesTest {

    @Test
    void msh16DecidesWhichApplicationAcksAreSent() {
        assertThat(TestListener.sendsApplicationAck("AL", AckCode.AA)).isTrue();
        assertThat(TestListener.sendsApplicationAck("AL", AckCode.AE)).isTrue();
        assertThat(TestListener.sendsApplicationAck("ER", AckCode.AA)).isFalse();
        assertThat(TestListener.sendsApplicationAck("ER", AckCode.AR)).isTrue();
        assertThat(TestListener.sendsApplicationAck("SU", AckCode.AA)).isTrue();
        assertThat(TestListener.sendsApplicationAck("SU", AckCode.AE)).isFalse();
        assertThat(TestListener.sendsApplicationAck("NE", AckCode.AA)).isFalse();
        assertThat(TestListener.sendsApplicationAck("", AckCode.AA)).isFalse();
    }

    @Test
    void anApplicationAckIsNotACommitCode() {
        assertThatThrownBy(() -> new ListenerSettings.AppAck(6700, AckCode.CA, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ListenerSettings.AppAck(0, AckCode.AA, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
