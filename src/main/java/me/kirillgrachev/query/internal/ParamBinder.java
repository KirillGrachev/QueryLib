package me.kirillgrachev.query.internal;

import lombok.experimental.UtilityClass;
import me.kirillgrachev.query.exception.ErrorCode;
import me.kirillgrachev.query.exception.QueryException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Привязка значений к плейсхолдерам {@code PreparedStatement}.
 *
 * <p>Исходная библиотека вызывала {@code setObject(i, value)} для всех значений
 * без исключения. Это работает не везде:
 * <ul>
 *   <li>{@code setObject(i, null)} часть драйверов отвергает, поскольку не может
 *       определить SQL-тип; корректный способ — {@code setNull(i, Types.NULL)};</li>
 *   <li>enum не является JDBC-типом, и драйвер либо бросает исключение, либо
 *       вызывает {@code toString()}, что расходится с ожидаемым {@code name()};</li>
 *   <li>{@code java.util.Date} и {@code Calendar} не входят в JDBC 4.2, для них
 *       нужен {@code setTimestamp};</li>
 *   <li>{@code Optional} передавать бессмысленно: это обёртка, а не значение;</li>
 *   <li>{@code UUID} и {@code Character} надёжнее передавать строкой.</li>
 * </ul>
 *
 * <p>Ключевое отличие — качество диагностики. Ошибка привязки оборачивается с
 * контекстом: индекс параметра, его Java-тип и текст запроса. Без этого
 * {@code SQLException} от драйвера сообщает «неверный тип», не говоря, о каком из
 * двадцати параметров речь, и разбор превращается в бинарный поиск по коду.
 */
@UtilityClass
public class ParamBinder {

    /**
     * Привязывает все параметры к плейсхолдерам statement'а.
     *
     * @param statement подготовленный statement
     * @param sql       текст запроса, используется только для диагностики
     * @param params    значения в порядке следования плейсхолдеров
     * @throws QueryException если драйвер отверг значение
     */
    public void bind(@NotNull PreparedStatement statement,
                     @NotNull String sql,
                     @NotNull List<Object> params) {
        for (int i = 0; i < params.size(); i++) {
            bindOne(statement, sql, i + 1, params.get(i));
        }
    }

    private void bindOne(@NotNull PreparedStatement statement,
                         @NotNull String sql,
                         int index,
                         @Nullable Object value) {

        try {
            bindValue(statement, index, value);
        } catch (SQLException e) {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("index", index);
            context.put("javaType", value == null ? "null" : value.getClass().getName());
            context.put("sql", truncate(sql));

            throw QueryException.of(ErrorCode.PARAMETER_BINDING_FAILED,
                    "Failed to bind parameter #" + index + " of type "
                            + (value == null ? "null" : value.getClass().getSimpleName())
                            + ": " + e.getMessage(),
                    context,
                    e);
        }

    }

    /**
     * Привязывает одно значение с учётом его Java-типа.
     *
     * <p>Порядок проверок имеет значение: {@code null} проверяется первым, потому
     * что для него тип неизвестен в принципе, а {@code Optional} распаковывается
     * до определения типа содержимого.
     */
    private void bindValue(@NotNull PreparedStatement statement,
                           int index,
                           @Nullable Object value) throws SQLException {

        if (value == null) {
            // Types.NULL, а не setObject(index, null): последнее не позволяет
            // драйверу определить SQL-тип и отвергается рядом драйверов.
            statement.setNull(index, Types.NULL);
            return;
        }

        if (value instanceof Optional<?> optional) {
            bindValue(statement, index, optional.orElse(null));
            return;
        }

        if (value instanceof Enum<?> enumValue) {
            // name(), а не toString(): toString может быть переопределён для
            // человекочитаемого вывода, и в базу уехало бы не то, что хранится.
            statement.setString(index, enumValue.name());
            return;
        }

        if (value instanceof String s) {
            statement.setString(index, s);
            return;
        }

        if (value instanceof Integer i) {
            statement.setInt(index, i);
            return;
        }

        if (value instanceof Long l) {
            statement.setLong(index, l);
            return;
        }

        if (value instanceof Boolean b) {
            statement.setBoolean(index, b);
            return;
        }

        if (value instanceof Double d) {
            statement.setDouble(index, d);
            return;
        }

        if (value instanceof Float f) {
            statement.setFloat(index, f);
            return;
        }

        if (value instanceof Short s) {
            statement.setShort(index, s);
            return;
        }

        if (value instanceof Byte b) {
            statement.setByte(index, b);
            return;
        }

        if (value instanceof java.math.BigDecimal decimal) {
            statement.setBigDecimal(index, decimal);
            return;
        }

        if (value instanceof byte[] bytes) {
            statement.setBytes(index, bytes);
            return;
        }

        if (value instanceof UUID uuid) {
            statement.setString(index, uuid.toString());
            return;
        }

        if (value instanceof Character c) {
            statement.setString(index, String.valueOf(c));
            return;
        }

        if (value instanceof java.time.temporal.Temporal temporal) {
            // JDBC 4.2 поддерживает java.time напрямую, и setObject здесь —
            // корректный путь: самостоятельное преобразование в Timestamp
            // потеряло бы зону и точность для OffsetDateTime и Instant.
            statement.setObject(index, temporal);
            return;
        }

        // Собственные типы JDBC проверяются ДО java.util.Date: java.sql.Date,
        // java.sql.Time и Timestamp являются его наследниками, и при обратном
        // порядке Timestamp попадал в ветку ниже, где пересоздавался через
        // new Timestamp(date.getTime()). getTime() возвращает миллисекунды,
        // поэтому наносекундная часть значения молча терялась — ошибка, которую
        // невозможно заметить на данных с целыми секундами.
        if (value instanceof java.sql.Date || value instanceof java.sql.Time
                || value instanceof Timestamp) {
            statement.setObject(index, value);
            return;
        }

        if (value instanceof java.util.Date date) {
            statement.setTimestamp(index, new Timestamp(date.getTime()));
            return;
        }

        if (value instanceof Calendar calendar) {
            statement.setTimestamp(index, new Timestamp(calendar.getTimeInMillis()));
            return;
        }

        if (value instanceof Number) {
            statement.setObject(index, value);
            return;
        }

        // Неизвестный тип: отдаём драйверу как есть. Если он его не примет,
        // исключение будет обёрнуто выше с индексом и именем типа.
        statement.setObject(index, value);

    }

    /**
     * Обрезает SQL для контекста исключения.
     *
     * <p>Ограничение нужно потому, что контекст попадает в лог целиком, а запрос
     * с десятками плейсхолдеров может быть длиннее самого сообщения об ошибке и
     * вытеснить из лога всё остальное.
     */
    private @NotNull String truncate(@NotNull String sql) {
        int max = 512;
        return sql.length() <= max ? sql : sql.substring(0, max) + "... (" + sql.length() + " chars)";
    }

}
