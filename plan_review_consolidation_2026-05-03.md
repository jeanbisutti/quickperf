# Plan-review consolidation — `remove_r2dbc_limitations_plan` v3

- **Date:** 2026-05-03
- **Plan revised:** `remove_r2dbc_limitations_plan_2026-05-03_09-36-53.md` (v2 → v3)
- **Inputs reconciled:**
  - Review A — Claude Opus 4.7 (Extra high reasoning) — `plan_review_2026-05-03_22-58-21.md`
  - Review B — GPT-5.5 (Extra high thinking) — `gpt55-review.md`
- **Working tree HEAD verified:** `C:\code\fork6\quickperf` on 2026-05-03
- **External jars verified (sources extracted from local m2):** r2dbc-proxy 1.1.4.RELEASE, r2dbc-spi 1.0.0.RELEASE, r2dbc-pool 1.0.1.RELEASE.

---

## Executive summary

- Both reviews independently identified the same primary blocker: **`ResultCallbackHandler` is `public final`** in r2dbc-proxy 1.1.4, so the v2 plan's "extends `ResultCallbackHandler`" design will not compile. v3 replaces it with a `QuickPerfMonitoringResult` decorator (a `final class implements io.r2dbc.spi.Result`) installed via a custom `ProxyFactoryFactory` whose `wrapResult` returns the decorator wrapping the JDK-default proxied `Result`. The new `ProxyFactoryFactory`/`ProxyFactory` pair is placed in package `io.r2dbc.proxy.callback` so it can construct a `JdkProxyFactory(proxyConfig)` against the user's `ProxyConfig` without reflection — package-private access to `JdkProxyFactory` is the only viable seam, since both `JdkProxyFactory` and `JdkProxyFactoryFactory` are package-private final.
- Opus alone identified the second runtime blocker: `ConnectionListenerRegistry` is backed by `InheritableThreadLocal`, which does not propagate to Reactor scheduler threads. v3 introduces a JVM-global `ConnectionListenerHook` (mirroring the existing `SqlRecorderHook`) for the new neutral connection-lifecycle events. The legacy JDBC-typed callbacks keep using `ConnectionListenerRegistry`. `@Execution(SAME_THREAD)` is acknowledged to be a no-op (JUnit 5 parallel is not enabled in any of the relevant modules) and is removed as a recommended fix.
- GPT alone identified that the v2 plan's CGLIB design referenced `ProxyConnectionFactory.wrap(Connection)` which does not exist in r2dbc-proxy 1.1.4. v3 keeps the CGLIB outer subclass solely to preserve `instanceof ConnectionPool`/`Wrapped<ConnectionFactory>`, and its `MethodInterceptor` rewrites `create()` to delegate to a pre-built `ProxyConnectionFactory.builder(target).listener(...).build()`. Per-Connection wrapping is performed automatically by r2dbc-proxy on the outer factory; we never call any nonexistent `wrap(Connection)` method.
- GPT also flagged that the v2 plan's annotation matrix (Appendix A and §1.1) lists annotations that do not exist in the working tree (`@ExpectNoSelect`, `@ExpectNoQueries`, `@ExpectQueriesNumber`, `@ExpectMaxQueriesNumber`, `@ExpectMaxQueryExecutionTimeWithDb`, `@ExpectNoLikeWithLeadingWildcard`, `@DisableExactSameSelects`, `@ExpectMaxSameSelectTypes`, `@DisableSelectInTransaction`, `@DisableQueriesDisplay`, `@DisableSqlDisplay`). v3 normalises both matrices against the actual `sql/sql-annotations/src/main/java/org/quickperf/sql/annotation/` directory listing.
- Opus M1 (annotation-aliases dual-registration with the same verifier instance) is correct and accepted: every existing `VerifiablePerformanceIssue<…>` is parameterised on the legacy annotation type. v3 changes §2.9 from "register both annotations against the same verifier instance" to "introduce one alias-specific verifier per alias, sharing the same logic via package-private delegation". The alias registrations must be added in `org.quickperf.sql.config.library.SqlAnnotationsConfigs` (package-private, in package `org.quickperf.sql.config.library` — the v2 plan's `org.quickperf.sql.config` references are wrong).

---

## Decision table

Legend: ✅ accept · ✏️ accept-with-modification · ❌ reject

| ID | Source | Title | Decision | One-line justification | Plan section(s) affected |
|---|---|---|---|---|---|
| **B1** | Opus + GPT | `ResultCallbackHandler` is `final` | ✏️ accept-with-design | Subclass is impossible (verified `r2dbc-proxy-1.1.4-sources.jar!ResultCallbackHandler.java:40`); v3 introduces `QuickPerfMonitoringResult implements io.r2dbc.spi.Result` plus `QuickPerfProxyFactoryFactory`/`QuickPerfProxyFactory` pair placed in `io.r2dbc.proxy.callback` (so it can construct `JdkProxyFactory(proxyConfig)` package-privately). | §2.1, §4.1, §4.2, §6.1, §7 (PR-2) |
| **B2 (Opus)** | Opus | `ConnectionListenerRegistry` `InheritableThreadLocal` is empty on Reactor scheduler threads | ✏️ accept-with-design | Verified `ConnectionListenerRegistry.java:28-38, 60-67`; preferred mitigation is a JVM-global `ConnectionListenerHook` with `CopyOnWriteArraySet<SqlConnectionListener>` for the new events only — keeping the legacy registry untouched. | §2.3, §3.3, §3.4, §6.1, §7 (PR-0/PR-4/PR-5) |
| **B3 (Opus)** | Opus | `JdkProxyFactory`/`JdkProxyFactoryFactory` are package-private final | ✅ accept (option b) | `JdkProxyFactory.java:41` and `JdkProxyFactoryFactory.java:27` are both package-private final; the only practical seam is to place our `ProxyFactoryFactory`/`ProxyFactory` in package `io.r2dbc.proxy.callback`. Option (c) "delegate to a JdkProxyFactoryFactory held by the outer factory" is *only* viable from inside the package. Reading `JdkProxyFactory.wrapStatement` confirms result-callback dispatch is *not* internal to `wrapStatement` — it goes through `this.proxyConfig.getProxyFactory().wrapResult(...)`, so a single-method override suffices. | §2.1, §4.1 |
| **B4 (GPT B2)** | GPT | `ProxyConnectionFactory.wrap(Connection)` does not exist | ✅ accept | `ProxyConnectionFactory.java` (1.1.4) has only `builder(...)` and `Builder.build()` (returns a `ConnectionFactory`); there is no `wrap(Connection)`. v3 drops the per-Connection inner-proxy attempt. The r2dbc-proxy listener on the outer `ConnectionFactory` proxy automatically observes per-Connection method events because r2dbc-proxy chains `wrapConnection(...)` on every emitted `Connection` — verified in `JdkProxyFactory.wrapConnection`. | §2.7 |
| **B5 (GPT B3)** | GPT | Annotation matrix lists fictional annotations | ✅ accept | Directory listing of `sql/sql-annotations/src/main/java/org/quickperf/sql/annotation/` (32 files) shows the actual SQL annotations. Eleven names referenced in the plan do not exist. v3 normalises §1.1, §1.2, §3.6, Appendix A. | §1.1, §1.2, §3.6, Appendix A |
| **B6 (GPT B4)** | GPT | Per-test attribution under real parallel R2DBC | ✏️ accept | Same root cause as B2; the dedicated R2DBC Spring module already pins Surefire `<parallel>none</parallel>` (verified `spring/junit5-spring-boot-3-r2dbc-test/pom.xml:135-139`). v3 documents `<parallel>none</parallel>` as the only safe stance for v2 and lists Reactor-`Context`-based per-test routing as a v3 deferral. `@Execution(SAME_THREAD)` is dropped because JUnit 5 parallel is not enabled anywhere relevant. | §3.4, §3.6, §6.1, §6.3 |
| **M1 (Opus)** | Opus | `wrapResult`, not `createResultProxy` | ✅ accept | `ProxyFactory.java:92`. Cosmetic but consequential. | §2.1 (line 79) |
| **M2 (Opus)** | Opus | `Result.getRowMetadata()` does not exist; only the BiFunction overload exposes `RowMetadata` directly; `map(Function)`, `flatMap`, `filter` need separate instrumentation | ✏️ accept (combine with Opus M8) | r2dbc-spi 1.0.0 `Result.java` exposes `map(BiFunction)`, default `map(Function<Readable,T>)`, `getRowsUpdated`, `filter(Predicate<Segment>)`, `flatMap(Function<Segment, Publisher<T>>)`. The v3 decorator instruments all four; the technique is to wrap the user-supplied lambda with a tracing wrapper that, on first invocation involving a `Row`, calls `row.getMetadata().getColumnMetadatas().size()`. Capture happens on the first row only (idempotent CAS). | §2.1.A, §2.1.D, §5.2 |
| **M3 (Opus + GPT M6)** | both | `ConnectionPool` does not extend `AbstractConnectionFactory` | ✅ accept | `r2dbc-pool-1.0.1-sources.jar!ConnectionPool.java:63`: `public class ConnectionPool implements ConnectionFactory, Disposable, Closeable, Wrapped<ConnectionFactory>`. CGLIB subclass interface list must include `Wrapped`, `Disposable`, `Closeable`, plus our marker. | §2.7.A, §2.7.E, §6.1 R3/R4 |
| **M4 (Opus)** | Opus | `SystemProperties` only reads `System.getProperty(...)` | ✅ accept (option a, with v3 deferral) | Verified — `core/src/main/java/org/quickperf/SystemProperties.java` lines 26, 42, 59, 76, 93 all call `System.getProperty(name)`. No Spring `Environment` consultation anywhere in `spring/`. v3 documents the limitation in §3.5 and `r2dbc.md`: opt-out flags are read via `-D` only. The Spring-Environment bridge is listed in §6.3 as a v3 deferral. | §3.5, §6.3 |
| **M5 (Opus)** | Opus | `SqlAnnotationsConfigs` is in `org.quickperf.sql.config.library`, package-private | ✅ accept | Verified `sql/sql-annotations/src/main/java/org/quickperf/sql/config/library/SqlAnnotationsConfigs.java:13, 59`. v3 fixes references and adds explicit "alias registrations live in the same package" note. | §2.9, §3.1, §4.2 |
| **M6 (Opus)** | Opus | `@Execution(SAME_THREAD)` is a no-op | ✅ accept | Verified all `junit-platform.properties` files in the repo only enable `extensions.autodetection`. Surefire `<parallel>all</parallel>` is for JUnit 4 / TestNG providers; the JUnit 5 platform parallel needs separate property. The R2DBC Spring module already pins Surefire `<parallel>none</parallel>`. v3 drops the recommendation and points to §2.3 / B2 fix. | §3.4, §6.1 R6, §7 (PR-4/PR-5) |
| **M7 (Opus)** | Opus | Carrier scope ambiguous between `ConnectionInfo.getValueStore()` and per-execution map | ✏️ accept (carrier chosen) | v3 picks a single carrier: a JVM-static `ConcurrentHashMap<String, long[]>` keyed by `connectionId + "#" + System.identityHashCode(queryExecutionInfo)`, populated by `QuickPerfMonitoringResult` and drained (and removed) by `R2dbcExecutionAdapter.adapt`. Documented cleanup point. | §2.1.B |
| **M8 (Opus)** | Opus | flatMap/filter need explicit instrumentation | ✅ accept | Combined with M2 above. | §2.1.A, §5.2 |
| **M9 (Opus)** | Opus | JDBC-side files touched by opaque-id refactor | ✅ accept | `QuickPerfDatabaseConnection.java` exists at `sql/sql-annotations/src/main/java/org/quickperf/sql/connection/`. v3 adds an explicit "JDBC-side changes" subsection to §2.3 and updates §4.2. | §2.3, §4.2 |
| **GPT M1** | GPT | Existing verifiers are typed; dual registration with same verifier instance fails | ✏️ accept | Verified — every `VerifiablePerformanceIssue<…>` (e.g., `SqlStatementBatchVerifier implements VerifiablePerformanceIssue<ExpectJdbcBatching, SqlBatchSizes>`) is generic on the legacy annotation type. v3 requires one alias-specific verifier per alias (or a generic base accepting both). v3 also notes the latent `TestExecutionContext.buildPerfRecordersToExecute` deduplication bug: `perfRecorderClasses` set is never `.add()`'d to (verified `TestExecutionContext.java:154-164`). It is harmless today (the `contains()` check is always false) but should be fixed in PR-7. | §2.9, §4.2, §7 (PR-7) |
| **GPT M2** | GPT | `commitTransaction`/`rollbackTransaction` (not `commit`/`rollback`) | ✅ accept | r2dbc-spi `Connection.java` (1.0.0) has `commitTransaction`, `rollbackTransaction`, `beginTransaction`, `beginTransaction(TransactionDefinition)`, `setTransactionIsolationLevel`, `setAutoCommit`, `createStatement`, `createBatch`, `close`, `createSavepoint(String)`, `releaseSavepoint(String)`, `rollbackTransactionToSavepoint(String)`, `getMetadata`, `validate(ValidationDepth)`, `setLockWaitTimeout`, `setStatementTimeout`. v3 §2.4 lists the exact SPI names. | §2.4 |
| **GPT M3** | GPT | `SqlExecution` short-circuits to 0 on null result; CCE never fires | ✅ accept | `SqlExecution.java:72-92` — `dbExceptionHappened` returns `true` for null result and the helper returns 0 before the cast. v3 reword §2.1 root-cause narrative. | §2.1 |
| **GPT M4** | GPT | Placeholder scanner misses E-strings, U&-strings, Oracle Q-strings | ✏️ accept | v3 §2.6 adds states for PostgreSQL `E'…'` (with backslash-escapes) and `U&'…'`. Oracle alternative quoting `Q'⟨delim⟩…⟨delim⟩'` and MS SQL `[id]` are explicitly out-of-scope for v2 with a v3 deferral entry. | §2.6 |
| **GPT M5** | GPT | r2dbc-pool validation queries — defined semantics | ✅ accept (validation does NOT count) | `ConnectionPool.create()` runs validation internally before emitting the Connection. The r2dbc-proxy listener installed at the outer-factory level sees the user-visible `create()` event and subsequent per-Connection events; the validation `SELECT 1` never reaches our listener. v3 §2.7 documents this and adds a regression IT (`R2dbcPoolValidationQueryNotCountedIT`). | §2.7, §5.2 |
| **GPT M6** | GPT | Same as Opus M3 | ✅ accept | Folded into M3 above. | §2.7 |
| **GPT M7** | GPT | Same as Opus M2 | ✅ accept | Folded into M2 above. | §2.1 |
| **Opus m1** | Opus | `a::int` already handled by `(?<!:):name` regex | ✅ accept | `PlaceholderRewriter.java:50` and the existing test `PlaceholderRewriterTest.preserves_postgres_double_colon_cast_operator` confirm `::` is already correctly preserved. | §1.2, §2.6 |
| **Opus m2** | Opus | `?` is not regex-rewritten today; the real bug for `?`-only SQL is empty `orderedKeys` | ✅ accept | `PlaceholderRewriter.java` only has `NAMED_PLACEHOLDER` and `POSITIONAL` patterns; `?` is passed through verbatim. The actual bug is that pure-`?` SQL produces empty `orderedKeys`, breaking `@DisplaySql` rendering. v3 corrects the v1 limitations narrative. | §1.2, §2.6 |
| **Opus m3** | Opus | IllegalStateException message wording | ✅ accept | `QueryParamsExtractor.java:51-54` — actual text is "Several parameter set not managed, please create an issue on https://github.com/quick-perf/quickperf/issues describing your use case." v3 quotes it correctly. | §2.5 |
| **Opus m4** | Opus | "hot path short-circuited" misleading | ✅ accept | `QuickPerfDatabaseConnection.buildFrom(...)` is unconditional. v3 rewords to "gated at registration time, unconditional once registered". | §2.8 |
| **Opus m5** | Opus | CCE narrative — same as GPT M3 | ✅ accept | Folded. | §2.1 |
| **Opus m6** | Opus | Savepoint methods missing | ✅ accept | Folded into GPT M2. | §2.4 |
| **Opus m7** | Opus | `synchronized (recorder)` already in place | ✅ accept | `R2dbcQuickPerfListener.java:103` — already serialised per-recorder. v3 §3.4 references this. | §3.4 |
| **Opus m8** | Opus | Scanner not reused for JDBC | ✅ accept | JDBC display rendering goes through datasource-proxy's per-index substitution. v3 drops §6.1 R6's "scanner reused for JDBC" line. | §6.1 R6 |
| **Opus m9** | Opus | `Wrapped<ConnectionFactory>` interface | ✅ accept | Folded into M3. | §2.7 |
| **Opus m10** | Opus | Verify `close()` non-final too | ✅ accept | Verified `r2dbc-pool-1.0.1-sources!ConnectionPool.java:305 public Mono<Void> close()` (non-final) and `:310 public void dispose()` (non-final). | §2.7.E, §5.2 |
| **GPT m1** | GPT | `ConnectionListenerRegistry` global `ArrayList` is not thread-safe | ✅ accept (note only) | Confirmed — `connectionListenersOfTestJvm = new ArrayList<>()` (line 26) is used unguarded under `TEST_CODE_EXECUTING_IN_NEW_JVM`. v3 mentions the latent issue but it is out of scope (the forked-JVM path is single-threaded by construction). | §3.3 (note) |
| **GPT m2** | GPT | Spring Objenesis fallback wording | ✅ accept | Reword §2.7.D to mention `final` classes as the main CGLIB blocker. | §2.7.D |
| **GPT m3** | GPT | R2DBC Spring module already pins Surefire `<parallel>none</parallel>` | ✅ accept | Confirmed at `spring/junit5-spring-boot-3-r2dbc-test/pom.xml:135-139`. v3 cites this as the actual isolation mechanism for v2. | §3.4, §3.6 |

---

## Contested-point decisions

### B1 — `Result` decorator design

The v3 design comprises three classes:

1. **`org.quickperf.sql.r2dbc.QuickPerfMonitoringResult`** — `final class implements io.r2dbc.spi.Result`. Wraps a delegate `Result` (which is itself the JDK-default `ResultCallbackHandler`-backed proxy, so the existing `ResultInvocationSubscriber` chain that drives `afterQuery` is preserved).

   It overrides exactly four `Result` API methods that touch row metadata, plus the two non-mutating methods, and forwards everything to the delegate. The `BiFunction`/`Function`/`flatMap`/`filter` lambdas are wrapped with a tracing wrapper that, on first invocation, captures `column-count = row.getMetadata().getColumnMetadatas().size()` (or, for the `BiFunction` case, directly `rowMetadata.getColumnMetadatas().size()`). Capture is idempotent (CAS on a per-execution slot).

   ```java
   // Outline (illustrative — not a literal code dump)
   final class QuickPerfMonitoringResult implements Result {
       private final Result delegate;
       private final QueryExecutionInfo queryExecutionInfo;

       @Override public Publisher<Long> getRowsUpdated() { return delegate.getRowsUpdated(); }
       @Override public Result filter(Predicate<Segment> p) {
           return new QuickPerfMonitoringResult(delegate.filter(traceSegment(p)), queryExecutionInfo);
       }
       @Override public <T> Publisher<T> flatMap(Function<Segment, ? extends Publisher<? extends T>> f) {
           return delegate.flatMap(traceSegmentFn(f));
       }
       @Override public <T> Publisher<T> map(BiFunction<Row, RowMetadata, ? extends T> bi) {
           return delegate.map(traceBiFn(bi));
       }
       @Override public <T> Publisher<T> map(Function<? super Readable, ? extends T> f) {
           return delegate.map(traceReadableFn(f));
       }
       // tracers call recordColumnCountOnce(...) and forward to user
   }
   ```

2. **`io.r2dbc.proxy.callback.QuickPerfProxyFactory`** — `final class implements ProxyFactory`. Constructor takes a `ProxyConfig` and a `JdkProxyFactory delegate = new JdkProxyFactory(proxyConfig)` (constructor is package-visible). Six methods delegate to `delegate`. `wrapResult` calls `delegate.wrapResult(...)` first and then wraps that result in `QuickPerfMonitoringResult`.

3. **`io.r2dbc.proxy.callback.QuickPerfProxyFactoryFactory`** — `final class implements ProxyFactoryFactory`. `create(ProxyConfig)` returns `new QuickPerfProxyFactory(proxyConfig)`.

   The `ProxyFactoryFactory` is installed via `proxyConfig.setProxyFactoryFactory(new QuickPerfProxyFactoryFactory())` (or `ProxyConfig.builder().proxyFactoryFactory(...).build()`).

The two classes in `io.r2dbc.proxy.callback` carry a header comment: *"Placed in `io.r2dbc.proxy.callback` to access the package-private `JdkProxyFactory`. Verified against r2dbc-proxy 1.1.4. If the upstream `JdkProxyFactory` constructor or class is changed in a future minor release, this code must be revisited."*

### B2 — JVM-global hook for connection-lifecycle events

v3 adds `org.quickperf.sql.connection.ConnectionListenerHook` modelled on `SqlRecorderHook`:

```java
public final class ConnectionListenerHook {
    private static final Set<SqlConnectionListener> ACTIVE = new CopyOnWriteArraySet<>();
    public static void register(SqlConnectionListener l) { ACTIVE.add(l); }
    public static void unregister(SqlConnectionListener l) { ACTIVE.remove(l); }
    public static Set<SqlConnectionListener> getActive() { return Collections.unmodifiableSet(ACTIVE); }
}
```

- `ConnectionLeakListener.startRecording` and `TestConnectionProfiler` (the constructor path that already registers with `ConnectionListenerRegistry`) **also** register with `ConnectionListenerHook`. Symmetric on stop / `cleanResources`.
- `R2dbcConnectionLifecycleListener` and `R2dbcConnectionEventsProfilerListener` dispatch into `ConnectionListenerHook.getActive()` only — they do not consult `ConnectionListenerRegistry`.
- `ConnectionListenerRegistry` is left untouched for the legacy JDBC dispatch path (it is consulted by `QuickPerfDatabaseConnection` in `buildFrom(...)` and is correct for synchronous JDBC).
- Within-test concurrency: under v2, the `parallel=all` Surefire setting is in effect for JDBC tests but the R2DBC Spring module pins `<parallel>none</parallel>`. For users running their own R2DBC tests under JUnit Jupiter parallelism, `r2dbc.md` documents that R2DBC SQL annotations require Surefire `<parallel>none</parallel>` (or a single test class executing serially). Reactor-`Context`-based per-test routing is deferred to v3.

### B3 — ProxyFactory placement is option (b)

Verified by reading `JdkProxyFactory.wrapStatement` (lines 86–95): the result-callback dispatch is **not** internal to `wrapStatement`. `StatementCallbackHandler` (and downstream `MutableStatementInfo`/`MutableQueryExecutionInfo`) ultimately consults `proxyConfig.getProxyFactory().wrapResult(...)` for every emitted `Result`. As long as the `ProxyConfig`'s `ProxyFactory` is `QuickPerfProxyFactory`, our `wrapResult` is invoked end-to-end without reimplementing any of the other 6 wrap methods.

The only constraint is that we need to construct `JdkProxyFactory(proxyConfig)`, which has a package-private constructor. We therefore **place `QuickPerfProxyFactory` and `QuickPerfProxyFactoryFactory` in package `io.r2dbc.proxy.callback`**. This is option (b) from the user's prompt. Option (c) "delegate to a held `JdkProxyFactoryFactory().create(proxyConfig)`" is identical at runtime but only executable from inside the package — `JdkProxyFactoryFactory` is itself package-private.

### GPT B2 — drop `wrap(Connection)` pseudo-code; rely on outer-factory listener

v3 §2.7 replaces the pseudo-code at the v2 plan's lines 415–420 with a fully described two-layer design:

- Outer layer: a Spring AOP `ProxyFactory(target)` with `setProxyTargetClass(true)`, `addInterface(QuickPerfR2dbcProxyMarker.class)`, `addInterface(Wrapped.class)`. The `MethodInterceptor` rewrites the `create()` method only — every other method (including `getMetadata()`, `dispose()`, `close()`, `unwrap()`) calls `invocation.proceed()` to delegate to the `ConnectionPool` target.
- The `MethodInterceptor` builds the inner r2dbc-proxy ConnectionFactory once (at BPP time): `proxiedFactory = ProxyConnectionFactory.builder(target).proxyConfig(quickPerfProxyConfig).listener(quickPerfListener).listener(connectionLifecycleListener).build()`. When the user calls `wrapped.create()`, the interceptor returns `proxiedFactory.create()`.
- r2dbc-proxy automatically wraps the emitted `Connection` with `JdkProxyFactory.wrapConnection(...)` (verified at `JdkProxyFactory.java:67-74`), so per-`Connection` method events automatically flow through our listeners. No `ProxyConnectionFactory.wrap(Connection)` call is needed (and none exists).

### GPT B3 — annotation matrix corrections

Real annotations in `sql/sql-annotations/src/main/java/org/quickperf/sql/annotation/` (32 .java files):

`AnalyzeSql`, `DisableLikeWithLeadingWildcard`, `DisableQueriesWithoutBindParameters`, `DisableSameSelects`, `DisableSameSelectTypesWithDifferentParamValues`, `DisableStatements`, `DisplaySql`, `DisplaySqlOfTestMethodBody`, `EnableLikeWithLeadingWildcard`, `EnableQueriesWithoutBindParameters`, `EnableSameSelects`, `EnableSameSelectTypesWithDifferentParamValues`, `EnableStatements`, `ExpectDelete`, `ExpectInsert`, `ExpectJdbcBatching`, `ExpectJdbcQueryExecution`, `ExpectMaxDelete`, `ExpectMaxInsert`, `ExpectMaxJdbcQueryExecution`, `ExpectMaxQueryExecutionTime`, `ExpectMaxSelect`, `ExpectMaxSelectedColumn`, `ExpectMaxUpdate`, `ExpectMaxUpdatedColumn`, `ExpectNoConnectionLeak`, `ExpectSelect`, `ExpectSelectedColumn`, `ExpectUpdate`, `ExpectUpdatedColumn`, `ProfileConnection`. Plus `SqlAnnotationBuilder` (helper) and `package-info`.

Names that the v2 plan invented and v3 removes:
- `@ExpectNoSelect`, `@ExpectNoInsert`, `@ExpectNoUpdate`, `@ExpectNoDelete`, `@ExpectNoQueries`, `@ExpectNoLikeWithLeadingWildcard`
- `@ExpectQueriesNumber`, `@ExpectMaxQueriesNumber`
- `@ExpectMaxQueryExecutionTimeWithDb`, `@ExpectMaxQueryExecutionTimeWithoutDb`
- `@DisableExactSameSelects`, `@ExpectMaxSameSelectTypes`
- `@DisableSelectInTransaction`, `@DisableQueriesDisplay`, `@DisableSqlDisplay`
- `@JdbcSpyDataSource`, `@JdbcStatementSpy` (out-of-scope already; not removed but flagged as nonexistent today)

v3's Appendix A is rebuilt against the actual file list. v1 status of "no-X" semantics is achieved today by passing `0` to the corresponding `@Expect*` annotation (e.g., `@ExpectSelect(0)`); v3 does not introduce shorthand `@ExpectNo*` annotations as that would be a separate feature outside this plan.

### GPT M1 — alias verifiers

Each new alias (`@ExpectQueryBatching`, `@ExpectQueryExecution`, `@ExpectMaxQueryExecution`) gets its own `*Verifier` class typed on the alias annotation. The implementation is a one-liner that delegates to the legacy verifier instance via a private constructor that exposes the verification logic on a package-private base class. Concretely, v3 adds:

- `SqlStatementBatchVerifier` already exists. v3 adds `SqlQueryBatchingVerifier implements VerifiablePerformanceIssue<ExpectQueryBatching, SqlBatchSizes>` whose body is `return SqlStatementBatchVerifier.verify(annotation.batchSize(), annotation.value()/* whatever */, measure);` — i.e., the legacy verifier exposes a package-private static `verify(int expectedBatchSize, int[] measured)` helper, and both verifier classes delegate to it.

Same pattern for `JdbcQueryExecutionVerifier`/`MaxJdbcQueryExecutionVerifier`. This avoids duplicating logic while keeping the generic-type contract.

`SqlAnnotationsConfigs` registers each `(AliasAnnotation, AliasVerifier)` pair as a separate `AnnotationConfig`; the recorder class is reused since `TestExecutionContext.buildPerfRecordersToExecute` already deduplicates by recorder class (modulo the latent "set not populated" bug, fixed in PR-7).

### GPT M2 — R2DBC `Connection` SPI method names

Verified against `r2dbc-spi-1.0.0-sources!Connection.java`:

| Plan v2 said | Real SPI name | Status |
|---|---|---|
| `commit` | `commitTransaction()` | ✅ correct in v3 |
| `rollback` | `rollbackTransaction()` | ✅ correct in v3 |
| `beginTransaction` | `beginTransaction()` + `beginTransaction(TransactionDefinition)` | both listed |
| `setTransactionIsolationLevel` | unchanged | ok |
| `setAutoCommit` | unchanged | ok |
| `createStatement` | unchanged | ok |
| `createBatch` | unchanged | ok |
| `close` | unchanged | ok |
| (missing) | `createSavepoint(String)`, `releaseSavepoint(String)`, `rollbackTransactionToSavepoint(String)` | added |
| (missing) | `getMetadata`, `validate(ValidationDepth)`, `setLockWaitTimeout(Duration)`, `setStatementTimeout(Duration)` | added |

### GPT M5 — pool validation queries

Verified `ConnectionPool.create()` (line 300-302) returns `this.create` which is built up internally via `reactor.pool` and includes validation invocation. Validation `SELECT 1` runs **inside** the inner `ConnectionFactory.create()` call before the wrapped Connection emerges. With the v3 design:

- The outer CGLIB subclass intercepts `create()` and routes to `proxiedFactory.create()`.
- `proxiedFactory = ProxyConnectionFactory.builder(originalPool).listener(...).build()` — note we wrap the **pool**, not the inner factory.
- When `proxiedFactory.create()` runs, it calls `originalPool.create()` which internally runs validation against an inner Connection (NOT proxied). When the Connection is finally emitted to user, r2dbc-proxy wraps it.

Therefore validation queries do **not** count toward `@ExpectMaxQueriesNumber`. v3 documents this in §2.7 and §3.6, and adds an IT (`R2dbcPoolValidationQueryNotCountedIT`) to §5.2.

### Opus M2 + M8 — instrumenting all four `Result` API surfaces

The decorator wraps each user-supplied lambda. The technique is straightforward CAS-once:

- `map(BiFunction<Row, RowMetadata, T>)`: wrap `(row, md) → user.apply(row, md)` with `(row, md) → { recordOnce(md); return user.apply(row, md); }`.
- `map(Function<? super Readable, T>)`: wrap `r → user.apply(r)` with `r → { if (r instanceof Row) recordOnce(((Row) r).getMetadata()); return user.apply(r); }`.
- `flatMap(Function<Segment, Publisher<T>>)`: wrap `seg → user.apply(seg)` with `seg → { if (seg instanceof RowSegment) recordOnce(((RowSegment) seg).row().getMetadata()); return user.apply(seg); }`.
- `filter(Predicate<Segment>)`: wrap `seg → user.test(seg)` with `seg → { if (seg instanceof RowSegment) recordOnce(((RowSegment) seg).row().getMetadata()); return user.test(seg); }`.

`recordOnce(RowMetadata md)` does `columnCountByExec.putIfAbsent(key, new long[]{md.getColumnMetadatas().size()})` — first writer wins. Heterogeneous batches are handled in `R2dbcExecutionAdapter.adapt`: each `QueryExecutionInfo` carries one `long[]` of length 1 (one number per execution). The v2 plan's "long[] of per-query counts" was a layering confusion — every `Statement.execute()` call produces ONE `QueryExecutionInfo`, so we have one count per execution. For batches that yield multiple Result publishers, we record the **first** column count and rely on JDBC's existing "max across results in one SqlExecution" semantic.

### Opus M4 — System properties

v3 keeps option (a): document that the four new `quickperf.sql.*` properties are read via `System.getProperty(...)` only. `application.properties` / `application.yml` / `@SpringBootTest(properties=…)` do **not** populate these. Spring-Environment bridging is a v3 deferral (R-row added to §6.3). `r2dbc.md` and the property table in §3.5 carry an explicit warning.

### Opus M9 — JDBC-side files for opaque-id refactor

`ConnectionLeakListener` (currently keyed on `Connection` instance) is refactored to track by opaque `String connectionId`. The JDBC-side change is that `QuickPerfDatabaseConnection.theDatasourceGetsTheConnection()` (lines 38-42) and `close()` propagate to the new neutral event by calling `onConnectionOpened(SqlConnectionEvent)` / `onConnectionClosed(SqlConnectionEvent)` on every active `SqlConnectionListener` retrieved from `ConnectionListenerHook`. The neutral connectionId for JDBC is `String.valueOf(System.identityHashCode(this))` (the wrapper instance, stable across the wrapper lifetime).

Files touched on the JDBC side (PR-4):
- `sql/sql-annotations/src/main/java/org/quickperf/sql/connection/QuickPerfDatabaseConnection.java` — fire neutral events alongside the existing JDBC-typed callbacks.
- `sql/sql-annotations/src/main/java/org/quickperf/sql/connection/ConnectionListener.java` — implement `SqlConnectionListener` with no-op defaults; bridge methods call neutral events.
- `sql/sql-annotations/src/main/java/org/quickperf/sql/connection/ConnectionLeakListener.java` — switch to `Set<String> openConnectionIds = ConcurrentHashMap.newKeySet()`; override `onConnectionOpened`/`onConnectionClosed`.
- `sql/sql-annotations/src/test/.../*ConnectionLeakListener*Test.java` and `*ConnectionLeak*IT.java` — adjust assertions on internal state (was `connections.size()`, now `openConnectionIds.size()`).

---

## New design choices introduced in v3 (not from either review)

1. **`io.r2dbc.proxy.callback` package extension.** Both reviews flagged the impossibility of subclassing `ResultCallbackHandler` and implementing `ProxyFactory` from scratch. Neither proposed the solution: place our implementation in r2dbc-proxy's package. v3 documents this as a deliberate, narrow seam (two classes only, both forwarding to `JdkProxyFactory`).
2. **Static `ColumnCountStore` map.** v3 picks one carrier (against Opus M7's two-option ambiguity) and documents the cleanup in `R2dbcExecutionAdapter.adapt` (last-touch removal) plus a JVM-shutdown drain hook for paranoia.
3. **`ConnectionListenerHook` (separate from `ConnectionListenerRegistry`).** Opus B2 said "mirror SqlRecorderHook". v3 picks this concretely as a standalone hook for the new `SqlConnectionListener`-typed events, leaving `ConnectionListenerRegistry` entirely untouched. This minimises blast radius for the existing JDBC paths.
4. **Alias verifiers via package-private static helper.** GPT M1 said "duplicate the verifier per alias OR alias-aware base verifier". v3 picks neither — instead, each verifier (legacy + alias) calls a package-private `static verify(...)` helper that holds the actual logic. Avoids both code duplication and complex generic gymnastics.
5. **`@Execution(SAME_THREAD)` removed entirely.** Both reviews discussed it. v3 removes the recommendation outright. The R2DBC test module uses Surefire `<parallel>none</parallel>` (already in place); user docs in `r2dbc.md` explain the constraint.
6. **Dedicated `quick-perf-r2dbc-bom` artifact (post-review user request).** Originally listed as a v3-release deferral in both this memo and the v3 plan. After the consolidation pass, the user asked for it to be in scope. v3 §2.10 introduces a sibling Maven module `bom-r2dbc/` producing `org.quickperf:quick-perf-r2dbc-bom:pom`. Its `<dependencyManagement>` pins the three QuickPerf R2DBC modules (`quick-perf-sql-annotations`, `quick-perf-sql-annotations-r2dbc`, `quick-perf-springboot-r2dbc-sql-starter`), the three test-runner modules (JUnit 4/5 + TestNG), and the three external R2DBC artifacts at the verified versions (`r2dbc-spi 1.0.0`, `r2dbc-proxy 1.1.4`, `r2dbc-pool 1.0.1`). The umbrella `quick-perf-bom` is unchanged in scope but gains the same external R2DBC pins so the two BOMs cannot drift. PR-9 owns both. Rationale: the umbrella BOM already lists every QuickPerf module (verified `bom/pom.xml` lines 30-98), but R2DBC-only consumers should not have to import the full QuickPerf surface or remember which subset they need; the dedicated BOM also pins compatible third-party versions against which `QuickPerfProxyFactory` (in `io.r2dbc.proxy.callback`) and the `ConnectionPool` decoration design were verified.

---

## Items deferred to v3 (release)

- **Spring `Environment` bridge for `quickperf.sql.*` properties.** Currently only `-D` and programmatic `System.setProperty` work.
- **Reactor `Context`-based per-test routing.** Required if/when users want to run R2DBC tests with `parallel=all`.
- **MS SQL `[id]` bracketed identifier in placeholder scanner.**
- **Oracle `Q'⟨delim⟩…⟨delim⟩'` alternative quoting.**
- **PostgreSQL `E'…'` backslash continuation across multiple `E''` strings.** Single-string backslash escapes are handled.
- **`@ExpectNo*` shorthand annotations** (current workaround: `@ExpectSelect(0)` / `@ExpectMaxQueriesNumber(0)` against a real annotation when one exists).
- **Auto-detection of R2DBC URL scheme for non-Spring users.**
- **Connection listener registry thread-safety hardening** (out-of-scope for the v2 R2DBC parity goal; current single-threaded forked-JVM mode is unaffected).

---

## Risk-row deltas (vs. v2 plan §6.1)

- **Add R-B1**: "`QuickPerfProxyFactory` placement in `io.r2dbc.proxy.callback`. Likelihood medium (r2dbc-proxy 1.x is stable; minor releases unlikely to remove `JdkProxyFactory`'s package-visible constructor). Mitigation: pin r2dbc-proxy version range in BOM; smoke-build against next minor on each upgrade."
- **Add R-B2**: "`ConnectionListenerHook` events lost if a recorder is registered AFTER `ConnectionFactory.create()` has already emitted Connections. Likelihood low for the supported lifecycle (recorders register in `startRecording` before the test body runs). Mitigation: documented contract; assertion in tests."
- **Modify R3** (CGLIB final factory): split into "ConnectionPool is final → fall back to JDK proxy with WARN" plus "non-pool ConnectionFactory is final → fall back to JDK proxy with WARN". Both are covered by `R2dbcCglibFallbackIT`.
- **Modify R4**: "`ConnectionPool.dispose()` and `close()` both verified non-final at 1.0.1. Add `R2dbcAutoConfigDisposeIT` and `R2dbcAutoConfigCloseIT`."
- **Drop R6 line** about scanner reuse for JDBC.
- **Modify R8**: stack-trace caveat unchanged; add explicit reference to `<parallel>none</parallel>` as the v2 isolation mechanism.

---

## PR breakdown deltas (vs. v2 plan §7)

- **PR-2 → PR-2 + PR-2a.** Split: PR-2 introduces `QuickPerfProxyFactory`/`QuickPerfProxyFactoryFactory` + `QuickPerfMonitoringResult` (the R2DBC adapter switch is separate). PR-2 depends on **PR-6** because the BPP must install the custom `ProxyConfig`. v2's claim "PR-2 independent" is wrong.
- **PR-7** depends on **PR-6**: the R2DBC acquisition-time listener uses the same listener-installation seam as PR-2/PR-4/PR-5. v2's claim "PR-7 only because of seam-sharing; recorder logic is independent" understates the wiring dependency.
- **PR-7 (acquisition time)** also fixes the latent `TestExecutionContext.buildPerfRecordersToExecute` deduplication bug (set never populated, line 154-164). The fix is one line: `perfRecorderClasses.add(perfRecorderClass);` after the recorder is built. The bug is harmless today (no duplicate registration scenarios in the existing annotation set) but becomes harmful once aliases share recorder classes.
- Updated dependency graph:

  ```
  PR-0 ──┬──> PR-4 ──> PR-5
         └──> PR-7
  PR-1 ──> PR-8
  PR-6 ──> PR-2
  PR-6 ──> PR-7  (acquisition wiring)
  PR-3  (independent)
  PR-9  (last; depends on all)
  ```

---

## Verified-correct from v2 plan (carried unchanged into v3)

- `SqlExecution` constructor lines (44-53, 55-59), `retrieveNumberOfReturnedColumns` (72-84), `dbExceptionHappened` (86-88), `executeMethodOnStatement` (90-92).
- `R2dbcExecutionAdapter.setBatch`/`setBatchSize` (lines 85-87), `setElapsedTime` from `Duration` (79-80), null-result Javadoc (39-40).
- `SqlRecorderHook` JVM-global `CopyOnWriteArraySet` (line 39).
- `TestConnectionProfiler` constructor-time registration (line 23-30), `cleanResources` unregistration (48-50).
- `QuickPerfR2dbcProxyBeanPostProcessor` `QuickPerfR2dbcProxyMarker` short-circuit (line 53-55) and JDK proxy creation (60-63).
- `R2dbcQuickPerfListener.afterQuery` synchronization (line 103) and JVM-global recorder dispatch (line 58).
- `PlaceholderRewriter` is package-private final, exposes `static Result rewrite(String sql)`.
- `SqlStatementBatchRecorder` registers only with `SqlRecorderRegistry` (line 39).
- `ProxyConfig.builder().proxyFactoryFactory(...)` exists (`ProxyConfig.java:90`).
- `ExecutionInfo.addCustomValue(String, Object)` / `getCustomValue(String, Class<T>)` exist (datasource-proxy 1.11.0 API).
- `ConnectionInfo.getValueStore()` exists at `r2dbc-proxy 1.1.4 ConnectionInfo.java:101`.
- r2dbc-proxy/spi/pool versions pinned: r2dbc-proxy 1.1.4.RELEASE, r2dbc-spi 1.0.0.RELEASE, r2dbc-pool 1.0.1.RELEASE.
- `PlaceholderRewriterTest.preserves_postgres_double_colon_cast_operator` exists and passes (line 55).
- Surefire `parallel=all` `threadCount=5` at root pom (line 109-110); R2DBC Spring module pins `<parallel>none</parallel>` (line 137).
