package com.example.governance.assets.driver;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Driver jar repository: builtin registration, validated uploads, deletion protection. */
public final class DriverStore {
    public record DriverRecord(long id, String driverKey, String name, String dbType, String driverClass, String versionLabel, String originalFilename, String jarPath, String sha256, boolean builtin, Long uploadedBy, LocalDateTime createdAt) {}
    public record Builtin(String key, String name, String dbType, String driverClass, String resource) {}
    public static final List<Builtin> BUILTINS = List.of(
        new Builtin("builtin-mysql-5", "MySQL Connector/J 5.1", "MYSQL", "com.mysql.jdbc.Driver", "/drivers-builtin/builtin-mysql-5.jar"),
        new Builtin("builtin-mysql-8", "MySQL Connector/J (8.x+)", "MYSQL", "com.mysql.cj.jdbc.Driver", "/drivers-builtin/builtin-mysql-8.jar"),
        new Builtin("builtin-postgresql", "PostgreSQL JDBC", "POSTGRESQL", "org.postgresql.Driver", "/drivers-builtin/builtin-postgresql.jar"),
        new Builtin("builtin-ojdbc8", "Oracle JDBC (ojdbc8)", "ORACLE", "oracle.jdbc.OracleDriver", "/drivers-builtin/builtin-ojdbc8.jar"),
        new Builtin("builtin-ojdbc11", "Oracle JDBC (ojdbc11)", "ORACLE", "oracle.jdbc.OracleDriver", "/drivers-builtin/builtin-ojdbc11.jar"));
    public static final long MAX_JAR_BYTES = 50L * 1024 * 1024;

    public static final class DriverInUseException extends RuntimeException {
        private final List<String> references;
        public DriverInUseException(List<String> references) { super("Driver is referenced by data sources"); this.references = List.copyOf(references); }
        public List<String> references() { return references; }
    }

    private static final RowMapper<DriverRecord> MAPPER = (rs, row) -> {
        long uploadedBy = rs.getLong("uploaded_by");
        Long uploader = rs.wasNull() ? null : uploadedBy;
        var createdAt = rs.getTimestamp("created_at");
        return new DriverRecord(rs.getLong("id"), rs.getString("driver_key"), rs.getString("name"), rs.getString("db_type"), rs.getString("driver_class"),
            rs.getString("version_label"), rs.getString("original_filename"), rs.getString("jar_path"), rs.getString("sha256"), rs.getBoolean("builtin"), uploader,
            createdAt == null ? null : createdAt.toLocalDateTime());
    };

    private final JdbcTemplate jdbc;
    private final IsolatedDriverLoader loader;
    private final Path root;
    public DriverStore(JdbcTemplate jdbc, IsolatedDriverLoader loader, Path root) { this.jdbc = jdbc; this.loader = loader; this.root = root; }

    /** Idempotent: extracts packaged builtin jars and upserts their metadata. */
    public void registerBuiltins() {
        for (Builtin builtin : BUILTINS) {
            byte[] bytes;
            try (InputStream in = DriverStore.class.getResourceAsStream(builtin.resource())) {
                if (in == null) throw new IllegalStateException("Missing builtin driver resource: " + builtin.resource());
                bytes = in.readAllBytes();
            } catch (IOException ex) { throw new IllegalStateException("Builtin driver registration failed: " + builtin.key(), ex); }
            String sha = sha256(bytes);
            Path target = root.resolve("builtin").resolve(builtin.key() + ".jar");
            try {
                Files.createDirectories(target.getParent());
                if (!Files.exists(target) || !sha256(Files.readAllBytes(target)).equals(sha)) Files.write(target, bytes);
            } catch (IOException ex) { throw new IllegalStateException("Builtin driver registration failed: " + builtin.key(), ex); }
            String version = loader.probe(target, builtin.driverClass());
            var existing = findByKey(builtin.key());
            if (existing.isEmpty()) {
                try {
                    jdbc.update("INSERT INTO jdbc_drivers(driver_key,name,db_type,driver_class,version_label,jar_path,sha256,builtin) VALUES (?,?,?,?,?,?,?,TRUE)",
                        builtin.key(), builtin.name(), builtin.dbType(), builtin.driverClass(), version, target.toString(), sha);
                } catch (DuplicateKeyException concurrentRegistration) {
                    jdbc.update("UPDATE jdbc_drivers SET version_label=?,jar_path=?,sha256=? WHERE driver_key=?", version, target.toString(), sha, builtin.key());
                }
            } else if (!existing.get().sha256().equals(sha)) {
                jdbc.update("UPDATE jdbc_drivers SET version_label=?,jar_path=?,sha256=? WHERE driver_key=?", version, target.toString(), sha, builtin.key());
            }
        }
    }

    public DriverRecord upload(String name, String dbType, String driverClass, String versionLabel, String originalFilename, byte[] bytes, Long uploadedBy, Set<String> knownTypes) {
        if (name == null || name.isBlank() || name.length() > 120) throw new IllegalArgumentException("驱动名称无效");
        if (dbType == null || !knownTypes.contains(dbType)) throw new IllegalArgumentException("未知数据库类型");
        if (driverClass == null || !driverClass.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*){1,10}")) throw new IllegalArgumentException("驱动类名无效");
        String label = versionLabel == null || versionLabel.isBlank() ? null : versionLabel.trim();
        if (label != null && label.length() > 60) throw new IllegalArgumentException("版本标签过长");
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_JAR_BYTES) throw new IllegalArgumentException("驱动文件为空或超过 50MB");
        requireValidJar(bytes);
        String sha = sha256(bytes);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM jdbc_drivers WHERE sha256=?", Integer.class, sha) > 0) throw new IllegalArgumentException("相同内容的驱动已存在");
        Path temp;
        try {
            Files.createDirectories(root.resolve("uploaded"));
            temp = Files.createTempFile(root.resolve("uploaded"), "probe-", ".jar");
            Files.write(temp, bytes);
        } catch (IOException ex) { throw new IllegalStateException("驱动保存失败", ex); }
        String probed;
        try { probed = loader.probe(temp, driverClass); }
        catch (RuntimeException ex) { deleteQuietly(temp); throw new IllegalArgumentException("驱动类无法加载: " + driverClass); }
        String key = UUID.randomUUID().toString();
        Path target = root.resolve("uploaded").resolve(key + ".jar");
        try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (IOException ex) { deleteQuietly(temp); throw new IllegalStateException("驱动保存失败", ex); }
        jdbc.update("INSERT INTO jdbc_drivers(driver_key,name,db_type,driver_class,version_label,original_filename,jar_path,sha256,builtin,uploaded_by) VALUES (?,?,?,?,?,?,?,?,FALSE,?)",
            key, name.trim(), dbType, driverClass, label == null ? probed : label, safeFilename(originalFilename), target.toString(), sha, uploadedBy);
        return findByKey(key).orElseThrow();
    }

    public void delete(long id) {
        var driver = find(id).orElseThrow(() -> new IllegalArgumentException("Unknown driver"));
        if (driver.builtin()) throw new IllegalStateException("内置驱动不可删除");
        var references = jdbc.queryForList("SELECT name FROM data_sources WHERE driver_id=? ORDER BY id", String.class, id);
        if (!references.isEmpty()) throw new DriverInUseException(references);
        jdbc.update("DELETE FROM jdbc_drivers WHERE id=?", id);
        loader.retire(id);
        deleteQuietly(Path.of(driver.jarPath()));
    }

    public DriverRecord resolveForProfile(DatabaseProfile profile, Long driverId) {
        DriverRecord driver = driverId == null
            ? findByKey(profile.defaultDriverKey()).orElseThrow(() -> new IllegalStateException("Builtin driver missing: " + profile.defaultDriverKey()))
            : find(driverId).orElseThrow(() -> new IllegalArgumentException("Unknown driver"));
        if (!driver.dbType().equals(profile.type())) throw new IllegalArgumentException("驱动与数据库类型不匹配");
        return driver;
    }

    public Connection connect(DriverRecord driver, String url, Properties props) throws SQLException {
        return loader.connect(driver.id(), Path.of(driver.jarPath()), driver.driverClass(), url, props);
    }

    public List<DriverRecord> list() { return jdbc.query("SELECT * FROM jdbc_drivers ORDER BY builtin DESC, id", MAPPER); }
    public Optional<DriverRecord> find(long id) { return jdbc.query("SELECT * FROM jdbc_drivers WHERE id=?", MAPPER, id).stream().findFirst(); }
    public Optional<DriverRecord> findByKey(String key) { return jdbc.query("SELECT * FROM jdbc_drivers WHERE driver_key=?", MAPPER, key).stream().findFirst(); }

    private static void requireValidJar(byte[] bytes) {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            if (zip.getNextEntry() == null) throw new IllegalArgumentException("不是有效的 jar 文件");
        } catch (IOException ex) { throw new IllegalArgumentException("不是有效的 jar 文件"); }
    }
    private static String safeFilename(String name) {
        if (name == null) return null;
        String base = Path.of(name.replace('\\', '/')).getFileName().toString();
        return base.length() > 255 ? base.substring(0, 255) : base;
    }
    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static void deleteQuietly(Path path) { try { Files.deleteIfExists(path); } catch (IOException ignored) { } }
}
