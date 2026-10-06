package io.hl7sender.core.auth;

import java.util.Optional;

/**
 * Who is using the app or CLI, and what they may do. When no users have been added, access control is off and
 * everything is allowed; once the first user exists, everyone must sign in.
 *
 * @param enabled whether access control is on (at least one user exists)
 * @param user    the signed-in user; empty when access control is off
 */
public record Access(boolean enabled, Optional<User> user) {

    /** Access control off: everything is allowed. */
    public static final Access OPEN = new Access(false, Optional.empty());

    public static Access signedIn(User user) {
        return new Access(true, Optional.of(user));
    }

    public boolean can(Permission p) {
        return !enabled || user.map(u -> u.can(p)).orElse(false);
    }

    /** Throws {@link AccessDeniedException} unless the user may do {@code what}. */
    public void require(Permission p, String what) {
        if (!can(p)) {
            throw new AccessDeniedException(user.map(u -> u.username() + " (" + u.role().name().toLowerCase(
                    java.util.Locale.ROOT) + ")").orElse("Nobody") + " may not " + what);
        }
    }

    /** The name recorded in the audit trail, or empty when access control is off. */
    public Optional<String> actor() {
        return user.map(User::username);
    }
}
