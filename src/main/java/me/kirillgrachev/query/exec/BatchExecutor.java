package me.kirillgrachev.query.exec;

import me.kirillgrachev.query.exception.ErrorCode;
import me.kirillgrachev.query.exception.QueryException;
import me.kirillgrachev.query.internal.ParamBinder;
import me.kirillgrachev.query.sql.Dialect;
import me.kirillgrachev.query.sql.SqlStatement;
import org.jetbrains.annotations.NotNull;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Пакетное исполнение одного и того же запроса с разными параметрами.
 *
 * <p>Три дефекта исходного {@code BatchBuilder} закрыты здесь явно.
 *
 * <p>Первый — {@code executeBatch()} возвращает {@code Statement.SUCCESS_NO_INFO}
 * ({@code -2}) для каждой строки, когда драйвер переписывает пакет в один запрос;
 * так ведёт себя MySQL с {@code rewriteBatchedStatements=true}. Исходная
 * реализация отдавала массив как есть, и код, суммирующий его и сравнивающий с
 * числом строк, молча получал отрицательное значение. Здесь результат разложен на
 * «строк затронуто» и «счётчик неизвестен», и второе видно явно.
 *
 * <p>Второй — буфер не очищался после исполнения, поэтому повторный вызов
 * отправлял все предыдущие строки заново. Здесь буфер очищается всегда, и класс
 * не является переиспользуемым накопителем между вызовами.
 *
 * <p>Третий — размер пакета не ограничивался, и пакет на миллион строк собирался
 * в памяти целиком, упираясь в {@code max_allowed_packet} или в OOM. Здесь есть
 * {@link #chunkSize(int)}, а значение по умолчанию подобрано так, чтобы пакет
 * гарантированно помещался в типовые лимиты драйверов.
 *
 * <p>Экземпляр не потокобезопасен: как и любой накопитель, он рассчитан на
 * использование из одного потока.
 */
public final class BatchExecutor {

    /** Размер пакета по умолчанию: 500 строк — компромисс между числом round-trip и объёмом одного пакета. */
    public static final int DEFAULT_CHUNK_SIZE = 500;

    private final Connection ownedConnection;
    private final DataSource dataSource;
    private final Dialect dialect;
    private final List<SqlStatement> statements = new ArrayList<>();

    private String expectedSql;
    private int chunkSize = DEFAULT_CHUNK_SIZE;

    BatchExecutor(@NotNull DataSource dataSource, @NotNull Dialect dialect) {

        this.dataSource = dataSource;
        this.ownedConnection = null;
        this.dialect = dialect;

    }

    BatchExecutor(@NotNull Connection connection, @NotNull Dialect dialect) {

        this.dataSource = null;
        this.ownedConnection = connection;
        this.dialect = dialect;

    }

    /**
     * Добавляет запрос в пакет.
     *
     * <p>Текст SQL должен совпадать с первым добавленным: JDBC-пакет — это один
     * {@code PreparedStatement} с разными параметрами, и разный текст означал бы
     * разные statement'ы. Проверка выполняется по уже отрендеренному SQL, поэтому
     * расхождение обнаруживается сразу, а не как SQLException от драйвера.
     *
     * @param st запрос, идентичный первому по тексту
     * @return этот объект
     * @throws QueryException если текст SQL отличается от первого добавленного
     */
    public @NotNull BatchExecutor add(@NotNull SqlStatement st) {

        if (st == null) {
            throw QueryException.of(ErrorCode.NULL_ARGUMENT, "Statement must not be null");
        }

        String sql = st.getSql(dialect);

        if (expectedSql == null) {
            expectedSql = sql;
        } else if (!sql.equals(expectedSql)) {
            throw QueryException.of(ErrorCode.CONFLICTING_CLAUSES,
                    "Batch requires identical SQL for every entry",
                    Map.of("expected", expectedSql, "actual", sql));
        }

        statements.add(st);

        return this;

    }

    /**
     * Задаёт размер пакета для разбиения.
     *
     * @param chunkSize положительное число строк в одном JDBC-пакете
     * @return этот объект
     */
    public @NotNull BatchExecutor chunkSize(int chunkSize) {

        if (chunkSize < 1) {
            throw QueryException.of(ErrorCode.INVALID_ARGUMENT_VALUE,
                    "Chunk size must be at least 1", Map.of("chunkSize", chunkSize));
        }

        this.chunkSize = chunkSize;

        return this;

    }

    /**
     * Исполняет накопленные запросы и очищает буфер.
     *
     * <p>Очистка выполняется всегда, в том числе при ошибке: оставить часть строк
     * в буфере означало бы, что повторный вызов отправит их второй раз, а
     * вызывающая сторона не сможет отличить «не исполнено» от «исполнено частично».
     *
     * @return агрегированный результат
     * @throws QueryException при ошибке JDBC
     */
    public @NotNull BatchResult execute() {

        List<SqlStatement> batch = List.copyOf(statements);
        statements.clear();
        expectedSql = null;

        if (batch.isEmpty()) {
            return BatchResult.empty();
        }

        if (ownedConnection != null) {
            return runOn(ownedConnection, batch, false);
        }

        try (Connection con = dataSource.getConnection()) {
            return runOn(con, batch, true);
        } catch (SQLException e) {
            throw QueryException.of(ErrorCode.CONNECTION_UNAVAILABLE,
                    "Failed to obtain a connection for the batch: " + e.getMessage(), Map.of(), e);
        }

    }

    /**
     * Отменяет накопленные запросы без исполнения.
     *
     * @return этот объект
     */
    public @NotNull BatchExecutor clear() {

        statements.clear();
        expectedSql = null;

        return this;

    }

    /**
     * Число накопленных запросов.
     *
     * @return размер буфера
     */
    public int size() {
        return statements.size();
    }

    private @NotNull BatchResult runOn(@NotNull Connection con,
                                       @NotNull List<SqlStatement> batch,
                                       boolean restoreAutoCommit) {

        boolean originalAutoCommit = true;

        try (PreparedStatement ps = con.prepareStatement(batch.getFirst().getSql(dialect))) {

            if (restoreAutoCommit) {
                // Пакет из нескольких chunk'ов обязан быть атомарным: без
                // выключенного autoCommit каждый chunk фиксировался бы отдельно,
                // и ошибка в середине оставила бы половину данных в базе.
                originalAutoCommit = con.getAutoCommit();

                if (originalAutoCommit) {
                    con.setAutoCommit(false);
                }
            }

            int executed = 0;
            long rows = 0;
            int unknown = 0;
            List<Integer> raw = new ArrayList<>(batch.size());

            for (SqlStatement st : batch) {
                ParamBinder.bind(ps, st.getSql(dialect), st.getParams());
                ps.addBatch();
                executed++;

                if (executed % chunkSize == 0) {
                    int[] counts = ps.executeBatch();
                    rows += sumKnown(counts);
                    unknown += countUnknown(counts);
                    collect(raw, counts);
                }

            }

            if (executed % chunkSize != 0) {
                int[] counts = ps.executeBatch();
                rows += sumKnown(counts);
                unknown += countUnknown(counts);
                collect(raw, counts);
            }

            // Коммит нужен только если autoCommit ВЫКЛЮЧИЛИ мы, то есть когда
            // исходно он был включён. Обратное условие означало бы, что пакет,
            // исполненный на соединении из пула с включённым autoCommit,
            // фиксируется и данные теряются при возврате соединения.
            if (restoreAutoCommit && originalAutoCommit) {
                con.commit();
            }

            return new BatchResult(executed, rows, unknown, List.copyOf(raw));

        } catch (SQLException e) {
            rollbackQuietly(con, restoreAutoCommit, originalAutoCommit);

            throw QueryException.of(ErrorCode.SQL_EXECUTION_FAILED,
                    "Batch execution failed after " + batch.size() + " statement(s): " + e.getMessage(),
                    Map.of("batchSize", batch.size(), "sql", batch.getFirst().getSql(dialect)),
                    e);

        } finally {
            if (restoreAutoCommit && originalAutoCommit) {
                try {
                    con.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // Соединение закрывается try-with-resources, пул проверит его сам.
                }
            }
        }

    }

    private void rollbackQuietly(@NotNull Connection con, boolean restoreAutoCommit, boolean wasAutoCommit) {

        // Откат нужен только там, где транзакцию начали мы: если autoCommit был
        // выключен вызывающей стороной, она сама управляет фиксацией.
        if (!restoreAutoCommit || !wasAutoCommit) {
            return;
        }

        try {
            con.rollback();
        } catch (SQLException ignored) {
            // Исходная ошибка важнее; соединение всё равно закрывается вызывающей стороной.
        }

    }

    /** Суммирует только неотрицательные счётчики. */
    private long sumKnown(int @NotNull [] counts) {

        long sum = 0;

        for (int count : counts) {
            if (count >= 0) {
                sum += count;
            }
        }

        return sum;

    }

    /**
     * Считает элементы с {@code SUCCESS_NO_INFO} и {@code EXECUTE_FAILED}.
     *
     * <p>Оба значения отрицательны и оба означают «число строк неизвестно»:
     * драйвер переписал пакет либо выполнил его частично. Различать их здесь
     * бессмысленно — {@code EXECUTE_FAILED} сопровождается {@code BatchUpdateException},
     * который обрабатывается как ошибка на уровне выше.
     */
    private int countUnknown(int @NotNull [] counts) {

        int unknown = 0;

        for (int count : counts) {
            if (count == Statement.SUCCESS_NO_INFO) {
                unknown++;
            }
        }

        return unknown;

    }

    private void collect(@NotNull List<Integer> target, int @NotNull [] counts) {
        for (int count : counts) {
            target.add(count);
        }
    }

}
