package io.hl7sender.core.mllp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MllpFrameReaderTest {

    private static byte[] bytes(Object... parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Object p : parts) {
            if (p instanceof String s) {
                out.write(s.getBytes(StandardCharsets.UTF_8));
            } else {
                out.write(((Number) p).intValue());
            }
        }
        return out.toByteArray();
    }

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    @Test
    void framesAndUnframesMessage() throws IOException {
        byte[] framed = Mllp.frame("MSH|x\r", StandardCharsets.UTF_8);
        assertThat(framed[0]).isEqualTo(Mllp.START_BLOCK);
        assertThat(framed[framed.length - 2]).isEqualTo(Mllp.END_BLOCK);
        assertThat(framed[framed.length - 1]).isEqualTo(Mllp.CARRIAGE_RETURN);
        MllpFrameReader reader = new MllpFrameReader(new ByteArrayInputStream(framed));
        assertThat(str(reader.readFrame())).isEqualTo("MSH|x\r");
        assertThat(reader.readFrame()).isNull();
    }

    @Test
    void readsFrameDeliveredOneByteAtATime() throws IOException {
        byte[] framed = Mllp.frame("MSH|slow\rPID|1\r", StandardCharsets.UTF_8);
        InputStream trickle = new ByteArrayInputStream(framed) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(1, len));
            }
        };
        MllpFrameReader reader = new MllpFrameReader(trickle);
        assertThat(str(reader.readFrame())).isEqualTo("MSH|slow\rPID|1\r");
        assertThat(reader.readFrame()).isNull();
        assertThat(reader.discardedBytes()).isZero();
    }

    @Test
    void readsSeveralFramesFromOneBuffer() throws IOException {
        byte[] data = bytes(0x0B, "ONE", 0x1C, 0x0D, 0x0B, "TWO", 0x1C, 0x0D);
        MllpFrameReader reader = new MllpFrameReader(new ByteArrayInputStream(data));
        assertThat(str(reader.readFrame())).isEqualTo("ONE");
        assertThat(str(reader.readFrame())).isEqualTo("TWO");
        assertThat(reader.readFrame()).isNull();
    }

    @Test
    void skipsBytesOutsideFrames() throws IOException {
        byte[] data = bytes("junk\r\n", 0x0B, "MSG", 0x1C, 0x0D);
        MllpFrameReader reader = new MllpFrameReader(new ByteArrayInputStream(data));
        assertThat(str(reader.readFrame())).isEqualTo("MSG");
        assertThat(reader.discardedBytes()).isEqualTo(6);
    }

    @Test
    void toleratesEndBlockWithoutCarriageReturn() throws IOException {
        byte[] data = bytes(0x0B, "ONE", 0x1C, 0x0B, "TWO", 0x1C);
        MllpFrameReader reader = new MllpFrameReader(new ByteArrayInputStream(data));
        assertThat(str(reader.readFrame())).isEqualTo("ONE");
        assertThat(str(reader.readFrame())).isEqualTo("TWO");
    }

    @Test
    void failsWhenStreamEndsInsideFrame() {
        byte[] data = bytes0x0B("PARTIAL");
        MllpFrameReader reader = new MllpFrameReader(new ByteArrayInputStream(data));
        assertThatThrownBy(reader::readFrame)
                .isInstanceOf(MllpProtocolException.class)
                .hasMessageContaining("closed in the middle");
    }

    @Test
    void enforcesMaximumFrameSize() throws IOException {
        byte[] data = bytes(0x0B, "0123456789", 0x1C, 0x0D);
        MllpFrameReader reader = new MllpFrameReader(new ByteArrayInputStream(data), 5);
        assertThatThrownBy(reader::readFrame)
                .isInstanceOf(MllpProtocolException.class)
                .hasMessageContaining("maximum size");
    }

    @Test
    void preservesNonAsciiBytes() throws IOException {
        String text = "PID|1||||MÜLLER^JOSÉ";
        MllpFrameReader reader = new MllpFrameReader(
                new ByteArrayInputStream(Mllp.frame(text, StandardCharsets.UTF_8)));
        assertThat(str(reader.readFrame())).isEqualTo(text);
    }

    private static byte[] bytes0x0B(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[b.length + 1];
        out[0] = Mllp.START_BLOCK;
        System.arraycopy(b, 0, out, 1, b.length);
        return out;
    }
}
