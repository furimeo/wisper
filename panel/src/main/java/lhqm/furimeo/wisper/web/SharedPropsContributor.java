package lhqm.furimeo.wisper.web;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Adds props that every Inertia page receives, whatever its controller put in the model.
 *
 * <p>The app chrome needs a handful of things on every screen - who is signed in, which
 * organization they are looking at, how many nodes are unhealthy - and threading those
 * through forty controllers by hand is how they come to be present on some pages and
 * missing on others.
 *
 * <p>Declare a {@code @Component} implementing this interface in your own domain
 * package; nothing central needs editing. Contributors run in {@code @Order} order and
 * write into the same map, so a later one can overwrite an earlier one. Keep the work
 * cheap: this runs on every page render, including partial reloads.
 *
 * <p>Props whose keys are already set by the controller's model win - the model is
 * merged last, on the principle that a page asking for something specific means it.
 */
public interface SharedPropsContributor {

    void contribute(Map<String, Object> props, HttpServletRequest request);
}
