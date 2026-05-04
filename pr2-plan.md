# PR2 — `QuickPerfContext.wrap(...)` API + Spring auto-coverage — Implementation Plan (v2, post-critique)

> **Source.** `threading-fix-pr-sequence.md` §PR2.
> **Branch.** `pr2-quickperf-context-wrap`, off `pr1-spec-defer-per-connection-snapshot` (`1e95100`).
> **History.** v1 of this plan was critiqued in parallel by Opus 4.7
> xhigh and GPT-5.5 xhigh. Four BLOCKERs and several MAJORs were
> identified; this v2 incorporates all of them.

---

## 1. Goal

Programmatic opt-in to attribute SQL on worker threads to the
originating test, plus auto-cover Spring `@Async` so most Spring
users do not need to wrap manually.

Public API:

```java
QuickPerfContext.wrap(executor)            // Executor
QuickPerfContext.wrap(executorService)     // ExecutorService
QuickPerfContext.wrap(scheduledExecutor)   // ScheduledExecutorService
QuickPerfContext.wrap(runnable)            // Runnable
QuickPerfContext.wrap(callable)            // Callable<V>
```

**Capture timing rules (locked in v2):**
- `wrap(Executor*)` returns a wrapper that captures the calling
  thread's state at each `execute(...)` / `submit(...)` /
  `schedule(...)` invocation. The wrapper itself is reusable across
  tests.
- `wrap(Runnable)` / `wrap(Callable)` capture **at construction
  time**. The returned wrapper is one-shot in spirit; reusing it
  across tests preserves the original test's snapshot. Documented as
  such; `QuickPerfContext` Javadoc warns against caching direct task
  wrappers.
- For `scheduleAtFixedRate(...)` / `scheduleWithFixedDelay(...)`,
  capture happens **once at schedule time**, but
  `install`/`run`/`finally restore` runs **per fire** — each
  invocation gets its own install/restore cycle. Cross-test fire
  hazard documented (§6).

**In scope.**
- `core/.../context/` package: SPI + 5 wrappers.
- `sql-annotations`: 2 providers + registry snapshot/install/restore methods.
- `sql-spring4` / `sql-spring5`: `QuickPerfTaskDecorator` for
  `ThreadPoolTaskExecutor` only, auto-injected by
  `QuickPerfProxyBeanPostProcessor`.

**Out of scope (deferred, with documented rationale).**
- `ThreadPoolTaskScheduler.setTaskDecorator(...)` — the method does
  not exist in Spring 4.3.x, 5.0.x, 5.1.x, 5.2.x, 5.3.x. It was
  added in Spring 6. Auto-cover requires `sql-spring6` (does not
  exist). `@Scheduled` users on the supported Spring versions wrap
  the underlying `ScheduledExecutorService` manually via
  `QuickPerfContext.wrap(...)`.
- Spring 3 (`junit4-spring3`) — no `TaskDecorator` (added in 4.3).
- Tomcat NIO worker (`CrossTestContaminationWithConcurrentRandomPort`)
  — not a Spring bean, deferred to PR6+.
- Reactive / R2DBC — PR6 / PR7.

---

## 2. Architecture

### 2.1 SPI (revised — opaque Snapshot)

`core` cannot depend on `sql-annotations`, so we define the SPI in
`core` and let `sql-annotations` register providers via
`META-INF/services`.

```java
package org.quickperf.context;

public interface ContextSnapshotProvider {
    /**
     * Capture the current thread's QuickPerf state.
     * Returns {@code null} if there is no state to propagate.
     * MUST be thread-safe.
     */
    Snapshot capture();
}

public interface Snapshot {
    /**
     * Install this snapshot on the calling (worker) thread.
     * Returns an opaque token capturing the worker's previous state;
     * the token MUST be passed to {@link #restore(Object)} in a
     * finally block.
     */
    Object install();

    /** Restore the worker thread to {@code previous} state. */
    void restore(Object previous);
}
```

Why opaque `Snapshot` instead of `Object capture/install/restore`:
- No parallel-array bookkeeping in `QuickPerfContext` (one
  `Snapshot` per provider, each holds its own state).
- PR4 (Quarkus interceptor) and PR5 (Micronaut listener) can call
  `Snapshot s = provider.capture(); Object prev = s.install();
  try { ... } finally { s.restore(prev); }` directly without
  going through `QuickPerfContext.wrap(...)`. The API is reusable
  outside of `wrap`.

`QuickPerfContext` discovers providers once at class init via
`ServiceLoader.load(ContextSnapshotProvider.class,
QuickPerfContext.class.getClassLoader())` (deliberate classloader
choice — avoids Spring Boot devtools restart-loader anomalies and
OSGi class-loader confusion). `ServiceConfigurationError` per
provider is caught and logged; one bad provider does not brick
the API.

**Empty snapshot is a no-op.** If `capture()` returns `null`,
the wrapper does not include that provider in its install/restore
cycle. If *all* providers return `null`, `wrap(...)` returns the
input task / executor wrapper that just delegates without capturing
(important: even an "all empty" wrapper still re-checks providers
on each `execute` because the calling thread may have registered
recorders by then). Specifically:
- `wrap(Runnable)` / `wrap(Callable)` capture immediately; if all
  snapshots are null at that point, return the original task
  unchanged (zero overhead, no allocations).
- `wrap(Executor*)` always returns a wrapper (the wrapper captures
  per-`execute`, so calling-thread state at `wrap(...)` time is
  irrelevant).

**Critical correctness invariant.** An empty per-thread submap
written into `SqlRecorderRegistry.PER_THREAD_RECORDERS` would
suppress PR1's `ACTIVE_RECORDERS` fallback on the worker (see
`SqlRecorderRegistry.getSqlRecorders` lines 177-181). So when a
provider's `capture()` finds no per-thread entry, it MUST return
`null` (do not install anything on the worker, let PR1's fallback
work). This is a regression test target (§4.2).

### 2.2 Both registries are covered

`SqlRecorderRegistry` and `ConnectionListenerRegistry` have
parallel dual-map structures in PR1. PR2 adds snapshot APIs to
both and registers two providers. Asymmetry would silently break
`@ExpectNoConnectionLeak` on `@Async`.

### 2.3 Provider ordering

Iterate providers in `ServiceLoader` order on capture and install;
restore in reverse order. This is cheap insurance — none of today's
providers have inter-provider state — but matches LIFO try/finally
discipline elsewhere in the codebase. Provider `restore` failures
are caught, logged, and do not stop other providers from
restoring. The original task exception (if any) is preserved and
re-thrown after all restores complete.

### 2.4 Spring auto-coverage

We auto-cover only `ThreadPoolTaskExecutor` (the bean type used by
`@Async` and most `TaskExecutor` injections). `ThreadPoolTaskScheduler`
is excluded — see "out of scope" §1.

We provide:

- `QuickPerfTaskDecorator` — `implements TaskDecorator`, delegates
  `decorate(r)` to `QuickPerfContext.wrap(r)`.
- `QuickPerfComposingTaskDecorator extends QuickPerfTaskDecorator` —
  used when a user already configured a `TaskDecorator`. It
  composes by running QuickPerf's snapshot capture/install
  *outside* the user decorator (so SQL attribution observes
  whatever thread-local state the user decorator restores).

`QuickPerfProxyBeanPostProcessor.postProcessAfterInitialization`
detects `ThreadPoolTaskExecutor` beans and:

1. Reads the existing `taskDecorator` via reflection on the
   private field (`ThreadPoolTaskExecutor.taskDecorator`,
   `setAccessible(true)` once per BPP). Spring 4.3 / 5.1 do not
   expose a public getter.
2. If existing is `null`: `setTaskDecorator(new
   QuickPerfTaskDecorator())`.
3. If existing is `instanceof QuickPerfTaskDecorator`: do nothing
   (already covered, BPP re-run is a no-op).
4. Else: `setTaskDecorator(new
   QuickPerfComposingTaskDecorator(existingDecorator))`.

**Idempotency.** Step 3 handles both raw and composing cases
because `QuickPerfComposingTaskDecorator extends
QuickPerfTaskDecorator`. Repeated BPP runs (hierarchical contexts,
context refresh, AOT replay) leave a single layer.

**Reflection failure handling.** If reflective access fails (a
SecurityManager, a future Spring rename), log a warning and
fall back to "always set, accept the override risk" — the user
keeps a working test, and we surface the issue in logs.

---

## 3. Files

### 3.1 `core` (JDK 1.7)

| File | Status | Responsibility |
|---|---|---|
| `core/.../context/ContextSnapshotProvider.java` | **NEW** | SPI interface (`Snapshot capture()`). |
| `core/.../context/Snapshot.java` | **NEW** | Inner SPI: `Object install()`, `void restore(Object)`. |
| `core/.../context/QuickPerfContext.java` | **NEW** | Public API. Static `wrap(...)` overloads + classloader-pinned provider list. Throws `NullPointerException` on null input (`Objects.requireNonNull` is JDK 1.7-safe). |
| `core/.../context/QuickPerfRunnable.java` | **NEW** | Wraps `Runnable`. Captures on construct, install/restore in `run()`. After run, sets snapshot list to null (Opus #11 leak-mitigation). |
| `core/.../context/QuickPerfCallable.java` | **NEW** | Same shape for `Callable<V>`. |
| `core/.../context/QuickPerfExecutor.java` | **NEW** | Implements `Executor`. `execute(r)` does `wrap(r)` then forwards. |
| `core/.../context/QuickPerfExecutorService.java` | **NEW** | Implements `ExecutorService`. **Methods enumerated in §3.1.1.** |
| `core/.../context/QuickPerfScheduledExecutorService.java` | **NEW** | Implements `ScheduledExecutorService`. **Methods enumerated in §3.1.2.** |

#### 3.1.1 `QuickPerfExecutorService` method coverage

Wrap-the-task methods (each must wrap before forwarding):

| Method | Action |
|---|---|
| `void execute(Runnable r)` | `delegate.execute(wrap(r))` |
| `Future<?> submit(Runnable r)` | `delegate.submit(wrap(r))` |
| `<T> Future<T> submit(Runnable r, T result)` | `delegate.submit(wrap(r), result)` |
| `<T> Future<T> submit(Callable<T> c)` | `delegate.submit(wrap(c))` |
| `<T> List<Future<T>> invokeAll(Collection<? extends Callable<T>>)` | wrap each callable, forward |
| `<T> List<Future<T>> invokeAll(Collection<? extends Callable<T>>, long, TimeUnit)` | same |
| `<T> T invokeAny(Collection<? extends Callable<T>>)` | wrap each, forward |
| `<T> T invokeAny(Collection<? extends Callable<T>>, long, TimeUnit)` | same |

Pure-delegation lifecycle methods:

| Method | Action |
|---|---|
| `shutdown()` | delegate |
| `shutdownNow()` | delegate; **returns wrapped Runnables** (documented; tests assert this is acceptable for the contract) |
| `isShutdown()` / `isTerminated()` | delegate |
| `awaitTermination(long, TimeUnit)` | delegate |

#### 3.1.2 `QuickPerfScheduledExecutorService` additions

In addition to all `ExecutorService` methods (delegated to a
`QuickPerfExecutorService`-equivalent or composed):

| Method | Action |
|---|---|
| `ScheduledFuture<?> schedule(Runnable, long, TimeUnit)` | wrap once, forward |
| `<V> ScheduledFuture<V> schedule(Callable<V>, long, TimeUnit)` | wrap once, forward |
| `ScheduledFuture<?> scheduleAtFixedRate(Runnable, long, long, TimeUnit)` | capture **once at schedule time**, build a Runnable that on each fire installs/runs/restores (per-fire reentrancy) |
| `ScheduledFuture<?> scheduleWithFixedDelay(Runnable, long, long, TimeUnit)` | same |

The recurring case is implemented via a small inner class
`RecurringQuickPerfRunnable` that holds `List<Snapshot>` (captured
once) and runs install/restore on each `run()`.

### 3.2 `sql/sql-annotations` (JDK 1.7)

| File | Status | Responsibility |
|---|---|---|
| `sql/sql-annotations/.../SqlRecorderRegistry.java` | **MODIFY** | Add `Map<Class<? extends SqlRecorder>, SqlRecorder> snapshotForCurrentThread()` (returns defensive copy of the per-thread submap, or `Collections.emptyMap()` if absent — the empty case is what the provider treats as "no snapshot"). Add `Object installSnapshot(Map)` and `void restoreSnapshot(Object previous)`. **Empty input map → no-op install** (returns a sentinel "no-op" token; `restoreSnapshot` recognizes it and does nothing). Forked-JVM mode (`TEST_CODE_EXECUTING_IN_NEW_JVM`) → all three methods are no-ops. |
| `sql/sql-annotations/.../connection/ConnectionListenerRegistry.java` | **MODIFY** | Same three methods against connection-listener map. |
| `sql/sql-annotations/.../sql/SqlRecorderContextSnapshotProvider.java` | **NEW** | `capture()` returns `null` if `snapshotForCurrentThread()` is empty; else returns a `Snapshot` whose `install()` calls `installSnapshot(map)` and `restore(prev)` calls `restoreSnapshot(prev)`. |
| `sql/sql-annotations/.../connection/ConnectionListenerContextSnapshotProvider.java` | **NEW** | Mirror. |
| `sql/sql-annotations/src/main/resources/META-INF/services/org.quickperf.context.ContextSnapshotProvider` | **NEW** | Two FQCN lines. |

### 3.3 `spring/sql-spring4` (JDK 1.7, Spring 4.3.22)

| File | Status | Responsibility |
|---|---|---|
| `spring/sql-spring4/.../sql/QuickPerfTaskDecorator.java` | **NEW** | `class QuickPerfTaskDecorator implements TaskDecorator`. `decorate(r)` returns `QuickPerfContext.wrap(r)`. |
| `spring/sql-spring4/.../sql/QuickPerfComposingTaskDecorator.java` | **NEW** | `class QuickPerfComposingTaskDecorator extends QuickPerfTaskDecorator`. Holds a delegate `TaskDecorator`. `decorate(r)` returns `super.decorate(delegate.decorate(r))` (QuickPerf outermost). |
| `spring/sql-spring4/.../sql/QuickPerfProxyBeanPostProcessor.java` | **MODIFY** | In `postProcessAfterInitialization`, after the existing datasource-proxy logic, branch on `bean instanceof ThreadPoolTaskExecutor`. Use reflection to read the private `taskDecorator` field. Apply rules from §2.4 (set raw / no-op / set composing). Reflection failures logged, fall back to "set raw decorator" with a warning. |

JDK 1.7 source: the composing decorator uses an anonymous-inner-class
delegation pattern (no lambdas). Plan §3.3 in v1 used a lambda by
mistake — this v2 fixes it.

### 3.4 `spring/sql-spring5` (JDK 1.8, Spring 5.1.8)

Same three files as §3.3, JDK 1.8 source allowed.

---

## 4. Tests

All tests use JUnit 4 + AssertJ (the project standard). Tests must
be safe under Surefire `parallel=all, threadCount=5`.

### 4.1 `core` tests

| Test | Asserts |
|---|---|
| `QuickPerfContextNullInputTest` | `wrap(null)` for each overload throws `NullPointerException` with a clear message. |
| `QuickPerfContextWithNoProvidersTest` | When no SPI registration is on the **core test classpath** (verified by the absence of any `META-INF/services/org.quickperf.context.ContextSnapshotProvider` file), `wrap(Runnable)` and `wrap(Callable)` return the input unchanged. `wrap(Executor)` returns a wrapper that delegates without capture overhead. |
| `QuickPerfContextStubProviderTest` | A `RecordingStubProvider` is registered via `META-INF/services` **on the test classpath of a small dedicated test resource directory** — to avoid contaminating the "no providers" test, the SPI file is placed under `src/test/resources/stub-provider/META-INF/services/...` and the test uses a custom `URLClassLoader` to load it (bypassing the default class loader). Verifies `capture` / `install` / `restore` are called exactly once each, in correct order, for a `Runnable`. |
| `QuickPerfContextOrderingTest` | Two stub providers: capture+install in registration order, restore in reverse. |
| `QuickPerfContextRestoreOnExceptionTest` | Wrapped `Runnable` throws `RuntimeException` → all `restore`s called, exception re-propagates. |
| `QuickPerfContextRestoreOnThrowableTest` | Wrapped `Runnable` throws `Error` → all `restore`s still called. |
| `QuickPerfContextProviderRestoreFailureTest` | One provider throws on `restore` → other providers still restore; original task exception (if any) is the one that re-propagates; provider failure is logged but suppressed. |
| `QuickPerfContextEmptyCaptureNoOpTest` | All providers return `null` from `capture()` → `wrap(Runnable r)` returns `r` itself (`==` equality). |
| `QuickPerfExecutorServiceMethodsTest` | Parametrized over: `execute`, `submit(Runnable)`, `submit(Runnable, T)`, `submit(Callable)`, `invokeAll`, `invokeAll(timeout)`, `invokeAny`, `invokeAny(timeout)`. Each variant asserts the stub provider observed exactly one capture/install/restore per task. |
| `QuickPerfExecutorServiceShutdownNowTest` | Tasks queued but not yet started; `shutdownNow()` returns wrapped Runnables (documented contract). |
| `QuickPerfExecutorServiceRejectedExecutionTest` | Saturated executor + `submit(...)` → `RejectedExecutionException` propagates; no captured snapshot leaks. |
| `QuickPerfScheduledExecutorServiceTest` | All four `schedule*` overloads are wrapped. For `scheduleAtFixedRate`, three fires → three install/restore cycles (asserted via stub-provider counts), with the same captured Snapshot reused across fires. |

### 4.2 `sql-annotations` tests

| Test | Asserts |
|---|---|
| `SqlRecorderRegistrySnapshotTest` | `snapshotForCurrentThread()` returns `Collections.emptyMap()` on a thread with no entry (assert equality, not identity); returns the entries for a thread with a registered recorder; defensive copy (mutating the snapshot does not mutate the registry's internal map). |
| `SqlRecorderRegistryInstallRestoreTest` | `installSnapshot(emptyMap)` is a no-op and returns a sentinel; `installSnapshot(nonEmptyMap)` populates `PER_THREAD_RECORDERS` for the calling thread and returns a token; `restoreSnapshot(token)` reverts to the prior state (absent → removed; present → restored). Test all 4 transition cases: absent→non-empty→absent, absent→empty(no-op)→absent, non-empty→non-empty (nested), empty(no-op)→non-empty→empty. |
| `SqlRecorderRegistryEmptySnapshotPreservesActiveFallbackTest` | **Critical regression test for Opus BLOCKER #2.** Worker thread starts with no per-thread entry. Test thread A registers recorder X. Worker calls `installSnapshot(emptyMap)` (e.g., wrapping was used but capture happened on a thread with no recorders). Worker calls `getSqlRecorders()`. Assert: returns recorder X via `ACTIVE_RECORDERS` fallback (not empty list). |
| `SqlRecorderContextSnapshotProviderTest` | Provider's `capture` returns `null` when calling thread has no entry; returns a working `Snapshot` when entry is non-empty; full round-trip via `Snapshot.install()` + `restore()`. |
| `ConnectionListenerRegistrySnapshotTest` | Mirror. |
| `ConnectionListenerContextSnapshotProviderTest` | Mirror. |
| `QuickPerfContextWorkerThreadAttributionTest` | High-value integration. Test thread registers `PersistenceSqlRecorder` with owner tid X. Submits a `Runnable` via `wrap(executor)` to a 1-thread executor. Worker fires SQL. Assertions: (a) recorder observed exactly that SQL; (b) `SqlRecorderRegistry`'s contamination flag is **not** set for that recorder (this is the explicit "wrap suppresses the contamination warning" guarantee per Opus #9 / GPT #16). |
| `QuickPerfContextNoContaminationWithTwoTestsTest` | Two simulated tests register recorders A and B on threads tA, tB. Both wrap their executors. Both fire SQL on workers. Assert recorders A and B each see only their own SQL, neither has the contamination flag. |

### 4.3 Spring auto-coverage

| Test | Module | Asserts |
|---|---|---|
| `QuickPerfTaskDecoratorTest` | `sql-spring5/src/test/...` | Pure unit. `decorate(r)` calls back through `QuickPerfContext.wrap(r)` (verifiable via stub provider). |
| `QuickPerfComposingTaskDecoratorTest` | same | `decorate(r)` calls user delegate's `decorate(...)` first, then QuickPerf's wrap is the outermost layer (assert via order-checking stubs). |
| `BeanPostProcessorAutoTaskDecoratorTest` | same | Spring `AnnotationConfigApplicationContext` with a `ThreadPoolTaskExecutor` bean and the BPP. After refresh: `executor.taskDecorator` (read via reflection) is `instanceof QuickPerfTaskDecorator`. |
| `BeanPostProcessorComposesUserTaskDecoratorTest` | same | Bean with pre-configured user `TaskDecorator`. After refresh: `executor.taskDecorator` is `instanceof QuickPerfComposingTaskDecorator` and its delegate is the user's. |
| `BeanPostProcessorIdempotencyTest` | same | Run BPP twice on same bean. After: still exactly one layer of QuickPerf wrapping. |

`sql-spring4` repeats the four tests above (sharing fixtures via
copy is acceptable for PR2 — the alternative is touching
`junit4-spring-base-tests` which is out of scope).

### 4.4 Acceptance (per spec §PR2)

| Scenario | Status after PR2 |
|---|---|
| `ConcurrentTestsWithSharedExecutor` with `QuickPerfContext.wrap(SHARED_EXECUTOR)` | ✅ |
| `CrossTestContaminationWithConcurrentAsync` (Spring `@Async` via auto-injected TaskDecorator) | ✅ |
| `CrossTestContaminationWithConcurrentRandomPort` (Tomcat) | ⚠ unchanged (PR6+) |
| `@Scheduled` cross-test attribution (Spring 4/5) | manual `wrap(scheduledExecutor)` only; auto-coverage deferred to a future `sql-spring6` |

---

## 5. Commit breakdown

Each commit ships its tests with its impl; each commit compiles
and tests green standalone.

1. **`core` SPI + `QuickPerfContext` + 5 wrappers**
   - `ContextSnapshotProvider`, `Snapshot` interfaces
   - `QuickPerfContext` (5 `wrap(...)` overloads)
   - `QuickPerfRunnable`, `QuickPerfCallable`,
     `QuickPerfExecutor`, `QuickPerfExecutorService`,
     `QuickPerfScheduledExecutorService`
   - All §4.1 tests
   - **Note:** `QuickPerfContextWithNoProvidersTest` is reliable
     here because no `META-INF/services` registration exists in
     the core test classpath at this commit.

2. **`SqlRecorderRegistry` + sql provider**
   - 3 methods on `SqlRecorderRegistry` (with empty-snapshot no-op)
   - `SqlRecorderContextSnapshotProvider`
   - `META-INF/services/org.quickperf.context.ContextSnapshotProvider`
     (just the SQL FQCN at this point)
   - §4.2 SQL tests including `EmptySnapshotPreservesActiveFallbackTest`,
     `WorkerThreadAttributionTest`, and contamination-flag assertions

3. **`ConnectionListenerRegistry` + connection-listener provider**
   - 3 methods on `ConnectionListenerRegistry`
   - `ConnectionListenerContextSnapshotProvider`
   - Append the FQCN to the SPI file
   - §4.2 connection-listener mirror tests

4. **Spring 4 `TaskDecorator` + BPP integration**
   - `QuickPerfTaskDecorator`, `QuickPerfComposingTaskDecorator`
   - BPP modification (reflection-based detection of existing
     decorator)
   - §4.3 four tests

5. **Spring 5 `TaskDecorator` + BPP integration**
   - Same as #4 in `sql-spring5`

After commit 1, `QuickPerfContext.wrap(...)` is a no-op (no
providers registered) — this is intentional and matches Opus #14's
"bisectability gap" note. Users should not rely on the public API
until commit 2 is merged.

---

## 6. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Empty snapshot installed on worker masks PR1 fallback | `capture()` returns `null` for empty state; wrapper omits the no-op provider. Locked in by `EmptySnapshotPreservesActiveFallbackTest`. |
| `ServiceConfigurationError` from a misconfigured downstream module | Per-provider try/catch in the static loader. Bad provider → log + skip. |
| User's pre-wired `TaskDecorator` overridden | Compose: ours wraps theirs, both are `instanceof QuickPerfTaskDecorator`. |
| BPP runs twice, double-wraps | `instanceof QuickPerfTaskDecorator` check covers both raw and composing. |
| Long-lived wrapper pinning a recorder past test teardown | `QuickPerfRunnable.run()` nulls the snapshot list field in `finally` after restore, so a queued-but-completed wrapper doesn't pin recorders. Document also: don't cache `wrap(Runnable)` direct task wrappers across tests. |
| `unregister(...)` between capture and worker run | Existing PR1 behavior: late SQL is recorded into the unregistered recorder's `SqlMemoryRepository` (the recorder is still reachable through the snapshot). Documented as known limitation. |
| `register(...)` from inside wrapped task | Documented as unsupported. The new recorder is dropped from `PER_THREAD_RECORDERS` on `restore` but remains in `ACTIVE_RECORDERS`. Tests assert the documented behavior, not aspirational behavior. |
| Recurring scheduled task whose lifespan exceeds the originating test | Captured snapshot is reused across all fires. Late fires write to a recorder that has been unregistered and whose results have already been read. Document: cancel long-period recurrences in test teardown. |
| `setTaskDecorator` reflection fails on a future Spring rename or under SecurityManager | Log warning, fall back to `setTaskDecorator(new QuickPerfTaskDecorator())` (overrides any pre-existing decorator). Test by running with no SecurityManager (the realistic case) and asserting the warning fires only when reflection genuinely fails. |
| Multi-classloader environments (devtools, OSGi) | `ServiceLoader.load(svc, QuickPerfContext.class.getClassLoader())` is deterministic but does not see app-restart-loader providers. Documented. Future enhancement (out of scope) could add per-thread classloader resolution. |

---

## 7. Validation order

```
mvn -pl core -am test -Dtest=QuickPerfContext*Test
mvn -pl sql/sql-annotations -am test -Dtest=*Snapshot*,*ContextSnapshot*,QuickPerfContextWorkerThread*,QuickPerfContextNoContamination*
mvn -pl spring/sql-spring4 -am test -Dtest=QuickPerfTaskDecorator*,QuickPerfComposingTaskDecorator*,BeanPostProcessor*TaskDecorator*
mvn -pl spring/sql-spring5 -am test -Dtest=QuickPerfTaskDecorator*,QuickPerfComposingTaskDecorator*,BeanPostProcessor*TaskDecorator*
mvn clean install
```

---

## 8. Open questions resolved (formerly v1 §8)

| Question | Resolution |
|---|---|
| Capture at construction vs submit time | Construction time for `wrap(Runnable/Callable)`; per-`execute` for `wrap(Executor*)`; per-fire install/restore (capture-once at schedule) for recurring scheduled tasks. |
| Compose order with user `TaskDecorator` | QuickPerf outermost — the user decorator runs inside our snapshot scope. |
| `Thread` constructor wrap | Out of scope (spec doesn't require). |
| `wrap(null)` behavior | NPE via `Objects.requireNonNull`. |
| `shutdownNow()` returning wrapped Runnables | Documented behavior; tests assert it. |
