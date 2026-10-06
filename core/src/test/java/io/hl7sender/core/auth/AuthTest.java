package io.hl7sender.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AuthTest {

    @Test
    void passwordsAreSaltedAndCheckedInConstantForm() {
        String a = PasswordHasher.hash("correct horse", 1_000);
        String b = PasswordHasher.hash("correct horse", 1_000);
        assertThat(a).startsWith("pbkdf2-sha256$1000$").isNotEqualTo(b);
        assertThat(PasswordHasher.verify("correct horse", a)).isTrue();
        assertThat(PasswordHasher.verify("correct horsE", a)).isFalse();
        assertThat(PasswordHasher.verify(null, a)).isFalse();
        for (String bad : new String[] {"", "x", "md5$1$a$b", "pbkdf2-sha256$0$AA$AA", "pbkdf2-sha256$x$AA$AA",
            "pbkdf2-sha256$1000$!!$AA"}) {
            assertThat(PasswordHasher.verify("correct horse", bad)).as(bad).isFalse();
        }
        assertThat(PasswordHasher.hash("correct horse")).contains("$" + PasswordHasher.ITERATIONS + "$");
        assertThatThrownBy(() -> PasswordHasher.hash("short")).hasMessageContaining("at least 8");
    }

    @Test
    void rolesGrantPermissions() {
        assertThat(Role.VIEWER.permissions()).containsExactly(Permission.VIEW);
        assertThat(Role.OPERATOR.can(Permission.SEND)).isTrue();
        assertThat(Role.OPERATOR.can(Permission.MANAGE_QUEUE)).isTrue();
        assertThat(Role.OPERATOR.can(Permission.CONFIGURE)).isFalse();
        assertThat(Role.ADMIN.permissions()).containsExactlyInAnyOrder(Permission.values());
        assertThat(Role.parse(" Operator ")).isEqualTo(Role.OPERATOR);
        assertThatThrownBy(() -> Role.parse("root")).hasMessageContaining("admin, operator or viewer");
    }

    @Test
    void usersAndAccess() {
        User u = User.of(" Alice@Lab ", " Alice ", Role.OPERATOR);
        assertThat(u.username()).isEqualTo("alice@lab");
        assertThat(u.label()).isEqualTo("Alice");
        assertThatThrownBy(() -> User.of("bad name", "", Role.VIEWER)).hasMessageContaining("user name");
        assertThatThrownBy(() -> User.of("-x", "", Role.VIEWER)).hasMessageContaining("user name");
        assertThat(User.of("x", "", Role.ADMIN).withEnabled(false).can(Permission.VIEW)).isFalse();

        assertThat(Access.OPEN.can(Permission.ADMIN_USERS)).isTrue();
        assertThat(Access.OPEN.actor()).isEmpty();
        Access op = Access.signedIn(new User(3, "bob", "", Role.OPERATOR, true, Instant.EPOCH, Optional.empty()));
        assertThat(op.can(Permission.SEND)).isTrue();
        assertThat(op.actor()).contains("bob");
        assertThatThrownBy(() -> op.require(Permission.CONFIGURE, "change destinations"))
                .isInstanceOf(AccessDeniedException.class).hasMessage("bob (operator) may not change destinations");
        assertThat(new Access(true, Optional.empty()).can(Permission.VIEW)).isFalse();
    }
}
