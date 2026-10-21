package me.kirillgrachev.query.testsupport;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Считает созданные и закрытые JDBC-ресурсы, оборачивая их динамическим прокси.
 *
 * <p>Нужен потому, что утечку statement'а невозможно обнаружить по поведению:
 * запросы продолжают работать, а падение наступает через часы, когда счётчик
 * открытых курсоров упирается в лимит СУБД. Единственный способ доказать
 * отсутствие утечки — посчитать вызовы {@code close()} и сравнить с числом
 * созданных ресурсов.
 *
 * <p>Именно так обнаруживается дефект исходной библиотеки, где
 * {@code ResultSetWrapper.map()/mapAll()} закрывали {@code ResultSet}, но не
 * родительский {@code PreparedStatement}, хотя javadoc обещал обратное.
 *
 * <p>Прокси применяется ко всем трём уровням (Connection, PreparedStatement,
 * ResultSet), потому что statement создаётся соединением, а result set —
 * statement'ом: перехват только одного уровня не увидел бы остальные.
 */
public final class CountingDataSource implements DataSource {

    private final DataSource delegate;

    private final AtomicInteger connectionsCreated = new AtomicInteger();
    private final AtomicInteger connectionsClosed = new AtomicInteger();
    private final AtomicInteger statementsCreated = new AtomicInteger();
    private final AtomicInteger statementsClosed = new AtomicInteger();
    private final AtomicInteger resultSetsCreated = new AtomicInteger();
    private final AtomicInteger resultSetsClosed = new AtomicInteger();

    public CountingDataSource(DataSource delegate) {

        this.delegate = delegate;

    }

    public int getConnectionsCreated() {

        return connectionsCreated.get();

    }

    public int getConnectionsClosed() {

        return connectionsClosed.get();

    }

    public int getStatementsCreated() {

        return statementsCreated.get();

    }

    public int getStatementsClosed() {

        return statementsClosed.get();

    }

    public int getResultSetsCreated() {

        return resultSetsCreated.get();

    }

    public int getResultSetsClosed() {

        return resultSetsClosed.get();

    }

    /** Утечка — разница между созданными и закрытыми ресурсами. */
    public int getLeaks() {

        return (statementsCreated.get() - statementsClosed.get())
                + (resultSetsCreated.get() - resultSetsClosed.get())
                + (connectionsCreated.get() - connectionsClosed.get());

    }

    public void reset() {

        connectionsCreated.set(0);
        connectionsClosed.set(0);
        statementsCreated.set(0);
        statementsClosed.set(0);
        resultSetsCreated.set(0);
        resultSetsClosed.set(0);

    }

    @Override
    public Connection getConnection() throws SQLException {

        return wrapConnection(delegate.getConnection());

    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {

        return wrapConnection(delegate.getConnection(username, password));

    }

    private Connection wrapConnection(Connection real) {

        connectionsCreated.incrementAndGet();

        return (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Connection.class},
                new ConnectionHandler(real));

    }

    private final class ConnectionHandler implements InvocationHandler {

        private final Connection real;

        private ConnectionHandler(Connection real) {

            this.real = real;

        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

            String name = method.getName();

            if (name.equals("close")) {
                connectionsClosed.incrementAndGet();

                return method.invoke(real, args);
            }

            Object result;

            try {
                result = method.invoke(real, args);

            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }

            if (name.startsWith("prepareStatement") && result instanceof PreparedStatement ps) {
                return wrapStatement(ps);
            }

            return result;

        }

    }

    private PreparedStatement wrapStatement(PreparedStatement real) {

        statementsCreated.incrementAndGet();

        return (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                new StatementHandler(real));

    }

    private final class StatementHandler implements InvocationHandler {

        private final PreparedStatement real;

        private StatementHandler(PreparedStatement real) {

            this.real = real;

        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

            String name = method.getName();

            if (name.equals("close")) {
                statementsClosed.incrementAndGet();

                return method.invoke(real, args);
            }

            Object result;

            try {
                result = method.invoke(real, args);

            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }

            if ((name.equals("executeQuery") || name.equals("getGeneratedKeys"))
                    && result instanceof ResultSet rs) {
                return wrapResultSet(rs);
            }

            return result;

        }

    }

    private ResultSet wrapResultSet(ResultSet real) {

        resultSetsCreated.incrementAndGet();

        return (ResultSet) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        resultSetsClosed.incrementAndGet();
                    }

                    try {
                        return method.invoke(real, args);

                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });

    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {

        return delegate.getLogWriter();

    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {

        delegate.setLogWriter(out);

    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {

        delegate.setLoginTimeout(seconds);

    }

    @Override
    public int getLoginTimeout() throws SQLException {

        return delegate.getLoginTimeout();

    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {

        return delegate.getParentLogger();

    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {

        return delegate.unwrap(iface);

    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {

        return delegate.isWrapperFor(iface);

    }

}
