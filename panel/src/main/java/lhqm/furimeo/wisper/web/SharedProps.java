package lhqm.furimeo.wisper.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.support.RequestContextUtils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Collects the props every page gets: validation errors and flash messages carried
 * across a redirect, plus whatever the {@link SharedPropsContributor} beans add.
 *
 * <p>{@code errors} and {@code flash} are always present, even when empty. A page that
 * has to write {@code props.errors?.name} in one place and {@code props.errors.name} in
 * another eventually gets it wrong in the place nobody tested.
 */
public class SharedProps {

    /** Flash attribute holding field name to message, written by {@link InertiaFlash}. */
    static final String ERRORS = "errors";

    /** Flash attribute holding one-off notices, written by {@link InertiaFlash}. */
    static final String FLASH = "flash";

    private final ObjectProvider<SharedPropsContributor> contributors;

    public SharedProps(ObjectProvider<SharedPropsContributor> contributors) {
        this.contributors = contributors;
    }

    Map<String, Object> forRequest(HttpServletRequest request) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(ERRORS, flashValue(request, ERRORS));
        props.put(FLASH, flashValue(request, FLASH));
        contributors.orderedStream().forEach(contributor -> contributor.contribute(props, request));
        return props;
    }

    /**
     * Reads one flash attribute written by the request that redirected here.
     *
     * <p>Spring's flash map survives exactly one redirect, which is the lifetime a
     * "saved" banner should have. An empty map rather than null so the client can index
     * into it unconditionally.
     */
    private static Map<String, Object> flashValue(HttpServletRequest request, String key) {
        Map<String, ?> input = RequestContextUtils.getInputFlashMap(request);
        if (input == null) {
            return Map.of();
        }
        Object value = input.get(key);
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((name, message) -> copy.put(String.valueOf(name), message));
        return copy;
    }
}
