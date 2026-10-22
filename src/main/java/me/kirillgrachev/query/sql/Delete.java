package me.kirillgrachev.query.sql;

import me.kirillgrachev.query.exception.ErrorCode;
import me.kirillgrachev.query.exception.QueryException;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Запрос {@code DELETE}.
 *
 * <p>Ключевое отличие от исходной реализации: {@code DELETE} без {@code WHERE}
 * больше не генерируется по умолчанию. Раньше {@code Query.deleteFrom("users")}
 * молча производил {@code DELETE FROM `users`}, то есть удаление всей таблицы,
 * и это поведение было закреплено тестом как эталонное. Теперь требуется явный
 * вызов {@link #allRows()}, который невозможно сделать случайно.
 *
 * <p>Защита стоит ровно в том месте, где цена ошибки максимальна: восстановление
 * удалённых строк требует бэкапа, а исключение на этапе построения запроса не
 * стоит ничего.
 */
public final class Delete extends ConditionalStatement<Delete> {

    private final Identifier table;

    private boolean allRows;

    /**
     * Конструктор публичный, хотя предполагается создание через
     * {@link me.kirillgrachev.query.Query}: фабрика живёт в соседнем пакете, а
     * package-private конструктор был бы ей недоступен. Прямое создание не
     * запрещается — оно эквивалентно фабрике, но лишает читателя единой точки
     * входа, поэтому в документации рекомендуется {@code Query.*}.
     */
    public Delete(@NotNull Identifier table) {
        this.table = table;
    }

    /**
     * Явно подтверждает намерение удалить все строки таблицы.
     *
     * @return этот объект
     */
    public @NotNull Delete allRows() {
        allRows = true;
        return this;
    }

    @Override
    public @NotNull String getSql(@NotNull Dialect dialect) {

        if (!hasConditions() && !allRows) {
            throw QueryException.of(ErrorCode.UNCONDITIONAL_MODIFICATION,
                    "DELETE without WHERE would remove every row of '" + table
                            + "'; call allRows() to confirm the intent, or add a condition",
                    Map.of("table", table.toString()));
        }

        StringBuilder sb = new StringBuilder("DELETE FROM ").append(table.render(dialect));

        String where = renderWhere(dialect);

        if (!where.isEmpty()) {
            sb.append(' ').append(where);
        }

        return sb.toString();

    }

    @Override
    public @NotNull List<Object> getParams() {

        List<Object> params = new ArrayList<>();
        collectConditionParams(params);

        return List.copyOf(params);

    }

}
