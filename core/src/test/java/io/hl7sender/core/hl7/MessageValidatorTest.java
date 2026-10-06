package io.hl7sender.core.hl7;

import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.hl7.validation.MessageValidator;
import io.hl7sender.core.hl7.validation.ValidationIssue;
import io.hl7sender.core.hl7.validation.ValidationReport;
import org.junit.jupiter.api.Test;

class MessageValidatorTest {

    private static final MessageValidator VALIDATOR = new MessageValidator();
    private static final String VALID = "MSH|^~\\&|APP|FAC|RAPP|RFAC|20260101120000||ADT^A01^ADT_A01|C1|P|2.5.1\n"
            + "EVN|A01|20260101120000\n"
            + "PID|1||MRN1^^^HOSP^MR||DOE^JANE||19800101|F\n"
            + "PV1|1|I";

    @Test
    void validMessageHasNoIssues() {
        ValidationReport r = VALIDATOR.validate(VALID);
        assertThat(r.issues()).isEmpty();
        assertThat(r.hasErrors()).isFalse();
        assertThat(r.segmentCount()).isEqualTo(4);
        assertThat(r.summary()).isEqualTo("ADT^A01 v2.5.1, 4 segments, 0 errors, 0 warnings");
    }

    @Test
    void emptyMessageIsAnError() {
        assertThat(VALIDATOR.validate("  ").hasErrors()).isTrue();
    }

    @Test
    void messageMustStartWithMsh() {
        ValidationReport r = VALIDATOR.validate("PID|1||X");
        assertThat(r.hasErrors()).isTrue();
        assertThat(r.header()).isEmpty();
    }

    @Test
    void missingTypeAndVersionAreErrors() {
        ValidationReport r = VALIDATOR.validate("MSH|^~\\&|APP|FAC|||20260101||||P|");
        assertThat(r.errors()).extracting(ValidationIssue::message)
                .anySatisfy(m -> assertThat(m).contains("MSH-9"))
                .anySatisfy(m -> assertThat(m).contains("MSH-12"));
    }

    @Test
    void missingControlIdIsOnlyAWarning() {
        ValidationReport r = VALIDATOR.validate(VALID.replace("|C1|", "||"));
        assertThat(r.hasErrors()).isFalse();
        assertThat(r.warnings()).extracting(ValidationIssue::message)
                .anySatisfy(m -> assertThat(m).contains("MSH-10"));
    }

    @Test
    void invalidSegmentNameIsAnError() {
        ValidationReport r = VALIDATOR.validate(VALID + "\npid|lowercase");
        assertThat(r.errors()).extracting(ValidationIssue::message)
                .anySatisfy(m -> assertThat(m).contains("invalid name 'pid'"));
    }

    @Test
    void multipleMessagesAreAnError() {
        ValidationReport r = VALIDATOR.validate(VALID + "\n" + VALID);
        assertThat(r.errors()).extracting(ValidationIssue::message)
                .anySatisfy(m -> assertThat(m).contains("More than one MSH"));
    }

    @Test
    void hapiDataTypeProblemsAreWarnings() {
        ValidationReport r = VALIDATOR.validate(VALID.replace("19800101", "NOT-A-DATE"));
        assertThat(r.hasErrors()).isFalse();
        assertThat(r.warnings()).singleElement().satisfies(w -> {
            assertThat(w.source()).isEqualTo(ValidationIssue.Source.HAPI);
            assertThat(w.message()).startsWith("Validation failed").contains("PID-7").doesNotContain("Exception");
        });
    }
}
