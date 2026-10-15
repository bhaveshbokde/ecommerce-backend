package com.bhavesh.ecommerce.user.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

/** The user entity and its role invariants. */
class UserTest {

    /**
     * The structural guarantee behind the whole privilege-escalation story: there
     * is no parameter on this factory through which a registration could request a
     * different role. A validation annotation could be relaxed; a missing parameter
     * cannot.
     */
    @Test
    @DisplayName("registerCustomer has no role parameter, so escalation has nowhere to enter")
    void registerCustomerTakesNoRole() {
        Method factory = java.util.Arrays.stream(User.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("registerCustomer"))
                .findFirst()
                .orElseThrow();

        assertThat(factory.getParameterTypes())
                .as("adding a Role parameter here would be the escalation bug")
                .containsExactly(String.class, String.class, String.class);
    }

    @Test
    @DisplayName("a self-registered account is always ROLE_CUSTOMER")
    void registersAsCustomer() {
        User user = User.registerCustomer("priya@example.com", "$2a$12$hash", "Priya Sharma");

        assertThat(user.getRole()).isEqualTo(Role.ROLE_CUSTOMER);
        assertThat(user.isAdmin()).isFalse();
        assertThat(user.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("createAdmin is the only path to ROLE_ADMIN")
    void createAdmin() {
        assertThat(User.createAdmin("admin@example.com", "$2a$12$hash", "Administrator").isAdmin())
                .isTrue();
    }

    @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
    @CsvSource({
            "Priya@Example.com,   priya@example.com",
            "  PRIYA@EXAMPLE.COM, priya@example.com",
            "priya@example.com,   priya@example.com",
    })
    @DisplayName("the address is canonicalised to match uq_users_email_lower")
    void normalisesEmail(String input, String expected) {
        assertThat(User.normalizeEmail(input)).isEqualTo(expected);
        assertThat(User.registerCustomer(input, "$2a$12$hash", "Name").getEmail())
                .isEqualTo(expected);
    }

    @Test
    void nullEmailNormalisesToNull() {
        assertThat(User.normalizeEmail(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_CUSTOMER", "ROLE_ADMIN"})
    @DisplayName("the authority string matches the stored value, so no translation can drift")
    void authorityMatchesTheStoredName(String name) {
        Role role = Role.valueOf(name);
        assertThat(role.authority()).isEqualTo(name);
        assertThat(role.shortName()).isEqualTo(name.substring("ROLE_".length()));
    }

    @Test
    @DisplayName("toString never includes the password hash")
    void toStringOmitsTheHash() {
        User user = User.registerCustomer("priya@example.com", "$2a$12$averysecrethash", "Priya");

        assertThat(user.toString())
                .contains("priya@example.com")
                .doesNotContain("$2a$")
                .doesNotContain("averysecrethash");
    }

    /**
     * The standard JPA identity recipe. Two unsaved entities must not be equal, or
     * a {@code HashSet} of them would collapse into one; and the hash code must not
     * change when the id is assigned, or an entity already in a set would become
     * unfindable the moment it was saved.
     */
    @Test
    @DisplayName("two transient users are never equal")
    void transientUsersAreDistinct() {
        User a = User.registerCustomer("a@example.com", "$2a$12$h", "A");
        User b = User.registerCustomer("a@example.com", "$2a$12$h", "A");

        assertThat(a).isNotEqualTo(b);
        assertThat(new HashSet<>(java.util.List.of(a, b))).hasSize(2);
    }

    @Test
    @DisplayName("the hash code survives the transition from transient to persistent")
    void hashCodeIsStableAcrossPersist() {
        User user = User.registerCustomer("a@example.com", "$2a$12$h", "A");
        var set = new HashSet<User>();
        set.add(user);

        ReflectionTestUtils.setField(user, "id", 7L);

        assertThat(set).as("the entity must still be findable after it is saved").contains(user);
    }

    @Test
    @DisplayName("two persistent users with the same id are equal")
    void sameIdMeansEqual() {
        User a = User.registerCustomer("a@example.com", "$2a$12$h", "A");
        User b = User.registerCustomer("b@example.com", "$2a$12$h", "B");
        ReflectionTestUtils.setField(a, "id", 7L);
        ReflectionTestUtils.setField(b, "id", 7L);

        assertThat(a).isEqualTo(b);
    }
}
