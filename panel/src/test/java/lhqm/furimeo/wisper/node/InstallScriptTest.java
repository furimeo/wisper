package lhqm.furimeo.wisper.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The installer is a shell script an operator pipes into a root shell, so the properties
 * design §7.1 asks for have to be visible in the text it actually serves.
 */
class InstallScriptTest {

    private static final String SHA256 =
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    private final InstallScript script = InstallScript.render("https://panel.example",
            "panel.example:9090", List.of(release("amd64"), release("arm64")));

    @Test
    void theBinaryChecksumIsBakedInPerArchitecture() {
        // Baked in, not fetched: a script that asked the same server for the checksum
        // would be verifying a download against the download's own source.
        assertThat(script.text())
                .contains("x86_64) RELEASE_FILE=\"sasayaki-0.4.1-linux-amd64\"")
                .contains("aarch64) RELEASE_FILE=\"sasayaki-0.4.1-linux-arm64\"")
                .contains("RELEASE_SHA256=\"" + SHA256 + "\"")
                .contains("sha256sum -c -");
    }

    @Test
    void theTokenIsNeverAcceptedAsAnArgument() {
        assertThat(script.text())
                .contains("--token-file")
                .contains("refusing --token")
                .contains("argv is world-readable");
    }

    @Test
    void preflightRunsBeforeAnythingIsWritten() {
        String text = script.text();

        int doctor = text.indexOf("\"$WORK/sasayaki\" doctor");
        int install = text.indexOf("install -m 0755");

        assertThat(doctor).isGreaterThan(0);
        assertThat(install).isGreaterThan(doctor);
    }

    @Test
    void thePanelUrlAndDialEndpointAreTheOnesTheRequestArrivedOn() {
        assertThat(script.text())
                .contains("PANEL_URL=\"https://panel.example\"")
                .contains("DIAL_ENDPOINT=\"panel.example:9090\"");
    }

    @Test
    void anUnknownArchitectureStopsBeforeItDownloadsAnything() {
        assertThat(script.text())
                .contains("*) RELEASE_FILE=\"\"")
                .contains("publishes no sasayaki build for");
    }

    @Test
    void theChecksumOfTheScriptChangesWithItsContents() {
        InstallScript elsewhere = InstallScript.render("https://other.example",
                "other.example:9090", List.of(release("amd64"), release("arm64")));

        assertThat(script.sha256()).hasSize(64);
        assertThat(script.sha256()).isNotEqualTo(elsewhere.sha256());
        // Stable for the same inputs: the value on the node's page has to match what the
        // operator downloads a moment later.
        assertThat(script.sha256()).isEqualTo(InstallScript.render("https://panel.example",
                "panel.example:9090", List.of(release("amd64"), release("arm64"))).sha256());
    }

    @Test
    void theDeclaredLengthMatchesWhatIsServed() {
        assertThat(script.byteLength())
                .isEqualTo(script.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    private static AgentRelease release(String architecture) {
        String fileName = "sasayaki-0.4.1-linux-" + architecture;
        return new AgentRelease("0.4.1", architecture, fileName, Path.of(fileName), SHA256,
                30_000_000L);
    }
}
