package com.github.lechandonga.cqe;

import com.github.lechandonga.cqe.type.ColumnSchema;
import com.github.lechandonga.cqe.type.DataType;
import com.github.lechandonga.cqe.type.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SmokeTest {

    private ColumnarQueryEngine engineWithUsers() {
        ColumnarQueryEngine engine = new ColumnarQueryEngine();
        engine.catalog().createTable("users", new Schema(List.of(
                ColumnSchema.of("id", DataType.INT),
                ColumnSchema.of("name", DataType.STRING),
                ColumnSchema.of("age", DataType.INT))));
        engine.catalog().insertRows("users", List.of(
                new Object[]{1, "alice", 30},
                new Object[]{2, "bob", 25},
                new Object[]{3, null, 40},
                new Object[]{2, "bob", 25}));
        return engine;
    }

    @Test
    void selectStarAndFilter() {
        ColumnarQueryEngine engine = engineWithUsers();
        QueryResult result = engine.execute("SELECT * FROM users WHERE age >= 30 ORDER BY id ASC");
        assertEquals(2, result.rowCount());
        assertEquals(1, result.rows().get(0)[0]);
        assertEquals(3, result.rows().get(1)[0]);
        assertNull(result.rows().get(1)[1]);
    }

    @Test
    void groupAggregateWithNulls() {
        ColumnarQueryEngine engine = engineWithUsers();
        QueryResult result = engine.execute(
                "SELECT count(*) c, sum(age) s, avg(age) a, min(name) mn FROM users GROUP BY id ORDER BY id");
        assertEquals(3, result.rowCount());
        // id=3: name 为空组 -> min 为 null
        assertEquals(40, result.rows().get(2)[1]);
        assertNull(result.rows().get(2)[3]);
    }

    @Test
    void syntaxErrorHasPosition() {
        ColumnarQueryEngine engine = engineWithUsers();
        QueryException ex = assertThrows(QueryException.class,
                () -> engine.execute("SELECT FROM users"));
        assertEquals(QueryException.Code.SYNTAX_ERROR, ex.code());
        assertTrue(ex.position() >= 0);
    }

    @Test
    void unknownColumnRejected() {
        ColumnarQueryEngine engine = engineWithUsers();
        QueryException ex = assertThrows(QueryException.class,
                () -> engine.execute("SELECT nope FROM users"));
        assertEquals(QueryException.Code.UNKNOWN_COLUMN, ex.code());
    }

    @Test
    void typeMismatchRejected() {
        ColumnarQueryEngine engine = engineWithUsers();
        assertEquals(QueryException.Code.TYPE_MISMATCH,
                assertThrows(QueryException.class,
                        () -> engine.execute("SELECT * FROM users WHERE age = 'x'")).code());
    }
}
