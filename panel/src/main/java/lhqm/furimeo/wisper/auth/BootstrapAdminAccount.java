package lhqm.furimeo.wisper.auth;

import java.security.SecureRandom;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.audit.AuditActor;

/**
 * Creates the first operator account on a database that has nobody in it.
 *
 * <p>Without this the panel is unreachable: there is no public sign-up (see
 * {@link RegisterUser}), accounts are created from {@code /admin/accounts}, and
 * {@code /admin/**} needs an admin to sign in first. Somebody has to be able to get in,
 * and the alternative to doing it here is a documented {@code INSERT} with a BCrypt hash
 * in it, which is a worse thing to ask an operator to paste into a terminal.
 *
 * <p>The password is generated, printed once at {@code WARN}, and never stored anywhere
 * but the hash. It is not read from configuration: a password in {@code application.yml}
 * is a password in the repository, and one in an environment variable is a password in
 * {@code /proc}. The operator signs in with what the log printed and changes it.
 *
 * <p>Runs only when {@code account} is empty. An {@code ApplicationRunner} rather than a
 * {@code @PostConstruct}, so the migrations have certainly finished - runners execute
 * after {@code ContextRefreshedEvent} (docs/contracts/panel-configuration.md).
 */
@Component
public class BootstrapAdminAccount implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminAccount.class);

    /** 192 bits, base64url: long enough that leaving it unchanged is not a hole. */
    private static final int PASSWORD_BYTES = 24;

    private final AccountRepository accounts;
    private final RegisterUser registerUser;
    private final AuthSettings settings;

    public BootstrapAdminAccount(AccountRepository accounts, RegisterUser registerUser,
                                 AuthSettings settings) {
        this.accounts = accounts;
        this.registerUser = registerUser;
        this.settings = settings;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (accounts.count() > 0) {
            return;
        }

        byte[] entropy = new byte[PASSWORD_BYTES];
        new SecureRandom().nextBytes(entropy);
        String password = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);

        Account created = registerUser.run(settings.bootstrapAdminEmail(), "Platform operator",
                password, PlatformRole.ADMIN, AuditActor.system("bootstrap"));

        log.warn("""

                ----------------------------------------------------------------
                 No accounts existed, so wisper created the first operator.

                   email:    {}
                   password: {}

                 This is printed once. Sign in, change the password, and turn on
                 two-factor authentication in /settings/security.
                ----------------------------------------------------------------
                """, created.email(), password);
    }
}
