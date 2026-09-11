package lhqm.furimeo.wisper.migration;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a migration file into the statements JDBC will accept one at a time.
 *
 * <p>PostgreSQL's wire protocol takes one statement per {@code execute}, so a file has
 * to be cut on semicolons. Doing that naively breaks on the two constructs this schema
 * actually contains: a dollar-quoted function body, and a string literal with a
 * semicolon inside it. Both are handled here, and the alternative - "just do not write
 * those" - is a rule nobody remembers at two in the morning while writing a trigger.
 *
 * <p>Line comments are dropped. Block comments are left alone: PostgreSQL parses them,
 * and stripping them correctly means tracking nesting, which is more machinery than a
 * migration file justifies.
 */
final class SqlStatements {

    private SqlStatements() {
    }

    static List<String> split(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder(sql.length());

        int index = 0;
        while (index < sql.length()) {
            char character = sql.charAt(index);

            if (character == '-' && next(sql, index) == '-') {
                index = skipToEndOfLine(sql, index);
                continue;
            }
            if (character == '\'' || character == '"') {
                int end = endOfQuoted(sql, index, character);
                current.append(sql, index, end);
                index = end;
                continue;
            }
            if (character == '$') {
                int tagEnd = dollarTagEnd(sql, index);
                if (tagEnd > 0) {
                    int end = endOfDollarQuoted(sql, index, sql.substring(index, tagEnd));
                    current.append(sql, index, end);
                    index = end;
                    continue;
                }
            }
            if (character == ';') {
                add(statements, current);
                index++;
                continue;
            }

            current.append(character);
            index++;
        }

        add(statements, current);
        return statements;
    }

    private static void add(List<String> statements, StringBuilder current) {
        String statement = current.toString().strip();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
        current.setLength(0);
    }

    private static char next(String sql, int index) {
        return index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
    }

    /** Past the newline, so the comment leaves no blank fragment behind. */
    private static int skipToEndOfLine(String sql, int index) {
        int newline = sql.indexOf('\n', index);
        return newline < 0 ? sql.length() : newline + 1;
    }

    /**
     * The index just past the closing quote. A doubled quote inside is an escaped quote,
     * not the end - {@code 'it''s'} is one literal, and treating it as two ends the
     * statement in the middle of a string.
     */
    private static int endOfQuoted(String sql, int start, char quote) {
        int index = start + 1;
        while (index < sql.length()) {
            char character = sql.charAt(index);
            if (character == quote) {
                if (next(sql, index) == quote) {
                    index += 2;
                    continue;
                }
                return index + 1;
            }
            index++;
        }
        throw new IllegalArgumentException("Unterminated " + quote + " literal in migration SQL");
    }

    /**
     * The index just past a dollar-quote opening tag, or -1 when this {@code $} is not
     * one - {@code $1} in a prepared statement and {@code $body$} look alike for exactly
     * one character.
     */
    private static int dollarTagEnd(String sql, int start) {
        int index = start + 1;
        while (index < sql.length()) {
            char character = sql.charAt(index);
            if (character == '$') {
                return index + 1;
            }
            if (!Character.isLetterOrDigit(character) && character != '_') {
                return -1;
            }
            index++;
        }
        return -1;
    }

    private static int endOfDollarQuoted(String sql, int start, String tag) {
        int close = sql.indexOf(tag, start + tag.length());
        if (close < 0) {
            throw new IllegalArgumentException("Unterminated " + tag + " block in migration SQL");
        }
        return close + tag.length();
    }
}
