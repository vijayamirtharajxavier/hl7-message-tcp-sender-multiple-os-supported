package io.hl7sender.core.auth;

/** The signed-in user (or nobody) may not do this, or signing in failed. */
public final class AccessDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AccessDeniedException(String message) {
        super(message);
    }
}
