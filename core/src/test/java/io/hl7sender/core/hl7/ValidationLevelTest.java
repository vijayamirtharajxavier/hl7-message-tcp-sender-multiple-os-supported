package io.hl7sender.core.hl7;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hl7sender.core.hl7.validation.ConformanceProfile;
import io.hl7sender.core.hl7.validation.MessageValidator;
import io.hl7sender.core.hl7.validation.ValidationIssue;
import io.hl7sender.core.hl7.validation.ValidationLevel;
import io.hl7sender.core.hl7.validation.ValidationReport;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ValidationLevelTest {

    private static final MessageValidator VALIDATOR = new MessageValidator();
    private static final String GOOD = "MSH|^~\\&|APP|FAC|R|RF|20260101||ADT^A01^ADT_A01|1|P|2.5.1\r"
            + "EVN|A01|20260101\rPID|1||MRN1^^^H^MR||DOE^JANE||19800101|F\rPV1|1|I\r";
    private static final String BAD_DATE = GOOD.replace("19800101", "NOTADATE");

    private static Path profilePath() throws URISyntaxException {
        return Path.of(ValidationLevelTest.class.getResource("/profiles/adt_a01_demo.xml").toURI());
    }

    @Test
    void levelsControlWhatRunsAndWhatBlocks() {
        assertThat(VALIDATOR.validate(BAD_DATE, ValidationLevel.LENIENT, null).issues()).isEmpty();
        ValidationReport standard = VALIDATOR.validate(BAD_DATE, ValidationLevel.STANDARD, null);
        assertThat(standard.hasErrors()).isFalse();
        assertThat(standard.warnings()).hasSize(1);
        ValidationReport strict = VALIDATOR.validate(BAD_DATE, ValidationLevel.STRICT, null);
        assertThat(strict.errors()).singleElement().extracting(ValidationIssue::source)
                .isEqualTo(ValidationIssue.Source.HAPI);
        // Structural errors block at every level.
        assertThat(VALIDATOR.validate("PID|1", ValidationLevel.LENIENT, null).hasErrors()).isTrue();
    }

    @Test
    void conformanceProfileFindsUsageViolations() throws Exception {
        ConformanceProfile profile = ConformanceProfile.load(profilePath());
        assertThat(profile.describe()).contains("ADT^A01");
        assertThat(VALIDATOR.validate(GOOD, ValidationLevel.STRICT, profile).issues()).isEmpty();

        // PID-2 is "X" (not supported) and PID-7 is required.
        String violating = GOOD.replace("PID|1||MRN1^^^H^MR||DOE^JANE||19800101|F",
                "PID|1|OLD|MRN1^^^H^MR||DOE^JANE|||F");
        ValidationReport standard = VALIDATOR.validate(violating, ValidationLevel.STANDARD, profile);
        assertThat(standard.hasErrors()).isFalse();
        assertThat(standard.warnings()).extracting(ValidationIssue::source)
                .containsOnly(ValidationIssue.Source.PROFILE);
        assertThat(standard.warnings()).extracting(ValidationIssue::message)
                .anySatisfy(m -> assertThat(m).contains("Date/Time of Birth"))
                .anySatisfy(m -> assertThat(m).contains("Field 2 in PID"));
        assertThat(VALIDATOR.validate(violating, ValidationLevel.STRICT, profile).errors()).hasSize(2);
        assertThat(VALIDATOR.validate(violating, ValidationLevel.LENIENT, profile).issues()).isEmpty();
    }

    @Test
    void invalidProfileFileIsRejected(@TempDir Path dir) throws IOException {
        Path junk = Files.writeString(dir.resolve("x.xml"), "<not-a-profile/>");
        assertThatThrownBy(() -> ConformanceProfile.load(junk)).isInstanceOf(IOException.class);
    }

    @Test
    void cachedProfileIsReusedUntilFileChanges() throws Exception {
        Path p = profilePath();
        assertThat(ConformanceProfile.cached(p)).isSameAs(ConformanceProfile.cached(p));
    }
}
