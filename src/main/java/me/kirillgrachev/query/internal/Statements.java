package me.kirillgrachev.query.internal;

import lombok.experimental.UtilityClass;
import me.kirillgrachev.query.exception.ErrorCode;
import me.kirillgrachev.query.exception.QueryException;
import me.kirillgrachev.query.exec.RowMapper;
import me.kirillgrachev.query.sql.Dialect;
import me.kirillgrachev.query.sql.SqlStatement;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Примитивы исполнения запросов на готовом {@link Connection}.
 *
 * <p>Единственное место в библиотеке, где открываются {@link PreparedStatement} и
 * {@link ResultSet}. Все три ресурса — соединение, statement, result set —
 * закрываются через try-with-resources на КАЖДОМ пути выхода, включая путь через
 * исключение. Это структурное решение, а не дисциплина: в исходной библиотеке
 * закрытие зависело от того, вспомнил ли автор метода про него, и три метода из
 * шести его не вспомнили.
 *
 * <p>Конкретные дефекты исходной реализации, которые здесь невозможны:
 * <ul>
 *   <li>{@code query(Connection)} терял {@code PreparedStatement}, если
 *       {@code executeQuery()} бросал исключение: statement создавался вне
 *       try-with-resources и передавался в обёртку уже после исполнения;</li>
 *   <li>{@code updateWithKeys(Connection)} терял statement на обоих вызовах —
 *       и при {@code executeUpdate()}, и при {@code getGeneratedKeys()};</li>
 *   <li>{@code ResultSetWrapper.map()/mapAll()} закрывали только
 *       {@code ResultSet}, хотя javadoc обещал закрыть и родительский statement.
 *       На пуле соединений утечка маскировалась закрытием {@code Connection}, но
 *       на долгоживущем соединении курсоры накапливались до
 *       {@code max_prepared_stmt_count}, и проявлялось это через часы работы.</li>
 * </ul>
 *
 * <p>Наружу не отдаётся ни один ресурс, требующий закрытия: обработчики получают
 * {@code ResultSet} внутри области действия try-with-resources и обязаны
 * прочитать данные до возврата. Поэтому у вызывающей стороны физически нет
 * способа забыть закрыть statement.
 */
@UtilityClass
public class Statements {

    /**
     * Обрабатывает результат запроса, гарантированно закрывая ресурсы.
     *
     * @param handler получает {@code ResultSet} с курсором ДО первой строки
     * @param <T>     тип результата
     */
    @FunctionalInterface
    public interface ResultSetHandler<T> {
        T handle(@NotNull ResultSet rs) throws SQLException;
    }

    /**
     * Исполняет запрос и передаёт result set обработчику.
     *
     * @param con     соединение, остаётся открытым — закрывает вызывающая сторона
     * @param st      запрос
     * @param dialect диалект для рендера SQL
     * @param handler обработчик результата
     * @param <T>     тип результата
     * @return значение, возвращённое обработчиком
     * @throws QueryException при ошибке JDBC
     */
    public <T> T query(@NotNull Connection con,
                       @NotNull SqlStatement st,
                       @NotNull Dialect dialect,
                       @NotNull ResultSetHandler<T> handler) {

        String sql = st.getSql(dialect);

        try (PreparedStatement ps = con.prepareStatement(sql); ) {
            ParamBinder.bind(ps, sql, st.getParams());

            try (ResultSet rs = ps.executeQuery()) {
                return handler.handle(rs);
            }

        } catch (SQLException e) {
            throw wrap(ErrorCode.SQL_EXECUTION_FAILED, "Query execution failed", sql, st, e);
        }

    }

    /**
     * Исполняет запрос и читает первую строку.
     *
     * @param con     соединение
     * @param st      запрос
     * @param dialect диалект
     * @param mapper  маппер строки
     * @param <T>     тип результата
     * @return значение либо пустой {@code Optional}, если строк нет или маппер вернул {@code null}
     */
    public <T> @NotNull Optional<T> queryOne(@NotNull Connection con,
                                             @NotNull SqlStatement st,
                                             @NotNull Dialect dialect,
                                             @NotNull RowMapper<T> mapper) {
        return query(con, st, dialect, rs -> rs.next() ? Optional.ofNullable(mapper.map(rs)) : Optional.<T>empty());
    }

    /**
     * Исполняет запрос и читает все строки.
     *
     * <p>Результат материализуется в список: потоковое чтение через открытый
     * {@code ResultSet} нарушило бы гарантию закрытия ресурсов, ради которой
     * этот класс существует. Для действительно больших выборок используйте
     * {@code LIMIT} и пагинацию либо {@link #query} с собственным обработчиком
     * внутри транзакции.
     *
     * @param con     соединение
     * @param st      запрос
     * @param dialect диалект
     * @param mapper  маппер строки
     * @param <T>     тип элемента
     * @return неизменяемый список значений
     */
    public <T> @NotNull List<T> queryAll(@NotNull Connection con,
                                         @NotNull SqlStatement st,
                                         @NotNull Dialect dialect,
                                         @NotNull RowMapper<T> mapper) {

        return query(con, st, dialect, rs -> {
            List<T> result = new ArrayList<>();

            while (rs.next()) {
                result.add(mapper.map(rs));
            }

            return List.copyOf(result);
        });

    }

    /**
     * Исполняет запрос на изменение и возвращает число затронутых строк.
     *
     * @param con     соединение
     * @param st      запрос
     * @param dialect диалект
     * @return результат {@code executeUpdate}
     */
    public int update(@NotNull Connection con,
                      @NotNull SqlStatement st,
                      @NotNull Dialect dialect) {

        String sql = st.getSql(dialect);

        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ParamBinder.bind(ps, sql, st.getParams());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw wrap(ErrorCode.SQL_EXECUTION_FAILED, "Update execution failed", sql, st, e);
        }

    }

    /**
     * Исполняет запрос на изменение и читает сгенерированные ключи.
     *
     * @param con     соединение
     * @param st      запрос
     * @param dialect диалект
     * @param mapper  маппер строки ключей
     * @param <T>     тип результата
     * @return первое значение ключа либо пустой {@code Optional}
     */
    public <T> @NotNull Optional<T> updateReturningKey(@NotNull Connection con,
                                                       @NotNull SqlStatement st,
                                                       @NotNull Dialect dialect,
                                                       @NotNull RowMapper<T> mapper) {

        String sql = st.getSql(dialect);

        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ParamBinder.bind(ps, sql, st.getParams());
            ps.executeUpdate();

            // getGeneratedKeys() внутри того же try-with-resources: в исходной
            // библиотеке исключение на этом вызове оставляло statement открытым.
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? Optional.ofNullable(mapper.map(rs)) : Optional.<T>empty();
            }

        } catch (SQLException e) {
            throw wrap(ErrorCode.SQL_EXECUTION_FAILED, "Update with generated keys failed", sql, st, e);
        }

    }

    /**
     * Исполняет запрос на изменение и читает все сгенерированные ключи.
     *
     * @param con     соединение
     * @param st      запрос
     * @param dialect диалект
     * @param mapper  маппер строки ключей
     * @param <T>     тип элемента
     * @return неизменяемый список ключей
     */
    public <T> @NotNull List<T> updateReturningKeys(@NotNull Connection con,
                                                    @NotNull SqlStatement st,
                                                    @NotNull Dialect dialect,
                                                    @NotNull RowMapper<T> mapper) {

        String sql = st.getSql(dialect);

        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ParamBinder.bind(ps, sql, st.getParams());
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                List<T> result = new ArrayList<>();

                while (rs.next()) {
                    result.add(mapper.map(rs));
                }

                return List.copyOf(result);
            }

        } catch (SQLException e) {
            throw wrap(ErrorCode.SQL_EXECUTION_FAILED, "Update with generated keys failed", sql, st, e);
        }

    }

    /**
     * Оборачивает {@link SQLException} с контекстом запроса.
     *
     * <p>В контекст попадает SQL-код запроса и состояние его SQL: без этого
     * сообщение драйвера («You have an error in your SQL syntax») невозможно
     * связать с конкретным местом в коде, а именно это и требуется при разборе.
     */
    private @NotNull QueryException wrap(@NotNull ErrorCode code,
                                         @NotNull String message,
                                         @NotNull String sql,
                                         @NotNull SqlStatement st,
                                         @NotNull SQLException cause) {

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("sql", sql);
        context.put("statement", st.getClass().getSimpleName());
        context.put("sqlState", String.valueOf(cause.getSQLState()));
        context.put("vendorCode", cause.getErrorCode());

        return QueryException.of(code, message + ": " + cause.getMessage(), context, cause);

    }

}
