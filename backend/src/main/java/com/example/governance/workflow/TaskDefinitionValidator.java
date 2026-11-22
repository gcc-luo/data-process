package com.example.governance.workflow;

import com.example.governance.assets.DataSourceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Validates ORCH task definitions before execution; authorization is enforced separately. */
public final class TaskDefinitionValidator {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DataSourceStore sources;

    public TaskDefinitionValidator(DataSourceStore sources) { this.sources = sources; }

    public void validate(String type, String configuration) {
        try {
            if (configuration == null || configuration.length() > OrchDefinition.MAX_DEFINITION_CHARS) throw new IllegalArgumentException("Invalid task configuration");
            JsonNode root = mapper.readTree(configuration);
            if (!"ORCH".equals(type)) throw new IllegalArgumentException("Invalid task type");
            var definition = OrchDefinition.parse(root);
            var issues = definition.validate(id -> sources.find(id).map(com.example.governance.assets.DataSourceDefinition::type));
            var errors = issues.stream().filter(i -> "ERROR".equals(i.severity())).toList();
            if (!errors.isEmpty()) throw new IllegalArgumentException(errors.getFirst().message());
        } catch (java.io.IOException ex) { throw new IllegalArgumentException("Invalid task configuration"); }
    }
}