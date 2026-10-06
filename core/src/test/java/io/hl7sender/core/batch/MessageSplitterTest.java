package io.hl7sender.core.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessageSplitterTest {

    private static final String A = "MSH|^~\\&|A|||||ADT^A01|1|P|2.5\rPID|1||X";
    private static final String B = "MSH|^~\\&|B|||||ADT^A08|2|P|2.5\rPID|1||Y\rPV1|1|I";

    @Test
    void splitsBackToBackMessagesWithAnyLineEndings() {
        SplitResult r = MessageSplitter.split(A.replace("\r", "\r\n") + "\n\n" + B.replace("\r", "\n"));
        assertThat(r.messages()).containsExactly(A + "\r", B + "\r");
        assertThat(r.warnings()).isEmpty();
        assertThat(r.isBatch()).isFalse();
    }

    @Test
    void unwrapsBatchEnvelopeAndChecksCounts() {
        String batch = "FHS|^~\\&|APP\rBHS|^~\\&|APP\r" + A + "\r" + B + "\rBTS|2\rFTS|1\r";
        SplitResult r = MessageSplitter.split(batch);
        assertThat(r.messages()).containsExactly(A + "\r", B + "\r");
        assertThat(r.warnings()).isEmpty();
        assertThat(r.fileHeader()).isTrue();
        assertThat(r.batches()).isEqualTo(1);
    }

    @Test
    void warnsOnWrongTrailerCounts() {
        String batch = "BHS|^~\\&\r" + A + "\rBTS|5\r";
        SplitResult r = MessageSplitter.split(batch);
        assertThat(r.messages()).hasSize(1);
        assertThat(r.warnings()).singleElement().asString().contains("BTS-1").contains("says 5 but 1");
        assertThat(MessageSplitter.split("BHS|^~\\&\r" + A + "\rBTS|x\r").warnings())
                .singleElement().asString().contains("not a number");
    }

    @Test
    void multipleBatchesInOneFile() {
        String batch = "FHS|^~\\&\rBHS|^~\\&\r" + A + "\rBTS|1\rBHS|^~\\&\r" + B + "\r" + A + "\rBTS|2\rFTS|2\r";
        SplitResult r = MessageSplitter.split(batch);
        assertThat(r.messages()).hasSize(3);
        assertThat(r.batches()).isEqualTo(2);
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void acceptsMllpFramedCaptures() {
        String framed = "\u000B" + A + "\r\u001C\r\u000B" + B + "\r\u001C\r";
        assertThat(MessageSplitter.split(framed).messages()).containsExactly(A + "\r", B + "\r");
    }

    @Test
    void ignoresGarbageBeforeFirstMessageWithWarning() {
        SplitResult r = MessageSplitter.split("﻿some header\nanother\n" + A);
        assertThat(r.messages()).containsExactly(A + "\r");
        assertThat(r.warnings()).singleElement().asString().contains("2 line(s)");
    }

    @Test
    void emptyInput() {
        assertThat(MessageSplitter.split("").messages()).isEmpty();
        assertThat(MessageSplitter.split(null).messages()).isEmpty();
    }

    @Test
    void batchFileRoundTrip() {
        String file = MessageSplitter.toBatchFile(List.of(A, B));
        SplitResult r = MessageSplitter.split(file);
        assertThat(r.messages()).containsExactly(A + "\r", B + "\r");
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void listsAndReadsMessageFiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("b.hl7"), B);
        Files.writeString(dir.resolve("a.TXT"), A);
        Files.writeString(dir.resolve("notes.pdf"), "x");
        Files.writeString(dir.resolve(".hidden.hl7"), A);
        Files.createDirectory(dir.resolve("sub.hl7"));
        assertThat(MessageFiles.list(dir)).extracting(p -> p.getFileName().toString())
                .containsExactly("a.TXT", "b.hl7");
        assertThat(MessageFiles.read(dir.resolve("b.hl7"), StandardCharsets.UTF_8).messages()).hasSize(1);
    }
}
