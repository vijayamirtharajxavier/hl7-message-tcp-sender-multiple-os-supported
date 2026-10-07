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
 */
public record ListenerSettings(
        ResponseMode mode,
        int delayMs,
        boolean commitCodes,
        String responseText,
        Charset charset,
        int maxFrameBytes,
        List<ResponseRule> rules,
        Path saveFolder) {

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

    public ListenerSettings withMode(ResponseMode newMode) {
        return new ListenerSettings(newMode, delayMs, commitCodes, responseText, charset, maxFrameBytes, rules,
                saveFolder);
    }

    public ListenerSettings withCommitCodes(boolean newCommitCodes) {
        return new ListenerSettings(mode, delayMs, newCommitCodes, responseText, charset, maxFrameBytes, rules,
                saveFolder);
    }

    public ListenerSettings withDelayMs(int newDelayMs) {
        return new ListenerSettings(mode, newDelayMs, commitCodes, responseText, charset, maxFrameBytes, rules,
                saveFolder);
    }

    public ListenerSettings withRules(List<ResponseRule> newRules) {
        return new ListenerSettings(mode, delayMs, commitCodes, responseText, charset, maxFrameBytes, newRules,
                saveFolder);
    }

    public ListenerSettings withSaveFolder(Path folder) {
        return new ListenerSettings(mode, delayMs, commitCodes, responseText, charset, maxFrameBytes, rules, folder);
    }
}
