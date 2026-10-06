package io.hl7sender.core.hl7;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Hl7TextTest {

    @Test
    void normalizesMixedLineEndingsToCarriageReturns() {
        String text = "MSH|^~\\&|A\r\nPID|1\nPV1|1\rOBX|1";
        assertThat(Hl7Text.normalize(text)).isEqualTo("MSH|^~\\&|A\rPID|1\rPV1|1\rOBX|1\r");
    }

    @Test
    void dropsBomBlankLinesAndTrailingWhitespace() {
        String text = "﻿MSH|^~\\&|A   \n\n  \nPID|1\t\n\n";
        assertThat(Hl7Text.normalize(text)).isEqualTo("MSH|^~\\&|A\rPID|1\r");
    }

    @Test
    void emptyInputNormalizesToEmpty() {
        assertThat(Hl7Text.normalize(null)).isEmpty();
        assertThat(Hl7Text.normalize(" \n \r\n")).isEmpty();
    }

    @Test
    void displayUsesNewlines() {
        assertThat(Hl7Text.toDisplay("MSH|x\rPID|1\r")).isEqualTo("MSH|x\nPID|1");
    }
}
