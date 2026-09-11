package lhqm.furimeo.wisper.migration;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlStatementsTest {

    @Test
    void splitsOnSemicolonsAndDropsBlankFragments() {
        List<String> statements = SqlStatements.split("""
                CREATE TABLE node (id bigserial PRIMARY KEY);

                CREATE INDEX node_name_idx ON node (name);
                """);

        assertThat(statements).containsExactly(
                "CREATE TABLE node (id bigserial PRIMARY KEY)",
                "CREATE INDEX node_name_idx ON node (name)");
    }

    @Test
    void dropsLineComments() {
        List<String> statements = SqlStatements.split("""
                -- Nodes are enrolled, never created by hand.
                CREATE TABLE node (id bigserial PRIMARY KEY); -- trailing note
                """);

        assertThat(statements).containsExactly("CREATE TABLE node (id bigserial PRIMARY KEY)");
    }

    @Test
    void keepsSemicolonsInsideStringLiterals() {
        List<String> statements = SqlStatements.split(
                "INSERT INTO plan (name) VALUES ('starter; not free');\n"
                + "SELECT 1;");

        assertThat(statements).containsExactly(
                "INSERT INTO plan (name) VALUES ('starter; not free')",
                "SELECT 1");
    }

    @Test
    void keepsDoubledQuotesInsideStringLiterals() {
        List<String> statements = SqlStatements.split("INSERT INTO note (body) VALUES ('it''s; fine');");

        assertThat(statements).containsExactly("INSERT INTO note (body) VALUES ('it''s; fine')");
    }

    @Test
    void keepsSemicolonsInsideQuotedIdentifiers() {
        List<String> statements = SqlStatements.split("CREATE TABLE \"odd;name\" (id int);");

        assertThat(statements).containsExactly("CREATE TABLE \"odd;name\" (id int)");
    }

    @Test
    void keepsDollarQuotedFunctionBodiesWhole() {
        List<String> statements = SqlStatements.split("""
                CREATE FUNCTION touch() RETURNS trigger AS $body$
                BEGIN
                  NEW.updated_at := now();
                  RETURN NEW;
                END;
                $body$ LANGUAGE plpgsql;
                SELECT 1;
                """);

        assertThat(statements).hasSize(2);
        assertThat(statements.get(0))
                .startsWith("CREATE FUNCTION touch()")
                .contains("NEW.updated_at := now();")
                .endsWith("LANGUAGE plpgsql");
        assertThat(statements.get(1)).isEqualTo("SELECT 1");
    }

    @Test
    void treatsAPositionalParameterAsOrdinaryText() {
        List<String> statements = SqlStatements.split("SELECT $1 FROM node; SELECT 2;");

        assertThat(statements).containsExactly("SELECT $1 FROM node", "SELECT 2");
    }

    @Test
    void toleratesAMissingTrailingSemicolon() {
        assertThat(SqlStatements.split("SELECT 1")).containsExactly("SELECT 1");
    }

    @Test
    void rejectsAnUnterminatedLiteralRatherThanSplittingInsideIt() {
        assertThatThrownBy(() -> SqlStatements.split("INSERT INTO note (body) VALUES ('oops;"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unterminated");
    }

    @Test
    void rejectsAnUnterminatedDollarBlock() {
        assertThatThrownBy(() -> SqlStatements.split("CREATE FUNCTION f() AS $body$ BEGIN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unterminated");
    }
}
