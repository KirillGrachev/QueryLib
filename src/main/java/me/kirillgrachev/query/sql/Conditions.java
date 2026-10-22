package me.kirillgrachev.query.sql;

import me.kirillgrachev.query.exception.ErrorCode;
import me.kirillgrachev.query.exception.QueryException;
import me.kirillgrachev.query.internal.Placeholders;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Фабрики условий {@code WHERE}.
 *
 * <p>Отдельный класс вместо статических методов в {@link Condition}, чтобы
 * импорт читался как {@code Conditions.eq(...)}: вызов {@code Condition.eq(...)}
 * не отличим от обращения к константе перечисления, а здесь фабрики — это
 * именно фабрики.
 *
 * <p>Все фабрики валидируют вход на месте, а не откладывают до рендера. Ошибка в
 * имени колонки должна быть видна в стеке там, где она допущена, а не через десять
 * вызовов, когда запрос собирается.
 */
public final class Conditions {

    private Conditions() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * Равенство: {@code column = ?}.
     *
     * <p>{@code null} не принимается: сравнение с NULL в SQL всегда даёт UNKNOWN,
     * а не TRUE, поэтому {@code col = NULL} не находит ничего и является почти
     * наверняка ошибкой. Для проверки на NULL есть {@link #isNull(String)}.
     *
     * @param column имя колонки
     * @param value  значение, не {@code null}
     * @return условие
     */
    public static @NotNull Condition eq(@NotNull String column, @NotNull Object value) {
        return comparison(column, "=", value);
    }

    public static @NotNull Condition ne(@NotNull String column, @NotNull Object value) {
        return comparison(column, "<>", value);
    }

    public static @NotNull Condition gt(@NotNull String column, @NotNull Object value) {
        return comparison(column, ">", value);
    }

    public static @NotNull Condition ge(@NotNull String column, @NotNull Object value) {
        return comparison(column, ">=", value);
    }

    public static @NotNull Condition lt(@NotNull String column, @NotNull Object value) {
        return comparison(column, "<", value);
    }

    public static @NotNull Condition le(@NotNull String column, @NotNull Object value) {
        return comparison(column, "<=", value);
    }

    public static @NotNull Condition like(@NotNull String column, @NotNull String pattern) {
        return comparison(column, "LIKE", pattern);
    }

    /**
     * Проверка на {@code NULL}: {@code column IS NULL}.
     *
     * @param column имя колонки
     * @return условие
     */
    public static @NotNull Condition isNull(@NotNull String column) {
        Identifier id = Identifier.of(column);
        return SimpleCondition.of(dialect -> id.render(dialect) + " IS NULL", List.of());
    }

    public static @NotNull Condition isNotNull(@NotNull String column) {
        Identifier id = Identifier.of(column);
        return SimpleCondition.of(dialect -> id.render(dialect) + " IS NOT NULL", List.of());
    }

    /**
     * Вхождение в набор: {@code column IN (?, ?, ...)}.
     *
     * <p>Закрывает главный пробел исходной библиотеки: при отсутствии {@code IN}
     * любой запрос с набором значений приходилось писать сырым SQL, то есть ровно
     * тем способом, от которого библиотека должна избавлять.
     *
     * <p>Значения подставляются плейсхолдерами, а не склейкой, поэтому список из
     * пользовательских данных не создаёт инъекцию. Пустой список отклоняется:
     * {@code IN ()} является синтаксической ошибкой в большинстве диалектов, а
     * молчаливая замена на {@code FALSE} спрятала бы ошибку вызывающей стороны.
     *
     * @param column имя колонки
     * @param values непустой набор значений
     * @return условие
     * @throws QueryException если набор пуст
     */
    public static @NotNull Condition in(@NotNull String column, @NotNull Collection<?> values) {
        return membership(column, values, false);
    }

    public static @NotNull Condition notIn(@NotNull String column, @NotNull Collection<?> values) {
        return membership(column, values, true);
    }

    /**
     * Произвольное SQL-выражение как условие.
     *
     * <p>Escape hatch для случаев, которые не покрыты фабриками. Число
     * плейсхолдеров проверяется с учётом строковых литералов и комментариев.
     *
     * @param sql    текст выражения
     * @param params параметры
     * @return условие
     */
    public static @NotNull Condition raw(@NotNull String sql, @Nullable Object @NotNull ... params) {

        Placeholders.checkCount(sql, params);

        List<Object> copy = new ArrayList<>(params.length);
        Collections.addAll(copy, params);

        return SimpleCondition.of(dialect -> "(" + sql + ")", Collections.unmodifiableList(copy));

    }

    /**
     * Логическое И: все перечисленные условия должны выполняться.
     *
     * @param conditions условия, минимум одно
     * @return составное условие в скобках
     */
    public static @NotNull Condition allOf(@NotNull Condition @NotNull ... conditions) {
        return composite("AND", conditions);
    }

    /**
     * Логическое ИЛИ: достаточно одного из перечисленных условий.
     *
     * @param conditions условия, минимум одно
     * @return составное условие в скобках
     */
    public static @NotNull Condition anyOf(@NotNull Condition @NotNull ... conditions) {
        return composite("OR", conditions);
    }

    /**
     * Отрицание условия: {@code NOT (...)}.
     *
     * @param condition условие
     * @return отрицание
     */
    public static @NotNull Condition not(@NotNull Condition condition) {

        requireNonNull(condition, "condition");

        return SimpleCondition.of(
                dialect -> "NOT (" + condition.render(dialect) + ")",
                condition.getParams());

    }

    private static @NotNull Condition comparison(@NotNull String column,
                                                 @NotNull String operator,
                                                 @NotNull Object value) {

        Identifier id = Identifier.of(column);
        requireNonNull(value, "value");

        return SimpleCondition.of(
                dialect -> id.render(dialect) + " " + operator + " ?",
                List.of(value));

    }

    private static @NotNull Condition membership(@NotNull String column,
                                                 @NotNull Collection<?> values,
                                                 boolean negate) {

        Identifier id = Identifier.of(column);
        requireNonNull(values, "values");

        if (values.isEmpty()) {
            throw QueryException.of(ErrorCode.INVALID_ARGUMENT_VALUE,
                    (negate ? "NOT IN" : "IN") + " requires at least one value; "
                            + "an empty list renders to invalid SQL and is almost always a bug",
                    Map.of("column", id.getName(), "negated", negate));
        }

        List<Object> copy = List.copyOf(values);
        StringJoiner placeholders = new StringJoiner(", ");
        copy.forEach(v -> placeholders.add("?"));

        String operator = negate ? " NOT IN (" : " IN (";

        return SimpleCondition.of(
                dialect -> id.render(dialect) + operator + placeholders + ")",
                copy);

    }

    private static @NotNull Condition composite(@NotNull String operator,
                                                @NotNull Condition @NotNull [] conditions) {

        if (conditions.length == 0) {
            throw QueryException.of(ErrorCode.INVALID_ARGUMENT_VALUE,
                    operator + " composite requires at least one condition");
        }

        if (conditions.length == 1) {
            return requireNonNull(conditions[0], "conditions[0]");
        }

        List<Condition> copy = List.of(conditions);

        return SimpleCondition.of(dialect -> {
            StringJoiner joiner = new StringJoiner(" " + operator + " ", "(", ")");

            for (Condition c : copy) {
                joiner.add(c.render(dialect));
            }

            return joiner.toString();
        }, () -> {
            List<Object> params = new ArrayList<>();

            for (Condition c : copy) {
                params.addAll(c.getParams());
            }

            return Collections.unmodifiableList(params);
        });

    }

    private static @NotNull <T> T requireNonNull(@Nullable T value, @NotNull String name) {

        if (value == null) {
            throw QueryException.of(ErrorCode.NULL_ARGUMENT,
                    "Argument '" + name + "' must not be null", Map.of("argument", name));
        }

        return value;

    }

    /**
     * Простая реализация условия: рендер и параметры заданы функциями.
     *
     * <p>Параметры вычисляются лениво и только для составных условий, где список
     * собирается из дочерних узлов; для листовых он передаётся готовым.
     */
    private static final class SimpleCondition implements Condition {

        private final java.util.function.Function<Dialect, String> renderer;
        private final java.util.function.Supplier<List<Object>> paramsSupplier;

        private SimpleCondition(java.util.function.Function<Dialect, String> renderer,
                                java.util.function.Supplier<List<Object>> paramsSupplier) {
            this.renderer = renderer;
            this.paramsSupplier = paramsSupplier;
        }

        static @NotNull SimpleCondition of(@NotNull java.util.function.Function<Dialect, String> renderer,
                                           @NotNull List<Object> params) {
            return new SimpleCondition(renderer, () -> params);
        }

        static @NotNull SimpleCondition of(@NotNull java.util.function.Function<Dialect, String> renderer,
                                           @NotNull java.util.function.Supplier<List<Object>> supplier) {
            return new SimpleCondition(renderer, supplier);
        }

        @Override
        public @NotNull String render(@NotNull Dialect dialect) {
            return renderer.apply(dialect);
        }

        @Override
        public @NotNull List<Object> getParams() {
            return paramsSupplier.get();
        }

    }

}
