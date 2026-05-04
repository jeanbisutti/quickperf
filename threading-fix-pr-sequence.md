# QuickPerf — Threading Fix: Recommended PR Sequence

> Companion to `threading-fix-options-evaluation.md`,
> `threading-fix-options-cross-framework.md`, and
> `threading-fix-quarkus-micronaut-evaluation.md`.
>
> This document defines the **shipping plan**: which work to bundle into
> which PR, in which order, and why — with the constraint that future
> Quarkus and Micronaut support must be unblocked but not pre-implemented.

---

## TL;DR

| Release | PRs | Headline change | User-visible effect |
|---|---|---|---|
| **v1** | PR1 + PR2 | Dual-map registry foundation + universal `wrap()` opt-in API + soft warning on contamination | Single-test threading scenarios pass. Parallel-shared tests warn (not fail). Users have a one-line fix. |
| **v2** | PR3 | Tighten contamination warning into a forced failure | Prime-directive guarantee: never claim a wrong count silently. |
| **v3** | PR4 | New `quickperf-quarkus` extension (deployment + runtime) | Zero-config Quarkus support, native-image safe. |
| **v4** | PR5 | New `quickperf-micronaut` module | Zero-config Micronaut support, AOT safe. |
| **v5+** | PR6 / PR7 (optional) | `quickperf-reactive` (Micrometer + MP CP) and `quickperf-r2dbc` | Reactive paths and Spring WebFlux + R2DBC users covered. |

The two **architectural commitments** in PR1 are non-negotiable because every later PR depends on them:

1. **Drop `InheritableThreadLocal`** in `SqlRecorderRegistry` and `ConnectionListenerRegistry`. Use plain `ThreadLocal` (or `ConcurrentHashMap<Long, …>` keyed by `Thread.getId()` à la `inheritable-threadlocal-fix-analysis.txt`).
2. **Dual-map registry with active-set fallback + cross-test contamination flag.** The listener consults `SqlRecorderRegistry.getSqlRecorders()`; routing logic lives in the registry, not in the wrapper.

> **Note (2025-11):** the per-`Connection` recorder snapshot originally drafted as a third PR1 commitment has been **deferred to PR4 (Quarkus) and PR5 (Micronaut)**. It cannot be wired through the dsproxy listener at PR1's wrapping layer — `executionInfo.getStatement().getConnection()` returns the *raw* pool `Connection`, one level below `wrapper.delegate` (the dsproxy `ProxyConnection`), so identity-based dispatch from the listener misses 100% of the time. Pool-side hooks (Agroal `PoolInterceptor`, Micronaut `BeanCreatedEventListener`) are the natural place for it because the framework holds the QuickPerf wrapper at pool checkout, before any `Statement` exists. Validated by Claude Opus 4.7 review on 2025-11; full critique in `pr1-per-connection-snapshot-plan.md` §12.

Everything else layers on top of these two and can be re-ordered.

---

## Why this sequence

### Decision matrix considered

| Sequencing option | Disruption to existing passing tests | Honest about wrong counts in v1 | Users have a fix path in v1 | Ships in releases |
|---|---|---|---|---|
| PR1 only first | none | half (warning only on failure) | no | 4+ |
| **PR1 + PR2 first (chosen)** | **none** | **half** | **yes** | **3** |
| PR1 + PR3 (skip PR2) | parallel-shared tests fail | full | no — only avoidance (`@Execution(SAME_THREAD)`) | 3 |
| PR1 + PR2 + PR3 together | parallel-shared tests fail | full | yes | 2 |

**PR1 + PR2 as v1** wins because:

- It's **non-disruptive** — no test that passes today becomes a failure tomorrow.
- It gives users the **`QuickPerfContext.wrap(...)` API** as a remediation tool, so when they upgrade to v2 (drop-and-fail), the migration path already exists in the codebase they're already on.
- It avoids the "release a hard break, then release the fix" cycle (which is what PR1 + PR3 without PR2 produces).
- For Quarkus and Micronaut specifically, all four sequencings are equivalent — those frameworks serialize their own tests, so the parallel-shared loud-fail branch never fires for them. The sequencing call is therefore purely about **Spring users with parallel `@SpringBootTest` + shared context**.

---

## PR1 — Foundation: dual-map routing + broadcast-and-alert (F2 + warning)

**Goal**: replace the `InheritableThreadLocal` attribution mechanism with a dual-map registry whose active-set fallback gives worker threads a chance to find the live recorder, and emit a soft warning when ≥ 2 tests are simultaneously active. Lay the groundwork for PR4/PR5 to install per-`Connection` snapshots later, without committing PR1 to a routing layer it cannot reliably implement.

### Relationship to existing repo proposals

PR1 is **not new design from scratch** — it is a strict superset of two analyses already in the repo:

```
PR1 = dual-map      (inheritable-threadlocal-fix-analysis.txt)
    + alert         (cross-test-sql-contamination-analysis.txt)
    + ConcurrentLinkedDeque   (orthogonal F0 fix)
```

> **Originally drafted as a third PR1 element** — a per-`Connection` recorder snapshot in `QuickPerfDatabaseConnection.buildFrom(...)` — but **deferred to PR4 / PR5**. See the note in §TL;DR and `pr1-per-connection-snapshot-plan.md` §12 for the technical reason (dsproxy returns the raw pool `Connection` at `executionInfo.getStatement().getConnection()`, not `wrapper.delegate`, so the listener-side identity lookup cannot work without either wrapping every `Statement` or hooking pool checkout — both belong in framework PRs, not in PR1).

| Item in PR1 | Source | What it does |
|---|---|---|
| Drop `InheritableThreadLocal` → explicit per-thread map keyed by `Thread.getId()` (or plain `ThreadLocal`) | `inheritable-threadlocal-fix-analysis.txt` (PER_THREAD_RECORDERS) | Replaces ITL semantics; kills the persistent-worker / cross-test stale snapshot bug |
| Process-wide active-recorders set populated in `register` / cleared in `unregister` | `inheritable-threadlocal-fix-analysis.txt` (ALL_ACTIVE_RECORDERS) | Cross-thread visibility for pre-existing workers |
| `getSqlRecorders()` falls back to active-set when per-thread map is empty | `inheritable-threadlocal-fix-analysis.txt` | F2 routing |
| `markPossibleCrossContamination()` flag + warning text appended to assertion errors | `cross-test-sql-contamination-analysis.txt` | Diagnostic alert when contamination detected |
| Mirror change in `ConnectionListenerRegistry` | `inheritable-threadlocal-fix-analysis.txt` (mentions symmetry) | Keeps the two registries from drifting |
| `ConcurrentLinkedDeque` for `SqlExecutions` | F0 — orthogonal data-race fix every variant needs | Makes concurrent `add` safe |

### Why per-`Connection` snapshot is deferred (and not blocking PR1)

The dual-map alone fixes the dominant single-test scenarios via the active-set fallback. A per-`Connection` snapshot would have changed **when that fallback fires** (test thread hands a connection to a worker; worker acquires its own connection while exactly one test is active) by routing directly from the wrapper instead. The synthesized PR1 add-on plan (`pr1-per-connection-snapshot-plan.md`) explored an identity-keyed map keyed on `wrapper.delegate`, but the 2025-11 review (§12 of that document) demonstrated:

1. **Listener-side dispatch cannot work at PR1's wrapping layer.** dsproxy stamps the `ExecutionInfo` with the *raw* `Statement` (not the JDK proxy), so `executionInfo.getStatement().getConnection()` returns the raw pool `Connection` (`HikariConnection`, etc.), one level below `wrapper.delegate` (the dsproxy `ProxyConnection`). The two are never the same object; identity equality fails 100% of the time.
2. **The two viable PR1-internal alternatives** — wrapping every `Statement` returned by `QuickPerfDatabaseConnection.createStatement(...)` / `prepareStatement(...)` / `prepareCall(...)`, or keying by `executionInfo.getConnectionId()` and stamping that ID at vend time — are each ≈ 600 LOC of new surface (and respectively introduce JDBC-overload maintenance debt or couple `sql-annotations` to dsproxy internals). Neither fits PR1's "low-risk additive" profile.
3. **Pool-side hooks are the natural place** for the snapshot. Agroal's `PoolInterceptor.onConnectionAcquire` (PR4) and Micronaut's `BeanCreatedEventListener<DataSource>` (PR5) hold the QuickPerf wrapper at pool checkout, before any `Statement` exists. They can stamp the snapshot directly onto the wrapper and add their own listener-side dispatch in the framework module — without re-touching `sql-annotations` once PR4/PR5 expose a stable extension point.

For Spring users: the dual-map's active-set fallback covers the *single-test-active* case (the dominant case after fixing ITL). The fallback fires for the genuinely-ambiguous parallel case where the warning mechanism is what catches it.

For Quarkus and Micronaut users (where `@QuarkusTest` and `@MicronautTest` serialize tests by default), the active-set is *always* size 1 during a test → the dual-map already routes correctly. The per-`Connection` snapshot becomes a productized improvement for native-image / pool-checkout integration in PR4/PR5, not a PR1 prerequisite.

### Files touched

| File | Change |
|---|---|
| `sql/sql-annotations/.../SqlExecutions.java` | `ArrayDeque` → `ConcurrentLinkedDeque` (F0). |
| `sql/sql-annotations/.../SqlRecorderRegistry.java` | Replace `InheritableThreadLocal` with plain `ThreadLocal` (or `ConcurrentHashMap<Long, …>` keyed by `Thread.getId()`). Add process-wide `CopyOnWriteArraySet<SqlRecorder> activeRecorders` populated in `register`/cleared in `unregister`. Rewrite `getSqlRecorders()` per the routing decision tree below. |
| `sql/sql-annotations/.../connection/ConnectionListenerRegistry.java` | Mirror the same changes. |
| `sql/sql-annotations/.../SqlRecorder.java` | Add `markPossibleCrossContamination()` / `isPossibleCrossContamination()` (default methods). |
| `sql/sql-annotations/.../PersistenceSqlRecorder.java` | Implement the contamination flag (private `volatile boolean`). Propagate to `SqlExecutions` when flushed. |
| `sql/sql-annotations/.../SqlExecutions.java` | Carry the `possibleCrossContamination` flag (added by previous bullet). |
| `core/.../reporter/ThrowableBuilder.java` (or equivalent) | If the failing perf record carries the contamination flag, append the warning text below to the assertion error message. |

### Routing decision tree (`getSqlRecorders` — F2 + alert)

```
getSqlRecorders():
    tl = perThread.get()
    if tl is non-empty:
        return tl                                  # test thread fast-path
    if activeRecorders.size() == 1:
        return activeRecorders                     # one test active — unambiguous fallback
    if activeRecorders.size() >= 2:
        for r in activeRecorders:
            r.markPossibleCrossContamination()     # diagnostic flag
        return activeRecorders                     # F2 broadcast — set flag and route to all
    return emptyList()                             # no test active — drop silently
```

### Warning text appended to assertion failures (when contamination flag set)

```
[PERF] Expected number of SELECT statements <N> but is <M>

WARNING: SQL was recorded from a worker thread (e.g. @Async, executor)
while other tests were running in parallel. The SQL count above may
include statements from another test. Consider running this test
sequentially with @Execution(SAME_THREAD), or wrap your executor with
QuickPerfContext.wrap(...) (available since QuickPerf X.Y.Z).
```

### Acceptance criteria

| Failing test scenario | Status after PR1 |
|---|---|
| `JUnit5ThreadingScenariosTest.SqlFromPreExistingTomcatStylePool` | ✅ passes (active-set fallback) |
| `JUnit5ThreadingScenariosTest.SqlFromPreExistingExecutorThread` | ✅ passes |
| `JUnit5ThreadingScenariosTest.SqlFromPreExistingCompletableFutureExecutor` | ✅ passes |
| `JUnit5ThreadingScenariosTest.SqlFromPreExistingScheduledExecutor` | ✅ passes |
| `JUnit5ThreadingScenariosTest.SqlFromPreExistingReactorScheduler` | ✅ passes |
| `JUnit5ThreadingScenariosTest.SqlFromPreExistingMessageListenerThread` | ✅ passes (with plain `ThreadLocal` change) |
| `JUnit5ThreadingScenariosTest.SqlFromPersistentSharedWorker` (sequential reuse) | ✅ passes (no `InheritableThreadLocal` → no stale snapshot) |
| `JUnit5ThreadingScenariosTest.ConcurrentQuickPerfTests` (5 parallel methods, own workers) | ✅ passes |
| `JUnit5ThreadingScenariosTest.ConcurrentTestsWithSharedExecutor` | ⚠ may broadcast — warning attached to failing tests |
| `SpringBootThreadingScenariosJunit5Test.should_fail_if_select_number_is_greater_than_expected_with_async` | ✅ passes |
| `SpringBootThreadingScenariosJunit5Test.should_fail_if_select_number_is_greater_than_expected_with_completable_future` | ✅ passes |
| `SpringBootThreadingScenariosJunit5Test.should_detect_select_from_scheduled_task` | ✅ passes |
| `SpringBootThreadingScenariosJunit5Test.concurrent_tests_with_async` (`CrossTestContaminationWithConcurrentAsync`) | ⚠ may broadcast — warning attached to failing tests |
| `SpringBootThreadingScenariosJunit5Test.concurrent_tests_with_random_port` (`CrossTestContaminationWithConcurrentRandomPort`) | ⚠ may broadcast — warning attached to failing tests |
| `arraydeque-thread-safety-test/SqlExecutionsNotThreadSafeTest` | ✅ passes (`ConcurrentLinkedDeque`) |

### Risk

- **Low**: every change is internal to `sql-annotations` and `core`. Public API (annotations, `QuickPerfTestExtension`, `QuickPerfJUnitRunner`, `QuickPerfSpringRunner`, `QuickPerfTestNGListener`) is unchanged.
- The `InheritableThreadLocal` → plain `ThreadLocal` switch is a breaking change *only* for code that relied on auto-inheritance. No user code does (it's an internal mechanism); the only behavioral effect is that worker threads no longer see a stale snapshot.

### Rollback strategy

- Revertible as a single PR. Restoring `InheritableThreadLocal` and the old `getSqlRecorders` reverts to today's behavior.

---

## PR2 — Universal opt-in API + Spring auto-coverage

**Goal**: give users a way to make parallel-shared-infra tests pass while keeping parallelism. Auto-cover Spring `@Async` / `@Scheduled` so most Spring users don't need to wrap manually.

### Files touched

| File | Change |
|---|---|
| `core/.../context/QuickPerfContext.java` (NEW) | `static Executor wrap(Executor)`, `static ExecutorService wrap(ExecutorService)`, `static ScheduledExecutorService wrap(ScheduledExecutorService)`, `static Runnable wrap(Runnable)`, `static <V> Callable<V> wrap(Callable<V>)`. Each captures the current TL recorder map at submit time and restores it on the worker via try/finally. |
| `core/.../context/QuickPerfRunnable.java` (NEW) | Internal wrapper used by `QuickPerfContext.wrap`. |
| `spring/sql-spring*/.../sql/QuickPerfTaskDecorator.java` (NEW per Spring major) | `implements org.springframework.core.task.TaskDecorator`; `decorate(Runnable)` → `QuickPerfRunnable`. |
| `spring/sql-spring*/.../sql/QuickPerfProxyBeanPostProcessor.java` | Extend to detect `ThreadPoolTaskExecutor` / `ThreadPoolTaskScheduler` beans and inject the `QuickPerfTaskDecorator`. |

### Acceptance criteria (in addition to PR1's)

| Test scenario | Status after PR2 |
|---|---|
| `ConcurrentTestsWithSharedExecutor` when user wraps `SHARED_EXECUTOR` with `QuickPerfContext.wrap(...)` | ✅ passes |
| `CrossTestContaminationWithConcurrentAsync` (Spring `@Async`) — auto-coverage via `TaskDecorator` | ✅ passes |
| `CrossTestContaminationWithConcurrentRandomPort` (Spring Tomcat) | ⚠ Tomcat NIO worker is not a Spring `TaskExecutor` bean; needs PR6+ servlet filter or `@Execution(SAME_THREAD)`. Document. |

### Risk

- **Low to medium**. New API surface. The Spring `BeanPostProcessor` change is additive and idempotent (don't double-wrap).
- Watch for users who already register their own `TaskDecorator` — composition order matters; document to chain ours first or ours last.

### Rollback

- New module / new classes; can be deleted without affecting PR1.
- The `BeanPostProcessor` change is gated by detecting the existing decorator chain — defensive.

---

## PR3 — Tighten: broadcast-and-alert → drop-and-fail

**Goal**: convert the contamination flag from a warning text into a forced assertion failure. Achieves the prime-directive guarantee (*"never claim a wrong count silently"*).

### Files touched

| File | Change |
|---|---|
| `sql/sql-annotations/.../SqlRecorderRegistry.java` | In the `activeRecorders.size() >= 2` branch, **drop** the SQL (return empty list) instead of broadcasting. |
| `core/.../perfrecording/PerformanceRecording.java` (or recorder `stopRecording`) | After all `RecordablePerformance.stopRecording` calls, if any active recorder has the contamination flag set, throw an `AssertionError` with the explicit message naming `QuickPerfContext.wrap(...)` (now available since PR2). |
| `core/.../reporter/ThrowableBuilder.java` | The warning message becomes the *body* of the new assertion error rather than an appendix. |

### Behavioral change

| Scenario | PR1 (broadcast-and-alert) | PR3 (drop-and-fail) |
|---|---|---|
| Parallel test passes assertion despite contamination | passes silently (warning hidden) | **fails** with explicit "ambiguous attribution" message |
| Parallel test fails assertion | fails with warning text | fails with explicit message naming `wrap()` |

### Acceptance criteria

- Every test in `JUnit5ThreadingScenariosTest` and `SpringBootThreadingScenariosJunit5Test` either passes (single-test scenarios + correctly-wrapped concurrent ones) or fails with an actionable message naming `QuickPerfContext.wrap(...)`.
- No silent passes when contamination is detected.

### Risk

- **Medium-high for Spring users with parallel `@SpringBootTest` + shared context**. Tests that were passing today via accidental contamination will now fail loudly.
- Mitigated by: PR2 already shipped → users have `QuickPerfContext.wrap()` available → Spring users get auto-coverage via `TaskDecorator` → only the residue (raw `Executors.newFixedThreadPool` + parallel + shared) hits the failure.
- Document loudly in release notes.

### Rollback

- Single-commit revert restores PR1's broadcast-and-alert behavior.

### Why ship in v2 not v1

- Allows one full release cycle for users to discover their parallel-test problems via PR1's warnings, adopt `wrap()` (PR2), and stabilize before the failure mode tightens.
- Avoids the "we shipped a hard break with no in-code fix" anti-pattern of PR1 + PR3 without PR2.

---

## PR4 — New `quickperf-quarkus` extension

**Goal**: zero-config Quarkus support that survives GraalVM native image.

### Module layout

```
quarkus/
  quickperf-quarkus-runtime/
    src/main/java/org/quickperf/quarkus/runtime/
      QuickPerfRecorder.java                  @io.quarkus.runtime.annotations.Recorder
      QuickPerfConfig.java                    @ConfigRoot(phase = RUN_TIME)
      QuickPerfAgroalInterceptor.java         implements io.agroal.api.AgroalPoolInterceptor
      QuickPerfRouteFilter.java               @io.quarkus.vertx.web.RouteFilter (priority MAX_VALUE)
      QuickPerfRestAssuredCustomizer.java     QuarkusTestResourceLifecycleManager
    src/main/resources/META-INF/quarkus-extension.yaml
  quickperf-quarkus-deployment/
    src/main/java/org/quickperf/quarkus/deployment/
      QuickPerfProcessor.java                 @BuildStep methods:
        - consume JdbcDataSourceBuildItem; produce SyntheticBeanBuildItem wrapping each
        - register NativeImageProxyDefinitionBuildItem for Connection/Statement/PreparedStatement/CallableStatement/ResultSet/DatabaseMetaData/Savepoint
        - register ReflectiveClassBuildItem for QuickPerfProxyDataSource, DataSourceQuickPerfListener, QuickPerfDatabaseConnection
        - register RouteFilterBuildItem for QuickPerfRouteFilter
        - register AgroalPoolInterceptor as an @Unremovable bean
```

### Mechanism

- **Agroal `PoolInterceptor.onConnectionAcquire`** stamps the active-test snapshot (looked up via `SqlRecorderRegistry`'s active set) onto the Agroal connection. Because PR1 deferred the per-`Connection` snapshot, **PR4 owns both ends of the routing layer**: it adds the snapshot field to a Quarkus-side wrapper *and* installs the listener-side dispatch (typically by replacing the default `DataSourceQuickPerfListener` with a Quarkus subclass, or by adding a small `Map<ConnectionId, Map<Class, SqlRecorder>>` side-channel keyed on `executionInfo.getConnectionId()` — see `pr1-per-connection-snapshot-plan.md` §12.1 for the technical reason listener-side identity dispatch fails inside `sql-annotations`). PR4 is the first place the routing layer can be wired correctly because Agroal's `PoolInterceptor` callback fires *before* any `Statement` is created on the connection — the framework still owns the connection identity at that moment.
- **`@RouteFilter`** reads `X-QuickPerf-Test-Id` header and stores it on `Vertx.currentContext().putLocal(QP_KEY, testId)` — secondary correlation channel, used for diagnostics and parallel test disambiguation.
- **`RestAssured` customizer** auto-adds the header on outgoing requests during a `@QuarkusTest`.

### Acceptance criteria

- Adding `<dependency>quickperf-quarkus</dependency>` to a Quarkus app's `<scope>test</scope>`:
  - Auto-wraps every `@DataSource` qualifier's Agroal pool.
  - Per-Connection attribution works for `@Blocking` JDBC, `@io.quarkus.scheduler.Scheduled`, `@Asynchronous`, Quarkus REST handlers.
  - Native-image build (`mvn package -Pnative`) succeeds without manual reflection / proxy configuration.
- Drop-and-fail mode (PR3) **never fires** for `@QuarkusTest` users (Quarkus serializes test methods).

### Risk

- **Medium**: requires familiarity with Quarkus extension SDK (build steps, recorders). CI must build with `quarkus-bootstrap-maven-plugin`.
- Native image: maintenance burden — every new JDBC interface added in a future JDK release needs registration.

### Rollback

- New optional module; can be deprecated/removed without affecting other modules.

---

## PR5 — New `quickperf-micronaut` module

**Goal**: zero-config Micronaut support that survives Micronaut AOT and GraalVM native image. Mirror the layered pattern used by `micronaut-tracing` and `micronaut-mdc`.

### Module layout

```
micronaut/
  quickperf-micronaut/
    src/main/java/org/quickperf/micronaut/
      QuickPerfDataSourceListener.java         @Singleton implements BeanCreatedEventListener<DataSource>
      QuickPerfExecutorListener.java           @Singleton implements BeanCreatedEventListener<ExecutorService>
      QuickPerfScheduledExecutorListener.java  @Singleton implements BeanCreatedEventListener<ScheduledExecutorService>
      QuickPerfRunnableInstrumenter.java       implements io.micronaut.scheduling.instrument.RunnableInstrumenter
      QuickPerfReactorHookListener.java        @Singleton ApplicationEventListener<StartupEvent>
                                                — registers Schedulers.onScheduleHook("quickperf", ...)
                                                — clears in ShutdownEvent
      QuickPerfMicronautAutoConfiguration.java @Factory exposing the instrumenter
    src/main/resources/META-INF/native-image/org.quickperf/quickperf-micronaut/
      reflect-config.json                      QuickPerfProxyDataSource, QuickPerfDatabaseConnection
      proxy-config.json                        Connection, Statement, PreparedStatement, CallableStatement, ResultSet, DatabaseMetaData, Savepoint
      resource-config.json
```

### Mechanism

- `BeanCreatedEventListener<DataSource>` wraps each DataSource via `QuickPerfSqlDataSourceBuilder`. As with PR4, **PR5 owns the per-`Connection` routing layer end-to-end** (snapshot install + listener dispatch); see PR4 mechanism note. Micronaut's `BeanCreatedEventListener<DataSource>` runs at bean-creation time, so PR5 can either expose its own dsproxy listener subclass that consults a Micronaut-managed `Map<ConnectionId, Map<Class, SqlRecorder>>`, or wrap connections at the Micronaut DataSource layer to round-trip `Statement.getConnection()` back to the QuickPerf wrapper.
- `BeanCreatedEventListener<ExecutorService>` wraps each executor with `InstrumentedExecutorService` carrying `QuickPerfRunnableInstrumenter` (which captures the active recorder snapshot at submit and restores on the worker via try/finally).
- Same for `ScheduledExecutorService` → `InstrumentedScheduledExecutorService`.
- `Schedulers.onScheduleHook("quickperf", ...)` covers Reactor `boundedElastic` / `parallel` / `single` workers.

### Acceptance criteria

- Adding `<dependency>quickperf-micronaut</dependency>` to a `@MicronautTest` app:
  - Auto-wraps `DataSource` beans.
  - Auto-wraps `TaskExecutors.IO`, `TaskExecutors.SCHEDULED`, `TaskExecutors.MESSAGE_CONSUMER` and any user `ExecutorService` bean.
  - `@Async`, `@Scheduled`, Micronaut HTTP handlers, `@KafkaListener`, `@RabbitListener` all attribute correctly.
  - Reactor `Mono.subscribeOn(Schedulers.boundedElastic())` chains attribute correctly.
  - Micronaut AOT compile (`mvn package -Pnative`) succeeds.

### Risk

- **Medium**: same kind of integration burden as PR4. The `Schedulers.onScheduleHook` global is shared JVM-wide — namespace key `"quickperf"`.

### Rollback

- New optional module; standalone.

---

## PR6 (optional) — `quickperf-reactive` for Spring WebFlux + Quarkus reactive + Micronaut reactive

**Goal**: cover purely reactive code paths via `Reactor Context` + `Mutiny Context` propagation.

### Mechanism

- Implement `io.micrometer.context.ThreadLocalAccessor<Map<Class, SqlRecorder>>` for QuickPerf's recorder TL. Register via `META-INF/services/io.micrometer.context.ThreadLocalAccessor`.
- Implement `org.eclipse.microprofile.context.spi.ThreadContextProvider` for MP-CP runtimes (Quarkus, Helidon, OpenLiberty). Register via `META-INF/services/org.eclipse.microprofile.context.spi.ThreadContextProvider`.
- Mutiny `ContextSupport` integration via SmallRye CP.
- Spring Boot 3 enables via `spring.reactor.context-propagation=AUTO`. Quarkus enables via SmallRye Mutiny integration. Micronaut needs explicit `Hooks.enableAutomaticContextPropagation()` in a `BeanCreatedEventListener`.

### Why optional

- Most QuickPerf users today are imperative (Spring MVC + JDBC, plain JUnit + Hibernate). Reactive is a smaller (but growing) audience.
- Ship only if user demand materializes.

---

## PR7 (optional) — `quickperf-r2dbc` for Spring WebFlux + R2DBC

**Goal**: cover R2DBC SQL interception via `r2dbc-proxy`. Currently QuickPerf has zero coverage for Spring WebFlux + R2DBC users.

### Mechanism

- Mirror `QuickPerfProxyDataSource` / `DataSourceQuickPerfListener` for R2DBC.
- Per-`Connection` snapshot model carries over verbatim from whichever PR4/PR5 routing layer ends up canonical.
- `r2dbc-proxy` (`io.r2dbc:r2dbc-proxy`) provides the `ProxyConnectionFactory` / `ProxyExecutionListener` analogs of datasource-proxy.

### Why optional

- Same as PR6: ship if user demand justifies the maintenance.

---

## Cross-cutting architectural commitments

These survive every PR and must be preserved:

1. **No `InheritableThreadLocal`** anywhere in `sql-annotations` or `core` after PR1.
2. **No JVM agent** (`java.lang.instrument`, `-javaagent`, `ByteBuddyAgent.install()`) anywhere in any module — must work on Quarkus native image and Micronaut AOT.
3. **Public user-facing API** (`@QuickPerfTest`, `@ExpectSelect`, `QuickPerfJUnitRunner`, `QuickPerfTestExtension`, `QuickPerfSpringRunner`, `QuickPerfTestNGListener`) **never changes** across PR1–PR3. Only internal types may.
4. **Per-`Connection` snapshot is the canonical attribution mechanism** for framework integrations (PR4 Quarkus, PR5 Micronaut, future R2DBC). In `sql-annotations` itself (PR1), the active-set fallback is the canonical mechanism — the per-`Connection` snapshot was deferred there because dsproxy's `executionInfo.getStatement().getConnection()` returns the raw pool connection, not the QuickPerf wrapper, so listener-side identity dispatch cannot work at the wrapping layer (see `pr1-per-connection-snapshot-plan.md` §12). The active-set fallback exists for connections vended outside any test (warm-up, housekeeping) and for genuinely-ambiguous parallel cases, and is also where the dual-map's contamination warning originates. If a future PR adds a third source of attribution (e.g., MP `ThreadContextProvider`), it must feed the per-`Connection` snapshot in framework modules and the active-set in `sql-annotations`, not bypass either.
5. **Forked-JVM mode** (`@HeapSize` / `@Xmx` style tests) collapses naturally to a single-recorder case and must remain identical to today.

---

## What is deliberately deferred (and why)

| Out of scope | Rationale |
|---|---|
| `quarkus-reactive-pg-client`, `mutiny-zero-jdbc`, Vert.x SQL Client (used by Hibernate Reactive) | Bypass `javax.sql.DataSource` entirely — needs a separate Vert.x SqlClient interceptor. Deferred until Hibernate Reactive demand materializes. |
| Connection-tag refresh on `Statement.execute*` (long-lived `Connection` pinned across tests) | Edge case; can be added without breaking the core model. Defer until a real bug report. |
| Cross-JVM correlation header propagation via `TestRestTemplate` (Spring) and arbitrary HTTP clients | PR4 covers `RestAssured`; Spring uses `@Execution(SAME_THREAD)` workaround. Generalize later if demand exists. |
| Replacing `SqlRecorderRegistry` with a `TestScopeId`-keyed registry (the architect-eval Option 7) | Larger blast radius; the dual-map active-set fallback achieves equivalent coverage with smaller risk in PR1. PR4/PR5's per-`Connection` snapshot covers the framework path. Defer indefinitely. |

---

## Bottom line

```
v1: PR1 + PR2          → architectural foundation + remediation API + soft warning
v2: PR3                → tighten warning into forced failure
v3: PR4                → quickperf-quarkus
v4: PR5                → quickperf-micronaut
v5+: PR6 / PR7         → reactive / R2DBC if demanded
```

Total committed work: 5 PRs across 4–5 releases. Each is independently mergeable, independently reverteable, and each unlocks the next. The architectural commitment in PR1 (drop ITL + dual-map registry) is the single most important move; everything else is layered on top. The per-`Connection` snapshot — originally drafted as a third PR1 commitment — is owned by PR4/PR5, where the framework holds the QuickPerf wrapper at pool checkout and can install snapshot dispatch correctly.

For Quarkus and Micronaut specifically, PR1 ships dual-map routing that's correct under the framework default of serialized test methods. PR4 and PR5 layer the per-`Connection` snapshot on top to make pool-checkout integration native-image-safe and to close the corner cases where parallel `@QuarkusTest` / `@MicronautTest` execution becomes adopted — not architectural prerequisites blocking PR1.
