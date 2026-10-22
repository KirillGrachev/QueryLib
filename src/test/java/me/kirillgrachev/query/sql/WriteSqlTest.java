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
 * Тесты генерации {@code INSERT}, {@code UPDATE}, {@code DELETE} и {@code CREATE TABLE}.
 *
 * <p>Отдельное внимание — upsert, потому что это единственная конструкция, где
 * синтаксис расходится между диалектами настолько, что общего решения нет:
 * MySQL использует {@code ON DUPLICATE KEY UPDATE ... VALUES(col)}, стандарт —
 * {@code ON CONFLICT (...) DO UPDATE SET ... = EXCLUDED.col}, а H2 не поддерживает
 * ни то, ни другое и требует {@code MERGE INTO ... KEY(...)}. Формы для H2 и
 * PostgreSQL проверены исполнением на настоящей базе в SqlIntegrationTest.
 */
class WriteSqlTest {

    @Test
    void insertRendersColumnsAndValuesInInsertionOrder() {

        Insert insert = Query.insertInto("users")
                .value("name", "alice")
                .valueExpr("age", "? + 1", 30);

        assertEquals("INSERT INTO `users` (`name`, `age`) VALUES (?, ? + 1)", insert.getSql(Dialect.MYSQL));
        assertEquals(List.of("alice", 30), insert.getParams());

    }

    @Test
    void insertHasNoTrailingSemicolon() {
        String sql = Query.insertInto("t").value("a", 1).getSql(Dialect.MYSQL);
        assertTrue(!sql.endsWith(";"), "запрос не должен заканчиваться точкой с запятой: " + sql);
    }

    @Test
    void insertWithSchemaQuotesBothParts() {
        String sql = Query.insertInto("shop", "orders").value("id", 1).getSql(Dialect.POSTGRES);
        assertEquals("INSERT INTO \"shop\".\"orders\" (\"id\") VALUES (?)", sql);
    }

    @Test
    void insertWithoutValuesIsRejected() {

        QueryException e = assertThrows(QueryException.class,
                () -> Query.insertInto("t").getSql(Dialect.MYSQL));

        assertEquals(ErrorCode.INCOMPLETE_STATEMENT, e.getCode());

    }

    @Test
    void duplicateColumnIsRejectedInsteadOfSilentOverwrite() {

        // Исходная библиотека хранила значения в Map, поэтому второй вызов для той
        // же колонки молча отменял первый и вместе с ним менял порядок параметров.
        Insert insert = Query.insertInto("t").value("a", 1);

        QueryException e = assertThrows(QueryException.class, () -> insert.value("a", 2));

        assertEquals(ErrorCode.DUPLICATE_ELEMENT, e.getCode());
        assertEquals("a", e.getContextValue("column", String.class));

    }

    @Test
    void duplicateColumnInUpdateIsRejected() {

        Update update = Query.update("t").value("a", 1);

        assertEquals(ErrorCode.DUPLICATE_ELEMENT,
                assertThrows(QueryException.class, () -> update.value("a", 2)).getCode());

    }

    @Test
    void nullValueRequiresExplicitMethod() {

        assertEquals(ErrorCode.NULL_ARGUMENT,
                assertThrows(QueryException.class,
                        () -> Query.insertInto("t").value("a", null)).getCode());
        assertEquals(ErrorCode.NULL_ARGUMENT,
                assertThrows(QueryException.class,
                        () -> Query.update("t").value("a", null)).getCode());

    }

    @Test
    void valueNullAndValueNullable() {

        assertEquals("INSERT INTO `t` (`a`) VALUES (NULL)",
                Query.insertInto("t").valueNull("a").getSql(Dialect.MYSQL));
        assertEquals("INSERT INTO `t` (`a`) VALUES (NULL)",
                Query.insertInto("t").valueNullable("a", null).getSql(Dialect.MYSQL));
        assertEquals("INSERT INTO `t` (`a`) VALUES (?)",
                Query.insertInto("t").valueNullable("a", 5).getSql(Dialect.MYSQL));

    }

    @Test
    void mysqlUpsertUsesOnDuplicateKeyWithValues() {

        String sql = Query.insertInto("users")
                .value("id", 1).value("name", "a")
                .onDuplicateKeyUpdateExcept("id")
                .getSql(Dialect.MYSQL);

        assertEquals("INSERT INTO `users` (`id`, `name`) VALUES (?, ?)"
                + " ON DUPLICATE KEY UPDATE `name` = VALUES(`name`)", sql);

    }

    @Test
    void postgresUpsertUsesOnConflictWithExcluded() {

        String sql = Query.insertInto("users")
                .value("id", 1).value("name", "a")
                .onDuplicateKeyUpdateExcept("id")
                .getSql(Dialect.POSTGRES);

        assertEquals("INSERT INTO \"users\" (\"id\", \"name\") VALUES (?, ?)"
                + " ON CONFLICT (\"id\") DO UPDATE SET \"name\" = EXCLUDED.\"name\"", sql);

    }

    @Test
    void h2UpsertUsesMergeWithKey() {

        // H2 не поддерживает ни ON CONFLICT, ни INSERT IGNORE (проверено на 2.2.224),
        // но поддерживает MERGE INTO ... KEY(...) VALUES (...).
        String sql = Query.insertInto("users")
                .value("id", 1).value("name", "a")
                .onDuplicateKeyUpdateExcept("id")
                .getSql(Dialect.H2);

        assertEquals("MERGE INTO \"users\" (\"id\", \"name\") KEY (\"id\") VALUES (?, ?)", sql);

    }

    @Test
    void upsertParamsAreNotDuplicated() {

        // VALUES(col) и EXCLUDED.col ссылаются на уже переданное значение, поэтому
        // новых плейсхолдеров не появляется. Исходная библиотека рендерила
        // "ON DUPLICATE KEY UPDATE col = <то же выражение>" и вынуждена была
        // добавлять параметры второй раз, что ломало запрос при выражении без
        // плейсхолдеров и удваивало их при выражении с плейсхолдером.
        Insert insert = Query.insertInto("users")
                .value("id", 1).valueExpr("score", "? * 2", 10)
                .onDuplicateKeyUpdateExcept("id");

        assertEquals(List.of(1, 10), insert.getParams());
        assertEquals(2, insert.getParams().size());

    }

    @Test
    void upsertWithoutKeysIsRejected() {

        // Прежнее поведение «пустой список ключей означает обновить всё» приводило
        // к затиранию первичного ключа при upsert.
        Insert insert = Query.insertInto("t").value("id", 1).value("a", 2);

        assertEquals(ErrorCode.INVALID_ARGUMENT_VALUE,
                assertThrows(QueryException.class, insert::onDuplicateKeyUpdateExcept).getCode());

    }

    @Test
    void upsertAllIsExplicit() {

        String sql = Query.insertInto("t").value("id", 1).value("a", 2)
                .onDuplicateKeyUpdateAll().getSql(Dialect.MYSQL);

        assertEquals("INSERT INTO `t` (`id`, `a`) VALUES (?, ?)"
                + " ON DUPLICATE KEY UPDATE `id` = VALUES(`id`), `a` = VALUES(`a`)", sql);

    }

    @Test
    void upsertAllOnStandardDialectsRequiresConflictTarget() {

        Insert insert = Query.insertInto("t").value("id", 1).onDuplicateKeyUpdateAll();

        QueryException e = assertThrows(QueryException.class, () -> insert.getSql(Dialect.POSTGRES));

        assertEquals(ErrorCode.UNSUPPORTED_DIALECT_FEATURE, e.getCode());

    }

    @Test
    void upsertWhereEveryColumnIsAKeyIsRejected() {

        Insert insert = Query.insertInto("t").value("id", 1)
                .onDuplicateKeyUpdateExcept("id");

        QueryException e = assertThrows(QueryException.class, () -> insert.getSql(Dialect.MYSQL));

        assertEquals(ErrorCode.INCOMPLETE_STATEMENT, e.getCode());

    }

    @Test
    void ignoreAndUpsertCannotBeCombined() {

        Insert insert = Query.insertInto("t").value("a", 1).ignore().onDuplicateKeyUpdateAll();

        assertEquals(ErrorCode.CONFLICTING_CLAUSES,
                assertThrows(QueryException.class, () -> insert.getSql(Dialect.MYSQL)).getCode());

    }

    @Test
    void mysqlIgnoreIsRendered() {
        assertEquals("INSERT IGNORE INTO `t` (`a`) VALUES (?)",
                Query.insertInto("t").value("a", 1).ignore().getSql(Dialect.MYSQL));
    }

    @Test
    void postgresIgnoreBecomesOnConflictDoNothing() {
        assertEquals("INSERT INTO \"t\" (\"a\") VALUES (?) ON CONFLICT DO NOTHING",
                Query.insertInto("t").value("a", 1).ignore().getSql(Dialect.POSTGRES));
    }

    @Test
    void ignoreOnUnsupportedDialectIsRejected() {

        // Пустая строка вместо клаузы означала бы, что IGNORE молча выброшен и
        // конфликтующие строки вызовут ошибку вместо пропуска.
        Insert insert = Query.insertInto("t").value("a", 1).ignore();

        assertEquals(ErrorCode.UNSUPPORTED_DIALECT_FEATURE,
                assertThrows(QueryException.class, () -> insert.getSql(Dialect.SQL_SERVER)).getCode());
        assertEquals(ErrorCode.UNSUPPORTED_DIALECT_FEATURE,
                assertThrows(QueryException.class, () -> insert.getSql(Dialect.H2)).getCode());

    }

    @Test
    void updateRendersAssignmentsBeforeConditions() {

        Update update = Query.update("users")
                .value("name", "bob")
                .valueExpr("age", "age + ?", 1)
                .where("id", 7);

        assertEquals("UPDATE `users` SET `name` = ?, `age` = age + ? WHERE `id` = ?",
                update.getSql(Dialect.MYSQL));
        // Порядок параметров обязан совпадать с порядком плейсхолдеров.
        assertEquals(List.of("bob", 1, 7), update.getParams());

    }

    @Test
    void updateWithoutValuesIsRejected() {

        assertEquals(ErrorCode.INCOMPLETE_STATEMENT,
                assertThrows(QueryException.class,
                        () -> Query.update("t").where("id", 1).getSql(Dialect.MYSQL)).getCode());

    }

    @Test
    void updateWithoutWhereRequiresExplicitConfirmation() {

        // UPDATE без WHERE перезаписывает каждый ряд таблицы.
        Update update = Query.update("users").value("status", "x");

        QueryException e = assertThrows(QueryException.class, () -> update.getSql(Dialect.MYSQL));

        assertEquals(ErrorCode.UNCONDITIONAL_MODIFICATION, e.getCode());
        assertEquals("UPDATE `users` SET `status` = ?",
                update.allRows().getSql(Dialect.MYSQL));

    }

    @Test
    void deleteWithoutWhereRequiresExplicitConfirmation() {

        // В исходной библиотеке Query.deleteFrom("users") молча давал
        // "DELETE FROM `users`", и это поведение было закреплено её тестом.
        Delete delete = Query.deleteFrom("users");

        QueryException e = assertThrows(QueryException.class, () -> delete.getSql(Dialect.MYSQL));

        assertEquals(ErrorCode.UNCONDITIONAL_MODIFICATION, e.getCode());
        assertEquals("DELETE FROM `users`", delete.allRows().getSql(Dialect.MYSQL));

    }

    @Test
    void deleteWithConditionIsRendered() {

        Delete delete = Query.deleteFrom("users").where("id", 5);

        assertEquals("DELETE FROM \"users\" WHERE \"id\" = ?", delete.getSql(Dialect.POSTGRES));
        assertEquals(List.of(5), delete.getParams());

    }

    @Test
    void createTableRendersConstraints() {

        CreateTable create = Query.createTable("users")
                .bigIntKey("id")
                .varchar("name", 64).notNull()
                .text("bio")
                .bool("active")
                .intColumn("age").defaultValue(18)
                .ifNotExists();

        assertEquals("CREATE TABLE IF NOT EXISTS `users` ("
                        + "`id` BIGINT NOT NULL AUTO_INCREMENT, "
                        + "`name` VARCHAR(64) NOT NULL, "
                        + "`bio` TEXT, "
                        + "`active` BOOLEAN, "
                        + "`age` INT DEFAULT ?, "
                        + "PRIMARY KEY (`id`))",
                create.getSql(Dialect.MYSQL));
        assertEquals(List.of(18), create.getParams());

    }

    @Test
    void createTableUsesIdentityForStandardDialects() {

        String sql = Query.createTable("t").intKey("id").getSql(Dialect.POSTGRES);

        assertEquals("CREATE TABLE \"t\" (\"id\" INT GENERATED BY DEFAULT AS IDENTITY NOT NULL,"
                + " PRIMARY KEY (\"id\"))", sql);

    }

    @Test
    void createTableWithoutColumnsIsRejected() {

        assertEquals(ErrorCode.INCOMPLETE_STATEMENT,
                assertThrows(QueryException.class,
                        () -> Query.createTable("t").getSql(Dialect.MYSQL)).getCode());

    }

    @Test
    void createTableModifierWithoutColumnIsRejected() {

        // notNull() и autoIncrement() применяются к последней добавленной колонке,
        // поэтому вызов до добавления колонки — ошибка, а не тихий no-op.
        assertEquals(ErrorCode.INCOMPLETE_STATEMENT,
                assertThrows(QueryException.class, () -> Query.createTable("t").notNull()).getCode());
        assertEquals(ErrorCode.INCOMPLETE_STATEMENT,
                assertThrows(QueryException.class, () -> Query.createTable("t").autoIncrement()).getCode());

    }

    @Test
    void createTableRejectsNonPositiveVarcharLength() {
        assertEquals(ErrorCode.INVALID_ARGUMENT_VALUE,
                assertThrows(QueryException.class, () -> Query.createTable("t").varchar("a", 0)).getCode());
    }

    @Test
    void sqliteAutoIncrementIsRejectedInsteadOfSilentlyDropped() {

        // В SQLite автоинкремент выражается конструкцией INTEGER PRIMARY KEY
        // AUTOINCREMENT, которую нельзя собрать из общего рендера колонок.
        // Молча вернуть пустую строку означало бы потерю автоинкремента.
        CreateTable create = Query.createTable("t").intColumn("id").autoIncrement();

        QueryException e = assertThrows(QueryException.class, () -> create.getSql(Dialect.SQLITE));

        assertEquals(ErrorCode.UNSUPPORTED_DIALECT_FEATURE, e.getCode());
        assertTrue(e.getMessage().contains("INTEGER PRIMARY KEY AUTOINCREMENT"), e.getMessage());

    }

    @Test
    void compositePrimaryKeyIsSupported() {

        String sql = Query.createTable("link")
                .bigIntColumn("a").notNull()
                .bigIntColumn("b").notNull()
                .primaryKey("a", "b")
                .getSql(Dialect.POSTGRES);

        assertEquals("CREATE TABLE \"link\" (\"a\" BIGINT NOT NULL, \"b\" BIGINT NOT NULL,"
                + " PRIMARY KEY (\"a\", \"b\"))", sql);

    }

    @Test
    void emptyPrimaryKeyIsRejected() {
        assertEquals(ErrorCode.INVALID_ARGUMENT_VALUE,
                assertThrows(QueryException.class, () -> Query.createTable("t").primaryKey()).getCode());
    }

    @Test
    void columnsListIsExposedForDiagnostics() {

        Insert insert = Query.insertInto("t").value("b", 1).value("a", 2);

        assertEquals(List.of("b", "a"), List.copyOf(insert.getColumns()),
                "порядок колонок должен соответствовать порядку добавления");

    }

}
