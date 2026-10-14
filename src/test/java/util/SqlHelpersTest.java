package util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlHelpersTest {

    @Test
    void quotePgEscapesDoubleQuotes() {
        assertEquals("\"a\"\"b\"", SqlHelpers.quotePg("a\"b"));
        assertEquals("\"normal\"", SqlHelpers.quotePg("normal"));
    }

    @Test
    void quoteMyEscapesBackticks() {
        assertEquals("`a``b`", SqlHelpers.quoteMy("a`b"));
    }

    @Test
    void quoteMsEscapesBrackets() {
        assertEquals("[a]]b]", SqlHelpers.quoteMs("a]b"));
    }

    @Test
    void clampLimitBounds() {
        assertEquals(1, SqlHelpers.clampLimit(0));
        assertEquals(100, SqlHelpers.clampLimit(100));
        assertEquals(5000, SqlHelpers.clampLimit(999999));
    }

    @Test
    void hostPortValidation() {
        assertTrue(SqlHelpers.isValidHostAndPort("127.0.0.1", "3306"));
        assertTrue(SqlHelpers.isValidHostAndPort("db.example.com", "5432"));
        assertFalse(SqlHelpers.isValidHostAndPort("", "3306"));
        assertFalse(SqlHelpers.isValidHostAndPort("host", ""));
        assertFalse(SqlHelpers.isValidHostAndPort("host", "abc"));
        assertFalse(SqlHelpers.isValidHostAndPort("host", "70000"));
    }
}
