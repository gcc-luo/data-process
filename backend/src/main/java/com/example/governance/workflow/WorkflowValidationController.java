package com.example.governance.workflow;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WorkflowValidationController {
    record Request(List<String> tasks, List<WorkflowGraph.Edge> edges) {}
    record Result(boolean valid, List<String> ready, String message) {}

    @PostMapping("/api/workflows/validate")
    ResponseEntity<Result> validate(@RequestBody Request request) {
        try {
            if (request.tasks() == null || request.tasks().isEmpty() || request.edges() == null ||
                    request.tasks().stream().anyMatch(id -> id == null || id.isBlank()) ||
                    request.tasks().size() != new HashSet<>(request.tasks()).size() ||
                    request.edges().stream().anyMatch(edge -> edge == null || edge.from() == null || edge.to() == null)) {
                throw new IllegalArgumentException("Invalid workflow definition");
            }
            var graph = new WorkflowGraph(Set.copyOf(request.tasks()), request.edges());
            return ResponseEntity.ok(new Result(true, graph.ready(Set.of(), Set.of()).stream().sorted().toList(), ""));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new Result(false, List.of(), ex.getMessage()));
        }
    }
}
