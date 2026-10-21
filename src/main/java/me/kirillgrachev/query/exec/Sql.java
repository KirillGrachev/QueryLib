package me.kirillgrachev.query.exec;

import me.kirillgrachev.query.exception.ErrorCode;
import me.kirillgrachev.query.exception.QueryException;
import me.kirillgrachev.query.internal.Statements;
import me.kirillgrachev.query.sql.Dialect;
import me.kirillgrachev.query.sql.SqlStatement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Точка исполнения запросов: источник соединений плюс диалект.
 *
 * <p>Отдельный класс, а не методы в самом запросе, как было в исходной
 * библиотеке. Там {@code AbstractQuery} совмещал описание запроса с его
 * исполнением и носил около десятка методов {@code update(DataSource)},
 * {@code queryAndMap(DataSource, ...)}, {@code updateWithKeysAndMapAll(...)} —
 * по одному на комбинацию «что делаем» × «куда возвращаем». Добавление транзакций
 * или батчей умножило бы это число, а любое изменение в исполнении потребовало бы
 * правки каждого билдера.
 *
 * <p>Здесь запрос — чистые данные, исполнение — в одном классе, и транзакции
 * появляются естественно: {@link #inTransaction} передаёт в тело
 * {@link Transaction} с тем же набором методов, но работающий в одном соединении.
 *
 * <p>Диалект обязателен и не угадывается. Исходная библиотека генерировала только
 * MySQL-синтаксис и нигде этого не оговаривала, поэтому перенос кода на PostgreSQL
 * обнаруживался уже в рантайме. Здесь несовпадение невозможно: запрос без
 * диалекта не рендерится.
 *
 * <p>Экземпляр неизменяемый и потокобезопасный: его можно создать один раз на
 * приложение и переиспользовать.
 */
public final class Sql {

    private final DataSource dataSource;
    private final Dialect dialect;

    private Sql(DataSource dataSource, Dialect dialect) {

        this.dataSource = dataSource;
        this.dialect = dialect;

    }

    /**
     * Создаёт контекст исполнения.
     *
     * @param dataSource источник соединений
     * @param dialect    диалект целевой СУБД
     * @return неизменяемый контекст
     */
    public static @NotNull Sql of(@NotNull DataSource dataSource, @NotNull Dialect dialect) {

        if (dataSource == null) {
            throw QueryException.of(ErrorCode.NULL_ARGUMENT, "DataSource must not be null");
        }

        if (dialect == null) {
            throw QueryException.of(ErrorCode.NULL_ARGUMENT, "Dialect must not be null");
        }

        return new Sql(dataSource, dialect);

    }

    public @NotNull DataSource getDataSource() {

        return dataSource;

    }

    public @NotNull Dialect getDialect() {

        return dialect;

    }

    /**
     * Исполняет запрос и возвращает первую строку.
     *
     * @param st     запрос
     * @param mapper маппер строки
     * @param <T>    тип результата
     * @return значение либо пустой {@code Optional}
     */
    public <T> @NotNull Optional<T> queryOne(@NotNull SqlStatement st, @NotNull RowMapper<T> mapper) {

        return withConnection(con -> Statements.queryOne(con, st, dialect, mapper));

    }

    /**
     * Исполняет запрос и возвращает все строки.
     *
     * @param st     запрос
     * @param mapper маппер строки
     * @param <T>    тип элемента
     * @return неизменяемый список
     */
    public <T> @NotNull List<T> queryAll(@NotNull SqlStatement st, @NotNull RowMapper<T> mapper) {

        return withConnection(con -> Statements.queryAll(con, st, dialect, mapper));

    }

    /**
     * Исполняет запрос на изменение.
     *
     * @param st запрос
     * @return число затронутых строк
     */
    public int update(@NotNull SqlStatement st) {

        return withConnection(con -> Statements.update(con, st, dialect));

    }

    /**
     * Исполняет вставку и возвращает сгенерированный ключ.
     *
     * @param st     запрос вставки
     * @param mapper маппер строки ключей; обычно {@code rs -> rs.getLong(1)}
     * @param <T>    тип ключа
     * @return ключ либо пустой {@code Optional}, если СУБД его не вернула
     */
    public <T> @NotNull Optional<T> insertReturningKey(@NotNull SqlStatement st, @NotNull RowMapper<T> mapper) {

        return withConnection(con -> Statements.updateReturningKey(con, st, dialect, mapper));

    }

    /**
     * Исполняет вставку и возвращает все сгенерированные ключи.
     *
     * @param st     запрос вставки
     * @param mapper маппер строки ключей
     * @param <T>    тип ключа
     * @return неизменяемый список ключей
     */
    public <T> @NotNull List<T> insertReturningKeys(@NotNull SqlStatement st, @NotNull RowMapper<T> mapper) {

        return withConnection(con -> Statements.updateReturningKeys(con, st, dialect, mapper));

    }

    /**
     * Выполняет тело в одной транзакции.
     *
     * <p>Соединение берётся один раз и передаётся в {@link Transaction}; все
     * запросы тела исполняются в нём. При любом исключении выполняется откат, при
     * успешном завершении — фиксация. Исходное состояние autoCommit
     * восстанавливается в {@code finally}, поэтому соединение можно безопасно
     * возвращать в пул.
     *
     * <p>Откат, который сам завершился ошибкой, не маскирует исходное исключение:
     * он добавляется как suppressed. В исходной библиотеке транзакций не было
     * вовсе, и этот сценарий каждая вызывающая сторона реализовывала сама —
     * обычно без suppressed, теряя причину сбоя.
     *
     * @param body тело транзакции
     * @param <T>  тип результата
     * @return значение, возвращённое телом
     */
    public <T> T inTransaction(@NotNull TransactionBody<T> body) {

        if (body == null) {
            throw QueryException.of(ErrorCode.NULL_ARGUMENT, "Transaction body must not be null");
        }

        Connection con = openConnection();
        boolean originalAutoCommit;

        try {
            originalAutoCommit = con.getAutoCommit();
            con.setAutoCommit(false);

        } catch (SQLException e) {
            closeQuietly(con, e);
            throw QueryException.of(ErrorCode.TRANSACTION_FAILED,
                    "Failed to begin transaction: " + e.getMessage(),
                    Map.of("phase", "begin"), e);
        }

        // Исключение сохраняется в переменную, чтобы блок finally мог прикрепить
        // к нему suppressed-ошибки восстановления соединения. Добавить suppressed
        // «в текущее исключение» из finally нельзя — там оно уже недоступно.
        Throwable failure = null;

        try {
            T result = body.run(new Transaction(con, dialect));

            con.commit();

            return result;

        } catch (SQLException | RuntimeException e) {
            failure = e;
            rollback(con, e);

            if (e instanceof SQLException sqlException) {
                throw QueryException.of(ErrorCode.TRANSACTION_FAILED,
                        "Transaction body failed: " + sqlException.getMessage(),
                        Map.of("phase", "body"), sqlException);
            }

            throw (RuntimeException) e;

        } finally {
            try {
                // Восстановление autoCommit обязательно: соединение возвращается
                // в пул, и следующий, кто его возьмёт, не должен унаследовать
                // выключенный autoCommit от чужой транзакции.
                con.setAutoCommit(originalAutoCommit);

            } catch (SQLException e) {
                if (failure != null) {
                    failure.addSuppressed(e);
                }
                // Иначе ошибка проглатывается намеренно: соединение закрывается
                // следующей строкой, а пул при возврате проверит его сам.
            }

            closeQuietly(con, failure);
        }

    }

    /**
     * Выполняет тело в транзакции без возвращаемого значения.
     *
     * <p>Имя отличается от {@link #inTransaction(TransactionBody)}, а не является
     * его перегрузкой: две перегрузки с функциональными интерфейсами одной арности
     * неоднозначны для неявно типизированной лямбды. Компилятор выбирает между
     * void- и value-совместимой формой по телу лямбды, а блок, который всегда
     * бросает исключение, совместим с обеими, — вызов не компилируется с сообщением
     * «reference to inTransaction is ambiguous», которое ничего не говорит о причине.
     *
     * @param body тело транзакции
     */
    public void inTransactionVoid(@NotNull TransactionVoid body) {

        // Проверка нужна именно здесь: передача null дальше обернулась бы лямбдой,
        // и NPE возник бы в момент исполнения тела транзакции — уже после открытия
        // соединения и выключения autoCommit, то есть в худшем возможном месте.
        if (body == null) {
            throw QueryException.of(ErrorCode.NULL_ARGUMENT, "Transaction body must not be null");
        }

        inTransaction(tx -> {
            body.run(tx);

            return null;
        });

    }

    /**
     * Создаёт исполнитель пакетных операций для этого контекста.
     *
     * @return новый {@link BatchExecutor}
     */
    public @NotNull BatchExecutor batch() {

        return new BatchExecutor(dataSource, dialect);

    }

    /**
     * Даёт доступ к соединению для операций, не покрытых API.
     *
     * <p>Соединение закрывается автоматически по возвращении из функции. Это
     * escape hatch: если он нужен часто, значит в API чего-то не хватает, и
     * правильнее добавить метод, чем размножать прямой доступ к JDBC.
     *
     * @param function тело, получающее открытое соединение
     * @param <T>      тип результата
     * @return значение, возвращённое телом
     */
    public <T> T withConnection(@NotNull ConnectionFunction<T> function) {

        if (function == null) {
            throw QueryException.of(ErrorCode.NULL_ARGUMENT, "Connection function must not be null");
        }

        try (Connection con = openConnection()) {
            return function.apply(con);

        } catch (SQLException e) {
            throw QueryException.of(ErrorCode.SQL_EXECUTION_FAILED,
                    "Failed to execute against connection: " + e.getMessage(),
                    Map.of(), e);
        }

    }

    private @NotNull Connection openConnection() {

        Connection con;

        try {
            con = dataSource.getConnection();

        } catch (SQLException e) {
            throw QueryException.of(ErrorCode.CONNECTION_UNAVAILABLE,
                    "DataSource failed to provide a connection: " + e.getMessage(), Map.of(), e);
        }

        // DataSource может вернуть null (некорректная реализация или исчерпанный
        // пул с неудачной обработкой); без проверки NPE возник бы позже и глубже.
        if (con == null) {
            throw QueryException.of(ErrorCode.CONNECTION_UNAVAILABLE,
                    "DataSource returned a null connection",
                    Map.of("dataSource", dataSource.getClass().getName()));
        }

        return con;

    }

    private void rollback(@NotNull Connection con, @NotNull Throwable original) {

        try {
            con.rollback();

        } catch (SQLException rollbackFailure) {
            // Откат прикрепляется к исходной ошибке, а не заменяет её: причина
            // сбоя важнее того, что не удалось отменить его последствия.
            original.addSuppressed(rollbackFailure);
        }

    }

    private void closeQuietly(@NotNull Connection con, @Nullable Throwable original) {

        try {
            con.close();

        } catch (SQLException e) {
            if (original != null) {
                original.addSuppressed(e);
            }

        }

    }

    /** Тело транзакции с возвращаемым значением. */
    @FunctionalInterface
    public interface TransactionBody<T> {

        T run(@NotNull Transaction tx) throws SQLException;

    }

    /** Тело транзакции без возвращаемого значения. */
    @FunctionalInterface
    public interface TransactionVoid {

        void run(@NotNull Transaction tx) throws SQLException;

    }

    /** Функция, получающая открытое соединение. */
    @FunctionalInterface
    public interface ConnectionFunction<T> {

        T apply(@NotNull Connection con) throws SQLException;

    }

}
