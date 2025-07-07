package com.lib.AsyncTaskOrchestrator.handler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;


import com.lib.AsyncTaskOrchestrator.constant.OnFailurePolicy;
import com.lib.AsyncTaskOrchestrator.dto.TaskWrapper;
import lombok.extern.slf4j.Slf4j;

/**
 * DSL-style orchestrator that allows composing a pre-condition and multiple asynchronous tasks
 * with timeout, retry, failure policy, and aggregation – all executed on Virtual Threads.
 *
 * <p>Supports optional pre-condition (context builder), flexible task registration using either
 * context-aware functions or plain async suppliers, and customizable failure handling.</p>
 *
 * <p><b>Key features:</b></p>
 * <ul>
 *   <li>Optional pre-condition to build a shared context</li>
 *   <li>Register tasks using Function&lt;C, CompletableFuture&lt;T&gt;&gt; or Supplier&lt;CompletableFuture&lt;T&gt;&gt;</li>
 *   <li>Retry configuration with conditional logic</li>
 *   <li>Failure handling strategies (THROW or SKIP)</li>
 *   <li>Timeout support per task</li>
 *   <li>Aggregation of task results via a result collector</li>
 * </ul>
 *
 * @param <P> Input parameter to pre-condition
 * @param <C> Context object shared to all tasks after pre-condition
 */
@Slf4j
public class TaskOrchestratorDSL<P, C> {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final Executor VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private P param;
    private Function<P, CompletableFuture<C>> preConditionFn;
    private final List<TaskWrapper<?, C>> tasks = new ArrayList<>();
    private OnFailurePolicy failurePolicy = OnFailurePolicy.THROW;
    private Function<List<Object>, ?> aggregator;
    private int defaultMaxRetry = 0;
    private int retriesCount = 0;
    private Predicate<Throwable> retryCondition = t -> false;
    private Duration retryDelay = Duration.ofSeconds(1);

    /**
     * Creates a new orchestrator builder instance.
     */
    public static <P, C> TaskOrchestratorDSL<P, C> builder() {
        return new TaskOrchestratorDSL<>();
    }

    /**
     * Sets the input parameter that will be passed into the pre-condition.
     *
     * @param param the input parameter
     */
    public TaskOrchestratorDSL<P, C> withParam(P param) {
        this.param = param;
        return this;
    }

    /**
     * Registers a pre-condition function that transforms the input param into a shared context.
     * If not provided, the param will be cast to context (C) directly.
     *
     * @param preConditionFn function to compute context from input
     */
    public TaskOrchestratorDSL<P, C> preCondition(Function<P, CompletableFuture<C>> preConditionFn) {
        this.preConditionFn = preConditionFn;
        return this;
    }

    /**
     * Sets the number of retry attempts for each task.
     *
     * @param times retry count (must be ≥ 0)
     */
    public TaskOrchestratorDSL<P, C> retry(int times) {
        this.defaultMaxRetry = times;
        return this;
    }

    /**
     * Sets the condition under which a task should be retried.
     * If not set, no retry will occur regardless of failure.
     *
     * <p>Example: retry only if exception is instance of TimeoutException</p>
     * <pre>
     *     retryIf(TimeoutException.class::isInstance)
     * </pre>
     *
     * @param condition predicate to check whether to retry on a given Throwable
     */
    public TaskOrchestratorDSL<P, C> retryIf(Predicate<Throwable> condition) {
        this.retryCondition = condition;
        return this;
    }

    /**
     * Sets the policy to apply when a task fails after all retries.
     *
     * @param policy OnFailurePolicy.THROW (default) or SKIP
     */
    public TaskOrchestratorDSL<P, C> onFailure(OnFailurePolicy policy) {
        this.failurePolicy = policy;
        return this;
    }

    /**
     * Registers a context-aware task with default timeout.
     *
     * @param name   task name (for logging)
     * @param taskFn function taking context and returning async result
     * @param type   return type (used only for metadata)
     */
    public <T> TaskOrchestratorDSL<P, C> addTask(String name, Function<C, CompletableFuture<T>> taskFn, Class<T> type) {
        return addTask(name, taskFn, type, DEFAULT_TIMEOUT);
    }

    /**
     * Registers a context-aware task with custom timeout.
     *
     * @param name    task name
     * @param taskFn  function taking context and returning async result
     * @param type    return type
     * @param timeout timeout duration per attempt
     */
    public <T> TaskOrchestratorDSL<P, C> addTask(
            String name, Function<C, CompletableFuture<T>> taskFn, Class<T> type, Duration timeout) {
        tasks.add(new TaskWrapper<>(name, taskFn, type, timeout));
        return this;
    }

    /**
     * Registers a context-free task using Supplier, with default timeout.
     */
    public <T> TaskOrchestratorDSL<P, C> addTask(String name, Supplier<CompletableFuture<T>> supplier, Class<T> type) {
        return addTask(name, ctx -> supplier.get(), type, DEFAULT_TIMEOUT);
    }

    /**
     * Registers a context-free task using Supplier, with custom timeout.
     */
    public <T> TaskOrchestratorDSL<P, C> addTask(
            String name, Supplier<CompletableFuture<T>> supplier, Class<T> type, Duration timeout) {
        return addTask(name, ctx -> supplier.get(), type, timeout);
    }

    /**
     * Sets the function that aggregates the results from all tasks.
     * This is required to produce the final output.
     */
    public TaskOrchestratorDSL<P, C> aggregate(Function<List<Object>, ?> aggregator) {
        this.aggregator = aggregator;
        return this;
    }

    /**
     * Starts execution: first runs pre-condition (if any), then executes all registered tasks
     * concurrently on virtual threads, applies retry and failure handling logic, and aggregates the results.
     *
     * @return a CompletableFuture that resolves to the aggregated result
     * @throws CompletionException if any task fails and failure policy is THROW
     */
    @SuppressWarnings("unchecked")
    public <R> CompletableFuture<R> execute() {
        validate();

        CompletableFuture<C> ctxFuture = (preConditionFn != null)
                ? preConditionFn.apply(param).handle(this::handlePreCondition)
                : CompletableFuture.completedFuture((C) param);

        return ctxFuture.thenCompose(ctx -> {
            List<CompletableFuture<Object>> futures =
                    tasks.stream().map(task -> runTaskWithPolicy(task, ctx)).toList();

            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> collectResults(futures))
                    .thenApply(result -> (R) result);
        });
    }

    private void validate() {
        if (aggregator == null) {
            throw new IllegalStateException("Missing aggregator function.");
        }
    }

    private C handlePreCondition(C ctx, Throwable ex) {
        if (ex != null) {
            throw new CompletionException("❌ Pre-condition failed", ex);
        }
        return ctx;
    }

    private <T> CompletableFuture<Object> runTaskWithPolicy(TaskWrapper<T, C> task, C context) {
        return runWithRetry(task.tasks(), context, task.timeout(), task.name());
    }

    private <T> CompletableFuture<Object> runWithRetry(
            Function<C, CompletableFuture<T>> fn, C ctx, Duration timeout, String name) {
        CompletableFuture<Object> result = new CompletableFuture<>();
        executeWithRetry(fn, ctx, timeout, name, result);
        return result;
    }

    private <T> void executeWithRetry(
            Function<C, CompletableFuture<T>> fn,
            C ctx,
            Duration timeout,
            String name,
            CompletableFuture<Object> result) {

        CompletableFuture.supplyAsync(
                        () -> {
                            log.info("🧵 Running  task '{}' inside virtual thread: {}", name, Thread.currentThread());
                            return fn.apply(ctx).join();
                        },
                        VIRTUAL_EXECUTOR)
                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((res, ex) -> {
                    if (ex == null) {
                        result.complete(res);
                    } else {
                        this.retriesCount += 1;
                        log.error(
                                "❌ Task '{}' failed (attempt {}/{}): {}",
                                name,
                                this.retriesCount,
                                this.defaultMaxRetry,
                                ex.getMessage());
                        handleFailureOrRetry(
                                fn, ctx, this.retriesCount, this.defaultMaxRetry, timeout, name, result, ex);
                    }
                });
    }

    private <T> void handleFailureOrRetry(
            Function<C, CompletableFuture<T>> fn,
            C ctx,
            int retriesCount,
            int maxRetry,
            Duration timeout,
            String name,
            CompletableFuture<Object> result,
            Throwable error) {

        if (retriesCount < maxRetry && retryCondition.test(error)) {
            CompletableFuture.delayedExecutor(retryDelay.toMillis(), TimeUnit.MILLISECONDS)
                    .execute(() -> executeWithRetry(fn, ctx, timeout, name, result));
        } else {
            if (failurePolicy == OnFailurePolicy.SKIP) {
                log.warn("⚠️ Task '{}' skipped after {} attempts", name, retriesCount + 1);
                result.complete(null);
            } else {
                result.completeExceptionally(error);
            }
        }
    }

    private Object collectResults(List<CompletableFuture<Object>> futures) {
        List<Object> results = new ArrayList<>();
        for (CompletableFuture<Object> f : futures) {
            try {
                results.add(f.get());
            } catch (Exception e) {
                if (failurePolicy == OnFailurePolicy.THROW) {
                    throw new CompletionException("❌ Task failed", e);
                } else {
                    results.add(null);
                }
            }
        }
        return aggregator.apply(results);
    }
}

