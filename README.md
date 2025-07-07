# TaskOrchestratorDSL

## ✨ Overview

`TaskOrchestratorDSL` is a lightweight, fluent Java DSL (Domain-Specific Language) that allows you to compose and orchestrate multiple asynchronous tasks with full support for:

- ✔ Virtual thread execution (JDK21+)
- ✔ Optional pre-condition/context initializer
- ✔ Retry and timeout per task
- ✔ Custom failure handling (`THROW` or `SKIP`)
- ✔ Aggregation of final results into a POJO

This library is designed for **backend developers** to simplify complex workflows that involve calling multiple external/internal services, especially in microservice architectures.

---

## ⚡ Use Cases

- Coordinating multiple remote service calls (e.g. fetch user info, validate payment, get status)
- Executing non-blocking logic in **parallel** using **virtual threads**
- Resilient workflow orchestration with retry/timeout policies

---

## 📈 Architecture Diagram

```
+--------------------------+
|     Optional Param       |
|         (P)              |
+--------------------------+
              |
              v
+--------------------------+
|     Pre-condition Fn     |
|   P -> CompletableFuture<C>   |
+--------------------------+
              |
              v
+--------------------------+
|      Context (C)         |
+--------------------------+
              |
              v
+-----------------------------------------------+
|                 Asynchronous Tasks             |
|  [Task1(C) -> CompletableFuture<T1>]           |
|  [Task2(C) -> CompletableFuture<T2>]           |
|  [Task3(C) -> CompletableFuture<T3>]           |
+-----------------------------------------------+
              |
              v
+--------------------------+
|      Aggregator Fn       |
|    List<Object> -> R     |
+--------------------------+
              |
              v
+--------------------------+
|  Final Result (POJO R)   |
+--------------------------+
```

---

## ✍ How to Use

```java
TaskOrchestratorDSL.<String, String>builder()
    .withParam("token") // Optional input to preCondition
    .preCondition(token -> CompletableFuture.completedFuture("CTX_" + token))
    .retry(2)
    .retryIf(t -> t instanceof TimeoutException)
    .onFailure(OnFailurePolicy.THROW)
    .addTask("User", ctx -> callUserService(ctx), String.class)
    .addTask("Age", ctx -> callAgeService(ctx), Integer.class)
    .addTask("Status", ctx -> callStatusService(ctx), Boolean.class)
    .aggregate(results -> new Demo((String) results.get(0), (Integer) results.get(1), (Boolean) results.get(2)))
    .execute()
    .join();
```

---

## 📌 API Reference

### Builder

| Method                                    | Description                                         |
| ----------------------------------------- | --------------------------------------------------- |
| `withParam(P param)`                      | Sets optional input param                           |
| `preCondition(P -> CompletableFuture<C>)` | Optional context initialization                     |
| `retry(int times)`                        | Sets number of retries per task                     |
| `retryIf(Predicate<Throwable>)`           | Only retry when condition met                       |
| `onFailure(THROW or SKIP)`                | THROW = stop pipeline, SKIP = null result           |
| `addTask(...)`                            | Add async task using context or supplier            |
| `aggregate(List<Object> -> R)`            | Aggregates result list into POJO                    |
| `execute()`                               | Starts execution and returns `CompletableFuture<R>` |

---

## 🌐 Threading Model

All tasks are executed on **Virtual Threads** (JDK 21+):

- Each task is run independently in parallel.
- Timeout and retry policies are handled per task.
- Failures are logged and retried accordingly.

---

## 🚀 Example Output

```java
record Demo(String name, int age, boolean status) {}
```

```text
Result: Demo[name=User-CTX_token, age=30, status=true]
```

---

## ✉ Notes

- If no `preCondition()` is provided, the `param` becomes the context (`C`)
- `retry()` works only when `retryIf()` condition is satisfied
- All task results are collected in order of registration
- `aggregate()` is **required** to finalize result

---


## 🏆 Credits

Developed with ❤️ by the Quang

---

