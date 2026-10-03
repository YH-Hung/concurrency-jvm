package hle.org.workqueue.engine;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/** A recording JDBC boundary: real Spring/Hikari/configuration, no database server. */
public final class StartupDatabase implements Driver {
    static final AtomicInteger connections = new AtomicInteger();
    static final List<String> queries = new CopyOnWriteArrayList<>();
    static volatile Properties connectionProperties;
    static volatile List<String> namespaces = List.of("demo");
    static volatile long migrations = 1;
    static volatile BigDecimal timezone = BigDecimal.ZERO;
    static volatile boolean missingTable;
    static volatile boolean sqlFailure;
    static { try { DriverManager.registerDriver(new StartupDatabase()); } catch (SQLException e) { throw new ExceptionInInitializerError(e); } }
    static void reset() {
        connections.set(0); queries.clear(); connectionProperties = null;
        namespaces = List.of("demo"); migrations = 1; timezone = BigDecimal.ZERO;
        missingTable = false; sqlFailure = false;
    }
    @Override public Connection connect(String url, Properties info) {
        if (!acceptsURL(url)) return null;
        connections.incrementAndGet(); connectionProperties = info;
        return proxy(Connection.class, (method, args) -> switch (method) {
            case "prepareStatement" -> statement((String) args[0], PreparedStatement.class);
            case "createStatement" -> statement(null, Statement.class);
            case "getAutoCommit", "isValid" -> true;
            case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
            default -> null;
        });
    }
    private static Object statement(String preparedSql, Class<?> type) {
        return proxy(type, (method, args) -> {
            if (method.startsWith("execute")) {
                String sql = preparedSql != null ? preparedSql : (String) args[0];
                queries.add(sql);
                if (sqlFailure && !sql.startsWith("SET ")) throw new SQLException("secret-database-detail", "08001", -4499);
                if (missingTable && sql.contains("WORK_ITEM")) throw new SQLException("secret table detail", "42704");
                if (method.equals("executeQuery")) return rows(sql);
                if (method.equals("executeUpdate")) return 0;
                if (method.equals("execute")) return false;
            }
            return null;
        });
    }
    private static ResultSet rows(String sql) {
        List<Object> values;
        if (sql.contains("flyway_schema_history")) values = List.of(migrations);
        else if (sql.contains("SELECT NAMESPACE")) values = new ArrayList<>(namespaces);
        else if (sql.contains("CURRENT TIMEZONE")) values = List.of(timezone);
        else if (sql.contains("COUNT(CASE")) values = List.of(0L);
        else values = List.of();
        AtomicInteger row = new AtomicInteger(-1);
        return proxy(ResultSet.class, (method, args) -> {
            if (method.equals("next")) return row.incrementAndGet() < values.size();
            if (method.equals("getMetaData")) return proxy(ResultSetMetaData.class, (m, a) -> switch(m) {
                case "getColumnCount" -> 1;
                case "getColumnType" -> values.isEmpty() || !(values.getFirst() instanceof String) ? Types.BIGINT : Types.VARCHAR;
                case "getColumnLabel", "getColumnName" -> "VALUE";
                default -> null;
            });
            if (method.equals("getLong")) return ((Number) values.get(row.get())).longValue();
            if (method.equals("getInt")) return ((Number) values.get(row.get())).intValue();
            if (method.equals("getString")) return values.get(row.get()).toString();
            if (method.equals("getBigDecimal")) return (BigDecimal) values.get(row.get());
            if (method.equals("getObject")) return values.get(row.get());
            return null;
        });
    }
    interface Call { Object invoke(String method, Object[] args) throws Throwable; }
    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, Call call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getName().equals("toString")) return "recording-" + type.getSimpleName();
            if (method.getName().equals("hashCode")) return System.identityHashCode(self);
            if (method.getName().equals("equals")) return self == args[0];
            Object value = call.invoke(method.getName(), args);
            if (value != null || !method.getReturnType().isPrimitive()) return value;
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == long.class) return 0L;
            if (method.getReturnType() == int.class) return 0;
            return null;
        });
    }
    @Override public boolean acceptsURL(String url) { return url != null && url.startsWith("jdbc:queue-test:"); }
    @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties p) { return new DriverPropertyInfo[0]; }
    @Override public int getMajorVersion() { return 1; }
    @Override public int getMinorVersion() { return 0; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public Logger getParentLogger() { return Logger.getGlobal(); }
}
