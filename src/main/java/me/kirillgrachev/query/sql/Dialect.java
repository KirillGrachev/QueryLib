package me.kirillgrachev.query.sql;

import me.kirillgrachev.query.exception.ErrorCode;
import me.kirillgrachev.query.exception.QueryException;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * SQL-диалект: как экранировать идентификаторы и как писать пагинацию.
 *
 * <p>Исходная библиотека оборачивала все идентификаторы в обратные кавычки, то
 * есть была MySQL-only, хотя в описании этого не было ни слова. На PostgreSQL,
 * SQLite, H2 в стандартном режиме и Oracle такой запрос не выполняется вовсе.
 * Абстракция диалекта закрывает это расхождение и делает его явным: вызывающая
 * сторона обязана назвать диалект, и библиотека не угадывает.
 *
 * <p>Различаются две вещи, и обе здесь:
 * <ul>
 *   <li>символы кавычек идентификатора и способ экранирования их внутри имени
 *       (в обоих семействах — удвоение символа, но сам символ разный);</li>
 *   <li>синтаксис пагинации: {@code LIMIT n OFFSET m} против
 *       {@code OFFSET m ROWS FETCH NEXT n ROWS ONLY} в SQL Server и Oracle 12c+.</li>
 * </ul>
 *
 * <p>Побочный эффект экранирования, о котором нужно помнить: заключённое в кавычки
 * имя становится регистрозависимым в PostgreSQL, H2 и Oracle. Схема, созданная как
 * {@code CREATE TABLE users}, хранит имя в верхнем регистре, и запрос с
 * {@code "users"} её не найдёт. Для переносимой схемы DDL тоже должен использовать
 * кавычки и один регистр. В MySQL имена таблиц регистрозависимы только на
 * чувствительных к регистру файловых системах, поэтому там расхождение проявляется
 * реже и от этого опаснее.
 *
 * <p>{@link #NONE} оставлен для случаев, когда идентификаторы заведомо безопасны
 * и их не нужно оборачивать — например, при генерации DDL для инструмента,
 * который сам расставляет кавычки.
 */
public enum Dialect {

    /** MySQL и MariaDB: идентификаторы в обратных кавычках. */
    MYSQL('`', PaginationStyle.LIMIT_OFFSET),

    /** PostgreSQL: идентификаторы в двойных кавычках, пагинация стандартная. */
    POSTGRES('"', PaginationStyle.LIMIT_OFFSET),

    /** H2 в стандартном режиме совместимости. */
    H2('"', PaginationStyle.LIMIT_OFFSET),

    /** SQLite: двойные кавычки принимаются наряду с обратными. */
    SQLITE('"', PaginationStyle.LIMIT_OFFSET),

    /** SQL Server: квадратные скобки и пагинация через OFFSET/FETCH. */
    SQL_SERVER('[', PaginationStyle.OFFSET_FETCH),

    /** Oracle 12c и новее: двойные кавычки, пагинация через OFFSET/FETCH. */
    ORACLE('"', PaginationStyle.OFFSET_FETCH),

    /** Стандарт SQL: двойные кавычки. */
    ANSI('"', PaginationStyle.LIMIT_OFFSET),

    /** Идентификаторы не оборачиваются. Только для заведомо безопасных имён. */
    NONE('\0', PaginationStyle.LIMIT_OFFSET);

    /** Символ, которым идентификаторы экранируются в этом диалекте. */
    private final char quoteChar;

    /** Стиль пагинации. */
    private final PaginationStyle paginationStyle;

    Dialect(char quoteChar, PaginationStyle paginationStyle) {

        this.quoteChar = quoteChar;
        this.paginationStyle = paginationStyle;

    }

    public char getQuoteChar() {

        return quoteChar;

    }

    public @NotNull PaginationStyle getPaginationStyle() {

        return paginationStyle;

    }

    /**
     * Оборачивает идентификатор в кавычки диалекта с экранированием.
     *
     * <p>Экранирование обязательно: удвоение символа кавычки — единственный
     * корректный способ включить её в идентификатор. Исходная библиотека вместо
     * экранирования запрещала символ в имени, из-за чего легитимное имя колонки
     * с кавычкой было невыразимо, а проверка при этом всё равно не покрывала
     * остальные опасные символы.
     *
     * <p>Для {@link #NONE} имя возвращается как есть: предполагается, что
     * вызывающая сторона уже позаботилась о безопасности.
     *
     * @param identifier валидированный идентификатор
     * @return идентификатор в кавычках диалекта
     */
    public @NotNull String quote(@NotNull String identifier) {

        if (this == NONE) {
            return identifier;
        }

        String quoted = identifier.replace(String.valueOf(quoteChar),
                String.valueOf(quoteChar) + quoteChar);

        // SQL Server закрывает идентификатор другим символом: [name].
        char closing = this == SQL_SERVER ? ']' : quoteChar;

        return quoteChar + quoted + closing;

    }

    /**
     * Собирает клаузу пагинации в синтаксисе диалекта.
     *
     * @param limit  ограничение числа строк, либо {@code null}
     * @param offset смещение, либо {@code null}
     * @return клауза с ведущим пробелом, либо пустая строка
     * @throws QueryException если в диалекте с OFFSET/FETCH задано смещение без лимита
     */
    public @NotNull String renderPagination(Long limit, Long offset) {

        if (limit == null && offset == null) {
            return "";
        }

        if (paginationStyle == PaginationStyle.LIMIT_OFFSET) {
            StringBuilder sb = new StringBuilder();

            if (limit != null) {
                sb.append(" LIMIT ").append(limit);
            }

            if (offset != null) {
                sb.append(" OFFSET ").append(offset);
            }

            return sb.toString();
        }

        // OFFSET/FETCH: стандарт SQL требует OFFSET всегда, а FETCH опционален.
        // Смещение без лимита корректно, лимит без смещения — нет, поэтому
        // отсутствие offset при limit заменяется нулём, а не ошибкой.
        if (offset == null && limit != null) {
            return " OFFSET 0 ROWS FETCH NEXT " + limit + " ROWS ONLY";
        }

        if (limit == null) {
            throw QueryException.of(ErrorCode.UNSUPPORTED_DIALECT_FEATURE,
                    "OFFSET without LIMIT is not representable in " + name()
                            + " (FETCH requires a row count)",
                    Map.of("dialect", name(), "offset", offset));
        }

        return " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";

    }

    /** Синтаксис пагинации, поддерживаемый диалектом. */
    public enum PaginationStyle {

        /** {@code LIMIT n OFFSET m} — MySQL, PostgreSQL, SQLite, H2. */
        LIMIT_OFFSET,

        /** {@code OFFSET m ROWS FETCH NEXT n ROWS ONLY} — SQL Server, Oracle 12c+. */
        OFFSET_FETCH

    }

}
