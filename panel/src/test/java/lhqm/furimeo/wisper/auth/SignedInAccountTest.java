package lhqm.furimeo.wisper.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * The principal as Spring Security sees it.
 *
 * <p>The first test is the one that matters and it is here because its absence cost a
 * working panel: {@code Authentication.getName()} falls back to {@code toString()} for a
 * principal it does not recognise, so a record principal silently reports
 * {@code SignedInAccount[id=..., email=...]} as its name. Everything that resolves the
 * current account looks that up, finds nothing, and every write in the application fails
 * while every read keeps working.
 */
class SignedInAccountTest {

    private final SignedInAccount account = new SignedInAccount(UUID.randomUUID(),
            "operator@example.com", "Operator", PlatformRole.ADMIN, "en");

    @Test
    void springSecurityReportsTheEmailAsTheName() {
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(account, null, account.authorities());

        assertThat(authentication.getName()).isEqualTo("operator@example.com");
    }

    @Test
    void anOperatorCarriesBothAuthorities() {
        // hasRole("ADMIN") on /admin/** has to fail for a customer, and ROLE_USER has to be
        // present for everybody so a page can require "signed in" without naming a role.
        assertThat(account.authorities())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
        assertThat(account.isPlatformAdmin()).isTrue();
    }

    @Test
    void aCustomerCarriesOnlyTheSignedInAuthority() {
        SignedInAccount customer = new SignedInAccount(UUID.randomUUID(), "someone@example.com",
                "Someone", PlatformRole.CUSTOMER, "vi");

        assertThat(customer.authorities()).extracting(Object::toString)
                .containsExactly("ROLE_USER");
        assertThat(customer.isPlatformAdmin()).isFalse();
    }
}
