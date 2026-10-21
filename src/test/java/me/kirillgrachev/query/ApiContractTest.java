package me.kirillgrachev.query;

import me.kirillgrachev.query.exec.BatchResult;
import me.kirillgrachev.query.exec.Sql;
import me.kirillgrachev.query.exception.ErrorCategory;
import me.kirillgrachev.query.exception.ErrorCode;
import me.kirillgrachev.query.exception.QueryException;
import me.kirillgrachev.query.sql.Conditions;
import me.kirillgrachev.query.sql.Dialect;
import me.kirillgrachev.query.sql.Order;
import me.kirillgrachev.query.sql.Select;
import me.kirillgrachev.query.sql.SqlStatement;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Контракты публичного API: валидация аргументов, фабрики условий, исключение.
 *
 * <p>Набор покрывает то, что не проверяется тестами генерации SQL и исполнения:
 * поведение на некорректных входных данных и форму сообщений. Для библиотеки это
 * не второстепенно — вызывающая сторона видит именно исключения, и если сообщение
 * не сообщает, какой аргумент и почему отвергнут, диагностика ложится на неё.
 */
class ApiContractTest {

    private static Sql h2() {

        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:api_contract;DB_CLOSE_DELAY=-1");

        return Sql.of(ds, Dialect.H2);

    }

    @Test
    void sqlContextRejectsNullArguments() {

        JdbcDataSource ds = new JdbcDataSource();

        assertEquals(ErrorCode.NULL_ARGUMENT,
                assertThrows(QueryException.class, () -> Sql.of(null, Dialect.H2)).getCode());
        assertEquals(ErrorCode.NULL_ARGUMENT,
                assertThrows(QueryException.class, () -> Sql.of(ds, null)).getCode());

    }

    @Test
    void sqlContextExposesItsDependencies() {

        JdbcDataSource ds = new JdbcDataSource();
        Sql sql = Sql.of(ds, Dialect.POSTGRES);

        assertSame(ds, sql.getDataSource());
        assertEquals(Dialect.POSTGRES, sql.getDialect());

    }

    @Test
    void sqlRejectsNullTransactionBody() {

        assertEquals(ErrorCode.NULL_ARGUMENT,
                assertThrows(QueryException.class,
                        () -> h2().inTransaction((Sql.TransactionBody<Object>) null)).getCode());
        assertEquals(ErrorCode.NULL_ARGUMENT,
                assertThrows(QueryException.class,
                        () -> h2().inTransactionVoid(null)).getCode());

    }

    @Test
    void inTransactionVoidCommitsWithoutReturnValue() throws SQLException {

        Sql sql = h2();

        try (var con = sql.getDataSource().getConnection(); var st = con.createStatement()) {
            st.execute("DROP TABLE IF EXISTS \"kv\"");
            st.execute("CREATE TABLE \"kv\" (\"k\" VARCHAR(8) PRIMARY KEY, \"v\" INT NOT NULL)");
        }

        sql.inTransactionVoid(tx -> tx.update(
                Query.insertInto("kv").value("k", "a").value("v", 1)));

        assertEquals(1, sql.queryOne(Query.select().expr("COUNT(*)").from("kv"),
                rs -> rs.getInt(1)).orElseThrow());

    }

    @Test
    void inTransactionRollsBackVoidBodyOnFailure() throws SQLException {

        Sql sql = h2();

        try (var con = sql.getDataSource().getConnection(); var st = con.createStatement()) {
            st.execute("DROP TABLE IF EXISTS \"kv2\"");
            st.execute("CREATE TABLE \"kv2\" (\"k\" VARCHAR(8) PRIMARY KEY, \"v\" INT NOT NULL)");
        }

        assertThrows(IllegalStateException.class, () -> sql.inTransactionVoid(tx -> {
            tx.update(Query.insertInto("kv2").value("k", "a").value("v", 1));

            throw new IllegalStateException("rollback");
        }));

        assertEquals(0, sql.queryOne(Query.select().expr("COUNT(*)").from("kv2"),
                rs -> rs.getInt(1)).orElseThrow());

    }

    @Test
    void withConnectionClosesConnectionAfterUse() throws SQLException {

        Sql sql = h2();

        Integer result = sql.withConnection(con -> {
            try (var st = con.createStatement(); var rs = st.executeQuery("SELECT 42")) {
                rs.next();

                return rs.getInt(1);
            }
        });

        assertEquals(42, result);

    }

    @Test
    void withConnectionRejectsNull() {

        assertEquals(ErrorCode.NULL_ARGUMENT,
                assertThrows(QueryException.class, () -> h2().withConnection(null)).getCode());

    }

    @Test
    void queryFactoryRejectsNullArguments() {

        assertEquals(ErrorCode.NULL_ARGUMENT,
                assertThrows(QueryException.class, () -> Query.select((String[]) null)).getCode());
        assertEquals(ErrorCode.NULL_ARGUMENT,
                assertThrows(QueryException.class, () -> Query.select("a", null)).getCode());
        assertEquals(ErrorCode.NULL_ARGUMENT,
                assertThrows(QueryException.class, () -> Query.insertInto(null)).getCode());
        assertEquals(ErrorCode.NULL_ARGUMENT,
                assertThrows(QueryException.class, () -> Query.insertInto(null, "t")).getCode());
        assertEquals(ErrorCode.NULL_ARGUMENT,
                assertThrows(QueryException.class, () -> Query.insertInto("s", null)).getCode());
        assertEquals(ErrorCode.NULL_ARGUMENT,
                assertThrows(QueryException.class, () -> Query.raw(null)).getCode());
        assertEquals(ErrorCode.NULL_ARGUMENT,
                assertThrows(QueryException.class, () -> Query.raw("SELECT 1", (Object[]) null)).getCode());

    }

    @Test
    void schemaQualifiedFactoriesQuoteBothParts() {

        assertEquals("UPDATE \"shop\".\"t\" SET \"a\" = ? WHERE \"id\" = ?",
                Query.update("shop", "t").value("a", 1).where("id", 2).getSql(Dialect.POSTGRES));
        assertEquals("DELETE FROM \"shop\".\"t\" WHERE \"id\" = ?",
                Query.deleteFrom("shop", "t").where("id", 1).getSql(Dialect.POSTGRES));
        assertTrue(Query.createTable("shop", "t").intColumn("a").getSql(Dialect.POSTGRES)
                .contains("\"shop\".\"t\""));

    }

    @Test
    void conditionsCoverAllComparisonOperators() {

        assertEquals("\"a\" <> ?", Conditions.ne("a", 1).render(Dialect.POSTGRES));
        assertEquals("\"a\" > ?", Conditions.gt("a", 1).render(Dialect.POSTGRES));
        assertEquals("\"a\" >= ?", Conditions.ge("a", 1).render(Dialect.POSTGRES));
        assertEquals("\"a\" < ?", Conditions.lt("a", 1).render(Dialect.POSTGRES));
        assertEquals("\"a\" <= ?", Conditions.le("a", 1).render(Dialect.POSTGRES));
        assertEquals("\"a\" LIKE ?", Conditions.like("a", "x%").render(Dialect.POSTGRES));
        assertEquals("\"a\" IS NULL", Conditions.isNull("a").render(Dialect.POSTGRES));
        assertEquals("\"a\" IS NOT NULL", Conditions.isNotNull("a").render(Dialect.POSTGRES));

    }

    @Test
    void conditionsCarryParamsInRenderOrder() {

        var condition = Conditions.allOf(
                Conditions.eq("a", 1),
                Conditions.anyOf(Conditions.gt("b", 2), Conditions.lt("c", 3)));

        assertEquals("(\"a\" = ? AND (\"b\" > ? OR \"c\" < ?))", condition.render(Dialect.POSTGRES));
        assertEquals(List.of(1, 2, 3), condition.getParams());

    }

    @Test
    void inAndNotInRenderPlaceholderLists() {

        assertEquals("\"a\" IN (?, ?, ?)",
                Conditions.in("a", List.of(1, 2, 3)).render(Dialect.POSTGRES));
        assertEquals("\"a\" NOT IN (?, ?)",
                Conditions.notIn("a", List.of(1, 2)).render(Dialect.POSTGRES));
        assertEquals(List.of(1, 2, 3), Conditions.in("a", List.of(1, 2, 3)).getParams());

    }

    @Test
    void emptyInListIsRejected() {

        // "IN ()" является синтаксической ошибкой в большинстве диалектов, а
        // молчаливая замена на FALSE спрятала бы ошибку вызывающей стороны.
        QueryException e = assertThrows(QueryException.class, () -> Conditions.in("a", List.of()));

        assertEquals(ErrorCode.INVALID_ARGUMENT_VALUE, e.getCode());
        assertEquals(ErrorCode.INVALID_ARGUMENT_VALUE,
                assertThrows(QueryException.class, () -> Conditions.notIn("a", List.of())).getCode());

    }

    @Test
    void notWrapsConditionInParentheses() {

        assertEquals("NOT (\"a\" = ?)", Conditions.not(Conditions.eq("a", 1)).render(Dialect.POSTGRES));
        assertEquals(List.of(1), Conditions.not(Conditions.eq("a", 1)).getParams());

    }

    @Test
    void singleElementCompositeIsReturnedAsIs() {

        var only = Conditions.eq("a", 1);

        assertSame(only, Conditions.allOf(only));
        assertSame(only, Conditions.anyOf(only));

    }

    @Test
    void emptyCompositeAndNullsAreRejected() {

        assertEquals(ErrorCode.INVALID_ARGUMENT_VALUE,
                assertThrows(QueryException.class, () -> Conditions.allOf()).getCode());
        assertEquals(ErrorCode.INVALID_ARGUMENT_VALUE,
                assertThrows(QueryException.class, () -> Conditions.anyOf()).getCode());
        assertEquals(ErrorCode.NULL_ARGUMENT,
                assertThrows(QueryException.class, () -> Conditions.not(null)).getCode());
        assertEquals(ErrorCode.NULL_ARGUMENT,
                assertThrows(QueryException.class, () -> Conditions.eq(null, 1)).getCode());
        assertEquals(ErrorCode.NULL_ARGUMENT,
                assertThrows(QueryException.class, () -> Conditions.eq("a", null)).getCode());

    }

    @Test
    void rawConditionValidatesPlaceholders() {

        assertEquals("(a = ? AND b = ?)",
                Conditions.raw("a = ? AND b = ?", 1, 2).render(Dialect.POSTGRES));
        assertEquals(ErrorCode.PLACEHOLDER_COUNT_MISMATCH,
                assertThrows(QueryException.class, () -> Conditions.raw("a = ?", 1, 2)).getCode());

    }

    @Test
    void statementWhereRejectsNullCondition() {

        assertEquals(ErrorCode.NULL_ARGUMENT,
                assertThrows(QueryException.class,
                        () -> Query.select("a").where((me.kirillgrachev.query.sql.Condition) null)).getCode());

    }

    @Test
    void whereNullableChoosesIsNullForNullValue() {

        Select select = Query.select("a").from("t").whereNullable("b", null);

        assertEquals("SELECT \"a\" FROM \"t\" WHERE \"b\" IS NULL", select.getSql(Dialect.POSTGRES));
        assertTrue(select.getParams().isEmpty());

    }

    @Test
    void selectColumnAndExpressionAliases() {

        // select(String...) перечисляет колонки, а алиас задаётся отдельным
        // методом col(name, alias) / expr(sql, alias, params). Смешивать их нельзя:
        // select("name", "n") означает две колонки, а не колонку с алиасом.
        String sql = Query.select().col("name", "n").expr("COUNT(*)", "cnt")
                .from("t").getSql(Dialect.POSTGRES);

        assertEquals("SELECT \"name\" AS \"n\", COUNT(*) AS \"cnt\" FROM \"t\"", sql);
        assertEquals("SELECT \"name\", \"n\" FROM \"t\"",
                Query.select("name", "n").from("t").getSql(Dialect.POSTGRES));

    }

    @Test
    void rightJoinIsRendered() {

        String sql = Query.select().all().from("a")
                .rightJoin("b", Conditions.eq("x", 1))
                .getSql(Dialect.MYSQL);

        // Условия сравнения рендерятся без скобок: они не нужны, когда условие
        // одно. Скобки появляются у составных узлов (allOf/anyOf) и у raw().
        assertTrue(sql.contains("RIGHT JOIN `b` ON `x` = ?"), sql);

    }

    @Test
    void orderByExpressionIsRenderedWithDirection() {

        // Order создаётся только через Select: фабрики Order package-private,
        // поскольку элемент сортировки не имеет смысла в отрыве от запроса.
        Select select = Query.select("a").from("t")
                .orderByExpr("lower(name)", Order.Direction.ASC)
                .orderBy("id", Order.Direction.DESC);

        String sql = select.getSql(Dialect.POSTGRES);

        assertTrue(sql.contains("ORDER BY lower(name) ASC, \"id\" DESC"), sql);
        assertEquals(Order.Direction.DESC, Order.Direction.valueOf("DESC"));

    }

    @Test
    void batchResultDefaultsAreCoherent() {

        BatchResult empty = BatchResult.empty();

        assertEquals(0, empty.statementsExecuted());
        assertEquals(0L, empty.rowsAffected());
        assertEquals(0, empty.unknownCounts());
        assertTrue(empty.rawCounts().isEmpty());
        assertTrue(empty.isRowCountExact());
        assertEquals(0L, empty.effectiveRows());

    }

    @Test
    void batchResultCountsUnknownAsExecuted() {

        // SUCCESS_NO_INFO означает «строка исполнена успешно, число строк неизвестно»,
        // поэтому effectiveRows() учитывает её, а rowsAffected() — нет.
        BatchResult result = new BatchResult(3, 1, 2, List.of(1, -2, -2));

        assertTrue(!result.isRowCountExact());
        assertEquals(1L, result.rowsAffected());
        assertEquals(3L, result.effectiveRows());

    }

    @Test
    void exceptionFactoriesCoverAllShapes() {

        QueryException bare = QueryException.of(ErrorCode.INVALID_IDENTIFIER);

        assertEquals(ErrorCode.INVALID_IDENTIFIER, bare.getCode());
        assertEquals(ErrorCategory.VALIDATION, bare.getCategory());
        assertEquals("Identifier is not valid", bare.getMessage());
        assertTrue(bare.getContext().isEmpty());
        assertNull(bare.getCause());

        QueryException withMessage = QueryException.of(ErrorCode.INVALID_IDENTIFIER, "custom");
        assertEquals("custom", withMessage.getMessage());

        SQLException cause = new SQLException("driver said no");
        QueryException withCause = QueryException.of(ErrorCode.SQL_EXECUTION_FAILED, "failed", cause);
        assertSame(cause, withCause.getCause());

        QueryException withContext = QueryException.of(ErrorCode.INVALID_IDENTIFIER, "custom",
                Map.of("column", "a`b"));
        assertEquals("a`b", withContext.getContextValue("column", String.class));

    }

    @Test
    void exceptionContextIsImmutable() {

        QueryException e = QueryException.of(ErrorCode.INVALID_IDENTIFIER, "x", Map.of("k", 1));

        assertThrows(UnsupportedOperationException.class, () -> e.getContext().put("k2", 2));

    }

    @Test
    void getContextValueReturnsNullOnTypeMismatch() {

        QueryException e = QueryException.of(ErrorCode.INVALID_IDENTIFIER, "x", Map.of("k", 1));

        assertEquals(1, (int) e.getContextValue("k", Integer.class));
        assertNull(e.getContextValue("k", String.class), "несовпадение типа должно давать null");
        assertNull(e.getContextValue("absent", Integer.class));

    }

    @Test
    void exceptionToStringIncludesCodeAndContext() {

        QueryException e = QueryException.of(ErrorCode.INVALID_IDENTIFIER, "bad name", Map.of("k", "v"));
        String text = e.toString();

        assertTrue(text.contains("[INVALID_IDENTIFIER]"), text);
        assertTrue(text.contains("bad name"), text);
        assertTrue(text.contains("k=v"), text);

        // Без контекста блок context не печатается вовсе.
        assertTrue(!QueryException.of(ErrorCode.INVALID_IDENTIFIER).toString().contains("context"));

    }

    @Test
    void everyErrorCodeHasMessageAndCategory() {

        for (ErrorCode code : ErrorCode.values()) {
            assertNotNull(code.getDefaultMessage(), code.name());
            assertTrue(!code.getDefaultMessage().isBlank(), code.name());
            assertNotNull(code.getCategory(), code.name());
        }

    }

    @Test
    void dialectsExposeQuoteCharAndPagination() {

        assertEquals('`', Dialect.MYSQL.getQuoteChar());
        assertEquals('"', Dialect.POSTGRES.getQuoteChar());
        assertEquals('[', Dialect.SQL_SERVER.getQuoteChar());
        assertEquals('\0', Dialect.NONE.getQuoteChar());
        assertEquals(Dialect.PaginationStyle.LIMIT_OFFSET, Dialect.MYSQL.getPaginationStyle());
        assertEquals(Dialect.PaginationStyle.OFFSET_FETCH, Dialect.ORACLE.getPaginationStyle());

    }

    @Test
    void dialectPaginationHandlesAbsentLimitAndOffset() {

        assertEquals("", Dialect.MYSQL.renderPagination(null, null));
        assertEquals(" LIMIT 5", Dialect.MYSQL.renderPagination(5L, null));
        assertEquals(" OFFSET 5", Dialect.MYSQL.renderPagination(null, 5L));

    }

    @Test
    void statementInterfaceIsImplementedByEveryBuilder() {

        List<SqlStatement> statements = List.of(
                Query.select("a").from("t"),
                Query.insertInto("t").value("a", 1),
                Query.update("t").value("a", 1).where("b", 2),
                Query.deleteFrom("t").where("b", 2),
                Query.createTable("t").intColumn("a"),
                Query.raw("SELECT 1"));

        for (SqlStatement st : statements) {
            assertNotNull(st.getSql(Dialect.POSTGRES));
            assertNotNull(st.getParams());
        }

    }

}
