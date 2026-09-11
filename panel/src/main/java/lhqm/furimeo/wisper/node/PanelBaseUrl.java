package lhqm.furimeo.wisper.node;

import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The panel's own public URL, taken from the request that is asking.
 *
 * <p>The panel has no configured hostname and cannot have one: it sits behind a tunnel,
 * and the address people and nodes reach it on is whatever the tunnel publishes today. The
 * one reliable source is the request itself, read after
 * {@code server.forward-headers-strategy: framework} has applied
 * {@code X-Forwarded-Proto} and {@code X-Forwarded-Host} - without which every URL the
 * panel generates is the tunnel's internal one.
 *
 * <p>Three things need it and all three break in the same way if it is wrong: the install
 * command an operator pastes, the download URL inside the generated script, and the URL a
 * node is told to fetch an upgrade from. A node given {@code http://localhost:8080/dist}
 * cannot upgrade, and the error it reports is a connection refused that names nothing
 * useful.
 */
public final class PanelBaseUrl {

    private PanelBaseUrl() {
    }

    /** {@code https://panel.example}, with no trailing slash and no path. */
    public static String of(HttpServletRequest request) {
        String url = ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath(null)
                .replaceQuery(null)
                .build()
                .toUriString();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
