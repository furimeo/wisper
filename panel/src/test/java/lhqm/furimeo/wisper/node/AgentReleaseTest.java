package lhqm.furimeo.wisper.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The release directory is the source of truth, so the file name has to be readable and
 * the ordering has to be right - "which binary is newest" decides what every node is
 * offered.
 */
class AgentReleaseTest {

    @Test
    void aReleaseFileNameCarriesItsVersionAndArchitecture() {
        AgentRelease release = AgentRelease.describe(Path.of("sasayaki-0.4.1-linux-arm64"),
                "abc", 1234L);

        assertThat(release).isNotNull();
        assertThat(release.version()).isEqualTo("0.4.1");
        assertThat(release.architecture()).isEqualTo("arm64");
        assertThat(release.fileName()).isEqualTo("sasayaki-0.4.1-linux-arm64");
    }

    @Test
    void aPreReleaseSuffixIsPartOfTheVersion() {
        AgentRelease release = AgentRelease.describe(Path.of("sasayaki-1.0.0-rc.2-linux-amd64"),
                "abc", 1L);

        assertThat(release).isNotNull();
        assertThat(release.version()).isEqualTo("1.0.0-rc.2");
    }

    @Test
    void anythingElseInTheDirectoryIsNotARelease() {
        assertThat(AgentRelease.isReleaseFile("sasayaki")).isFalse();
        assertThat(AgentRelease.isReleaseFile("sasayaki-0.4.1-darwin-arm64")).isFalse();
        assertThat(AgentRelease.isReleaseFile("sasayaki-0.4-linux-amd64")).isFalse();
        assertThat(AgentRelease.isReleaseFile("../../etc/shadow")).isFalse();
        assertThat(AgentRelease.isReleaseFile("sasayaki-0.4.1-linux-amd64.sha256")).isFalse();
    }

    @Test
    void versionsAreComparedNumericallyRatherThanAsStrings() {
        // The bug this exists to stop: a plain sort puts 0.10.0 before 0.9.0, and the panel
        // keeps offering an older binary while insisting it is the newest.
        assertThat(AgentRelease.compareVersions("0.10.0", "0.9.0")).isPositive();
        assertThat(AgentRelease.compareVersions("1.0.0", "1.0.0")).isZero();
        assertThat(AgentRelease.compareVersions("1.2.3", "1.3.0")).isNegative();
    }

    @Test
    void aPreReleaseIsOlderThanTheVersionItLeadsTo() {
        assertThat(AgentRelease.compareVersions("1.0.0-rc.1", "1.0.0")).isNegative();
        assertThat(AgentRelease.compareVersions("1.0.0-rc.1", "1.0.0-rc.2")).isNegative();
    }

    @Test
    void sortingPutsTheNewestFirst() {
        List<AgentRelease> releases = new ArrayList<>(List.of(
                release("0.9.0", "amd64"), release("0.10.0", "amd64"),
                release("0.10.0-rc.1", "amd64")));

        releases.sort(AgentRelease.NEWEST_FIRST);

        assertThat(releases).extracting(AgentRelease::version)
                .containsExactly("0.10.0", "0.10.0-rc.1", "0.9.0");
    }

    @Test
    void theDownloadUrlIsRootedAtTheUrlTheOperatorReachedThePanelOn() {
        assertThat(release("0.4.1", "amd64").downloadUrl("https://panel.example"))
                .isEqualTo("https://panel.example/dist/sasayaki-0.4.1-linux-amd64");
    }

    private static AgentRelease release(String version, String architecture) {
        String fileName = "sasayaki-" + version + "-linux-" + architecture;
        return new AgentRelease(version, architecture, fileName, Path.of(fileName),
                "0".repeat(64), 30_000_000L);
    }
}
