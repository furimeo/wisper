package lhqm.furimeo.wisper.web;

import java.time.Duration;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Where static files come from.
 *
 * <p>Everything the browser loads sits under {@code /assets/}, and the compiled client
 * under {@code /assets/app/} - which is what {@code base} in vite.config.ts is set to.
 * Keeping the prefix means no application route can ever be shadowed by a file, and no
 * file by a route.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        /*
         * The compiled client. Vite puts a content hash in every filename it emits, so
         * the contents behind one of these URLs never change - and being wrong is
         * impossible, because a new build produces new names and the Inertia version
         * check forces a reload for whoever has not noticed yet.
         *
         * Registered first: the more specific pattern has to win.
         */
        registry.addResourceHandler("/assets/app/**")
                .addResourceLocations("classpath:/static/app/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());

        // Hand-placed files - icons, the sign-in illustration. No hash in the name, so
        // an hour is as long as a stale copy may live.
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic());
    }
}
