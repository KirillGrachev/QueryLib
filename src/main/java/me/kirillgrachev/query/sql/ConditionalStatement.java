package me.kirillgrachev.query.sql;

import me.kirillgrachev.query.exception.ErrorCode;
import me.kirillgrachev.query.exception.QueryException;
import me.kirillgrachev.query.internal.Placeholders;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * База для запросов с клаузой {@code WHERE}.
 *
 * <p>Package-private: наружный API — это {@link Select}, {@link Update} и
 * {@link Delete}, а способ организации условий является деталью реализации и может
 * меняться. В исходной библиотеке аналогичный класс {@code ConditionalQuery} был
 * публичным и защищённое поле {@code conditions} в нём было доступно наследникам,
 * то есть внутреннее представление оказалось частью контракта.
 *
 * <p>Условия соединяются через {@code AND} и рендерятся в порядке добавления.
 * Каждый элемент — {@link Condition}, поэтому произвольная булева логика выражается
 * вложенными {@code allOf}/{@code anyOf} без обращения к сырому SQL.
 *
 * @param <T> тип наследника для сохранения fluent-сигнатур
 */
abstract class ConditionalStatement<T extends ConditionalStatement<T>> implements SqlStatement {

    /** Условия WHERE в порядке добавления, соединяются через AND. */
    private final List<Condition> conditions = new ArrayList<>();

    /**
     * Добавляет условие равенства {@code column = value}.
     *
     * @param column имя колонки
     * @param value  значение, не {@code null}; для NULL используйте {@link #whereNull(String)}
     * @return этот объект
     */
    public final @NotNull T where(@NotNull String column, @NotNull Object value) {

        return where(Conditions.eq(column, value));

    }

    /**
     * Добавляет произвольное условие.
     *
     * @param condition условие, собранное фабриками {@link Conditions}
     * @return этот объект
     */
    public final @NotNull T where(@NotNull Condition condition) {

        if (condition == null) {
            throw QueryException.of(ErrorCode.NULL_ARGUMENT, "Condition must not be null");
        }

        conditions.add(condition);

        return self();

    }

    /**
     * Добавляет условие по сырому SQL-выражению.
     *
     * @param sql    выражение, например {@code "created_at > NOW() - INTERVAL ? DAY"}
     * @param params параметры
     * @return этот объект
     */
    public final @NotNull T whereExpr(@NotNull String sql, Object @NotNull ... params) {

        return where(Conditions.raw(sql, params));

    }

    /**
     * Добавляет условие {@code column IS NULL}.
     *
     * @param column имя колонки
     * @return этот объект
     */
    public final @NotNull T whereNull(@NotNull String column) {

        return where(Conditions.isNull(column));

    }

    /**
     * Добавляет условие {@code column IS NOT NULL}.
     *
     * @param column имя колонки
     * @return этот объект
     */
    public final @NotNull T whereNotNull(@NotNull String column) {

        return where(Conditions.isNotNull(column));

    }

    /**
     * Добавляет условие {@code column IN (...)}.
     *
     * @param column имя колонки
     * @param values непустой набор значений
     * @return этот объект
     */
    public final @NotNull T whereIn(@NotNull String column, @NotNull java.util.Collection<?> values) {

        return where(Conditions.in(column, values));

    }

    /**
     * Добавляет условие, если {@code value} может быть {@code null}.
     *
     * <p>Для {@code null} генерируется {@code IS NULL}, иначе обычное равенство.
     * Раздельные методы {@code where} и {@code whereNullable} в исходной
     * библиотеке приводили к тому, что вызывающая сторона выбирала неверный и
     * получала {@code col = NULL}, которое не находит ни одной строки.
     *
     * @param column имя колонки
     * @param value  значение или {@code null}
     * @return этот объект
     */
    public final @NotNull T whereNullable(@NotNull String column, Object value) {

        return value == null ? whereNull(column) : where(column, value);

    }

    /**
     * Рендерит клаузу {@code WHERE} без окружающих пробелов.
     *
     * <p>Пробелы намеренно не добавляются: разделители между клаузами расставляет
     * вызывающая сторона, которая знает, будет ли после WHERE ещё что-то. Исходная
     * библиотека добавляла завершающий пробел безусловно, из-за чего в SQL
     * появлялся артефакт вида "... WHERE (`c` = ?) ;".
     *
     * @param dialect диалект для экранирования идентификаторов
     * @return текст клаузы либо пустая строка, если условий нет
     */
    protected final @NotNull String renderWhere(@NotNull Dialect dialect) {

        if (conditions.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("WHERE ");

        for (int i = 0; i < conditions.size(); i++) {
            if (i > 0) {
                sb.append(" AND ");
            }

            sb.append(conditions.get(i).render(dialect));
        }

        return sb.toString();

    }

    /**
     * Собирает параметры всех условий в порядке их следования в SQL.
     *
     * @param target список, в который добавляются параметры
     */
    protected final void collectConditionParams(@NotNull List<Object> target) {

        for (Condition condition : conditions) {
            target.addAll(condition.getParams());
        }

    }

    protected final boolean hasConditions() {

        return !conditions.isEmpty();

    }

    /**
     * Возвращает себя с типом наследника.
     *
     * <p>Проверенное приведение вместо {@code (T) this} с подавлением предупреждения:
     * ограничение типа {@code T extends ConditionalStatement<T>} гарантирует
     * корректность, а явный метод делает это место единственным, где приведение
     * вообще происходит.
     */
    @SuppressWarnings("unchecked")
    private @NotNull T self() {

        return (T) this;

    }

    /**
     * Проверяет соответствие плейсхолдеров и параметров в произвольном выражении.
     *
     * <p>Вынесено сюда, потому что проверка нужна и в {@code ORDER BY}, и в
     * {@code SET}, и в значениях {@code INSERT}.
     *
     * @param sql    выражение
     * @param params параметры
     */
    static void checkPlaceholders(@NotNull String sql, Object @NotNull ... params) {

        Placeholders.checkCount(sql, params);

    }

    /**
     * Создаёт неизменяемый список-копию параметров.
     *
     * @param params исходный массив, может содержать {@code null}
     * @return неизменяемый список
     */
    static @NotNull List<Object> copyParams(Object @NotNull ... params) {

        List<Object> copy = new ArrayList<>(params.length);
        Collections.addAll(copy, params);

        return Collections.unmodifiableList(copy);

    }

}
