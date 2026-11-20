package com.example.governance.workflow;

import java.util.function.Consumer;

/** Log sink plus a one-shot stats JSON consumer persisted onto the current attempt row. */
public record ExecutionSinks(RunLogSink log, Consumer<String> stats) {
    public static final ExecutionSinks NOOP = new ExecutionSinks((level, message) -> {}, json -> {});
}
