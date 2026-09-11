package lhqm.furimeo.wisper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import lhqm.furimeo.wisper.org.RequestRejected;

/**
 * The app/site divergence, which is the one distinction this whole package branches on.
 *
 * <p>Every rule below is also a CHECK constraint in V14. Both exist deliberately: the
 * database is the authority and the Java is what turns "constraint
 * service_site_has_no_container violated" into a sentence under the input the customer got
 * wrong. A rule that drifts out of step between them shows up here first.
 *
 * <p>This file is over the three-hundred-line threshold and is deliberately not split.
 * Splitting it would mean an app file and a site file, and the whole value of these cases
 * is reading them next to each other: the pair "an app needs an image" and "a site must
 * not have one" is one rule seen from two sides, and separating them is how the two halves
 * drift. About eighty lines of it are the draft builder at the bottom, which exists so the
 * cases themselves stay one line each.
 */
class ServiceShapeTest {

    private static ServiceDraft app() {
        return draft(ServiceKind.APP).image("ghcr.io/acme/api:1.4").build();
    }

    private static ServiceDraft site() {
        return draft(ServiceKind.SITE).preset(BuildPreset.NODE).build();
    }

    private static Builder draft(ServiceKind kind) {
        return new Builder(kind);
    }

    // ---- apps ---------------------------------------------------------------------

    @Test
    void anAppNeedsAnImageBecauseThereIsNothingElseToRun() {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> ServiceShape.validated(draft(ServiceKind.APP).build()))
                .matches(rejected -> "image".equals(rejected.field()));
    }

    @Test
    void anAppMayHaveAPortACommandAndAHealthCheck() {
        ServiceDraft draft = draft(ServiceKind.APP)
                .image("node:22")
                .command(List.of("node", "server.js"))
                .port(8080)
                .health("/healthz")
                .build();

        assertThatNoException().isThrownBy(() -> ServiceShape.validated(draft));
    }

    @Test
    void aHealthCheckWithoutAPortIsRefusedRatherThanSilentlyIgnored() {
        ServiceDraft draft = draft(ServiceKind.APP).image("node:22").health("/healthz").build();

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> ServiceShape.validated(draft))
                .matches(rejected -> "containerPort".equals(rejected.field()));
    }

    @Test
    void aHealthCheckPathHasToBeAPath() {
        ServiceDraft draft = draft(ServiceKind.APP).image("node:22").port(80).health("healthz")
                .build();

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> ServiceShape.validated(draft))
                .matches(rejected -> "healthCheckPath".equals(rejected.field()));
    }

    @Test
    void anAppCanAlsoBeBuiltFromARepositoryAndGetsTheOutputDirectoryChecked() {
        ServiceDraft draft = draft(ServiceKind.APP)
                .image("node:22")
                .preset(BuildPreset.NODE)
                .build();

        assertThat(ServiceShape.validated(draft).buildOutputDir()).isEqualTo("dist");
    }

    // ---- sites --------------------------------------------------------------------

    @Test
    void aSiteHasNoImageBecauseItHasNoContainer() {
        ServiceDraft draft = draft(ServiceKind.SITE).preset(BuildPreset.NODE)
                .image("nginx:latest").build();

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> ServiceShape.validated(draft))
                .matches(rejected -> "image".equals(rejected.field()));
    }

    @Test
    void aSiteHasNoPortBecauseNothingListens() {
        ServiceDraft draft = draft(ServiceKind.SITE).preset(BuildPreset.NODE).port(8080).build();

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> ServiceShape.validated(draft))
                .matches(rejected -> "containerPort".equals(rejected.field()));
    }

    @Test
    void aSiteHasNoArgvBecauseThereIsNothingToExec() {
        ServiceDraft withCommand = draft(ServiceKind.SITE).preset(BuildPreset.NODE)
                .command(List.of("npm", "start")).build();

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> ServiceShape.validated(withCommand))
                .withMessageContaining("build command");
    }

    @Test
    void aSiteHasNoHealthCheck() {
        ServiceDraft draft = draft(ServiceKind.SITE).preset(BuildPreset.NODE).health("/up")
                .build();

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> ServiceShape.validated(draft))
                .matches(rejected -> "healthCheckPath".equals(rejected.field()));
    }

    @Test
    void aSiteNeedsToSayHowItIsBuilt() {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> ServiceShape.validated(draft(ServiceKind.SITE).build()))
                .matches(rejected -> "buildPreset".equals(rejected.field()));
    }

    @Test
    void aPresetFillsInTheOutputDirectorySoTheCommonCaseIsOneDecision() {
        assertThat(ServiceShape.validated(site()).buildOutputDir()).isEqualTo("dist");
        assertThat(ServiceShape.validated(draft(ServiceKind.SITE).preset(BuildPreset.HUGO).build())
                .buildOutputDir()).isEqualTo("public");
    }

    @Test
    void aCustomBuildHasToBringItsOwnCommandAndDirectory() {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> ServiceShape.validated(
                        draft(ServiceKind.SITE).preset(BuildPreset.CUSTOM).build()))
                .matches(rejected -> "buildCommand".equals(rejected.field()));

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> ServiceShape.validated(draft(ServiceKind.SITE)
                        .preset(BuildPreset.CUSTOM).buildCommand("make site").build()))
                .matches(rejected -> "buildOutputDir".equals(rejected.field()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/etc", "../../etc", "dist/../../../etc"})
    void anOutputDirectoryThatClimbsOutOfTheCheckoutIsRefused(String directory) {
        ServiceDraft draft = draft(ServiceKind.SITE).preset(BuildPreset.NODE)
                .outputDir(directory).build();

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> ServiceShape.validated(draft))
                .matches(rejected -> "buildOutputDir".equals(rejected.field()));
    }

    @Test
    void anOutputDirectoryIsTidiedRatherThanRefusedForAStrayDotOrSlash() {
        ServiceDraft draft = draft(ServiceKind.SITE).preset(BuildPreset.NODE)
                .outputDir("./build/").build();

        assertThat(ServiceShape.validated(draft).buildOutputDir()).isEqualTo("build");
    }

    // ---- rules both kinds share ----------------------------------------------------

    @Test
    void turningGvisorOffNeedsAReasonTheCustomerWrites() {
        ServiceDraft draft = draft(ServiceKind.APP).image("node:22")
                .isolation(RuntimeIsolation.RUNC, null).build();

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> ServiceShape.validated(draft))
                .matches(rejected -> "isolationReason".equals(rejected.field()));

        assertThatNoException().isThrownBy(() -> ServiceShape.validated(
                draft(ServiceKind.APP).image("node:22")
                        .isolation(RuntimeIsolation.RUNC, "io_uring is not supported under runsc")
                        .build()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", "javascript:alert(1)", "ftp://host/repo",
            "ext::sh -c whoami"})
    void onlyRepositoryUrlsANodeShouldCloneAreAccepted(String url) {
        ServiceDraft draft = draft(ServiceKind.APP).image("node:22").repository(url).build();

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> ServiceShape.validated(draft))
                .matches(rejected -> "repositoryUrl".equals(rejected.field()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://github.com/acme/api.git", "ssh://git@github.com/acme/api",
            "git@github.com:acme/api.git"})
    void theThreeFormsPeoplePasteOutOfAGitHostAreAccepted(String url) {
        ServiceDraft draft = draft(ServiceKind.APP).image("node:22").repository(url).build();

        assertThatNoException().isThrownBy(() -> ServiceShape.validated(draft));
    }

    @Test
    void aCredentialWithNoRepositoryIsRefusedRatherThanStoredForever() {
        ServiceDraft draft = draft(ServiceKind.APP).image("node:22").credential("ghp_xyz").build();

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> ServiceShape.validated(draft))
                .matches(rejected -> "repositoryUrl".equals(rejected.field()));
    }

    @Test
    void limitsOutsideWhatANodeCanHonourAreRefused() {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> ServiceShape.validated(
                        draft(ServiceKind.APP).image("node:22").cpu(0L).build()))
                .matches(rejected -> "cpuMillicores".equals(rejected.field()));

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> ServiceShape.validated(
                        draft(ServiceKind.APP).image("node:22").memory(-1L).build()))
                .matches(rejected -> "memoryBytes".equals(rejected.field()));
    }

    @Test
    void aDraftWithNoKindIsRefusedBeforeAnythingElseIsRead() {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> ServiceShape.validated(draft(null).build()))
                .matches(rejected -> "kind".equals(rejected.field()));
    }

    @Test
    void theDefaultsAreTheSameNumbersTheMigrationDeclares() {
        ServiceDraft defaults = ServiceDraft.defaults();

        assertThat(defaults.cpuMillicores()).isEqualTo(500);
        assertThat(defaults.memoryBytes()).isEqualTo(268_435_456L);
        assertThat(defaults.diskBytes()).isEqualTo(1_073_741_824L);
        assertThat(defaults.pidsLimit()).isEqualTo(256);
        assertThat(defaults.keepReleases()).isEqualTo(5);
        assertThat(defaults.healthCheckIntervalSeconds()).isEqualTo(30);
        assertThat(defaults.runtimeIsolation()).isEqualTo(RuntimeIsolation.RUNSC);
        assertThat(defaults.restartPolicy()).isEqualTo(RestartPolicy.ALWAYS);
    }

    @Test
    void appAndSiteDraftsBothSurviveValidationUnchangedWhereTheRulesAgree() {
        assertThat(ServiceShape.validated(app()).kind()).isEqualTo(ServiceKind.APP);
        assertThat(ServiceShape.validated(site()).kind()).isEqualTo(ServiceKind.SITE);
    }

    /** A readable way to build one of twenty-six components without twenty-six nulls. */
    private static final class Builder {

        private final ServiceKind kind;
        private String image;
        private List<String> command = List.of();
        private Integer port;
        private String health;
        private BuildPreset preset;
        private String buildCommand;
        private String outputDir;
        private String repositoryUrl;
        private String credential;
        private RuntimeIsolation isolation = RuntimeIsolation.RUNSC;
        private String isolationReason;
        private Long cpu;
        private Long memory;

        private Builder(ServiceKind kind) {
            this.kind = kind;
        }

        Builder image(String value) {
            this.image = value;
            return this;
        }

        Builder command(List<String> value) {
            this.command = value;
            return this;
        }

        Builder port(Integer value) {
            this.port = value;
            return this;
        }

        Builder health(String value) {
            this.health = value;
            return this;
        }

        Builder preset(BuildPreset value) {
            this.preset = value;
            return this;
        }

        Builder buildCommand(String value) {
            this.buildCommand = value;
            return this;
        }

        Builder outputDir(String value) {
            this.outputDir = value;
            return this;
        }

        Builder repository(String value) {
            this.repositoryUrl = value;
            return this;
        }

        Builder credential(String value) {
            this.credential = value;
            return this;
        }

        Builder isolation(RuntimeIsolation value, String reason) {
            this.isolation = value;
            this.isolationReason = reason;
            return this;
        }

        Builder cpu(Long value) {
            this.cpu = value;
            return this;
        }

        Builder memory(Long value) {
            this.memory = value;
            return this;
        }

        ServiceDraft build() {
            return new ServiceDraft("Api", null, kind, image, command, List.of(), null, port,
                    health, null, null, preset, buildCommand, outputDir, null, repositoryUrl,
                    null, credential, true, cpu, memory, null, null, List.of(), isolation,
                    isolationReason);
        }
    }
}
