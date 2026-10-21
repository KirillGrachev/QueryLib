package me.kirillgrachev.query.sql;

import me.kirillgrachev.query.exception.ErrorCode;
import me.kirillgrachev.query.exception.QueryException;
import me.kirillgrachev.query.internal.Placeholders;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Запрос {@code INSERT} с поддержкой upsert в синтаксисе диалекта.
 *
 * <p>Отличия от исходной реализации:
 * <ul>
 *   <li>повторное добавление той же колонки — ошибка, а не молчаливая перезапись.
 *       В исходной версии значения хранились в {@code LinkedHashMap}, поэтому
 *       {@code .value("a", 1).value("a", 2)} тихо оставляло только двойку и вместе
 *       с ней меняло порядок параметров;</li>
 *   <li>upsert рендерится по диалекту: {@code ON DUPLICATE KEY UPDATE} для MySQL
 *       и {@code ON CONFLICT (...) DO UPDATE} для PostgreSQL, H2 и SQLite.
 *       Исходная библиотека генерировала только MySQL-синтаксис, то есть upsert
 *       на любой другой СУБД не работал вовсе;</li>
 *   <li>{@code onDuplicateKeyUpdateExcept()} без аргументов больше не означает
 *       «обновлять всё»: пустой список ключей — ошибка, для обновления всех
 *       колонок есть явный {@link #onDuplicateKeyUpdateAll()}. Прежняя семантика
 *       приводила к затиранию первичного ключа при upsert;</li>
 *   <li>нет завершающей точки с запятой.</li>
 * </ul>
 */
public final class Insert implements SqlStatement {

    private final Identifier table;
    private final Map<Identifier, Expression> values = new LinkedHashMap<>();
    private final Set<Identifier> conflictKeys = new LinkedHashSet<>();

    private boolean ignore;
    private boolean upsert;
    private boolean upsertAll;

    /**
     * Конструктор публичный, хотя предполагается создание через
     * {@link me.kirillgrachev.query.Query}: фабрика живёт в соседнем пакете, а
     * package-private конструктор был бы ей недоступен. Прямое создание не
     * запрещается — оно эквивалентно фабрике, но лишает читателя единой точки
     * входа, поэтому в документации рекомендуется {@code Query.*}.
     */
    public Insert(@NotNull Identifier table) {

        this.table = table;

    }

    /**
     * Добавляет {@code IGNORE} (MySQL) или {@code ON CONFLICT DO NOTHING} (остальные).
     *
     * @return этот объект
     */
    public @NotNull Insert ignore() {

        ignore = true;

        return this;

    }

    /**
     * Добавляет значение колонки, подставляемое плейсхолдером.
     *
     * @param column имя колонки
     * @param value  значение, не {@code null}; для NULL используйте {@link #valueNull(String)}
     *               или {@link #valueNullable(String, Object)}
     * @return этот объект
     * @throws QueryException если значение для этой колонки уже добавлено
     */
    public @NotNull Insert value(@NotNull String column, @NotNull Object value) {

        requireNonNull(value, "value");

        return valueExpr(column, "?", value);

    }

    /**
     * Добавляет значение колонки произвольным SQL-выражением.
     *
     * @param column имя колонки
     * @param sql    выражение, например {@code "NOW()"} или {@code "? + 1"}
     * @param params параметры выражения
     * @return этот объект
     * @throws QueryException если значение для этой колонки уже добавлено
     */
    public @NotNull Insert valueExpr(@NotNull String column, @NotNull String sql, Object @NotNull ... params) {

        Identifier id = Identifier.of(column);
        Placeholders.checkCount(sql, params);

        if (values.containsKey(id)) {
            throw QueryException.of(ErrorCode.DUPLICATE_ELEMENT,
                    "Column '" + id.getName() + "' already has a value; a silent overwrite would"
                            + " also reorder the parameter list",
                    Map.of("column", id.getName()));
        }

        values.put(id, Expression.of(sql, params));

        return this;

    }

    /**
     * Добавляет {@code NULL} для колонки.
     *
     * @param column имя колонки
     * @return этот объект
     */
    public @NotNull Insert valueNull(@NotNull String column) {

        return valueExpr(column, "NULL");

    }

    /**
     * Добавляет значение либо {@code NULL}, если значение равно {@code null}.
     *
     * @param column имя колонки
     * @param value  значение или {@code null}
     * @return этот объект
     */
    public @NotNull Insert valueNullable(@NotNull String column, @Nullable Object value) {

        return value == null ? valueNull(column) : valueExpr(column, "?", value);

    }

    /**
     * Включает upsert, обновляющий все добавленные колонки.
     *
     * <p>Отдельный метод вместо {@code onDuplicateKeyUpdateExcept()} без аргументов:
     * прежнее поведение «пустой список означает все колонки» приводило к тому, что
     * при upsert затирались ключевые колонки, и это обнаруживалось уже по данным.
     *
     * @return этот объект
     */
    public @NotNull Insert onDuplicateKeyUpdateAll() {

        upsert = true;
        upsertAll = true;

        return this;

    }

    /**
     * Включает upsert, обновляющий все добавленные колонки кроме перечисленных.
     *
     * @param keys ключевые колонки, которые не должны обновляться; минимум одна
     * @return этот объект
     * @throws QueryException если список ключей пуст
     */
    public @NotNull Insert onDuplicateKeyUpdateExcept(@NotNull String @NotNull ... keys) {

        if (keys.length == 0) {
            throw QueryException.of(ErrorCode.INVALID_ARGUMENT_VALUE,
                    "At least one key column is required; use onDuplicateKeyUpdateAll()"
                            + " to update every column explicitly");
        }

        upsert = true;
        upsertAll = false;
        conflictKeys.clear();

        for (String key : keys) {
            conflictKeys.add(Identifier.of(key));
        }

        return this;

    }

    @Override
    public @NotNull String getSql(@NotNull Dialect dialect) {

        if (values.isEmpty()) {
            throw QueryException.of(ErrorCode.INCOMPLETE_STATEMENT,
                    "INSERT requires at least one value");
        }

        if (ignore && upsert) {
            throw QueryException.of(ErrorCode.CONFLICTING_CLAUSES,
                    "IGNORE and upsert cannot be combined: the first skips conflicting rows,"
                            + " the second updates them");
        }

        // H2 не поддерживает ни ON CONFLICT, ни INSERT IGNORE (проверено на
        // 2.2.224), но поддерживает MERGE INTO ... KEY(...) VALUES (...), который
        // обновляет строку при совпадении ключа. Для H2 форма запроса другая
        // целиком, а не только хвост, поэтому ветвление делается до сборки.
        if (upsert && dialect == Dialect.H2) {
            return renderH2Merge(dialect);
        }

        StringBuilder sb = new StringBuilder("INSERT ");

        if (ignore && isMysqlFamily(dialect)) {
            sb.append("IGNORE ");
        }

        sb.append("INTO ").append(table.render(dialect)).append(" (");

        int index = 0;

        for (Identifier column : values.keySet()) {
            if (index++ > 0) {
                sb.append(", ");
            }

            sb.append(column.render(dialect));
        }

        sb.append(") VALUES (");

        index = 0;

        for (Expression expression : values.values()) {
            if (index++ > 0) {
                sb.append(", ");
            }

            sb.append(expression.getSql());
        }

        sb.append(')');

        if (ignore && !isMysqlFamily(dialect)) {
            sb.append(renderPostgresIgnore(dialect));
        } else if (upsert) {
            sb.append(renderUpsert(dialect));
        }

        return sb.toString();

    }

    @Override
    public @NotNull List<Object> getParams() {

        List<Object> params = new ArrayList<>();

        for (Expression expression : values.values()) {
            params.addAll(expression.getParams());
        }

        // Параметры НЕ дублируются для upsert. Исходная библиотека рендерила
        // "ON DUPLICATE KEY UPDATE col = <то же выражение>" и потому вынуждена была
        // добавлять параметры второй раз; здесь обновляемое значение берётся из уже
        // вставленной строки — VALUES(col) в MySQL и EXCLUDED.col в стандартном
        // синтаксисе, — поэтому новых плейсхолдеров не появляется. Дублирование
        // параметров дало бы запрос, где число значений не совпадает с числом
        // плейсхолдеров, и драйвер отклонил бы его.
        return List.copyOf(params);

    }

    /**
     * Рендерит upsert в форме {@code MERGE INTO ... KEY(...) VALUES (...)}.
     *
     * <p>Параметры в такой форме встречаются один раз — в списке VALUES, поэтому
     * {@link #getParams()} не требует для H2 никакой особой обработки.
     *
     * @param dialect диалект, всегда {@link Dialect#H2}
     * @return полный текст запроса
     */
    private @NotNull String renderH2Merge(@NotNull Dialect dialect) {

        if (conflictKeys.isEmpty()) {
            throw QueryException.of(ErrorCode.UNSUPPORTED_DIALECT_FEATURE,
                    "MERGE INTO ... KEY(...) requires the key columns;"
                            + " use onDuplicateKeyUpdateExcept(keys) on " + dialect.name(),
                    Map.of("dialect", dialect.name()));
        }

        StringBuilder sb = new StringBuilder("MERGE INTO ").append(table.render(dialect)).append(" (");

        int index = 0;

        for (Identifier column : values.keySet()) {
            if (index++ > 0) {
                sb.append(", ");
            }

            sb.append(column.render(dialect));
        }

        sb.append(") KEY (");

        index = 0;

        for (Identifier key : conflictKeys) {
            if (index++ > 0) {
                sb.append(", ");
            }

            sb.append(key.render(dialect));
        }

        sb.append(") VALUES (");

        index = 0;

        for (Expression expression : values.values()) {
            if (index++ > 0) {
                sb.append(", ");
            }

            sb.append(expression.getSql());
        }

        return sb.append(')').toString();

    }

    private boolean isMysqlFamily(@NotNull Dialect dialect) {

        return dialect == Dialect.MYSQL;

    }

    private @NotNull String renderPostgresIgnore(@NotNull Dialect dialect) {

        return switch (dialect) {
            // Пустая строка для неподдерживаемого диалекта означала бы, что IGNORE
            // молча выброшен и конфликтующие строки вызовут ошибку вместо пропуска.
            // H2 проверен эмпирически: ON CONFLICT DO NOTHING он не поддерживает,
            // равно как и INSERT IGNORE.
            case POSTGRES, SQLITE -> " ON CONFLICT DO NOTHING";
            default -> throw QueryException.of(ErrorCode.UNSUPPORTED_DIALECT_FEATURE,
                    "INSERT IGNORE has no equivalent in " + dialect.name(),
                    Map.of("dialect", dialect.name()));
        };

    }

    /**
     * Рендерит upsert в синтаксисе диалекта.
     *
     * <p>MySQL обновляет значения через {@code VALUES(col)}, поэтому параметры в
     * SQL не повторяются; стандартный синтаксис использует {@code EXCLUDED.col} и
     * тоже не требует повторных параметров. Оба варианта выбраны так, чтобы список
     * параметров оставался равным списку значений вставки.
     *
     * @param dialect диалект
     * @return текст upsert-клаузы с ведущим пробелом
     */
    private @NotNull String renderUpsert(@NotNull Dialect dialect) {

        Set<Identifier> updated = new LinkedHashSet<>();

        for (Identifier column : values.keySet()) {
            if (upsertAll || !conflictKeys.contains(column)) {
                updated.add(column);
            }
        }

        if (updated.isEmpty()) {
            throw QueryException.of(ErrorCode.INCOMPLETE_STATEMENT,
                    "Upsert would update zero columns; every inserted column is listed as a key",
                    Map.of("keys", conflictKeys.stream().map(Identifier::getName).toList()));
        }

        if (isMysqlFamily(dialect)) {
            StringBuilder sb = new StringBuilder(" ON DUPLICATE KEY UPDATE ");
            int i = 0;

            for (Identifier column : updated) {
                if (i++ > 0) {
                    sb.append(", ");
                }

                sb.append(column.render(dialect)).append(" = VALUES(")
                        .append(column.render(dialect)).append(')');
            }

            return sb.toString();
        }

        return switch (dialect) {
            case POSTGRES, SQLITE -> {
                if (conflictKeys.isEmpty()) {
                    throw QueryException.of(ErrorCode.UNSUPPORTED_DIALECT_FEATURE,
                            "ON CONFLICT DO UPDATE requires the conflict target;"
                                    + " use onDuplicateKeyUpdateExcept(keys) instead of"
                                    + " onDuplicateKeyUpdateAll() on " + dialect.name(),
                            Map.of("dialect", dialect.name()));
                }

                StringBuilder sb = new StringBuilder(" ON CONFLICT (");
                int i = 0;

                for (Identifier key : conflictKeys) {
                    if (i++ > 0) {
                        sb.append(", ");
                    }

                    sb.append(key.render(dialect));
                }

                sb.append(") DO UPDATE SET ");
                i = 0;

                for (Identifier column : updated) {
                    if (i++ > 0) {
                        sb.append(", ");
                    }

                    sb.append(column.render(dialect)).append(" = EXCLUDED.")
                            .append(column.render(dialect));
                }

                yield sb.toString();
            }
            default -> throw QueryException.of(ErrorCode.UNSUPPORTED_DIALECT_FEATURE,
                    "Upsert is not supported for dialect " + dialect.name(),
                    Map.of("dialect", dialect.name()));
        };

    }

    private static void requireNonNull(@Nullable Object value, @NotNull String name) {

        if (value == null) {
            throw QueryException.of(ErrorCode.NULL_ARGUMENT,
                    "Argument '" + name + "' must not be null; use valueNull() or valueNullable()",
                    Map.of("argument", name));
        }

    }

    /**
     * Имена колонок в порядке добавления.
     *
     * @return неизменяемая коллекция имён
     */
    public @NotNull Collection<String> getColumns() {

        return values.keySet().stream().map(Identifier::getName).toList();

    }

}
