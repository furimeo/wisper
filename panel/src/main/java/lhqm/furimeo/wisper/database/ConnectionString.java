package lhqm.furimeo.wisper.database;

import java.util.UUID;

/**
 * Everything needed to connect to one customer database, including the password.
 *
 * <p>This is the only shape in the panel that carries a decrypted database credential,
 * and it exists for exactly one screen: the one a customer opens deliberately, having
 * pressed "show connection details", which {@link ShowConnectionString} records in the
 * audit trail before it builds one of these. It is never a field on a list row and never
 * part of a page's default props - a secret that is on the page whether or not anybody
 * asked for it is a secret in a browser cache, a screenshot and a support ticket.
 *
 * <p>The parts are here as well as the URI because they are not interchangeable in
 * practice: a framework's {@code .env} wants the URI, a JDBC application wants host, port
 * and database separately, and a customer copying one into the other by hand is how a
 * password ends up in the wrong field.
 *
 * @param databaseId the row this belongs to, so the screen can post rotate or drop back
 *                   to the right place
 * @param engine     which engine, because the client library differs
 * @param host       the name the engine answers to on the node's private network. Not
 *                   reachable from the public internet: a customer's own containers
 *                   resolve it, and nobody outside the node does
 * @param password   plaintext, decrypted for this one response
 */
public record ConnectionString(
        UUID databaseId,
        EngineKind engine,
        String host,
        int port,
        String database,
        String username,
        String password,
        String charset) {

    /** Builds the details for a database on an engine. */
    public static ConnectionString of(ManagedDatabase database, DatabaseEngine engine,
                                      String plaintextPassword) {
        return new ConnectionString(database.id(), engine.engine(), engine.host(), engine.port(),
                database.name(), database.dbUsername(), plaintextPassword,
                database.dbCharset() == null ? engine.engine().defaultEncoding()
                        : database.dbCharset());
    }

    /**
     * The URI most client libraries parse.
     *
     * <p>No escaping, and that is deliberate rather than an oversight:
     * {@link DatabasePassword} draws from an alphabet with no character a URI treats
     * specially, and {@link DatabaseIdentifier} does the same for the name and the login.
     * Escaping here would hide the day one of those alphabets grows a {@code @}.
     */
    public String uri() {
        return engine.uriScheme() + "://" + username + ":" + password + "@" + host + ":" + port
                + "/" + database;
    }

    /**
     * The same address with the password replaced.
     *
     * <p>What goes in a log line, a flash message or an audit detail. There is no path in
     * this package that writes {@link #uri()} anywhere except into the one response the
     * customer asked for.
     */
    public String redactedUri() {
        return engine.uriScheme() + "://" + username + ":********@" + host + ":" + port + "/"
                + database;
    }

    /**
     * The JDBC URL, for a customer deploying something on the JVM.
     *
     * <p>Credentials are left out on purpose: JDBC takes them as separate properties, and
     * a URL with a password in it is the version that ends up committed.
     */
    public String jdbcUrl() {
        return "jdbc:" + engine.uriScheme() + "://" + host + ":" + port + "/" + database;
    }
}
