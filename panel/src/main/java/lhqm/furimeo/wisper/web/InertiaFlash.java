package lhqm.furimeo.wisper.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Writes the two flash props every form in the panel relies on.
 *
 * <p>A form submission that fails validation redirects back to the form; the client
 * re-renders and reads {@code errors}. A submission that succeeds redirects to the
 * result page and the client reads {@code flash.success}. Both are shared props (see
 * {@link SharedProps}), so no controller has to remember to put them in its model.
 *
 * <p>Field names are used verbatim, so they match the {@code name} attribute of the
 * input that produced them and the page can put the message under the right field
 * without a translation table.
 */
public final class InertiaFlash {

    private InertiaFlash() {
    }

    /** A one-line confirmation for the page being redirected to. */
    public static void success(RedirectAttributes attributes, String message) {
        notice(attributes, "success", message);
    }

    /** A one-line failure notice for something that is not a specific field's fault. */
    public static void failure(RedirectAttributes attributes, String message) {
        notice(attributes, "error", message);
    }

    /** Field errors from Bean Validation, ready for the form to render inline. */
    public static void errors(RedirectAttributes attributes, BindingResult result) {
        Map<String, String> messages = new LinkedHashMap<>();
        for (FieldError error : result.getFieldErrors()) {
            // The first message per field. A field with three failing constraints does
            // not need three lines of red text under one input.
            messages.putIfAbsent(error.getField(), messageOf(error));
        }
        result.getGlobalErrors().forEach(error -> {
            String message = error.getDefaultMessage();
            // Map.copyOf below rejects a null value, and a constraint with no message
            // attached is not worth an exception on the error path.
            messages.putIfAbsent(error.getObjectName(), message != null ? message : "is not valid");
        });
        errors(attributes, messages);
    }

    /** Field errors the controller worked out itself, such as "that name is taken". */
    public static void errors(RedirectAttributes attributes, Map<String, String> fieldMessages) {
        attributes.addFlashAttribute(SharedProps.ERRORS, Map.copyOf(fieldMessages));
    }

    private static void notice(RedirectAttributes attributes, String key, String message) {
        Map<String, Object> flash = new LinkedHashMap<>();
        Object existing = attributes.getFlashAttributes().get(SharedProps.FLASH);
        if (existing instanceof Map<?, ?> previous) {
            previous.forEach((name, value) -> flash.put(String.valueOf(name), value));
        }
        flash.put(key, message);
        attributes.addFlashAttribute(SharedProps.FLASH, Map.copyOf(flash));
    }

    private static String messageOf(FieldError error) {
        String message = error.getDefaultMessage();
        return message != null ? message : error.getField() + " is not valid";
    }
}
