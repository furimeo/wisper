package lhqm.furimeo.wisper.auth;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The sign-in page.
 *
 * <p>Only a GET. The POST is Spring Security's {@code UsernamePasswordAuthenticationFilter},
 * mapped to the same URL by {@code SecurityConfig} - a controller method for it would
 * never be reached, because the filter answers before the {@code DispatcherServlet} sees
 * the request.
 *
 * <p>The whole job of this class is turning the query parameters
 * {@link SignInFailureHandler} and {@link SessionGateFilter} redirect with into a sentence
 * a person can act on. They travel in the URL rather than in a flash attribute because
 * both of those run inside the security filter chain, where Spring's {@code FlashMap} is
 * not yet set up.
 */
@Controller
public class SignInController {

    @GetMapping("/login")
    public String signIn(@RequestParam(required = false) String error,
                         @RequestParam(required = false) String ended,
                         @AuthenticationPrincipal SignedInAccount account,
                         HttpServletRequest request,
                         Model model) {

        if (account != null) {
            /*
             * Already signed in. Sending them to the dashboard rather than showing the
             * form again is what a person expects from a bookmarked /login - and if their
             * second factor is still outstanding, SessionGateFilter turns them round at
             * the next request and puts them on the challenge.
             */
            return "redirect:/";
        }

        model.addAttribute("notice", noticeFor(error, ended,
                request.getParameterMap().containsKey("signedout")));
        return "auth/SignIn";
    }

    /**
     * @return {@code {tone, message}} for the banner above the form, or null when the
     *         visitor arrived without anything having gone wrong
     */
    private static Notice noticeFor(String error, String ended, boolean signedOut) {
        if (error != null) {
            return switch (error) {
                // A wrong password and an unknown address say the same thing on purpose.
                case "credentials" -> new Notice("error",
                        "That email address and password do not match.");
                case "locked" -> new Notice("error",
                        "Too many failed attempts. This account is locked for a few minutes; "
                        + "try again shortly.");
                case "suspended" -> new Notice("error",
                        "That account is suspended. Ask the platform operator to reactivate it.");
                case "code" -> new Notice("error",
                        "Too many incorrect codes. Sign in again to get a new challenge.");
                default -> new Notice("error", "Sign in did not work. Try again.");
            };
        }
        if (ended != null) {
            return switch (ended) {
                case "password_changed" -> new Notice("info",
                        "Your password was changed, so every other session was signed out.");
                case "signed_out" -> new Notice("info",
                        "This browser was signed out from somewhere else.");
                case "signed_out_everywhere" -> new Notice("info",
                        "You signed out everywhere from another browser.");
                case "account_suspended" -> new Notice("error",
                        "That account has been suspended.");
                case "admin" -> new Notice("info", "An operator ended your session.");
                default -> new Notice("info", "Your session expired. Sign in again.");
            };
        }
        if (signedOut) {
            return new Notice("success", "You are signed out.");
        }
        return null;
    }

    /**
     * The banner above the form.
     *
     * @param tone {@code error}, {@code info} or {@code success} - the client picks the
     *             colour, so the wording and the styling cannot disagree
     */
    public record Notice(String tone, String message) {
    }
}
