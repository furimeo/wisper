package lhqm.furimeo.wisper.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/**
 * Who can reach what.
 *
 * <p>Everything is authenticated unless it is on the short list below, because the
 * failure mode of the opposite default is a page nobody remembered to protect. The list
 * is short on purpose and each entry says why it is there.
 *
 * <p>Authentication mechanisms are not configured here - packages add their own filters
 * through {@link HttpSecurityContribution}. This file answers exactly one question, and
 * a reader should be able to answer it without scrolling past a token parser.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Reachable without signing in.
     *
     * <ul>
     * <li>{@code /login/**} - the sign-in page and the second-factor challenge that
     *     follows it, which happens before the session is fully authenticated.</li>
     * <li>{@code /assets/**}, {@code /favicon.ico} - the client bundle and its
     *     stylesheets. The sign-in page needs them to render at all.</li>
     * <li>{@code /error} - Spring forwards here to render a failure. Without it, an
     *     anonymous visitor who hits a bad URL is bounced to the sign-in page instead of
     *     being told the page does not exist.</li>
     * <li>{@code /health} - liveness for whatever runs the jar.</li>
     * <li>{@code /install.sh}, {@code /dist/**} - the node installer and the signed
     *     binary manifest it verifies against (design §7.1). These are fetched by curl
     *     on a machine that has no session and, at that point, no credentials either.
     *     They are public artifacts; the bootstrap token is what grants anything.</li>
     * <li>{@code /webhooks/**} - Git providers post here. They authenticate with the
     *     per-service secret carried in the request, not with a session, and they cannot
     *     be told to send a CSRF token.</li>
     * </ul>
     */
    private static final String[] PUBLIC_PATHS = {
        "/login/**",
        "/assets/**",
        "/favicon.ico",
        "/error",
        "/health",
        "/install.sh",
        "/dist/**",
        "/webhooks/**"
    };

    /**
     * Paths whose callers are not browsers, so the double-submit cookie has nothing to
     * double-submit. Both authenticate per request - an API token, or a webhook secret -
     * which is the property CSRF protection exists to provide for a session cookie.
     */
    private static final String[] CSRF_EXEMPT_PATHS = {
        "/api/**",
        "/webhooks/**"
    };

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectProvider<HttpSecurityContribution> contributions) throws Exception {

        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(PUBLIC_PATHS).permitAll()
                // Nodes, placement, platform settings and the audit log. A customer with
                // a valid session must not see another tenant's infrastructure.
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                // false: honour the page the visitor was trying to reach, if any.
                .defaultSuccessUrl("/", false)
                .failureUrl("/login?error")
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?signedout")
                .invalidateHttpSession(true)
                .deleteCookies("WISPERSESSION")
                .permitAll()
            )
            .csrf(csrf -> csrf
                /*
                 * A cookie, not a hidden form field. There are no server-rendered forms:
                 * the Inertia client reads XSRF-TOKEN and echoes it in X-XSRF-TOKEN,
                 * which is the same double-submit check the hidden field performed.
                 * withHttpOnlyFalse is required - script has to read the cookie - and is
                 * safe, because the token is not a credential on its own.
                 */
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers(CSRF_EXEMPT_PATHS)
            )
            .sessionManagement(session -> session
                // A session id handed out before sign-in must not survive the sign-in
                // that turns it into an authenticated one.
                .sessionFixation(fixation -> fixation.changeSessionId())
            )
            .headers(headers -> headers
                // Sent only over HTTPS, which behind the tunnel means whatever
                // X-Forwarded-Proto says - hence forward-headers-strategy in the config.
                .httpStrictTransportSecurity(hsts -> hsts
                        .includeSubDomains(true)
                        .maxAgeInSeconds(31_536_000))
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.SAME_ORIGIN))
            )
            // After authorization, so the cookie is written for requests that were
            // allowed through and not for the ones that were turned away.
            .addFilterAfter(new EagerCsrfCookieFilter(), AuthorizationFilter.class);

        for (HttpSecurityContribution contribution : contributions.orderedStream().toList()) {
            contribution.apply(http);
        }

        return http.build();
    }
}
