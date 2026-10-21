package me.kirillgrachev.query.sql;

import me.kirillgrachev.query.Query;
import me.kirillgrachev.query.exception.ErrorCode;
import me.kirillgrachev.query.exception.QueryException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты генерации {@code SELECT}.
 *
 * <p>Каждый тест привязан к дефекту исходной реализации, который он закрывает:
 * многостолбцовый {@code ORDER BY}, {@code DISTINCT}, соединения, {@code GROUP BY},
 * диалектная пагинация, отсутствие завершающей точки с запятой и валидация имени
 * схемы. Эталоны проверяются для нескольких диалектов, потому что экранирование
 * идентификаторов — ровно то место, где исходная библиотека была MySQL-only.
 */
class SelectSqlTest {

    @Test
    void rendersColumnsWithDialectQuoting() {

        Select select = Query.select("id", "name").from("users");

        assertEquals("SELECT `id`, `name` FROM `users`", select.getSql(Dialect.MYSQL));
        assertEquals("SELECT \"id\", \"name\" FROM \"users\"", select.getSql(Dialect.POSTGRES));
        assertEquals("SELECT [id], [name] FROM [users]", select.getSql(Dialect.SQL_SERVER));
        assertEquals("SELECT id, name FROM users", select.getSql(Dialect.NONE));

    }

    @Test
    void noTrailingSemicolonAndNoDoubleSpaces() {

        // Исходная библиотека добавляла ";" в конце и порождала артефакт
        // "WHERE (`c` = ?) ;" с пробелом перед точкой с запятой. Оба поведения
        // были закреплены в её тестах как эталонные.
        String sql = Query.select("a").from("t").where("c", 1).getSql(Dialect.MYSQL);

        assertTrue(!sql.endsWith(";"), "запрос не должен заканчиваться точкой с запятой: " + sql);
        assertTrue(!sql.contains("  "), "в запросе не должно быть двойных пробелов: " + sql);
        assertTrue(!sql.contains(" ;"), "в запросе не должно быть пробела перед ';': " + sql);
        assertEquals("SELECT `a` FROM `t` WHERE `c` = ?", sql);

    }

    @Test
    void schemaAndTableAreBothQuoted() {

        Select select = Query.select("a").from("shop", "orders");

        assertEquals("SELECT `a` FROM `shop`.`orders`", select.getSql(Dialect.MYSQL));
        assertEquals("SELECT \"a\" FROM \"shop\".\"orders\"", select.getSql(Dialect.POSTGRES));

    }

    @Test
    void distinctIsRendered() {

        String sql = Query.select("city").distinct().from("users").getSql(Dialect.POSTGRES);

        assertEquals("SELECT DISTINCT \"city\" FROM \"users\"", sql);

    }

    @Test
    void selectAllCannotBeMixedWithColumns() {

        QueryException e = assertThrows(QueryException.class,
                () -> Query.select("a").all());

        assertEquals(ErrorCode.CONFLICTING_CLAUSES, e.getCode());
        assertEquals("SELECT * FROM \"t\"", Query.select().all().from("t").getSql(Dialect.POSTGRES));

    }

    @Test
    void emptySelectionIsAnError() {

        QueryException e = assertThrows(QueryException.class,
                () -> Query.select().from("t").getSql(Dialect.MYSQL));

        assertEquals(ErrorCode.INCOMPLETE_STATEMENT, e.getCode());

    }

    @Test
    void multipleConditionsAreChainedWithAnd() {

        String sql = Query.select("a").from("t")
                .where("x", 1)
                .where("y", 2)
                .getSql(Dialect.MYSQL);

        assertEquals("SELECT `a` FROM `t` WHERE `x` = ? AND `y` = ?", sql);

    }

    @Test
    void orderBySupportsSeveralColumnsWithOwnDirection() {

        // Исходная реализация хранила одно выражение и глобальный флаг desc,
        // поэтому такая сортировка была невыразима в принципе.
        String sql = Query.select("a").from("t")
                .orderBy("name", Order.Direction.DESC)
                .orderBy("id")
                .getSql(Dialect.POSTGRES);

        assertEquals("SELECT \"a\" FROM \"t\" ORDER BY \"name\" DESC, \"id\" ASC", sql);

    }

    @Test
    void orderByExpressionCarriesItsOwnParams() {

        Select select = Query.select("a").from("t")
                .orderByExpr("ABS(score - ?)", Order.Direction.DESC, 10)
                .where("b", 2);

        // Параметры WHERE идут раньше параметров ORDER BY, потому что в SQL
        // клауза WHERE встречается раньше.
        assertEquals(List.of(2, 10), select.getParams());
        assertEquals("SELECT \"a\" FROM \"t\" WHERE \"b\" = ? ORDER BY ABS(score - ?) DESC",
                select.getSql(Dialect.POSTGRES));

    }

    @Test
    void groupByAndHaving() {

        String sql = Query.select().all().from("orders")
                .groupBy("customer_id")
                .having("COUNT(*) > ?", 3)
                .getSql(Dialect.POSTGRES);

        assertEquals("SELECT * FROM \"orders\" GROUP BY \"customer_id\" HAVING COUNT(*) > ?", sql);

    }

    @Test
    void joinsAreRendered() {

        String sql = Query.select("u.name", "o.total")
                .fromAs("users", "u")
                .innerJoin("orders", Conditions.raw("\"o\".\"user_id\" = \"u\".\"id\""))
                .leftJoin("payments", Conditions.raw("\"p\".\"order_id\" = \"o\".\"id\""))
                .getSql(Dialect.POSTGRES);

        assertTrue(sql.startsWith("SELECT \"u.name\", \"o.total\" FROM \"users\" AS \"u\""), sql);
        assertTrue(sql.contains("INNER JOIN \"orders\" ON (\"o\".\"user_id\" = \"u\".\"id\")"), sql);
        assertTrue(sql.contains("LEFT JOIN \"payments\" ON (\"p\".\"order_id\" = \"o\".\"id\")"), sql);

    }

    @Test
    void limitAndOffsetUseDialectSyntax() {

        Select select = Query.select("a").from("t").limit(10).offset(20);

        assertEquals("SELECT `a` FROM `t` LIMIT 10 OFFSET 20", select.getSql(Dialect.MYSQL));
        assertEquals("SELECT \"a\" FROM \"t\" LIMIT 10 OFFSET 20", select.getSql(Dialect.POSTGRES));
        assertEquals("SELECT [a] FROM [t] OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY",
                select.getSql(Dialect.SQL_SERVER));

    }

    @Test
    void limitWithoutOffsetBecomesZeroOffsetInFetchDialects() {

        String sql = Query.select("a").from("t").limit(5).getSql(Dialect.ORACLE);

        assertEquals("SELECT \"a\" FROM \"t\" OFFSET 0 ROWS FETCH NEXT 5 ROWS ONLY", sql);

    }

    @Test
    void offsetWithoutLimitIsRejectedInFetchDialects() {

        Select select = Query.select("a").from("t").offset(5);

        // В LIMIT/OFFSET-диалектах смещение без лимита корректно.
        assertEquals("SELECT `a` FROM `t` OFFSET 5", select.getSql(Dialect.MYSQL));

        QueryException e = assertThrows(QueryException.class, () -> select.getSql(Dialect.SQL_SERVER));

        assertEquals(ErrorCode.UNSUPPORTED_DIALECT_FEATURE, e.getCode());

    }

    @Test
    void negativeLimitAndOffsetAreRejected() {

        assertEquals(ErrorCode.INVALID_ARGUMENT_VALUE,
                assertThrows(QueryException.class, () -> Query.select("a").limit(0)).getCode());
        assertEquals(ErrorCode.INVALID_ARGUMENT_VALUE,
                assertThrows(QueryException.class, () -> Query.select("a").offset(-1)).getCode());

    }

    @Test
    void forUpdateIsRejectedForSqlServer() {

        Select select = Query.select("a").from("t").forUpdate();

        assertEquals("SELECT `a` FROM `t` FOR UPDATE", select.getSql(Dialect.MYSQL));
        assertEquals(ErrorCode.UNSUPPORTED_DIALECT_FEATURE,
                assertThrows(QueryException.class, () -> select.getSql(Dialect.SQL_SERVER)).getCode());

    }

    @Test
    void paramsAreImmutable() {

        Select select = Query.select("a").from("t").where("x", 1);
        List<Object> params = select.getParams();

        assertThrows(UnsupportedOperationException.class, () -> params.add(2));

    }

    @Test
    void paramArrayMutationDoesNotAffectBuiltQuery() {

        // Исходная библиотека хранила varargs-массив по ссылке, поэтому мутация
        // массива вызывающей стороной меняла уже собранный запрос.
        Object[] values = {1};
        Select select = Query.select("a").from("t").whereExpr("x = ?", values);
        values[0] = 999;

        assertEquals(List.of(1), select.getParams());

    }

    @Test
    void rawStatementIsReturnedVerbatim() {

        SqlStatement raw = Query.raw("SELECT * FROM t WHERE a = ?", 5);

        assertEquals("SELECT * FROM t WHERE a = ?", raw.getSql(Dialect.POSTGRES));
        assertEquals(List.of(5), raw.getParams());

    }

    @Test
    void rawStatementRejectsPlaceholderMismatch() {

        QueryException e = assertThrows(QueryException.class, () -> Query.raw("SELECT ?", 1, 2));

        assertEquals(ErrorCode.PLACEHOLDER_COUNT_MISMATCH, e.getCode());

    }

}
