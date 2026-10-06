package io.hl7sender.cli;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads message text from a file, or from standard input when the path is {@code -}. */
final class MessageInput {

    private MessageInput() {
    }

    static String read(Path file, Charset charset) throws IOException {
        if (file.toString().equals("-")) {
            return new String(System.in.readAllBytes(), charset);
        }
        return Files.readString(file, charset);
    }
}
