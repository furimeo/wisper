package lhqm.furimeo.wisper.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import tools.jackson.databind.ObjectMapper;

/**
 * Wires the Inertia adapter.
 *
 * <p>The pieces themselves are plain classes with no Spring annotations - they are
 * constructed here so that the order the filters run in is visible in one place instead
 * of being spread across five {@code @Order} annotations that have to be read together
 * to be understood.
 *
 * <p>The order matters:
 * <ol>
 * <li>the dev proxy first, so an asset request never reaches the application at all;</li>
 * <li>the version check next, so a client on an old bundle is sent to reload before any
 *     controller does work whose result it could not render;</li>
 * <li>the redirect fixer last, because it only wraps the response and has to be inside
 *     everything that might write one.</li>
 * </ol>
 */
@Configuration
public class InertiaConfig {

    @Bean
    public ViteManifest viteManifest(ObjectMapper objectMapper) {
        return new ViteManifest(objectMapper);
    }

    @Bean
    public ViteDevServer viteDevServer() {
        return new ViteDevServer();
    }

    @Bean
    public SharedProps sharedProps(ObjectProvider<SharedPropsContributor> contributors) {
        return new SharedProps(contributors);
    }

    @Bean
    public InertiaViewResolver inertiaViewResolver(ObjectMapper objectMapper, ViteManifest manifest,
                                                   ViteDevServer devServer, SharedProps sharedProps) {
        return new InertiaViewResolver(objectMapper, manifest, devServer, sharedProps);
    }

    @Bean
    public FilterRegistrationBean<ViteDevProxyFilter> viteDevProxyFilter(ViteDevServer devServer) {
        return registered(new ViteDevProxyFilter(devServer), Ordered.HIGHEST_PRECEDENCE);
    }

    @Bean
    public FilterRegistrationBean<InertiaVersionFilter> inertiaVersionFilter(ViteManifest manifest) {
        return registered(new InertiaVersionFilter(manifest), Ordered.HIGHEST_PRECEDENCE + 10);
    }

    @Bean
    public FilterRegistrationBean<InertiaRedirectFilter> inertiaRedirectFilter() {
        return registered(new InertiaRedirectFilter(), Ordered.HIGHEST_PRECEDENCE + 20);
    }

    private static <T extends jakarta.servlet.Filter> FilterRegistrationBean<T> registered(T filter, int order) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(order);
        return registration;
    }
}
