package me.kirillgrachev.query.sql;

import me.kirillgrachev.query.exception.ErrorCode;
import me.kirillgrachev.query.exception.QueryException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Запрос {@code SELECT}.
 *
 * <p>Отличия от исходной реализации, каждое из которых закрывает конкретный дефект:
 * <ul>
 *   <li>{@code ORDER BY} хранится списком элементов с направлением у каждого,
 *       а не одним выражением с глобальным флагом {@code desc}: построить
 *       {@code ORDER BY a ASC, b DESC} было невозможно в принципе;</li>
 *   <li>добавлены {@code DISTINCT}, {@code JOIN}, {@code GROUP BY}/{@code HAVING} —
 *       без них самые частые запросы приходилось писать сырым SQL;</li>
 *   <li>пагинация рендерится диалектом, а не захардкоженным {@code LIMIT n OFFSET m},
 *       который не работает в SQL Server и в Oracle до 12c;</li>
 *   <li>результат не содержит завершающей точки с запятой и лишнего пробела перед ней;</li>
 *   <li>имя схемы валидируется так же, как имя таблицы: в исходной версии
 *       {@code from(database, table)} проверял только таблицу, что давало
 *       SQL-инъекцию через имя базы;</li>
 *   <li>алиас таблицы действительно применяется: в исходной версии перегрузка
 *       {@code from(database, table)} принимала два имени, и отдельного алиаса
 *       не было вовсе.</li>
 * </ul>
 *
 * <p>Не потокобезопасен на этапе построения, как и любой fluent-билдер: собирать
 * запрос нужно в одном потоке. Построенный объект исполняется многократно, но
 * сам по себе остаётся изменяемым, поэтому переиспользовать один экземпляр
 * {@code Select} между потоками нельзя — соберите новый.
 */
public final class Select extends ConditionalStatement<Select> {

    private final List<Selection> selection = new ArrayList<>();
    private final List<Join> joins = new ArrayList<>();
    private final List<Identifier> groupBy = new ArrayList<>();
    private final List<Order> orderBy = new ArrayList<>();

    private boolean all;
    private boolean distinct;

    private @Nullable Identifier table;
    private @Nullable Identifier tableAlias;
    private @Nullable Expression having;
    private @Nullable Long limit;
    private @Nullable Long offset;
    private boolean forUpdate;

    /**
     * Конструктор публичный, хотя предполагается создание через
     * {@link me.kirillgrachev.query.Query}: фабрика живёт в соседнем пакете, а
     * package-private конструктор был бы ей недоступен. Прямое создание не
     * запрещается — оно эквивалентно фабрике, но лишает читателя единой точки
     * входа, поэтому в документации рекомендуется {@code Query.*}.
     */
    public Select() {
    }

    /**
     * Выбирает все колонки ({@code SELECT *}).
     *
     * @return этот объект
     * @throws QueryException если колонки уже были добавлены
     */
    public @NotNull Select all() {

        if (!selection.isEmpty()) {
            throw QueryException.of(ErrorCode.CONFLICTING_CLAUSES,
                    "Cannot select all columns: explicit columns were already added",
                    Map.of("columnsAdded", selection.size()));
        }

        all = true;

        return this;

    }

    /**
     * Добавляет {@code DISTINCT}.
     *
     * @return этот объект
     */
    public @NotNull Select distinct() {
        distinct = true;
        return this;
    }

    /**
     * Добавляет колонку к списку выбираемых.
     *
     * @param name имя колонки
     * @return этот объект
     */
    public @NotNull Select col(@NotNull String name) {
        selection.add(Selection.ofColumn(Identifier.of(name), null));
        return this;
    }

    /**
     * Добавляет колонку с алиасом: {@code "column" AS "alias"}.
     *
     * @param name  имя колонки
     * @param alias алиас
     * @return этот объект
     */
    public @NotNull Select col(@NotNull String name, @NotNull String alias) {
        selection.add(Selection.ofColumn(Identifier.of(name), Identifier.of(alias)));
        return this;
    }

    /**
     * Добавляет произвольное выражение к списку выбираемых.
     *
     * <p>Выражение считается готовым SQL и выводится дословно, в отличие от
     * {@link #col(String)}, где имя экранируется диалектом. Различие хранится
     * в типе элемента, а не угадывается по содержимому строки.
     *
     * @param sql    выражение, например {@code "COUNT(*)"}
     * @param params параметры
     * @return этот объект
     */
    public @NotNull Select expr(@NotNull String sql, Object @NotNull ... params) {

        checkPlaceholders(sql, params);

        if (all) {
            throw QueryException.of(ErrorCode.CONFLICTING_CLAUSES,
                    "Cannot add expressions when selecting all columns");
        }

        selection.add(Selection.ofExpression(Expression.of(sql, params), null));

        return this;

    }

    /**
     * Добавляет выражение с алиасом: {@code COUNT(*) AS "total"}.
     *
     * @param sql    выражение
     * @param alias  алиас
     * @param params параметры
     * @return этот объект
     */
    public @NotNull Select expr(@NotNull String sql, @NotNull String alias, Object @NotNull ... params) {

        checkPlaceholders(sql, params);
        selection.add(Selection.ofExpression(Expression.of(sql, params), Identifier.of(alias)));

        return this;

    }

    /**
     * Задаёт таблицу.
     *
     * @param tableName имя таблицы
     * @return этот объект
     */
    public @NotNull Select from(@NotNull String tableName) {

        table = Identifier.of(tableName);
        tableAlias = null;

        return this;

    }

    /**
     * Задаёт схему и таблицу.
     *
     * <p>Валидируются ОБА имени. В исходной реализации проверялось только имя
     * таблицы, а имя базы подставлялось в кавычки как есть: это позволяло закрыть
     * кавычку и выполнить произвольный SQL.
     *
     * @param schema    имя схемы или базы данных
     * @param tableName имя таблицы
     * @return этот объект
     */
    public @NotNull Select from(@NotNull String schema, @NotNull String tableName) {

        table = Identifier.of(schema, tableName);
        tableAlias = null;

        return this;

    }

    /**
     * Задаёт таблицу с алиасом: {@code FROM "table" AS "t"}.
     *
     * @param tableName имя таблицы
     * @param alias     алиас таблицы
     * @return этот объект
     */
    public @NotNull Select fromAs(@NotNull String tableName, @NotNull String alias) {

        table = Identifier.of(tableName);
        tableAlias = Identifier.of(alias);

        return this;

    }

    /**
     * Добавляет {@code INNER JOIN}.
     *
     * @param tableName присоединяемая таблица
     * @param on        условие соединения
     * @return этот объект
     */
    public @NotNull Select innerJoin(@NotNull String tableName, @NotNull Condition on) {
        return join(Join.Type.INNER, tableName, on);
    }

    /**
     * Добавляет {@code LEFT JOIN}.
     *
     * @param tableName присоединяемая таблица
     * @param on        условие соединения
     * @return этот объект
     */
    public @NotNull Select leftJoin(@NotNull String tableName, @NotNull Condition on) {
        return join(Join.Type.LEFT, tableName, on);
    }

    /**
     * Добавляет {@code RIGHT JOIN}.
     *
     * @param tableName присоединяемая таблица
     * @param on        условие соединения
     * @return этот объект
     */
    public @NotNull Select rightJoin(@NotNull String tableName, @NotNull Condition on) {
        return join(Join.Type.RIGHT, tableName, on);
    }

    /**
     * Добавляет колонку к {@code GROUP BY}.
     *
     * @param column имя колонки
     * @return этот объект
     */
    public @NotNull Select groupBy(@NotNull String column) {
        // Хранится Identifier, а не готовый текст: диалект становится известен
        // только в момент рендера, и предварительно экранированное через
        // Dialect.NONE имя осталось бы без кавычек во всех диалектах.
        groupBy.add(Identifier.of(column));
        return this;
    }

    /**
     * Добавляет условие {@code HAVING}.
     *
     * @param sql    выражение, например {@code "COUNT(*) > ?"}
     * @param params параметры
     * @return этот объект
     */
    public @NotNull Select having(@NotNull String sql, Object @NotNull ... params) {

        checkPlaceholders(sql, params);
        having = Expression.of(sql, params);

        return this;

    }

    /**
     * Добавляет элемент {@code ORDER BY} с сортировкой по возрастанию.
     *
     * <p>Можно вызывать несколько раз: каждый вызов добавляет элемент, а не
     * заменяет предыдущий.
     *
     * @param column имя колонки
     * @return этот объект
     */
    public @NotNull Select orderBy(@NotNull String column) {
        return orderBy(column, Order.Direction.ASC);
    }

    /**
     * Добавляет элемент {@code ORDER BY} с явным направлением.
     *
     * @param column    имя колонки
     * @param direction направление сортировки
     * @return этот объект
     */
    public @NotNull Select orderBy(@NotNull String column, @NotNull Order.Direction direction) {
        orderBy.add(Order.ofColumn(Identifier.of(column), direction));
        return this;
    }

    /**
     * Добавляет произвольное выражение в {@code ORDER BY}.
     *
     * @param sql       выражение
     * @param direction направление
     * @param params    параметры
     * @return этот объект
     */
    public @NotNull Select orderByExpr(@NotNull String sql,
                                       @NotNull Order.Direction direction,
                                       Object @NotNull ... params) {

        checkPlaceholders(sql, params);
        orderBy.add(Order.ofExpression(Expression.of(sql, params), direction));

        return this;

    }

    /**
     * Устанавливает {@code LIMIT}.
     *
     * @param limit положительное число строк
     * @return этот объект
     * @throws QueryException если значение меньше единицы
     */
    public @NotNull Select limit(long limit) {

        if (limit < 1) {
            throw QueryException.of(ErrorCode.INVALID_ARGUMENT_VALUE,
                    "LIMIT must be at least 1", Map.of("limit", limit));
        }

        this.limit = limit;

        return this;

    }

    /**
     * Устанавливает {@code OFFSET}.
     *
     * @param offset неотрицательное смещение
     * @return этот объект
     * @throws QueryException если значение отрицательное
     */
    public @NotNull Select offset(long offset) {

        if (offset < 0) {
            throw QueryException.of(ErrorCode.INVALID_ARGUMENT_VALUE,
                    "OFFSET must not be negative", Map.of("offset", offset));
        }

        this.offset = offset;

        return this;

    }

    /**
     * Добавляет {@code FOR UPDATE}.
     *
     * <p>Имеет смысл только внутри транзакции: вне её блокировка снимается сразу
     * после чтения. Используется вместе с {@code Sql.inTransaction(...)}.
     *
     * @return этот объект
     */
    public @NotNull Select forUpdate() {
        forUpdate = true;
        return this;
    }

    @Override
    public @NotNull String getSql(@NotNull Dialect dialect) {

        if (!all && selection.isEmpty()) {
            throw QueryException.of(ErrorCode.INCOMPLETE_STATEMENT,
                    "SELECT requires at least one column or expression, or an explicit all()");
        }

        StringBuilder sb = new StringBuilder("SELECT ");

        if (distinct) {
            sb.append("DISTINCT ");
        }

        if (all) {
            sb.append('*');
        } else {
            for (int i = 0; i < selection.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }

                sb.append(selection.get(i).render(dialect));
            }
        }

        if (table != null) {
            sb.append(" FROM ").append(table.render(dialect));

            if (tableAlias != null) {
                sb.append(" AS ").append(dialect.quote(tableAlias.getName()));
            }
        }

        for (Join join : joins) {
            sb.append(' ').append(join.render(dialect));
        }

        String where = renderWhere(dialect);

        if (!where.isEmpty()) {
            sb.append(' ').append(where);
        }

        if (!groupBy.isEmpty()) {
            sb.append(" GROUP BY ");

            for (int i = 0; i < groupBy.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }

                sb.append(groupBy.get(i).render(dialect));
            }
        }

        if (having != null) {
            sb.append(" HAVING ").append(having.getSql());
        }

        if (!orderBy.isEmpty()) {
            sb.append(" ORDER BY ");

            for (int i = 0; i < orderBy.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }

                sb.append(orderBy.get(i).render(dialect));
            }
        }

        // Пагинация целиком на стороне диалекта: LIMIT/OFFSET и OFFSET/FETCH
        // несовместимы, и выбор синтаксиса не должен зависеть от вызывающей стороны.
        sb.append(dialect.renderPagination(limit, offset));

        if (forUpdate) {
            if (dialect == Dialect.SQL_SERVER) {
                throw QueryException.of(ErrorCode.UNSUPPORTED_DIALECT_FEATURE,
                        "FOR UPDATE has no SQL Server equivalent; use the UPDLOCK hint",
                        Map.of("dialect", dialect.name()));
            }

            // В SQLite нет FOR UPDATE: любая запись и так блокирует всю базу, а сам
            // синтаксис движок отвергает сырой ошибкой уже на исполнении. Отказываем
            // на этапе построения запроса — как для SQL Server выше.
            if (dialect == Dialect.SQLITE) {
                throw QueryException.of(ErrorCode.UNSUPPORTED_DIALECT_FEATURE,
                        "SQLite has no FOR UPDATE: writes lock the whole database; drop forUpdate()",
                        Map.of("dialect", dialect.name()));
            }

            sb.append(" FOR UPDATE");
        }

        return sb.toString();

    }

    @Override
    public @NotNull List<Object> getParams() {

        List<Object> params = new ArrayList<>();

        // Порядок обязан совпадать с порядком плейсхолдеров в getSql:
        // выборка, соединения, WHERE, HAVING, ORDER BY.
        for (Selection item : selection) {
            item.collectParams(params);
        }

        for (Join join : joins) {
            params.addAll(join.condition.getParams());
        }

        collectConditionParams(params);

        if (having != null) {
            params.addAll(having.getParams());
        }

        for (Order order : orderBy) {
            order.collectParams(params);
        }

        return List.copyOf(params);

    }

    private @NotNull Select join(@NotNull Join.Type type,
                                 @NotNull String tableName,
                                 @NotNull Condition on) {

        if (on == null) {
            throw QueryException.of(ErrorCode.NULL_ARGUMENT, "JOIN condition must not be null");
        }

        joins.add(new Join(type, Identifier.of(tableName), on));

        return this;

    }

    /**
     * Элемент списка выбора.
     *
     * <p>Хранит либо идентификатор колонки (тогда имя экранируется диалектом при
     * рендере), либо готовое выражение (тогда оно выводится дословно), плюс
     * необязательный алиас. Раздельное хранение вместо одной строки нужно потому,
     * что диалект неизвестен в момент добавления колонки, а угадывать «имя это или
     * выражение» по содержимому строки — значит ломаться на легальных именах
     * с пробелами и на выражениях, похожих на имена.
     */
    private static final class Selection {

        private final @Nullable Identifier column;
        private final @Nullable Expression expression;
        private final @Nullable Identifier alias;

        private Selection(@Nullable Identifier column,
                          @Nullable Expression expression,
                          @Nullable Identifier alias) {

            this.column = column;
            this.expression = expression;
            this.alias = alias;

        }

        static @NotNull Selection ofColumn(@NotNull Identifier column, @Nullable Identifier alias) {
            return new Selection(column, null, alias);
        }

        static @NotNull Selection ofExpression(@NotNull Expression expression, @Nullable Identifier alias) {
            return new Selection(null, expression, alias);
        }

        @NotNull String render(@NotNull Dialect dialect) {
            String rendered = column != null ? column.render(dialect) : expression.getSql();
            return alias == null ? rendered : rendered + " AS " + dialect.quote(alias.getName());
        }

        void collectParams(@NotNull List<Object> target) {
            if (expression != null) {
                target.addAll(expression.getParams());
            }
        }

    }

    /** Присоединённая таблица: тип соединения, имя и условие. */
    private static final class Join {

        enum Type {
            INNER, LEFT, RIGHT
        }

        private final Type type;
        private final Identifier table;
        private final Condition condition;

        private Join(Type type, Identifier table, Condition condition) {

            this.type = type;
            this.table = table;
            this.condition = condition;

        }

        @NotNull String render(@NotNull Dialect dialect) {
            return type.name() + " JOIN " + table.render(dialect)
                    + " ON " + condition.render(dialect);
        }

    }

}
