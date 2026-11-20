package com.example.governance.workflow;

/** Receives execution log lines; implementations assign line numbers and persist or collect. */
@FunctionalInterface
public interface RunLogSink { void log(String level, String message); }
