package com.example.governance.workflow;

import java.util.function.Consumer;
import java.util.function.Function;

/** Log sink, one-shot stats JSON consumer and an optional per-node log factory. */
public record ExecutionSinks(RunLogSink log, Consumer<String> stats, Function<String, RunLogSink> nodeLog) {
    public ExecutionSinks(RunLogSink log, Consumer<String> stats) { this(log, stats, null); }
    public static final ExecutionSinks NOOP = new ExecutionSinks((level, message) -> {}, json -> {}, null);
    public RunLogSink logFor(String nodeKey) { return nodeLog == null ? log : nodeLog.apply(nodeKey); }
}