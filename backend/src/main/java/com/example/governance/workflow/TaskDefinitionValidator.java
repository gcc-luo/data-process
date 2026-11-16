package com.example.governance.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Validates task configuration shape; authorization is enforced separately. */
public final class TaskDefinitionValidator {
    private final ObjectMapper mapper = new ObjectMapper();
    public void validate(String type, String configuration) {
        try {
            if (configuration == null || configuration.length() > 100000) throw new IllegalArgumentException("Invalid task configuration");
            JsonNode root = mapper.readTree(configuration);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("Invalid task configuration");
            if (root.has("retry")) {
                var retry = root.path("retry"); var max = retry.path("maxRetries"); var delay = retry.path("delaySeconds");
                if (!retry.isObject() || !retry.path("safe").isBoolean() || !retry.path("safe").booleanValue() || !max.isInt() || max.intValue() < 1 || max.intValue() > 3 || !delay.isInt() || delay.intValue() < 1 || delay.intValue() > 3600) throw new IllegalArgumentException("Invalid retry policy");
            }
            id(root, "sourceId");
            if ("SQL".equals(type)) {
                text(root, "sql");
                parameters(root.path("parameters"));
            } else if ("SYNC".equals(type)) {
                id(root, "targetId"); text(root, "sourceQuery");
                if (root.has("sourceParameters")) parameters(root.path("sourceParameters"));
                String table = text(root, "targetTable");
                if (!table.matches("[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)?")) throw new IllegalArgumentException("Invalid table");
                JsonNode columns = root.path("targetColumns");
                if (!columns.isArray() || columns.isEmpty() || columns.size() > 1000) throw new IllegalArgumentException("Invalid columns");
                for (JsonNode column : columns) if (!column.isTextual() || !column.textValue().matches("[A-Za-z_][A-Za-z0-9_$]*")) throw new IllegalArgumentException("Invalid column");
                String mode = root.has("writeMode") ? text(root, "writeMode") : "APPEND";
                if (!java.util.Set.of("APPEND", "UPSERT").contains(mode)) throw new IllegalArgumentException("Invalid write mode");
                if ("UPSERT".equals(mode)) {
                    JsonNode keys = root.path("keyColumns");
                    if (!keys.isArray() || keys.isEmpty() || keys.size() > columns.size()) throw new IllegalArgumentException("Invalid primary keys");
                    var known = new java.util.HashSet<String>();
                    for (JsonNode column : columns) known.add(column.textValue().toLowerCase(java.util.Locale.ROOT));
                    var seen = new java.util.HashSet<String>();
                    for (JsonNode key : keys) if (!key.isTextual() || !known.contains(key.textValue().toLowerCase(java.util.Locale.ROOT)) || !seen.add(key.textValue().toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException("Invalid primary key");
                }
                JsonNode batch = root.get("batchSize");
                if (batch != null && (!batch.isIntegralNumber() || !batch.canConvertToInt() || batch.intValue() < 1 || batch.intValue() > 10000)) throw new IllegalArgumentException("Invalid batch size");
            } else throw new IllegalArgumentException("Invalid task type");
        } catch (java.io.IOException ex) { throw new IllegalArgumentException("Invalid task configuration"); }
    }
    private static void parameters(JsonNode params) {
        if (!params.isArray() || params.size() > 1000) throw new IllegalArgumentException("Invalid parameters");
        for (JsonNode param : params) {
            String kind = text(param, "type");
            if ("VALUE".equals(kind)) {
                JsonNode value = param.get("value");
                if (value != null && !value.isValueNode()) throw new IllegalArgumentException("Invalid parameter");
            } else if (!"BUSINESS_DATE".equals(kind)) throw new IllegalArgumentException("Invalid parameter type");
        }
    }
    private static void id(JsonNode root, String key) {
        JsonNode value = root.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) throw new IllegalArgumentException("Invalid source ID");
    }
    private static String text(JsonNode root, String key) {
        JsonNode value = root.get(key);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw new IllegalArgumentException("Invalid task field");
        return value.textValue();
    }
}
