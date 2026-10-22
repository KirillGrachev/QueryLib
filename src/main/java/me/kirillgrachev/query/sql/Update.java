package me.kirillgrachev.query.sql;

import me.kirillgrachev.query.exception.ErrorCode;
import me.kirillgrachev.query.exception.QueryException;
import me.kirillgrachev.query.internal.Placeholders;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Запрос {@code UPDATE}.
 *
 * <p>Главное отличие от исходной реализации — защита от обновления всей таблицы.
 * {@code UPDATE} без {@code WHERE} перезаписывает каждый ряд, и это вторая по
 * разрушительности операция после безусловного {@code DELETE}. Здесь такой запрос
 * требует явного подтверждения намерением {@link #allRows()}: случайная ошибка
 * (забытый {@code where}) превращается из потерянных данных в понятное исключение.
 *
 * <p>Повторное присваивание той же колонке — ошибка, а не молчаливая перезапись:
 * в исходной версии значения хранились в {@code Map}, и второе
 * {@code value("a", ...)} незаметно отменяло первое.
 */
public final class Update extends ConditionalStatement<Update> {

    private final Identifier table;
    private final Map<Identifier, Expression> assignments = new LinkedHashMap<>();

    private boolean allRows;

    /**
     * Конструктор публичный, хотя предполагается создание через
     * {@link me.kirillgrachev.query.Query}: фабрика живёт в соседнем пакете, а
     * package-private конструктор был бы ей недоступен. Прямое создание не
     * запрещается — оно эквивалентно фабрике, но лишает читателя единой точки
     * входа, поэтому в документации рекомендуется {@code Query.*}.
     */
    public Update(@NotNull Identifier table) {
        this.table = table;
    }

    /**
     * Добавляет присваивание {@code column = ?}.
     *
     * @param column имя колонки
     * @param value  значение, не {@code null}
     * @return этот объект
     * @throws QueryException если колонке уже присвоено значение
     */
    public @NotNull Update value(@NotNull String column, @NotNull Object value) {

        if (value == null) {
            throw QueryException.of(ErrorCode.NULL_ARGUMENT,
                    "Value must not be null; use valueNull() or valueNullable()",
                    Map.of("column", column));
        }

        return valueExpr(column, "?", value);

    }

    /**
     * Добавляет присваивание произвольным выражением.
     *
     * @param column имя колонки
     * @param sql    выражение, например {@code "counter + ?"}
     * @param params параметры
     * @return этот объект
     */
    public @NotNull Update valueExpr(@NotNull String column, @NotNull String sql, Object @NotNull ... params) {

        Identifier id = Identifier.of(column);
        Placeholders.checkCount(sql, params);

        if (assignments.containsKey(id)) {
            throw QueryException.of(ErrorCode.DUPLICATE_ELEMENT,
                    "Column '" + id.getName() + "' is already assigned",
                    Map.of("column", id.getName()));
        }

        assignments.put(id, Expression.of(sql, params));

        return this;

    }

    /**
     * Добавляет присваивание {@code NULL}.
     *
     * @param column имя колонки
     * @return этот объект
     */
    public @NotNull Update valueNull(@NotNull String column) {
        return valueExpr(column, "NULL");
    }

    /**
     * Добавляет значение либо {@code NULL}, если оно равно {@code null}.
     *
     * @param column имя колонки
     * @param value  значение или {@code null}
     * @return этот объект
     */
    public @NotNull Update valueNullable(@NotNull String column, @Nullable Object value) {
        return value == null ? valueNull(column) : valueExpr(column, "?", value);
    }

    /**
     * Явно разрешает обновление всех строк таблицы.
     *
     * <p>Без этого вызова {@code UPDATE} без {@code WHERE} не генерируется.
     *
     * @return этот объект
     */
    public @NotNull Update allRows() {
        allRows = true;
        return this;
    }

    @Override
    public @NotNull String getSql(@NotNull Dialect dialect) {

        if (assignments.isEmpty()) {
            throw QueryException.of(ErrorCode.INCOMPLETE_STATEMENT,
                    "UPDATE requires at least one assignment");
        }

        if (!hasConditions() && !allRows) {
            throw QueryException.of(ErrorCode.UNCONDITIONAL_MODIFICATION,
                    "UPDATE without WHERE would rewrite every row; call allRows() to confirm"
                            + " the intent, or add a condition",
                    Map.of("table", table.toString()));
        }

        StringBuilder sb = new StringBuilder("UPDATE ").append(table.render(dialect)).append(" SET ");

        int index = 0;

        for (Map.Entry<Identifier, Expression> entry : assignments.entrySet()) {
            if (index++ > 0) {
                sb.append(", ");
            }

            sb.append(entry.getKey().render(dialect)).append(" = ").append(entry.getValue().getSql());
        }

        String where = renderWhere(dialect);

        if (!where.isEmpty()) {
            sb.append(' ').append(where);
        }

        return sb.toString();

    }

    @Override
    public @NotNull List<Object> getParams() {

        List<Object> params = new ArrayList<>();

        // Присваивания идут в SQL раньше условий, поэтому и параметры
        // собираются в том же порядке: привязка выполняется позиционно.
        for (Expression expression : assignments.values()) {
            params.addAll(expression.getParams());
        }

        collectConditionParams(params);

        return List.copyOf(params);

    }

}
