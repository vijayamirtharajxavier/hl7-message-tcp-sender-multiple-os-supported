package io.hl7sender.core.samples;

import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.hl7.validation.MessageValidator;
import org.junit.jupiter.api.Test;

class SampleMessagesTest {

    @Test
    void allSamplesAreCleanAgainstHapi() {
        MessageValidator validator = new MessageValidator();
        assertThat(SampleMessages.all()).hasSize(9).allSatisfy(sample ->
                assertThat(validator.validate(sample.text()).issues()).as(sample.name()).isEmpty());
    }

    @Test
    void templatesExpandToCleanMessages() {
        MessageValidator validator = new MessageValidator();
        io.hl7sender.core.template.TemplateEngine engine = new io.hl7sender.core.template.TemplateEngine();
        assertThat(SampleMessages.templates()).hasSize(2).allSatisfy(t -> {
            var expansion = engine.expand(t.text());
            assertThat(expansion.unknown()).as(t.name()).isEmpty();
            assertThat(validator.validate(expansion.text()).issues()).as(t.name()).isEmpty();
        });
    }
}
