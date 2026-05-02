# Plan — Reactive (R2DBC) Database Support for QuickPerf with Spring Boot

> **Status:** Design proposal — **Revision 3.1 (post final validation review)**. No code changes are made by this plan.
> **Target stack:** QuickPerf `1.1.x`, Spring Boot `3.2.x`, R2DBC SPI `1.0.0.RELEASE`, R2DBC Proxy `1.1.4.RELEASE`, Reactor Core `3.6.5`.
> **Authoring:** Synthesized from two parallel design proposals (Claude Opus 4.7 (Extra high reasoning) and GPT-5.5), revised after a second round of independent reviews by both models (R2 / R2.1), revised again after a **third round** of independent reviews by Claude Opus 4.7 (Extra high reasoning) and GPT-5.5 (extra-deep reasoning) (R3), and finalized after a closing Opus xhigh validation pass (R3.1). Where the original proposals diverged, §10 records the synthesis decision; post-review changes are annotated `(R2)`, `(R2.1)`, `(R3)`, or `(R3.1)` in §10.

---

## 0. Revision history

**R2 — post-review changes incorporated:**

1. **Lifecycle redesign (Critical).** The original §3.8 / §5.1 `R2dbcQuickPerfRecorderBridge` would never have been instantiated: `TestExecutionContext.java:152-165` only builds recorders that are mapped from applied annotations, and `QuickPerfConfigsLoader` only sorts execution-order metadata for *already-instantiated* recorders. Replaced with a **two-line edit to `PersistenceSqlRecorder.startRecording`/`stopRecording`** plus a new `SqlRecorderHook` class in `sql-annotations` (R2DBC reads from it; hook is empty for JDBC-only users).
2. **Java version (Critical).** `spring-boot-r2dbc-sql-starter` cannot be Java 8 / `dependencies.max.jdk.version=1.8`: `spring-boot-autoconfigure` 3.2.5 is Java 17 bytecode (verified via `javap -verbose ... R2dbcAutoConfiguration` → major version 61). The starter is now Java 17, lives under `<profile id="SpringBoot3Tests">` activation, and is built only on JDK 17+. `sql-annotations-r2dbc` remains Java 8 (R2DBC SPI baseline).
3. **Deferred-annotation warnings (Critical).** r2dbc-proxy's `ProxyExecutionListener.beforeMethod(MethodExecutionInfo)` has no access to `TestExecutionContext` and cannot read `getPerfAnnotations()`. Removed the runtime WARN; documented behavior in Javadoc/README; `SqlRecorderHook` exposes an optional callback that `PersistenceSqlRecorder.startRecording` can use if a future enhancement wants to emit the warning from a place that does have `TestExecutionContext`.
4. **Placeholder regex (Critical).** `s/:[A-Za-z_][A-Za-z0-9_]*/?/g` corrupts PostgreSQL `column::int` to `column:?`. Replaced with negative lookbehind `(?<!:):[A-Za-z_]\w*` and combined with order-preserving capture so named bindings stay aligned with `?` slots in `@DisplaySql`.
5. **Named-binding ordering (Critical).** `Bindings.getNamedBindings()` returns a `SortedSet` (alphabetical), so `WHERE name=:name AND active=:active` would emit values in the wrong order under `@DisplaySql`. The adapter now captures placeholder source order during the regex rewrite and looks up `Bindings.getNamedBindings()` by name (Opus's "Option A").
6. **Annotation matrix (Important).** `@AnalyzeSql` and `@DisableSameSelectTypesWithDifferentParamValues` are **not** free rides for multi-binding R2DBC executions: `QueryParamsExtractor.java:50-55` throws `IllegalStateException` when `parametersList.size() > 1` (existing JDBC behavior for batches). Marked as "in scope for single-binding executions; multi-binding executions skipped with the existing JDBC semantics".
7. **Suggestion location (Important).** `SqlExecutions.format(...)` (lines 159–174) does **not** currently emit any `JdbcSuggestion` text. Round-trip suggestions live in `SelectAnalysis.java:60` and `SqlReport.java:87`; starter-detection messages live in `SpringDataSourceConfig.getMessage()`. The `SqlExecutions.format` edit is removed; suggestions go to their proper locations.
8. **`NoMeasure` → `PerfRecord` (Important).** `RecordablePerformance<NoMeasure>` references a non-existent type. The lifecycle redesign (R2-1) makes this moot — there is no bridge class.
9. **License compliance (Important).** r2dbc-pool POM declares `<name>Apache License 2.0</name>` over `https://...`; `licenses\licenses.xml` lines 33–43 list neither alias. Plan now requires concrete additions to `licenses.xml`.
10. **`@ExpectMaxQueryExecutionTime` semantics (Minor).** Existing `SqlQueryExecutionTimeExtractor.java:31-43` returns the **maximum** elapsed time among executions, not a sum. Wording corrected.
11. **Pool-acquisition timing claim (Minor).** Removed: r2dbc-proxy `QueryExecutionInfo.getExecuteDuration()` measures query execution, not `ConnectionFactory.create()`. v2 may add `beforeMethod`/`afterMethod` capture if pool-acquisition observability is wanted.
12. **`spring.factories` for SB 3 (Minor).** Removed the optional `META-INF/spring.factories` entry; SB 3.0+ uses only `META-INF/spring/...AutoConfiguration.imports`.
13. **BPP double-proxy claim (Minor).** Existing JDBC `QuickPerfProxyBeanPostProcessor` does **not** have an explicit double-proxy marker check — only scoped-target skipping. Plan now states the R2DBC variant *adds* the marker (independent of the JDBC variant) and softens the "wraps last among priority-ordered BPPs" claim.
14. **Path mistakes (Minor).** `AllParametersAreBoundExtractor.java` lives under `bindparams\`, not `select\analysis\`; `pom.xml` parallel/threadCount is at lines 109–110, not 104–113.
15. **Standalone `ConnectionFactories.get(...)` user story (scope).** Removed from §1.1 acceptance scope. Documented as v2; Spring-managed `ConnectionFactory` is the only v1 surface.
16. **TestNG / multi-framework parallelism (gap).** Documented that the JVM-global hook constraint applies to TestNG (`testng/pom.xml` declares `<parallel>all</parallel>`), Gradle, and any framework, not just Surefire. Mitigation: framework-specific config samples in starter README.
17. **GraalVM native-image, observability stacking, multi-tenancy, Kotlin coroutines (gaps).** Added §8 risk rows or §9 open questions for each.

The pre-revision file size and structure is preserved; line-anchor references (e.g., file:line) have been updated to actual locations.

**R2.1 — second-pass corrections (post final acceptance review):**

- **§3.8 diff context fix.** `PersistenceSqlRecorder` declares only `SqlRecorder<SqlExecutions>` (the `RecordablePerformance` super-interface is inherited via `SqlRecorder.java:22`). `register` is an instance call but `unregister` is a static call (`SqlRecorderRegistry.java:51`). Diff updated to match the real lines 25, 32-35, 43-48.
- **§3.8 wording fix.** `SqlRecorderRegistry`'s JVM-global map type is `Map<Class<? extends SqlRecorder>, SqlRecorder>` (line 25), not `Map<String, SqlRecorder>`; line range corrected.
- **§5.4 `SpringDataSourceConfig` insertion-point fix.** Actual branch order in `getMessage()` is SB1 → SB2-or-SB3 → Spring4 → Spring5 (no separate SB3 detection branch). The new R2DBC branch must be inserted **before** the existing combined `containsSpringBoot2() || containsSpringBoot3()` block, gated on `containsSpringBoot3() && containsR2dbcSpi() && !contains(QUICKPERF_SPRING_BOOT_R2DBC_SQL_STARTER)`.
- **§4.6 mixed-type SQL limitation.** The two-pass NAMED-then-POSITIONAL rewrite preserves source order within one placeholder type but loses interleaved order across types (e.g., `WHERE name=:name AND id=$1 AND active=:active` produces `["name", "active", "0"]` instead of `["name", "0", "active"]`). Mixed positional + named placeholders in a single statement are documented as **not supported in v1**; no real R2DBC driver produces such SQL.
- **§6.7 license-aliases diff structural fix.** `<valid>` is the OUTER container; aliases use `<names><name>…</name></names>` and `<urls><url>…</url></urls>` envelopes; the actual entry name is "Apache Software License, Version 2.0". Diff rewritten accordingly.

**R3 — third-round corrections incorporated (post third-round Opus xhigh + GPT-5.5 review):**

1. **r2dbc-proxy 1.1.4 binding API (Critical).** §4.6's binding-extraction code referenced `NullValue.INSTANCE` and `RegularValue` — these types **do not exist** in `io.r2dbc:r2dbc-proxy:1.1.4.RELEASE` (verified by `javap` against the published JAR — only `BoundValue` and `BoundValue$DefaultBoundValue` are present in `io/r2dbc/proxy/core/`). The actual API is a single `BoundValue` interface with `boolean isNull()` and `Object getValue()`. §4.6 now uses `bv.isNull() ? null : bv.getValue()` — without this fix the proposed code would not compile.
2. **Reactor-thread `addQueryExecution` race (Critical).** `SqlExecutions.sqlExecutions` is a `java.util.ArrayDeque` (`SqlExecutions.java:32`) — not thread-safe. A reactive test that runs `flatMap` / `parallel` / multiple subscriptions can fire `afterQuery` on multiple Reactor scheduler threads. The `CopyOnWriteArraySet` hook only protects the active-recorder set — it does not serialize calls into the recorder. §3.8 / §4.1 / §6.4 now mandate `synchronized (recorder)` per dispatch, plus a new `R2dbcConcurrentDispatchToOneRecorderTest` that exercises the real production path (not constructor-injected bypasses) under N concurrent threads.
3. **`SqlConfigLoader` citation (Critical).** §3.6 R2 footnote pointed at `SqlConfigLoader.java:67-89`; lines 67-89 are actually `loadRecorderExecutionOrdersBeforeTestMethod()` / `loadRecorderExecutionOrdersAfterTestMethod()` (execution ordering). The annotation→recorder mapping is `loadAnnotationConfigs()` at lines 31-65, forwarding to `SqlAnnotationsConfigs.*` constants. Footnote rewritten — the lifecycle redesign rationale rests on this claim.
4. **§11 PR 4 stale wording (Critical).** PR 4 still said "append R2DBC starter suggestion path in `getMessage()` (after the SB3 detection branch)". `SpringDataSourceConfig.getMessage()` has no separate SB3 detection branch (verified at lines 30-77: SB1 → SB2-or-SB3 combined → Spring4 → Spring5). Inserting after the combined block makes the R2DBC suggestion unreachable. PR 4 wording now matches §5.4 R2.1: "before the combined `containsSpringBoot2() || containsSpringBoot3()` branch, gated by `containsSpringBoot3() && containsR2dbcSpi() && !contains(QUICKPERF_SPRING_BOOT_R2DBC_SQL_STARTER)`".
5. **"Silently skipped" wording for multi-binding executions (Important).** §2.1 rows for `@DisableSameSelectTypesWithDifferentParamValues` and `@AnalyzeSql` claimed multi-binding R2DBC executions are "silently skipped (matches JDBC)". `QueryParamsExtractor.java:50-55` actually **throws** `IllegalStateException` and the throw is not caught by `SelectAnalysisExtractor`. R2DBC's idiomatic batch (`Statement.bind().add().bind().add().execute()`) hits this far more often than JDBC. Wording corrected — users must keep batched SELECTs out of these annotations' scope.
6. **`SelectAnalysis.getNPlusOneSelectAlert()` insertion shape (Important).** The line-60 emission lives inside a single-expression `return` (`SelectAnalysis.java:52-63`) — a conditional R2DBC append cannot be a one-line edit. §5.4 now shows the explicit `StringBuilder` refactor.
7. **`SqlReport.buildNPlusOneMessage()` insertion shape (Important).** Line 87 sits between two `+ System.lineSeparator()` segments (`SqlReport.java:80-93`); a naive append produces "JdbcMsgR2dbcMsg" without a separator. §5.4 now shows the corrected diff with one explicit `lineSeparator()` between the two suggestion blocks.
8. **`SpringDataSourceConfig` interaction matrix (Important).** §3.6 / §5.4 now include an explicit 4-cell truth table (JDBC starter ± × R2DBC starter ±). The most user-visible failure mode — JDBC starter missing AND R2DBC starter missing on a Boot 3 + R2DBC-only app — was emitting "add `quick-perf-springboot2-sql-starter`" instead of pointing at the R2DBC starter. The new branch order resolves this.
9. **`SqlRecorderRegistry.unregister` asymmetry note (Important).** `SqlRecorderRegistry.unregister(...)` at lines 51-56 has **no** JVM-global branch — only the per-thread path. The new `SqlRecorderHook.unregister(...)` is always JVM-global. §3.8 documents the asymmetry so future maintainers don't assume the two registries follow the same convention.
10. **§6.1 vs §4.11 contradiction on listener tests (Important).** §6.1 said `R2dbcQuickPerfListenerTest` registers a Mockito recorder into the static `SqlRecorderHook`; §4.11 said tests under `parallel=all` must use a private hook instance or constructor-injected recorders. Resolved: `R2dbcQuickPerfListener` accepts a `Supplier<Iterable<SqlRecorder<?>>>` constructor parameter (defaulting to `SqlRecorderHook::getActiveRecorders`); unit tests inject a private set, integration tests use the default. Cleaner and unblocks `parallel=all` for the unit-test module.
11. **`@Autowired ConnectionPool` regression (Important).** §4.10 said `setProxyTargetClass(true)` is unnecessary because `ConnectionFactory` is an interface. True — but after wrapping, the bean is a JDK proxy implementing `ConnectionFactory` (and `QuickPerfR2dbcProxyMarker`), not `ConnectionPool`. Users with `@Autowired ConnectionPool pool` see Spring fail to wire. Documented as a v1 limitation; users should switch to `@Autowired ConnectionFactory`.
12. **Coexistence test split (Important).** §6.3 conflated two tests into one. `@DataR2dbcTest` slice imports include `FlywayAutoConfiguration` and `R2dbcAutoConfiguration` but **not** `DataSourceAutoConfiguration` — the original "coexistence" test could not exercise JDBC at all. Split: (a) `SpringBootR2dbcFlywayStartupNoiseJunit5Test` (`@DataR2dbcTest`) verifies Flyway DDL is not counted; (b) `SpringBootR2dbcJdbcCoexistenceJunit5Test` (`@SpringBootTest`) issues one `JdbcTemplate.queryForObject(...)` AND one R2DBC repo query inside the test method, asserts unified count + both `dataSourceName` forms in the report.
13. **Mixed positional + named placeholder tests (Important).** §6.1 said "mixed positional/named preserve all values"; §4.6 said mixed is unsupported in v1. §6.1 now matches §4.6: a single explicit unit test asserts the documented unsupported behavior (e.g., `mixed_positional_and_named_throws_or_documents_loss_of_order`), instead of listing as a preservation test.
14. **Lifecycle regression test for hook leak on startup failure (Important).** §3.8 / §11 PR 1 now require a regression test that forces `SqlRepositoryFactory.getSqlRepository(...)` to fail mid-`startRecording` and asserts `SqlRecorderHook.getActiveRecorders()` is empty after framework cleanup. Without this, a leaked recorder contaminates subsequent reactive tests even under `parallel=none`.
15. **Static `@Bean` BPP factory method (Important).** §5.2 now declares `QuickPerfR2dbcConfig` as `@Configuration(proxyBeanMethods = false)` and the BPP factory method as `public static QuickPerfR2dbcProxyBeanPostProcessor …(…)` so Spring's `BeanPostProcessorChecker` / early-instantiation gotcha is avoided.
16. **Generic-type warning (Minor).** §3.8 hook code now uses `Set<SqlRecorder<?>>` instead of raw `Set<SqlRecorder>` to avoid unchecked-cast warnings (`SqlRecorder<R extends PerfRecord>` at `SqlRecorder.java:22`).
17. **`SqlExecution.writeExternal` field categorization (Minor).** §4.2 R2.1 said "transfers only counts, query text, parameter sets, and batch metadata". Actual serialized fields (`SqlExecution.java:117-123`) are `dataSourceName`, `connectionId`, `statementType`, `isBatch`, `batchSize`, queries list, parameters list. The omitted-fields claim (no timing/throwable/result) is the load-bearing part; it stays accurate.
18. **r2dbc-spi license alias (Minor).** §6.7 / §11 PR 5 wording mentioned only `r2dbc-pool`; verified that `r2dbc-spi:1.0.0.RELEASE` also declares the license under `Apache License 2.0` over `https://...` (same alias mismatch). Both POMs are covered by the same alias additions, but the wording now names both deps.
19. **Misc minor edits.** §5.3 reword `com.h2database:h2` (already transitive `compile` via `r2dbc-h2:1.0.0.RELEASE`); §5.4 note that `SelectAnalysis` and `SqlReport` are independent emission sites; §6.1 add a `R2dbcQueryParamsExtractorTest` lock-in case for PostgreSQL `$N` indexing (`Binding.getKey()` returns 0 vs 1 differs by driver).

**R3.1 — final-pass corrections (post final Opus xhigh validation review):**

- **`dataSourceName` format contradiction (Important).** §4.2 line 421 wrote `synth.setDataSourceName("r2dbc:" + connectionInfo.getConnectionId())` while §2.2 / §6.3 / §1.3 #3 require `r2dbc:<beanName>`. `ConnectionInfo.getConnectionId()` is a per-connection UUID/counter from r2dbc-proxy, not the Spring bean name — using it would (a) emit one tag per pooled connection (instead of one per `ConnectionFactory` bean), (b) cause `SpringBootR2dbcJdbcCoexistenceJunit5Test`'s "report contains `r2dbc:<r2dbc-bean-name>`" assertion to fail, (c) bloat `@DisplaySql` output, and (d) break the §8 multi-tenancy story. §4.2 now plumbs `beanName` from `QuickPerfR2dbcProxyBeanPostProcessor.postProcessAfterInitialization(bean, beanName)` into `R2dbcQuickPerfListener` at construction; `connectionId` is preserved on `ExecutionInfo.connectionId` for diagnostics.
- **`Binding.getBoundValue()` API description (Important).** §4.6 line 473 still listed `NullValue` / `RegularValue` as the `BoundValue` subtypes; the (R3) note 60 lines below correctly says these don't exist (R3 #1 fixed only the code, not the prose 60 lines above). Fixed: the API bullet at line 475 now says "discriminate via `BoundValue.isNull()` and `BoundValue.getValue()`" with a back-reference to the (R3) note.
- **Listener constructor signature (Minor).** §4.11 says `Supplier<Iterable<SqlRecorder<?>>>`; §5.1 / §6.1 said "constructor-injected `Set<SqlRecorder<?>>`". A `Set<…>` is not a `Supplier<Iterable<…>>`, so the test code wouldn't compile against the §4.11 signature. Reconciled on §4.11's `Supplier<Iterable<SqlRecorder<?>>>` (defaulting to `SqlRecorderHook::getActiveRecorders`) — production uses the default; tests inject `() -> mySet` over a private mutable `Set`. §5.1 / §6.1 wording updated.
- **`PersistenceSqlRecorder` line range (Trivial).** §3.8 R2.1 footnote cited "lines 25, 32-35, 43-48"; `stopRecording`'s body actually spans 44-52 (line 43 is the `@Override`; the body extends through the `if (datasourceProxyVerifier.hasQuickPerfBuiltSeveralDataSourceProxies())` block ending at 52). Footnote corrected to "lines 25, 32-35, 44-52".
- **§10 threading-isolation cell (Trivial).** Cell still read "force `parallel=none` for reactive test module"; R3 #10 narrowed `parallel=none` to integration tests only. Cell now distinguishes: integration tests `parallel=none`; unit tests in `sql-annotations-r2dbc` run under root `parallel=all, threadCount=5` via constructor-injected `Supplier<Iterable<SqlRecorder<?>>>`; only `SqlRecorderHookTest` (which exercises the static set directly) overrides to `parallel=none`.

---

## 1. Problem statement & user-visible goal

### 1.1 What "reactive databases for Spring Boot" means in QuickPerf

Today QuickPerf's SQL recording is anchored on `javax.sql.DataSource` and `net.ttddyy:datasource-proxy`. *Reactive support* means doing the same kind of recording for `io.r2dbc.spi.ConnectionFactory` when the application uses Spring Data R2DBC, plain `org.springframework.r2dbc.core.DatabaseClient`, `R2dbcEntityTemplate`, or a directly-injected `ConnectionFactory`, on top of:

- `@SpringBootTest` (full reactive context),
- `@DataR2dbcTest` (reactive slice).

**Out of scope for v1:**

- Standalone tests obtaining a `ConnectionFactory` via `ConnectionFactories.get(...)` (no Spring context). Users would have to manually wrap with `ProxyConnectionFactory.builder(...)`. A documented helper (`QuickPerfR2dbcConnectionFactoryBuilder`) is provided in `sql-annotations-r2dbc` for this case, but no integration tests cover it; deferred to v2.
- **Hibernate Reactive** (Mutiny / Reactor adapter). Different connection pool, different SPI; not addressed by this plan.
- **R2DBC over CompletableFuture wrappers** (e.g., third-party adapters). The R2DBC SPI is `Publisher<T>`-based; any wrapper still needs a subscriber to drive the underlying publishers to completion before `stopRecording`. Out of scope.
- **Spring Data R2DBC Kotlin Coroutines** (`CoroutineCrudRepository`). Indirectly covered when the underlying `ConnectionFactory` is wrapped, but no Kotlin test is included in v1 (gap §9).

### 1.2 Concrete user story

A user adds a single Maven test dependency (the new reactive starter) and an existing test like:

```java
@QuickPerfTest
@DataR2dbcTest
class PlayerRepositoryR2dbcTest {

    @Autowired private PlayerRepository repo; // ReactiveCrudRepository

    @ExpectSelect(1)
    @Test
    void should_select_all_players_in_one_query() {
        StepVerifier.create(repo.findAll()).expectNextCount(2).verifyComplete();
    }
}
```

…starts to fail/pass against R2DBC executions without changing test code, exactly like its JDBC counterpart.

### 1.3 Acceptance criteria

1. All annotations listed as **In scope** in §2.1 (≈ 19 entries when enable/disable aliases are counted) react to R2DBC-issued queries on Spring Boot 3.
2. An R2DBC-only test asserting `@ExpectSelect(1)` produces the same diagnostic format and error report quality as JDBC tests, with a new `R2DBCSuggestion` hint added to (not replacing) the existing `JdbcSuggestion` text.
3. A test using **both** JDBC (e.g. Flyway migration) and R2DBC against the same database has its annotations target both APIs in a unified `SqlExecutions` measure.
4. Existing JDBC behavior is bit-for-bit unchanged — adding the new starter is opt-in, R2DBC dependencies do not leak onto the test classpath of JDBC-only users, and the new `SqlRecorderHook` (§3.8) is empty/no-op when no R2DBC listener has subscribed.
5. **Lifecycle correctness:** the R2DBC listener forwards events to the active `PersistenceSqlRecorder` even though it fires on a Reactor scheduler thread (not the test thread). Verified by an integration test that asserts `SqlRecorderHook.getActiveRecorders().size() == 1` from inside an `afterQuery` callback during an `@ExpectSelect(1)` test.

---

## 2. Scope decisions

### 2.1 Annotation scope matrix (v1)

| Annotation | v1 Decision | Rationale |
|---|---|---|
| `@ExpectSelect`, `@ExpectMaxSelect` | **In scope** | SQL text + `QueryTypeRetriever` parsing is API-agnostic. |
| `@ExpectInsert`, `@ExpectMaxInsert` | **In scope** | Same. |
| `@ExpectUpdate`, `@ExpectMaxUpdate` | **In scope** | Same; cover `UPDATE … RETURNING`. |
| `@ExpectDelete`, `@ExpectMaxDelete` | **In scope** | Same. |
| `@ExpectJdbcQueryExecution`, `@ExpectMaxJdbcQueryExecution` | **In scope, name retained** | Counts statement executions regardless of API. The "Jdbc" prefix is historical; keep to preserve binary compatibility. Aliases `@ExpectQueryExecution` / `@ExpectMaxQueryExecution` are **deferred** to a follow-up PR (open question §9.2). |
| `@ExpectMaxQueryExecutionTime` | **In scope, single-execution semantics** | r2dbc-proxy exposes `QueryExecutionInfo.getExecuteDuration() : Duration`. Note: existing `SqlQueryExecutionTimeExtractor.java:31-43` returns the **maximum** elapsed time across recorded executions (not a sum). R2DBC inherits the same semantics — the annotation fires when any single recorded query exceeds the threshold. |
| `@DisplaySql`, `@DisplaySqlOfTestMethodBody` | **In scope** | Output is API-agnostic. |
| `@DisableSameSelects` | **In scope** | Compares query text. |
| `@DisableSameSelectTypesWithDifferentParamValues` | **In scope, single-binding executions only** | Internally calls `QueryParamsExtractor` which throws `IllegalStateException` when `parametersList.size() > 1` (`QueryParamsExtractor.java:50-55`). This is the **existing** JDBC behavior for batched executions; R2DBC `Statement.bind().add().bind().execute()` falls into the same branch. (R3) **The throw propagates** — `SelectAnalysisExtractor` does not catch it, so a batched SELECT in scope of this annotation **fails the test with `IllegalStateException`**, in JDBC today and in R2DBC after this plan. R2DBC's idiomatic batches hit this far more often than JDBC. Document loudly in §7.2 / §7.3: keep batched SELECTs out of `@DisableSameSelectTypesWithDifferentParamValues` scope, or break batches into single executions. A defensive try/catch in `SelectAnalysisExtractor` is tracked as v2 follow-up. |
| `@DisableLikeWithLeadingWildcard` / `@EnableLikeWithLeadingWildcard` | **In scope** | Inspects SQL text + bind values. |
| `@DisableStatements` / `@EnableStatements` | **In scope** | R2DBC has only `Statement`; map to `StatementType.STATEMENT` only when **no** bindings are present; otherwise `PREPARED`. This avoids false positives on parameterised R2DBC `Statement.bind(...).execute()`. See §4.5. |
| `@DisableQueriesWithoutBindParameters` / `@EnableQueriesWithoutBindParameters` | **In scope** | Reuse `AllParametersAreBoundExtractor` (`bindparams/AllParametersAreBoundExtractor.java:118-120`) after rewriting placeholders to `?` in the adapter. See §4.6. |
| `@AnalyzeSql` | **In scope, single-binding executions only** | `SqlAnalysisExtractor.java:27-31` delegates to `SelectAnalysisExtractor` which uses `QueryParamsExtractor`; same throw on `parametersList.size() > 1`. (R3) Same constraint as `@DisableSameSelectTypesWithDifferentParamValues` above — the `IllegalStateException` propagates and fails the test. |
| `@ExpectUpdatedColumn`, `@ExpectMaxUpdatedColumn` | **In scope (free ride)** | Parse SQL `SET … WHERE …` clause; no metadata needed. |
| `@ExpectMaxSelectedColumn`, `@ExpectSelectedColumn` | **Deferred** | R2DBC `RowMetadata` is only available inside `Result.map`. Capturing it requires a `ResultRowConverter` + `ConnectionInfo.getValueStore()`. Adapter sets `ExecutionInfo.getResult()==null` so existing column-count extractor returns 0 (predictable behavior). v2 work. |
| `@ExpectJdbcBatching` | **Deferred** | R2DBC `Batch` semantics differ; existing verifier is hard-wired around datasource-proxy `StatementType`. Future `@ExpectR2dbcBatch` annotation (v2). |
| `@ExpectNoConnectionLeak` | **Deferred** | `ConnectionListener` chain is built around `java.sql.Connection`. R2DBC `Connection.close() : Publisher<Void>` requires a parallel `R2dbcConnectionListener` chain (v2). |
| `@ProfileConnection` | **Deferred** | Same root cause as `@ExpectNoConnectionLeak`. |

**Behavior when a deferred annotation is found on a reactive test:** the annotation has no effect on R2DBC executions; it does not crash the test. **No runtime WARN is emitted in v1** — `ProxyExecutionListener.beforeMethod(MethodExecutionInfo)` has no access to `TestExecutionContext.getPerfAnnotations()` (verified by `javap` of r2dbc-proxy 1.1.4), and adding the warning to `PersistenceSqlRecorder` would couple the JDBC core to R2DBC. The behavior is documented in:

- the README "Supported / unsupported annotations" table (§7.2),
- per-annotation Javadoc on the deferred annotations (§7.3),
- the starter Javadoc.

A future v2 enhancement could surface the warning from a small QuickPerf-side `RecordablePerformance` that owns the `TestExecutionContext` reference (see open question §9, Q-13).

### 2.2 Coexistence: `DataSource` + `ConnectionFactory` in the same context

A typical Boot 3 reactive app keeps a JDBC `DataSource` (Flyway/Liquibase migration) and an R2DBC `ConnectionFactory` (running app).

Decisions for v1:

1. **Both proxies are installed in parallel.** The existing JDBC `BeanPostProcessor` continues to wrap `DataSource` beans; the new R2DBC `BeanPostProcessor` wraps `ConnectionFactory` beans. Each maintains its own listener.
2. **Both feed the same `PersistenceSqlRecorder` via the same `SqlExecutions` measure.** Counts are unified.
3. Flyway DDL during context startup is **not** counted because recording starts at test-method scope (`PersistenceSqlRecorder.startRecording`), not at context initialization.
4. **Source tagging:** the synthetic `ExecutionInfo.dataSourceName` is set to `r2dbc:<beanName>` for R2DBC executions and remains the JDBC bean name for JDBC executions, so `@DisplaySql` output remains traceable in mixed contexts.
5. **Escape hatch deferred:** a future property `quickperf.sql.r2dbc.exclusive=true` would let users ignore JDBC-issued queries entirely. Not needed for v1.

---

## 3. Architectural approach

### 3.1 Option comparison

| Option | Verdict |
|---|---|
| **A** — Extend `datasource-proxy` | ❌ Not viable; datasource-proxy is JDBC-only. |
| **B** — Use `io.r2dbc:r2dbc-proxy` and adapt events to existing `SqlExecution` model | ✔ Cheap, reuses every existing verifier. |
| **C** — Hand-roll a `ConnectionFactory` decorator from scratch | ❌ Reinvents r2dbc-proxy poorly; loses bind-parameter conversion, `ProxyConfig`, `ResultRowConverter`. |
| **D** — Refactor `SqlExecution` to a query-API-neutral `RecordedQuery` model with adapters for both proxies | Cleanest long term but touches ≥ 30 verifier classes. Big-bang risk. v2. |

### 3.2 Recommendation: **Option B with a thin "Option D-light" seam**

Use `r2dbc-proxy` for the listener side. On entry into the recorder, **synthesize datasource-proxy `ExecutionInfo` and `List<QueryInfo>` instances** from the r2dbc-proxy `QueryExecutionInfo`. All existing verifiers, formatters, and analyzers continue to work unchanged. The seam (`R2dbcExecutionAdapter`) is the only place that knows about both proxy libraries.

```text
io.r2dbc.proxy.core.QueryExecutionInfo           net.ttddyy.dsproxy.ExecutionInfo
       │                                                ▲
       │ R2dbcExecutionAdapter.toDsProxy(info)          │
       └───────────────────► synthesize ────────────────┘
                                  ▼
                       SqlRecorder.addQueryExecution(...)
                                  ▼
                       (unchanged) SqlExecutions / verifiers
```

**Why not D first:** D's blast radius (renaming and reworking ≥ 30 verifier/extractor classes that read `QueryInfo`/`ExecutionInfo`) is unjustified before R2DBC support is proven valuable. Adopting D as a follow-up after v1 is straightforward — the adapter seam is the first half of the work.

### 3.3 Module layout (2 new modules)

```
sql/
 ├── sql-annotations/                        (existing — minor edits, see §5.4)
 ├── sql-annotations-r2dbc/                  NEW: bytecode 1.8, r2dbc-proxy listener + adapter
 │
spring/
 ├── spring-boot-2-sql-starter/              unchanged
 ├── spring-boot-r2dbc-sql-starter/          NEW: SB-3 reactive autoconfig, bytecode 17 (built only when JDK ≥ 17)
 └── junit5-spring-boot-3-r2dbc-test/        NEW: test-only, bytecode 17
```

**Rationale for not folding R2DBC into `spring-boot-2-sql-starter`:**

- The existing starter declares `provided` deps on `spring-jdbc`. Bringing in `r2dbc-spi`/`r2dbc-proxy`/`reactor-core` even as `provided` adds Reactor types onto the test classpath of every JDBC user.
- Mirrors Spring Boot's own `spring-boot-starter-data-jdbc` / `spring-boot-starter-data-r2dbc` split.
- Allows the R2DBC starter to evolve independently (Boot 3-only, JDK 17-only).

**Why no separate `sql-r2dbc-spring` module** (the existing JDBC has `sql-spring4` / `sql-spring5` because the BPP is reused across Spring 4, 5, and Boot 1, 2):

For R2DBC v1 (Spring Boot 3+ only) the BPP and its `@Configuration` are not reused across multiple Spring versions, so a single `spring-boot-r2dbc-sql-starter` module that contains both the BPP and the autoconfiguration is sufficient. If wider Spring (non-Boot) reactive support is added later, the BPP can be extracted then.

### 3.4 Bytecode / Java version

| Module | `maven.compiler.source/target` | `dependencies.max.jdk.version` enforcer | Built when |
|---|---|---|---|
| `sql-annotations-r2dbc` | `1.8` | `1.8` | Always (JDK 8+). `r2dbc-spi` 1.0, `r2dbc-proxy` 1.1, `reactor-core` 3.6 are all Java 8 bytecode (verified via `javap -verbose ... ConnectionFactory.class` → major version 52). |
| `spring-boot-r2dbc-sql-starter` | `17` | `17` | **Only inside `<profile id="SpringBoot3Tests">`** (or any profile activated by `<jdk>[17,)</jdk>`). `spring-boot-autoconfigure` 3.2.5 is Java 17 bytecode (`javap -verbose org/springframework/boot/autoconfigure/r2dbc/R2dbcAutoConfiguration.class` → major version 61). Setting `dependencies.max.jdk.version=1.8` here would fail enforcer immediately. |
| `junit5-spring-boot-3-r2dbc-test` | `17` | `17` | Inside `<profile id="SpringBoot3Tests">` (already JDK ≥ 17). |

The root enforcer property `<dependencies.max.jdk.version>1.7</dependencies.max.jdk.version>` (root `pom.xml`) and the `1.7` source in `sql-annotations` itself are **unchanged**. JDK 8 developers running `mvn clean install` build `sql-annotations-r2dbc` (Java 8) but skip `spring-boot-r2dbc-sql-starter` and the integration test module (both gated on JDK 17).

### 3.5 Maven dependencies (versions chosen for SB 3.2.x compatibility)

Spring Boot 3.2.5 manages:

| Dependency | Version |
|---|---|
| `io.r2dbc:r2dbc-spi` | `1.0.0.RELEASE` |
| `io.r2dbc:r2dbc-proxy` | `1.1.4.RELEASE` |
| `io.r2dbc:r2dbc-pool` | `1.0.1.RELEASE` |
| `io.r2dbc:r2dbc-h2` | `1.0.0.RELEASE` |
| `io.projectreactor:reactor-core` | `3.6.5` |
| `io.projectreactor:reactor-test` | `3.6.5` |
| `org.springframework.data:spring-data-r2dbc` | `3.2.5` |

**Final dependency block** for `sql-annotations-r2dbc/pom.xml`:

```xml
<dependencies>
    <dependency>
        <groupId>org.quickperf</groupId>
        <artifactId>quick-perf-sql-annotations</artifactId>
        <version>${project.version}</version>
    </dependency>
    <dependency>
        <groupId>io.r2dbc</groupId>
        <artifactId>r2dbc-spi</artifactId>
        <version>1.0.0.RELEASE</version>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>io.r2dbc</groupId>
        <artifactId>r2dbc-proxy</artifactId>
        <version>1.1.4.RELEASE</version>
    </dependency>
    <dependency>
        <groupId>io.projectreactor</groupId>
        <artifactId>reactor-core</artifactId>
        <version>3.6.5</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

For `spring-boot-r2dbc-sql-starter/pom.xml`, add `spring-boot-autoconfigure` (provided) and the above as `compile` (transitive to user). Pin no version on `reactor-core` — let consumer's Boot BOM win.

### 3.6 New autoconfig (Spring Boot side)

- **New `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`** in `spring-boot-r2dbc-sql-starter` listing `org.quickperf.spring.boot.r2dbc.QuickPerfR2dbcProxyBeanAutoConfiguration`.
- **Test-slice imports** for `@DataR2dbcTest`: `META-INF/spring/org.springframework.boot.test.autoconfigure.data.r2dbc.AutoConfigureDataR2dbc.imports` containing the same FQCN.
- **Auto-configuration class** `QuickPerfR2dbcProxyBeanAutoConfiguration`:
  - `@AutoConfiguration(after = R2dbcAutoConfiguration.class)`
  - `@ConditionalOnClass({ ConnectionFactory.class, ProxyConnectionFactory.class })`
  - `@ConditionalOnBean(ConnectionFactory.class)`
  - `@Import(QuickPerfR2dbcConfig.class)` which `@Bean`s `QuickPerfR2dbcProxyBeanPostProcessor` (`PriorityOrdered`, `LOWEST_PRECEDENCE - 1`, mirroring the JDBC variant).

> **Note (R2 / R3):** the original §3.6 also listed a `META-INF/services/org.quickperf.config.library.QuickPerfConfigLoader` SPI entry registering an `R2dbcConfigLoader` to position a freestanding recorder. This was removed: see §3.8 below for why a freestanding recorder cannot be instantiated by the existing core, and the **`SqlRecorderHook` redesign** that replaces it. R2DBC adds **no new `QuickPerfConfigLoader` SPI**; it relies on the existing `SqlConfigLoader.loadAnnotationConfigs()` (`SqlConfigLoader.java:31-65`) which forwards to `SqlAnnotationsConfigs.*` constants — each one declaring `.perfRecorderClass(PersistenceSqlRecorder.class)` for >20 annotation entries. (R3 — earlier draft mis-cited lines 67-89, which are `loadRecorderExecutionOrders*` execution-ordering methods, not annotation-to-recorder bindings.)

> **(R3) `SpringDataSourceConfig` interaction matrix.** With both JDBC and R2DBC starters now possible on a Boot 3 app, `getMessage()` must steer users to the right starter. The four cells:
>
> |   | R2DBC starter present | R2DBC starter missing |
> |---|---|---|
> | **JDBC starter present** | both fine; no message needed | suggest R2DBC starter (when `containsR2dbcSpi()`) |
> | **JDBC starter missing** | (rare) suggest JDBC starter | **suggest R2DBC starter** when `containsR2dbcSpi()`; else fall through to existing JDBC SB2-or-SB3 message |
>
> The new R2DBC branch is inserted **before** the existing combined `containsSpringBoot2() || containsSpringBoot3()` branch (which currently emits "add `quick-perf-springboot2-sql-starter`"). Gating: `containsSpringBoot3() && containsR2dbcSpi() && !contains(QUICKPERF_SPRING_BOOT_R2DBC_SQL_STARTER)`. The bottom-right cell — newcomer adds R2DBC + Boot 3, never realises QuickPerf needs an R2DBC starter — was previously emitting the wrong (JDBC) suggestion. §6.4 adds an explicit test for this cell.

### 3.7 Listener flow

```text
ConnectionFactory bean (e.g. r2dbc-h2 driver, possibly wrapped by ConnectionPool)
        │
        ▼  wrapped by  QuickPerfR2dbcProxyBeanPostProcessor
ProxyConnectionFactory + R2dbcQuickPerfListener (ProxyExecutionListener)
        │
        ▼  user code calls  Statement#execute(): Publisher<Result>
        ▼  subscriber subscribes (anywhere — Reactor scheduler thread)
R2dbcQuickPerfListener.afterQuery(QueryExecutionInfo)
        │
        ▼
R2dbcExecutionAdapter.toDsProxy(info)  →  ExecutionInfo + List<QueryInfo>
        │
        ▼  for each recorder in SqlRecorderHook.getActiveRecorders()…
SqlRecorder.addQueryExecution(executionInfo, queries, listenerId)
        │
        ▼  (unchanged)
PersistenceSqlRecorder → SqlRepository.addQueryExecution(...) → SqlExecutions
        │
        ▼  at end-of-test (unchanged)
PerfIssuesEvaluator + verifiers
```

### 3.8 Lifecycle bridge: `SqlRecorderHook` (R2)

**Why a freestanding recorder doesn't work.** `TestExecutionContext.java:152-165` instantiates only those `RecordablePerformance` classes that are mapped, via `AnnotationConfig`, from annotations actually present on the test method. `QuickPerfConfigsLoader` only sorts execution-order metadata for *already-instantiated* recorders — it does not instantiate anything. Adding an SPI loader that returns an order entry for a `R2dbcQuickPerfRecorderBridge` therefore has no effect on instantiation, and the bridge's `startRecording` would never run (verified in the test-runner trace; the bridge object is never new-ed).

**Why `r2dbc-proxy` cannot warn either.** `ProxyExecutionListener.beforeMethod(MethodExecutionInfo)` exposes the connection / statement / batch object but no QuickPerf objects (no `TestExecutionContext`, no annotation list). So the alternative ("warn from `beforeMethod`") proposed in earlier drafts is not implementable.

**The hook design.** A new tiny class in `sql-annotations`:

```java
// sql/sql-annotations/src/main/java/org/quickperf/sql/SqlRecorderHook.java
package org.quickperf.sql;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public final class SqlRecorderHook {
    private static final Set<SqlRecorder<?>> ACTIVE = new CopyOnWriteArraySet<>();
    private SqlRecorderHook() {}
    public static void register(SqlRecorder<?> recorder)   { ACTIVE.add(recorder); }
    public static void unregister(SqlRecorder<?> recorder) { ACTIVE.remove(recorder); }
    public static Set<SqlRecorder<?>> getActiveRecorders() { return Collections.unmodifiableSet(ACTIVE); }
}
```

And a **two-line edit** to `PersistenceSqlRecorder`:

```diff
 public class PersistenceSqlRecorder implements SqlRecorder<SqlExecutions> {
     @Override
     public void startRecording(TestExecutionContext testExecutionContext) {
+        SqlRecorderHook.register(this);
         SqlRecorderRegistry.INSTANCE.register(this);
         sqlRepository = SqlRepositoryFactory.getSqlRepository(testExecutionContext);
     }
     @Override
     public void stopRecording(TestExecutionContext testExecutionContext) {
+        SqlRecorderHook.unregister(this);
         SqlRecorderRegistry.unregister(this);                  // existing static call
         WorkingFolder workingFolder = testExecutionContext.getWorkingFolder();
         sqlRepository.flush(workingFolder);
         // … existing several-proxies warning …
     }
 }
```

> **R2.1 — diff-context note:** `PersistenceSqlRecorder` declares only `SqlRecorder<SqlExecutions>` (the `RecordablePerformance` super-interface is inherited via `SqlRecorder<R> extends RecordablePerformance<R>` at `SqlRecorder.java:22` — no second declaration). Also note the asymmetry in the existing code: `register` is an **instance** call (`SqlRecorderRegistry.INSTANCE.register(this)`) but `unregister` is a **static** call (`SqlRecorderRegistry.unregister(this)` at `SqlRecorderRegistry.java:51`). The diff above mirrors the real lines 25, 32-35, and 44-52 of `PersistenceSqlRecorder.java` (R3.1 — earlier draft cited 43-48; `stopRecording`'s body actually spans lines 44-52 because of the `if (datasourceProxyVerifier.hasQuickPerfBuiltSeveralDataSourceProxies()) { … }` block).

**Properties of this design:**

- **Empty-and-cheap when R2DBC isn't used.** A `CopyOnWriteArraySet` add/remove is O(n) over the active set, which is 1 in 99 % of cases (one test running in this JVM). For pure JDBC users, the `R2dbcQuickPerfListener` is never instantiated, so nothing reads from the hook.
- **Survives Reactor scheduler hops.** The hook is JVM-global, so the listener's `afterQuery` callback (which may run on `boundedElastic`, `parallel`, or a Netty event loop) sees the active recorders without any `ThreadLocal` propagation.
- **Works for cross-JVM tests.** `TEST_CODE_EXECUTING_IN_NEW_JVM=true` mode (forked-JVM annotations) triggers the existing `SqlRecorderRegistry`'s JVM-global `Map<Class<? extends SqlRecorder>, SqlRecorder>` mode (`SqlRecorderRegistry.java:25, 42-44, 59-60, 73-74`); the hook coexists with both modes.
- **Replaces `R2dbcSqlRecorderRegistry`** that earlier drafts placed in `sql-annotations-r2dbc`. There is no separate registry; the hook is the single source of active recorders for R2DBC.
- **Trade-off acknowledged.** This adds two lines to `PersistenceSqlRecorder` and one new file to `sql-annotations`. Earlier drafts claimed "no edits to `PersistenceSqlRecorder`" — that claim is now invalidated. The two-line edit is the minimum change that makes the lifecycle physically possible. The "Authoring note" at §11 is updated accordingly.

> **(R3) The hook does NOT make the recorder thread-safe.** `SqlRecorderHook` only protects the *active-recorder set* with `CopyOnWriteArraySet`. The recorder it returns (`PersistenceSqlRecorder`) writes to `SqlExecutions`, whose `sqlExecutions` field is a `java.util.ArrayDeque` (`SqlExecutions.java:32`) — **not thread-safe**. A reactive test using `flatMap` / `parallel` / multiple subscriptions can fire `afterQuery` on multiple Reactor scheduler threads concurrently, racing on `addLast`/iteration. **The R2DBC listener MUST serialize dispatch per recorder** (see §4.1):
>
> ```java
> for (SqlRecorder<?> recorder : SqlRecorderHook.getActiveRecorders()) {
>     synchronized (recorder) {
>         recorder.addQueryExecution(executionInfo, queries, listenerId);
>     }
> }
> ```
>
> This preserves the existing recorder/repository invariants without modifying them in v1. §6.4 covers the regression test.

> **(R3) Asymmetry vs `SqlRecorderRegistry`.** `SqlRecorderRegistry.unregister(...)` (`SqlRecorderRegistry.java:51-56`) only handles the per-thread mode — it has **no** JVM-global branch (in `TEST_CODE_EXECUTING_IN_NEW_JVM=true` mode the JVM-global map is only cleared on `clear()` at JVM teardown). The new `SqlRecorderHook.unregister(...)` is **always** JVM-global. This is intentional: the legacy registry's JVM-global pathway exists for the forked-JVM single-test scenario (the JVM exits anyway), whereas `SqlRecorderHook` spans the lifetime of the test JVM and must clean up on every `stopRecording`. Future maintainers extending the lifecycle should not assume the two registries follow the same convention.

> **(R3) Exception-safety on `startRecording` failure.** The diff places `SqlRecorderHook.register(this)` as the *first* line of `startRecording` and `SqlRecorderHook.unregister(this)` as the *first* line of `stopRecording` — symmetric register/unregister. If a future edit moves work between these and `register` succeeds while subsequent work throws, the recorder leaks into the JVM-global hook and contaminates subsequent reactive tests *even under `parallel=none`*. The §11 PR 1 exit criteria therefore add a regression test that forces `SqlRepositoryFactory.getSqlRepository(...)` to fail mid-`startRecording` and asserts `SqlRecorderHook.getActiveRecorders()` is empty after framework cleanup.

---

## 4. Cross-cutting design problems and concrete solutions

### 4.1 Threading / context propagation (the hardest)

**Symptom.** `SqlRecorderRegistry` and `ConnectionListenerRegistry` use `InheritableThreadLocal<Map<...>>`. JDBC works because `getConnection()` and `afterQuery` execute on the test thread (or a thread that inherited from it before `register()`).

R2DBC breaks this:
- Reactor schedulers (`boundedElastic`, `parallel`, Netty event loops in r2dbc drivers) are pre-allocated *before* the test thread sets the recorder. `InheritableThreadLocal` only copies the parent's value at thread *creation*, so workers see `null`.
- A reactive chain may hop between schedulers (`subscribeOn`, `publishOn`, async I/O) before reaching `Statement#execute()`.

**Options considered:**

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| (a) Reactor `Context` propagation via user-site `contextWrite` | Idiomatic | Requires user code change — unacceptable for a drop-in library | ❌ |
| (b) `Hooks.onEachOperator` + `io.micrometer:context-propagation` lifting a `ThreadLocal` to `Context` | Automatic; works in Reactor 3.5+ | Extra dependency; intrusive global hook; behavior changes per Reactor version; misbehaves under `parallel` | ❌ for v1 |
| (c) Per-`ConnectionFactory`-proxy + JVM-global recorder set | Trivial; thread-agnostic | When parallel tests share a `ConnectionFactory`, recorders cross-contaminate | ✔ acceptable IF tests are single-threaded per JVM |
| (d) Per-`ConnectionInfo.getValueStore()` recorder pinning at `beforeCreateOnConnectionFactory` | Lifetime-of-connection isolation | The `create` call may itself happen on a non-test thread | Partial fix; combine with (c) |

**Recommendation: (c) + Surefire `parallel=none` for any test module that exercises reactive recording.**

- The JVM-global active-recorder set is `SqlRecorderHook` (§3.8) in `sql-annotations`. There is no separate `R2dbcSqlRecorderRegistry`.
- The new R2DBC test module sets `<parallel>none</parallel>` in Surefire, mirroring the existing `junit5-spring-boot-3-test/pom.xml` (line 144) and Boot's own R2DBC test slices.
- The starter Javadoc and README **document the constraint loudly**: *if you run reactive QuickPerf tests in parallel inside the same JVM, recorders will see queries from sibling tests; configure Surefire `parallel=classes` with `forkCount=N` instead* (the same constraint applies to TestNG users — `testng/pom.xml` declares `<parallel>all</parallel>` — and to Gradle's `maxParallelForks`/`forkEvery` setup).
- Future option (b) can be added as opt-in via a system property without breaking (c).

For the `JdbcQueryExecutionVerifier` family this is acceptable because verifiers count *per-recorder*, and the recorder-per-test lifecycle is preserved by `PersistenceSqlRecorder.startRecording/stopRecording` — only the listener's thread-of-invocation differs from JDBC. (R3) However, a single test can still drive `afterQuery` callbacks **from multiple Reactor scheduler threads concurrently** (e.g., `Flux.flatMap(...).subscribe()`). The recorder pipeline (`PersistenceSqlRecorder` → `SqlMemoryRepository` → `SqlExecutions` → `ArrayDeque`) is **not thread-safe**. The R2DBC listener therefore wraps each `addQueryExecution` call in `synchronized (recorder)`. This adds zero overhead to JDBC users (they don't go through the listener) and a single lock acquisition per query for R2DBC users — negligible vs the I/O cost of a database round trip. See §6.4 for the regression test.

**Interaction with the existing dual-mode `SqlRecorderRegistry` (R2):** `SqlRecorderRegistry` already runs in two modes — `InheritableThreadLocal` for in-JVM tests (`SqlRecorderRegistry.java:27-37`) and JVM-global `Map` when the system property `TEST_CODE_EXECUTING_IN_NEW_JVM=true` (used by `@HeapSize`, `@Xmx`, etc.). The new `SqlRecorderHook` is **always** JVM-global and runs in addition to (not instead of) the existing registry. JDBC dispatch continues to use `SqlRecorderRegistry`; R2DBC dispatch uses `SqlRecorderHook`. There is no double-dispatch because each listener subscribes to only one path.

### 4.2 `SqlExecution` model coupling

**Recommendation: synthesize datasource-proxy types in `R2dbcExecutionAdapter`.**

```text
ExecutionInfo synth = new ExecutionInfo();
synth.setDataSourceName("r2dbc:" + this.beanName);              // (R3.1) bean name plumbed from BPP, NOT connectionId
synth.setConnectionId(connectionInfo.getConnectionId());         // r2dbc-proxy per-connection UUID — preserved for diagnostics
synth.setStatementType(rdProxy.getQueries().stream().anyMatch(q -> q.getBindingsList().isEmpty())
                       ? StatementType.STATEMENT : StatementType.PREPARED);
synth.setBatch(rdProxy.getType() == ExecutionType.BATCH || rdProxy.getBindingsSize() > 1);
synth.setBatchSize(...);  // see §4.7
synth.setElapsedTime(rdProxy.getExecuteDuration().toMillis());
synth.setResult(null);                 // R2DBC has no ResultSet → SqlExecution short-circuits column count to 0
synth.setThrowable(rdProxy.getThrowable());

List<QueryInfo> qs = new ArrayList<>(rdProxy.getQueries().size());
for (io.r2dbc.proxy.core.QueryInfo rq : rdProxy.getQueries()) {
    QueryInfo dq = new QueryInfo();
    dq.setQuery(rewritePlaceholdersToQuestionMark(rq.getQuery()));   // §4.6
    dq.setParametersList(R2dbcBindingsAdapter.toParameterSetOperations(rq.getBindingsList()));
    qs.add(dq);
}
```

> **(R3.1) `dataSourceName` source.** `setDataSourceName("r2dbc:" + this.beanName)` uses the **Spring bean name** (e.g., `connectionFactory`), not `ConnectionInfo.getConnectionId()` (a per-connection UUID/counter from r2dbc-proxy). Reasons: (a) the bean name is stable across pooled connections, matching JDBC's `dataSourceName`-is-bean-name convention; (b) using `connectionId` would emit one tag per pooled connection and break the §6.3 coexistence assertion (which checks the report contains `r2dbc:<r2dbc-bean-name>`); (c) it preserves the §8 multi-tenancy story of "one tag per `ConnectionFactory` bean". The bean name is plumbed via `R2dbcQuickPerfListener`'s constructor — `QuickPerfR2dbcProxyBeanPostProcessor.postProcessAfterInitialization(bean, beanName)` passes `beanName` into `R2dbcQuickPerfListener.builder().beanName(beanName).build()`. For non-Spring users invoking `QuickPerfR2dbcConnectionFactoryBuilder` manually, `beanName` defaults to `"connectionFactory"` (or a builder-supplied override).

Justification:

- **Zero risk to existing verifiers.** They consume `QueryInfo.getQuery()`, `getParametersList()`, `ExecutionInfo.isBatch()`, `getElapsedTime()`, `getStatementType()` — all populated.
- **`SqlExecution.retrieveNumberOfReturnedColumns` short-circuits to 0** because `executionInfo.getResult()==null` matches the `dbExceptionHappened` branch (`SqlExecution.java:86-88`). Column-count verifiers are correctly inert (deferred per §2.1).
- **`Externalizable` cross-JVM transfer (R2 / R3):** `SqlExecution.writeExternal` serializes only `dataSourceName`, `connectionId`, `statementType`, `isBatch`, `batchSize`, the queries list, and the parameters list (`SqlExecution.java:117-123`); **timing, throwable, and `ResultSet` are not transferred**. For forked-JVM annotations (`@HeapSize`, `@Xmx`), R2DBC count-based verifiers (`@ExpectSelect`, `@ExpectInsert`, `@ExpectUpdate`, `@ExpectDelete`, `@ExpectJdbcQueryExecution`) keep working; `@ExpectMaxQueryExecutionTime` and exception-introspection verifiers will still be inert across JVM boundaries (they already are for JDBC). No regression — same constraint as today.

The trade-off (residual datasource-proxy coupling in the SQL core) is tracked as v2 work — Option D becomes attractive once a third query API (e.g., Vert.x SQL Client) is added.

### 4.3 Column count for `@ExpectMaxSelectedColumn` / `@ExpectSelectedColumn`

**Deferred** (per §2.1). Adapter sets `ExecutionInfo.getResult()==null` so `SqlExecution.retrieveNumberOfReturnedColumns` returns 0. A v2 implementation would tap `Result.map((row, md) -> ...)` via r2dbc-proxy's `ResultRowConverter`, store the column count in `ConnectionInfo.getValueStore()`, and override `SqlExecutions.add` for the R2DBC adapter to pass an explicit column count via the existing `new SqlExecution(executionInfo, queries, columnCount)` constructor.

**No runtime WARN in v1 (R2).** As established in §2.1 footer and §3.8, neither `R2dbcQuickPerfListener.beforeMethod` nor a freestanding `RecordablePerformance` can both observe the test's annotation list and reliably emit one warning per test. Behavior is documented in Javadoc and README. v2 may surface the warning from `PersistenceSqlRecorder.startRecording` (which has access to `TestExecutionContext.getPerfAnnotations()`) once the lifecycle hook (§3.8) is in place — see open question §9, Q-13.

### 4.4 Query type detection

`QueryTypeRetriever` calls `net.ttddyy.dsproxy.listener.QueryUtils.getQueryType(...)` which inspects the leading SQL keyword. Driver-agnostic; no change needed. Edge cases (PostgreSQL `INSERT … RETURNING`, CTEs `WITH … SELECT …`) behave identically to JDBC.

### 4.5 Statement vs prepared-statement disambiguation

R2DBC has only `Statement` (no JDBC-style `Statement` vs `PreparedStatement` split). Mapping rule for the synthetic `StatementType`:

- If **none** of the `Bindings` for the executed query is non-empty → synthesize `StatementType.STATEMENT` (treated as "unbound SQL statement" by `NoStatementVerifier`).
- Otherwise → synthesize `StatementType.PREPARED`.

This makes `@DisableStatements` flag exactly the cases where SQL is sent without bind markers (e.g., string concatenation), which is the developer-facing intent of the annotation.

### 4.6 Bind parameters mapping

r2dbc-proxy exposes `QueryInfo.getBindingsList() : List<Bindings>`. Each `Bindings` has:

- `getIndexBindings() : SortedSet<Binding>` — positional markers (`$1`, `?1`, …)
- `getNamedBindings() : SortedSet<Binding>` — named markers (`:foo`)
- `Binding.getBoundValue() : BoundValue` — discriminate via `BoundValue.isNull()` and `BoundValue.getValue()`. (R3.1 — earlier drafts named non-existent subtypes `NullValue` / `RegularValue`; r2dbc-proxy 1.1.4 ships only the `BoundValue` interface, see §4.6 (R3) note further down for the static factory methods.)

> **Note (R2):** `getNamedBindings()` returns a `SortedSet`, sorted alphabetically by name. A naive "iterate named bindings in iteration order and append after positional bindings" approach therefore reorders parameters relative to the SQL source — `WHERE name = :name AND active = :active` would emit `[active, name]` under `@DisplaySql`, which is wrong. The adapter must preserve **source order** of placeholders.

**Two complementary changes in the adapter:**

1. **Rewrite SQL placeholders to `?` while capturing source order.** The regex must distinguish a leading `:` (named binding) from a doubled `::` (PostgreSQL cast operator like `column::int`):

   ```java
   private static final Pattern POSITIONAL = Pattern.compile("\\$\\d+");
   // (?<!:)  → not preceded by ':'
   // [A-Za-z_][A-Za-z0-9_]*  → identifier chars
   private static final Pattern NAMED      = Pattern.compile("(?<!:):([A-Za-z_][A-Za-z0-9_]*)");

   /** Returns rewritten SQL with '?' placeholders and the ordered list of original placeholder keys. */
   static RewriteResult rewritePlaceholders(String sql) {
       List<String> orderedKeys = new ArrayList<>();
       StringBuffer out = new StringBuffer(sql.length());
       Matcher m = NAMED.matcher(sql);
       while (m.find()) {
           orderedKeys.add(m.group(1));    // named binding key (no leading ':')
           m.appendReplacement(out, "?");
       }
       m.appendTail(out);
       String afterNamed = out.toString();

       out = new StringBuffer(afterNamed.length());
       m = POSITIONAL.matcher(afterNamed);
       int positionalIndex = 0;
       while (m.find()) {
           orderedKeys.add(Integer.toString(positionalIndex++));
           m.appendReplacement(out, "?");
       }
       m.appendTail(out);
       return new RewriteResult(out.toString(), orderedKeys);
   }
   ```

   Unit tests must cover at minimum: `column::int`, `'literal :not_a_binding'`, `-- :comment_binding`, dollar-quoted strings (`$tag$ ... $tag$`). For v1 the rewrite is regex-based and conservative — string-literal and comment escaping are not implemented; if a user's SQL contains `'…:foo…'` literal text the placeholder rewrite will mis-fire. Tracked as v2 enhancement (§9 Q-14); for now, document the limitation in the starter Javadoc.

   > **R2.1 — mixed-type SQL limitation.** The two-pass rewrite above (NAMED first, POSITIONAL second) preserves source order **within one placeholder type** but does not preserve interleaved order across types. For `WHERE name=:name AND id=$1 AND active=:active`, source order is `[:name, $1, :active]` but `orderedKeys` ends up as `["name", "active", "0"]`. **Mixed positional + named placeholders in a single statement are not supported in v1** and are not produced by any real R2DBC driver in practice (PostgreSQL emits `$N` only, MySQL/H2 R2DBC emit `?`/`$N` only, Spring Data emits `:name` only). Document this limitation in the starter Javadoc and lock the chosen behavior with one explicit unit test (`mixed_positional_and_named_throws_or_documents_loss_of_order`). v2 may upgrade to a single-pass merged regex `(?<!:):[A-Za-z_]\w*|\$\d+` if a real driver requires it.

2. **Map `Bindings → ParameterSetOperation` in source order** (mainly cosmetic, for `@DisplaySql`):

   ```java
   List<ParameterSetOperation> ops = new ArrayList<>(orderedKeys.size());
   int displayIndex = 1;
   for (String key : orderedKeys) {
       BoundValue bv = isNumeric(key)
           ? bindings.getIndexBindings().stream()
               .filter(b -> b.getKey().equals(Integer.parseInt(key)))
               .findFirst().map(Binding::getBoundValue).orElse(BoundValue.nullValue(Object.class))
           : bindings.getNamedBindings().stream()
               .filter(b -> b.getKey().equals(key))
               .findFirst().map(Binding::getBoundValue).orElse(BoundValue.nullValue(Object.class));
       Object value = bv.isNull() ? null : bv.getValue();
       ops.add(new ParameterSetOperation(SET_OBJECT_METHOD, new Object[]{ displayIndex++, unwrap(value) }));
   }
   ```

   > **(R3) r2dbc-proxy 1.1.4 binding API.** Earlier drafts used `NullValue.INSTANCE` and `RegularValue` cast/dispatch — **those types do not exist** in `io.r2dbc:r2dbc-proxy:1.1.4.RELEASE` (verified by `javap` against the published JAR — `io/r2dbc/proxy/core/` contains only `BoundValue.class` and `BoundValue$DefaultBoundValue.class`). The actual API is the `BoundValue` interface with two factories (`BoundValue.value(Object)` and `BoundValue.nullValue(Class<?>)`) and two readers (`isNull()` and `getValue()`). Code above uses the documented discriminator; a missing binding falls back to `BoundValue.nullValue(Object.class)`.

   This preserves source ordering for `@DisplaySql` and keeps `AllParametersAreBoundExtractor` (`bindparams/AllParametersAreBoundExtractor.java:118-120` — it scans for `?`) honest across PostgreSQL (`$N`), H2 R2DBC (`$N`), and named (`:name`) markers without modifying the extractor. MySQL R2DBC uses `?` natively and is unaffected.

`Method` references are cached via reflection. Existing verifiers do not read `Method`/`args`, but `@DisplaySql` does — building a faithful method mapping keeps the displayed parameter list readable.

### 4.7 `isBatch` / `batchSize` mapping

r2dbc-proxy `ExecutionType`:

- `STATEMENT` → `Statement#execute()`. May still have N bindings (`bindingsSize >= 1`, R2DBC's "PreparedStatement.add().add().execute()" pattern).
- `BATCH` → `Batch#execute()`. Multiple distinct SQL statements.

Mapping to `ExecutionInfo`:

| Source | `setBatch(...)` | `setBatchSize(...)` |
|---|---|---|
| `ExecutionType.BATCH` | `true` | `info.getBatchSize()` |
| `ExecutionType.STATEMENT` AND `info.getBindingsSize() > 1` | `true` | `info.getBindingsSize()` |
| else | `false` | `0` |

This preserves count semantics. A faithful R2DBC-batch verifier (`@ExpectR2dbcBatch`) is v2.

### 4.8 Connection leak detection

**Deferred** (per §2.1). Document in the new starter's Javadoc that `@ExpectNoConnectionLeak` and `@ProfileConnection` have no effect on R2DBC connections; the verifier passes trivially.

A v2 sketch: proxy `ConnectionFactory.create()` to assign a connection id; mark `OPEN` on emission, `CLOSE_SUBSCRIBED` on `Connection.close()` subscription, `CLOSED` on completion. Fail at `stopRecording` if any connection acquired during the test isn't `CLOSED`. For pooled connections, treat pool return as closed.

### 4.9 Connection pooling order (`r2dbc-pool`)

Bean topology with Boot 3:

```
ConnectionFactory (driver) ─wrapped by─► ConnectionPool ─exposed as─► @Bean ConnectionFactory
```

**The QuickPerf BPP wraps the bean exposed in the context** (the pool), not the driver. Justification:

- r2dbc-proxy is designed to wrap the user-visible `ConnectionFactory`.
- Wrapping the underlying driver factory before the pool would cause pool-internal queries (validation, eviction) to surface in user recordings.
- Captures user-visible behavior across the application's logical query interface.

Document this in the starter README so users understand which calls QuickPerf observes (only those issued through the bean; pool internals stay hidden). **Pool-acquisition timing is *not* included in `QueryExecutionInfo.getExecuteDuration()`** — that field measures `Statement#execute()` only. If pool-acquisition observability is desired, v2 can add `beforeMethod`/`afterMethod` capture for `ConnectionFactory.create()`.

### 4.10 Spring Boot ordering & double-proxy avoidance

- `@AutoConfiguration(after = R2dbcAutoConfiguration.class)` ensures the `ConnectionFactory`/`ConnectionPool` bean exists before our BPP is registered.
- `QuickPerfR2dbcProxyBeanPostProcessor implements BeanPostProcessor, PriorityOrdered`, `getOrder() == LOWEST_PRECEDENCE - 1`, mirroring `QuickPerfProxyBeanPostProcessor`. This places it among the last `BeanPostProcessor`s to inspect each bean, after most `Ordered` BPPs that may also decorate the `ConnectionFactory` (e.g. user-defined Micrometer observability decorators). It does **not** guarantee "wrap last in absolute terms" — another `PriorityOrdered` BPP at `LOWEST_PRECEDENCE` would still run after us. We accept this: a downstream decorator that wraps QuickPerf's proxy is fine because r2dbc-proxy's listener still fires on the inner `Statement.execute()` call.
- Avoid double-wrapping by checking for a marker interface (`QuickPerfR2dbcProxyMarker`) added to the AOP proxy; skip beans already wearing it. (The existing JDBC `QuickPerfProxyBeanPostProcessor` does not currently use such a marker — only a `ScopedProxyUtils.isScopedTarget` skip — so the R2DBC marker is independent and adds no risk to JDBC.)
- Skip `ScopedProxyUtils.isScopedTarget(beanName)` (mirrors JDBC).
- `setProxyTargetClass(true)` is unnecessary for `ConnectionFactory` (it's an interface) and adds a CGLIB dependency. Use JDK proxies. (R3) **Limitation:** because `ProxyConnectionFactory.Builder.build()` returns `io.r2dbc.spi.ConnectionFactory` (the interface), the BPP-wrapped bean is a JDK proxy implementing `ConnectionFactory` and `QuickPerfR2dbcProxyMarker` — **not** the original concrete type. Users who write `@Autowired io.r2dbc.pool.ConnectionPool pool` (or any concrete `ConnectionFactory` subclass) will see Spring fail to wire when the QuickPerf starter is on the classpath. **v1 supports only `ConnectionFactory`-typed injection**; `ConnectionPool`-specific APIs (e.g., `pool.getMetrics()`) are unreachable. Document this in the starter README and add a smoke test that asserts the contract. If preserving concrete `ConnectionPool` injection is required by future users, v2 can switch to a CGLIB target-class proxy around the original bean (as JDBC does).

### 4.11 Surefire parallel + reactive lifecycle

- New R2DBC test module sets `<parallel>none</parallel>` in Surefire (matches existing `junit5-spring-boot-3-test`).
- Non-Spring reactive **unit tests** (mock `QueryExecutionInfo`) under root config (`pom.xml:109-110` declares `parallel=all, threadCount=5`) need care. (R3) **Resolution:** `R2dbcQuickPerfListener`'s constructor accepts a `Supplier<Iterable<SqlRecorder<?>>>` parameter (defaults to `SqlRecorderHook::getActiveRecorders`). Production code uses the default (the JVM-global hook); unit tests inject an isolated `Set<SqlRecorder<?>>` per-instance. Tests therefore never touch the static hook, can run safely under `parallel=all`, and the contradiction with §6.1 / §4.11 in earlier drafts is resolved without setting `parallel=none` for the whole `sql-annotations-r2dbc` module. `SqlRecorderHookTest` (in `sql-annotations`, since the hook lives there) still runs in `parallel=none` mode because it exercises the static set directly.
- **Do not** introduce a hidden `block()` in the recorder. If a user's reactive chain isn't subscribed-to-completion before test end, that's a test bug. We document it.
- `SqlRecorderHook.getActiveRecorders()` returns an empty collection after `stopRecording`, so any straggling `afterQuery` is a no-op. Add a one-shot DEBUG log: *"afterQuery received for query 'X' but no active recorders — late subscription? Use `StepVerifier.verifyComplete()`/`block()` to ensure events fire before assertion."*

---

## 5. Module / file layout

All paths are absolute under `C:\code\fork6\quickperf\`.

### 5.1 NEW Maven module: `sql/sql-annotations-r2dbc/`

**`sql\sql-annotations-r2dbc\pom.xml`** (new)
- Parent: `quick-perf-sql-parent` (`sql/pom.xml`).
- `<artifactId>quick-perf-sql-annotations-r2dbc</artifactId>`.
- Properties: `maven.compiler.source/target=1.8`, `dependencies.max.jdk.version=1.8`.
- Dependencies as in §3.5.

**`sql\pom.xml`** (edit) — add `<module>sql-annotations-r2dbc</module>` after `sql-annotations`.

**Java sources** under `sql\sql-annotations-r2dbc\src\main\java\org\quickperf\sql\r2dbc\`:

| Class | Responsibility |
|---|---|
| `R2dbcQuickPerfListener` (package-private) | Implements `io.r2dbc.proxy.listener.ProxyExecutionListener`. In `afterQuery(QueryExecutionInfo)` calls `R2dbcExecutionAdapter` and forwards to recorders from `SqlRecorderHook.getActiveRecorders()` (the hook lives in `sql-annotations`, see §3.8). No `beforeMethod` warning logic in v1 (R2). |
| `R2dbcExecutionAdapter` | Static helper. `toDsProxyExecutionInfo(QueryExecutionInfo)` and `toDsProxyQueries(List<io.r2dbc.proxy.core.QueryInfo>)`. Performs SQL placeholder rewriting + source-order capture (§4.6). |
| `R2dbcBindingsAdapter` | Converts r2dbc-proxy `Bindings` → `List<List<ParameterSetOperation>>` in source order (§4.6). |
| `config.QuickPerfR2dbcConnectionFactoryBuilder` | `aBuilder().buildProxy(ConnectionFactory)` — mirror of `QuickPerfSqlDataSourceBuilder`. Returns `ProxyConnectionFactory.builder(cf).listener(new R2dbcQuickPerfListener()).build()`. |

> **Removed (R2):** the original §5.1 listed `R2dbcSqlRecorderRegistry`, `R2dbcQuickPerfRecorderBridge`, and `config.library.R2dbcConfigLoader`. These are gone:
> - `R2dbcSqlRecorderRegistry` → replaced by `SqlRecorderHook` in `sql-annotations` (§3.8).
> - `R2dbcQuickPerfRecorderBridge` (`RecordablePerformance<NoMeasure>`) → `NoMeasure` doesn't exist as a type; the bridge would never have been instantiated by `TestExecutionContext`. Replaced by the 2-line edit to `PersistenceSqlRecorder` (§3.8 + §5.4).
> - `config.library.R2dbcConfigLoader` → no SPI loader is needed; the existing `SqlConfigLoader` already wires `PersistenceSqlRecorder` for every SQL annotation.

**No new SPI registration in `sql-annotations-r2dbc`.** The original `META-INF/services/org.quickperf.config.library.QuickPerfConfigLoader` resource is **not** added.

**Unit tests** under `sql\sql-annotations-r2dbc\src\test\java\org\quickperf\sql\r2dbc\`:

- `R2dbcExecutionAdapterTest` — uses `io.r2dbc.proxy.test.MockQueryExecutionInfo`. Covers `column::int` cast preservation, `'literal :not_a_binding'` (asserts the documented best-effort/mis-fire behavior — see §4.6 limitation note, not a preservation test), positional `$1`/`$2` rewrite, named `:foo`/`:bar` rewrite + source-order capture. (R3) Add an explicit case `mixed_positional_and_named_throws_or_documents_loss_of_order` that locks the v1 unsupported-mixed-placeholder behavior. Add a PostgreSQL `$N`-binding case `$1` / `$2` that asserts the chosen `Binding.getKey()` indexing (driver-dependent — locks the contract for r2dbc-postgresql).
- `R2dbcBindingsAdapterTest` — positional / named / null / mixed bindings; verifies source-order preservation against `getNamedBindings()` (`SortedSet`) reorder. (R3) `null` case uses `BoundValue.nullValue(Object.class)` (not the non-existent `NullValue.INSTANCE`) and asserts `bv.isNull() == true`.
- `R2dbcQuickPerfListenerTest` — (R3.1) instantiates `R2dbcQuickPerfListener` with a constructor-injected `Supplier<Iterable<SqlRecorder<?>>>` (in tests, `() -> mySet` over a private mutable `Set<SqlRecorder<?>>` populated with a Mockito recorder; production default is `SqlRecorderHook::getActiveRecorders` per §4.11). Simulated `afterQuery` triggers `addQueryExecution`; tests do **not** touch the static `SqlRecorderHook`, so they run safely under root `parallel=all, threadCount=5`.
- `R2dbcRegistryParallelExecutionTest` — JUnit 5 `@Execution(CONCURRENT)`, 5 threads each construct a private listener+recorder pair, fire 1000 simulated queries each, assert recorder isolation by SQL tag. (R3) Uses constructor injection per §4.11; never touches the static hook.
- `SqlRecorderHookTest` (in `sql-annotations`, since the hook lives there) — concurrent register/unregister from 8 threads via `CountDownLatch`; no `ConcurrentModificationException`. Run with `parallel=none` annotation override (this test exercises the static set directly).
- (R3) `R2dbcConcurrentDispatchToOneRecorderTest` — register one **real** `PersistenceSqlRecorder` via `SqlRecorderHook`, fire `R2dbcQuickPerfListener.afterQuery` concurrently from N threads against the same recorder, assert exactly N executions are recorded and no `ArrayDeque` corruption / `ConcurrentModificationException` occurs. **This test must exercise the production `synchronized (recorder)` path**, not the constructor-injected bypass; otherwise the §3.8 thread-safety claim is unverified.

### 5.2 NEW Maven module: `spring/spring-boot-r2dbc-sql-starter/`

**`spring\spring-boot-r2dbc-sql-starter\pom.xml`** (new)
- Parent: `quick-perf-spring`.
- `<artifactId>quick-perf-springboot-r2dbc-sql-starter</artifactId>`.
- Properties: `maven.compiler.source/target=17`, `dependencies.max.jdk.version=17` (R2 — `spring-boot-autoconfigure` 3.2.5 is Java 17 bytecode; setting `1.8` would fail enforcer).
- Dependencies:
  - `org.quickperf:quick-perf-sql-annotations-r2dbc:${project.version}` (compile)
  - `org.springframework.boot:spring-boot-autoconfigure` (provided)
  - `org.springframework:spring-context` (provided)
  - `org.springframework:spring-aop` (provided)
  - `io.r2dbc:r2dbc-spi:1.0.0.RELEASE` (provided)

**`spring\pom.xml`** (edit) — add `<module>spring-boot-r2dbc-sql-starter</module>` **inside `<profile id="SpringBoot3Tests">`** (or any JDK-17-gated profile), **not** in the always-on `<modules>` block. JDK-8 developers building the repo continue to skip Boot-3 modules; the existing `junit5-spring-boot-3-test` module follows the same gating.

**Java sources** under `spring\spring-boot-r2dbc-sql-starter\src\main\java\org\quickperf\spring\boot\r2dbc\`:

| Class | Responsibility |
|---|---|
| `QuickPerfR2dbcProxyBeanAutoConfiguration` | `@AutoConfiguration(after = R2dbcAutoConfiguration.class)`, `@ConditionalOnClass({ ConnectionFactory.class, ProxyConnectionFactory.class })`, `@ConditionalOnBean(ConnectionFactory.class)`, `@Import(QuickPerfR2dbcConfig.class)`. |
| `QuickPerfR2dbcConfig` | `@Configuration(proxyBeanMethods = false)`. (R3) Declares `@Bean public static QuickPerfR2dbcProxyBeanPostProcessor quickPerfR2dbcProxyBeanPostProcessor()` — the **`static`** factory method registers the `BeanPostProcessor` without forcing eager instantiation of the configuration class itself, avoiding Spring's `BeanPostProcessorChecker` warning and the early-instantiation gotcha. |
| `QuickPerfR2dbcProxyBeanPostProcessor` | `BeanPostProcessor` + `PriorityOrdered`. In `postProcessAfterInitialization`, if `bean instanceof ConnectionFactory` and not a scoped target and not already QuickPerf-proxied, wraps via `QuickPerfR2dbcConnectionFactoryBuilder` + Spring AOP `ProxyFactory` (mirror of JDBC variant). |
| `ProxyConnectionFactoryInterceptor` | `MethodInterceptor` delegating `ConnectionFactory` methods to the r2dbc-proxy-decorated factory. |
| `QuickPerfR2dbcProxyMarker` | Marker interface attached to AOP proxy to prevent double-wrapping. |

**Resource files:**

- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
  ```
  org.quickperf.spring.boot.r2dbc.QuickPerfR2dbcProxyBeanAutoConfiguration
  ```
- `META-INF/spring/org.springframework.boot.test.autoconfigure.data.r2dbc.AutoConfigureDataR2dbc.imports` — same content; activates the autoconfig in `@DataR2dbcTest` slices.

> **Removed (R2):** `META-INF/spring.factories`. Spring Boot 3.0+ uses only `META-INF/spring/...AutoConfiguration.imports`; `spring.factories` would be inert and could mislead readers into thinking Boot 2 is supported by this starter.

### 5.3 NEW test module: `spring/junit5-spring-boot-3-r2dbc-test/`

Mirror of `junit5-spring-boot-3-test` with:
- `<parallel>none</parallel>` Surefire override.
- `<dependencies.max.jdk.version>17</dependencies.max.jdk.version>`.
- `<maven.install.skip>true</maven.install.skip>`, `<maven.deploy.skip>true</maven.deploy.skip>`, `<gpg.skip>true</gpg.skip>`, `<skipNexusStagingDeployMojo>true</skipNexusStagingDeployMojo>` (test-only).
- Activated in `spring/pom.xml` `<profile id="SpringBoot3Tests">` (already JDK ≥ 17).
- Dependencies:
  - `quick-perf-junit5`, `quick-perf-junit5-test-util` (test classifier), `quick-perf-springboot-r2dbc-sql-starter`.
  - `org.springframework.boot:spring-boot-starter-data-r2dbc`.
  - `io.r2dbc:r2dbc-h2` (test) — pulls `com.h2database:h2:2.x` as a transitive `compile` dep, so a separate explicit `com.h2database:h2` declaration is unnecessary unless we want to pin a different H2 version. (R3 — earlier draft listed h2 as required-runtime; it is already transitive.)
  - `io.projectreactor:reactor-test` (test).
  - `org.springframework.boot:spring-boot-starter-test`.
  - For the JDBC+R2DBC coexistence test: `org.springframework.boot:spring-boot-starter-jdbc` (test).

Test classes under `spring\junit5-spring-boot-3-r2dbc-test\src\test\java\org\quickperf\spring\springboottest\r2dbc\`:

- `SpringBootR2dbcExpectSelectJunit5Test` — outer test runs an inner `@DataR2dbcTest`-annotated class via `JUnit5Tests.createInstance(InnerClass.class).run()`, asserts on `getNumberOfFailures()` and `getErrorReport()` (mirror of `SpringBootExpectSelectJunit5Test`).
- `SpringBootR2dbcExpectMaxSelectJunit5Test`, `…ExpectInsertJunit5Test`, `…ExpectUpdateJunit5Test`, `…ExpectDeleteJunit5Test`.
- `SpringBootR2dbcExpectJdbcQueryExecutionJunit5Test`, `…ExpectMaxJdbcQueryExecutionJunit5Test`.
- `SpringBootR2dbcExpectMaxQueryExecutionTimeJunit5Test`.
- `SpringBootR2dbcDisplaySqlJunit5Test`.
- `SpringBootR2dbcDisableSameSelectsJunit5Test`.
- `SpringBootR2dbcDisableLikeWithLeadingWildcardJunit5Test`.
- `SpringBootR2dbcDisableQueriesWithoutBindParametersJunit5Test`.
- (R3) `SpringBootR2dbcFlywayStartupNoiseJunit5Test` (`@DataR2dbcTest`) — verifies Flyway DDL during context startup is **not** counted by the recorder (recording starts at test-method scope).
- (R3) `SpringBootR2dbcJdbcCoexistenceJunit5Test` (`@SpringBootTest`, with `spring-boot-starter-jdbc` + `spring-boot-starter-data-r2dbc` both on the classpath) — issues one `JdbcTemplate.queryForObject(...)` AND one R2DBC `DatabaseClient`/repository query inside the test method, asserts the unified count and that the report contains both `dataSourceName` forms (`<jdbc-bean-name>` and `r2dbc:<r2dbc-bean-name>`). This is the test that proves acceptance criterion §1.3 #3 (unified `SqlExecutions`) — `@DataR2dbcTest` cannot exercise it because that slice does not import `DataSourceAutoConfiguration` (verified in `META-INF/spring/.../AutoConfigureDataR2dbc.imports`).
- `SpringBootR2dbcLateReactiveSqlWarningJunit5Test` — intentionally non-subscribing reactive chain; verify late-event DEBUG/WARN.
- (R3) `SpringBootR2dbcMissingStarterSuggestionTest` — exercise the §3.6 truth-table cell where `containsR2dbcSpi() && !contains(QUICKPERF_SPRING_BOOT_R2DBC_SQL_STARTER)`; assert the failure report contains the R2DBC starter suggestion (not the JDBC one).
- Inner test classes under `…\r2dbc\inner\`.

Resources under `src/test/resources/`:
- `application.properties` — `spring.r2dbc.url=r2dbc:h2:mem:///quickperf_test`, `spring.flyway.url=jdbc:h2:mem:quickperf_test;DB_CLOSE_DELAY=-1` (coexistence variant).
- `schema.sql`, `data.sql`.

### 5.4 Edits to existing files

| File | Edit |
|---|---|
| `sql\sql-annotations\src\main\java\org\quickperf\sql\SqlRecorderHook.java` | **NEW file** (R2). Tiny utility: JVM-global `CopyOnWriteArraySet<SqlRecorder<?>>` with static `register`/`unregister`/`getActiveRecorders`. Lives in `sql-annotations` (no R2DBC dep) so `PersistenceSqlRecorder` can call it without a circular dependency. See §3.8 for full source. (R3 — `Set<SqlRecorder<?>>` not raw `Set<SqlRecorder>`; avoids unchecked warnings since `SqlRecorder` declares `<R extends PerfRecord>`.) |
| `sql\sql-annotations\src\main\java\org\quickperf\sql\PersistenceSqlRecorder.java` | **2-line edit** (R2). Add `SqlRecorderHook.register(this);` as first line of `startRecording(...)` and `SqlRecorderHook.unregister(this);` as first line of `stopRecording(...)`. No other change. JDBC-only users see no behavior change because no R2DBC listener subscribes to the hook. |
| `sql\sql-annotations\src\main\java\org\quickperf\sql\framework\ClassPath.java` | Add `containsR2dbcSpi()` (looks for `r2dbc-spi`), `containsR2dbcProxy()` (looks for `r2dbc-proxy`), `containsSpringDataR2dbc()` (looks for `spring-data-r2dbc`). |
| `sql\sql-annotations\src\main\java\org\quickperf\sql\framework\quickperf\QuickPerfDependency.java` | Add enum constant `QUICKPERF_SPRING_BOOT_R2DBC_SQL_STARTER("quick-perf-springboot-r2dbc-sql-starter")`. |
| `sql\sql-annotations\src\main\java\org\quickperf\sql\framework\R2DBCSuggestion.java` | New file. Sibling to `JdbcSuggestion`, `HibernateSuggestion`. Two enum members: `SERVER_ROUND_TRIPS` (R2DBC roundtrip guidance), `N_PLUS_ONE` (Spring Data R2DBC `@Query` JOINs / `R2dbcEntityTemplate` projections). |
| `sql\sql-annotations\src\main\java\org\quickperf\sql\framework\quickperf\SpringDataSourceConfig.java` | (R2) **Starter-detection suggestion path.** When `classPath.containsSpringBoot3()` AND `classPath.containsR2dbcSpi()` AND NOT `classPath.contains(QUICKPERF_SPRING_BOOT_R2DBC_SQL_STARTER)`, `getMessage()` (existing method, lines 30-77) returns the new R2DBC starter dependency suggestion. Add `private String buildR2dbcStarterMessage()`. **Insertion point (R2.1):** insert a new R2DBC-aware branch *before* the existing combined `if (classPath.containsSpringBoot2() \|\| classPath.containsSpringBoot3())` block (lines 41-47). The actual branch order in `getMessage()` is SB1 → SB2-or-SB3 → Spring4 → Spring5; gate the new branch on `containsSpringBoot3() && containsR2dbcSpi() && !contains(QUICKPERF_SPRING_BOOT_R2DBC_SQL_STARTER)` so it short-circuits before the JDBC SB3 path. |
| `sql\sql-annotations\src\main\java\org\quickperf\sql\select\analysis\SelectAnalysis.java` | (R2 / R3) **Round-trip suggestion text.** `SelectAnalysis.getNPlusOneSelectAlert()` (lines 52-63) is currently a single-expression `return` returning `lineSeparator() + lineSeparator() + JdbcSuggestion.SERVER_ROUND_TRIPS.getMessage() + getNPlusOneFrameworkMessage()`. A conditional R2DBC append cannot be done as a "+1 line" change — refactor to a `StringBuilder`: <br>**Concrete diff:**<br><pre>public static String getNPlusOneSelectAlert() {<br>    if (SystemProperties.SIMPLIFIED_SQL_DISPLAY.evaluate()) return "";<br>    StringBuilder sb = new StringBuilder()<br>        .append(lineSeparator()).append(lineSeparator())<br>        .append(JdbcSuggestion.SERVER_ROUND_TRIPS.getMessage());<br>    if (ClassPath.INSTANCE.containsR2dbcSpi()) {<br>        sb.append(lineSeparator())<br>          .append(R2DBCSuggestion.SERVER_ROUND_TRIPS.getMessage());<br>    }<br>    return sb.append(getNPlusOneFrameworkMessage()).toString();<br>}</pre> JDBC-only users see no diff (the `if` is false). |
| `sql\sql-annotations\src\main\java\org\quickperf\sql\analyze\SqlReport.java` | (R2 / R3) **Independent emission site** from `SelectAnalysis` (different method: `SqlReport.buildNPlusOneMessage()` lines 80-93). The `JdbcSuggestion.SERVER_ROUND_TRIPS.getMessage()` at line 87 sits between `+ System.lineSeparator()` segments, so a naive append yields "JdbcMsgR2dbcMsg" without separator. Refactor to a `StringBuilder` and place the R2DBC suggestion **immediately after** the JDBC one with one `lineSeparator()` between them: <br>**Concrete diff:**<br><pre>StringBuilder sb = new StringBuilder()<br>    .append(addSeparationString()).append(ALERT_MESSAGE)<br>    .append(SelectAnalysis.getNPlusOneFrameworkMessage())<br>    .append(System.lineSeparator()).append(System.lineSeparator())<br>    .append(JdbcSuggestion.SERVER_ROUND_TRIPS.getMessage());<br>if (ClassPath.INSTANCE.containsR2dbcSpi()) {<br>    sb.append(System.lineSeparator())<br>      .append(R2DBCSuggestion.SERVER_ROUND_TRIPS.getMessage());<br>}<br>return sb.append(System.lineSeparator()).append(System.lineSeparator()).toString();</pre> Both edits gated on `ClassPath.INSTANCE.containsR2dbcSpi()`; JDBC-only users see no diff. **Both must be patched** for the R2DBC suggestion to appear consistently — the report pipeline reaches each via different verifier paths. |
| `bom\pom.xml` | Add `<dependency>` entries for `quick-perf-sql-annotations-r2dbc` and `quick-perf-springboot-r2dbc-sql-starter`. |
| `spring\pom.xml` | Add `<module>spring-boot-r2dbc-sql-starter</module>` **inside `<profile id="SpringBoot3Tests">`** (R2 — was "always-on" in earlier draft); add `<module>junit5-spring-boot-3-r2dbc-test</module>` to the same profile. |
| `licenses\licenses.xml` | (R2.1 / R3) Add aliases inside the existing `<names>` and `<urls>` containers of the existing **`Apache Software License, Version 2.0`** entry (lines 33-43): two `<name>` entries (`Apache License 2.0`, `Apache License, Version 2.0`) and two `<url>` entries (`https://www.apache.org/licenses/LICENSE-2.0`, `https://www.apache.org/licenses/LICENSE-2.0.txt`). **Both `r2dbc-pool` AND `r2dbc-spi` POMs** declare the license under `<name>Apache License 2.0</name>` over `https://www.apache.org/licenses/LICENSE-2.0.txt` — neither alias currently resolves. See §6.7 for the concrete diff. |
| `CLAUDE.md` | See §7.1. |


> **Removed (R2):** the original `SqlExecutions.java` `format(...)` edit row. `SqlExecutions.format` (lines 159-174) does not currently emit any `JdbcSuggestion` text — round-trip suggestions live in `SelectAnalysis.java:60` and `SqlReport.java:87`, and starter-detection messages live in `SpringDataSourceConfig.getMessage()`. Patching `format` would have been silently dead code.

> **Path correction (R2):** the `AllParametersAreBoundExtractor` referenced in §4.6 lives at `sql\sql-annotations\src\main\java\org\quickperf\sql\bindparams\AllParametersAreBoundExtractor.java` (line 119 has the `parameter.contains("?")` check), not under `select\analysis\` as earlier text might imply.

**Existing files NOT edited:** `SqlRecorder.java`, `SqlExecution.java`, `SqlRecorderRegistry.java`, `ConnectionListenerRegistry.java`, any of the verifier classes, `SqlConfigLoader.java`, `DataSourceFrameworkConfigFactory.java`, `SqlExecutions.java`. The 2-line `PersistenceSqlRecorder` edit + 1-file `SqlRecorderHook` addition are the only changes inside `sql-annotations`'s recorder pipeline (R2).

---

## 6. Test strategy

### 6.1 Unit tests in `sql-annotations-r2dbc`

- `R2dbcExecutionAdapterTest`:
  - SELECT with named bindings → synthesized `QueryInfo.getQuery()` has `?` placeholders, `getParametersList()` size matches `Bindings.size()`.
  - INSERT with `Statement.bind().add().bind().add().execute()` (3 bindings) → `executionInfo.isBatch() == true`, `batchSize == 3`.
  - `Batch.execute()` with 4 statements → `executionInfo.isBatch() == true`, `batchSize == 4`, `queries.size() == 4`.
  - Query failure (`getThrowable() != null`) → `executionInfo.getResult() == null`, no NPE.
  - DROP statement → `QueryTypeRetriever` correctly reports `OTHER`.
  - PostgreSQL `INSERT … RETURNING` counted as `INSERT` (not `SELECT`).
  - Placeholder rewriting: `$1`, `$2`, `:foo`, `:bar` all → `?`.
  - `column::int` cast operator preserved (negative-lookbehind regex from §4.6).
  - Source-order preservation: `WHERE name=:name AND active=:active` → bindings appear in `[name, active]` order despite `SortedSet` returning `[active, name]`.
  - (R3) `mixed_positional_and_named_throws_or_documents_loss_of_order` — locks the v1 unsupported-mixed-placeholder behavior with an explicit assertion (see §4.6 limitation note).
  - (R3) `'literal :not_a_binding'` and `-- :comment` cases assert the documented best-effort/mis-fire behavior, **not** preservation. v2 (§9 Q-14) tracks an upgrade to a small lexer.
  - (R3) `R2dbcQueryParamsExtractorTest` (new sub-suite or class): PostgreSQL `$1` → `Binding.getKey()` returns 0 (or 1 — driver-dependent). One test per supported R2DBC driver locks the contract; if r2dbc-postgresql 1.0.x and r2dbc-h2 1.0.x disagree, document the disagreement and pin the adapter behavior to one of them.
- `R2dbcBindingsAdapterTest`:
  - Positional `0 → 1`, multiple positional preserve order.
  - Named bindings preserve **source** order (not alphabetical).
  - (R3) Mixed positional + named placeholders are documented as **unsupported in v1** (asserts the chosen behavior — either explicit exception or documented loss-of-order — not "preserve all values").
  - `bindNull` path: builds `BoundValue.nullValue(Object.class)`, asserts `bv.isNull() == true`, and produces a null marker usable by existing verifiers. (R3 — earlier draft referenced the non-existent `NullValue.INSTANCE` type.)
  - Multiple `Bindings` → multiple parameter sets.
- `R2dbcQuickPerfListenerTest`:
  - (R3.1) Constructor-injected `Supplier<Iterable<SqlRecorder<?>>>` returning a private mutable `Set<SqlRecorder<?>>` containing a `Mockito.mock(SqlRecorder.class)`; simulate `afterQuery(MockQueryExecutionInfo)` → `recorder.addQueryExecution(...)` called once with synthesized `ExecutionInfo`. Tests do **not** touch `SqlRecorderHook`'s static set, so they run safely under the root `parallel=all, threadCount=5` config.
  - With no recorder in the injected supplier, `afterQuery` is a no-op (no NPE; one DEBUG log).
- `R2dbcRegistryParallelExecutionTest` (R2 — see §4.11): JUnit 5 `@Execution(CONCURRENT)`, 5 threads, each constructs an isolated listener+recorder pair via constructor injection of `Supplier<Iterable<SqlRecorder<?>>>` (not via `SqlRecorderHook` static set), fires tagged queries, asserts recorder isolation by SQL tag.
- `SqlRecorderHookTest` (R2 — lives in `sql-annotations` module since the hook does too): single-threaded test verifying register/unregister/getActiveRecorders semantics and that double-register / double-unregister are idempotent.
- (R3) `R2dbcConcurrentDispatchToOneRecorderTest` (in `sql-annotations-r2dbc`): register one **real** `PersistenceSqlRecorder` via `SqlRecorderHook` (override `parallel=none` for this test class only), instantiate `R2dbcQuickPerfListener` with the default `Supplier` (i.e., the static hook), fire `afterQuery` concurrently from N threads with N distinct tagged queries, assert exactly N executions are recorded and no `ConcurrentModificationException` / `ArrayDeque` corruption occurs. **This test exercises the production `synchronized (recorder)` dispatch path** and must fail if §3.8's synchronization is removed.

### 6.2 Integration tests in `junit5-spring-boot-3-r2dbc-test`

Mirror the JDBC tests one-for-one (subset matching §2.1):

| New test class | Mirrors |
|---|---|
| `SpringBootR2dbcExpectSelectJunit5Test` | `SpringBootExpectSelectJunit5Test` |
| `SpringBootR2dbcExpectMaxSelectJunit5Test` | `SpringBootExpectMaxSelectJunit5Test` |
| `SpringBootR2dbcExpectJdbcQueryExecutionJunit5Test` | `SpringBootExpectJdbcQueryExecutionJunit5Test` |
| `SpringBootR2dbcExpectMaxJdbcQueryExecutionJunit5Test` | `SpringBootExpectMaxJdbcQueryExecutionJunit5Test` |

Each follows the existing pattern: an outer test runs an inner `@QuickPerfTest @DataR2dbcTest`-annotated class via `JUnit5Tests.createInstance(InnerClass.class).run()` and asserts on `getNumberOfFailures()` + `getErrorReport()` content.

### 6.3 Coexistence tests (R3 — split)

(R3) The R2.1 plan listed a single `SpringBootR2dbcCoexistenceJunit5Test` under `@DataR2dbcTest`. That slice's imports include `FlywayAutoConfiguration` and `R2dbcAutoConfiguration` but **not** `DataSourceAutoConfiguration` (verified at `META-INF/spring/.../AutoConfigureDataR2dbc.imports` — Flyway at line 4, R2DBC at line 6, no `DataSource`). A `@DataR2dbcTest` slice cannot exercise the JDBC half of the coexistence story, so the original test could not prove acceptance criterion §1.3 #3 (unified `SqlExecutions`). Split into two tests:

1. **`SpringBootR2dbcFlywayStartupNoiseJunit5Test`** (`@DataR2dbcTest` slice). The slice runs Flyway during context startup; assert `@ExpectSelect(1)` on a reactive `repository.findAll()` and that Flyway DDL is **not** counted (recording starts at test-method scope via `PersistenceSqlRecorder.startRecording`).

2. **`SpringBootR2dbcJdbcCoexistenceJunit5Test`** (`@SpringBootTest` — full context). Both `spring-boot-starter-jdbc` and `spring-boot-starter-data-r2dbc` on the classpath; both `DataSource` and `ConnectionFactory` beans wired and proxied. Inside the test method, run **one `JdbcTemplate.queryForObject(...)` AND one R2DBC `DatabaseClient`/repository query**; assert the unified count (`@ExpectSelect(2)`) and that the report contains both `dataSourceName` forms (`<jdbc-bean-name>` and `r2dbc:<r2dbc-bean-name>`). This is the test that proves the §1.3 #3 acceptance criterion.

Cover three reactive APIs across the two tests (one per API):
- Spring Data R2DBC `ReactiveCrudRepository`.
- `R2dbcEntityTemplate`.
- Plain `org.springframework.r2dbc.core.DatabaseClient`.

### 6.4 Thread-safety / parallel verification

`R2dbcRegistryParallelExecutionTest` (in `sql-annotations-r2dbc`, see §6.1) covers JVM-global registry isolation via constructor injection. The integration test module runs `parallel=none` per §4.11.

(R3) The dispatch hot path under reactive concurrency is exercised by `R2dbcConcurrentDispatchToOneRecorderTest` (see §6.1 entry): one real `PersistenceSqlRecorder` registered in `SqlRecorderHook`, N concurrent `afterQuery` calls into the same recorder via `R2dbcQuickPerfListener` with the production `synchronized (recorder)` dispatch (§3.8). The test asserts no `ConcurrentModificationException` from `SqlExecutions.sqlExecutions` (`ArrayDeque` — not thread-safe) and that exactly N executions are recorded. Removing `synchronized (recorder)` from the listener must cause this test to fail (regression gate).

### 6.5 Build profile activation

- Reuse existing `<profile id="SpringBoot3Tests">` (JDK 17+); add **both** `spring-boot-r2dbc-sql-starter` **and** `junit5-spring-boot-3-r2dbc-test` there (R2 — the starter is JDK 17, not JDK 8, so it cannot live in always-on modules).
- `sql-annotations-r2dbc` is **always built** — `r2dbc-spi`, `r2dbc-proxy`, and `reactor-core` are all Java 8 bytecode, and the module compiles cleanly under JDK 8+. `mvn clean install` on JDK 8 produces this artifact.
- A JDK-8 developer running `mvn clean install` thus gets `quick-perf-sql-annotations-r2dbc` published but skips `quick-perf-springboot-r2dbc-sql-starter` and the integration test module. Document this in the `bom` README.

### 6.6 Enforcer / bytecode rule

Verify that `mvn clean install` passes `enforce-bytecode-version`:
- `sql-annotations-r2dbc` with `<dependencies.max.jdk.version>1.8</dependencies.max.jdk.version>`. `r2dbc-spi` 1.0, `r2dbc-proxy` 1.1, `reactor-core` 3.6 are all Java 8 bytecode.
- `spring-boot-r2dbc-sql-starter` with `<dependencies.max.jdk.version>17</dependencies.max.jdk.version>` (R2 — `spring-boot-autoconfigure` 3.2.5 is Java 17).
- The root `1.7` enforcer for `sql-annotations` and other 1.7 modules is unchanged.

### 6.7 License compliance

Run `mvn license:check` and `mvn org.apache.maven.plugins:maven-dependency-plugin:tree -Dincludes=io.r2dbc:*,io.projectreactor:*`.

**Action required (R2.1 / R3):** edit `licenses\licenses.xml` to add aliases under the existing **`Apache Software License, Version 2.0`** entry (lines 33-43). The schema is `<licenses><valid><license><name>…</name><names><name>…</name></names><urls><url>…</url></urls></license>…</valid></licenses>` — `<valid>` is the OUTER container of all licenses; alias names go inside `<names>`, alias URLs inside `<urls>`. **Both `r2dbc-pool` AND `r2dbc-spi` POMs** declare `<name>Apache License 2.0</name>` over the URL `https://www.apache.org/licenses/LICENSE-2.0.txt` — neither alias currently appears in the existing `<names>`/`<urls>`. Concrete diff:

```diff
         <license>
             <name>Apache Software License, Version 2.0</name>
             <names>
                 <name>The Apache Software License, Version 2.0</name>
                 <name>Apache Public License 2.0</name>
+                <name>Apache License 2.0</name>
+                <name>Apache License, Version 2.0</name>
             </names>
             <urls>
                 <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
                 <url>http://www.apache.org/licenses/LICENSE-2.0</url>
+                <url>https://www.apache.org/licenses/LICENSE-2.0</url>
+                <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
             </urls>
         </license>
```

> **R2.1 — schema note:** an earlier R2 draft of this section nested a `<valid>` element *inside* `<license>` and listed `<name>`/`<url>` directly. That patch shape doesn't match the file (verified against `licenses\licenses.xml:13-43` — `<valid>` is the outer wrapper, alias names use `<names><name>…</name></names>`, alias URLs use `<urls><url>…</url></urls>`). Diff above is the corrected form.

Re-run `mvn license:check` to confirm zero unresolved licenses. Add to `licenses\allowedMissingLicense.xml` only if any transitive dep ships *without* a license declaration (none expected for the listed deps).

### 6.8 Driver coverage

- **v1 required:** `r2dbc-h2` (in-memory; default in test module).
- **v1 nice-to-have:** `r2dbc-postgresql` via the existing `testcontainers` Maven profile.
- **Future:** `io.asyncer:r2dbc-mysql`, `org.mariadb:r2dbc-mariadb`, `io.r2dbc:r2dbc-mssql`.

---

## 7. Documentation impacts

### 7.1 `CLAUDE.md`

Append to `### Module Structure`:
- Under `sql/`: `sql-annotations-r2dbc/` — R2DBC support via r2dbc-proxy.
- Under `spring/`: `spring-boot-r2dbc-sql-starter/` — Spring Boot 3 reactive starter (Java 17).

Append at the end of `### SQL Recording`:

> **Reactive SQL recording.** When `io.r2dbc:r2dbc-spi` is on the classpath, an additional listener built on `io.r2dbc:r2dbc-proxy` is installed by `QuickPerfR2dbcProxyBeanPostProcessor` wrapping `ConnectionFactory` beans. Events are adapted in `R2dbcExecutionAdapter` to synthetic `net.ttddyy.dsproxy.ExecutionInfo`/`QueryInfo` so existing verifiers operate unchanged. Reactive recording uses a JVM-global `SqlRecorderHook` (in `sql-annotations`) — populated by a 2-line edit to `PersistenceSqlRecorder.startRecording`/`stopRecording` — instead of relying on a `ThreadLocal`, because Reactor schedulers move query execution off the test thread; consequently, **reactive QuickPerf tests must run with `surefire.parallel=none` per JVM** (the `junit5-spring-boot-3-r2dbc-test` module sets this; users adopting the starter should mirror the configuration in Surefire, TestNG, and Gradle).

Append to `### Test Execution Flow` after step 4:

> 4a. (When `PersistenceSqlRecorder.startRecording` runs) The recorder registers itself in `SqlRecorderHook` (a JVM-global `CopyOnWriteArraySet`). For reactive R2DBC tests, the `R2dbcQuickPerfListener` reads from this hook (rather than the test thread's `ThreadLocal`) when an `afterQuery` event fires on a Reactor scheduler thread.

### 7.2 README

Add a subsection under "Spring Boot SQL Starter": **"Reactive (R2DBC) Spring Boot starter"**:

```xml
<dependency>
    <groupId>org.quickperf</groupId>
    <artifactId>quick-perf-springboot-r2dbc-sql-starter</artifactId>
    <version>${quickperf.version}</version>
    <scope>test</scope>
</dependency>
```

Example:

```java
@DataR2dbcTest
@QuickPerfTest
class BookRepositoryTest {

    @Test
    @ExpectSelect(1)
    void finds_books() {
        StepVerifier.create(repository.findByTitle("QuickPerf").collectList())
                    .expectNextCount(1)
                    .verifyComplete();
    }
}
```

Include a short table of supported / unsupported annotations from §2.1.

### 7.3 Annotation-level Javadoc tweaks

Append a one-line note on each in-scope annotation:

> Also applies to R2DBC connections via the `quick-perf-springboot-r2dbc-sql-starter` (Spring Boot 3+).

Files: `ExpectSelect.java`, `ExpectMaxSelect.java`, `ExpectInsert.java`, `ExpectMaxInsert.java`, `ExpectUpdate.java`, `ExpectMaxUpdate.java`, `ExpectDelete.java`, `ExpectMaxDelete.java`, `ExpectJdbcQueryExecution.java`, `ExpectMaxJdbcQueryExecution.java`, `ExpectMaxQueryExecutionTime.java`, `DisplaySql.java`, `DisplaySqlOfTestMethodBody.java`, `DisableSameSelects.java`, `DisableSameSelectTypesWithDifferentParamValues.java`, `DisableLikeWithLeadingWildcard.java`, `DisableStatements.java`, `DisableQueriesWithoutBindParameters.java`.

For deferred annotations append:

> Not yet supported on R2DBC; tracked as v2 work.

Files: `ExpectMaxSelectedColumn.java`, `ExpectSelectedColumn.java`, `ExpectJdbcBatching.java`, `ExpectNoConnectionLeak.java`, `ProfileConnection.java`.

For `ExpectJdbcQueryExecution.java` / `ExpectMaxJdbcQueryExecution.java` add:

> Despite the historical "Jdbc" name, this annotation also counts R2DBC SQL executions when the QuickPerf R2DBC starter is present.

### 7.4 New `R2DBCSuggestion`

Implementation in §5.4. Triggered conditionally in:
- `SpringDataSourceConfig.getMessage()` — starter-detection suggestion (when `containsR2dbcSpi()` and the R2DBC starter is missing).
- `SelectAnalysis.java:60` and `SqlReport.java:87` — appended to existing `JdbcSuggestion` text **only when** `ClassPath.INSTANCE.containsR2dbcSpi()` is true.

JDBC-only users see no diff. (R2 — the original draft incorrectly placed the conditional inside `SqlExecutions.format(...)`, which does not currently emit any suggestions; that location has been replaced with the two correct insertion points above.)

Sample text for missing-starter detection:

```text
QuickPerf detected Spring Data R2DBC, but no QuickPerf R2DBC SQL starter.

Add this test dependency:

  Maven:
    <dependency>
      <groupId>org.quickperf</groupId>
      <artifactId>quick-perf-springboot-r2dbc-sql-starter</artifactId>
      <version>${quickperf.version}</version>
      <scope>test</scope>
    </dependency>

Reactive SQL assertions require the test to complete its publishers
with StepVerifier.verify(), verifyComplete(), block(), or blockLast().
```

---

## 8. Risks & mitigations

| Risk | Mitigation |
|---|---|
| **License compliance.** `r2dbc-spi`, `r2dbc-proxy`, `r2dbc-pool`, `reactor-core`, `r2dbc-h2` are all Apache 2.0. | (R2) `r2dbc-pool` declares the license under the alias `Apache License 2.0` over `https://...`, neither of which is currently a `<valid>` form in `licenses\licenses.xml` lines 33-43. Add aliases per §6.7. Re-run `mvn license:check` to confirm. |
| **Reactor version drift.** SB 3.0/3.1 → Reactor 3.5; SB 3.2/3.3 → Reactor 3.6. r2dbc-proxy 1.1.x supports Reactor 3.4+. | Declare `reactor-core` `provided`; let user's Boot BOM win. CI matrix anchored on Boot 3.2.5. |
| **Custom user-provided `ConnectionFactory` decorators** (e.g., gavlyukovskiy-style, Micrometer's `ObservationProxyExecutionListener`). | Our BPP wraps the bean *as exposed* in the context. Decorators wrapping after us are fine. Decorators wrapping before us produce nested proxies — both intercept correctly. (R2) The `LOWEST_PRECEDENCE - 1` ordering does **not** guarantee absolute "wrap last" — another `PriorityOrdered` BPP at `LOWEST_PRECEDENCE` would still run after us; we accept this because nested proxies still work. |
| **Compatibility breadth.** `R2dbcEntityTemplate`, Spring Data R2DBC repositories, plain `DatabaseClient`, raw `ConnectionFactory` use. | All consume the same `ConnectionFactory` bean → all benefit. Test matrix exercises one of each (§6.3). |
| **`r2dbc-pool` wrapping order.** Wrapping the pool, not the driver, ensures one listener invocation per logical query. | Documented in starter README. |
| **Late-subscription queries firing `afterQuery` after `stopRecording`.** | Listener checks empty `SqlRecorderHook` → DEBUG log; tests must `block()`/`StepVerifier.verify()`. Documented. |
| **Bind-marker dialect divergence.** R2DBC drivers use `?`, `$N`, or `:name`. | (R2) Adapter rewrites `$N`/`:name` → `?` with negative-lookbehind regex (preserves PostgreSQL `column::int` cast operator); captures source order so `getNamedBindings()`'s `SortedSet` reordering doesn't corrupt `@DisplaySql`. Tested across positional/named/mixed. **Limitation:** SQL inside string literals or comments containing `:foo` patterns will be mis-rewritten in v1; documented in starter Javadoc. v2 work tracked in §9 Q-14. |
| **Backward compatibility.** Existing JDBC behavior must be bit-identical for users who don't add the new starter. | New starter is opt-in (separate Maven artifact). The `SqlRecorderHook` is empty when no R2DBC listener subscribes; `PersistenceSqlRecorder.startRecording`/`stopRecording` add only one `register`/`unregister` call to a `CopyOnWriteArraySet` (microsecond cost). The `R2DBCSuggestion` text is appended only when `ClassPath.containsR2dbcSpi()` returns true. Run the full existing test suite as a regression gate. |
| **Surefire `parallel=all` clash (Surefire / TestNG / Gradle).** Parent root POM uses `parallel=all, threadCount=5` (`pom.xml:109-110`); `testng/pom.xml` uses `<parallel>all</parallel>`; Gradle users may set `maxParallelForks`. | `sql-annotations-r2dbc` unit tests pass under parallel=all by design — `R2dbcRegistryParallelExecutionTest` uses constructor-injected recorders, not `SqlRecorderHook`'s static set (§6.1). The integration test module overrides to `parallel=none` matching `junit5-spring-boot-3-test`. `SqlRecorderHookTest` (in `sql-annotations`) runs single-threaded. README documents the constraint for downstream users in Surefire, TestNG, and Gradle. |
| **GraalVM native-image (R2).** `spring-boot-r2dbc-sql-starter` will be native-image-evaluated by Boot 3 users. Reflection on `setObject` `Method` references (§4.6) and proxy generation may need hints. | v1 ships **without** explicit native-image hints (`reflect-config.json` / `proxy-config.json`); document in README that native-image users should report issues. v2: add hints under `META-INF/native-image/org.quickperf/quick-perf-springboot-r2dbc-sql-starter/`. |
| **Observability listener stacking (R2).** Boot 3 enables `ObservationProxyExecutionListener` (Micrometer) on the same `ConnectionFactory` when `spring.r2dbc.observation.enabled=true`. | Our `R2dbcQuickPerfListener` is added via `ProxyConnectionFactory.builder(cf).listener(quickperfListener).build()`. r2dbc-proxy supports multiple listeners on one factory; both fire. Document. |
| **Multi-`ConnectionFactory` beans / multi-tenancy (R2).** Some apps register two `ConnectionFactory` beans (e.g., one read-only, one read-write). | The BPP wraps every `ConnectionFactory` bean. Each wrapping creates one listener instance. All listeners forward to the same `SqlRecorderHook`, so the recorder sees executions from all factories in a unified count. Document; if users want to scope by factory, they can use a custom `@Qualifier`-aware listener (v2). |
| **Multiple test frameworks per build (R2).** Some projects mix JUnit 4, JUnit 5, and TestNG in one module. The JVM-global `SqlRecorderHook` requires `parallel=none` regardless of framework. | Document in starter README per framework: Surefire `<parallel>none</parallel>`, TestNG `<parallel>methods</parallel>` with `<threadCount>1</threadCount>`, Gradle `maxParallelForks=1`. |

---

## 9. Open questions (for Jean Bisutti)

Each is framed as a multiple-choice with a recommended option. None blocks design; all can be resolved at PR review.

| # | Question | Choices | Recommendation |
|---|---|---|---|
| 1 | New starter artifactId | (a) `quick-perf-springboot-r2dbc-sql-starter`, (b) `quick-perf-springboot3-r2dbc-starter`, (c) `quick-perf-r2dbc-sql-starter` | **(a)** — symmetric with `quick-perf-springboot2-sql-starter` |
| 2 | Spring Boot version floor | (a) **SB 3.0+ only**, (b) SB 2.7+ (extra dual-config effort), (c) SB 3.2+ (matches existing test module) | **(a)** — clean modern baseline; aligns with Spring's `spring-boot-starter-data-r2dbc` |
| 3 | `@ExpectMaxSelectedColumn` on R2DBC | (a) **Defer**, (b) implement now via `ResultRowConverter` + `ConnectionInfo.getValueStore()`, (c) silently return 0 with no warning | **(a)** — defer; (R2) no warning in v1 because the listener has no `TestExecutionContext` access |
| 4 | `@ExpectJdbcQueryExecution` naming | (a) **Keep as-is**, (b) introduce alias `@ExpectQueryExecution` and deprecate the JDBC name, (c) introduce alias without deprecation | **(a)** for v1; revisit (c) in a follow-up PR |
| 5 | Surefire parallel for new test module | (a) **`parallel=none`**, (b) `parallel=classes` with `forkCount=1C` | **(a)** — mirrors existing `junit5-spring-boot-3-test` |
| 6 | `SqlRecorderHook` location | (a) **In `sql-annotations`** (R2 — must live there so `PersistenceSqlRecorder` can call it without circular dep), (b) merge into `SqlRecorderRegistry` as a third mode | **(a)** — separate concerns; the existing dual-mode `SqlRecorderRegistry` remains untouched |
| 7 | `JdbcSuggestion.SERVER_ROUND_TRIPS` text on R2DBC failures | (a) **Keep + append `R2DBCSuggestion`**, (b) replace with R2DBC-only when classpath is reactive | **(a)** — additive; preserves message stability for mixed contexts |
| 8 | Coexistence (DataSource + ConnectionFactory) | (a) **Both feed unified `SqlExecutions`**, (b) split into two measures with distinct annotations | **(a)** — simpler; future opt-in `quickperf.sql.r2dbc.exclusive` if needed |
| 9 | `@ExpectNoConnectionLeak`/`@ProfileConnection` for R2DBC | (a) **Defer to v2**, (b) v1 throw clear exception, (c) v1 log warning | **(a)** — defer the feature; (R2) no warning in v1 (no listener path can both observe annotations and emit one warning per test); document in Javadoc |
| 10 | SQL placeholder rewriting in synthesized `QueryInfo` | (a) **Yes, in adapter** (`$N`/`:name` → `?` with negative-lookbehind), (b) leave raw and patch `AllParametersAreBoundExtractor` to recognize `$N`/`:name` | **(a)** — keeps existing extractor unchanged; minimal blast radius |
| 11 | Heading text in `SqlExecutions.format` | (a) Keep `[JDBC QUERY EXECUTION ...]` for JDBC-only contexts, switch to `[SQL QUERY EXECUTION ...]` for mixed/R2DBC, (b) always `[SQL QUERY EXECUTION ...]` | **(a)** — preserves existing message stability; opt-in generalization for mixed mode |
| 12 (R2) | `PersistenceSqlRecorder` 2-line edit | (a) **Accept 2-line edit + new `SqlRecorderHook` file in `sql-annotations`**, (b) refactor `SqlRecorderRegistry` to support a third "scheduler-agnostic" mode and dispatch through it, (c) add an SPI extension point allowing R2DBC to register a listener that `PersistenceSqlRecorder` invokes | **(a)** — smallest surface (3 LOC); (b) is invasive; (c) reinvents (a) with more ceremony |
| 13 (R2) | Deferred-annotation runtime warning | (a) **Document only (Javadoc + README)**, (b) emit one-shot WARN from `PersistenceSqlRecorder.startRecording` when an R2DBC listener is registered AND deferred annotation is on test, (c) emit per-execution DEBUG | **(a)** for v1; (b) in v2 once `SqlRecorderHook` is established |
| 14 (R2) | Placeholder-rewrite SQL-string-literal handling | (a) **Best-effort regex; document `'…:foo…'` mis-fire limitation**, (b) full SQL tokenizer in adapter, (c) parse via `JSqlParser` | **(a)** for v1; revisit if users hit it. (b)/(c) add ~50 KB dep + ~2× CPU cost per query for an edge case |
| 15 (R2) | Kotlin Coroutines (`CoroutineCrudRepository`) | (a) **Out of scope for v1**; assume it works via underlying `ConnectionFactory` wrap, (b) add explicit Kotlin test in v1 | **(a)** — unblocks v1; add a Kotlin smoke test in v2 |
| 16 (R2) | GraalVM native-image hints | (a) **Document only ("report issues")**, (b) ship `reflect-config.json` / `proxy-config.json` in v1 | **(a)** for v1; r2dbc-proxy + Spring AOP + JDK proxies need careful hint design |
| 17 (R2) | Hibernate Reactive (Mutiny) | (a) **Out of scope; document explicitly**, (b) v2 work | **(a)** for v1; Hibernate Reactive uses a different pool implementation and Mutiny adapter — separate plan needed |

---

## 10. Synthesis decisions (where the two source proposals diverged)

This plan combines proposals from Claude Opus 4.7 (Extra high reasoning) and GPT-5.5, then was revised after a second round of review by both models. Here are the key divergences and why this synthesis chose one over the other:

| Topic | Opus | GPT-5.5 | Chosen |
|---|---|---|---|
| Threading isolation | JVM-global `CopyOnWriteArraySet` + force `parallel=none` for reactive test module | Reactor `Hooks.onEachOperator` global hook for `Context` propagation | **Opus** — pragmatic, matches existing `junit5-spring-boot-3-test` precedent; reactor hook is intrusive and adds version-coupling risk. (R3.1) **`parallel=none` applies to integration tests only** (`junit5-spring-boot-3-r2dbc-test` module). Unit tests in `sql-annotations-r2dbc` run under root `parallel=all, threadCount=5` via constructor-injected `Supplier<Iterable<SqlRecorder<?>>>` (see §4.11) — only `SqlRecorderHookTest` (which exercises the static set directly, in `sql-annotations`) overrides to `parallel=none`. |
| Module count | 2 new modules (`sql-annotations-r2dbc`, `spring-boot-r2dbc-sql-starter`) | 3 new modules (additionally `sql-r2dbc-spring`) | **Opus (2 modules)** — for v1 (Boot 3+ only) the BPP doesn't need its own module; GPT-5.5's split is justified only if we expand to non-Boot Spring reactive support later |
| SB version floor | 3.0+ only | Java 8 modules + optional 2.7+ | **Opus (3.0+ only)** — simpler; aligns with Spring's reactive baseline; reduces dual `spring.factories`/`AutoConfiguration.imports` effort |
| Placeholder rewriting | Explicit `$N`/`:name` → `?` rewrite in adapter | Map binding keys but no SQL rewrite | **Opus** — concrete and surgical; (R2) **revised** to use negative-lookbehind regex + source-order capture after both reviewers caught the `column::int` corruption and `getNamedBindings()` `SortedSet` reordering bug |
| `SqlExecution` model | Synthesize datasource-proxy types in adapter | Same, with optional repository overload for prebuilt `SqlExecution`/explicit column count | **Opus's purity** for v1 — no edits to `SqlRecorder`/`SqlRepository`; (R2) the column-count overload becomes useful in v2 when the column-count feature lands |
| Annotation matrix | Concise (17 in/5 deferred) | Granular per-annotation table with rationale | **GPT-5.5 framing** for the matrix (more comprehensive); (R2) `@AnalyzeSql` and `@DisableSameSelectTypesWithDifferentParamValues` carved out as "single-binding only" after GPT-5.5 caught `QueryParamsExtractor.IllegalStateException` for multi-binding cases |
| Late-event handling | Empty-registry no-op + DEBUG log | DEBUG/WARN with one-shot per-test message | **GPT-5.5** — clearer user feedback when reactive tests forget `block()` |
| Driver coverage list | H2 only (mentioned) | H2 + Postgres + MySQL/MariaDB/MSSQL future | **GPT-5.5** for the explicit list |
| **Recorder lifecycle (R2)** | Freestanding `RecordablePerformance<NoMeasure>` bridge wired via SPI loader | Same as Opus | **Neither proposal worked.** Both reviewers' second round (GPT-C1, Opus C-1) caught that `TestExecutionContext` only instantiates recorders mapped from applied annotations, so the bridge would never run. **Adopted: hook approach** — new `SqlRecorderHook` in `sql-annotations` + 2-line `PersistenceSqlRecorder` edit (§3.8). |
| **Java version of starter (R2)** | `<dependencies.max.jdk.version>1.8</dependencies.max.jdk.version>` | Same | **Both wrong.** GPT-5.5 second round caught that `spring-boot-autoconfigure` 3.2.5 is Java 17 bytecode (`javap` major 61). Starter is now JDK 17, in `<profile id="SpringBoot3Tests">`. |
| **Suggestion location (R2)** | Edit `SqlExecutions.format(...)` to append text | Same | **Both wrong.** Opus second round (I-1) caught that `SqlExecutions.format` doesn't currently emit any `JdbcSuggestion`. Adopted: starter-suggestion → `SpringDataSourceConfig`; round-trip → `SelectAnalysis` + `SqlReport`. |
| **Deferred-annotation warning (R2)** | Emit WARN from `R2dbcQuickPerfListener.beforeMethod` | Same | **Both wrong.** GPT-5.5 second round (C3) caught that `MethodExecutionInfo` has no `TestExecutionContext` access. Adopted: document-only in v1; future hook into `PersistenceSqlRecorder.startRecording` deferred to v2 (Q-13). |
| **License compliance (R2)** | "Already a `<valid>` license" | Same | **Both wrong.** Both reviewers' second round caught that `r2dbc-pool` declares the license under unrecognized aliases. Adopted: add explicit aliases to `licenses.xml` per §6.7. |
| **Standalone `ConnectionFactories.get(...)` (R2)** | Listed as in-scope user story | Same | **Removed from v1 acceptance** (GPT-5.5 second round). No Spring BPP path; manual builder API documented but not test-covered in v1. |

Both proposals agreed unanimously on:
- Architecture: Option B (use r2dbc-proxy + adapter to existing model) over Option D (neutral model now).
- Annotation deferrals: `@ExpectMaxSelectedColumn`, `@ExpectSelectedColumn`, `@ExpectJdbcBatching`, `@ExpectNoConnectionLeak`, `@ProfileConnection`.
- Coexistence: unified `SqlExecutions`.
- Connection-pooling order: wrap exposed pool bean.
- 5-PR phased delivery with the same logical sequence.

**Post-review credit:** GPT-5.5's second-round review caught the lifecycle blocker (recorder-instantiation) and the Java-version blocker that Opus's first-round review missed. Opus's second-round review caught the suggestion-location bug (`SqlExecutions.format` is the wrong target), the placeholder-regex `::cast` corruption, the `NoMeasure` non-existence, and the `getNamedBindings()` `SortedSet` source-order issue. The R2 plan is the union of all 17 distinct findings.

---

## 11. Phased delivery (no time estimates)

Each PR builds and tests cleanly on its own; reviewers can merge in order without forcing a big-bang. PRs do not reorder existing recorder execution numbers, do not rename existing classes, and do not change existing serialization format.

### PR 1 — Foundations *(no functional change to existing JDBC)*

**Files included** (all under `C:\code\fork6\quickperf\`):
- `sql\sql-annotations\src\main\java\org\quickperf\sql\framework\ClassPath.java` — add `containsR2dbcSpi()`, `containsR2dbcProxy()`, `containsSpringDataR2dbc()`.
- `sql\sql-annotations\src\main\java\org\quickperf\sql\framework\quickperf\QuickPerfDependency.java` — add `QUICKPERF_SPRING_BOOT_R2DBC_SQL_STARTER`.
- `sql\sql-annotations\src\main\java\org\quickperf\sql\framework\R2DBCSuggestion.java` — new file.
- (R2 / R3) `sql\sql-annotations\src\main\java\org\quickperf\sql\SqlRecorderHook.java` — **new file**, JVM-global `CopyOnWriteArraySet<SqlRecorder<?>>` (parameterized — see §3.8).
- (R2) `sql\sql-annotations\src\main\java\org\quickperf\sql\PersistenceSqlRecorder.java` — **2-line edit** (`SqlRecorderHook.register(this)` / `.unregister(this)` in `start`/`stopRecording`).
- Unit tests for the new `ClassPath` methods and `SqlRecorderHook` (concurrency under `@Execution(SAME_THREAD)`).
- (R3) `SqlRecorderLifecycleRegressionTest` — assert `SqlRecorderHook.getActiveRecorders()` is empty after a `PersistenceSqlRecorder.startRecording` failure (fault-injection: throw from a stub recorder construction step), proving the §3.8 exception-safety contract.

**Exit criteria:**
- `mvn clean install` passes.
- New `ClassPath` methods + `SqlRecorderHook` covered by unit tests.
- (R2) **Critical regression gate:** all existing JDBC tests still pass — the 2-line `PersistenceSqlRecorder` change must be invisible to JDBC users. Run `mvn clean install -P -SpringBootTests -P -jfr -P testcontainers` and confirm zero failures.
- (R3) Lifecycle regression test green.

### PR 2 — `sql-annotations-r2dbc`: r2dbc-proxy listener & adapter

**Files included:**
- `sql\sql-annotations-r2dbc\pom.xml` (`maven.compiler.source/target=1.8`, `dependencies.max.jdk.version=1.8`).
- `sql\pom.xml` — add `<module>sql-annotations-r2dbc</module>`.
- All Java sources under `sql\sql-annotations-r2dbc\src\main\java\org\quickperf\sql\r2dbc\` (§5.1) — listener, adapter, bindings adapter, builder. **No SPI loader.**
- Unit tests (§6.1): `R2dbcExecutionAdapterTest`, `R2dbcBindingsAdapterTest`, `R2dbcQuickPerfListenerTest`, `R2dbcRegistryParallelExecutionTest`.
- (R3) `R2dbcConcurrentDispatchToOneRecorderTest` — see §6.1; gates §3.8's `synchronized (recorder)` dispatch contract.
- (R3) `R2dbcQueryParamsExtractorTest` — locks PostgreSQL `$N` `Binding.getKey()` indexing contract.

**Exit criteria:**
- `mvn -pl sql/sql-annotations-r2dbc -am clean test` passes.
- `mvn clean install -P -SpringBootTests -P -jfr` (full repo, no SB tests) passes.
- Adapter coverage ≥ 90 %.
- `enforce-bytecode-version` passes with `dependencies.max.jdk.version=1.8`.
- (R3) Concurrent-dispatch regression gate: removing `synchronized (recorder)` in §3.8 must cause `R2dbcConcurrentDispatchToOneRecorderTest` to fail.

### PR 3 — Spring Boot autoconfig + `BeanPostProcessor` *(JDK 17 only)*

**Files included:**
- `spring\spring-boot-r2dbc-sql-starter\pom.xml` (R2 — `maven.compiler.source/target=17`, `dependencies.max.jdk.version=17`).
- `spring\pom.xml` — add `<module>spring-boot-r2dbc-sql-starter</module>` **inside `<profile id="SpringBoot3Tests">`**, NOT in always-on modules (R2).
- All Java sources under `spring\spring-boot-r2dbc-sql-starter\src\main\java\org\quickperf\spring\boot\r2dbc\` (§5.2).
- `META-INF/spring/...AutoConfiguration.imports` and `...AutoConfigureDataR2dbc.imports`. **No `spring.factories`** (R2).

**Exit criteria:**
- `mvn -pl spring/spring-boot-r2dbc-sql-starter -am clean install` passes.
- A smoke test instantiates an `AnnotationConfigApplicationContext` with a mock `ConnectionFactory` bean and asserts the BPP wraps it (proxy class implements `ConnectionFactory` and `QuickPerfR2dbcProxyMarker`).
- (R3) **`@Autowired ConnectionPool` smoke test** — separate Spring context with a `ConnectionPool` bean (concrete class, not just `ConnectionFactory`); after BPP wraps it, asserts that `@Autowired ConnectionPool pool` injection still resolves OR documents the JDK-proxy regression with a `@ConditionalOnMissingClass` exclusion / type-erased fallback. The §4.10 acceptance criterion: either users keep typing fields as `ConnectionFactory` (documented) or the BPP detects `ConnectionPool` and uses a CGLIB sub-class proxy. v1 chooses the documented `ConnectionFactory` path; the smoke test enforces it.
- Existing JDBC-only Boot tests still pass without R2DBC starter.

### PR 4 — Test module + R2DBC framework hints

**Files included:**
- `spring\junit5-spring-boot-3-r2dbc-test\pom.xml`.
- `spring\pom.xml` — add module to `<profile id="SpringBoot3Tests">`.
- All Java sources & resources under `spring\junit5-spring-boot-3-r2dbc-test\src\` (§5.3).
- (R2 / R3) `sql\sql-annotations\src\main\java\org\quickperf\sql\framework\quickperf\SpringDataSourceConfig.java` — insert R2DBC starter suggestion branch in `getMessage()` **before the combined `containsSpringBoot2() || containsSpringBoot3()` branch (lines 41-47)**, gated on `containsSpringBoot3() && containsR2dbcSpi() && !contains(QUICKPERF_SPRING_BOOT_R2DBC_SQL_STARTER)` so it short-circuits before the JDBC SB3 path. (R3 — earlier R2 wording said "after the SB3 detection branch", which would have been a no-op because the file has no separate SB3 branch — only a combined SB2-or-SB3 block at line 41.)
- (R2 / R3) `sql\sql-annotations\src\main\java\org\quickperf\sql\select\analysis\SelectAnalysis.java` (`getNPlusOneSelectAlert`, single-statement `return` at lines 52-63) — refactor to `StringBuilder` per §5.4 and append `R2DBCSuggestion.SERVER_ROUND_TRIPS` when `ClassPath.INSTANCE.containsR2dbcSpi()`. (R3: cannot be done as a "+1 line" change because of the single-statement `return`.)
- (R2 / R3) `sql\sql-annotations\src\main\java\org\quickperf\sql\analyze\SqlReport.java` (`buildNPlusOneMessage`, lines 80-93, line 87) — append `R2DBCSuggestion.SERVER_ROUND_TRIPS` text after `JdbcSuggestion.SERVER_ROUND_TRIPS`, with one `lineSeparator()` between them; mind that line 87 sits between `+ System.lineSeparator()` segments — naive append yields `JdbcMsgR2dbcMsg`. **Replaces** the original (and dead) `SqlExecutions.java` edit.

**Exit criteria:**
- `mvn -P SpringBoot3Tests -pl spring/junit5-spring-boot-3-r2dbc-test -am clean test` passes on JDK 17+.
- Existing `SpringBoot3Tests` profile remains green.
- Failing R2DBC tests display the new suggestion text.
- Coexistence tests pass (R3: split per §6.3 — Flyway-startup-noise and JDBC+R2DBC unification).
- `mvn clean install` (with all profiles auto-activated on JDK 17) green.

### PR 5 — Documentation, BOM, license finalization

**Files included:**
- `bom\pom.xml` — add `quick-perf-sql-annotations-r2dbc` and `quick-perf-springboot-r2dbc-sql-starter`.
- `CLAUDE.md` — additions per §7.1.
- `README.md` — additions per §7.2.
- All Javadoc tweaks per §7.3 across the listed annotation files.
- (R2 / R3) `licenses\licenses.xml` — add `Apache License 2.0` name alias and `https://...` URL aliases per §6.7. Required for **both `r2dbc-pool` and `r2dbc-spi`** (both POMs declare the license under `<name>Apache License 2.0</name>` over `https://www.apache.org/licenses/LICENSE-2.0.txt`, neither of which currently resolves).
- `licenses\allowedMissingLicense.xml` — only if any new transitive dep is flagged.

**Exit criteria:**
- `mvn license:format` produces no diff.
- (R2 / R3) `mvn license:check` passes (the `licenses.xml` alias additions resolve **both** `r2dbc-pool`'s and `r2dbc-spi`'s license declarations).
- `mvn -P testcontainers` profile still builds.
- A clean checkout of master + this PR series produces a deployable `quick-perf-bom` exposing the two new artifacts.

---

> **Authoring note (R2)** — this plan deliberately minimizes touching `SqlRecorder`, `SqlExecution`, `SqlExecutions`, `SqlRecorderRegistry`, `ConnectionListenerRegistry`, or any of the existing 17 verifier classes. **Two unavoidable exceptions**, validated by both reviewers' second round, are: (1) a **2-line edit** to `PersistenceSqlRecorder.startRecording`/`stopRecording` to call `SqlRecorderHook.register/unregister` (without it, R2DBC events on Reactor scheduler threads cannot reach the active recorder); (2) a **conditional append** in `SelectAnalysis` and `SqlReport` to add `R2DBCSuggestion` text alongside `JdbcSuggestion` when the classpath is reactive. Both are gated by no-op-when-empty and no-op-when-classpath-is-JDBC-only behavior, preserving exact backward compatibility for every JDBC-only user. Reactive support is implemented as a parallel layer that *feeds* the existing recorder pipeline, with the 3 LOC inside `sql-annotations` being the smallest change that makes the JDBC↔reactive lifecycle physically possible.
