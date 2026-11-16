package com.example.governance.workflow;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import org.apache.camel.ProducerTemplate;
import javax.sql.DataSource;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;

/** Executes one parameterized database statement through a Camel route. */
public final class SqlTaskExecutor implements AutoCloseable {
    private record Request(DataSource source, String sql, List<?> parameters) {}
    private final DefaultCamelContext context = new DefaultCamelContext();
    private final ProducerTemplate producer;

    public SqlTaskExecutor() throws Exception {
        context.addRoutes(new RouteBuilder() {
            @Override public void configure() {
                errorHandler(noErrorHandler());
                from("direct:execute-sql").process(exchange -> {
                    Request request = exchange.getMessage().getBody(Request.class);
                    try (var connection = request.source().getConnection();
                         var statement = connection.prepareStatement(request.sql())) {
                        statement.setQueryTimeout(30);
                        for (int i = 0; i < request.parameters().size(); i++) statement.setObject(i + 1, request.parameters().get(i));
                        exchange.getMessage().setBody(statement.executeUpdate());
                    }
                });
            }
        });
        context.start();
        producer = context.createProducerTemplate();
    }
    public int execute(DataSource source, String sql, List<?> parameters) {
        if (source == null || sql == null || sql.isBlank() || parameters == null) throw new IllegalArgumentException("SQL task is incomplete");
        return producer.requestBody("direct:execute-sql", new Request(source, sql, Collections.unmodifiableList(new ArrayList<>(parameters))), Integer.class);
    }
    @Override public void close() { producer.stop(); context.stop(); }
}
