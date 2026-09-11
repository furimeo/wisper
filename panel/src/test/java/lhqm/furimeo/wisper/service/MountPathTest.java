package lhqm.furimeo.wisper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import lhqm.furimeo.wisper.org.RequestRejected;

/**
 * Where a volume may and may not appear inside a container.
 *
 * <p>The {@code ..} cases are the ones worth having: a mount path is handed to a node,
 * which resolves it, and a path that can climb out of the container is the same class of
 * bug as a file-manager traversal - the largest attack surface in v1.
 */
class MountPathTest {

    @Test
    void anOrdinaryPathIsKept() {
        assertThat(MountPath.require("/data")).isEqualTo("/data");
        assertThat(MountPath.require("/var/lib/postgresql/data"))
                .isEqualTo("/var/lib/postgresql/data");
    }

    @Test
    void spellingsOfOneMountPointAreNormalisedToOne() {
        assertThat(MountPath.require("  /data/  ")).isEqualTo("/data");
        assertThat(MountPath.require("/data//sub///")).isEqualTo("/data/sub");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/data/../../etc", "/../etc", "/data/..", "/a/./b"})
    void aPathThatCanClimbOutIsRefused(String path) {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> MountPath.require(path))
                .matches(rejected -> "mountPath".equals(rejected.field()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/etc", "/proc", "/sys", "/dev", "/usr", "/bin", "/sbin", "/lib"})
    void mountingOverASystemDirectoryIsRefused(String path) {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> MountPath.require(path))
                .withMessageContaining("hide the container's own files");
    }

    @Test
    void aTrailingSlashDoesNotSmuggleASystemDirectoryPast() {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> MountPath.require("/etc/"));
    }

    @Test
    void aRelativePathIsRefusedWithASentenceThatSaysWhy() {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> MountPath.require("data"))
                .withMessageContaining("starts with a slash");
    }

    @Test
    void anEmptyPathIsRefused() {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> MountPath.require("   "));
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> MountPath.require(null));
    }

    @Test
    void aWindowsPathIsRefusedRatherThanQuietlyStored() {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> MountPath.require("C:\\data"))
                .withMessageContaining("Linux path");
    }
}
