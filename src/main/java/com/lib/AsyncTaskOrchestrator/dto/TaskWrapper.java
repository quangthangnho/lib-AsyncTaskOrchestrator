package com.lib.AsyncTaskOrchestrator.dto;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Immutable container for representing a task in the orchestrator DSL.
 *
 * @param name       Task name (for logging).
 * @param tasks      Task logic as a function.
 * @param returnType Return type class.
 * @param timeout    Task timeout duration.
 * @param <T>        Task return type.
 * @param <C>        Context type (from pre-condition).
 */
public record TaskWrapper<T, C>(
        String name, Function<C, CompletableFuture<T>> tasks, Class<T> returnType, Duration timeout) {}