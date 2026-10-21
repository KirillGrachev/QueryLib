package me.kirillgrachev.query.sql;

import me.kirillgrachev.query.Query;
import me.kirillgrachev.query.exception.ErrorCode;
import me.kirillgrachev.query.exception.QueryException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты {@link Identifier}: валидация имён и экранирование по диалектам.
 *
 * <p>Отдельный набор потому, что здесь закрывается самый серьёзный дефект
 * исходной библиотеки — SQL-инъекция через имя схемы в {@code Select.from}.
 * Проверки сформулированы как попытки атаки, а не как «валидатор работает»:
 * тест должен падать, если защиту убрать.
 */
class IdentifierTest {

    @Test
    void acceptsOrdinaryNames() {

        assertEquals("users", Identifier.of("users").getName());
        assertEquals("user_id", Identifier.of("user_id").getName());
        assertEquals("order", Identifier.of("order").getName());
        assertEquals("тест", Identifier.of("тест").getName());

    }

    @Test
    void rejectsNullName() {

        QueryException e = assertThrows(QueryException.class, () -> Identifier.of(null));

        assertEquals(ErrorCode.NULL_ARGUMENT, e.getCode());

    }

    @Test
    void rejectsBlankName() {

        // Исходная реализация проверяла только isEmpty(), поэтому имя из пробелов
        // проходило и давало в SQL идентификатор "   ".
        for (String blank : new String[]{"", " ", "\t", "   "}) {
            QueryException e = assertThrows(QueryException.class, () -> Identifier.of(blank),
                    "blank-имя принято: [" + blank + "]");

            assertEquals(ErrorCode.INVALID_IDENTIFIER, e.getCode());
        }

    }

    @Test
    void rejectsControlCharacters() {

        for (String bad : new String[]{"users\n", "us\ners", "users\r", "users\u0000", "us\u001Bers"}) {
            QueryException e = assertThrows(QueryException.class, () -> Identifier.of(bad));

            assertEquals(ErrorCode.INVALID_IDENTIFIER, e.getCode());
        }

    }

    @Test
    void rejectsOverlongName() {

        String tooLong = "a".repeat(Identifier.MAX_LENGTH + 1);

        QueryException e = assertThrows(QueryException.class, () -> Identifier.of(tooLong));

        assertEquals(ErrorCode.INVALID_IDENTIFIER, e.getCode());
        assertEquals(Identifier.MAX_LENGTH + 1, (int) e.getContextValue("length", Integer.class));

    }

    @Test
    void acceptsQuoteCharactersAndEscapesThem() {

        // Кавычка в имени легальна и экранируется удвоением. Исходная библиотека
        // вместо экранирования запрещала символ, из-за чего легитимное имя было
        // невыразимо, а защита всё равно не покрывала остальные случаи.
        Identifier id = Identifier.of("weird`name");

        assertEquals("`weird``name`", id.render(Dialect.MYSQL));
        assertEquals("\"weird`name\"", id.render(Dialect.POSTGRES));

    }

    @Test
    void injectionPayloadCannotEscapeQuotedIdentifier() {

        // Защита основана на экранировании, а не на запрещении символов: имя
        // целиком остаётся внутри одной пары кавычек, поэтому payload не может
        // закрыть её и продолжить запрос. Это строже, чем прежний подход
        // «запретить backtick»: легальные имена с кавычкой остаются выразимыми,
        // а попытка инъекции нейтрализуется.
        String[] payloads = {
                "db`; DROP TABLE users; --",
                "t`; DELETE FROM x; --",
                "a` FROM t; DROP TABLE t; --",
                "x` OR `1`=`1",
                "y``z"
        };

        for (String payload : payloads) {
            assertEquals(2, countTopLevelQuotes(Identifier.of(payload).render(Dialect.MYSQL), '`'),
                    "payload вышел за пределы кавычек (MySQL): " + payload);
            assertEquals(2, countTopLevelQuotes(Identifier.of(payload).render(Dialect.POSTGRES), '"'),
                    "payload вышел за пределы кавычек (PostgreSQL): " + payload);
        }

    }

    @Test
    void injectionThroughSchemaNameIsNeutralized() {

        // Точный вектор из исходной библиотеки: Select.from(database, table)
        // валидировал только таблицу, и имя базы попадало в SQL дословно.
        String sql = Query.select("x").from("db`; DROP TABLE users; --", "t").getSql(Dialect.MYSQL);

        assertEquals("SELECT `x` FROM `db``; DROP TABLE users; --`.`t`", sql);
        // Шесть неэкранированных кавычек: по паре на колонку, схему и таблицу.
        assertEquals(6, countTopLevelQuotes(sql, '`'));

    }

    @Test
    void injectionThroughTableNameIsRejected() {

        // Управляющие символы отвергаются безусловно: NUL обрывает строку в нативном
        // слое драйвера, а перевод строки ломает однострочное логирование запроса.
        assertThrows(QueryException.class, () -> Query.insertInto("t\nDROP TABLE x"));
        assertThrows(QueryException.class, () -> Query.update("t\r\nDROP TABLE x"));
        assertThrows(QueryException.class, () -> Query.deleteFrom("t\u0000"));
        assertThrows(QueryException.class, () -> Query.createTable("t", "x\ny"));

    }

    @Test
    void injectionThroughColumnNameIsNeutralized() {

        // allRows() нужен потому, что UPDATE без WHERE теперь отклоняется:
        // защита от перезаписи всей таблицы срабатывает раньше рендера.
        String sql = Query.update("t").value("a`; DROP TABLE t; --", 1).allRows().getSql(Dialect.MYSQL);

        assertEquals("UPDATE `t` SET `a``; DROP TABLE t; --` = ?", sql);
        assertEquals(4, countTopLevelQuotes(sql, '`'));

        String selectSql = Query.select("a` FROM t; DROP TABLE t; --").getSql(Dialect.POSTGRES);
        assertEquals(2, countTopLevelQuotes(selectSql, '"'));

    }

    @Test
    void orderByColumnCannotBeUsedForInjection() {

        String sql = Query.select("a").from("t").orderBy("x OR 1=1").getSql(Dialect.MYSQL);

        // Пробел в имени легален и остаётся внутри кавычек, поэтому не превращается
        // в часть предложения ORDER BY.
        assertEquals("SELECT `a` FROM `t` ORDER BY `x OR 1=1` ASC", sql);

    }

    /**
     * Считает кавычки, не являющиеся частью удвоенной пары.
     *
     * <p>Именно это число показывает, может ли содержимое имени выйти за пределы
     * идентификатора: если payload закрыл кавычку, неэкранированных окажется
     * больше, чем открыл рендер.
     */
    private static int countTopLevelQuotes(String rendered, char quote) {

        int count = 0;

        for (int i = 0; i < rendered.length(); i++) {
            if (rendered.charAt(i) != quote) {
                continue;
            }

            if (i + 1 < rendered.length() && rendered.charAt(i + 1) == quote) {
                i++;
                continue;
            }

            count++;
        }

        return count;

    }

    @Test
    void qualifiedNameIsRenderedWithBothPartsQuoted() {

        Identifier id = Identifier.of("shop", "orders");

        assertEquals("`shop`.`orders`", id.render(Dialect.MYSQL));
        assertEquals("\"shop\".\"orders\"", id.render(Dialect.POSTGRES));
        assertEquals("[shop].[orders]", id.render(Dialect.SQL_SERVER));
        assertEquals("shop.orders", id.render(Dialect.NONE));

    }

    @Test
    void sqlServerUsesDifferentClosingBracket() {

        // У SQL Server закрывающий символ отличается от открывающего, и удвоение
        // как экранирование там не применяется — вместо этого закрывающая скобка
        // удваивается внутри имени.
        assertEquals("[weird]name]", Identifier.of("weird]name").render(Dialect.SQL_SERVER));

    }

    @Test
    void unqualifiedNameHasNoQualifier() {

        Identifier id = Identifier.of("users");

        assertNull(id.getQualifier());
        assertEquals("users", id.toString());

    }

    @Test
    void equalityIncludesQualifier() {

        assertEquals(Identifier.of("users"), Identifier.of("users"));
        assertEquals(Identifier.of("shop", "orders"), Identifier.of("shop", "orders"));
        assertTrue(!Identifier.of("shop", "orders").equals(Identifier.of("other", "orders")));
        assertEquals(Identifier.of("shop", "orders").hashCode(), Identifier.of("shop", "orders").hashCode());

    }

}
