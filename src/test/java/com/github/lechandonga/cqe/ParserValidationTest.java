package com.github.lechandonga.cqe;

import com.github.lechandonga.cqe.engine.QueryEngine;
import com.github.lechandonga.cqe.error.QueryException;
import com.github.lechandonga.cqe.schema.ColumnSchema;
import com.github.lechandonga.cqe.schema.TableSchema;
import com.github.lechandonga.cqe.storage.DataStore;
import com.github.lechandonga.cqe.type.DataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 语法错误、未知字段、类型不匹配必须被明确拒绝且原因可区分。 */
class ParserValidationTest {

    private QueryEngine engine;

    @BeforeEach
    void setUp() {
        DataStore store = new DataStore();
        store.createTable("users", new TableSchema(java.util.List.of(
                new ColumnSchema("id", DataType.INT),
                new ColumnSchema("name", DataType.STRING),
                new ColumnSchema("age", DataType.INT))));
        engine = new QueryEngine(store);
    }

    @Test
    void syntaxErrorIsRejectedWithPosition() {
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute("SELECT FROM users"));
        assertEquals(QueryException.Category.SYNTAX_ERROR, e.category());
        assertTrue(e.position() >= 0, "syntax error must carry a position");
        assertTrue(e.getMessage().contains("position"));
    }

    @Test
    void unterminatedStringIsSyntaxError() {
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute("SELECT name FROM users WHERE name = 'abc"));
        assertEquals(QueryException.Category.SYNTAX_ERROR, e.category());
    }

    @Test
    void unexpectedTokenIsSyntaxError() {
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute("SELECT name FROM users WHERE age = = 3"));
        assertEquals(QueryException.Category.SYNTAX_ERROR, e.category());
    }

    @Test
    void unknownTableIsRejected() {
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute("SELECT id FROM nope"));
        assertEquals(QueryException.Category.UNKNOWN_TABLE, e.category());
        assertTrue(e.getMessage().contains("nope"));
    }

    @Test
    void unknownColumnIsRejected() {
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute("SELECT email FROM users"));
        assertEquals(QueryException.Category.UNKNOWN_FIELD, e.category());
        assertTrue(e.getMessage().contains("email"));
    }

    @Test
    void unknownColumnInWhereIsRejected() {
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute("SELECT id FROM users WHERE salary > 1"));
        assertEquals(QueryException.Category.UNKNOWN_FIELD, e.category());
    }

    @Test
    void typeMismatchComparisonIsRejected() {
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute("SELECT id FROM users WHERE name > 3"));
        assertEquals(QueryException.Category.TYPE_MISMATCH, e.category());
    }

    @Test
    void nonBooleanWhereIsRejected() {
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute("SELECT id FROM users WHERE name"));
        assertEquals(QueryException.Category.TYPE_MISMATCH, e.category());
    }

    @Test
    void sumOfStringIsRejected() {
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute("SELECT SUM(name) FROM users"));
        assertEquals(QueryException.Category.TYPE_MISMATCH, e.category());
    }

    @Test
    void nonGroupKeyInAggregatedSelectIsRejected() {
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute("SELECT name, COUNT(*) FROM users"));
        assertEquals(QueryException.Category.TYPE_MISMATCH, e.category());
    }

    @Test
    void orderByUnknownOutputColumnIsRejected() {
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute("SELECT id FROM users ORDER BY age"));
        assertEquals(QueryException.Category.UNKNOWN_FIELD, e.category());
    }

    @Test
    void errorsAreDistinctAndNoneReturnEmptySilently() {
        // 语法错误、未知字段、类型不匹配是三种可区分的类别
        assertNotEquals(
                assertThrows(QueryException.class, () -> engine.execute("SELEC 1")).category(),
                assertThrows(QueryException.class, () -> engine.execute("SELECT nope FROM users")).category());
    }
}
