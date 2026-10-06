package io.hl7sender.core.hl7;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ControlIdGeneratorTest {

    private final Clock fixed = Clock.fixed(Instant.parse("2026-03-04T05:06:07.089Z"), ZoneOffset.UTC);

    @Test
    void producesTwentyCharacterTimestampIds() {
        ControlIdGenerator gen = new ControlIdGenerator(fixed);
        assertThat(gen.next()).isEqualTo("20260304050607089000");
        assertThat(gen.next()).isEqualTo("20260304050607089001");
    }

    @Test
    void idsAreUniqueWithinOneMillisecond() {
        ControlIdGenerator gen = new ControlIdGenerator(fixed);
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            ids.add(gen.next());
        }
        assertThat(ids).hasSize(1000).allSatisfy(id -> assertThat(id).hasSize(20));
    }
}
