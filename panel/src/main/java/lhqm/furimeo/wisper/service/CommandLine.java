package lhqm.furimeo.wisper.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns the one line a customer types into the argument vector the node execs, and back
 * again for the form that shows it.
 *
 * <p>{@code service.command}, {@code service.entrypoint} and {@code cron_task.command} are
 * {@code text[]} in the schema and {@code repeated string} on the wire, and they are
 * arrays for a reason worth restating: nothing on either side of this system ever builds a
 * shell string (AGENTS.md §5). A service named {@code api; rm -rf /} is a service with a
 * silly name, not a second command.
 *
 * <p>A text input is still the right control - nobody wants to add arguments one row at a
 * time on a phone - so the split happens here, once, with quoting rules a person already
 * knows:
 *
 * <pre>
 * node server.js --port 8080      -> [node, server.js, --port, 8080]
 * sh -c 'echo hello world'        -> [sh, -c, echo hello world]
 * /bin/my\ app --flag="a b"       -> [/bin/my app, --flag=a b]
 * </pre>
 *
 * <p>An unclosed quote is closed at the end of the line rather than rejected. The customer
 * is shown the parsed argv on the page they land on, which tells them more than an error
 * about a character they cannot see.
 */
public final class CommandLine {

    private CommandLine() {
    }

    /**
     * Splits a typed line into argv.
     *
     * @return an immutable list, empty for null, blank or whitespace-only input
     */
    public static List<String> parse(String line) {
        if (line == null || line.isBlank()) {
            return List.of();
        }
        List<String> argv = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inWord = false;
        char quote = 0;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else if (c == '\\' && quote == '"' && i + 1 < line.length()) {
                    current.append(line.charAt(++i));
                } else {
                    current.append(c);
                }
                continue;
            }
            switch (c) {
                case '\'', '"' -> {
                    quote = c;
                    // An empty quoted string is still an argument: "" means "".
                    inWord = true;
                }
                case '\\' -> {
                    if (i + 1 < line.length()) {
                        current.append(line.charAt(++i));
                        inWord = true;
                    }
                }
                default -> {
                    if (Character.isWhitespace(c)) {
                        if (inWord) {
                            argv.add(current.toString());
                            current.setLength(0);
                            inWord = false;
                        }
                    } else {
                        current.append(c);
                        inWord = true;
                    }
                }
            }
        }
        if (inWord) {
            argv.add(current.toString());
        }
        return List.copyOf(argv);
    }

    /**
     * Renders argv back into one line, quoting the arguments that need it.
     *
     * <p>{@link #parse} of the result is the argv that went in, which is the property the
     * settings form depends on: showing a command and saving it unchanged must not alter
     * it.
     */
    public static String format(String[] argv) {
        if (argv == null || argv.length == 0) {
            return "";
        }
        StringBuilder line = new StringBuilder();
        for (String argument : argv) {
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(quoteIfNeeded(argument));
        }
        return line.toString();
    }

    /**
     * Splits the placement-tag input: commas or whitespace, lower-cased, de-duplicated,
     * order preserved.
     *
     * <p>Order is preserved because the customer typed it and a reordered list looks like
     * the panel changed something. De-duplication happens because {@code node.tags} is
     * matched with a containment operator, where a repeat means nothing.
     */
    public static List<String> tags(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        Set<String> tags = new LinkedHashSet<>();
        for (String candidate : input.split("[,\\s]+")) {
            String tag = candidate.strip().toLowerCase(Locale.ROOT);
            if (!tag.isEmpty()) {
                tags.add(tag);
            }
        }
        return List.copyOf(tags);
    }

    private static String quoteIfNeeded(String argument) {
        if (argument.isEmpty()) {
            return "''";
        }
        boolean plain = true;
        for (int i = 0; i < argument.length() && plain; i++) {
            char c = argument.charAt(i);
            plain = !Character.isWhitespace(c) && c != '\'' && c != '"' && c != '\\';
        }
        if (plain) {
            return argument;
        }
        // Single quotes, with the shell's own way out of a single quote inside them, so
        // the round trip survives an argument that contains one.
        return "'" + argument.replace("'", "'\\''") + "'";
    }
}
