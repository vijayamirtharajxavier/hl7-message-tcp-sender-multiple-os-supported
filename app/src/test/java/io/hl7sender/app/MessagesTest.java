package io.hl7sender.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Every language has every text, and every text is a valid {@link MessageFormat} pattern. */
class MessagesTest {

    private static Properties load(String name) throws IOException {
        Properties p = new Properties();
        try (InputStream in = MessagesTest.class.getResourceAsStream("/io/hl7sender/app/" + name)) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void spanishHasEveryKey() throws IOException {
        Properties en = load("messages.properties");
        Properties es = load("messages_es.properties");
        assertThat(es.stringPropertyNames()).containsExactlyInAnyOrderElementsOf(en.stringPropertyNames());
    }

    @Test
    void everyTextFormats() throws IOException {
        for (String file : new String[] {"messages.properties", "messages_es.properties"}) {
            Properties p = load(file);
            for (String key : p.stringPropertyNames()) {
                String pattern = p.getProperty(key);
                assertThatCode(() -> new MessageFormat(pattern, Locale.ROOT).format(new Object[] {"a", "b", "c"}))
                        .as(file + ": " + key).doesNotThrowAnyException();
            }
        }
        // Template variables in help text are shown literally.
        assertThat(new MessageFormat(load("messages.properties").getProperty("rules.help")).format(new Object[0]))
                .contains("${IN:PID-3.1}");
    }
}
