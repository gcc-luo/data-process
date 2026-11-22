package com.example.governance.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** HTTP source/sink against a local stub plus SSRF guard behavior. */
class OrchHttpTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static final LocalDate DATE = LocalDate.of(2026, 11, 21);

    private static DriverManagerDataSource db() { return new DriverManagerDataSource("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""); }

    @Test void httpSourceExtractsPathAndLandsRows() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/orders", ex -> {
            byte[] body = "{\"data\":{\"items\":[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\"}]}}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body); ex.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            var target = db();
            try (var c = target.getConnection(); var s = c.createStatement()) { s.execute("CREATE TABLE dst(id INT, name VARCHAR(40))"); }
            String def = "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[" +
                    "{\"id\":\"h\",\"component\":\"source.http\",\"name\":\"API\",\"enabled\":true,\"config\":{\"method\":\"GET\",\"url\":\"http://127.0.0.1:" + port + "/api/orders\",\"extractPath\":\"data.items\",\"timeoutSeconds\":10}}," +
                    "{\"id\":\"k\",\"component\":\"sink.mysql\",\"name\":\"写\",\"enabled\":true,\"config\":{\"sourceId\":1,\"table\":\"dst\",\"writeMode\":\"APPEND\"}}]," +
                    "\"edges\":[{\"id\":\"e1\",\"from\":\"h\",\"fromPort\":\"out\",\"to\":\"k\",\"toPort\":\"in\",\"kind\":\"DATA\"}]}";
            try (var sql = new SqlTaskExecutor()) {
                var executor = new OrchTaskExecutor(sql, new HttpTargetGuard("127.0.0.1,localhost"));
                executor.execute(OrchDefinition.parse(M.readTree(def)), id -> target,
                        new OrchTaskExecutor.Context(1, 1L, DATE, Map.of(), false), new RunCancelContext(), ExecutionSinks.NOOP, null, 0, 1000, null);
            }
            assertEquals(2, new JdbcTemplate(target).queryForObject("SELECT COUNT(*) FROM dst", Integer.class));
            assertEquals("a", new JdbcTemplate(target).queryForObject("SELECT name FROM dst WHERE id=1", String.class));
        } finally { server.stop(0); }
    }

    @Test void httpSinkPushesBatchJson() throws Exception {
        var source = db();
        try (var c = source.getConnection(); var s = c.createStatement()) { s.execute("CREATE TABLE src(id INT)"); s.execute("INSERT INTO src VALUES (1),(2)"); }
        AtomicReference<String> received = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ingest", ex -> {
            received.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(200, -1); ex.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            String def = "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[" +
                    "{\"id\":\"s\",\"component\":\"source.mysql\",\"name\":\"源\",\"enabled\":true,\"config\":{\"sourceId\":1,\"readMode\":\"SQL\",\"sql\":\"SELECT id FROM src\"}}," +
                    "{\"id\":\"p\",\"component\":\"sink.http\",\"name\":\"推送\",\"enabled\":true,\"config\":{\"url\":\"http://127.0.0.1:" + port + "/ingest\",\"method\":\"POST\",\"batchSize\":100,\"timeoutSeconds\":10}}]," +
                    "\"edges\":[{\"id\":\"e1\",\"from\":\"s\",\"fromPort\":\"out\",\"to\":\"p\",\"toPort\":\"in\",\"kind\":\"DATA\"}]}";
            try (var sql = new SqlTaskExecutor()) {
                var executor = new OrchTaskExecutor(sql, new HttpTargetGuard("127.0.0.1"));
                executor.execute(OrchDefinition.parse(M.readTree(def)), id -> source,
                        new OrchTaskExecutor.Context(1, 1L, DATE, Map.of(), false), new RunCancelContext(), ExecutionSinks.NOOP, null, 0, 1000, null);
            }
            var rows = M.readTree(received.get());
            assertTrue(rows.isArray());
            assertEquals(2, rows.size());
            assertEquals(1, rows.get(0).path("id").asInt());
        } finally { server.stop(0); }
    }

    @Test void ssrfGuardBlocksLoopbackAndMetadataWithoutAllowlist() {
        var guard = new HttpTargetGuard("");
        assertThrows(IllegalArgumentException.class, () -> guard.check(URI.create("http://127.0.0.1:8080/x")));
        assertThrows(IllegalArgumentException.class, () -> guard.check(URI.create("http://169.254.169.254/latest/meta-data")));
        assertThrows(IllegalArgumentException.class, () -> guard.check(URI.create("http://localhost/x")));
        assertThrows(IllegalArgumentException.class, () -> guard.check(URI.create("ftp://example.com/x")));
    }

    @Test void ssrfGuardAllowsExplicitHost() {
        var guard = new HttpTargetGuard("127.0.0.1");
        assertDoesNotThrow(() -> guard.check(URI.create("http://127.0.0.1:8080/x")));
    }
}