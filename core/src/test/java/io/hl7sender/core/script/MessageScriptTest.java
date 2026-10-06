package io.hl7sender.core.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hl7sender.core.hl7.MutableMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class MessageScriptTest {

    private static final String ADT = "MSH|^~\\&|EHR|HOSP|LAB|LABFAC|20260101120000||ADT^A08^ADT_A01|C1|P|2.5.1\r"
            + "EVN|A08|20260101\r"
            + "PID|1||MRN1^^^HOSP^MR~ALT^^^X^MR||DOE^JANE||19800101|F\r"
            + "NK1|1|DOE^JOHN|SPO\r"
            + "NK1|2|DOE^JIM|CHD\r"
            + "PV1|1|O\r";

    @Test
    void mutableMessageReadsAndWritesFields() {
        MutableMessage m = MutableMessage.parse(ADT.replace('\r', '\n'));
        assertThat(m.get("PID-5.2")).isEqualTo("JANE");
        assertThat(m.type()).isEqualTo("ADT^A08");
        m.set("PID-5.1", "ROE");
        m.set("PID-3[2].1", "NEW");
        m.set("PID-11.3", "LEEDS");                    // beyond the last field: padded
        m.set("MSH-6", "TESTFAC");
        m.set("PV1-3.4.2", "SUB");
        m.set("ZPI-2", "added");                       // new segment
        assertThat(m.get("PID-5")).isEqualTo("ROE^JANE");
        assertThat(m.get("PID-3")).isEqualTo("MRN1^^^HOSP^MR~NEW^^^X^MR");
        assertThat(m.get("PID-11")).isEqualTo("^^LEEDS");
        assertThat(m.text()).contains("|19800101|F|||^^LEEDS\r").contains("|LAB|TESTFAC|").endsWith("ZPI||added\r");
        assertThat(m.get("PV1-3")).isEqualTo("^^^&SUB");
        assertThat(m.count("NK1")).isEqualTo(2);
        assertThat(m.remove("nk1")).isEqualTo(2);
        assertThat(m.text()).doesNotContain("NK1");
        m.add("ZHS|1|x");
        assertThat(m.text()).endsWith("ZHS|1|x\r");
        assertThatThrownBy(() -> m.set("MSH-2", "x")).hasMessageContaining("delimiters");
        assertThatThrownBy(() -> m.remove("MSH")).hasMessageContaining("cannot be removed");
        assertThatThrownBy(() -> m.add("not a segment")).hasMessageContaining("not a segment");
        assertThatThrownBy(() -> m.set("OBX(3)-5", "x")).hasMessageContaining("no OBX segment number 2");
    }

    @Test
    void scriptChangesTheMessage() {
        MessageScript s = MessageScript.compile("""
                msg.set('MSH-6', 'TESTFAC');
                msg.remove('NK1');
                var mrn = msg.get('PID-3.1');
                msg.add('ZHS|1|' + mrn.toLowerCase());
                log('moved ' + msg.type() + ' for ' + mrn + ', ' + msg.count('PID') + ' PID');
                """);
        MessageScript.Result r = s.apply(ADT);
        assertThat(r.filtered()).isFalse();
        assertThat(r.message()).contains("|LAB|TESTFAC|").doesNotContain("NK1").endsWith("ZHS|1|mrn1\r");
        assertThat(r.log()).containsExactly("moved ADT^A08 for MRN1, 1 PID");
    }

    @Test
    void filterDropsTheMessage() {
        MessageScript s = MessageScript.compile(
                "if (msg.get('PV1-2') == 'O') { filter('outpatient'); } else { msg.set('PV1-3', 'WARD'); }");
        MessageScript.Result out = s.apply(ADT);
        assertThat(out.filtered()).isTrue();
        assertThat(out.filterReason()).isEqualTo("outpatient");
        assertThat(out.message()).isEqualTo(ADT);
        MessageScript.Result in = s.apply(ADT.replace("PV1|1|O", "PV1|1|I"));
        assertThat(in.filtered()).isFalse();
        assertThat(in.message()).contains("PV1|1|I|WARD");
    }

    @Test
    void errorsAreReportedWithLineNumbers() {
        assertThatThrownBy(() -> MessageScript.compile("msg.set('PID-5',\n'x'"))
                .isInstanceOf(MessageScript.ScriptFailure.class).hasMessageContaining("Syntax error on line");
        assertThatThrownBy(() -> MessageScript.compile("\nthrow new Error('boom')").apply(ADT))
                .isInstanceOf(MessageScript.ScriptFailure.class).hasMessageContaining("line 2")
                .hasMessageContaining("boom");
        assertThatThrownBy(() -> MessageScript.compile("msg.set('PID3', 'x')").apply(ADT))
                .isInstanceOf(MessageScript.ScriptFailure.class).hasMessageContaining("line 1")
                .hasMessageContaining("not a field");
        // Scripts can catch these errors themselves.
        assertThat(MessageScript.compile("try { msg.get('bad') } catch (e) { log('caught: ' + e.message) }")
                .apply(ADT).log()).singleElement().asString()
                .startsWith("caught: 'bad' is not a field");
        assertThatThrownBy(() -> MessageScript.compile("msg.set('A', 'B')").apply("not hl7"))
                .hasMessageContaining("cannot be transformed");
    }

    @Test
    void sandboxHasNoJavaAccessAndStopsEndlessLoops() {
        for (String escape : new String[] {"java.lang.System.exit(1)", "Packages.java.io.File",
            "new java.io.File('/tmp/x')", "JavaImporter", "msg.getClass()", "log.getClass().forName('x')"}) {
            assertThatThrownBy(() -> MessageScript.compile(escape).apply(ADT)).as(escape)
                    .isInstanceOf(MessageScript.ScriptFailure.class);
        }
        long start = System.nanoTime();
        assertThatThrownBy(() -> MessageScript.compile("while (true) { }").apply(ADT))
                .hasMessageContaining("was stopped");
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5_000);
        // Catching does not help: the timeout is not a JavaScript exception.
        assertThatThrownBy(() -> MessageScript.compile("while (true) { try { while (true) {} } catch (e) {} }")
                .apply(ADT)).hasMessageContaining("was stopped");
        // Globals do not leak from one message to the next.
        MessageScript counter = MessageScript.compile("var n = (typeof n == 'undefined') ? 1 : n + 1; log(n);");
        assertThat(counter.apply(ADT).log()).containsExactly("1");
        assertThat(counter.apply(ADT).log()).containsExactly("1");
    }

    @Test
    void compiledScriptsAreCachedAndThreadSafe() throws Exception {
        MessageScript s = MessageScript.cached("msg.set('PID-5.1', msg.get('PID-5.1') + '!')");
        assertThat(MessageScript.cached("msg.set('PID-5.1', msg.get('PID-5.1') + '!')")).isSameAs(s);
        java.util.List<Thread> threads = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger ok = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < 8; i++) {
            threads.add(Thread.ofPlatform().start(() -> {
                for (int j = 0; j < 50; j++) {
                    if (s.apply(ADT).message().contains("|DOE!^JANE|")) {
                        ok.incrementAndGet();
                    }
                }
            }));
        }
        for (Thread t : threads) {
            t.join();
        }
        assertThat(ok.get()).isEqualTo(400);
    }
}
