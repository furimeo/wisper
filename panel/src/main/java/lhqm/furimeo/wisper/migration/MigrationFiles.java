package lhqm.furimeo.wisper.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * Finds the migration files on the classpath and puts them in order.
 *
 * <p>One file per change, named {@code V{n}__what_it_does.sql}. The number is the
 * ordering and the identity; the words after it are for whoever opens the folder in two
 * years. Anything in the directory that does not match the pattern is ignored rather
 * than guessed at - a stray {@code .bak} must not become part of the schema.
 */
final class MigrationFiles {

    private static final String LOCATION = "classpath*:wisper/migrations/V*__*.sql";
    private static final Pattern NAME = Pattern.compile("V(\\d+)__(.+)\\.sql");

    private MigrationFiles() {
    }

    /** One migration file, with its content already read and hashed. */
    record Migration(int version, String name, String sql, String checksum) {

        String filename() {
            return "V" + version + "__" + name + ".sql";
        }
    }

    static List<Migration> discover() {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources(LOCATION);
        } catch (IOException e) {
            throw new IllegalStateException("Could not list migrations at " + LOCATION, e);
        }

        List<Migration> migrations = new ArrayList<>();
        for (Resource resource : resources) {
            String filename = resource.getFilename();
            if (filename == null) {
                continue;
            }
            Matcher matcher = NAME.matcher(filename);
            if (!matcher.matches()) {
                continue;
            }
            String sql = read(resource, filename);
            migrations.add(new Migration(
                    Integer.parseInt(matcher.group(1)),
                    matcher.group(2),
                    sql,
                    checksum(sql)));
        }

        migrations.sort(Comparator.comparingInt(Migration::version));
        rejectDuplicates(migrations);
        return List.copyOf(migrations);
    }

    /**
     * Two files claiming the same number is a merge of two branches that each added
     * "the next" migration. Applying one and skipping the other leaves a schema that
     * matches neither branch, and nothing later notices.
     */
    private static void rejectDuplicates(List<Migration> migrations) {
        for (int i = 1; i < migrations.size(); i++) {
            Migration previous = migrations.get(i - 1);
            Migration current = migrations.get(i);
            if (previous.version() == current.version()) {
                throw new IllegalStateException("Two migrations numbered V" + current.version()
                        + ": " + previous.filename() + " and " + current.filename()
                        + ". Renumber the newer one.");
            }
        }
    }

    private static String read(Resource resource, String filename) {
        try {
            /*
             * Line endings are normalised before anything else touches the text. A
             * checkout on Windows can produce CRLF where the file was committed with LF,
             * and a checksum over the raw bytes would then report every migration as
             * "modified since it was applied" on one developer's machine and no other.
             */
            return resource.getContentAsString(StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace("\r", "\n");
        } catch (IOException e) {
            throw new IllegalStateException("Could not read migration " + filename, e);
        }
    }

    private static String checksum(String sql) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sql.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }
}
