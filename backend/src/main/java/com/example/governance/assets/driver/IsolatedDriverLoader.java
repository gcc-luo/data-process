package com.example.governance.assets.driver;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loads each JDBC driver in its own URLClassLoader (parent = platform loader, so driver code
 * cannot see application classes). Connections are reference-counted; a retired loader closes
 * once its last connection is returned.
 */
public final class IsolatedDriverLoader implements AutoCloseable {
    private record Loaded(URLClassLoader loader, Driver driver, AtomicInteger references, AtomicBoolean closing) {}
    private final Map<Long, Loaded> cache = new ConcurrentHashMap<>();

    public Connection connect(long driverId, Path jar, String driverClass, String url, Properties props) throws SQLException {
        Loaded loaded = cache.computeIfAbsent(driverId, id -> load(jar, driverClass));
        loaded.references().incrementAndGet();
        Connection raw;
        try { raw = loaded.driver().connect(url, props); }
        catch (SQLException | RuntimeException ex) { loaded.references().decrementAndGet(); throw ex; }
        if (raw == null) { loaded.references().decrementAndGet(); throw new SQLException("Driver does not accept the URL"); }
        return counted(loaded, raw);
    }

    private static Connection counted(Loaded loaded, Connection raw) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "close":
                    try { raw.close(); } finally { release(loaded); }
                    return null;
                case "unwrap":
                    if (args.length == 1 && ((Class<?>) args[0]).isInstance(raw)) return raw;
                    throw new SQLException("Not a wrapper for " + args[0]);
                case "isWrapperFor":
                    return args.length == 1 && ((Class<?>) args[0]).isInstance(raw);
                default:
                    try { return method.invoke(raw, args); } catch (InvocationTargetException ex) { throw ex.getCause(); }
            }
        });
    }

    private static void release(Loaded loaded) {
        if (loaded.references().decrementAndGet() == 0 && loaded.closing().get()) closeQuietly(loaded.loader());
    }

    private Loaded load(Path jar, String driverClass) {
        try {
            var loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
            Driver driver = Class.forName(driverClass, true, loader).asSubclass(Driver.class).getDeclaredConstructor().newInstance();
            return new Loaded(loader, driver, new AtomicInteger(), new AtomicBoolean());
        } catch (ReflectiveOperationException | IOException ex) {
            throw new IllegalArgumentException("Driver load failed: " + driverClass, ex);
        }
    }

    /** Marks a driver removed; closes its loader immediately when idle, otherwise after the last connection closes. */
    public void retire(long driverId) {
        Loaded loaded = cache.remove(driverId);
        if (loaded == null) return;
        loaded.closing().set(true);
        if (loaded.references().get() == 0) closeQuietly(loaded.loader());
    }

    /** One-shot load used by upload validation and builtin registration; returns major.minor version. */
    public String probe(Path jar, String driverClass) {
        try (var loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Driver driver = Class.forName(driverClass, true, loader).asSubclass(Driver.class).getDeclaredConstructor().newInstance();
            return driver.getMajorVersion() + "." + driver.getMinorVersion();
        } catch (ReflectiveOperationException | IOException ex) {
            throw new IllegalArgumentException("Driver class invalid: " + driverClass, ex);
        }
    }

    boolean isCached(long driverId) { return cache.containsKey(driverId); }

    @Override public void close() { cache.keySet().forEach(this::retire); }
    private static void closeQuietly(URLClassLoader loader) { try { loader.close(); } catch (IOException ignored) { } }
}
