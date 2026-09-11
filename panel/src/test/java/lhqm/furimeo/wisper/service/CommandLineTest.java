package lhqm.furimeo.wisper.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Splitting a typed line into argv, and putting it back together.
 *
 * <p>The round trip is the property that matters: the settings form renders argv as one
 * line and saves whatever comes back, so a command that changes by being displayed is a
 * command that breaks on the second save.
 */
class CommandLineTest {

    @Test
    void whitespaceSeparatesArguments() {
        assertThat(CommandLine.parse("node server.js --port 8080"))
                .containsExactly("node", "server.js", "--port", "8080");
    }

    @Test
    void runsOfWhitespaceAreOneSeparator() {
        assertThat(CommandLine.parse("  node   server.js  "))
                .containsExactly("node", "server.js");
    }

    @Test
    void nothingIsAnEmptyVector() {
        assertThat(CommandLine.parse(null)).isEmpty();
        assertThat(CommandLine.parse("   ")).isEmpty();
    }

    @Test
    void singleQuotesKeepSpacesInsideOneArgument() {
        assertThat(CommandLine.parse("sh -c 'echo hello world'"))
                .containsExactly("sh", "-c", "echo hello world");
    }

    @Test
    void doubleQuotesDoTheSameAndUnderstandABackslash() {
        assertThat(CommandLine.parse("say \"a \\\"quoted\\\" word\""))
                .containsExactly("say", "a \"quoted\" word");
    }

    @Test
    void aBackslashOutsideQuotesEscapesTheNextCharacter() {
        assertThat(CommandLine.parse("/bin/my\\ app --flag"))
                .containsExactly("/bin/my app", "--flag");
    }

    @Test
    void anEmptyQuotedStringIsStillAnArgument() {
        assertThat(CommandLine.parse("cmd '' x")).containsExactly("cmd", "", "x");
    }

    @Test
    void anUnclosedQuoteIsClosedAtTheEndRatherThanRefused() {
        assertThat(CommandLine.parse("sh -c 'echo hi")).containsExactly("sh", "-c", "echo hi");
    }

    @Test
    void formattingAndParsingAreInverses() {
        List<String> argv = List.of("/bin/my app", "--flag=a b", "it's", "plain", "");
        String line = CommandLine.format(argv.toArray(String[]::new));

        assertThat(CommandLine.parse(line)).containsExactlyElementsOf(argv);
    }

    @Test
    void formattingLeavesOrdinaryArgumentsAlone() {
        assertThat(CommandLine.format(new String[] {"node", "server.js"}))
                .isEqualTo("node server.js");
        assertThat(CommandLine.format(new String[0])).isEmpty();
        assertThat(CommandLine.format(null)).isEmpty();
    }

    @Test
    void tagsSplitOnCommasAndWhitespaceAndAreLowerCasedAndDeduplicated() {
        assertThat(CommandLine.tags(" EU-West, ssd ,eu-west  gpu "))
                .containsExactly("eu-west", "ssd", "gpu");
        assertThat(CommandLine.tags(null)).isEmpty();
        assertThat(CommandLine.tags(" , , ")).isEmpty();
    }
}
