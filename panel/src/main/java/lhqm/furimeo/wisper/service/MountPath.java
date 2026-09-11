package lhqm.furimeo.wisper.service;

import lhqm.furimeo.wisper.org.RequestRejected;

/**
 * Where a volume appears inside a container, and the rules that stop it appearing
 * somewhere it must not.
 *
 * <p>Four CHECK constraints in V22 say the same thing - absolute, no {@code ..}, not a
 * system directory, unique per service - and the database has the final word. This exists
 * so the customer is told which rule they broke, in the box they typed in.
 *
 * <p>The dangerous case is not {@code /etc}: it is a path with {@code ..} in it, which the
 * node would resolve against its own filesystem. That is the same class of bug as a
 * file-manager traversal, which is the largest attack surface in v1 (AGENTS.md §5), and
 * the cheapest place to refuse it is before it is ever stored.
 */
public final class MountPath {

    private MountPath() {
    }

    /**
     * Normalises and checks a mount path.
     *
     * @return the path to store: absolute, no trailing slash, no doubled separators
     * @throws RequestRejected naming {@code mountPath}
     */
    public static String require(String raw) {
        String path = raw == null ? "" : raw.strip();
        if (path.isEmpty()) {
            throw new RequestRejected("mountPath",
                    "Say where the volume should appear inside the container, for example /data.");
        }
        // Before the leading-slash check, so somebody who pasted C:\data is told what is
        // actually wrong rather than being asked to put a slash in front of it.
        if (path.contains("\\")) {
            throw new RequestRejected("mountPath",
                    "A mount path is a Linux path; use forward slashes.");
        }
        if (!path.startsWith("/")) {
            throw new RequestRejected("mountPath",
                    "A mount path is absolute, so it starts with a slash: /data, not data.");
        }

        // Collapse repeated separators and drop the trailing one, so /data// and /data/
        // are stored as the one path they are - otherwise the unique index treats three
        // spellings of one mount point as three mount points.
        String tidy = path.replaceAll("/{2,}", "/");
        while (tidy.length() > 1 && tidy.endsWith("/")) {
            tidy = tidy.substring(0, tidy.length() - 1);
        }

        for (String segment : tidy.split("/")) {
            if (segment.equals("..")) {
                throw new RequestRejected("mountPath",
                        "A mount path cannot contain \"..\". Write the full path you mean.");
            }
            if (segment.equals(".")) {
                throw new RequestRejected("mountPath",
                        "A mount path cannot contain \".\". Write the full path you mean.");
            }
        }
        if (Volume.FORBIDDEN_MOUNT_PATHS.contains(tidy)) {
            throw new RequestRejected("mountPath",
                    "Mounting over " + tidy + " would hide the container's own files. Pick a "
                            + "path of your own, such as /data.");
        }
        if (tidy.length() > 512) {
            throw new RequestRejected("mountPath", "That path is too long.");
        }
        return tidy;
    }
}
