package io.hl7sender.core.auth;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A person (or service account) who signs in to the app or the CLI. The password hash is never part of this record.
 *
 * @param id          database ID, 0 for a new user
 * @param username    unique sign-in name, stored in lower case
 * @param displayName shown in the app, may be empty
 * @param role        what the user may do
 * @param enabled     disabled users cannot sign in
 * @param createdAt   when the user was added
 * @param lastLoginAt the last successful sign-in, if any
 */
public record User(long id, String username, String displayName, Role role, boolean enabled, Instant createdAt,
                   Optional<Instant> lastLoginAt) {

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._@-]{0,63}");

    public User {
        username = normalize(username);
        displayName = displayName == null ? "" : displayName.trim();
        if (role == null) {
            throw new IllegalArgumentException("A user needs a role");
        }
        lastLoginAt = lastLoginAt == null ? Optional.empty() : lastLoginAt;
    }

    /** A new, enabled user. */
    public static User of(String username, String displayName, Role role) {
        return new User(0, username, displayName, role, true, Instant.EPOCH, Optional.empty());
    }

    /** Lower-cases and checks a user name: letters, digits and {@code . _ @ -}, at most 64 characters. */
    public static String normalize(String username) {
        String u = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!NAME.matcher(u).matches()) {
            throw new IllegalArgumentException("A user name has 1-64 letters, digits, '.', '_', '@' or '-', "
                    + "and starts with a letter or digit");
        }
        return u;
    }

    public boolean can(Permission p) {
        return enabled && role.can(p);
    }

    /** The display name, or the user name if there is none. */
    public String label() {
        return displayName.isEmpty() ? username : displayName;
    }

    public User withRole(Role r) {
        return new User(id, username, displayName, r, enabled, createdAt, lastLoginAt);
    }

    public User withDisplayName(String name) {
        return new User(id, username, name, role, enabled, createdAt, lastLoginAt);
    }

    public User withEnabled(boolean e) {
        return new User(id, username, displayName, role, e, createdAt, lastLoginAt);
    }
}
