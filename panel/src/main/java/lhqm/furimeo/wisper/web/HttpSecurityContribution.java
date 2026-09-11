package lhqm.furimeo.wisper.web;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * Lets a domain package add to the security filter chain without editing
 * {@link SecurityConfig}.
 *
 * <p>Authentication mechanisms belong to the packages that own them: the API-token
 * filter is part of {@code auth}, and so is the second-factor challenge. Both need a
 * position in the filter chain, which only {@code HttpSecurity} can give them. Without
 * this seam every one of them would be an edit to the same file - the file that also
 * holds every authorization rule, and the one nobody wants to see in a merge conflict.
 *
 * <p>Declare a {@code @Component} implementing this in your own package. Contributions
 * are applied in {@code @Order} order, after the rules in {@link SecurityConfig} and
 * before {@code http.build()}.
 *
 * <p>What belongs here: filters, authentication providers, exception handling for a
 * specific path prefix. What does not: the request-to-role map. That stays in
 * {@link SecurityConfig}, because a reader has to be able to answer "who can reach this
 * URL" by opening one file.
 */
public interface HttpSecurityContribution {

    void apply(HttpSecurity http) throws Exception;
}
