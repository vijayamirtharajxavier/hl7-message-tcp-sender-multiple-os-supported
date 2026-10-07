package io.hl7sender.core.listener;

import io.hl7sender.core.mllp.Mllp;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Runtime behaviour of the {@link TestListener}. Changes take effect for the next message received.
 *
 * @param mode          how to respond to messages that match no rule
 * @param delayMs       wait this long before responding (useful for testing sender timeouts)
 * @param commitCodes   respond with enhanced-mode CA/CE/CR instead of AA/AE/AR
 * @param responseText  optional MSA-3 text; for errors it is also used as the ERR description
 * @param charset       character set used to decode messages and encode responses
 * @param maxFrameBytes largest inbound frame that is accepted
 * @param rules         responder rules, checked in order before the default response; the first match wins
 * @param saveFolder    folder to save every received message to, one file each, or {@code null}
 * @param appAck        in enhanced mode, also send an application ACK back to the sender later, or {@code null}
 */
public record ListenerSettings(
        ResponseMode mode,
        int delayMs,
        boolean commitCodes,
        String responseText,
        Charset charset,
        int maxFrameBytes,
        List<ResponseRule> rules,
        Path saveFolder,
        AppAck appAck) {

    /**
     * After answering a message with CA, send an application ACK as a new connection to the sender's address on
     * {@code port}, as a receiver in enhanced mode does when the message's MSH-16 asks for one: always for AL,
     * only an AE or AR for ER, and only an AA for SU.
     *
     * @param port    the sender's application ACK port
     * @param code    AA, AE or AR
     * @param delayMs wait this long after the commit ACK
     */
    public record AppAck(int port, io.hl7sender.core.ack.AckCode code, int delayMs) {
        public AppAck {
            Objects.requireNonNull(code, "code");
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("Application ACK port must be between 1 and 65535");
            }
            if (code.isCommit()) {
                throw new IllegalArgumentException("An application ACK is AA, AE or AR");
            }
            if (delayMs < 0) {
                throw new IllegalArgumentException("delayMs cannot be negative");
            }
        }
    }

    public static final ListenerSettings DEFAULTS = new ListenerSettings(
            ResponseMode.ACCEPT, 0, false, "", StandardCharsets.UTF_8, Mllp.DEFAULT_MAX_FRAME_BYTES);

    public ListenerSettings {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(charset, "charset");
        responseText = responseText == null ? "" : responseText;
        rules = rules == null ? List.of() : List.copyOf(rules);
        if (delayMs < 0) {
            throw new IllegalArgumentException("delayMs cannot be negative");
        }
        if (mode == ResponseMode.CUSTOM) {
            throw new IllegalArgumentException("A custom response is set per rule; choose another default response");
        }
    }

    /** Settings without rules or a save folder. */
    public ListenerSettings(ResponseMode mode, int delayMs, boolean commitCodes, String responseText, Charset charset,
                            int maxFrameBytes) {
        this(mode, delayMs, commitCodes, responseText, charset, maxFrameBytes, List.of(), null);
    }

    /** Settings without application ACKs. */
    public ListenerSettings(ResponseMode mode, int delayMs, boolean commitCodes, String responseText, Charset charset,
                            int maxFrameBytes, List<ResponseRule> rules, Path saveFolder) {
        this(mode, delayMs, commitCodes, responseText, charset, maxFrameBytes, rules, saveFolder, null);
    }

    public ListenerSettings withAppAck(AppAck newAppAck) {
        return new ListenerSettings(mode, delayMs, commitCodes, responseText, charset, maxFrameBytes, rules,
                saveFolder, newAppAck);
    }

    public ListenerSettings withMode(ResponseMode newMode) {
        return new ListenerSettings(newMode, delayMs, commitCodes, responseText, charset, maxFrameBytes, rules,
                saveFolder, appAck);
    }

    public ListenerSettings withCommitCodes(boolean newCommitCodes) {
        return new ListenerSettings(mode, delayMs, newCommitCodes, responseText, charset, maxFrameBytes, rules,
                saveFolder, appAck);
    }

    public ListenerSettings withDelayMs(int newDelayMs) {
        return new ListenerSettings(mode, newDelayMs, commitCodes, responseText, charset, maxFrameBytes, rules,
                saveFolder, appAck);
    }

    public ListenerSettings withRules(List<ResponseRule> newRules) {
        return new ListenerSettings(mode, delayMs, commitCodes, responseText, charset, maxFrameBytes, newRules,
                saveFolder, appAck);
    }

    public ListenerSettings withSaveFolder(Path folder) {
        return new ListenerSettings(mode, delayMs, commitCodes, responseText, charset, maxFrameBytes, rules, folder,
                appAck);
    }
}
