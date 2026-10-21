package me.kirillgrachev.query;

import me.kirillgrachev.query.exception.ErrorCode;
import me.kirillgrachev.query.exception.QueryException;
import me.kirillgrachev.query.sql.CreateTable;
import me.kirillgrachev.query.sql.Delete;
import me.kirillgrachev.query.sql.Identifier;
import me.kirillgrachev.query.sql.Insert;
import me.kirillgrachev.query.sql.RawSql;
import me.kirillgrachev.query.sql.Select;
import me.kirillgrachev.query.sql.SqlStatement;
import me.kirillgrachev.query.sql.Update;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Точка входа для построения запросов.
 *
 * <p>Только фабрики, исполнение — в {@link Sql}. Разделение нужно потому, что
 * запрос не знает ни соединения, ни диалекта: его можно собрать один раз,
 * залогировать, сравнить в тесте и исполнить позже против другой базы.
 *
 * <p>Конструктор приватный, чего не было в исходной библиотеке: класс только со
 * статическими методами, но с неявным публичным конструктором, поэтому
 * {@code new Query()} компилировался.
 *
 * <p>Все фабрики принимают {@code null}-имена и отклоняют их сразу, на месте
 * вызова, а не в момент рендера SQL: стектрейс указывает на строку, где ошибка
 * допущена, а не на строку, где запрос начали исполнять.
 */
public final class Query {

    private Query() {

        throw new AssertionError("Utility class must not be instantiated");

    }

    /**
     * Создаёт {@code SELECT} с перечисленными колонками.
     *
     * @param columns имена колонок; если не передано ни одной, список выбираемых
     *                выражений нужно задать явно либо вызвать {@link Select#all()}
     * @return новый запрос
     * @throws QueryException если любое из имён невалидно
     */
    public static @NotNull Select select(@Nullable String @NotNull ... columns) {

        if (columns == null) {
            throw QueryException.of(ErrorCode.NULL_ARGUMENT, "Columns array must not be null");
        }

        Select select = new Select();

        for (String column : columns) {
            select.col(requireNonNull(column, "column"));
        }

        return select;

    }

    /**
     * Создаёт {@code INSERT} в таблицу.
     *
     * @param table имя таблицы
     * @return новый запрос
     */
    public static @NotNull Insert insertInto(@NotNull String table) {

        return new Insert(identifier(table, "table"));

    }

    /**
     * Создаёт {@code INSERT} в таблицу указанной схемы.
     *
     * @param schema имя схемы или базы данных
     * @param table  имя таблицы
     * @return новый запрос
     */
    public static @NotNull Insert insertInto(@NotNull String schema, @NotNull String table) {

        return new Insert(qualified(schema, table));

    }

    /**
     * Создаёт {@code UPDATE} таблицы.
     *
     * @param table имя таблицы
     * @return новый запрос
     */
    public static @NotNull Update update(@NotNull String table) {

        return new Update(identifier(table, "table"));

    }

    /**
     * Создаёт {@code UPDATE} таблицы указанной схемы.
     *
     * @param schema имя схемы или базы данных
     * @param table  имя таблицы
     * @return новый запрос
     */
    public static @NotNull Update update(@NotNull String schema, @NotNull String table) {

        return new Update(qualified(schema, table));

    }

    /**
     * Создаёт {@code DELETE} из таблицы.
     *
     * <p>Удаление всех строк требует явного {@link Delete#allRows()}.
     *
     * @param table имя таблицы
     * @return новый запрос
     */
    public static @NotNull Delete deleteFrom(@NotNull String table) {

        return new Delete(identifier(table, "table"));

    }

    /**
     * Создаёт {@code DELETE} из таблицы указанной схемы.
     *
     * @param schema имя схемы или базы данных
     * @param table  имя таблицы
     * @return новый запрос
     */
    public static @NotNull Delete deleteFrom(@NotNull String schema, @NotNull String table) {

        return new Delete(qualified(schema, table));

    }

    /**
     * Создаёт {@code CREATE TABLE}.
     *
     * @param table имя таблицы
     * @return новый запрос
     */
    public static @NotNull CreateTable createTable(@NotNull String table) {

        return new CreateTable(identifier(table, "table"));

    }

    /**
     * Создаёт {@code CREATE TABLE} в указанной схеме.
     *
     * @param schema имя схемы или базы данных
     * @param table  имя таблицы
     * @return новый запрос
     */
    public static @NotNull CreateTable createTable(@NotNull String schema, @NotNull String table) {

        return new CreateTable(qualified(schema, table));

    }

    /**
     * Оборачивает произвольный SQL в запрос.
     *
     * @param sql    текст запроса
     * @param params параметры для плейсхолдеров {@code ?}
     * @return запрос
     * @throws QueryException если число плейсхолдеров не совпало с числом параметров
     */
    public static @NotNull SqlStatement raw(@NotNull String sql, @Nullable Object @NotNull ... params) {

        requireNonNull(sql, "sql");

        if (params == null) {
            throw QueryException.of(ErrorCode.NULL_ARGUMENT, "Params array must not be null");
        }

        return new RawSql(sql, params);

    }

    private static @NotNull Identifier identifier(@NotNull String value,
                                                                            @NotNull String role) {

        requireNonNull(value, role);

        return Identifier.of(value);

    }

    private static @NotNull Identifier qualified(@NotNull String schema,
                                                                           @NotNull String table) {

        requireNonNull(schema, "schema");
        requireNonNull(table, "table");

        return Identifier.of(schema, table);

    }

    private static @NotNull <T> T requireNonNull(@Nullable T value, @NotNull String role) {

        if (value == null) {
            throw QueryException.of(ErrorCode.NULL_ARGUMENT,
                    "Argument '" + role + "' must not be null", Map.of("argument", role));
        }

        return value;

    }

}
