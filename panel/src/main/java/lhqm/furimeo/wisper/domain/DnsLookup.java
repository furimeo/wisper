package lhqm.furimeo.wisper.domain;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

import org.springframework.stereotype.Component;

/**
 * Asks the internet what a hostname currently points at.
 *
 * <p>The JDK's own DNS provider, through JNDI, and no dependency: {@code InetAddress}
 * cannot read a TXT record and caches its answers for the life of the process, which is
 * wrong for a check that exists to notice a change the customer just made. JNDI queries the
 * platform's configured resolvers directly, with a timeout this class controls.
 *
 * <h2>The distinction the rest of the package depends on</h2>
 *
 * <p>An empty list means <em>the resolver answered and there is nothing there</em>:
 * NXDOMAIN, or a name with no record of that type. {@link DnsUnavailable} means the
 * resolver could not be asked. Collapsing the two would mark every hostname on the platform
 * as failing verification the moment the panel's own network hiccuped.
 */
@Component
public class DnsLookup {

    /** How many {@code CNAME} hops to follow before giving up on a loop. */
    private static final int MAX_ALIAS_HOPS = 6;

    private final DomainSettings settings;

    public DnsLookup(DomainSettings settings) {
        this.settings = settings;
    }

    /**
     * Every address a hostname resolves to, as normalised literals.
     *
     * <p>Follows {@code CNAME} chains, because a customer told to "point it at the node"
     * very often creates a CNAME to the node's own name, and a resolver that returns only
     * the alias would otherwise read as "points nowhere".
     *
     * @return dotted-quad and full-form IPv6 literals, in the order found and without
     *         duplicates; empty when the name does not resolve
     * @throws DnsUnavailable when no resolver answered
     */
    public List<String> addresses(String hostname) {
        try (Resolver resolver = new Resolver()) {
            Set<String> found = new LinkedHashSet<>();
            String name = hostname;
            for (int hop = 0; hop < MAX_ALIAS_HOPS; hop++) {
                collect(resolver.lookup(name, "A").get("A"), found);
                collect(resolver.lookup(name, "AAAA").get("AAAA"), found);
                if (!found.isEmpty()) {
                    return normalise(found);
                }
                List<String> aliases = new ArrayList<>();
                collect(resolver.lookup(name, "CNAME").get("CNAME"), aliases::add);
                if (aliases.isEmpty()) {
                    return List.of();
                }
                name = stripRootDot(aliases.get(0));
            }
            // A chain this long is a misconfiguration, not an outage: the resolver answered
            // every time. Reported as "nothing there", which is what a browser would also
            // conclude.
            return List.of();
        }
    }

    /**
     * The TXT strings published at a name.
     *
     * @return each record's text with the transport's quoting removed; empty when the name
     *         has no TXT records
     * @throws DnsUnavailable when no resolver answered
     */
    public List<String> textRecords(String name) {
        List<String> records = new ArrayList<>();
        try (Resolver resolver = new Resolver()) {
            collect(resolver.lookup(name, "TXT").get("TXT"),
                    value -> records.add(unquote(value)));
        }
        return List.copyOf(records);
    }

    /**
     * Whether a string is an address rather than a name.
     *
     * <p>{@code node.public_address} holds either. Asking a resolver about {@code 203.0.113.4}
     * would work on most resolvers and mean nothing on the rest, so the two cases are told
     * apart before anything is looked up.
     */
    public static boolean isAddressLiteral(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        String value = candidate.trim();
        if (value.indexOf(':') >= 0) {
            return true;
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3
                    || !part.chars().allMatch(Character::isDigit)) {
                return false;
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    /**
     * One address in the single form everything else compares against.
     *
     * <p>{@code 2001:db8::1} and {@code 2001:0db8:0000:0000:0000:0000:0000:0001} are one
     * address written two ways, and a string comparison between them says they are not.
     * Parsing and re-rendering removes the question.
     */
    public static String normaliseAddress(String literal) {
        String value = literal == null ? "" : literal.trim();
        if (!isAddressLiteral(value)) {
            return value.toLowerCase(Locale.ROOT);
        }
        try {
            // Safe from a lookup: the argument is already known to be a literal, and
            // getByName never consults a resolver for one.
            return InetAddress.getByName(value).getHostAddress();
        } catch (UnknownHostException malformed) {
            return value.toLowerCase(Locale.ROOT);
        }
    }

    private static void collect(Attribute attribute, Set<String> into) {
        collect(attribute, into::add);
    }

    /**
     * Reads one attribute's values.
     *
     * <p>A {@link NamingException} while walking values means the answer was truncated
     * mid-read, which is an unreachable resolver by another name.
     */
    private static void collect(Attribute attribute, Consumer<String> sink) {
        if (attribute == null) {
            return;
        }
        try {
            NamingEnumeration<?> values = attribute.getAll();
            while (values.hasMore()) {
                Object value = values.next();
                if (value != null) {
                    sink.accept(String.valueOf(value));
                }
            }
        } catch (NamingException truncated) {
            throw new DnsUnavailable(attribute.getID(),
                    "The answer for " + attribute.getID() + " could not be read: "
                            + describe(truncated), truncated);
        }
    }

    private static List<String> normalise(Set<String> raw) {
        Set<String> normalised = new LinkedHashSet<>();
        for (String value : raw) {
            normalised.add(normaliseAddress(value));
        }
        return List.copyOf(normalised);
    }

    /**
     * Strips the transport's quoting from a TXT value.
     *
     * <p>A TXT record is a sequence of character-strings, and the provider hands them back
     * quoted and space-separated when there is more than one. The value the customer typed
     * is the concatenation, without the quotes - which is what a long token split across two
     * strings by a DNS interface has to come back as.
     */
    private static String unquote(String raw) {
        if (raw.indexOf('"') < 0) {
            return raw;
        }
        StringBuilder text = new StringBuilder(raw.length());
        boolean inside = false;
        for (int index = 0; index < raw.length(); index++) {
            char character = raw.charAt(index);
            if (character == '"') {
                inside = !inside;
            } else if (inside) {
                text.append(character);
            }
        }
        return text.toString();
    }

    private static String stripRootDot(String name) {
        return name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
    }

    private static String describe(NamingException failure) {
        String explanation = failure.getExplanation();
        return explanation == null || explanation.isBlank()
                ? failure.getClass().getSimpleName() : explanation;
    }

    /**
     * One connection to the platform's resolvers, reused for every question in one check.
     *
     * <p><strong>One record type per query.</strong> Asking {@code getAttributes} for several
     * types at once makes the provider send a single {@code QTYPE=ANY} question, and a
     * resolver that follows RFC 8482 answers that with {@code NOTIMP} - which arrives here as
     * "no resolver answered" for a hostname that resolves perfectly well. Separate questions
     * cost one more round trip and always get an answer.
     */
    private final class Resolver implements AutoCloseable {

        private final DirContext context;

        Resolver() {
            Hashtable<String, Object> environment = new Hashtable<>();
            environment.put(Context.INITIAL_CONTEXT_FACTORY,
                    "com.sun.jndi.dns.DnsContextFactory");
            // No servers named: the provider reads the host's own resolver configuration,
            // which is the one an operator has already set up for this machine.
            environment.put(Context.PROVIDER_URL, "dns:");
            environment.put("com.sun.jndi.dns.timeout.initial",
                    String.valueOf(settings.dnsTimeout().toMillis()));
            environment.put("com.sun.jndi.dns.timeout.retries",
                    String.valueOf(settings.dnsRetries()));
            try {
                this.context = new InitialDirContext(environment);
            } catch (NamingException noResolver) {
                throw new DnsUnavailable("", "No DNS resolver is configured on this host: "
                        + describe(noResolver), noResolver);
            }
        }

        /**
         * @return the records found, or an empty set of attributes when the name or the type
         *         does not exist
         * @throws DnsUnavailable for anything that is not an answer
         */
        Attributes lookup(String name, String type) {
            try {
                return context.getAttributes(name, new String[] {type});
            } catch (NameNotFoundException nxdomain) {
                // An answer, and a useful one: nobody has created this name.
                return new BasicAttributes(true);
            } catch (NamingException unreachable) {
                throw new DnsUnavailable(name, "Could not look up the " + type + " record for "
                        + name + ": " + describe(unreachable), unreachable);
            }
        }

        @Override
        public void close() {
            try {
                context.close();
            } catch (NamingException ignored) {
                // Closing a resolver context has nothing to report and nothing a caller
                // could do about it.
            }
        }
    }
}
