package me.kirillgrachev.query.sql;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * Элемент клаузы {@code ORDER BY} с собственным направлением сортировки.
 *
 * <p>Отдельный публичный тип, а не поле внутри {@link Select}: исходная
 * библиотека хранила одно выражение {@code orderBy} и один глобальный флаг
 * {@code desc}, из-за чего {@code ORDER BY a ASC, b DESC} было невыразимо,
 * а повторный вызов {@code orderBy} молча перезаписывал предыдущий.
 *
 * <p>Тип публичный, потому что направление входит в публичную сигнатуру
 * {@code Select.orderBy(String, Order.Direction)}: приватный вложенный тип в
 * публичном методе сделал бы этот метод непригодным для вызова.
 *
 * <p>Неизменяемый: созданный элемент можно переиспользовать в нескольких запросах.
 */
public final class Order {

    /** Направление сортировки. */
    public enum Direction {
        /** По возрастанию. */
        ASC,
        /** По убыванию. */
        DESC
    }

    /** Колонка, если сортировка задана именем колонки. */
    private final @Nullable Identifier column;

    /** Выражение, если сортировка задана произвольным SQL. */
    private final @Nullable Expression expression;

    private final @NotNull Direction direction;

    private Order(@Nullable Identifier column,
                  @Nullable Expression expression,
                  @NotNull Direction direction) {

        this.column = column;
        this.expression = expression;
        this.direction = direction;

    }

    /**
     * Создаёт элемент по имени колонки.
     *
     * @param column    валидированное имя колонки
     * @param direction направление
     * @return элемент сортировки
     */
    static @NotNull Order ofColumn(@NotNull Identifier column, @NotNull Direction direction) {
        return new Order(column, null, direction);
    }

    /**
     * Создаёт элемент по произвольному выражению.
     *
     * @param expression выражение с уже проверенными плейсхолдерами
     * @param direction  направление
     * @return элемент сортировки
     */
    static @NotNull Order ofExpression(@NotNull Expression expression, @NotNull Direction direction) {
        return new Order(null, expression, direction);
    }

    /**
     * Рендерит элемент: {@code "column" ASC}.
     *
     * <p>Направление указывается всегда, в том числе для {@code ASC}. Исходная
     * библиотека оборачивала выражение в скобки и добавляла направление, но
     * скобки вокруг имени колонки избыточны и мешают использованию индекса
     * некоторыми планировщиками.
     *
     * @param dialect диалект для экранирования имени колонки
     * @return текст элемента
     */
    @NotNull String render(@NotNull Dialect dialect) {
        String rendered = column != null ? column.render(dialect) : expression.getSql();
        return rendered + " " + direction.name();
    }

    /**
     * Добавляет параметры выражения в общий список.
     *
     * @param target список параметров запроса
     */
    void collectParams(@NotNull List<Object> target) {
        if (expression != null) {
            target.addAll(expression.getParams());
        }
    }

    @Override
    public String toString() {
        return (column != null ? column.toString() : expression.getSql()) + " " + direction.name();
    }

    /**
     * Параметры элемента сортировки.
     *
     * @return неизменяемый список, пустой для сортировки по имени колонки
     */
    public @NotNull List<Object> getParams() {
        return expression == null ? Collections.emptyList() : expression.getParams();
    }

}
