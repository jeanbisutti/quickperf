# Remove R2DBC limitations — Plan v3

- **Date:** 2026-05-03 (v3 — second consolidation)
- **Goal:** every `@Expect*`, `@Profile*`, `@Disable*` / `@Enable*` SQL annotation in `sql/sql-annotations` works identically against JDBC and R2DBC.
- **Synthesis sources:**
  - First-round design notes: `remove_r2dbc_limitations_plan_2026-05-03_09-36-53` (v1, replaced by v2 then v3).
  - Round-2 review (Claude Opus 4.7 Extra-high reasoning): `plan_review_2026-05-03_22-58-21.md`.
  - Round-2 review (GPT-5.5 Extra-high thinking): `gpt55-review.md`.
  - Final consolidation memo: `plan_review_consolidation_2026-05-03.md` (verifies every disputed claim against the working tree and the r2dbc-proxy / r2dbc-pool / r2dbc-spi sources extracted into `.r2dbc-extract` and `.r2dbc-pool-extract`).
- **Final review:** see the consolidation memo for per-finding decisions; this document folds them in.

## Working-tree assumptions verified for v3

- r2dbc-proxy **1.1.4.RELEASE** (sources extracted from local m2). `ResultCallbackHandler` is `public final`. `JdkProxyFactory` and `JdkProxyFactoryFactory` are package-private final. `ProxyFactory` interface has 7 methods. `ProxyConnectionFactory` exposes `builder(...)` only — there is **no** `wrap(Connection)` method.
- r2dbc-spi **1.0.0.RELEASE**. `Result` exposes `map(BiFunction)`, default `map(Function<? super Readable, T>)`, `getRowsUpdated()`, `filter(Predicate<Segment>)`, `flatMap(Function<Segment, Publisher<T>>)`. There is **no** `Result.getRowMetadata()`.
- r2dbc-pool **1.0.1.RELEASE**. `ConnectionPool` is `public class ConnectionPool implements ConnectionFactory, Disposable, Closeable, Wrapped<ConnectionFactory>`. Both `dispose()` and `close()` are `public` non-final.
- `core/src/main/java/org/quickperf/SystemProperties.java` reads via `System.getProperty(name)` only (verified lines 26, 42, 59, 76, 93). No Spring `Environment` consultation.
- `sql/sql-annotations/src/main/java/org/quickperf/sql/connection/ConnectionListenerRegistry.java` lines 28-38 hold an `InheritableThreadLocal<List<ConnectionListener>>`; `getConnectionListeners()` (lines 60-67) reads from that thread-local.
- `sql/sql-annotations/src/main/java/org/quickperf/sql/connection/QuickPerfDatabaseConnection.java` line 21: `public class QuickPerfDatabaseConnection implements Connection`. `buildFrom(Connection)` is unconditional.
- `sql/sql-annotations/src/main/java/org/quickperf/sql/config/library/SqlAnnotationsConfigs.java` line 13 (package) and line 59 (`class SqlAnnotationsConfigs` — package-private). All `AnnotationConfig` registrations live here.
- `sql/sql-annotations/src/main/java/org/quickperf/sql/SqlRecorderHook.java` is the JVM-global `CopyOnWriteArraySet<SqlRecorder<?>>` model. v3 mirrors this for the new `ConnectionListenerHook`.
- Surefire root config (root `pom.xml` lines 109-110): `<parallel>all</parallel> <threadCount>5</threadCount>`. The R2DBC Spring module (`spring/junit5-spring-boot-3-r2dbc-test/pom.xml` lines 135-139) pins `<parallel>none</parallel>` and is the reference for v2 R2DBC isolation.
- `core/src/main/java/org/quickperf/TestExecutionContext.java` lines 152-165 — the `perfRecorderClasses` set is never populated with `.add(...)` (latent dedup bug); fixed in PR-7.
- Annotations actually present in `sql/sql-annotations/src/main/java/org/quickperf/sql/annotation/` (32 files, normative for the v3 matrix):

  `AnalyzeSql`, `DisableLikeWithLeadingWildcard`, `DisableQueriesWithoutBindParameters`, `DisableSameSelects`, `DisableSameSelectTypesWithDifferentParamValues`, `DisableStatements`, `DisplaySql`, `DisplaySqlOfTestMethodBody`, `EnableLikeWithLeadingWildcard`, `EnableQueriesWithoutBindParameters`, `EnableSameSelects`, `EnableSameSelectTypesWithDifferentParamValues`, `EnableStatements`, `ExpectDelete`, `ExpectInsert`, `ExpectJdbcBatching`, `ExpectJdbcQueryExecution`, `ExpectMaxDelete`, `ExpectMaxInsert`, `ExpectMaxJdbcQueryExecution`, `ExpectMaxQueryExecutionTime`, `ExpectMaxSelect`, `ExpectMaxSelectedColumn`, `ExpectMaxUpdate`, `ExpectMaxUpdatedColumn`, `ExpectNoConnectionLeak`, `ExpectSelect`, `ExpectSelectedColumn`, `ExpectUpdate`, `ExpectUpdatedColumn`, `ProfileConnection`. (Plus helper `SqlAnnotationBuilder` and `package-info`.)

---

## §1. Goal & scope

### 1.1 v1 status (rewritten against the real annotation set)

The v1 R2DBC support in `sql/sql-annotations-r2dbc` already works for the following annotations because they reduce to "count rows in `SqlExecutions`" or "compare elapsed time", which is wired by `R2dbcQuickPerfListener` → `R2dbcExecutionAdapter` → `SqlRecorder.record(ExecutionInfo, List<QueryInfo>)`:

- `@ExpectSelect`, `@ExpectMaxSelect`
- `@ExpectInsert`, `@ExpectMaxInsert`
- `@ExpectUpdate`, `@ExpectMaxUpdate`
- `@ExpectDelete`, `@ExpectMaxDelete`
- `@ExpectMaxQueryExecutionTime`
- `@ExpectJdbcBatching` (already, because it reads from `SqlBatchSizes` populated from `ExecutionInfo.getBatchSize()`; v3 also adds the `@ExpectQueryBatching` alias).
- `@ExpectJdbcQueryExecution`, `@ExpectMaxJdbcQueryExecution` (and v3 aliases).

The "no-X" intent is achieved today by passing `0` to the corresponding annotation (e.g., `@ExpectSelect(0)`); v3 does **not** introduce new shorthand annotations (out of scope).

### 1.2 v1 limitations addressed by v3

| # | Limitation | Real root cause (verified) | v3 fix in section |
|---|---|---|---|
| L1 | Column-count annotations (`@Expect[Max]SelectedColumn`, `@Expect[Max]UpdatedColumn`) cannot be supported because r2dbc-proxy hands the listener a `Result` we cannot inspect for `RowMetadata`. | `ResultCallbackHandler` is `public final`; we must instrument the `Result` lifecycle ourselves through a `ProxyFactory` swap. | §2.1 |
| L2 | `@ExpectNoConnectionLeak` cannot be supported. | `ConnectionListenerRegistry` is `InheritableThreadLocal`-backed; the listener that runs on the Reactor scheduler thread sees an empty list. | §2.3 |
| L3 | `@ProfileConnection` cannot be supported. | Same as L2 plus no plumbing exists for R2DBC-side connection lifecycle events. | §2.3 + §2.4 |
| L4 | `@AnalyzeSql` cannot be supported. | `SqlExecution.retrieveNumberOfReturnedColumns(...)` returns 0 when the underlying JDBC `Statement` is null (R2DBC executions). | §2.5 |
| L5 | `@DisplaySql`, `@DisplaySqlOfTestMethodBody` render `?` placeholders without their bound values. | The placeholder rewriter regex correctly preserves `::` casts (`PlaceholderRewriter.java:50` uses `(?<!:):name`). The actual bug is that pure-`?` SQL produces an empty `orderedKeys` list, so the rendering helper has nothing to substitute. **The v2 plan misstated this.** | §2.6 |
| L6 | `@DisableLikeWithLeadingWildcard` / `@DisableQueriesWithoutBindParameters` / `@DisableSameSelects` / `@DisableStatements` and their `@Enable*` counterparts have not been verified end-to-end against R2DBC; some require post-execution analysis on the SQL string + bind values. | The recorder pipeline is shared, but the placeholder rewriter and the `?`-only rendering bug (L5) cause spurious diagnostics. | §2.6 + §3.6 |
| L7 | r2dbc-pool `ConnectionFactory` cannot be wrapped because it is a concrete class, not a `DataSource`. | `ConnectionPool` is a non-final concrete class implementing `ConnectionFactory, Disposable, Closeable, Wrapped<ConnectionFactory>`; the only method we need to intercept is `create()`. v2's claim that it `extends AbstractConnectionFactory` is wrong (no such class in r2dbc-spi 1.0). | §2.7 |
| L8 | Spring Boot 3 R2DBC starter cannot register its bean post-processor in time. | The starter exists (`spring-boot-r2dbc-sql-starter`) but uses JDK proxies which break `instanceof ConnectionPool` lookups in user code. | §2.7 |
| L9 | Per-test isolation under JUnit Jupiter parallel execution is undefined. | Same root cause as L2 (thread-local). For v2, isolation requires Surefire `<parallel>none</parallel>` (already in place for the R2DBC Spring module). Reactor-`Context`-based per-test routing is a v3 deferral. | §3.4 |

### 1.3 Out-of-scope for v3 (deferred to a future release)

- Spring `Environment` bridge for `quickperf.sql.*` system properties.
- Reactor `Context`-based per-test routing for `parallel=all` user setups.
- MS SQL `[id]` bracketed identifier parsing in the placeholder scanner.
- Oracle `Q'⟨delim⟩…⟨delim⟩'` alternative quoting in the placeholder scanner.
- Cross-string PostgreSQL `E'…'\\…'` continuation.
- New shorthand annotations such as `@ExpectNoSelect`.
- Automatic detection of R2DBC URL schemes for non-Spring users (today the user must declare `quickperf-spring-boot-r2dbc-sql-starter`).
- Connection listener registry thread-safety hardening.

---

## §2. Per-annotation design

### 2.1 Column-count annotations: `@Expect[Max]SelectedColumn`, `@Expect[Max]UpdatedColumn`

#### 2.1.A The `Result` decorator

- `ResultCallbackHandler` is `public final` (verified `r2dbc-proxy-1.1.4-sources.jar!ResultCallbackHandler.java:40`). v3 does not subclass it; instead, we **decorate** the `Result` instance returned from the JDK-default proxy.
- New class: `org.quickperf.sql.r2dbc.QuickPerfMonitoringResult` — `final class implements io.r2dbc.spi.Result`. It holds:
  - `private final Result delegate;` — the result returned by `JdkProxyFactory.wrapResult(...)`, so the `ResultInvocationSubscriber` chain that drives `afterQuery` is preserved.
  - `private final QueryExecutionInfo queryExecutionInfo;`
  - `private final String connectionId;`
- The decorator overrides four `Result` methods that touch row metadata:
  - `Publisher<T> map(BiFunction<Row, RowMetadata, T> bi)` → wraps `bi` with a tracer that records `rowMetadata.getColumnMetadatas().size()` once per execution.
  - `<T> Publisher<T> map(Function<? super Readable, T> f)` → wraps `f` with a tracer that, on first invocation where `readable instanceof Row`, calls `((Row) readable).getMetadata().getColumnMetadatas().size()`.
  - `<T> Publisher<T> flatMap(Function<Segment, ? extends Publisher<? extends T>> f)` → wraps `f` with a tracer that, when `segment instanceof RowSegment`, calls `((RowSegment) segment).row().getMetadata().getColumnMetadatas().size()`.
  - `Result filter(Predicate<Segment> p)` → wraps `p` with a tracer that does the same `RowSegment` extraction; the filtered Result is itself wrapped in a new `QuickPerfMonitoringResult` so cascaded `map`/`flatMap` calls remain observed.
- It forwards the two non-row methods unchanged: `Publisher<Long> getRowsUpdated()` returns `delegate.getRowsUpdated()` (no instrumentation needed).
- The recording call is idempotent per `QueryExecutionInfo`: it uses `ColumnCountStore.recordOnce(connectionId, queryExecutionInfo, count)`, which performs a `putIfAbsent` on the `(connectionId#queryExecId)` key. First-writer wins. Heterogeneous result batches (multiple `Result` publishers from a single `Statement.execute()`) keep the first count as the canonical one — matching JDBC's `SqlExecution.retrieveNumberOfReturnedColumns` semantic that returns the column count of the first `ResultSet`.

#### 2.1.B Cross-thread carrier: `org.quickperf.sql.r2dbc.ColumnCountStore`

- `final class ColumnCountStore` with a private constructor and a JVM-static `ConcurrentHashMap<String, long[]>` keyed by `connectionId + "#" + System.identityHashCode(queryExecutionInfo)`.
- API:
  - `static void recordOnce(String connectionId, QueryExecutionInfo qei, long count)` — `putIfAbsent(key, new long[]{count})`.
  - `static long drain(String connectionId, QueryExecutionInfo qei)` — `remove(key)` and return `value[0]` (or 0 if absent).
- Drained from `R2dbcExecutionAdapter.adapt(QueriesExecutionContext, QueryExecutionInfo)` immediately before constructing the `SqlExecution`. The drained value is set on the synthetic `Statement` placeholder (see §2.1.C).
- A best-effort `Runtime.getRuntime().addShutdownHook(...)` clears the map on JVM exit (paranoia; in practice the map is empty between tests because every entry is drained synchronously in `adapt`).

#### 2.1.C Synthetic JDBC `Statement` returning the column count

- `SqlExecution.retrieveNumberOfReturnedColumns(...)` (lines 72-84) reads the column count by introspecting the `ResultSet` of the wrapped JDBC `Statement`. For R2DBC, `R2dbcExecutionAdapter` builds a `Statement` placeholder using a private static-final stub:
  - `R2dbcSyntheticStatement` is a `Statement` proxy whose only meaningful behaviour is to return a `ResultSet` whose `getMetaData().getColumnCount()` returns the previously-drained value. All other `Statement`/`ResultSet` calls throw `UnsupportedOperationException` to surface accidental misuse.
- This keeps `SqlExecution` untouched.

#### 2.1.D Installing the decorator: `QuickPerfProxyFactoryFactory`

- New class: **`io.r2dbc.proxy.callback.QuickPerfProxyFactory`** — `final class implements ProxyFactory`. Constructor: `QuickPerfProxyFactory(ProxyConfig proxyConfig)`. Body holds a `JdkProxyFactory delegate = new JdkProxyFactory(proxyConfig)` (constructor is package-visible). Six of the seven `ProxyFactory` methods delegate verbatim to `delegate`. `wrapResult(Result, QueryExecutionInfo, QueriesExecutionContext)` first calls `delegate.wrapResult(...)` (preserving `ResultInvocationSubscriber`), then returns `new QuickPerfMonitoringResult(delegateResult, queryExecutionInfo, connectionId)`.
- New class: **`io.r2dbc.proxy.callback.QuickPerfProxyFactoryFactory`** — `final class implements ProxyFactoryFactory`. `create(ProxyConfig pc)` returns `new QuickPerfProxyFactory(pc)`.
- Both classes carry a header comment documenting the deliberate package extension: *"Placed in `io.r2dbc.proxy.callback` to access the package-private `JdkProxyFactory(ProxyConfig)` constructor. Verified against r2dbc-proxy 1.1.4. Pin the r2dbc-proxy version range in the BOM and smoke-build before each minor upgrade."*
- Installation is in PR-6 (Spring boot starter): `ProxyConfig.Builder builder = ProxyConfig.builder(); builder.proxyFactoryFactory(new QuickPerfProxyFactoryFactory()); … ProxyConfig pc = builder.build();` and then `ProxyConnectionFactory.builder(target).proxyConfig(pc).listener(...).build()` (verified `ProxyConfig.java:90` `public void setProxyFactoryFactory(ProxyFactoryFactory)` and the equivalent Builder method).
- The result-callback dispatch is **not** internal to `JdkProxyFactory.wrapStatement` (verified by reading `JdkProxyFactory.wrapStatement` lines 86-95): when user code subscribes to `Statement.execute()`, the resulting publisher emits `Result` instances which are then wrapped via `proxyConfig.getProxyFactory().wrapResult(...)` — that is, our `QuickPerfProxyFactory.wrapResult` is invoked end-to-end. We do not need to override `wrapStatement` or `wrapConnection`.

#### 2.1.E Files (PR-2)

- New: `sql/sql-annotations-r2dbc/src/main/java/org/quickperf/sql/r2dbc/QuickPerfMonitoringResult.java`
- New: `sql/sql-annotations-r2dbc/src/main/java/org/quickperf/sql/r2dbc/ColumnCountStore.java`
- New: `sql/sql-annotations-r2dbc/src/main/java/org/quickperf/sql/r2dbc/R2dbcSyntheticStatement.java`
- New: `sql/sql-annotations-r2dbc/src/main/java/io/r2dbc/proxy/callback/QuickPerfProxyFactory.java`
- New: `sql/sql-annotations-r2dbc/src/main/java/io/r2dbc/proxy/callback/QuickPerfProxyFactoryFactory.java`
- Modified: `sql/sql-annotations-r2dbc/src/main/java/org/quickperf/sql/r2dbc/R2dbcExecutionAdapter.java` — `adapt(...)` consumes `ColumnCountStore.drain(...)` and feeds the synthetic statement into the `SqlExecution` constructor.

#### 2.1.F Tests (PR-2)

- Unit tests on the decorator covering all four `Result` API surfaces; one test per surface verifies that the user-visible `Publisher<T>` emits the same elements (i.e., the wrapper does not break user logic) and that `ColumnCountStore.recordOnce` was called exactly once with the expected count.
- Unit tests for heterogeneous batches (two `Result`s from one execution; first count wins).
- Unit tests for `getRowsUpdated()` only — no column count recorded; `drain` returns 0.
- Integration test in `spring/junit5-spring-boot-3-r2dbc-test`: `R2dbcSelectedColumnsIT` covering `@ExpectSelectedColumn`, `@ExpectMaxSelectedColumn`, `@ExpectUpdatedColumn`, `@ExpectMaxUpdatedColumn`.

### 2.2 `@ExpectNoConnectionLeak` (refactored to opaque-id tracking) — see §2.3

The annotation itself does not need a new verifier; the v3 work is on the underlying `ConnectionLeakListener` and the new neutral connection lifecycle plumbing. See §2.3.

### 2.3 New connection-lifecycle plumbing

#### 2.3.A `ConnectionListenerHook` (JVM-global)

- Verified problem: `ConnectionListenerRegistry` (line 28-38) holds an `InheritableThreadLocal<List<ConnectionListener>>`. Reactor schedulers (e.g., `Schedulers.parallel()`, `Schedulers.boundedElastic()`) thread-pool initialisation copies the InheritableThreadLocal value at thread *creation* time, not per `subscribe()`. Therefore a listener registered AFTER the Reactor scheduler thread was first created is invisible from that thread.
- **Fix:** new class `org.quickperf.sql.connection.ConnectionListenerHook`, modelled on `SqlRecorderHook` (verified `SqlRecorderHook.java:37-55`):

  ```java
  public final class ConnectionListenerHook {
      private static final Set<SqlConnectionListener> ACTIVE = new CopyOnWriteArraySet<>();
      private ConnectionListenerHook() {}
      public static void register(SqlConnectionListener l) { ACTIVE.add(l); }
      public static void unregister(SqlConnectionListener l) { ACTIVE.remove(l); }
      public static Set<SqlConnectionListener> getActiveListeners() { return Collections.unmodifiableSet(ACTIVE); }
  }
  ```

#### 2.3.B `SqlConnectionListener` (neutral interface)

- New interface `org.quickperf.sql.connection.SqlConnectionListener`:

  ```java
  public interface SqlConnectionListener {
      default void onConnectionAcquired(SqlConnectionEvent event) {}
      default void onConnectionReleased(SqlConnectionEvent event) {}
      default void onTransactionBegan(SqlConnectionEvent event) {}
      default void onTransactionCommitted(SqlConnectionEvent event) {}
      default void onTransactionRolledBack(SqlConnectionEvent event) {}
      default void onAutoCommitChanged(SqlConnectionEvent event, boolean autoCommit) {}
      default void onIsolationLevelChanged(SqlConnectionEvent event, String level) {}
      default void onSavepointCreated(SqlConnectionEvent event, String name) {}
      default void onSavepointReleased(SqlConnectionEvent event, String name) {}
      default void onSavepointRolledBack(SqlConnectionEvent event, String name) {}
  }
  ```

- New value type `org.quickperf.sql.connection.SqlConnectionEvent` (immutable):
  - `String connectionId` — opaque id, stable across the connection's lifetime, identifies the same Connection across multiple events.
  - `Source source` — enum `JDBC` / `R2DBC`.
  - `Optional<Throwable> stackTraceMarker` — captured at `onConnectionAcquired` time when stack-trace capture is enabled.
  - `Instant timestamp`.

#### 2.3.C Bridging the existing `ConnectionListener`

- Existing abstract class `org.quickperf.sql.connection.ConnectionListener` (144 lines, ~30 JDBC-typed callbacks) is changed in v3 to `implements SqlConnectionListener`. The new methods have default no-op implementations.
- Inside `QuickPerfDatabaseConnection.theDatasourceGetsTheConnection()` and the corresponding `close()` paths, after dispatching to JDBC-typed callbacks via `ConnectionListenerRegistry.getConnectionListeners()`, also dispatch to neutral callbacks via `ConnectionListenerHook.getActiveListeners()`. The opaque connection id for JDBC is `"jdbc-" + System.identityHashCode(this)` (the wrapper instance is stable across its lifetime).
- The transaction/savepoint/setAutoCommit/setTransactionIsolation paths in `QuickPerfDatabaseConnection` similarly fire neutral events.
- Backward compatibility: existing JDBC listeners that subclass `ConnectionListener` continue to work unchanged; they receive both the old and the new callbacks (the new ones default to no-op so they're effectively ignored).

#### 2.3.D `ConnectionLeakListener` refactor

- Current state (`ConnectionLeakListener.java:73`): tracks `private final List<Connection> connections` keyed on JDBC `Connection` instances. Cannot work for R2DBC because R2DBC `Connection` is a different type.
- v3:

  ```java
  public class ConnectionLeakListener extends ConnectionListener {
      private final Set<String> openConnectionIds = ConcurrentHashMap.newKeySet();

      @Override public void onConnectionAcquired(SqlConnectionEvent e) { openConnectionIds.add(e.connectionId()); }
      @Override public void onConnectionReleased(SqlConnectionEvent e) { openConnectionIds.remove(e.connectionId()); }

      // legacy JDBC overrides delegate to the new neutral methods
      @Override public void theDatasourceGetsTheConnection(QuickPerfDatabaseConnection c) {
          onConnectionAcquired(SqlConnectionEvent.jdbc("jdbc-" + System.identityHashCode(c)));
      }
      @Override public void aConnectionIsClosed(QuickPerfDatabaseConnection c) {
          onConnectionReleased(SqlConnectionEvent.jdbc("jdbc-" + System.identityHashCode(c)));
      }

      public int countLeakedConnections() { return openConnectionIds.size(); }
  }
  ```

- `startRecording()` (existing) registers with both `ConnectionListenerRegistry.INSTANCE.addConnectionListener(this)` AND `ConnectionListenerHook.register(this)`. Symmetric in `stopRecording()`/`cleanResources()`.

#### 2.3.E R2DBC-side dispatch

- `R2dbcConnectionLifecycleListener` (new, see §2.4) iterates `ConnectionListenerHook.getActiveListeners()` only. It does **not** consult `ConnectionListenerRegistry`. This is the key behaviour difference that fixes the InheritableThreadLocal bug for the reactive path.

#### 2.3.F Files (PR-4)

- New: `sql/sql-annotations/src/main/java/org/quickperf/sql/connection/ConnectionListenerHook.java`
- New: `sql/sql-annotations/src/main/java/org/quickperf/sql/connection/SqlConnectionListener.java`
- New: `sql/sql-annotations/src/main/java/org/quickperf/sql/connection/SqlConnectionEvent.java`
- Modified: `sql/sql-annotations/src/main/java/org/quickperf/sql/connection/ConnectionListener.java` — `implements SqlConnectionListener` with no-op defaults.
- Modified: `sql/sql-annotations/src/main/java/org/quickperf/sql/connection/QuickPerfDatabaseConnection.java` — fire neutral events alongside JDBC-typed ones (~6 sites: `theDatasourceGetsTheConnection`, `close`, `setAutoCommit`, `setTransactionIsolation`, `setSavepoint`, `releaseSavepoint`, `commit`, `rollback`).
- Modified: `sql/sql-annotations/src/main/java/org/quickperf/sql/connection/ConnectionLeakListener.java` — opaque-id tracking, dual registration on start/stop.
- Modified: existing JDBC tests under `sql/sql-annotations/src/test/.../*ConnectionLeak*Test.java` and `*ConnectionLeak*IT.java` — assertions on `countLeakedConnections()` instead of `connections.size()`.

### 2.4 R2DBC connection lifecycle: `R2dbcConnectionLifecycleListener`

- New class: `org.quickperf.sql.r2dbc.R2dbcConnectionLifecycleListener implements io.r2dbc.proxy.listener.ProxyExecutionListener`.
- In `beforeMethod(MethodExecutionInfo info)` and `afterMethod(MethodExecutionInfo info)`, switch on `info.getMethod().getName()`:
  - `create` (called on `ConnectionFactory`) — fire `onConnectionAcquired` after method success in `afterMethod`. The connection id is `"r2dbc-" + System.identityHashCode(info.getResult())` if `info.getResult() instanceof Connection`, else fallback to `"r2dbc-" + UUID.randomUUID()`.
  - `close` (called on `Connection`) — fire `onConnectionReleased` in `beforeMethod` (so the id is still recoverable from `info.getConnectionInfo()`).
  - `beginTransaction` (both overloads) — fire `onTransactionBegan` in `afterMethod`.
  - `commitTransaction` — fire `onTransactionCommitted` in `afterMethod`.
  - `rollbackTransaction` — fire `onTransactionRolledBack` in `afterMethod`.
  - `setAutoCommit` — fire `onAutoCommitChanged` in `afterMethod` with the new value (extract from `info.getMethodArgs()`).
  - `setTransactionIsolationLevel` — fire `onIsolationLevelChanged` (string form) in `afterMethod`.
  - `createSavepoint` — fire `onSavepointCreated` with the savepoint name from args.
  - `releaseSavepoint` — fire `onSavepointReleased` with the name.
  - `rollbackTransactionToSavepoint` — fire `onSavepointRolledBack`.
- `getMetadata`, `validate`, `setLockWaitTimeout`, `setStatementTimeout` are not surfaced in v2 (no annotation requires them). Listed in §1.3 as v3-deferral candidates if needed.
- The connection id `"r2dbc-" + System.identityHashCode(connectionInstance)` is computed once per `ConnectionInfo.getValueStore()` and cached there. `ConnectionInfo.getValueStore()` exists at `r2dbc-proxy 1.1.4 ConnectionInfo.java:101` (`ValueStore getValueStore()`).
- Listener installation is part of PR-6 (Spring Boot starter): added to `ProxyConnectionFactory.builder(target).listener(quickPerfQueryListener).listener(connectionLifecycleListener).build()`.

#### 2.4.A Verified SPI method names (from r2dbc-spi 1.0.0 `Connection.java`)

`commitTransaction()`, `rollbackTransaction()`, `beginTransaction()`, `beginTransaction(TransactionDefinition)`, `setTransactionIsolationLevel(IsolationLevel)`, `setAutoCommit(boolean)`, `createStatement(String)`, `createBatch()`, `close()`, `createSavepoint(String)`, `releaseSavepoint(String)`, `rollbackTransactionToSavepoint(String)`, `getMetadata()`, `validate(ValidationDepth)`, `setLockWaitTimeout(Duration)`, `setStatementTimeout(Duration)`. v3 is exhaustive on the ones it surfaces; the v2 plan's `commit`/`rollback` were wrong.

#### 2.4.B Files

- New: `sql/sql-annotations-r2dbc/src/main/java/org/quickperf/sql/r2dbc/R2dbcConnectionLifecycleListener.java`
- Tests: `R2dbcConnectionLifecycleListenerTest` (unit) and `R2dbcConnectionLeakIT` / `R2dbcExpectNoConnectionLeakIT` in the Spring module.

### 2.5 `@AnalyzeSql` for R2DBC

- Root cause (verified): `SqlExecution.dbExceptionHappened(...)` (lines 86-88) returns `true` for null `Statement`/`ResultSet`. `retrieveNumberOfReturnedColumns` short-circuits to 0 (line 75) before any cast — there is no `ClassCastException` waiting to fire. v2 was wrong about a CCE risk.
- Therefore the `@AnalyzeSql`-driven static analysis of the SQL string (e.g., `SELECT * detection`, joinless cross-product detection) works today against R2DBC because it parses the `String sql` carried by `QueryInfo`. The only blockage is for analyses that rely on knowing the column count or actual returned row count.
- **v3 fix:** with §2.1 in place, `SqlExecution` has a non-zero column count (via the synthetic statement), so `SELECT *`-style analyses that compare column count to the SELECT clause shape work for R2DBC too.
- The `QueryParamsExtractor` (`sql/sql-annotations/src/main/java/org/quickperf/sql/select/analysis/QueryParamsExtractor.java:51-54`) is package-private and throws:

  > `IllegalStateException("Several parameter set not managed, please create an issue on https://github.com/quick-perf/quickperf/issues describing your use case.")`

  for batched parameter sets. The R2DBC adapter constructs `ParameterSetOperation`s from `QueryInfo.getBatchOrParameterSets(...)` (returns `List<List<ParameterSetOperation>>`) — for R2DBC batches we today flatten the lists into a single execution; the exception path is never hit.
- No code changes required here beyond the §2.1 column-count integration.
- Files: integration test `R2dbcAnalyzeSqlIT`.

### 2.6 Placeholder rewriter / `@DisplaySql` / `@Disable*` correctness

#### 2.6.A Real bug for `?` placeholders

- `PlaceholderRewriter.java:50` uses `(?<!:):name` — verified that `a::int` casts are correctly preserved. `?` is **not** regex-rewritten; it is passed through verbatim.
- Real bug: `?`-only SQL produces an empty `orderedKeys` list, which the rendering helper consumes in lockstep with `String.indexOf('?')`. With an empty list, the rendering helper has no values to substitute and produces nonsense. v2 plan misdiagnosed this as a regex problem.

#### 2.6.B v3 fix (within §2.6)

- Extend the placeholder scanner to a small state machine recognising:
  - `?` (positional) — produces an entry in `orderedKeys` keyed by `1, 2, …`.
  - `?N` (Spring-style positional) — same, keyed by `N`.
  - `:name` (named) — preserved as-is.
  - `'…'` strings, including escaped `''` (sequence inside string).
  - `"…"` quoted identifiers (escaped `""`).
  - `--` line comments and `/* … */` block comments (skipped).
  - PostgreSQL `E'…'` and `U&'…'` strings (added in v3).
  - PostgreSQL `::` cast — already preserved by the existing regex; the state machine continues to skip the second colon when it follows the first.
  - PostgreSQL `$tag$ … $tag$` dollar-quoted strings.
- Out of scope (v3 deferral, see §1.3): MS SQL `[id]`, Oracle `Q'⟨delim⟩…⟨delim⟩'`, cross-string E-string continuation.
- Files:
  - Modified: `sql/sql-annotations-r2dbc/src/main/java/org/quickperf/sql/r2dbc/PlaceholderRewriter.java` — introduce a state-machine scanner, retain the public API.
  - New tests: `PlaceholderRewriterTest` adds cases for `?`-only SQL, `E'…'`, `U&'…'`, `$tag$ … $tag$`. The existing `preserves_postgres_double_colon_cast_operator` test (line 55) is preserved.

#### 2.6.C `?`-only rendering bug fix

- Modified: `sql/sql-annotations/src/main/java/org/quickperf/sql/display/SqlDisplayer*.java` (the helper that renders `?` against `orderedKeys`) — when `orderedKeys` is empty, default to numeric `1, 2, 3, …` indices.

### 2.7 r2dbc-pool `ConnectionFactory` wrapping (Spring Boot 3 R2DBC starter)

#### 2.7.A `ConnectionPool` is not extending `AbstractConnectionFactory`

- Verified `r2dbc-pool-1.0.1-sources!ConnectionPool.java:63`: `public class ConnectionPool implements ConnectionFactory, Disposable, Closeable, Wrapped<ConnectionFactory>`. There is no `AbstractConnectionFactory` superclass (no such class in r2dbc-spi 1.0).
- Therefore the CGLIB subclass interface list must include `Wrapped<ConnectionFactory>` so user code that does `((Wrapped<?>) bean).unwrap()` keeps working. CGLIB can subclass a non-final concrete class and add interfaces.

#### 2.7.B Two-layer wrapping (replaces v2's `wrap(Connection)` pseudo-code)

`ProxyConnectionFactory.wrap(Connection)` does **not** exist (verified `ProxyConnectionFactory.java:98-100` shows only `Builder.build()` returning a `ConnectionFactory`). v3 design:

- **Layer 1 (outer CGLIB subclass):** preserves the bean's runtime type for user code:

  ```java
  org.springframework.aop.framework.ProxyFactory aop = new org.springframework.aop.framework.ProxyFactory(target);
  aop.setProxyTargetClass(true); // CGLIB subclass of ConnectionPool
  aop.addInterface(QuickPerfR2dbcProxyMarker.class);
  aop.addInterface(io.r2dbc.spi.Wrapped.class);
  aop.addAdvice(new MethodInterceptor() {
      @Override public Object invoke(MethodInvocation inv) throws Throwable {
          if ("create".equals(inv.getMethod().getName()) && inv.getMethod().getParameterCount() == 0) {
              return proxiedConnectionFactory.create(); // Layer 2
          }
          return inv.proceed(); // dispose, close, getMetadata, unwrap, isDisposed, …
      }
  });
  Object outerBean = aop.getProxy();
  ```

- **Layer 2 (inner r2dbc-proxy ConnectionFactory):**

  ```java
  ConnectionFactory proxiedConnectionFactory =
      ProxyConnectionFactory.builder(target)
          .proxyConfig(quickPerfProxyConfig) // carries QuickPerfProxyFactoryFactory
          .listener(quickPerfQueryListener)
          .listener(r2dbcConnectionLifecycleListener)
          .build();
  ```

- r2dbc-proxy's `JdkProxyFactory.wrapConnection(...)` (verified `JdkProxyFactory.java:67-74`) automatically wraps every emitted `Connection`, dispatching `ProxyExecutionListener` events for per-`Connection` method calls. We do **not** call any `wrap(Connection)` ourselves.

#### 2.7.C `instanceof ConnectionPool` and `Wrapped<ConnectionFactory>`

- The CGLIB outer subclass is, at the JVM level, a subclass of `ConnectionPool`, so `instanceof ConnectionPool` is true. `getMetadata()`, `dispose()`, `close()`, `unwrap()`, `isDisposed()` all proceed to the underlying `ConnectionPool` instance via `inv.proceed()`.
- Verified `dispose()` at `ConnectionPool.java:310` (`public void dispose()`) and `close()` at `:305` (`public Mono<Void> close()`) are non-final.

#### 2.7.D Fallback when CGLIB is unavailable or target is `final`

- If Spring Objenesis is unavailable or the target class is `final` (e.g., user has a custom `final ConnectionFactory` subclass — uncommon), fall back to a JDK proxy with `addInterface(ConnectionFactory.class)`, `addInterface(Wrapped.class)`, `addInterface(Disposable.class)`, `addInterface(Closeable.class)` and emit a `WARN` log: *"QuickPerf: target ConnectionFactory ${class} could not be CGLIB-subclassed; falling back to JDK proxy. `instanceof ConnectionPool` will return false in user code."*
- `QuickPerfR2dbcProxyMarker` (existing) acts as the idempotency marker: `if (bean instanceof QuickPerfR2dbcProxyMarker) return bean;` (verified `QuickPerfR2dbcProxyBeanPostProcessor.java:53-55`).

#### 2.7.E Validation queries are NOT counted

- `ConnectionPool.create()` runs validation internally before emitting a `Connection`. Validation `SELECT 1` reaches the inner `ConnectionFactory.create()` directly — but the listener is installed on the *outer* `proxiedConnectionFactory` which wraps the entire `ConnectionPool` (via `ProxyConnectionFactory.builder(target)`), so listener visibility is on user-visible `create()` and the per-emitted-`Connection` events. Validation does not appear in `SqlExecutions` for `@ExpectMaxQueriesNumber` (or any other count-based annotation) because the validation call originates from inside `ConnectionPool` and never traverses the proxied ConnectionFactory.
- Wait — verify: `ProxyConnectionFactory.builder(target).build().create()` calls `target.create()` (the wrapped `ConnectionPool`); inside the pool, validation runs against the inner `ConnectionFactory` (which is NOT proxied). The emitted `Connection` is then wrapped by r2dbc-proxy on the way out. Therefore validation `SELECT 1` does not flow through any `ProxyExecutionListener` we installed. ✅
- New IT: `R2dbcPoolValidationQueryNotCountedIT` in the R2DBC Spring module — instantiates a `ConnectionPool` with a non-null validation query and asserts `@ExpectMaxQueriesNumber(N)` counts only user queries.

#### 2.7.F Files (PR-6)

- Rewritten: `spring/spring-boot-r2dbc-sql-starter/src/main/java/org/quickperf/spring/boot/r2dbc/QuickPerfR2dbcProxyBeanPostProcessor.java` — Spring AOP `ProxyFactory` with `setProxyTargetClass(true)` + outer CGLIB design.
- Existing marker: `QuickPerfR2dbcProxyMarker` (unchanged).
- New: `R2dbcCglibFallbackIT` for the JDK-proxy fallback path.

### 2.8 `@ExpectNoConnectionLeak` activation gate

- Wording fix from v2: `QuickPerfDatabaseConnection.buildFrom(...)` is **unconditional**. The activation gate is at registration time (`ConnectionLeakListener.startRecording()` is only invoked when `@ExpectNoConnectionLeak` is present on the test method). Once registered, all connection lifecycle events are observed for the test's duration.
- For R2DBC, the listener registration happens through the same `startRecording()` path; the recorder additionally registers with `ConnectionListenerHook` (see §2.3.D). No code changes here beyond §2.3.

### 2.9 Annotation aliases (`@ExpectQueryBatching`, `@ExpectQueryExecution`, `@ExpectMaxQueryExecution`)

#### 2.9.A The verifier is generic on the annotation type

- Verified: every `VerifiablePerformanceIssue<A, M>` is generic on the legacy annotation type. Concrete examples (file: line):
  - `SqlStatementBatchVerifier.java:34` — `class SqlStatementBatchVerifier implements VerifiablePerformanceIssue<ExpectJdbcBatching, SqlBatchSizes>`.
  - `JdbcQueryExecutionVerifier.java` — `implements VerifiablePerformanceIssue<ExpectJdbcQueryExecution, SqlExecutions>`.
  - `MaxJdbcQueryExecutionVerifier.java` — `implements VerifiablePerformanceIssue<ExpectMaxJdbcQueryExecution, SqlExecutions>`.
- Therefore registering `(ExpectQueryBatching, SqlStatementBatchVerifier.INSTANCE)` against the same instance does **not** type-check at registration call sites where `AnnotationConfig` parameters are inferred.

#### 2.9.B Alias verifiers (option chosen: alias-specific verifier classes delegating to a package-private static helper)

- Each alias annotation gets its own verifier class. The verification logic is extracted into a package-private `static verify(...)` helper in the same package as the legacy verifier, and both verifiers delegate to it.
- Concrete plan:
  - Existing `SqlStatementBatchVerifier` exposes a new package-private `static PerfIssue verify(int expectedBatchSize, int[] measured)` helper carrying the assertion logic (extracted from `verifyPerfIssue`). Public `verifyPerfIssue` becomes a thin wrapper.
  - New `org.quickperf.sql.batch.SqlQueryBatchingVerifier implements VerifiablePerformanceIssue<ExpectQueryBatching, SqlBatchSizes>` — body: `return SqlStatementBatchVerifier.verify(annotation.batchSize(), measure.toIntArray());` (or whatever the existing accessor is).
  - Same pattern for `JdbcQueryExecutionVerifier` → `QueryExecutionVerifier` (alias for `@ExpectQueryExecution`).
  - Same pattern for `MaxJdbcQueryExecutionVerifier` → `MaxQueryExecutionVerifier` (alias for `@ExpectMaxQueryExecution`).

#### 2.9.C Registration in `SqlAnnotationsConfigs`

- Verified package: `org.quickperf.sql.config.library` (NOT `org.quickperf.sql.config`). Class is package-private (`SqlAnnotationsConfigs.java:59`). Therefore alias registrations live in the same package; if any new helper class is needed it is also package-private and in `org.quickperf.sql.config.library`.
- Each alias registration is a separate `AnnotationConfig`:

  ```java
  AnnotationConfig EXPECT_QUERY_BATCHING_CONFIG = new AnnotationConfig.Builder()
      .perfRecorderClass(SqlStatementBatchRecorder.class) // reuse existing recorder
      .verifierClass(SqlQueryBatchingVerifier.class)
      .annotationClass(ExpectQueryBatching.class)
      .build();
  ```

  Same shape for `EXPECT_QUERY_EXECUTION_CONFIG` and `EXPECT_MAX_QUERY_EXECUTION_CONFIG`.

#### 2.9.D `TestExecutionContext.buildPerfRecordersToExecute` deduplication bug

- Verified `TestExecutionContext.java:154-164`: a `Set<Class<? extends RecordablePerformance>> perfRecorderClasses = new HashSet<>()` is created but **never** `.add(perfRecorderClass)` is called after the recorder is built. The dedup check `!perfRecorderClasses.contains(perfRecorderClass)` is therefore always true; the bug is harmless today (no annotations share a recorder class), but becomes harmful once aliases all use `SqlStatementBatchRecorder`.
- **Fix as part of PR-7:** add `perfRecorderClasses.add(perfRecorderClass);` after building the recorder. One-line fix; covered by a new unit test verifying that two annotations sharing a recorder class produce a single recorder instance.

#### 2.9.E Files (PR-3 + PR-7)

- New annotation files: `sql/sql-annotations/src/main/java/org/quickperf/sql/annotation/ExpectQueryBatching.java`, `ExpectQueryExecution.java`, `ExpectMaxQueryExecution.java`.
- New verifier files: `SqlQueryBatchingVerifier`, `QueryExecutionVerifier`, `MaxQueryExecutionVerifier` (next to the legacy verifiers).
- Modified: `sql/sql-annotations/src/main/java/org/quickperf/sql/config/library/SqlAnnotationsConfigs.java` — add three `AnnotationConfig` entries; add to `getAllAnnotationConfigs()`.
- Modified (PR-7): `core/src/main/java/org/quickperf/TestExecutionContext.java` lines 154-164 — fix the missing `.add(...)`.
- New tests: alias parity ITs (`R2dbcExpectQueryBatchingIT`, `R2dbcExpectQueryExecutionIT`, etc.) and a JDBC-side dedup unit test for the `TestExecutionContext` bug.

### 2.10 Dedicated `quick-perf-r2dbc-bom` artifact

#### 2.10.A Why a separate BOM (in addition to `quick-perf-bom`)

- The existing `bom/pom.xml` (`quick-perf-bom`) lists every QuickPerf module — JFR, JVM, SQL (JDBC + R2DBC), JUnit 4/5, TestNG, Spring 3/4/5 + Boot 1/2/3, etc. R2DBC-only users must currently import the full BOM and remember which subset of artifacts they need.
- A dedicated `quick-perf-r2dbc-bom` solves three concrete problems:
  1. **Discoverability** — one import that names exactly the QuickPerf artifacts an R2DBC project needs.
  2. **Third-party R2DBC version pinning** — the BOM also pins compatible versions of `io.r2dbc:r2dbc-spi`, `io.r2dbc:r2dbc-proxy`, `io.r2dbc:r2dbc-pool` against which the QuickPerf code in `sql-annotations-r2dbc` and `spring-boot-r2dbc-sql-starter` was verified (1.0.0 / 1.1.4 / 1.0.1). This keeps users' transitive R2DBC versions inside the supported range without forcing them to copy our `<dependencyManagement>` entries.
  3. **Future-proof versioning** — when r2dbc-proxy moves to 1.2.x and we re-verify, only `quick-perf-r2dbc-bom` changes; the umbrella BOM and JDBC users are unaffected.

#### 2.10.B Module layout

- New Maven module under `bom-r2dbc/` (sibling of `bom/`), declared in the root `pom.xml` `<modules>` list right after `bom`.
- `groupId` = `org.quickperf`, `artifactId` = `quick-perf-r2dbc-bom`, `packaging` = `pom`, `version` follows the parent `quick-perf` version (`1.1.1-SNAPSHOT` today; promoted to the v3 release version on cut).
- Parent reference is the same as `bom/pom.xml`'s parent (the root `quick-perf`).
- The BOM is a thin pom: only `<dependencyManagement>`. No code, no tests, no build plugins beyond `maven-license-plugin` (inherited from root).

#### 2.10.C `<dependencyManagement>` contents

| Group | Artifact | Version | Why |
|---|---|---|---|
| `org.quickperf` | `quick-perf-sql-annotations` | project version | API surface used by both JDBC and R2DBC modules; transitive consumer of `core`, `jvm-core`, etc. |
| `org.quickperf` | `quick-perf-sql-annotations-r2dbc` | project version | The R2DBC adapter module. |
| `org.quickperf` | `quick-perf-springboot-r2dbc-sql-starter` | project version | Spring Boot 3 starter — included so users on Boot 3 can omit explicit version. |
| `org.quickperf` | `quick-perf-junit4` | project version | Common test runner; cheap to include and stops version drift. |
| `org.quickperf` | `quick-perf-junit5` | project version | Same. |
| `org.quickperf` | `quick-perf-testng` | project version | Same. |
| `io.r2dbc` | `r2dbc-spi` | `1.0.0.RELEASE` | Pinned for 2.10.A.2. |
| `io.r2dbc` | `r2dbc-proxy` | `1.1.4.RELEASE` | Pinned — `QuickPerfProxyFactory` (placed in `io.r2dbc.proxy.callback`) depends on the package-private `JdkProxyFactory(ProxyConfig)` constructor, which is verified at this exact version. |
| `io.r2dbc` | `r2dbc-pool` | `1.0.1.RELEASE` | Pinned — verified that `dispose()` and `close()` are non-final at this version (§2.7.C). |

The QuickPerf entries are deduplicated against the umbrella `quick-perf-bom`: the same `<dependency>` blocks for the three R2DBC-related QuickPerf artifacts (`quick-perf-sql-annotations`, `quick-perf-sql-annotations-r2dbc`, `quick-perf-springboot-r2dbc-sql-starter`) appear in both BOMs at the same coordinates and version, so there is no risk of divergence.

#### 2.10.D Cross-references

- Root `pom.xml` `<modules>` gains `<module>bom-r2dbc</module>` after `<module>bom</module>` (no other root-pom changes).
- `bom/pom.xml` (`quick-perf-bom`) **stays** as the umbrella BOM and continues to manage every module as today; it is unchanged by this work apart from the third-party R2DBC version pins added in PR-9 (§7.10) which are kept in sync with `bom-r2dbc/pom.xml`.
- `docs/r2dbc.md` documents both BOMs under "Getting started" with a one-line `<scope>import</scope>` snippet for each, and recommends `quick-perf-r2dbc-bom` for R2DBC-only projects.

#### 2.10.E Verification

- A new module-build smoke step ensures the BOM resolves without dependency clashes: `mvn -pl bom-r2dbc -am clean install` is added as a CI lane (no test code; the install itself fails if the `<dependencyManagement>` block has a typo or unpublishable coordinate).
- A new IT in `spring/junit5-spring-boot-3-r2dbc-test` (`R2dbcBomImportIT`) is a no-op test class that lives in a Maven sub-test-project (under `src/test/it-projects/`) which imports `quick-perf-r2dbc-bom` instead of declaring versions explicitly. The IT is invoked via `maven-invoker-plugin` in the existing `testcontainers` profile and asserts that `mvn dependency:tree` resolves the expected r2dbc-proxy/r2dbc-spi/r2dbc-pool versions.

#### 2.10.F Files (PR-9)

- New: `bom-r2dbc/pom.xml`.
- Modified: `pom.xml` (root) — add `<module>bom-r2dbc</module>` to the `<modules>` list after `<module>bom</module>`.
- Modified: `bom/pom.xml` — pin r2dbc-proxy 1.1.4, r2dbc-spi 1.0.0, r2dbc-pool 1.0.1 (kept in sync with `bom-r2dbc/pom.xml`).
- New: `spring/junit5-spring-boot-3-r2dbc-test/src/test/it-projects/bom-import/pom.xml` and accompanying `R2dbcBomImportIT` (Maven Invoker entry).
- Documentation update: `docs/r2dbc.md` adds a "Dependency management" subsection.

---

## §3. Cross-cutting concerns

### 3.1 Configuration-loader landscape

- v2's references to `org.quickperf.sql.config.SqlAnnotationsConfigs` are **wrong**. The real path is `sql/sql-annotations/src/main/java/org/quickperf/sql/config/library/SqlAnnotationsConfigs.java` and the package is `org.quickperf.sql.config.library`. The class is package-private. All v3 alias additions live in this same package.
- Java SPI loader: `sql/sql-annotations/src/main/resources/META-INF/services/org.quickperf.config.library.QuickPerfConfigLoader` — registers the existing `SqlAnnotationsConfigLoader`. v3 adds the alias `AnnotationConfig`s to that class (or to `SqlAnnotationsConfigs.getAllAnnotationConfigs()` which it consumes).

### 3.2 R2DBC SPI / proxy versions

- r2dbc-proxy 1.1.4.RELEASE, r2dbc-spi 1.0.0.RELEASE, r2dbc-pool 1.0.1.RELEASE — these are the versions pinned in the BOM and verified for v3.
- The `io.r2dbc.proxy.callback.QuickPerfProxyFactory[Factory]` placement is contingent on `JdkProxyFactory(ProxyConfig)` retaining package-private constructor visibility. r2dbc-proxy 1.1.x is stable; if a future major release changes this, the `QuickPerfProxyFactory` must be revisited (covered by R-B1 in §6.1).

### 3.3 Connection-listener registries

- Two registries coexist after v3:
  - `ConnectionListenerRegistry` (existing, `InheritableThreadLocal`) — drives JDBC-typed callbacks via `QuickPerfDatabaseConnection`.
  - `ConnectionListenerHook` (new, JVM-global `CopyOnWriteArraySet`) — drives neutral `SqlConnectionListener` callbacks for both JDBC (alongside the legacy registry) and R2DBC (exclusively).
- The latent issue in `ConnectionListenerRegistry` (non-thread-safe `ArrayList connectionListenersOfTestJvm` field at line 26) is noted but out of scope; the forked-JVM path is single-threaded.

### 3.4 Concurrency under reactive schedulers

- `R2dbcQuickPerfListener.afterQuery` already wraps the per-recorder dispatch in `synchronized (recorder) { … }` (verified `R2dbcQuickPerfListener.java:103`). v3 keeps this pattern; the new `R2dbcConnectionLifecycleListener` does the same per-listener.
- v3 does **not** rely on `@Execution(SAME_THREAD)`. Verified: no `junit-platform.properties` in the repository enables `parallel.enabled=true` for the JUnit 5 platform; the `@Execution` annotation is therefore a no-op in current modules. The R2DBC Spring module pins Surefire `<parallel>none</parallel>` (verified `pom.xml:135-139`); that is the actual isolation mechanism for v2.
- For users running their own R2DBC tests with JUnit Jupiter parallel execution enabled, `r2dbc.md` documents that QuickPerf SQL annotations require Surefire `<parallel>none</parallel>` or per-class serialisation. Reactor-`Context`-based per-test routing is deferred to v3 (§1.3).

### 3.5 System properties and opt-out flags

- New system properties (read via `System.getProperty(name)` only; verified `core/src/main/java/org/quickperf/SystemProperties.java` lines 26, 42, 59, 76, 93):
  - `quickperf.sql.r2dbc.column-count.enabled` — default `true`. When `false`, `QuickPerfMonitoringResult` short-circuits to delegate-only.
  - `quickperf.sql.r2dbc.connection-events.enabled` — default `true`. When `false`, `R2dbcConnectionLifecycleListener` is not installed.
  - `quickperf.sql.r2dbc.cglib-fallback.enabled` — default `true`. When `false`, the BPP raises a configuration error instead of falling back to JDK proxy.
  - `quickperf.sql.r2dbc.diagnostics` — default `false`. When `true`, the BPP and listeners log structured diagnostic messages.
- **Important:** these properties are **not** read from `application.properties`/`application.yml`/`@SpringBootTest(properties=…)`. Documented in `r2dbc.md` and §3.5 of this plan. Spring-Environment bridging is deferred (§1.3).

### 3.6 Annotation-by-annotation matrix (R2DBC parity)

See Appendix A (rebuilt against the verified annotation list).

### 3.7 Surefire and parallelism

- Root pom (lines 109-110): `<parallel>all</parallel> <threadCount>5</threadCount>`. This applies to the JUnit 4 / TestNG providers; JUnit Jupiter parallel must be enabled via `junit.jupiter.execution.parallel.enabled=true` in `junit-platform.properties` to actually run tests in parallel — and no module enables it.
- The R2DBC Spring module overrides this with `<parallel>none</parallel>` (verified `spring/junit5-spring-boot-3-r2dbc-test/pom.xml:135-139`). v3 keeps this and documents it.

---

## §4. File inventory

### 4.1 New files

- `sql/sql-annotations-r2dbc/src/main/java/org/quickperf/sql/r2dbc/QuickPerfMonitoringResult.java` (PR-2)
- `sql/sql-annotations-r2dbc/src/main/java/org/quickperf/sql/r2dbc/ColumnCountStore.java` (PR-2)
- `sql/sql-annotations-r2dbc/src/main/java/org/quickperf/sql/r2dbc/R2dbcSyntheticStatement.java` (PR-2)
- `sql/sql-annotations-r2dbc/src/main/java/io/r2dbc/proxy/callback/QuickPerfProxyFactory.java` (PR-2)
- `sql/sql-annotations-r2dbc/src/main/java/io/r2dbc/proxy/callback/QuickPerfProxyFactoryFactory.java` (PR-2)
- `sql/sql-annotations-r2dbc/src/main/java/org/quickperf/sql/r2dbc/R2dbcConnectionLifecycleListener.java` (PR-4)
- `sql/sql-annotations/src/main/java/org/quickperf/sql/connection/ConnectionListenerHook.java` (PR-4)
- `sql/sql-annotations/src/main/java/org/quickperf/sql/connection/SqlConnectionListener.java` (PR-4)
- `sql/sql-annotations/src/main/java/org/quickperf/sql/connection/SqlConnectionEvent.java` (PR-4)
- `sql/sql-annotations/src/main/java/org/quickperf/sql/annotation/ExpectQueryBatching.java` (PR-3)
- `sql/sql-annotations/src/main/java/org/quickperf/sql/annotation/ExpectQueryExecution.java` (PR-3)
- `sql/sql-annotations/src/main/java/org/quickperf/sql/annotation/ExpectMaxQueryExecution.java` (PR-3)
- `sql/sql-annotations/src/main/java/org/quickperf/sql/batch/SqlQueryBatchingVerifier.java` (PR-3)
- `sql/sql-annotations/src/main/java/org/quickperf/sql/execution/QueryExecutionVerifier.java` (PR-3)
- `sql/sql-annotations/src/main/java/org/quickperf/sql/execution/MaxQueryExecutionVerifier.java` (PR-3)
- ITs: `R2dbcSelectedColumnsIT`, `R2dbcExpectNoConnectionLeakIT`, `R2dbcConnectionLeakIT`, `R2dbcAnalyzeSqlIT`, `R2dbcDisplaySqlIT`, `R2dbcCglibFallbackIT`, `R2dbcAutoConfigDisposeIT`, `R2dbcAutoConfigCloseIT`, `R2dbcPoolValidationQueryNotCountedIT`, `R2dbcExpectQueryBatchingIT`, `R2dbcExpectQueryExecutionIT`, `R2dbcExpectMaxQueryExecutionIT`, `R2dbcAcquisitionTimeIT` (PR-2/4/6/7).
- `docs/r2dbc.md` (PR-9).
- `bom-r2dbc/pom.xml` — dedicated `quick-perf-r2dbc-bom` (see §2.10) (PR-9).

### 4.2 Modified files

- `sql/sql-annotations-r2dbc/src/main/java/org/quickperf/sql/r2dbc/R2dbcExecutionAdapter.java` — drain `ColumnCountStore` and feed the synthetic statement (PR-2).
- `sql/sql-annotations-r2dbc/src/main/java/org/quickperf/sql/r2dbc/PlaceholderRewriter.java` — state-machine scanner (PR-1).
- `sql/sql-annotations/src/main/java/org/quickperf/sql/display/SqlDisplayer.java` (or equivalent renderer) — empty-`orderedKeys` numeric fallback (PR-1).
- `sql/sql-annotations/src/main/java/org/quickperf/sql/connection/QuickPerfDatabaseConnection.java` — fire neutral events alongside JDBC-typed ones (PR-4).
- `sql/sql-annotations/src/main/java/org/quickperf/sql/connection/ConnectionListener.java` — `implements SqlConnectionListener` (PR-4).
- `sql/sql-annotations/src/main/java/org/quickperf/sql/connection/ConnectionLeakListener.java` — opaque-id tracking (PR-4).
- `sql/sql-annotations/src/main/java/org/quickperf/sql/config/library/SqlAnnotationsConfigs.java` — alias `AnnotationConfig` entries (PR-3).
- `sql/sql-annotations/src/main/java/org/quickperf/sql/batch/SqlStatementBatchVerifier.java` — extract package-private static helper (PR-3).
- `sql/sql-annotations/src/main/java/org/quickperf/sql/execution/JdbcQueryExecutionVerifier.java` and `MaxJdbcQueryExecutionVerifier.java` — same extraction (PR-3).
- `core/src/main/java/org/quickperf/TestExecutionContext.java` lines 152-165 — fix the missing `.add(perfRecorderClass)` (PR-7).
- `spring/spring-boot-r2dbc-sql-starter/src/main/java/org/quickperf/spring/boot/r2dbc/QuickPerfR2dbcProxyBeanPostProcessor.java` — Spring AOP `ProxyFactory` outer-CGLIB design (PR-6).
- `spring/spring-boot-r2dbc-sql-starter/src/main/resources/META-INF/spring.factories` (or the Boot 3 `org.springframework.boot.autoconfigure.AutoConfiguration.imports`) — register the auto-configuration if needed (PR-6).
- `spring/junit5-spring-boot-3-r2dbc-test/pom.xml` — pin Surefire `<parallel>none</parallel>` (already in place, verified line 137).
- `bom/pom.xml` — pin r2dbc-proxy 1.1.4, r2dbc-spi 1.0.0, r2dbc-pool 1.0.1 (kept in sync with `bom-r2dbc/pom.xml`).
- `pom.xml` (root) — add `<module>bom-r2dbc</module>` to the `<modules>` list after `<module>bom</module>` (PR-9).

### 4.3 Test-tree changes

- `sql/sql-annotations/src/test/.../*ConnectionLeak*Test.java` and `*ConnectionLeak*IT.java` — assertions on `countLeakedConnections()` instead of `connections.size()`.
- `sql/sql-annotations-r2dbc/src/test/.../PlaceholderRewriterTest.java` — new cases for `?`-only, `E'…'`, `U&'…'`, `$tag$`.
- `sql/sql-annotations-r2dbc/src/test/.../QuickPerfMonitoringResultTest.java` — unit tests covering all four `Result` API surfaces.
- New ITs listed in §4.1.

---

## §5. Test strategy

### 5.1 Unit tests (PR-1, PR-2, PR-3, PR-4, PR-7)

- `PlaceholderRewriterTest` — exhaustive cases including the new states. Existing `preserves_postgres_double_colon_cast_operator` (line 55) is preserved.
- `QuickPerfMonitoringResultTest` — one test per `Result` API surface (`map(BiFunction)`, `map(Function)`, `flatMap`, `filter`, `getRowsUpdated`); one test for cascaded `filter().map(...)`; one test for heterogeneous batches (first count wins).
- `ColumnCountStoreTest` — `recordOnce` idempotency; `drain` removal semantics; concurrent producers.
- `R2dbcConnectionLifecycleListenerTest` — one test per surfaced SPI method (`commitTransaction`, `rollbackTransaction`, `beginTransaction(0|1 args)`, `setAutoCommit`, `setTransactionIsolationLevel`, `createSavepoint`, `releaseSavepoint`, `rollbackTransactionToSavepoint`).
- `ConnectionLeakListenerTest` — opaque-id tracking under concurrent acquire/release.
- `TestExecutionContextDedupTest` — two annotations sharing a recorder class produce a single recorder.
- Alias verifier unit tests — one per alias verifier asserting parity with the legacy verifier on synthetic measures.

### 5.2 Integration tests (PR-2, PR-4, PR-6, PR-7)

- `R2dbcSelectedColumnsIT` — `@ExpectSelectedColumn`, `@ExpectMaxSelectedColumn`, `@ExpectUpdatedColumn`, `@ExpectMaxUpdatedColumn` against a Testcontainers-backed PostgreSQL R2DBC connection.
- `R2dbcExpectNoConnectionLeakIT` — leak detection across reactive scheduler hops.
- `R2dbcConnectionLeakIT` — `@ProfileConnection` produces lifecycle entries on the Reactor scheduler thread.
- `R2dbcAnalyzeSqlIT` — column-count-driven `SELECT *` detection works for R2DBC.
- `R2dbcDisplaySqlIT` — `?`-only SQL renders correctly; `E'…'`, `U&'…'`, `$tag$` render correctly.
- `R2dbcCglibFallbackIT` — JDK-proxy fallback path emits WARN; `instanceof ConnectionPool` is false; `ConnectionFactory.create()` works.
- `R2dbcAutoConfigDisposeIT` — `dispose()` propagates to the underlying `ConnectionPool`.
- `R2dbcAutoConfigCloseIT` — `close()` propagates to the underlying `ConnectionPool`.
- `R2dbcPoolValidationQueryNotCountedIT` — pool with non-null validation query; `@ExpectMaxQueriesNumber(N)` counts only user queries.
- `R2dbcExpectQueryBatchingIT`, `R2dbcExpectQueryExecutionIT`, `R2dbcExpectMaxQueryExecutionIT` — alias parity with the legacy `@Expect*Jdbc*` annotations.
- `R2dbcAcquisitionTimeIT` — `@ProfileConnection`-style acquisition timing recorded for reactive `ConnectionFactory.create()`.

### 5.3 Stress / scheduler tests

- `R2dbcParallelSchedulerIT` — verify listener visibility from `Schedulers.parallel()` and `Schedulers.boundedElastic()` threads after dynamic listener registration.
- `R2dbcMultipleTestsSerialIT` — run 100 R2DBC tests in sequence (`<parallel>none</parallel>`); assert no listener leakage between tests (`ColumnCountStore` is empty after each test).

---

## §6. Risks

### 6.1 Top risks (rebuilt to reflect v3 design)

- **R-B1 (high impact, medium likelihood) — `io.r2dbc.proxy.callback` package extension breaks on r2dbc-proxy upgrade.**
  - Mitigation: pin r2dbc-proxy version range in `bom/pom.xml`; smoke-build against the next minor on each upgrade; the package-extension comment in both classes documents the constraint.
- **R-B2 (high impact, low likelihood) — `ConnectionListenerHook` events lost if a listener is registered after `ConnectionFactory.create()` has already emitted Connections.**
  - Mitigation: documented contract that recorders register in `startRecording()` BEFORE the test body executes; `R2dbcParallelSchedulerIT` verifies the contract.
- **R3 (medium) — CGLIB cannot subclass a `final` `ConnectionFactory` subclass.**
  - Mitigation: JDK-proxy fallback with WARN log; `R2dbcCglibFallbackIT` covers this path. Spring Objenesis is the standard CGLIB enabler; if missing on the user classpath, fallback fires.
- **R4 (medium) — `ConnectionPool.dispose()` / `close()` change in r2dbc-pool minor.**
  - Mitigation: verified non-final at 1.0.1 (`ConnectionPool.java:305, 310`); `R2dbcAutoConfigDisposeIT` and `R2dbcAutoConfigCloseIT` are guard tests.
- **R5 (medium) — Synthetic JDBC `Statement` for column count throws on accidental misuse.**
  - Mitigation: `R2dbcSyntheticStatement` throws `UnsupportedOperationException` for any method other than `getResultSet().getMetaData().getColumnCount()`. Failure mode is loud, not silent.
- **R6 (low) — Listener dispatch is not lock-free.**
  - Mitigation: per-recorder serialisation (`synchronized (recorder)`) is already in place at `R2dbcQuickPerfListener.java:103` and applied to `R2dbcConnectionLifecycleListener`. Throughput is bounded by recorder count, not concurrency.
- **R7 (low) — Stack-trace capture for connection leaks costs CPU.**
  - Mitigation: gated by `quickperf.sql.r2dbc.diagnostics`; default is no capture.
- **R8 (low) — Per-test attribution under `parallel=all`.**
  - Mitigation: documented in `r2dbc.md`; the R2DBC Spring module pins `<parallel>none</parallel>`. Reactor-`Context`-based routing is a v3 deferral.

### 6.2 Risks dropped from v2

- v2's R6 line about scanner reuse for JDBC — the JDBC display path uses datasource-proxy's per-index substitution, not the new scanner. Dropped.
- v2's claim that `QuickPerfDatabaseConnection.buildFrom(...)` is "hot-path short-circuited" — incorrect. v3 reframes the activation gate as registration-time (§2.8).

### 6.3 v3-deferral list (carried in `r2dbc.md`)

- Spring `Environment` bridge.
- Reactor `Context`-based per-test routing.
- Oracle `Q'…'`, MS SQL `[id]`, cross-string E-string continuation in the placeholder scanner.
- `@ExpectNo*` shorthand annotations.
- Auto-detection of R2DBC URL scheme without the Spring Boot starter.
- Connection listener registry thread-safety hardening.

---

## §7. PR breakdown

### 7.1 PR-0 — Package skeleton + neutral plumbing (no behaviour change yet)

- Files: `ConnectionListenerHook.java`, `SqlConnectionListener.java`, `SqlConnectionEvent.java`. `ConnectionListener` updated to `implements SqlConnectionListener` with no-op defaults.
- Tests: unit tests for the hook and the bridge.
- Risk: tiny; no observable behaviour changes.

### 7.2 PR-1 — Placeholder scanner + `?`-only renderer fix

- Files: `PlaceholderRewriter.java` (state machine), `SqlDisplayer*.java` (empty-orderedKeys fallback). Tests for E-strings, U&-strings, `$tag$`, `?`-only.
- Independent of the rest.

### 7.3 PR-2 — `Result` decorator + column count

- **Depends on PR-6** (the BPP must install `QuickPerfProxyFactoryFactory` for `R2dbcSelectedColumnsIT` to be meaningful end-to-end). PR-2 itself can land before PR-6 with unit-only tests; the full IT moves with PR-6.
- Files: `QuickPerfMonitoringResult.java`, `ColumnCountStore.java`, `R2dbcSyntheticStatement.java`, `QuickPerfProxyFactory.java`, `QuickPerfProxyFactoryFactory.java` (in `io.r2dbc.proxy.callback`), `R2dbcExecutionAdapter.java` (drain logic).
- Tests: unit tests for the decorator and store.

### 7.4 PR-3 — Annotation aliases

- Files: 3 alias annotations, 3 alias verifiers, `SqlAnnotationsConfigs.java`, package-private static-helper extraction in legacy verifiers.
- Tests: alias verifier unit tests; alias parity ITs pending PR-2/PR-6 completion.
- Independent of PR-1/PR-2 conceptually but coordinates with PR-7's dedup fix.

### 7.5 PR-4 — Connection lifecycle for R2DBC

- **Depends on PR-0** (uses `ConnectionListenerHook` + `SqlConnectionListener`).
- **Depends on PR-6** for the listener registration in the Spring Boot 3 R2DBC starter.
- Files: `R2dbcConnectionLifecycleListener.java`, `QuickPerfDatabaseConnection.java` (neutral-event firing), `ConnectionLeakListener.java` (opaque-id tracking).
- Tests: unit tests for the listener; ITs in the R2DBC Spring module.

### 7.6 PR-5 — `@ProfileConnection` for R2DBC

- **Depends on PR-4.**
- Wires `R2dbcConnectionEventsProfilerListener` against `ConnectionListenerHook` and the new neutral events.
- Tests: `@ProfileConnection` IT in the R2DBC Spring module.

### 7.7 PR-6 — Spring Boot 3 R2DBC starter (BPP rewrite)

- Files: `QuickPerfR2dbcProxyBeanPostProcessor.java` rewrite to Spring AOP `ProxyFactory(target).setProxyTargetClass(true).addInterface(QuickPerfR2dbcProxyMarker.class).addInterface(Wrapped.class)` + outer `MethodInterceptor` design. Auto-configuration registration. ITs for fallback, dispose, close, validation-not-counted.
- The CGLIB seam from this PR is what PR-2 / PR-4 / PR-7 use to install their listeners and the custom `ProxyConfig` carrying `QuickPerfProxyFactoryFactory`.

### 7.8 PR-7 — Connection acquisition time

- **Depends on PR-6** (uses the listener installation seam).
- **Depends on PR-0** (uses `ConnectionListenerHook`).
- Files: a per-execution acquisition-time recorder hooked from `R2dbcConnectionLifecycleListener.beforeMethod("create")` / `afterMethod("create")`; `TestExecutionContext.buildPerfRecordersToExecute` dedup-bug one-liner fix.
- Tests: `R2dbcAcquisitionTimeIT`, `TestExecutionContextDedupTest`.

### 7.9 PR-8 — Display & analyzeSql ITs

- **Depends on PR-1, PR-2.**
- ITs only: `R2dbcDisplaySqlIT`, `R2dbcAnalyzeSqlIT`.

### 7.10 PR-9 — Documentation and BOM

- `docs/r2dbc.md` documents:
  - Supported annotations (verified list, see Appendix A).
  - System properties and that they are `-D`-only (no Spring `Environment` bridge in v2).
  - The Surefire `<parallel>none</parallel>` requirement.
  - The `instanceof ConnectionPool` guarantee (and JDK-proxy fallback caveat).
  - Pool validation queries are NOT counted.
  - **Dependency management** — both `quick-perf-bom` (umbrella) and the new dedicated `quick-perf-r2dbc-bom`, with `<scope>import</scope>` snippets for each. R2DBC-only consumers are pointed at the dedicated BOM.
- `bom/pom.xml` — pin r2dbc-proxy 1.1.4, r2dbc-spi 1.0.0, r2dbc-pool 1.0.1 (kept in sync with `bom-r2dbc/pom.xml`).
- **NEW** `bom-r2dbc/pom.xml` — dedicated `quick-perf-r2dbc-bom` artifact (see §2.10). `<dependencyManagement>` pins the three QuickPerf R2DBC-related modules (`quick-perf-sql-annotations`, `quick-perf-sql-annotations-r2dbc`, `quick-perf-springboot-r2dbc-sql-starter`), the three test-runner modules (JUnit 4/5 + TestNG), and the three external R2DBC artifacts (`r2dbc-spi 1.0.0`, `r2dbc-proxy 1.1.4`, `r2dbc-pool 1.0.1`).
- `pom.xml` (root) — add `<module>bom-r2dbc</module>` to the `<modules>` list right after `<module>bom</module>`.
- BOM smoke verification — `mvn -pl bom-r2dbc -am clean install` is added to the CI matrix (the install itself fails on a malformed `<dependencyManagement>` block) and a Maven Invoker IT under `spring/junit5-spring-boot-3-r2dbc-test/src/test/it-projects/bom-import/` imports the BOM and asserts that `dependency:tree` resolves the verified r2dbc-proxy/r2dbc-spi/r2dbc-pool versions.

### 7.11 PR dependency graph

```
PR-0 ──┬──> PR-4 ──> PR-5
       └──> PR-7
PR-1 ──> PR-8
PR-6 ──> PR-2 ──> PR-8
PR-6 ──> PR-7
PR-3  (independent; coordinates with PR-7's dedup fix)
PR-9  (last; depends on all)
```

Differs from v2: PR-2 is now explicitly downstream of PR-6 (the BPP installs the custom `ProxyConfig`); PR-7 is downstream of PR-6 too.

---

## Appendix A — Annotation matrix (rebuilt against verified annotations)

Legend: ✅ works in v1 · 🆕 added by v3 · ➖ N/A · 🚧 deferred to v3 release

| Annotation | v1 R2DBC | v3 R2DBC | v3 fix sections |
|---|---|---|---|
| `@AnalyzeSql` | partial (column-count analyses returned 0) | 🆕 full | §2.1, §2.5 |
| `@DisableLikeWithLeadingWildcard` | partial (`?`-only display broken) | 🆕 full | §2.6 |
| `@DisableQueriesWithoutBindParameters` | partial (`?`-only display broken) | 🆕 full | §2.6 |
| `@DisableSameSelects` | partial (`?`-only display broken) | 🆕 full | §2.6 |
| `@DisableSameSelectTypesWithDifferentParamValues` | partial (`?`-only display broken) | 🆕 full | §2.6 |
| `@DisableStatements` | ✅ | ✅ | — |
| `@DisplaySql` | partial (`?`-only display broken) | 🆕 full | §2.6 |
| `@DisplaySqlOfTestMethodBody` | partial | 🆕 full | §2.6 |
| `@EnableLikeWithLeadingWildcard` | ✅ | ✅ | — |
| `@EnableQueriesWithoutBindParameters` | ✅ | ✅ | — |
| `@EnableSameSelects` | ✅ | ✅ | — |
| `@EnableSameSelectTypesWithDifferentParamValues` | ✅ | ✅ | — |
| `@EnableStatements` | ✅ | ✅ | — |
| `@ExpectDelete` | ✅ | ✅ | — |
| `@ExpectInsert` | ✅ | ✅ | — |
| `@ExpectJdbcBatching` | ✅ | ✅ | — |
| `@ExpectJdbcQueryExecution` | ✅ | ✅ | — |
| `@ExpectMaxDelete` | ✅ | ✅ | — |
| `@ExpectMaxInsert` | ✅ | ✅ | — |
| `@ExpectMaxJdbcQueryExecution` | ✅ | ✅ | — |
| `@ExpectMaxQueryExecutionTime` | ✅ | ✅ | — |
| `@ExpectMaxSelect` | ✅ | ✅ | — |
| `@ExpectMaxSelectedColumn` | ❌ (always 0) | 🆕 full | §2.1 |
| `@ExpectMaxUpdate` | ✅ | ✅ | — |
| `@ExpectMaxUpdatedColumn` | ❌ (always 0) | 🆕 full | §2.1 |
| `@ExpectNoConnectionLeak` | ❌ (InheritableThreadLocal empty) | 🆕 full | §2.3 |
| `@ExpectSelect` | ✅ | ✅ | — |
| `@ExpectSelectedColumn` | ❌ (always 0) | 🆕 full | §2.1 |
| `@ExpectUpdate` | ✅ | ✅ | — |
| `@ExpectUpdatedColumn` | ❌ (always 0) | 🆕 full | §2.1 |
| `@ProfileConnection` | ❌ | 🆕 full | §2.3, §2.4 |
| `@ExpectQueryBatching` (alias) | n/a | 🆕 | §2.9 |
| `@ExpectQueryExecution` (alias) | n/a | 🆕 | §2.9 |
| `@ExpectMaxQueryExecution` (alias) | n/a | 🆕 | §2.9 |

Annotations the v2 plan referenced but which **do not exist** in the current working tree (kept here for traceability; v3 does not introduce them):

`@ExpectNoSelect`, `@ExpectNoInsert`, `@ExpectNoUpdate`, `@ExpectNoDelete`, `@ExpectNoQueries`, `@ExpectQueriesNumber`, `@ExpectMaxQueriesNumber`, `@ExpectMaxQueryExecutionTimeWithDb`, `@ExpectMaxQueryExecutionTimeWithoutDb`, `@ExpectNoLikeWithLeadingWildcard`, `@DisableExactSameSelects`, `@ExpectMaxSameSelectTypes`, `@DisableSelectInTransaction`, `@DisableQueriesDisplay`, `@DisableSqlDisplay`, `@JdbcSpyDataSource`, `@JdbcStatementSpy`. These names are out of scope for v3.

---

## Appendix B — Cross-reference to v2 plan

- v2 §2.1 ("create a `R2dbcResultCallbackHandler extends ResultCallbackHandler`") → v3 §2.1 (decorator + `ProxyFactoryFactory` swap).
- v2 §2.2 (`createResultProxy`) → v3 §2.1.D (`wrapResult`).
- v2 §2.3 (`InheritableThreadLocal` mitigated by `@Execution(SAME_THREAD)`) → v3 §2.3 (`ConnectionListenerHook` JVM-global).
- v2 §2.4 (commit/rollback) → v3 §2.4 (commitTransaction/rollbackTransaction + savepoints + getMetadata + validate).
- v2 §2.7 (CGLIB extends `AbstractConnectionFactory`) → v3 §2.7 (CGLIB subclass of `ConnectionPool`; interface list `Wrapped`, `Disposable`, `Closeable`).
- v2 §2.7 `ProxyConnectionFactory.wrap(Connection)` pseudo-code → v3 §2.7.B (does not exist; replaced by outer-CGLIB + inner-`ProxyConnectionFactory.builder(target)`).
- v2 §2.9 (alias dual-registration with same verifier instance) → v3 §2.9 (alias-specific verifier classes via package-private static helper).
- v2 §3.1 (`org.quickperf.sql.config.SqlAnnotationsConfigs`) → v3 §3.1 (`org.quickperf.sql.config.library.SqlAnnotationsConfigs`, package-private).
- v2 §3.4 (`@Execution(SAME_THREAD)`) → v3 §3.4 (no-op; replaced by Surefire `<parallel>none</parallel>`).
- v2 §3.5 (Spring Environment) → v3 §3.5 (`-D` only; bridge deferred).
- v2 §6.1 R6 (scanner reused for JDBC) → dropped in v3 §6.2.
- v2 §7 (PR-2/PR-7 independent of PR-6) → v3 §7.11 (PR-2 and PR-7 depend on PR-6).
- v2 Appendix A (fictional annotations) → v3 Appendix A (rebuilt against verified file list).
