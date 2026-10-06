package io.hl7sender.core.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateEngineTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-02-03T04:05:06Z"), ZoneOffset.UTC);
    private final TemplateEngine engine = new TemplateEngine(clock, new Random(1));

    @Test
    void expandsTimeAndSequence() {
        assertThat(engine.expand("${NOW}|${TODAY}|${NOW:HHmm}").text()).isEqualTo("20260203040506|20260203|0405");
        assertThat(engine.expand("A${SEQ}|${SEQ:4}").text()).isEqualTo("A1|0001");
        assertThat(engine.expand("A${SEQ}").text()).isEqualTo("A2");
        assertThat(engine.preview("A${SEQ}", Map.of()).text()).isEqualTo("A3");
        assertThat(engine.expand("A${SEQ}").text()).isEqualTo("A3");
        engine.resetSequence();
        assertThat(engine.expand("${SEQ}").text()).isEqualTo("1");
    }

    @Test
    void randomValuesAreConsistentWithinOneMessageAndChangeBetweenMessages() {
        String t = "PID|1||${RANDOM_MRN}\rPV1|1||||||||||||||||||${RANDOM_MRN}|${UUID}|${UUID}";
        String first = engine.expand(t).text();
        String[] f = first.split("[|\r]");
        assertThat(f[3]).matches("\\d{8}").isEqualTo(f[f.length - 3]);
        assertThat(f[f.length - 2]).isEqualTo(f[f.length - 1]);
        assertThat(engine.expand(t).text()).isNotEqualTo(first);
    }

    @Test
    void otherBuiltIns() {
        String out = engine.expand("${RANDOM:4}|${RANDOM_FIRST_NAME}|${RANDOM_LAST_NAME}|${RANDOM_DOB}|${RANDOM_SEX}"
                + "|${CONTROL_ID}").text();
        String[] f = out.split("\\|");
        assertThat(f[0]).matches("\\d{4}");
        assertThat(f[1]).matches("[A-Z]+");
        assertThat(f[3]).matches("(19[3-9]\\d|20[0-2]\\d)\\d{4}");
        assertThat(f[4]).isIn("F", "M");
        assertThat(f[5]).hasSize(20).startsWith("20260203040506");
    }

    @Test
    void userVariablesUnknownsAndEscapes() {
        TemplateEngine.Expansion e = engine.expand("${FACILITY}|${nope}|$${NOW}|${NOW:not a [pattern}",
                Map.of("FACILITY", "NORTH"));
        assertThat(e.text()).isEqualTo("NORTH|${nope}|${NOW}|${NOW:not a [pattern}");
        assertThat(e.unknown()).containsExactlyInAnyOrder("nope", "NOW");
        assertThat(TemplateEngine.hasVariables("MSH|x")).isFalse();
        assertThat(TemplateEngine.hasVariables("MSH|${NOW}")).isTrue();
    }

    @Test
    void templateStoreRoundTrip(@TempDir Path dir) throws IOException {
        TemplateStore store = new TemplateStore(dir.resolve("templates"));
        assertThat(store.list()).isEmpty();
        TemplateStore.Template saved = store.save("My ADT: test/1", "MSH|^~\\&|A\rPID|1");
        assertThat(saved.name()).isEqualTo("My ADT_ test_1");
        assertThat(store.list()).singleElement().satisfies(t -> {
            assertThat(t.name()).isEqualTo("My ADT_ test_1");
            assertThat(t.text()).isEqualTo("MSH|^~\\&|A\nPID|1");
        });
        assertThat(store.delete("My ADT_ test_1")).isTrue();
        assertThat(store.list()).isEmpty();
        assertThatThrownBy(() -> store.save("  ", "x")).isInstanceOf(IllegalArgumentException.class);
    }
}
