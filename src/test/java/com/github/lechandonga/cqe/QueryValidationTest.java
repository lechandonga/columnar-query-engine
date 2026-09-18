package com.github.lechandonga.cqe;

import com.github.lechandonga.cqe.type.ColumnSchema;
import com.github.lechandonga.cqe.type.DataType;
import com.github.lechandonga.cqe.type.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 语法错误、未知字段、类型不匹配、聚合误用等必须被拒绝并给出可区分原因。 */
class QueryValidationTest {

    private ColumnarQueryEngine engine;

    @BeforeEach
    void setUp() {
        engine = new ColumnarQueryEngine();
        engine.catalog().createTable("orders", new Schema(List.of(
                ColumnSchema.of("id", DataType.INT),
                ColumnSchema.of("cid", DataType.INT),
                ColumnSchema.of("amount", DataType.DOUBLE),
                ColumnSchema.of("paid", DataType.BOOLEAN),
                ColumnSchema.of("memo", DataType.STRING))));
    }

    @Test
    void rejectsMalformedSyntaxWithPosition() {
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute("SELCT 1 FROM orders"));
        assertEquals(QueryException.Code.SYNTAX_ERROR, e.code());
        assertTrue(e.position() >= 0, "语法错误必须携带字符位置");
    }

    @Test
    void rejectsUnterminatedString() {
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute("SELECT * FROM orders WHERE memo = 'abc"));
        assertEquals(QueryException.Code.SYNTAX_ERROR, e.code());
    }

    @Test
    void rejectsUnknownTable() {
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute("SELECT * FROM ghost"));
        assertEquals(QueryException.Code.UNKNOWN_TABLE, e.code());
    }

    @Test
    void rejectsUnknownColumn() {
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute("SELECT nope FROM orders"));
        assertEquals(QueryException.Code.UNKNOWN_COLUMN, e.code());
    }

    @Test
    void rejectsAmbiguousColumn() {
        engine.catalog().createTable("customers", new Schema(List.of(
                ColumnSchema.of("id", DataType.INT),
                ColumnSchema.of("cid", DataType.INT))));
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute(
                        "SELECT cid FROM orders JOIN customers ON orders.cid = customers.id"));
        assertEquals(QueryException.Code.AMBIGUOUS_COLUMN, e.code());
    }

    @Test
    void rejectsStringNumberComparison() {
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute("SELECT * FROM orders WHERE amount = '10'"));
        assertEquals(QueryException.Code.TYPE_MISMATCH, e.code());
    }

    @Test
    void rejectsArithmeticOnString() {
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute("SELECT memo + 1 FROM orders"));
        assertEquals(QueryException.Code.TYPE_MISMATCH, e.code());
    }

    @Test
    void rejectsAndOnNumber() {
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute("SELECT * FROM orders WHERE amount AND paid"));
        assertEquals(QueryException.Code.TYPE_MISMATCH, e.code());
    }

    @Test
    void rejectsAggregateInWhere() {
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute("SELECT * FROM orders WHERE count(*) > 0"));
        assertEquals(QueryException.Code.AGGREGATE_MISUSE, e.code());
    }

    @Test
    void rejectsNonGroupedColumnInProjection() {
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute("SELECT cid, sum(amount) FROM orders"));
        assertEquals(QueryException.Code.GROUPING_ERROR, e.code());
    }

    @Test
    void rejectsUnknownAggregate() {
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute("SELECT median(amount) FROM orders"));
        assertEquals(QueryException.Code.UNSUPPORTED_FEATURE, e.code());
    }

    @Test
    void rejectsSumOnString() {
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute("SELECT sum(memo) FROM orders"));
        assertEquals(QueryException.Code.TYPE_MISMATCH, e.code());
    }

    @Test
    void rejectsNegativeLimit() {
        // -1 经一元运算产生 INT，解析器会把它当作表达式；LIMIT 仅接受非负整数 token
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute("SELECT * FROM orders LIMIT -1"));
        assertEquals(QueryException.Code.SYNTAX_ERROR, e.code());
    }

    @Test
    void rejectsOrderByNonProjectedWithDistinct() {
        QueryException e = assertThrows(QueryException.class,
                () -> engine.execute(
                        "SELECT DISTINCT cid FROM orders ORDER BY amount"));
        assertEquals(QueryException.Code.GROUPING_ERROR, e.code());
    }

    @Test
    void acceptsIntDoubleCrossComparison() {
        assertDoesNotThrow(() ->
                engine.prepare("SELECT * FROM orders WHERE cid = amount"));
    }

    @Test
    void differentErrorCodesAreDistinguishable() {
        QueryException syntax = assertThrows(QueryException.class,
                () -> engine.execute("SELECT FROM"));
        QueryException unknown = assertThrows(QueryException.class,
                () -> engine.execute("SELECT x FROM y"));
        assertNotEquals(syntax.code(), unknown.code());
        assertNotEquals(syntax.getMessage(), unknown.getMessage());
    }
}
