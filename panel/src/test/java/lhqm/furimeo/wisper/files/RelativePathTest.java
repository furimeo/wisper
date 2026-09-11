package lhqm.furimeo.wisper.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Path traversal, refused before a request is even built.
 *
 * <p>AGENTS.md §5: "Path traversal is a build failure, not a bug report." The node checks
 * every path as well, against a resolved real path, and that check is the authoritative one -
 * but the two fail differently and a bug in either is caught by the other. This is the panel
 * half.
 */
class RelativePathTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "..",
        "../etc/passwd",
        "logs/../../etc/shadow",
        "a/b/..",
        "./../secrets",
        "/../etc/passwd",
        "nested//../..//root",
        // Normalising by climbing would turn this into "b" and let it through; refusing any
        // ".." at all means an attacker guessing a real directory name first gains nothing.
        "a/../b"
    })
    void anySegmentThatClimbsIsRefused(String attempt) {
        assertThatExceptionOfType(PathRejected.class)
                .isThrownBy(() -> RelativePath.of(attempt))
                .matches(PathRejected::isTraversal);
    }

    @Test
    void aNameContainingTwoDotsInTheMiddleIsAnOrdinaryFile() {
        assertThat(RelativePath.of("release..2026.tar.gz").value())
                .isEqualTo("release..2026.tar.gz");
        assertThat(RelativePath.of("logs/app..log").value()).isEqualTo("logs/app..log");
    }

    @Test
    void aLeadingSlashIsTheRootAndNotAnAbsolutePath() {
        // There is no absolute path in this vocabulary, so a leading slash can only have
        // meant "from the top of this root".
        assertThat(RelativePath.of("/etc/hosts").value()).isEqualTo("etc/hosts");
    }

    @Test
    void repeatedTrailingAndCurrentDirectorySegmentsAreTidiedRatherThanRefused() {
        assertThat(RelativePath.of("logs//app.log").value()).isEqualTo("logs/app.log");
        assertThat(RelativePath.of("logs/").value()).isEqualTo("logs");
        assertThat(RelativePath.of("./logs/./app.log").value()).isEqualTo("logs/app.log");
        assertThat(RelativePath.of("   ").value()).isEmpty();
        assertThat(RelativePath.of(null).isRoot()).isTrue();
    }

    @Test
    void aNulByteIsRefusedBecauseItTruncatesAPathInEveryCLibrary() {
        assertThatExceptionOfType(PathRejected.class)
                .isThrownBy(() -> RelativePath.of("app.log" + (char) 0 + ".png"))
                .matches(rejected -> !rejected.isTraversal());
    }

    @Test
    void aControlCharacterIsRefused() {
        assertThatExceptionOfType(PathRejected.class)
                .isThrownBy(() -> RelativePath.of("logs/a\nb"));
    }

    @Test
    void aSegmentLongerThanAFilenameIsRefused() {
        assertThatExceptionOfType(PathRejected.class)
                .isThrownBy(() -> RelativePath.of("x".repeat(RelativePath.MAX_SEGMENT_LENGTH + 1)));
    }

    @Test
    void aPathLongerThanPathMaxIsRefused() {
        String tooLong = ("directory/").repeat(RelativePath.MAX_LENGTH / 10 + 1);

        assertThatExceptionOfType(PathRejected.class).isThrownBy(() -> RelativePath.of(tooLong));
    }

    @Test
    void resolvingAChildRefusesAnythingThatIsNotOneName() {
        RelativePath here = RelativePath.of("logs");

        assertThat(here.resolve("app.log").value()).isEqualTo("logs/app.log");
        assertThatExceptionOfType(PathRejected.class).isThrownBy(() -> here.resolve(".."));
        assertThatExceptionOfType(PathRejected.class).isThrownBy(() -> here.resolve("a/b"));
    }

    @Test
    void theParentOfTheRootIsTheRootRatherThanSomethingAboveIt() {
        assertThat(RelativePath.root().parent().isRoot()).isTrue();
        assertThat(RelativePath.of("a").parent().isRoot()).isTrue();
        assertThat(RelativePath.of("a/b/c").parent().value()).isEqualTo("a/b");
    }

    @Test
    void theNameIsTheLastSegment() {
        assertThat(RelativePath.of("a/b/c.txt").name()).isEqualTo("c.txt");
        assertThat(RelativePath.root().name()).isEmpty();
    }
}
