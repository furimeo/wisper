package lhqm.furimeo.wisper.domain;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The {@code wisper.domain.*} namespace.
 *
 * <p>Picked up by {@code @ConfigurationPropertiesScan} on {@code WisperApplication} with no
 * registration anywhere (panel-configuration.md). Every component carries a
 * {@code @DefaultValue}, because record binding does not fall back to a constructor default
 * and a missing key would otherwise bind to {@code null} and surface as a
 * {@code NullPointerException} inside a scheduled job.
 *
 * @param dnsTimeout      how long one query to one resolver may take. Short: the check runs
 *                        on a job thread and a hostname whose nameservers are down is
 *                        exactly the case that must not hold the queue.
 * @param dnsRetries      how many extra attempts the resolver makes per query, each with a
 *                        doubled timeout. Two is enough to ride out a dropped UDP packet
 *                        without turning one check into a minute.
 * @param firstRecheck    the wait after the first failed check. Somebody who has just added
 *                        a hostname is usually editing DNS in another tab.
 * @param earlyRecheck    the wait while the domain is still young, when propagation is the
 *                        likeliest explanation.
 * @param settledRecheck  the wait for a domain that has been unverified for a day. The
 *                        customer has gone away; wisper keeps looking, cheaply, because
 *                        they may come back to it next week.
 * @param impatientPeriod how long a domain counts as newly added.
 * @param settlingPeriod  how long it counts as still propagating.
 */
@ConfigurationProperties("wisper.domain")
public record DomainSettings(
        @DefaultValue("2s") Duration dnsTimeout,
        @DefaultValue("2") int dnsRetries,
        @DefaultValue("1m") Duration firstRecheck,
        @DefaultValue("5m") Duration earlyRecheck,
        @DefaultValue("30m") Duration settledRecheck,
        @DefaultValue("15m") Duration impatientPeriod,
        @DefaultValue("6h") Duration settlingPeriod) {

    public DomainSettings {
        if (dnsTimeout.isNegative() || dnsTimeout.isZero()) {
            throw new IllegalArgumentException(
                    "wisper.domain.dns-timeout must be positive; a zero timeout never resolves");
        }
        if (dnsRetries < 0) {
            throw new IllegalArgumentException("wisper.domain.dns-retries cannot be negative");
        }
    }

    /**
     * How long to wait before asking again about a hostname that has not verified.
     *
     * <p>Derived from the domain's age rather than from an attempt counter, because there is
     * no attempt column and adding one would mean a migration for a number that is only ever
     * used to pick a delay. The curve is the shape of the problem: minutes while somebody is
     * watching the screen, then half-hourly forever, because a customer who adds a hostname
     * on Friday and edits DNS on Monday should find it verified without pressing anything.
     *
     * @param age how long ago the domain row was created
     */
    public Duration recheckAfter(Duration age) {
        if (age.compareTo(impatientPeriod) < 0) {
            return firstRecheck;
        }
        return age.compareTo(settlingPeriod) < 0 ? earlyRecheck : settledRecheck;
    }
}
