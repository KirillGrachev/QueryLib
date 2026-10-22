package me.kirillgrachev.query.exec;

import me.kirillgrachev.query.internal.Statements;
import me.kirillgrachev.query.sql.Dialect;
import me.kirillgrachev.query.sql.SqlStatement;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;

/**
 * Область транзакции: те же операции, но в одном соединении.
 *
 * <p>Создаётся только внутри {@link Sql#inTransaction} и не должен покидать его
 * пределы: соединение закроется, когда тело транзакции завершится, и любой
 * сохранённый экземпляр превратится в ссылку на закрытый ресурс. Это осознанное
 * ограничение времени жизни вместо защитного копирования — транзакция, которую
 * можно унести наружу, перестаёт быть транзакцией.
 *
 * <p>Методы намеренно повторяют API {@link Sql}, чтобы перенос кода из
 * нетранзакционного контекста в транзакционный сводился к замене переменной, а не
 * к переписыванию вызовов.
 */
public final class Transaction {

    private final Connection connection;
    private final Dialect dialect;

    Transaction(@NotNull Connection connection, @NotNull Dialect dialect) {
        this.connection = connection;
        this.dialect = dialect;
    }

    /**
     * Возвращает соединение транзакции.
     *
     * <p>Нужно для операций, не покрытых API библиотеки. Соединение принадлежит
     * транзакции: закрывать его нельзя, иначе оставшаяся часть тела будет
     * исполняться против закрытого ресурса.
     *
     * @return открытое соединение транзакции
     */
    public @NotNull Connection getConnection() {
        return connection;
    }

    public @NotNull Dialect getDialect() {
        return dialect;
    }

    public <T> @NotNull Optional<T> queryOne(@NotNull SqlStatement st, @NotNull RowMapper<T> mapper) {
        return Statements.queryOne(connection, st, dialect, mapper);
    }

    public <T> @NotNull List<T> queryAll(@NotNull SqlStatement st, @NotNull RowMapper<T> mapper) {
        return Statements.queryAll(connection, st, dialect, mapper);
    }

    public int update(@NotNull SqlStatement st) {
        return Statements.update(connection, st, dialect);
    }

    public <T> @NotNull Optional<T> insertReturningKey(@NotNull SqlStatement st, @NotNull RowMapper<T> mapper) {
        return Statements.updateReturningKey(connection, st, dialect, mapper);
    }

    /**
     * Исполняет запрос с блокировкой строк внутри этой транзакции.
     *
     * <p>Отдельный метод не нужен: {@code SELECT ... FOR UPDATE} строится через
     * {@link me.kirillgrachev.query.sql.Select#forUpdate()}. Метод оставлен как
     * напоминание о том, что {@code FOR UPDATE} вне транзакции бесполезен —
     * блокировка снимается сразу после чтения.
     *
     * @param st     запрос с {@code FOR UPDATE}
     * @param mapper маппер строки
     * @param <T>    тип элемента
     * @return строки, заблокированные до конца транзакции
     */
    public <T> @NotNull List<T> selectForUpdate(@NotNull SqlStatement st, @NotNull RowMapper<T> mapper) {
        return Statements.queryAll(connection, st, dialect, mapper);
    }

    /**
     * Исполняет пакет внутри этой транзакции.
     *
     * <p>В отличие от {@link Sql#batch()}, пакет использует соединение транзакции,
     * поэтому его строки фиксируются или откатываются вместе с остальными
     * операциями тела.
     *
     * @return исполнитель пакетов, привязанный к соединению транзакции
     */
    public @NotNull BatchExecutor batch() {
        return new BatchExecutor(connection, dialect);
    }

}
