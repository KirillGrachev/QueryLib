package me.kirillgrachev.query.sql;

import me.kirillgrachev.query.internal.Placeholders;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Запрос из произвольного SQL-текста.
 *
 * <p>Нужен для сложных запросов, которые неудобно собирать билдером: оконные
 * функции, CTE, специфичные для СУБД конструкции. Библиотека не пытается покрыть
 * весь SQL, и честный escape hatch лучше, чем неполный билдер, вынуждающий
 * пользователя обходить библиотеку целиком.
 *
 * <p>В отличие от исходной реализации список параметров копируется и становится
 * неизменяемым: раньше {@code Raw} хранил переданный {@code List} по ссылке,
 * поэтому вызывающая сторона могла изменить параметры уже построенного запроса.
 */
public final class RawSql implements SqlStatement {

    private final String sql;
    private final List<Object> params;

    /**
     * Конструктор публичный, хотя предполагается создание через
     * {@link me.kirillgrachev.query.Query}: фабрика живёт в соседнем пакете, а
     * package-private конструктор был бы ей недоступен. Прямое создание не
     * запрещается — оно эквивалентно фабрике, но лишает читателя единой точки
     * входа, поэтому в документации рекомендуется {@code Query.*}.
     */
    public RawSql(@NotNull String sql, @Nullable Object @NotNull ... params) {

        Placeholders.checkCount(sql, params);

        this.sql = sql;

        List<Object> copy = new ArrayList<>(params.length);
        Collections.addAll(copy, params);
        this.params = Collections.unmodifiableList(copy);

    }

    /**
     * Возвращает SQL как есть.
     *
     * <p>Диалект игнорируется: текст считается уже написанным для целевой СУБД.
     * Это сознательное ограничение — пытаться переписывать произвольный SQL под
     * другой диалект означало бы строить полноценный транспайлер.
     *
     * @param dialect игнорируется
     * @return исходный текст запроса
     */
    @Override
    public @NotNull String getSql(@NotNull Dialect dialect) {
        return sql;
    }

    @Override
    public @NotNull List<Object> getParams() {
        return params;
    }

    @Override
    public String toString() {
        return "RawSql[" + sql + ", params=" + params.size() + "]";
    }

}
