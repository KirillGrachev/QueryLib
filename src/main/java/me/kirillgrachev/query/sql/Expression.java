package me.kirillgrachev.query.sql;

import me.kirillgrachev.query.internal.Placeholders;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Фрагмент SQL с плейсхолдерами и уже привязанными к ним параметрами.
 *
 * <p>Класс package-private намеренно. В исходной библиотеке его аналог был
 * публичным с публичными полями {@code expr} и {@code params}, а javadoc при этом
 * сообщал «Internal class, do not use directly». Такой контракт не обеспечивается
 * ничем: любой пользователь библиотеки мог прочитать массив параметров и изменить
 * его содержимое, поломав уже собранный запрос. Здесь доступность ограничена
 * языком, а не комментарием.
 *
 * <p>Неизменяемость достигается защитным копированием: конструктор принимает
 * varargs-массив и копирует его, поэтому последующая мутация массива вызывающей
 * стороной не влияет на запрос. Это закрывает дефект, при котором
 * {@code whereExpr(expr, params)} сохранял ссылку на массив клиента.
 *
 * <p>Число плейсхолдеров проверяется в момент создания, а не в момент рендера, и
 * проверка учитывает строковые литералы и комментарии: валидный SQL вида
 * {@code name = 'a?b'} не требует параметра.
 */
final class Expression {

    /** Текст фрагмента с плейсхолдерами {@code ?}. */
    private final String sql;

    /** Параметры фрагмента, неизменяемый список. */
    private final List<Object> params;

    private Expression(String sql, List<Object> params) {

        this.sql = sql;
        this.params = params;

    }

    /**
     * Создаёт фрагмент, проверяя соответствие плейсхолдеров и параметров.
     *
     * @param sql    текст фрагмента
     * @param params параметры; могут содержать {@code null}
     * @return неизменяемый фрагмент
     * @throws me.kirillgrachev.query.exception.QueryException если число плейсхолдеров не совпало
     */
    static @NotNull Expression of(@NotNull String sql, @Nullable Object @NotNull ... params) {

        Placeholders.checkCount(sql, params);

        return new Expression(sql, copyOf(params));

    }

    /**
     * Создаёт фрагмент без параметров, минуя проверку плейсхолдеров.
     *
     * <p>Используется для идентификаторов и литеральных клауз, где {@code ?}
     * заведомо нет. Проверка всё равно выполняется: фрагмент вроде
     * {@code `col`} её проходит тривиально, а ошибка здесь означала бы, что в
     * идентификатор попал вопросительный знак.
     *
     * @param sql текст фрагмента без плейсхолдеров
     * @return неизменяемый фрагмент
     */
    static @NotNull Expression raw(@NotNull String sql) {

        Placeholders.checkCount(sql, new Object[0]);

        return new Expression(sql, List.of());

    }

    /**
     * Копирует varargs-массив в неизменяемый список.
     *
     * <p>Нельзя использовать {@code List.of(params)}: фабрика не принимает
     * {@code null}-элементы, а SQL NULL — легальное значение параметра.
     *
     * @param params исходный массив
     * @return неизменяемый список-копия
     */
    private static @NotNull List<Object> copyOf(@Nullable Object @NotNull [] params) {

        List<Object> copy = new ArrayList<>(params.length);
        Collections.addAll(copy, params);

        return Collections.unmodifiableList(copy);

    }

    @NotNull String getSql() {

        return sql;

    }

    @NotNull List<Object> getParams() {

        return params;

    }

    boolean hasParams() {

        return !params.isEmpty();

    }

}
