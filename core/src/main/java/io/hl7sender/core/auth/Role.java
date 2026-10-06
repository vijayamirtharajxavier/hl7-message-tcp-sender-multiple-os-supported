package io.hl7sender.core.auth;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** What a user may do. Each role includes the permissions of the ones below it. */
public enum Role {
    /** Looks at queues, history and logs. */
    VIEWER(EnumSet.of(Permission.VIEW)),
    /** Sends messages and works the queue, but does not change the configuration. */
    OPERATOR(EnumSet.of(Permission.VIEW, Permission.SEND, Permission.MANAGE_QUEUE)),
    /** Everything, including users. */
    ADMIN(EnumSet.allOf(Permission.class));

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public Set<Permission> permissions() {
        return permissions;
    }

    public boolean can(Permission p) {
        return permissions.contains(p);
    }

    /** Parses a role name, ignoring case. */
    public static Role parse(String s) {
        try {
            return valueOf(String.valueOf(s).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown role '" + s + "' (use admin, operator or viewer)", e);
        }
    }
}
