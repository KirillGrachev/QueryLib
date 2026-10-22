package me.kirillgrachev.query.internal;

import me.kirillgrachev.query.exception.ErrorCode;
import me.kirillgrachev.query.exception.QueryException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты {@link ParamBinder}: какой именно setter JDBC вызывается для каждого типа.
 *
 * <p>Проверяется через записывающий прокси, а не через базу: цель — диспетчеризация
 * типов, и наблюдение за вызванными методами даёт точный ответ, тогда как чтение
 * значения из БД смешало бы ошибку привязки с приведением типов самой СУБД.
 *
 * <p>Исходная библиотека вызывала {@code setObject(i, value)} для всех значений,
 * из-за чего {@code null} отвергался частью драйверов, enum привязывался через
 * {@code toString()} вместо {@code name()}, а {@code java.util.Date} и
 * {@code Optional} не обрабатывались вовсе.
 */
class ParamBinderTest {

    private enum Color {
        RED, GREEN
    }

    /** Записывает вызовы setter'ов вместо обращения к драйверу. */
    private record Call(String method, Object[] args) {

        String describe() {

            StringBuilder sb = new StringBuilder(method).append('(');

            for (int i = 0; i < args.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }

                sb.append(args[i] instanceof String ? "\"" + args[i] + "\"" : String.valueOf(args[i]));
            }

            return sb.append(')').toString();

        }

    }

    private final List<Call> calls = new ArrayList<>();

    private PreparedStatement recorder() {

        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().startsWith("set")) {
                calls.add(new Call(method.getName(), args == null ? new Object[0] : args));
            }

            Class<?> type = method.getReturnType();

            if (type == boolean.class) {
                return false;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }

            return null;
        };

        return (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, handler);

    }

    private String bind(Object... params) {

        List<Object> list = new ArrayList<>();

        for (Object p : params) {
            list.add(p);
        }

        calls.clear();
        ParamBinder.bind(recorder(), "SELECT ?", list);

        return calls.isEmpty() ? "" : calls.getFirst().describe();

    }

    @Test
    void nullIsBoundThroughSetNull() {
        // setObject(i, null) не позволяет драйверу определить SQL-тип и частью
        // драйверов отвергается; корректный путь — setNull с Types.NULL (0).
        assertEquals("setNull(1, 0)", bind((Object) null));
    }

    @Test
    void emptyOptionalIsBoundAsNull() {
        assertEquals("setNull(1, 0)", bind(Optional.empty()));
    }

    @Test
    void optionalIsUnwrappedToItsValue() {
        assertEquals("setString(1, \"x\")", bind(Optional.of("x")));
        assertEquals("setInt(1, 7)", bind(Optional.of(7)));
    }

    @Test
    void nestedOptionalIsUnwrapped() {
        assertEquals("setString(1, \"deep\")", bind(Optional.of(Optional.of("deep"))));
    }

    @Test
    void enumIsBoundByNameNotByToString() {
        assertEquals("setString(1, \"RED\")", bind(Color.RED));
    }

    @Test
    void primitiveWrappersUseTypedSetters() {

        assertEquals("setString(1, \"text\")", bind("text"));
        assertEquals("setInt(1, 42)", bind(42));
        assertEquals("setLong(1, 42)", bind(42L));
        assertEquals("setBoolean(1, true)", bind(true));
        assertEquals("setDouble(1, 1.5)", bind(1.5d));
        assertEquals("setFloat(1, 1.5)", bind(1.5f));
        assertEquals("setShort(1, 3)", bind((short) 3));
        assertEquals("setByte(1, 2)", bind((byte) 2));

    }

    @Test
    void bigDecimalUsesSetBigDecimal() {
        assertEquals("setBigDecimal(1, 12.34)", bind(new BigDecimal("12.34")));
    }

    @Test
    void byteArrayUsesSetBytes() {
        String call = bind(new byte[]{1, 2, 3});
        assertTrue(call.startsWith("setBytes(1, [B@"), "ожидался setBytes, получено: " + call);
    }

    @Test
    void uuidIsBoundAsString() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertEquals("setString(1, \"00000000-0000-0000-0000-000000000001\")", bind(id));
    }

    @Test
    void characterIsBoundAsString() {
        assertEquals("setString(1, \"a\")", bind('a'));
    }

    @Test
    void javaTimeTypesGoThroughSetObject() {

        // JDBC 4.2 поддерживает java.time напрямую. Самостоятельное преобразование
        // в Timestamp потеряло бы зону у OffsetDateTime и точность у Instant.
        assertTrue(bind(LocalDate.of(2026, 10, 21)).startsWith("setObject(1, 2026-10-21"));
        assertTrue(bind(LocalDateTime.of(2026, 10, 21, 12, 0)).startsWith("setObject(1, 2026-10-21T12:00"));
        assertTrue(bind(Instant.EPOCH).startsWith("setObject(1, "));
        assertTrue(bind(OffsetDateTime.now()).startsWith("setObject(1, "));

    }

    @Test
    void legacyDateTypesAreConvertedToTimestamp() {

        java.util.Date legacy = new java.util.Date(1_700_000_000_000L);

        assertEquals("setTimestamp(1, " + new Timestamp(1_700_000_000_000L) + ")", bind(legacy));

        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(1_700_000_000_000L);

        assertEquals("setTimestamp(1, " + new Timestamp(1_700_000_000_000L) + ")", bind(calendar));

    }

    @Test
    void sqlDateAndTimeGoThroughSetObject() {

        assertTrue(bind(java.sql.Date.valueOf("2026-10-21")).startsWith("setObject(1, 2026-10-21"));
        assertTrue(bind(java.sql.Time.valueOf("12:30:00")).startsWith("setObject(1, 12:30:00"));
        assertTrue(bind(new Timestamp(0L)).startsWith("setObject(1, "));

    }

    @Test
    void unknownTypeFallsBackToSetObject() {

        Object custom = new Object() {
            @Override
            public String toString() {
                return "custom-value";
            }
        };

        assertEquals("setObject(1, custom-value)", bind(custom));

    }

    @Test
    void parametersAreBoundInOrderWithOneBasedIndexes() {

        List<Object> params = List.of("a", 1, true);

        calls.clear();
        ParamBinder.bind(recorder(), "SELECT ?, ?, ?", params);

        assertEquals(3, calls.size());
        assertEquals("setString(1, \"a\")", calls.get(0).describe());
        assertEquals("setInt(2, 1)", calls.get(1).describe());
        assertEquals("setBoolean(3, true)", calls.get(2).describe());

    }

    @Test
    void driverFailureIsWrappedWithIndexAndType() {

        // Драйвер, отвергающий значение, должен давать сообщение, по которому
        // понятно, КАКОЙ параметр и КАКОГО типа не принят: в запросе с двадцатью
        // параметрами иначе начинается бинарный поиск по коду.
        InvocationHandler failing = (proxy, method, args) -> {
            if (method.getName().equals("setString")) {
                throw new SQLException("driver rejects strings");
            }

            return null;
        };

        PreparedStatement ps = (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, failing);

        QueryException e = assertThrows(QueryException.class,
                () -> ParamBinder.bind(ps, "SELECT ? FROM t", List.of("first", "second")));

        assertEquals(ErrorCode.PARAMETER_BINDING_FAILED, e.getCode());
        assertEquals(1, (int) e.getContextValue("index", Integer.class));
        assertEquals("java.lang.String", e.getContextValue("javaType", String.class));
        assertTrue(e.getMessage().contains("parameter #1"), e.getMessage());

    }

    @Test
    void longSqlIsTruncatedInErrorContext() {

        InvocationHandler failing = (proxy, method, args) -> {
            if (method.getName().startsWith("set")) {
                throw new SQLException("nope");
            }

            return null;
        };

        PreparedStatement ps = (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, failing);

        String longSql = "SELECT " + "x".repeat(2000) + " FROM t WHERE a = ?";

        QueryException e = assertThrows(QueryException.class,
                () -> ParamBinder.bind(ps, longSql, List.of(1)));

        String contextSql = String.valueOf(e.getContext().get("sql"));

        assertTrue(contextSql.length() < longSql.length(), "SQL в контексте должен быть обрезан");
        assertTrue(contextSql.contains("chars)"), "обрезка должна быть помечена: " + contextSql);

    }

}
