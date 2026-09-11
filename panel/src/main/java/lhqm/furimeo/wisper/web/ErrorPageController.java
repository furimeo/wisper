package lhqm.furimeo.wisper.web;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.error.ErrorAttributeOptions.Include;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.ServletWebRequest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Renders failures as a real page instead of a white frame.
 *
 * <p>Declaring an {@code ErrorController} bean replaces Spring Boot's own, which is the
 * point: its view name is the literal string "error", and a name with no slash does not
 * fit the {@code feature/Page} convention the Inertia resolver uses. This returns
 * {@code error/Error}, so the failure page is an ordinary React page under
 * {@code features/error/} and looks like the rest of the panel.
 *
 * <p>The exception itself is never sent to the browser. A stack trace on a customer's
 * screen is both useless to them and a gift to anyone probing the panel; the status,
 * the path and a short message are all a person can act on.
 */
@Controller
public class ErrorPageController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(ErrorPageController.class);

    private static final String PATH = "/error";

    private final ErrorAttributes errorAttributes;

    public ErrorPageController(ErrorAttributes errorAttributes) {
        this.errorAttributes = errorAttributes;
    }

    @RequestMapping(PATH)
    public String render(HttpServletRequest request, HttpServletResponse response, Model model) {
        ServletWebRequest webRequest = new ServletWebRequest(request);
        Map<String, Object> attributes = errorAttributes.getErrorAttributes(
                webRequest,
                ErrorAttributeOptions.of(Include.MESSAGE, Include.PATH, Include.STATUS));

        HttpStatus status = statusOf(attributes);
        if (status.is5xxServerError()) {
            // The page promises the failure was recorded, so record it. With the stack
            // trace, because this is the only place that still has the throwable.
            log.error("{} while handling {}", status.value(),
                    attributes.getOrDefault("path", request.getRequestURI()),
                    errorAttributes.getError(webRequest));
        }
        model.addAttribute("status", status.value());
        model.addAttribute("reason", status.getReasonPhrase());
        model.addAttribute("message", readableMessage(status, attributes.get("message")));
        model.addAttribute("path", attributes.getOrDefault("path", request.getRequestURI()));
        response.setStatus(status.value());
        return "error/Error";
    }

    private static HttpStatus statusOf(Map<String, Object> attributes) {
        Object status = attributes.get("status");
        if (status instanceof Integer code) {
            HttpStatus resolved = HttpStatus.resolve(code);
            if (resolved != null) {
                return resolved;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /**
     * A sentence a person can act on.
     *
     * <p>Spring's own message is either empty or the exception's, and the exception's is
     * written for whoever wrote the code. Anything that is not a deliberate message from
     * a {@link NotFoundException} or a validation failure gets replaced.
     */
    private static String readableMessage(HttpStatus status, Object raw) {
        String message = raw == null ? "" : raw.toString().strip();
        if (status.is5xxServerError() || message.isEmpty() || "No message available".equals(message)) {
            return switch (status) {
                case NOT_FOUND -> "That page does not exist, or you are not allowed to see it.";
                case FORBIDDEN -> "You do not have access to this.";
                case UNAUTHORIZED -> "Sign in to continue.";
                case PAYLOAD_TOO_LARGE -> "That upload is larger than the panel accepts.";
                default -> "Something went wrong on our side. The failure has been logged.";
            };
        }
        return message;
    }
}
