package lhqm.furimeo.wisper.node;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One published sasayaki binary sitting in the release directory.
 *
 * <p>The file name is the metadata. There is no manifest file to keep in step with the
 * directory, because a manifest that disagrees with what is on disk is worse than no
 * manifest - the panel would be publishing a checksum for a binary nobody can download.
 * Naming the file {@code sasayaki-0.4.1-linux-amd64} and reading it back is a fact that
 * cannot drift.
 *
 * <p>The checksum is the load-bearing part. It goes into the install script an operator
 * pipes into a shell, into the {@code UpgradeNode} command a node verifies before it
 * replaces its own binary, and onto the node's page so a person can compare it by eye
 * (design §7.1, §7.5). An upgrade that installs an unverified binary is a remote code
 * execution feature.
 *
 * @param version      what the file name says, {@code 0.4.1}
 * @param architecture GOARCH, so it matches what {@code MachineFacts} reports
 * @param fileName     the name under {@code /dist}
 * @param path         where it is on the panel's disk
 * @param sha256       lower-case hex, computed from the bytes on disk
 * @param sizeBytes    for the download page and the progress bar
 */
public record AgentRelease(String version, String architecture, String fileName, Path path,
                           String sha256, long sizeBytes) {

    /** {@code sasayaki-<version>-linux-<arch>}, and nothing else is a release. */
    private static final Pattern FILE_NAME =
            Pattern.compile("^sasayaki-(\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.]+)?)-linux-([a-z0-9]+)$");

    /**
     * Newest first, comparing version components numerically.
     *
     * <p>A plain string sort puts {@code 0.10.0} before {@code 0.9.0}, which is the bug
     * that has an operator wondering why the panel keeps offering an older binary.
     */
    public static final Comparator<AgentRelease> NEWEST_FIRST =
            Comparator.comparing(AgentRelease::version, AgentRelease::compareVersions).reversed();

    /** Whether a file in the release directory is one of ours. */
    public static boolean isReleaseFile(String fileName) {
        return FILE_NAME.matcher(fileName).matches();
    }

    /** Pulls the version and architecture out of a file name, or returns null. */
    public static AgentRelease describe(Path path, String sha256, long sizeBytes) {
        String fileName = path.getFileName().toString();
        Matcher matcher = FILE_NAME.matcher(fileName);
        if (!matcher.matches()) {
            return null;
        }
        return new AgentRelease(matcher.group(1), matcher.group(2), fileName, path, sha256,
                sizeBytes);
    }

    /** The URL this is served at, given the panel's base URL. */
    public String downloadUrl(String panelBaseUrl) {
        return panelBaseUrl + "/dist/" + fileName;
    }

    /**
     * Compares two dotted versions numerically, treating a suffix such as {@code -rc1} as
     * older than the release it leads to - which is what every packaging convention means
     * and what an operator expects when both are in the directory.
     */
    static int compareVersions(String left, String right) {
        String[] leftCore = left.split("-", 2);
        String[] rightCore = right.split("-", 2);
        String[] leftParts = leftCore[0].split("\\.");
        String[] rightParts = rightCore[0].split("\\.");
        for (int index = 0; index < Math.max(leftParts.length, rightParts.length); index++) {
            int a = index < leftParts.length ? Integer.parseInt(leftParts[index]) : 0;
            int b = index < rightParts.length ? Integer.parseInt(rightParts[index]) : 0;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        boolean leftIsPre = leftCore.length > 1;
        boolean rightIsPre = rightCore.length > 1;
        if (leftIsPre != rightIsPre) {
            return leftIsPre ? -1 : 1;
        }
        return leftIsPre ? leftCore[1].compareTo(rightCore[1]) : 0;
    }
}
