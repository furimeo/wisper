package lhqm.furimeo.wisper.auth;

import java.util.UUID;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Changes the language an account reads the panel in.
 *
 * <p>The principal in the security context is replaced as well as the row, because
 * everything that renders a page - the shared props, the message source, a flash written
 * by the very next redirect - reads the language off the principal. Without that, the
 * choice would only take effect on the second page load, which reads as the setting not
 * having worked.
 *
 * <p>Audited like any other change to an account. It is not a security decision, but "why
 * is this operator's panel in a language they cannot read" is a real support question and
 * the trail is where it gets answered.
 */
@Component
public class ChooseLanguage {

    private final AccountRepository accounts;
    private final AuditTrail audit;

    public ChooseLanguage(AccountRepository accounts, AuditTrail audit) {
        this.accounts = accounts;
        this.audit = audit;
    }

    /**
     * @throws NotFoundException        if the account has been deleted mid-session
     * @throws IllegalArgumentException if the panel has no catalogue for that language
     */
    @Transactional
    public SupportedLocale run(UUID accountId, String tag, AuditActor actor) {
        if (!SupportedLocale.isSupported(tag)) {
            throw new IllegalArgumentException("wisper is not translated into " + tag);
        }
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> NotFoundException.of("account", accountId));
        Account saved = accounts.save(account.withLocale(tag));

        refreshPrincipal(saved);

        SupportedLocale chosen = SupportedLocale.of(tag);
        audit.record(AuditEntry.succeeded(actor, "account.language_change",
                AuditTarget.of("account", saved.id(), saved.email()), null,
                "Now reading the panel in " + chosen.nativeName()));
        return chosen;
    }

    /**
     * Puts the new language on the principal this request is already carrying.
     *
     * <p>The authentication object is immutable, so this is a replacement rather than a
     * mutation - same credentials, same authorities, new principal. Anything else would
     * mean re-authenticating, which would end the session over a preference.
     */
    private static void refreshPrincipal(Account account) {
        var context = SecurityContextHolder.getContext();
        var current = context.getAuthentication();
        if (current == null || !(current.getPrincipal() instanceof SignedInAccount)) {
            return;
        }
        var refreshed = new org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken(
                SignedInAccount.of(account), current.getCredentials(), current.getAuthorities());
        refreshed.setDetails(current.getDetails());
        context.setAuthentication(refreshed);
    }
}
