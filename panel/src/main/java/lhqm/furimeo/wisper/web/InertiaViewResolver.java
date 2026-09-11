package lhqm.furimeo.wisper.web;

import java.util.Locale;

import org.springframework.core.Ordered;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.ViewResolver;

import tools.jackson.databind.ObjectMapper;

/**
 * Turns every view name a controller returns into an Inertia page.
 *
 * <p>Resolving at the view layer rather than making every controller aware of Inertia is
 * the point: {@code return "deploy/DeploymentList"} keeps working, and so does every
 * {@code redirect:}, every flash attribute, and every permission check that ran before
 * the return statement.
 *
 * <p>The view name is the React component name, and it maps to a file by convention:
 * {@code deploy/DeploymentList} loads
 * {@code frontend/src/features/deploy/DeploymentListPage.tsx}. Feature first, page
 * second, and the same word on both sides - so there is no routing table to fall out of
 * sync, and the controller still decides what renders.
 */
public class InertiaViewResolver implements ViewResolver, Ordered {

    private final ObjectMapper objectMapper;
    private final ViteManifest manifest;
    private final ViteDevServer devServer;
    private final SharedProps sharedProps;

    public InertiaViewResolver(ObjectMapper objectMapper, ViteManifest manifest,
                               ViteDevServer devServer, SharedProps sharedProps) {
        this.objectMapper = objectMapper;
        this.manifest = manifest;
        this.devServer = devServer;
        this.sharedProps = sharedProps;
    }

    @Override
    public View resolveViewName(String viewName, Locale locale) {
        // redirect: and forward: belong to Spring's own resolver, which is ordered ahead
        // of this one and would never get a chance if this answered for everything.
        if (viewName == null || viewName.startsWith("redirect:") || viewName.startsWith("forward:")) {
            return null;
        }
        return new InertiaPage(viewName, objectMapper, manifest, devServer, sharedProps);
    }

    /**
     * Last. Spring's redirect and forward handling gets first refusal; this resolver
     * then answers for everything else rather than letting a view name fall through to
     * a 404 that says nothing about why.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
