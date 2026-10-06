package io.hl7sender.core.secrets;

/** The secret store could not be read or written. */
public class SecretStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SecretStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
