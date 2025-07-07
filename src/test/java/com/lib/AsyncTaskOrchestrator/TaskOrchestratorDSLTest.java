package com.lib.AsyncTaskOrchestrator;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import com.lib.AsyncTaskOrchestrator.constant.OnFailurePolicy;
import com.lib.AsyncTaskOrchestrator.handler.TaskOrchestratorDSL;
import org.junit.jupiter.api.Test;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TaskOrchestratorDSLTest {

    // ====================== MOCK METHODS ======================

    private CompletableFuture<String> mockPreCondition(String token) {
        return CompletableFuture.completedFuture("CTX_" + token);
    }

    private CompletableFuture<String> mockUserTask(String ctx) {
        return CompletableFuture.completedFuture("User-" + ctx);
    }

    private CompletableFuture<String> mockUserNoCtx() {
        return CompletableFuture.completedFuture("User1-");
    }

    private CompletableFuture<Integer> mockAgeTask(String ctx) {
        return CompletableFuture.completedFuture(30);
    }

    private CompletableFuture<Integer> mockAgeNoCtx() {
        return CompletableFuture.completedFuture(25);
    }

    private CompletableFuture<Boolean> mockStatusTask(String ctx) {
        return CompletableFuture.completedFuture(true);
    }

    // ====================== TEST CASES ======================

    /**
     *
     * ✅ Success flow with pre-condition and context shared across tasks.
     */
    @Test
    void should_execute_all_tasks_with_precondition_successfully() {
        var orchestrator = TaskOrchestratorDSL.<String, String>builder()
                .withParam("abc")
                .preCondition(this::mockPreCondition)
                .onFailure(OnFailurePolicy.THROW)
                .addTask("User", this::mockUserTask, String.class)
                .addTask("Age", this::mockAgeTask, Integer.class)
                .addTask("Status", this::mockStatusTask, Boolean.class)
                .aggregate(results -> {
                    assertEquals(3, results.size());
                    return new Demo((String) results.get(0), (Integer) results.get(1), (Boolean) results.get(2));
                });

        Demo result = (Demo) orchestrator.execute().join();

        assertTrue(result.name.contains("User"));
    }

    /**
     *
     * ✅ Success flow without pre-condition (param acts as context).
     */
    @Test
    void should_execute_all_tasks_without_precondition() {
        var orchestrator = TaskOrchestratorDSL.<String, String>builder()
                .withParam("abc")
                .onFailure(OnFailurePolicy.THROW)
                .addTask("User", this::mockUserNoCtx, String.class)
                .addTask("Age", this::mockAgeNoCtx, Integer.class)
                .addTask("Status", this::mockStatusTask, Boolean.class)
                .aggregate(results -> {
                    assertEquals(3, results.size());
                    return new Demo((String) results.get(0), (Integer) results.get(1), (Boolean) results.get(2));
                });

        Demo result = (Demo) orchestrator.execute().join();

        assertTrue(result.name.contains("User1"));
        assertEquals(25, result.age);
    }

    /**
     *
     * ✅ Retry succeeds after one failure.
     */
    @Test
    void should_retry_task_and_succeed_on_second_attempt() {
        AtomicInteger attemptCounter = new AtomicInteger(0);

        Function<String, CompletableFuture<String>> flakyTask = ctx -> {
            if (attemptCounter.getAndIncrement() < 2) {
                return CompletableFuture.failedFuture(new RuntimeException("First attempt failed"));
            }
            return CompletableFuture.completedFuture("Recovered-" + ctx);
        };

        var orchestrator = TaskOrchestratorDSL.<String, String>builder()
                .withParam("token")
                .preCondition(this::mockPreCondition)
                .retry(4)
                .retryIf(t -> t.getCause() instanceof RuntimeException)
                .onFailure(OnFailurePolicy.SKIP)
                .addTask("Flaky", flakyTask, String.class)
                .aggregate(results -> results.get(0));

        String result = (String) orchestrator.execute().join();

        assertEquals("Recovered-CTX_token", result);
    }

    /**
     *
     * ❌ Retry exhausted and failure policy is THROW -> should throw.
     */
    @Test
    void should_throw_when_all_retries_fail_and_policy_is_throw() {
        Function<String, CompletableFuture<String>> alwaysFail =
                ctx -> CompletableFuture.failedFuture(new RuntimeException("Fail always"));

        var orchestrator = TaskOrchestratorDSL.<String, String>builder()
                .withParam("abc")
                .preCondition(this::mockPreCondition)
                .retry(2)
                .retryIf(t -> t.getCause() instanceof RuntimeException)
                .onFailure(OnFailurePolicy.THROW)
                .addTask("AlwaysFail", alwaysFail, String.class)
                .aggregate(results -> "Should not reach");

        assertThrows(CompletionException.class, () -> orchestrator.execute().join());
    }

    /**
     *
     * ⚠️ Retry exhausted and failure policy is SKIP -> return null.
     */
    @Test
    void should_return_null_when_all_retries_fail_and_policy_is_skip() {
        Function<String, CompletableFuture<String>> alwaysFail =
                ctx -> CompletableFuture.failedFuture(new RuntimeException("Still fail"));

        var orchestrator = TaskOrchestratorDSL.<String, String>builder()
                .withParam("abc")
                .preCondition(this::mockPreCondition)
                .retry(2)
                .retryIf(t -> t.getCause() instanceof RuntimeException)
                .onFailure(OnFailurePolicy.SKIP)
                .addTask("AlwaysFail", alwaysFail, String.class)
                .aggregate(results -> {
                    assertEquals(1, results.size());
                    return results.get(0);
                });

        Object result = orchestrator.execute().join();

        assertNull(result);
    }

    /**
     *
     * 🚫 Pre-condition fails → throw immediately (skip all tasks).
     */
    @Test
    void should_throw_immediately_if_precondition_fails() {
        Function<String, CompletableFuture<String>> failPre =
                token -> CompletableFuture.failedFuture(new RuntimeException("Unauthorized"));

        var orchestrator = TaskOrchestratorDSL.<String, String>builder()
                .withParam("abc")
                .preCondition(failPre)
                .retry(2)
                .retryIf(t -> t.getCause() instanceof RuntimeException)
                .onFailure(OnFailurePolicy.THROW)
                .addTask("User", this::mockUserTask, String.class)
                .aggregate(results -> "Should not reach");

        assertThrows(CompletionException.class, () -> orchestrator.execute().join());
    }

    /**
     *
     * ⏰ Retry due to TimeoutException and fail after max retries.
     */
    @Test
    void should_retry_on_timeout_and_throw_after_exhausted() {
        AtomicInteger attempt = new AtomicInteger(0);

        Function<String, CompletableFuture<String>> timeoutTask = ctx -> {
            int i = attempt.incrementAndGet();
            log.info("Mock task attempt {}", i);
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(2000);
                    return "Should timeout";
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        };

        var orchestrator = TaskOrchestratorDSL.<String, String>builder()
                .withParam("abc")
                .preCondition(this::mockPreCondition)
                .retry(3)
                .retryIf(TimeoutException.class::isInstance)
                .onFailure(OnFailurePolicy.THROW)
                .addTask("TimeoutTask", timeoutTask, String.class, Duration.ofMillis(300))
                .aggregate(results -> "Should not reach");

        CompletionException ex = assertThrows(
                CompletionException.class, () -> orchestrator.execute().join());
        assertTrue(ex.getCause() instanceof TimeoutException);
        assertEquals(3, attempt.get());
    }

    /**
     *
     * ✅ Test for addTask using Supplier (no context needed).
     */
    @Test
    void should_execute_supplier_task_without_context() {
        Supplier<CompletableFuture<String>> supplier = () -> CompletableFuture.completedFuture("NoCtx");

        var orchestrator = TaskOrchestratorDSL.<Void, Void>builder()
                .addTask("NoCtxTask", supplier, String.class)
                .aggregate(results -> {
                    assertEquals(1, results.size());
                    return results.get(0);
                });

        String result = (String) orchestrator.execute().join();
        assertEquals("NoCtx", result);
    }

    /**
     *
     * ⏱️ Retry only if exception message contains "timeout" (custom retry condition).
     */
    @Test
    void should_retry_only_if_message_contains_timeout() {
        AtomicInteger attempt = new AtomicInteger(0);

        Function<String, CompletableFuture<String>> taskWithMessageError = ctx -> {
            int count = attempt.incrementAndGet();
            if (count < 3) {
                return CompletableFuture.failedFuture(new TimeoutException("this is a timeout error"));
            }
            return CompletableFuture.completedFuture("Recovered");
        };

        var orchestrator = TaskOrchestratorDSL.<String, String>builder()
                .withParam("abc")
                .preCondition(this::mockPreCondition)
                .retry(3)
                .retryIf(t -> t.getCause() instanceof TimeoutException)
                .onFailure(OnFailurePolicy.THROW)
                .addTask("MessageErrorTask", taskWithMessageError, String.class)
                .aggregate(results -> results.get(0));

        String result = (String) orchestrator.execute().join();

        assertEquals("Recovered", result);
        assertEquals(3, attempt.get());
    }

    /**
     *
     * ❌ If aggregator is missing, should throw IllegalStateException.
     */
    @Test
    void should_throw_if_aggregator_is_missing() {
        var orchestrator = TaskOrchestratorDSL.<String, String>builder()
                .withParam("abc")
                .preCondition(this::mockPreCondition)
                .addTask("User", this::mockUserTask, String.class);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> orchestrator.execute().join());
        assertEquals("Missing aggregator function.", ex.getMessage());
    }

    /**
     * ✅ Data holder for test aggregation result.
     */
    private record Demo(String name, int age, boolean status) {}
}
