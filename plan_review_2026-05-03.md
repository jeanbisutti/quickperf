# Critical review — `remove_r2dbc_limitations_plan_2026-05-03_09-36-53.md`

**Reviewer:** Claude Sonnet 4.5 (final synthesis review against actual repo source).
**Reviewed against:** `C:\code\fork6\quickperf\sql\sql-annotations` and `…\sql\sql-annotations-r2dbc` and `…\spring\spring-boot-r2dbc-sql-starter` at the working tree HEAD on 2026-05-03.
**Plan:** v2 R2DBC parity, 9 PRs, ~570 lines.

---

## 1. Executive summary

**Verdict: NEEDS REVISION BEFORE EXECUTION.**

The plan’s strategic direction is largely correct — every limitation it lists is a real one, the synthesis chose sensible designs for the column-count, batching and listener problems, and the per-PR slicing is broadly reasonable. **But almost every concrete file path, class name, package, and method signature it cites is wrong.** A subagent told to “execute the plan as written” will fail on the first `edit` call because the files do not exist at the paths given. The plan reads like it was written against an imagined version of the codebase rather than the actual one.

In addition there are three substantive design issues that need to be reconciled before merge:

1. **§2.1 column-count decorator** — the chosen approach (wrap `Result`) cannot be installed via a `ProxyExecutionListener`; r2dbc-proxy listeners do not let you replace the value returned by `Statement.execute()`. Replacement requires either a custom `ProxyConfig`/`ProxyFactoryFactory` or a Reactor `Publisher` map applied at a different seam.
2. **§2.3/§2.4 “neutral interface” design** — the codebase already has `ConnectionListener` (an abstract class with `java.sql.Connection`-typed methods) and `ConnectionListenerRegistry` (an `InheritableThreadLocal` registry). The plan invents a parallel `R2dbcConnectionListenerRegistry` instead of either (a) extracting a JDBC-free interface from `ConnectionListener` or (b) adapting the existing registry. Either of these exists; the plan picks neither.
3. **§3.5 “filter events by ConnectionFactory instance, held weakly”** — this is hand-waved; no concrete mechanism. The bridge `R2dbcQuickPerfListener` *does not* carry a `ConnectionFactory` reference today, and `MethodExecutionInfo` never exposes one in the connection-method case. The recorder cannot, in fact, scope to a factory instance in the way the plan claims.

Approving any of PR-1/2/3/4 against the real source as-is would require ad-hoc rework. The plan must be rewritten with the actual class names, packages, and APIs before it is fit to execute.

The remainder of this review is the evidence.

---

## 2. Verified assumptions

These claims were confirmed against source.

| Claim | Where verified |
|---|---|
| `r2dbc-proxy` 1.1.4 is the pinned version. | `spring/spring-boot-r2dbc-sql-starter/pom.xml:30` |
| `r2dbc-spi` 1.0.0.RELEASE is the pinned version. | `spring/spring-boot-r2dbc-sql-starter/pom.xml:29` |
| `ResultRowConverter#onGet(Row, Method, Object[], GetOperation)` does **not** expose `RowMetadata`. | `io.r2dbc.proxy.listener.ResultRowConverter` — lines 41–55 of upstream `v1.1.4.RELEASE`. The interface is `(Row, Method, Object[], GetOperation) -> Object`. ✔ |
| `Result.map(BiFunction<Row, RowMetadata, T>)` is the only path for column metadata. | `io.r2dbc.spi.Result` 1.0.0 surface; confirmed by inspection of `R2dbcExecutionAdapter`’s lack of any other path. ✔ |
| `ExecutionInfo.addCustomValue(String, Object)` / `getCustomValue(String, Class<T>)` exist and are stable since 1.5.1. | `net.ttddyy:datasource-proxy:1.11.0` `ExecutionInfo.java` lines `addCustomValue(String, Object)` and `getCustomValue(String, Class<T>)`. ✔ |
| `ProxyExecutionListener` is the listener interface in r2dbc-proxy. | `io.r2dbc.proxy.listener.ProxyExecutionListener` v1.1.4: `beforeMethod / afterMethod / beforeQuery / afterQuery / eachQueryResult`. ✔ |
| `MethodExecutionInfo.getConnectionInfo().getConnectionId()` is a `String`. | `io.r2dbc.proxy.core.ConnectionInfo#getConnectionId()` v1.1.4 returns `String`. ✔ |
| The `R2dbcExecutionAdapter` uses `ConnectionInfo.getConnectionId()` already. | `R2dbcExecutionAdapter.java:74-77`. ✔ |
| `r2dbc-spi` 1.0 `Connection` exposes `beginTransaction / commit / rollback / setTransactionIsolationLevel / setAutoCommit / createStatement / createBatch / close / createSavepoint / releaseSavepoint / rollbackTransactionToSavepoint`. | r2dbc-spi 1.0.0 source. ✔ |
| `SqlRecorderHook` is a JVM-global hook backed by `CopyOnWriteArraySet`. | `sql/sql-annotations/src/main/java/org/quickperf/sql/SqlRecorderHook.java:37-55`. ✔ |
| Surefire runs `parallel=all threadCount=5`. | Already documented in `CLAUDE.md`. ✔ |
| The R2DBC adapter currently sets `executionInfo.setBatch(...)` and `setBatchSize(...)`. | `R2dbcExecutionAdapter.java:85-87`. *(This actually contradicts a different plan claim — see §3.)* |
| `spring-aop` is transitively on the classpath of the R2DBC starter via `spring-boot-autoconfigure → spring-boot → spring-context → spring-aop`. | `mvn dependency:tree` on `spring-boot-r2dbc-sql-starter` shows `org.springframework:spring-aop:6.1.6:provided`. ✔ |
| CGLIB is bundled inside `spring-core` 6.x as `org.springframework.cglib`. | Spring 6 has shipped CGLIB inlined since 5.x; reachable from the same dependency chain. ✔ |
| The constructor `SqlExecution(ExecutionInfo, List<QueryInfo>, long columnCount)` already exists, bypassing auto-detect. | `SqlExecution.java:55-59`. ✔ — *the plan never mentions this and overlooks the simplest fix.* |

---

## 3. Falsified assumptions

These claims are wrong against source. For each: claim → evidence → suggested correction.

### 3.1 (HIGH) `SqlExecution.retrieveNumberOfReturnedColumns` lives at `SqlExecution.java:114-126` with a 3-argument signature

**Claim** (plan §2.1): “Update the helper currently at `SqlExecution.java:114-126`” with the proposed signature `retrieveNumberOfReturnedColumns(ExecutionInfo, QueryInfo, boolean atLeastOneSelect)`.

**Evidence**: The actual method is at `SqlExecution.java:72-84`, with a single-argument signature `retrieveNumberOfReturnedColumns(ExecutionInfo executionInfo)`. The `atLeastOneSelect` guard is **not inside** this helper at all — it sits in the constructor at line 50: `if (atLeastOneSelect(queries)) { this.columnCount = retrieveNumberOfReturnedColumns(executionInfo); }`. So the helper is never called for non-SELECT queries; the proposed “bypass the JDBC-only `atLeastOneSelect` guard” cannot happen by changing only the helper.

**Suggested correction**: Either modify the constructor (lines 44-53) to consult the custom value before the `atLeastOneSelect` test, *or* — far simpler — make the R2DBC adapter call the existing `SqlExecution(ExecutionInfo, List<QueryInfo>, long columnCount)` constructor at line 55-59, which already exists and skips the auto-detect entirely. The plan misses this constructor and writes a new branch instead. Recommend the constructor route.

### 3.2 (HIGH) `QueryParamsExtractor` lives in `org.quickperf.sql.formatter` with a method `replacePlaceholderWithParam(String, ParameterSetOperation)`

**Claim** (plan §1.2 line 42, §2.5 line 235, §2.6 lines 290–296, §3.1 file inventory): The class is at `org.quickperf.sql.formatter.QueryParamsExtractor` and has a method `replacePlaceholderWithParam(String sql, ParameterSetOperation op)` performing two regex passes (`\\?` and `\\$\\d+`).

**Evidence**: 
- The actual location is `org.quickperf.sql.select.analysis.QueryParamsExtractor` (`sql/sql-annotations/src/main/java/org/quickperf/sql/select/analysis/QueryParamsExtractor.java`).
- The class is **package-private** (`class QueryParamsExtractor` at line 22) — not public — so a “refactor of the public API” is a misnomer.
- It has only two methods: `getParamsOf(QueryInfo)` and `retrieveParameterSetOperations(QueryInfo)`. **`replacePlaceholderWithParam` does not exist anywhere in the codebase** — `grep` confirms it is only referenced in the plan itself.
- The actual placeholder rewriter is `org.quickperf.sql.r2dbc.PlaceholderRewriter` (package-private final class in `sql-annotations-r2dbc`), which exposes `static Result rewrite(String sql)`. It has known limitations (literals, comments) but with a different API surface than what the plan describes.
- Display rendering goes through `QuickPerfSqlFormatter → DefaultQueryLogEntryCreator` (datasource-proxy) which performs its own substitution from `setObject(index, value)` operations. There is no QuickPerf code path that does literal substitution into the SQL string for JDBC.

**Suggested correction**:
- Rename §2.5 and §2.6 references to the correct class `org.quickperf.sql.select.analysis.QueryParamsExtractor`.
- Drop the entire premise of replacing `replacePlaceholderWithParam`. The R2DBC rewrite work is in `PlaceholderRewriter` (in `sql-annotations-r2dbc`); the JDBC path doesn’t need a scanner because dsproxy already handles the rendering. Move `SqlPlaceholderScanner` (if introduced) into `sql-annotations-r2dbc` and wire it into `PlaceholderRewriter.rewrite`, not into a fictitious JDBC method.
- The “Free win: switching to the scanner fixes a long-standing JDBC bug where `'?'` literals are mis-rendered” (plan §2.6.F) is **invented**; no such bug exists in QuickPerf because dsproxy renders by index, not by regex.

### 3.3 (HIGH) `SqlStatementBatchRecorder` lives at `org.quickperf.sql.SqlStatementBatchRecorder`

**Claim** (plan §1.2 line 37, §2.2, §4.2): The class to dual-register with `SqlRecorderHook` is at `org.quickperf.sql.SqlStatementBatchRecorder` and currently registers “only with `SqlRecorderRegistry`”.

**Evidence**: Actual path is `org.quickperf.sql.batch.SqlStatementBatchRecorder` (`sql/sql-annotations/src/main/java/org/quickperf/sql/batch/SqlStatementBatchRecorder.java`). The `register/unregister` part of the claim is correct (lines 39, 44). But the plan’s additional claim — that “R2dbcExecutionAdapter never carries the batch flag” — is **falsified** by `R2dbcExecutionAdapter.java:85-87`:

```java
boolean isBatch = info.getType() == ExecutionType.BATCH || info.getBatchSize() > 1;
execInfo.setBatch(isBatch);
execInfo.setBatchSize(info.getBatchSize());
```

The flag is already set today. The actual gap is one of dispatch (`SqlStatementBatchRecorder` listens on `SqlRecorderRegistry`, not on `SqlRecorderHook`) — fixing only that single registration call closes the gap. The plan’s §2.2.B (“set batch flags for both flavours in the adapter”) is unnecessary work; both flavours are already covered.

**Suggested correction**:
- Update §1.2 root cause to “recorder registered only on `SqlRecorderRegistry`; not on `SqlRecorderHook`”.
- Delete §2.2.B; the adapter already does this.
- Fix the package path everywhere.

### 3.4 (HIGH) `ConnectionLeakAnalyzerListener` and `ConnectionEventsRecorder` are real classes in the codebase

**Claim** (plan §1.2 line 39–40, §2.3.D, §2.4): Two classes — `ConnectionLeakAnalyzerListener` (lines 170, 180, 193, 715) and `ConnectionEventsRecorder` (lines 207, 217, 222–224, 574) — exist as the recorders/listeners to update.

**Evidence**: Neither class exists. `grep -r "class ConnectionLeakAnalyzerListener"` and `grep -r "class ConnectionEventsRecorder"` both return zero hits. The actual classes are:
- `org.quickperf.sql.connection.ConnectionLeakListener` (extends `ConnectionListener`, implements `RecordablePerformance<BooleanMeasure>`). Manages a `List<Connection>`, not counters; on `stopRecording` checks `!connections.isEmpty()`. There is no `ConnectionLeakAnalyzer.compareOpensAndClosing(...)` method (the plan §2.3.D references one — it doesn’t exist).
- `org.quickperf.sql.connection.TestConnectionProfiler` is the recorder for `@ProfileConnection`. It wraps `ConnectionEventsProfiler` (a `ConnectionListener` subclass that prints to a `PrintWriter`). There is no “events list” or `formatAsText()` method (plan §2.4.D).
- `org.quickperf.sql.connection.ConnectionListenerRegistry` (singleton, exists today, line 22) is the actual registry — it uses `InheritableThreadLocal<Map<Class, ConnectionListener>>` for in-process tests and `ArrayList` for forked-JVM tests. The plan invents a parallel `R2dbcConnectionListenerRegistry` instead of touching this one.

`ConnectionListener` itself is an **abstract class** (not an interface), and every method takes a `java.sql.Connection`. So implementing it from R2DBC code requires a stub `Connection` (impossible in r2dbc-only modules) or an adapter that maps R2DBC connection events to JDBC-typed callbacks. Either way the plan’s described change does not work without modifying `ConnectionListener` itself.

**Suggested correction**: Rewrite §2.3 and §2.4 from scratch:
1. Verify that `ConnectionListener` should be split into a JDBC-typed subclass and a JDBC-free supertype. *Concretely*: introduce a new interface `org.quickperf.sql.connection.SqlConnectionListener` with two events `onConnectionOpened(String connectionId)` / `onConnectionClosed(String connectionId)` plus method-level events for profiling (`onConnectionMethodEvent(name, args, durationNanos, threadName)`). Make `ConnectionListener` (existing abstract class) call into a `SqlConnectionListener` adapter so JDBC code paths are unchanged.
2. Make `ConnectionLeakListener` track by **opaque connection id** (string), not by `java.sql.Connection` instance. Then both JDBC (`QuickPerfProxyDataSource.getConnection()` → adapter publishes `connection.toString()` or a UUID we attach) and R2DBC (`ConnectionInfo.getConnectionId()`) feed the same data structure.
3. Reuse `ConnectionListenerRegistry` for the new neutral interface; do not introduce a parallel registry.
4. The new R2DBC bridge listener (`R2dbcConnectionLifecycleListener`) implements `ProxyExecutionListener` and on `afterMethod` for `Connection.close()` (target is `Connection`, method name is `close`, no args) and `ConnectionFactory.create()` `afterQuery`/`afterMethod` emits the neutral events into the registry.

This is more work than the plan suggests but it’s also the only way that won’t leave two parallel registries and two parallel verifier paths.

### 3.5 (HIGH) Spring BPP class is `org.quickperf.spring.r2dbc.R2dbcBeanPostProcessor`

**Claim** (plan §2.7 line 354, §3.1, §4.2): The Bean Post Processor is named `R2dbcBeanPostProcessor` in package `org.quickperf.spring.r2dbc`.

**Evidence**: The actual class is `org.quickperf.spring.boot.r2dbc.QuickPerfR2dbcProxyBeanPostProcessor`, with sibling `QuickPerfR2dbcProxyBeanAutoConfiguration`, `QuickPerfR2dbcConfig`, and a marker interface `QuickPerfR2dbcProxyMarker` for idempotency. The package is `org.quickperf.spring.boot.r2dbc`, not `org.quickperf.spring.r2dbc`. The class is also more careful than the plan implies — it already short-circuits via `if (bean instanceof QuickPerfR2dbcProxyMarker)` at line 53 to avoid double-wrapping.

The “marker interface” pattern is significant for the CGLIB switch: if we move to CGLIB, the marker still needs to be enforced (since CGLIB-subclassing produces a class that is not equal to the marker by default). The plan never mentions the marker.

**Suggested correction**: Fix every reference to `R2dbcBeanPostProcessor` and `R2dbcConnectionFactoryUtils` to the correct names. Add explicit handling of `QuickPerfR2dbcProxyMarker` in the CGLIB outer-proxy path (the CGLIB subclass must explicitly add `QuickPerfR2dbcProxyMarker.class` to its interfaces, and `instanceof QuickPerfR2dbcProxyMarker` must short-circuit there too).

### 3.6 (MEDIUM) `SystemProperties` lives at `org.quickperf.sql.framework.SystemProperties`

**Claim** (plan §2.5, §3.4 line 497, §4.2 line 575): A class `org.quickperf.sql.framework.SystemProperties` houses `evaluate()` and the new properties.

**Evidence**: The actual class is `org.quickperf.SystemProperties` in `core/`. The package `org.quickperf.sql.framework` exists but contains only `JdbcSuggestion`, `R2DBCSuggestion`, and `ClassPath`-related helpers — not `SystemProperties`. Properties are added as static fields on `org.quickperf.SystemProperties` (e.g. `WORKING_FOLDER` at line 19, `TEST_CODE_EXECUTING_IN_NEW_JVM` at line 35). New properties belong here, not in `sql.framework`.

**Suggested correction**: Update §2.5, §3.4, §4.2 to reference `org.quickperf.SystemProperties`.

### 3.7 (MEDIUM) `R2dbcConnectionFactoryUtils` is the place to install listeners non-Spring users

**Claim** (plan §2.3.F, §2.4, §4.2 line 580): A class `R2dbcConnectionFactoryUtils` exists with a `wrap(...)` method that installs listeners.

**Evidence**: No file by that name exists in the working tree. The non-Spring entry point is `org.quickperf.sql.r2dbc.config.QuickPerfR2dbcConnectionFactoryBuilder`. The plan’s file inventory in §4.2 says “Edit `R2dbcConnectionFactoryUtils.java`” — that file does not exist.

**Suggested correction**: Either rename the plan’s references to `QuickPerfR2dbcConnectionFactoryBuilder` (existing) or, if a new utility class is intended, declare it as a *new* file in §4.1 and explain its purpose vs. the existing builder.

### 3.8 (MEDIUM) datasource-proxy version is 1.7

**Claim** (plan §2.1.B): “Datasource-proxy 1.7 exposes `ExecutionInfo.addCustomValue(...)`.”

**Evidence**: The repo uses datasource-proxy 1.11.0 (`mvn dependency:tree` confirms `net.ttddyy:datasource-proxy:1.11.0`). The API has existed since 1.5.1 (Javadoc on the method confirms `@since 1.5.1`). So the API is present, but the plan’s version pin is wrong.

**Suggested correction**: Change “1.7” to the actual version, or simply drop the version qualification (the method has been stable since 1.5.1).

### 3.9 (LOW) `core/` and `sql-annotations` target “JDK 7 bytecode”

**Claim** (plan §2.1, §3.2, §3.3): Modules are JDK 7 bytecode, no `Optional`, no `var`.

**Evidence**: `CLAUDE.md` says “The project targets JDK 1.7 bytecode compatibility for core modules (enforced via `maven-enforcer-plugin`)”. The actual `R2dbcExecutionAdapter.java` *does* use `Duration` (`java.time`, JDK 8+) at line 79–80 — confirming `sql-annotations-r2dbc` is JDK 8+. So the plan is correct about `core` and `sql-annotations` being JDK 7, but `sql-annotations-r2dbc` is already JDK 8+. The plan doesn’t state this clearly.

**Suggested correction**: Clarify in §3.2 that `sql-annotations-r2dbc` is JDK 8+ and may use `Duration`, lambdas, etc.

### 3.10 (LOW) `getNumberOfReturnedColumns()` is the verifier method name

**Claim** (plan §2.1 line 64): “verifiers (line 117 `getNumberOfReturnedColumns()`)”.

**Evidence**: The verifier reads `sqlExecution.getColumnCount()` (line 267 of `SqlExecution.java`); via `SqlExecutions.getMaxNumberOfSelectedColumns()` (line 148-157 of `SqlExecutions.java`). There is no method called `getNumberOfReturnedColumns()` anywhere in the codebase.

**Suggested correction**: Change the citation to `SqlExecution.getColumnCount()` / `SqlExecutions.getMaxNumberOfSelectedColumns()`.

---

## 4. Blind spots

Issues the plan misses, ordered by severity.

### 4.1 (HIGH) `QuickPerfMonitoringResult` cannot be installed via `ProxyExecutionListener`

**Description**: The plan’s §2.1.A says “In `R2dbcQuickPerfListener.afterQuery(QueryExecutionInfo)` we replace each `io.r2dbc.spi.Result` returned by the call (via reflection on `QueryExecutionInfo.getValueStore()`/`MethodExecutionInfo.getResult()` or by intercepting `Statement.execute`)”. But:

- `QueryExecutionInfo` (verified above against the upstream r2dbc-proxy 1.1.4 source) **does not expose the underlying `Publisher<? extends Result>`**. The closest is `getCurrentMappedResult()`, which is the *user-mapped* output during the `eachQueryResult` callback — read-only.
- `MethodExecutionInfo.getResult()` returns the result of an arbitrary method invocation (e.g. for `Statement.execute()` it returns the `Publisher<? extends Result>`). But by the time `afterMethod` fires, the publisher reference **is already in the user’s hands** — replacing the value held by the proxy does not change what the user already received.
- `ValueStore` is a key/value side-channel for the listener, not a way to substitute return values.

**Why it matters**: The chosen design literally cannot be implemented as described. The actual mechanism in r2dbc-proxy for replacing returned values is **not** the listener interface — it is the `CallbackHandler` / `ProxyConfig` chain (`ProxyConfig.getProxyFactoryFactory()` and the per-`Result` handler `ResultCallbackHandler` in package `io.r2dbc.proxy.callback`), or building a custom `ProxyConnectionFactory` that wraps `Statement.execute()` results before they leave the proxy.

**Suggested mitigation**: Three options, ordered by viability:
1. **Replace the proxy chain’s `Result` handler.** Use `ProxyConfig.builder().proxyFactoryFactory(custom)` to install a `ProxyFactoryFactory` whose `createResultProxy` step wraps every produced `Result` with `QuickPerfMonitoringResult`. This is the supported extension hook.
2. **Use `eachQueryResult` plus a `ResultRowConverter` per row.** The plan acknowledges this can’t see `RowMetadata`. But in practice, the *first* call to `Row.get(int)` or `Row.get(String)` is enough to know the column count if we count distinct keys/positions seen. Materialise a count from row-level observations. Lossy on edge cases but no proxy-chain surgery.
3. **Run the column-count off-band.** Capture `RowMetadata.getColumnMetadatas().size()` only when the user calls `Result.map(BiFunction)`, by replacing the supplied `BiFunction` with a wrapper. This requires intercepting `Result.map` itself — same proxy-chain plumbing as option 1.

The synthesis chose option 1 conceptually but described it as a listener-level operation, which it is not. The plan needs to call out that the implementation goes through `ProxyConfig`/`ProxyFactoryFactory`, not through `ProxyExecutionListener`.

### 4.2 (HIGH) JVM-global registry + `parallel=5` cannot scope events per test

**Description**: `SqlRecorderHook` is JVM-global via `CopyOnWriteArraySet<SqlRecorder<?>>`. With Surefire `parallel=all threadCount=5`, multiple test methods register/unregister concurrently. Recorders that aren’t per-test-instance (and most are singletons or per-instance) need a way to attribute incoming events to the right test.

The plan §3.5 says: “the recorder must filter events by `ConnectionFactory` instance (held weakly so it isn’t pinned). The JDBC equivalent already does this via `QuickPerfProxyDataSource` instance equality; we follow the same approach for R2DBC by carrying the wrapped `ConnectionFactory` reference in `R2dbcConnectionEvent`.”

**Why it matters**: This is hand-waved. Three problems:
1. R2DBC-proxy listener callbacks (`afterQuery`, `afterMethod`) receive `MethodExecutionInfo` / `QueryExecutionInfo`. Neither exposes the `ConnectionFactory` instance — only `ConnectionInfo` (which has the *original* `Connection`, not the factory). So the recorder cannot recover “which factory am I from?” from the event.
2. The current `R2dbcQuickPerfListener` constructor takes a `beanName` (String) — *not* a `ConnectionFactory` reference. Recorders can match on bean name, but the plan doesn’t say so.
3. With `r2dbc-pool`, a single `ConnectionPool` bean is shared across many tests; matching by factory instance does not narrow events to one test method.

**Suggested mitigation**: Tests under `parallel=all` need either:
- A test-thread-scoped key (e.g. correlation token in Reactor `Context`) — but R2DBC users frequently mix thread switches and the test thread is not the recording thread.
- Per-test recorder registration with a unique listener id, and dispatch through `synchronized (recorder)` (already done at `R2dbcQuickPerfListener.java:103`). The current code already gives each recorder its own bookkeeping; the *test* attribution problem is upstream — i.e., once a recorder is registered, every event for any test goes to it.
- The pragmatic path is **document a known gap**: under `parallel=all`, R2DBC tests that share a `ConnectionFactory` bean across tests will see cross-test contamination. Either (a) require tests using R2DBC annotations to declare `@Execution(SAME_THREAD)`, or (b) recommend `parallel=classes`. The plan should explicitly call this out and add a guard.

### 4.3 (HIGH) `R2dbcConnectionEventsListener` registration timing

**Description**: §2.4.D says “`ConnectionEventsRecorder.startRecording()` registers itself with both `SqlRecorderRegistry` and `R2dbcConnectionEventsListenerRegistry`.” But the actual recorder is `TestConnectionProfiler`, which registers a `ConnectionEventsProfiler` (a `ConnectionListener`) in its **constructor**, not in `startRecording`. See `TestConnectionProfiler.java:23-30`:

```java
public TestConnectionProfiler(AnnotationProfilingParameters annotationProfilingParameters) {
    ...
    if(annotationProfilingParameters.isBeforeAndAfterTestMethodExecution()) {
        connectionEventsProfiler.start();
    }
    ConnectionListenerRegistry.INSTANCE.register(connectionEventsProfiler);
}
```

The reason for early registration is that profiling can begin *before* the test method body. The plan’s recipe (“dual-register in `startRecording`/`stopRecording`”) wouldn’t cover that case — it would silently break `isBeforeAndAfterTestMethodExecution()`.

**Why it matters**: A subtle test-suite regression for any `@ProfileConnection(beforeAndAfterTestMethodExecution=true)`-marked tests would be introduced.

**Suggested mitigation**: Update §2.4 to mirror the constructor-and-cleanup pattern: register both listeners in the constructor of `TestConnectionProfiler`, unregister both in `cleanResources()`. Note this in the file inventory.

### 4.4 (HIGH) `R2DBC_SELECTED_COLUMN_COUNT` per-`ExecutionInfo` semantics for batches

**Description**: The plan §2.1.B writes the column count as a custom value on `ExecutionInfo`. But `R2dbcExecutionAdapter.adapt` (and `SqlRecorder.afterQuery`) handle one `ExecutionInfo` per *call*, with N `QueryInfo`s inside (for batches). If a `Batch` mixes a `SELECT a` and a `SELECT a, b, c`, only one column count fits on the `ExecutionInfo`. **Who wins?** The plan does not say.

**Why it matters**: With `Batch.add(sql1).add(sql2)`, multiple results may emerge with different column counts. The verifier `MaxSelectedColumnsPerfIssueVerifier` works on a *max*; if we publish only one count per `ExecutionInfo`, the wrong one might end up there.

**Suggested mitigation**:
- Make the custom value a `long[]` keyed by query index (or a `List<Long>`), one entry per query.
- Update `SqlExecution.retrieveNumberOfReturnedColumns` to consult the array per-`QueryInfo` (this is one reason the plan’s 3-arg signature was conceptually right; see §3.1).
- Document the semantics: “column count = max across results for the call” for `@ExpectMaxSelectedColumn`, exact-match-per-result for `@ExpectSelectedColumn`.

### 4.5 (MEDIUM) Test isolation and `Hooks.onOperatorDebug`

**Description**: §2.4.C proposes an opt-in `quickperf.sql.profileConnection.r2dbc.operator-debug=true` that calls `Hooks.onOperatorDebug()` once at recorder start. **`Hooks.onOperatorDebug()` is process-global.** Reactor explicitly warns that this hook impacts *all* publishers in the JVM until reset, and is for debugging only. It is not safe to enable per-test-method under `parallel=all`: one test enabling it will instrument the world for every other test until reset.

**Why it matters**: A `@ProfileConnection(operator-debug=true)` test concurrent with a perf test (`@ExpectMaxQueryExecutionTime`) will perturb the perf test’s measurements (5–10× CPU overhead per the plan’s own admission).

**Suggested mitigation**: Document this as a “forking-required” feature: the test method must be in its own forked JVM (`@HeapSize` or equivalent forces this) before `operator-debug` may be enabled. Block enabling it otherwise, with a clear failure message. Or remove the property and require users to set `-Dreactor.tools.agent` themselves.

### 4.6 (MEDIUM) `ConnectionPool` `instanceof` and Spring lifecycle hooks

**Description**: The plan §2.7.D says the CGLIB subclass forwards `dispose()` and `close()` so Spring Boot’s `@PreDestroy` works. But Spring Boot 3’s `R2dbcAutoConfiguration` registers `disposeBean(...)` based on `DisposableBean` / inferred `dispose` method. The actual `ConnectionPool` from `r2dbc-pool` 1.0.x **implements `Closeable`** (`close()` returns `Mono<Void>`) and exposes `dispose()` (`Mono<Void>`). Spring Boot’s lifecycle calls `dispose()` reflectively at shutdown.

CGLIB-subclassing is fine for forwarding; the gotcha is that Spring Boot’s `inferredDestroyMethod` resolution looks for a method named exactly `close` or `dispose` on the *bean class*, then the *declared* class — and CGLIB subclasses do declare them. So this should work, but the plan asserts it without verifying. The risk class is `final` methods on `ConnectionPool` — if `dispose()` is `final`, CGLIB cannot intercept it (it can be called but not advised). The plan should verify `ConnectionPool#dispose()` is non-final.

**Why it matters**: A CGLIB-subclass test that asserts `dispose()` count = 1 (plan §5.2 `R2dbcAutoConfigDisposeIT`) would detect a regression — but only if the test is actually written and runs.

**Suggested mitigation**: Verify `ConnectionPool#dispose()` is not `final` in r2dbc-pool 1.0.x. If it is, the inner-bean must implement the lifecycle directly; the CGLIB subclass would be a no-op for that path.

### 4.7 (MEDIUM) Single-pass scanner: MySQL `#` comments and backtick identifiers

**Description**: §2.6.A enumerates lexer modes for `--`, `/* */`, `'`, `"`, dollar-quoted strings. **Missing**: MySQL `#` line comments, MySQL backtick (`` ` ``) identifiers, MS SQL Server bracketed identifiers (`[col]`). R2DBC drivers exist for MySQL (`r2dbc-mysql`), MariaDB (`r2dbc-mariadb`), MS SQL (`r2dbc-mssql`).

**Why it matters**: For an R2DBC user using MariaDB:
```sql
SELECT `col?` FROM t -- ? not a placeholder
```
The scanner as described would (incorrectly) consume the `?` inside backticks. Same problem as the regex implementation it’s trying to replace.

**Suggested mitigation**: Add backticks (toggle into `BACKTICK_IDENTIFIER` mode) and `#`-line comments (toggle into `LINE_COMMENT` until newline). Bracket identifiers `[ ]` are MS SQL only and have known parsing ambiguities; either support them or document as out-of-scope.

### 4.8 (MEDIUM) Stack trace caveat — the JDBC path is not as good as the plan claims

**Description**: §2.4.C says “JDBC’s `ConnectionEventsRecorder` captures the stack via `Thread.currentThread().getStackTrace()` at the call site, where the user’s frame is on the stack.” Actual JDBC implementation is in `ConnectionProfiler.findCurrentStackTrace()` (line 70-73): it captures the stack on the recorder thread. For HikariCP, this is the test thread — frame is present. For R2DBC, it’s a Reactor scheduler thread — frame is absent. The plan’s premise is correct, but its claim that JDBC always shows the user frame is broad-strokes — async JDBC drivers (and Hikari with `connectionTestThread`) sometimes also give scheduler frames.

**Why it matters**: Documentation must be careful not to over-promise the JDBC behaviour while explaining the R2DBC limitation, lest users complain that JDBC also shows scheduler frames.

**Suggested mitigation**: Soften the wording in §2.4 and the doc to “JDBC typically captures user frames; R2DBC almost never does without `Hooks.onOperatorDebug`”.

### 4.9 (MEDIUM) Stack-trace identity collision — `connection.hashCode()`

**Description**: `ConnectionProfiler.computeIdentifier(Connection)` (line 79-81) uses `connection.hashCode()` as the identifier. For R2DBC, the proxy `Connection` object will be a different instance from the underlying connection, and `hashCode()` may collide across pooled connections. The plan reuses `ConnectionEventsRecorder` (which doesn’t exist) for both paths — so this aliasing risk is invisible to the plan.

**Why it matters**: Reports may show two distinct connections with the same id, confusing users.

**Suggested mitigation**: Switch the JDBC path to `System.identityHashCode(connection)` and the R2DBC path to `ConnectionInfo.getConnectionId()` (already a UUID). Format reports to print the connection id verbatim.

### 4.10 (MEDIUM) `SqlExecution.Externalizable` and serialization compat

**Description**: `SqlExecution` is `Externalizable` (line 33) with a defined `writeExternal/readExternal` (lines 107-204). It writes `columnCount` as a `long` at line 111. The plan §5.1 mentions “verify SqlExecution deserializes the extra `columnCount` correctly (it already does, line 111)”. ✔ this is correct.

**But**: If we extend §4.4’s suggestion (column count per QueryInfo, not per call), the serialized form changes. PR-2 must therefore add a version byte to the externalisation format and tolerate both the old and new layouts, or break wire compat for forked-JVM tests. The plan does not call this out.

**Suggested mitigation**: Either keep the single-`long` field (and make column count = max across queries in the batch — define this clearly), or add a version byte. Document the choice in PR-2.

### 4.11 (MEDIUM) `try/finally` discipline in registries

**Description**: The plan §3.3 says “`CopyOnWriteArraySet` for listeners” without mandating `try/finally` in recorder lifecycle. Looking at the existing `ConnectionLeakListener.startRecording/stopRecording`, there is no `try/finally` — if `startRecording` throws after `register`, the recorder leaks. Multiplying this by R2DBC adds a second registry; the surface for leakage doubles.

**Why it matters**: A leaked recorder receives events from subsequent tests. With JVM-global registries this means one bug poisons every following test.

**Suggested mitigation**: Add an invariant: every `register(...)` must be paired with a `finally`-block `unregister(...)` in the lifecycle. Add a smoke test (`R2dbcRecorderLeakTest`) that registers then throws, asserts the registry is empty.

### 4.12 (MEDIUM) PR-1 vs PR-8 ordering and feature flags

**Description**: §7 says PR-1 and PR-8 are independent. **They aren’t fully**:
- PR-1 changes `getParamsOf → getAllParamsOf` and updates `SelectAnalysisExtractor.SqlSelects.add` to iterate over each binding.
- PR-8 swaps the placeholder rewriter to a single-pass scanner.

Both touch the *path* used by `@DisableSameSelectTypesWithDifferentParamValues` in different ways. If PR-1 lands first and `getAllParamsOf` returns one list per binding, PR-8’s lexer is then asked to render *each binding’s* placeholder substitution — a code path that didn’t exist before. PR-8 must be tested with multi-binding inputs, which only PR-1 enables.

**Suggested mitigation**: State “PR-8 depends on PR-1” in §7 and refactor the order. Or unify them into a single PR.

### 4.13 (LOW) Annotation matrix backwards compatibility

**Description**: §3.6 says “All v1 R2DBC test methods continue to pass without code changes.” But:
- §2.5.D drops the `IllegalStateException` thrown in `getParamsOf`. Any user code that catches the exception (unlikely but possible) will silently change behaviour.
- §2.1.C bypasses the `atLeastOneSelect` guard for R2DBC. If a user’s INSERT-without-RETURNING in R2DBC was previously reporting columnCount = 0 and a test asserted `@ExpectMaxSelectedColumn(0)` *passes* on it — well, the new code would still report 0 (no `RowMetadata` available because `Result.map` not called), so this is fine. But the comment in the plan about `INSERT ... RETURNING` will *now* make `@ExpectMaxSelectedColumn(0)` fail on a query that previously slipped through. Document as a (correct) behaviour change, not as “backwards compatible”.

**Suggested mitigation**: Add a “Behaviour changes” subsection to §3.6 listing the two above.

### 4.14 (LOW) `@ExpectMaxConnectionAcquisitionTime` recorder activation

**Description**: §2.8 says “in `QuickPerfProxyDataSource.getConnection()`, capture `start = nanoTime()` before delegating, `end = nanoTime()` after. Emit a `ConnectionAcquisitionEvent`.” It does not say *when* the acquisition recorder is active. If we always measure, every JDBC `getConnection()` pays the wall-clock cost (negligible) AND every event is published (more expensive on a hot path) regardless of whether any test cares. The plan should specify: only measure when the annotation is on the test, by registering the recorder in `startRecording` and short-circuiting when not registered.

**Suggested mitigation**: Specify the activation gate. Mirror the JDBC equivalents (`@ExpectMaxQueryExecutionTime`).

### 4.15 (LOW) License header

**Description**: §3.7 documentation list includes `r2dbc.md`, `README.md`, `CHANGELOG.md`, `BENCHMARKING.md`. The Apache 2.0 license header requirement is mentioned in §5.4 (“mvn license:format”). It is **not** mentioned anywhere as a concrete step in the PR descriptions. Confirm by inspection: `CLAUDE.md` says “All source files must have the Apache 2.0 license header.”

**Suggested mitigation**: Add to each PR’s checklist: “Run `mvn license:format` to add headers to new files.”

### 4.16 (LOW) BPP marker interface in CGLIB path

**Description**: The current BPP idempotency check is `if (bean instanceof QuickPerfR2dbcProxyMarker) return bean;` (line 53). When we switch to a CGLIB subclass, the subclass must explicitly implement `QuickPerfR2dbcProxyMarker` (CGLIB allows adding interfaces via `setInterfaces` on `ProxyFactory`). The plan does not mention this.

**Suggested mitigation**: Note in §2.7 that the outer CGLIB proxy must add `QuickPerfR2dbcProxyMarker` to its declared interfaces, and add an explicit unit test.

---

## 5. Synthesis quality

The synthesis was asked to choose between two design subagents at four explicit points. Verdict per point:

### §2.1 column-count approach (Result decorator vs `SqlRecorder` overload)

**Plan’s choice**: Result decorator + `ExecutionInfo.addCustomValue`.

**Verdict**: ✔ Conceptually right, but the implementation seam is wrong (see §4.1). The alternative (`SqlRecorder` overload) would have coupled `core` to r2dbc-spi which is correctly rejected. The `addCustomValue` carrier choice is sound. The reason for rejecting the overload is correct (JDK 7 bytecode + decoupling). Just fix the “how” — install the decorator via `ProxyConfig`, not via the listener.

### §2.3 neutral interface vs reflective factory

**Plan’s choice**: Neutral interface (GPT-5.5’s).

**Verdict**: ✗ Right philosophy, wrong premise. The plan claims this “adds ~80 LOC and zero reflection.” But the plan invents a parallel registry and ignores the fact that `ConnectionListener` exists today as an abstract class with `java.sql.Connection`-typed methods. The right answer is:
1. Refactor `ConnectionListener` to an abstract class implementing a new `SqlConnectionListener` interface that uses opaque connection ids.
2. Reuse `ConnectionListenerRegistry` for both APIs.

This is GPT-5.5’s philosophy applied honestly. The plan picks the philosophy and then ignores the existing infrastructure, ending up with two parallel listener stacks. **Revise.**

### §2.5 multi-binding semantics

**Plan’s choice**: `getAllParamsOf` returns `List<List<Object>>`; `SelectAnalysisExtractor.SqlSelects.add` iterates and adds one logical SqlSelect per binding tuple.

**Verdict**: ✔ Sound semantically. A batched `SELECT ... WHERE id = ?` bound 3 times *is* the antipattern. The opt-out flag is a pragmatic safety net. The change is independent of v1 R2DBC behaviour.

But: the rename from `getParamsOf` → `getAllParamsOf` is unnecessary, and the “transition aid” deprecated wrapper that calls `.get(0)` is **harmful**: `.get(0)` on an empty list throws `IndexOutOfBoundsException`, and the existing call sites (`SelectAnalysisExtractor.SqlSelects` lines 84, 95, 105) pass through queries that may have zero bindings. Drop the deprecated wrapper. Just rename in place and update the three callers in lockstep.

### §2.7 hybrid two-layer proxy

**Plan’s choice**: CGLIB outer + r2dbc-proxy inner advice rebuilding listeners per `create()` call.

**Verdict**: ⚠ Needs caveats. The choice resolves the `instanceof ConnectionPool` problem, which is real. But:
- The “rebuild listeners per `create()` call” has subtle aliasing concerns: each call creates a *new* `ProxyConnectionFactory`, attaches the same listener instances, and produces a different proxied `Connection`. With a JVM-global recorder registry, this is fine. With per-test recorders, listener identity matters for unregistration — and a new `ProxyConnectionFactory` per call means we can never unregister a listener tied to the inner proxy. Document this.
- The `QuickPerfR2dbcProxyMarker` idempotency check needs to be on the CGLIB outer proxy (§4.16).
- `ProxyFactory.setProxyTargetClass(true)` requires `spring-aop`, which IS transitively present (verified §2 row 13). ✔
- “Final class fallback to JDK proxy” is sound. But the `WARN` log path needs structured fields (bean name, class) — not a free-text log message.

---

## 6. PR breakdown nits

| PR | Issue |
|---|---|
| PR-1 | `getParamsOf` is package-private; the “API change” framing in §7 is misleading. Internal-only refactor. |
| PR-1 ↔ PR-3 | §7 says “PR-1, PR-2, PR-3, PR-4, PR-8 can be developed in parallel.” But PR-1 modifies `SelectAnalysisExtractor` and PR-3 modifies `SqlAnnotationsConfigs.SQL_STATEMENTS_BATCHED`. Different files — independent. ✔ |
| PR-1 ↔ PR-8 | §4.12 above: PR-8’s scanner needs PR-1’s multi-binding extractor for the “batched bindings get rendered correctly in `@DisplaySql`” path. **Add explicit dependency.** |
| PR-3 | Plan’s §1.2 line 37 wrongly claims `R2dbcExecutionAdapter` doesn’t set the batch flag (it does, lines 85-87). PR-3’s actual scope shrinks to “register `SqlStatementBatchRecorder` on `SqlRecorderHook` too.” |
| PR-4 | Depends on the §3.4 listener-interface refactor, which the plan does not cleanly describe. PR-4 cannot land without a PR-0 that introduces the `SqlConnectionListener` abstraction. |
| PR-5 | Plan says “depends on PR-4”. Confirm — both touch `ConnectionListener`. ✔ |
| PR-6 | Plan says “independent.” It is, but the marker-interface concern (§4.16) means it depends on its *own* tests verifying `QuickPerfR2dbcProxyMarker` is preserved. |
| PR-7 | Depends on PR-4 in the plan. Why? `@ExpectMaxConnectionAcquisitionTime` measures Connection-creation time, which conceptually overlaps with the `R2dbcConnectionListener` from PR-4 but doesn’t structurally depend on it (PR-7 can use its own listener). Re-evaluate. |
| PR-8 | Independent, but see §4.7 (MySQL/MS SQL identifiers) and §4.12 (depends on PR-1). |
| PR-9 | Doc-only. ✔ |

---

## 7. Suggested edits to apply to the plan

Ordered by priority. Format: `<file>:<section> — <change>`.

1. **plan.md:§1.2** — Correct the “v1 limitations” table:
   - `@ExpectJdbcBatching` root cause: change to “`SqlStatementBatchRecorder` registers only on `SqlRecorderRegistry`, not `SqlRecorderHook`; the adapter already sets the flag”.
   - `Placeholder rewriter` root cause: rename target class to `org.quickperf.sql.r2dbc.PlaceholderRewriter` (in `sql-annotations-r2dbc/`).
2. **plan.md:§2.1** — Rewrite the “Capture point” paragraph to specify that the `Result` decorator is installed via `ProxyConfig`/`ProxyFactoryFactory`, not via `ProxyExecutionListener.afterQuery`. Cite `io.r2dbc.proxy.callback.ResultCallbackHandler`. Address §4.1 and §4.4 (per-query column count).
3. **plan.md:§2.1.C** — Replace the proposed 3-arg helper signature with one of:
   - (a) Modify the constructor at `SqlExecution.java:44-53` to consult the custom value before `atLeastOneSelect`, OR
   - (b) Have the R2DBC adapter call the existing `SqlExecution(ExecutionInfo, List<QueryInfo>, long columnCount)` constructor at line 55-59. Prefer (b) — it is one-line and bypasses the auto-detect entirely. (See §3.1.)
4. **plan.md:§2.2** — Drop §2.2.B (“Set batch flags in the adapter for both flavours”). The adapter already does this. Replace with “Add `SqlRecorderHook.register(this)` in `SqlStatementBatchRecorder.startRecording`.”
5. **plan.md:§2.3** — Rewrite from scratch. Introduce a JDBC-free `SqlConnectionListener` interface in `org.quickperf.sql.connection`. Make `ConnectionListener` (existing abstract class) bridge into it. Reuse `ConnectionListenerRegistry`. Make `ConnectionLeakListener` track by opaque connection id (string) instead of `java.sql.Connection` instance. (See §3.4.)
6. **plan.md:§2.4** — Rewrite to match actual classes: recorder is `TestConnectionProfiler`, listener is `ConnectionEventsProfiler`. Register both JDBC and R2DBC listeners in the **constructor** of `TestConnectionProfiler` and unregister both in `cleanResources()` (matching the existing pattern, see §4.3). Drop the fictional `ConnectionEventsRecorder` references.
7. **plan.md:§2.5** — Fix the package: `org.quickperf.sql.select.analysis.QueryParamsExtractor`, not `formatter`. Note the class is package-private. Drop the deprecated wrapper that calls `.get(0)`. Update the three caller lines (84, 95, 105) directly.
8. **plan.md:§2.6** — Drop the references to `QueryParamsExtractor.replacePlaceholderWithParam` (does not exist). Move the `SqlPlaceholderScanner` to `sql-annotations-r2dbc` and wire it into `org.quickperf.sql.r2dbc.PlaceholderRewriter.rewrite`. Add MySQL `#` line comments and backtick (`` ` ``) identifier handling. Drop the “free win for JDBC” claim — there is no JDBC bug here.
9. **plan.md:§2.7** — Fix the BPP class name throughout to `org.quickperf.spring.boot.r2dbc.QuickPerfR2dbcProxyBeanPostProcessor`. Add explicit handling of `QuickPerfR2dbcProxyMarker` in the CGLIB path (§4.16). Verify `ConnectionPool#dispose()` is non-final in r2dbc-pool 1.0.x (§4.6).
10. **plan.md:§2.8** — Add an activation gate so the recorder/measurement only runs when the annotation is present (§4.14).
11. **plan.md:§3.2** — Clarify: `sql-annotations-r2dbc` is JDK 8+ (already uses `java.time.Duration`); `core` and `sql-annotations` keep JDK 7.
12. **plan.md:§3.3** — Replace “follow the pattern of `SqlRecorderHook`” for connection events with “reuse the existing `ConnectionListenerRegistry`”. Discuss the per-test attribution gap under `parallel=all` (§4.2).
13. **plan.md:§3.4** — Fix `org.quickperf.sql.framework.SystemProperties` → `org.quickperf.SystemProperties`. Drop or document `Hooks.onOperatorDebug()` global-side-effect risk (§4.5).
14. **plan.md:§3.6** — Add a “Behaviour changes” subsection: dropped `IllegalStateException`, `INSERT ... RETURNING` now reports columns. (§4.13)
15. **plan.md:§4.1, §4.2** — Fix every file path:
    - `R2dbcConnectionFactoryUtils.java` → `QuickPerfR2dbcConnectionFactoryBuilder.java` (or declare it new).
    - `R2dbcBeanPostProcessor.java` → `QuickPerfR2dbcProxyBeanPostProcessor.java`, package `org.quickperf.spring.boot.r2dbc`.
    - `org.quickperf.sql.formatter.QueryParamsExtractor.java` → `org.quickperf.sql.select.analysis.QueryParamsExtractor.java`.
    - `org.quickperf.sql.SqlStatementBatchRecorder.java` → `org.quickperf.sql.batch.SqlStatementBatchRecorder.java`.
    - `org.quickperf.sql.connection.ConnectionLeakAnalyzerListener.java` → `org.quickperf.sql.connection.ConnectionLeakListener.java`.
    - `org.quickperf.sql.connection.ConnectionEventsRecorder.java` → choose between `org.quickperf.sql.connection.TestConnectionProfiler.java` (recorder) and `org.quickperf.sql.connection.ConnectionEventsProfiler.java` (listener) depending on what is being changed.
    - Add `QuickPerfR2dbcProxyMarker.java` to the touched-files list (it must be implemented by the CGLIB subclass).
16. **plan.md:§5.3** — Add `R2dbcRecorderLeakTest` to assert registry is empty after `startRecording` throws (§4.11).
17. **plan.md:§7 PR-1** — State PR-8 depends on PR-1, not the other way around. (§4.12)
18. **plan.md:§7 PR-2** — Add a sub-bullet: column count is per-`QueryInfo`, not per-`ExecutionInfo`. (§4.4)
19. **plan.md:§7 PR-7** — Re-evaluate the dependency on PR-4. (§6 row PR-7)
20. **plan.md:§6.1 R7** — Fix the custom-value key example: it’s a fully-qualified string, not a constant; add a value-versioning byte to the externalised form if §4.10 outcome requires it.
21. **plan.md:§3.1 file inventory** — Move `SqlPlaceholderScanner` from `sql/sql-annotations` to `sql/sql-annotations-r2dbc` (or justify why it lives in `sql-annotations`).
22. **plan.md (every PR description)** — Add an explicit checklist item: “Run `mvn license:format` for new files.”
23. **plan.md:§2.4.C / §6.1 R8** — Change “opt-in `Hooks.onOperatorDebug()`” to require forked-JVM (`@HeapSize` or equivalent) so the global hook does not contaminate other tests under `parallel=all`. (§4.5)

---

## 8. Closing assessment

The synthesis got the *shape* of the work right: the eight focus areas are exactly the right things to fix, and the strategy of routing R2DBC events into the dsproxy `ExecutionInfo`/`QueryInfo` types is the correct architecture. The plan is also commendably explicit about per-PR risk, opt-out flags, and the testing strategy — these are the bits that make the plan actually executable in principle.

But the plan was **not pressure-tested against the real source tree**. The class names are systematically wrong (8 distinct fictional names: `ConnectionLeakAnalyzerListener`, `ConnectionEventsRecorder`, `R2dbcBeanPostProcessor`, `R2dbcConnectionFactoryUtils`, `QueryParamsExtractor.replacePlaceholderWithParam`, `ProfileConnectionExtractor`, `ProfileConnectionPrinter`, `ConnectionLeakAnalyzer.compareOpensAndClosing`). Three of the eight design areas (§2.1, §2.3/§2.4, §2.6) describe APIs and seams that don’t exist as described.

Before this can be executed by a subagent, the plan needs:
- A pass through the source tree to replace every fictional name with a real one.
- A correct mechanism for installing the `Result` decorator (`ProxyConfig`, not `ProxyExecutionListener`).
- An honest restructuring of the `ConnectionListener` hierarchy — either reuse the existing one or refactor it; do not invent a parallel one.
- A clear answer to the per-test attribution question under `parallel=all`.
- A revised PR ordering with PR-8 depending on PR-1.

Once those are addressed, the plan should be re-circulated for a final pre-execution review. The current document, executed verbatim, would produce 9 broken PRs.
