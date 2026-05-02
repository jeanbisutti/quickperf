# Plan: Spring-aware QuickPerf properties (with parallel-safe wiring)

## 1. Goal

Make QuickPerf "display-control" properties readable from **any** Spring property
source, not only from `-D` system properties:

| Source                                              | Today (`System.getProperty`) | After this plan (Spring `Environment`) |
| --------------------------------------------------- | :--------------------------: | :-------------------------------------: |
| `-Dprop=value` JVM arg                              | ✅                            | ✅                                       |
| `@SpringBootTest(properties = {"prop=value"})`      | ❌                            | ✅                                       |
| `application.properties` / `application.yml`        | ❌                            | ✅                                       |
| `application-{profile}.properties` / `.yml`         | ❌                            | ✅                                       |
| `@TestPropertySource(locations / properties = ...)` | ❌                            | ✅                                       |
| `@DynamicPropertySource`                            | ❌                            | ✅                                       |
| OS environment variables                            | ❌                            | ✅ (via Spring's relaxed binding)        |

**Three** user-facing properties are migrated:

- `limitQuickPerfSqlInfoOnConsole` — display control (SQL)
- `limitQuickPerfJvmInfoOnConsole` — display control (JVM)
- `disableQuickPerf` — global short-circuit (highest impact)

`quickPerfWorkingFolder` and `quickPerfToExecInASpecificJvm` **stay system-only**
because they are internal plumbing read before any Spring context exists
(working folder coordination across JVMs, child-JVM identity flag during fork
bootstrap). They are not user-facing in any meaningful sense.

> **`disableQuickPerf` was previously attempted with a `System.setProperty`
> bridge** (`QuickPerfSpringPropertiesBridge`), and that PR review (see
> `pr-review-summary.txt` at the repo root) flagged five issues, including
> JVM-global state leakage, race conditions under `parallel=all`, and
> overwriting explicit `-D` flags. **This plan deliberately does NOT use a
> bridge** — see §3.4.

---

## 2. Architecture (single source of truth: Spring `Environment`)

```
                  ┌────────────────────────────────────────────────┐
                  │  Test runner / extension (per-test method)     │
                  │                                                │
   JUnit 4 (Spring, same JVM): SpringRunnerWithQuickPerfFeatures   │
       methodInvoker(...) reads                                    │
       getTestContextManager().getTestContext()                    │
              .getApplicationContext().getEnvironment()            │
       and passes resolver to a new                                │
       QuickPerfJUnitRunner.methodInvoker(..., resolver) overload  │
       which forwards to TestExecutionContext.buildFrom(..., r).   │
       The resolver is then retained on the resulting context.     │
                                                                   │
   JUnit 4 (Spring, fork parent): QuickPerfSpringRunner            │
       Spring context is NOT loaded in the parent fork path,       │
       so SystemPropertyResolver.INSTANCE is used.                 │
       The child JVM loads Spring and re-evaluates with its own    │
       resolver. (See §3.3 (13)).                                   │
                                                                   │
   JUnit 5 (Spring): QuickPerfTestExtension                        │
       discovers a PropertyResolverProvider via ServiceLoader.     │
       The Spring impl (shipped from quick-perf-junit5-spring,     │
       which has compile-time spring-test dep) returns a resolver  │
       backed by SpringExtension.getApplicationContext(extCtx)     │
       .getEnvironment(). Resolver is built ONCE in beforeEach     │
       and retained on the TestExecutionContext that goes into     │
       ExtensionContext.Store. Subsequent callbacks                │
       (interceptTestMethod, …) read it via                        │
       testExecutionContext.getPropertyResolver().                 │
       No reflection in QuickPerfTestExtension.                    │
                                                                   │
   Non-Spring callers (JUnit4 plain, JUnit5 plain, TestNG):        │
       SystemPropertyResolver.INSTANCE                             │
                  └──────────────────┬─────────────────────────────┘
                                     │  PropertyResolver r (immutable; captured in
                                     │  a final field of an anonymous class)
                                     ▼
                      TestExecutionContext.buildFrom(..., r)
                         │  quickPerfIsDisabled(perfAnnotations, r) reads
                         │  "disableQuickPerf" from r (Spring or System).
                         │  r is retained on the resulting TestExecutionContext
                         │  (single-assignment private field, no public setter).
                         ▼
                      testExecutionContext  (carries propertyResolver)
                         │
                         ▼
                      PerfIssuesEvaluator.evaluatePerfIssuesIfNoJvmIssue(
                         configs, testExecutionContext, jvmOrTestIssue)
                         │  PropertyResolver r =
                         │      testExecutionContext.getPropertyResolver();
                         │
                         ├──> VerifiablePerformanceIssue
                         │       .verifyPerfIssue(ann, measure, r)
                         │       (SPI signature now takes resolver)
                         └──> SqlExecutions.setPropertyResolver(r)
                                 then format(...) reads it
```

Three invariants make this parallel-safe by construction:

1. **Resolver is built per test method invocation** on the same thread that
   runs the assertions, after Spring's `ApplicationContext` is loaded for that test.
2. **Resolver is retained on `TestExecutionContext` as a single-assignment
   private field** (no public setter), assigned once by `buildFrom(...)` —
   never stored on a `ThreadLocal`, a static, or any long-lived mutable
   object. `TestExecutionContext` itself is per-test-method (constructed
   fresh by `buildFrom` on the calling thread, never shared with another
   test), so the resolver inherits that lifetime exactly. Downstream phases
   read the resolver from the context they already hold:
   - JUnit 5: `interceptTestMethod` / `interceptTestTemplateMethod` /
     `interceptDynamicTest` retrieve `TestExecutionContext` from
     `ExtensionContext.Store` (already done today) and call
     `testExecutionContext.getPropertyResolver()`. **The resolver is built
     once in `beforeEach`, not rebuilt per callback.** JUnit 5's `Store` is
     specified as thread-safe, which provides the happens-before relation
     between the `put` in `beforeEach` and the `get` in the interceptor.
   - JUnit 4: `MainJvmAfterJUnitStatement` already holds a `final
     TestExecutionContext` reference (per-test-method, captured by the JUnit
     `Statement` chain); it calls `testExecutionContext.getPropertyResolver()`.
     **No new constructor parameter is needed.** Within JUnit 4's
     `methodBlock`, `methodInvoker(...)` (which assigns `testExecutionContext`)
     and `withAfters(...)` (which reads it) run sequentially on the same
     worker thread, so the field assignment is visible without further
     synchronization.
   - Evaluator (`PerfIssuesEvaluator.evaluatePerfIssuesIfNoJvmIssue`) keeps
     its existing signature; it pulls the resolver from
     `testExecutionContext.getPropertyResolver()` and forwards it to
     verifiers / `SqlExecutions.setPropertyResolver(...)`.
3. **The Spring `Environment` is read-only after refresh** — concurrent reads
   from any number of test threads are safe.

**Critical: no `System.setProperty` push.** The resolver pulls on demand. The
JVM's system properties are never mutated by QuickPerf. This is what makes the
design parallel-safe; the previous bridge attempt failed precisely because it
mutated JVM-global state.

**Java 7 compatibility (this codebase targets `maven.compiler.source=1.7`).**
The plan uses **anonymous classes** (no lambdas, no method references) and **no
default methods on interfaces**. Boolean parsing uses `Boolean.parseBoolean(...)`
to preserve current case-insensitive behavior (today's code uses
`Boolean.valueOf(System.getProperty(...))`).

---

## 3. Thread-safety analysis (this is what the user asked for)

### 3.1 Spring side

| Mechanism                                                         | Why it's safe under parallel tests                                                                                                                                    |
| ----------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `MergedContextConfiguration.equals` includes `propertySourceProperties` (Spring) | Different `properties=` arrays → different cache keys → different `ApplicationContext` → different `Environment`. Each thread sees the right values for *its* test.    |
| `ContextCache` uses `Collections.synchronizedMap(new LinkedHashMap<>(..., true))` | Map mutations are serialized. Read access is safe.                                                                                                                     |
| `DefaultCacheAwareContextLoaderDelegate.loadContext` wraps everything in `synchronized (contextCache)` | Context creation is serialized (a one-time cost per cache key). Once loaded, the context reference is safely published to all threads.                                 |
| `AbstractEnvironment.getProperty(String)`                         | Iterates `MutablePropertySources`; reads after refresh are safe (Spring's runtime depends on this). No lock on the read path.                                          |
| `application.properties` / `.yml` parsing                          | Happens once during context load, under the cache lock. Result is immutable for the lifetime of the context.                                                           |

### 3.2 QuickPerf side

| Component                                  | State                                                          | Safety argument                                                                                                                       |
| ------------------------------------------ | -------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| `PropertyResolver` (anonymous class)       | captures `final Environment env`                               | No mutable state, no shared field. Each test thread builds its own instance.                                                          |
| `SystemPropertyResolver.INSTANCE`          | stateless singleton                                            | Delegates to `System.getProperty` (`Hashtable` based — already thread-safe). No fields.                                                |
| `TestExecutionContext.propertyResolver`    | per-test-method instance; single-assignment private field; no public setter | `TestExecutionContext` is built fresh by `buildFrom(...)` for each test method and never shared across tests, so two parallel tests cannot observe each other's resolver. The field is assigned exactly once on the calling thread and never reassigned. Cross-thread reads (JUnit 5 callbacks reading via `ExtensionContext.Store.get(...)`) inherit happens-before from the thread-safe `Store`; same-thread reads (JUnit 4 `withAfters`, evaluator on the test thread) need no extra publication. **`TestExecutionContext` is not `Serializable`**, so unlike `SqlExecutions` (§3.3 (2)) there is no fork-JVM `transient` concern. |
| `PerfIssuesEvaluator.INSTANCE`             | stateless singleton; `final TestIssueRepository`               | All per-test data flows through the `TestExecutionContext` parameter; the resolver is pulled from that context, no shared mutable field on the evaluator. |
| Verifier singletons (`SelectNumberPerfIssueVerifier.INSTANCE`, …) | stateless                                                      | Receive `(annotation, measure, resolver)` on the stack; no shared mutable fields.                                                     |
| `SqlExecutions` (per-test)                 | one instance per test (`new SqlExecutions()` in `SqlMemoryRepository`) | Setter `setPropertyResolver` is called by the evaluator thread, then `format(...)` is read by the same evaluator thread (no cross-thread). |
| `SqlExecutions.NONE` (shared sentinel)     | shared across threads                                          | **Risk** — see §3.3 (1) for the guard.                                                                                                |
| `SqlExecutions implements Serializable`    | crosses JVM boundary in fork mode                              | **Risk** — see §3.3 (2) for `transient`.                                                                                              |
| `SqlRecorderRegistry`                      | already designed for parallel SQL recording (existing concern, not introduced by this plan) | n/a                                                                                                                                   |

### 3.3 Risks and mitigations (these are the subtle ones)

1. **`SqlExecutions.NONE` is a shared sentinel.**
   If two threads called `NONE.setPropertyResolver(r1)` and `NONE.setPropertyResolver(r2)` concurrently we'd have a data race.
   *Mitigation:* `setPropertyResolver` becomes a no-op when `this == NONE`. (Defensive — in practice `format()` is never called on `NONE` because empty records don't reach the formatting path, but we don't want to depend on that.)

2. **`SqlExecutions` is `Serializable` (forked-JVM mode).**
   The new `propertyResolver` field would either fail to serialize (the anonymous class captures a Spring `Environment`) or land null in the main JVM. Both are wrong outcomes.
   *Mitigation:* declare the field `transient`. The flow is:
   - Fork JVM records SQL and writes the file → Spring not involved.
   - Main JVM reads the file (deserialize), `evaluator` retrieves the record, calls `setPropertyResolver(mainJvmResolver)` **before** `format(...)`. The `transient` field is null right after deserialization, but the setter populates it on the same thread before formatting. ✅

3. **`format()` could be called before `setPropertyResolver(...)` (defensive).**
   *Mitigation:* `format()` reads the field and falls back to `SystemProperties.SIMPLIFIED_SQL_DISPLAY.evaluate()` if it's `null`. This preserves today's behavior on the unhappy path.

4. **`filterByQueryType()` returns a new `SqlExecutions` without the resolver.**
   *Mitigation:* the formatter is only invoked on the original `PerfRecord` stored in `perfRecordByAnnotation`, never on a filtered copy. We document this and copy the resolver in `filterByQueryType` as a belt-and-braces measure (one extra line).

5. **Surefire thread reuse / `parallel=all, threadCount=5`.**
   The repo memory confirms the build runs Surefire with `parallel=all, threadCount=5`. Each test method gets its own `TestExecutionContext` instance carrying its own resolver; nothing is stashed on the worker thread itself, so reuse of a Surefire worker thread by a later test cannot leak a stale resolver (vs. `ThreadLocal`, which would).

6. **`ServiceLoader` SPI in JUnit 5 extension (no compile-time Spring dep).**
   `QuickPerfTestExtension` does **not** reference any Spring class — neither directly nor reflectively. Instead it discovers `PropertyResolverProvider` implementations via `ServiceLoader.load(PropertyResolverProvider.class)`, mirroring the existing `QuickPerfConfigLoader` SPI pattern (see `core/.../QuickPerfConfigsLoader` and the per-module `META-INF/services/org.quickperf.config.library.QuickPerfConfigLoader` files in `jvm-annotations`, `jfr-annotations`, `sql-annotations`).
   - The Spring impl ships from a new `spring/junit5-spring` module (artifact `quick-perf-junit5-spring`) which has `spring-test` on its compile classpath — so the call to `SpringExtension.getApplicationContext(extCtx).getEnvironment()` is a plain method call, not `Method.invoke`.
   - `ServiceLoader` results, the `Class` literals it loads, and the captured `Environment` are all effectively immutable after construction. The provider iterator is consumed once at extension-init time and cached in a `static final` `List<PropertyResolverProvider>` (safe publication via class-init semantics). Each `tryBuild(ExtensionContext)` call is invoked synchronously on the test thread.
   - The `Environment` is captured in a `final` field of an anonymous `PropertyResolver` returned by the provider (no method references — Java 7-friendly per §9 (1)).
   - The resolver is built **once** in `beforeEach` and stored on the `TestExecutionContext` that goes into `ExtensionContext.Store`. Subsequent JUnit 5 callbacks for the same test method retrieve it via `testExecutionContext.getPropertyResolver()` — no second `ServiceLoader` iteration, no second `SpringExtension.getApplicationContext(...)` call. JUnit 5's `Store` is contractually thread-safe, so `put`/`get` across callbacks (which may run on the same or different threads depending on parallelism configuration) establishes happens-before for the resolver field.

7. **Tests with no Spring context** (e.g., a JUnit 5 test without `@SpringBootTest`, or a test running without `quick-perf-junit5-spring` on the classpath).
   - **No provider on the classpath:** `ServiceLoader.load(...)` returns an empty iterator → `SystemPropertyResolver.INSTANCE`.
   - **Provider present but no Spring context for this test:** `SpringExtension.getApplicationContext(extCtx)` throws (e.g., `IllegalStateException` because no `@SpringBootTest`/`@ContextConfiguration`) → the provider catches and returns `null` → fall back to the next provider, then to `SystemPropertyResolver.INSTANCE`. The fallback path is synchronous; no race.

8. **Tests mutating `System.setProperty(...)` mid-run.**
   Pre-existing concern, unchanged. With Spring tests, the resolver reads from `Environment` first, so the migrated display properties are no longer affected by such mid-run mutations (slight improvement).

9. **Concurrent context loading for distinct property sets.**
   Spring's coarse `synchronized (this.contextCache)` serializes loading. This is a Spring cost, not a QuickPerf race. We document it for users so they know that varying `properties=` widely will reduce parallelism.

10. **Profile-driven `application-{profile}.{properties|yml}`.**
    Activated profiles change the property sources but not the cache-key shape — Spring handles this internally. No QuickPerf concern.

11. **`disableQuickPerf` timing — when is the resolver available?**
    For the **same-JVM Spring path**, `SpringRunnerWithQuickPerfFeatures` extends
    `SpringJUnit4ClassRunner`. By the time `methodInvoker(frameworkMethod, test)`
    is called, `createTest()` has already loaded the Spring context (Spring's
    own contract). At that moment, `getTestContextManager().getTestContext()
    .getApplicationContext().getEnvironment()` is safe to read. ✅
    For the **fork-parent path**, `QuickPerfSpringRunner.runChild` calls plain
    `super.runChild(...)` (line 216) and `createTest()` (line 188-197) routes
    to `BlockJUnit4ClassRunner.super.createTest()` — Spring's context is **not**
    loaded in the parent. The parent therefore uses
    `SystemPropertyResolver.INSTANCE`. The child JVM loads Spring and
    re-evaluates with its own resolver. (See §3.3 (13).)
    *Mitigation:* the same-JVM path builds the resolver in
    `SpringRunnerWithQuickPerfFeatures.methodInvoker(...)` and passes it to
    a new overload on `QuickPerfJUnitRunner.methodInvoker(..., resolver)`,
    which forwards it to `TestExecutionContext.buildFrom(...)`. The resolver
    is then retained on the resulting context (§4.2); subsequent phases
    (`withAfters` → `MainJvmAfterJUnitStatement` → evaluator → verifiers)
    pull it from `testExecutionContext.getPropertyResolver()` without any
    extra plumbing.

12. **`disableQuickPerf` and the runner-level short-circuit (`quickPerfFeaturesAreDisabled` field).**
    `QuickPerfSpringRunner` has a per-runner-instance `quickPerfFeaturesAreDisabled` field (line 51) used as an early skip. This field is driven by **class-level** signals (e.g., `@DisableQuickPerf` annotation on the test class) — *not* by the system property. We do **not** wire the Spring property into this field because:
    - The field is checked outside `methodInvoker`, before Spring's context is guaranteed to be loaded.
    - The Spring property is naturally a per-test-method runtime decision; the existing per-method `quickPerfIsDisabled` check is the right place.
    - Touching the runner-level field would create a timing dependency that we want to avoid.
    Net effect: with `@SpringBootTest(properties = "disableQuickPerf=true")`, the runner still goes through its normal path; `TestExecutionContext.quickPerfDisabled` becomes `true`; downstream code (`PerfIssuesEvaluator`, recorders, reporter) sees the disabled state and no-ops. Functionally identical to today's `-DdisableQuickPerf=true` behavior, just routed differently.

13. **`disableQuickPerf` and forked-JVM mode (`@HeapSize`, `@Xmx`, …).**
    With Spring-only `disableQuickPerf=true` + a forking annotation, the parent
    JVM **still forks** because Spring's context is not loaded in the parent's
    fork-decision path (see §3.3 (11)). The child JVM loads Spring, evaluates
    `disableQuickPerf=true`, sets `TestExecutionContext.quickPerfDisabled =
    true`, and runs the test as a no-op for QuickPerf assertions.
    - **`@SpringBootTest(properties = "disableQuickPerf=true") @HeapSize(...)`:**
      Parent forks → child evaluates Spring resolver → child no-ops QuickPerf.
      Test passes. **The fork cost is paid.** Users who need to skip the fork
      entirely should use `-DdisableQuickPerf=true` (system-only, evaluated in
      both JVMs without Spring). This is documented as a known limitation.
    - **`@SpringBootTest(properties = "disableQuickPerf=false") @HeapSize(...)`:**
      Parent forks → child evaluates Spring resolver → child runs the test
      normally with QuickPerf assertions. ✅
    *Implication:* we do **not** propagate the Spring-resolved `disableQuickPerf`
    value to the fork via a `-D` flag. Each JVM evaluates from its own Spring
    context (or falls back to system property). The fork-parent decision is
    based solely on the presence of forking annotations, not on Spring properties.

14. **Two parallel test classes with opposite `disableQuickPerf` values.**
    Each class has its own `MergedContextConfiguration` cache key (`propertySourceProperties` differ) → different `ApplicationContext` → different `Environment`. Each runner instance builds its own resolver per `methodInvoker` call. No shared state, no race. ✅

15. **`disableQuickPerf` set to an empty/blank value or upper-case `"TRUE"`.**
    Today's behavior is `Boolean.valueOf(System.getProperty("disableQuickPerf"))`,
    which is **case-insensitive** (`"TRUE"` → `true`, `"True"` → `true`). The
    plan uses `Boolean.parseBoolean(value)` everywhere, which is also
    case-insensitive — behavior preserved. `Boolean.parseBoolean(null)` and
    `Boolean.parseBoolean("")` both return `false`, matching today's behavior.
    (The earlier `pr-review-summary.txt` flagged the empty-string case as a
    "suggestion" — pull-not-push inherits Spring's normal handling.)

### 3.4 What we explicitly do NOT do (and why)

| Anti-pattern                                                                       | Why we reject it                                                                                                                                                                                                                                                                                              |
| ---------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Bridge Spring property → `System.setProperty()`** (`QuickPerfSpringPropertiesBridge`) | Documented in `pr-review-summary.txt` (5 findings): JVM-global mutable state leaks across tests, races under Surefire `parallel=all`, **silently disables QuickPerf for all subsequent tests** if `disableQuickPerf` leaks, overwrites explicit `-D` flags, throws `SecurityException` under SecurityManager. The pull model (`PropertyResolver` reads on demand) avoids all of these by never mutating JVM-global state. |
| Store resolver in a `ThreadLocal`                                                  | Surefire thread reuse → leak between tests; need explicit cleanup in `finally`; harder to reason about. Also forces every caller into a global hidden contract.                                                                                                                                                |
| **Setter** on `TestExecutionContext` (i.e., a `setPropertyResolver(...)` callable after construction) | Would make the context object mutable post-construction and allow races if two parts of the pipeline write/read on different threads. **Instead, the resolver is a single-assignment private field** assigned once by `buildFrom(...)` and exposed via a getter only (§4.2). The context is per-test-method, so each test thread builds its own logically-immutable instance. This matches how `perfAnnotations`, `workingFolder`, and `quickPerfDisabled` are already handled on the same class. |
| Static field on `PerfIssuesEvaluator`                                              | Cross-test contamination — Test A's resolver leaks to Test B running on another thread.                                                                                                                                                                                                                       |
| Shared `propertyResolver` cached on a singleton record                             | Every singleton becomes a hazard. Per-instance fields on per-test records are fine; per-instance fields on shared singletons (`SqlExecutions.NONE`) need the guard in §3.3 (1).                                                                                                                              |
| Auto-binding QuickPerf properties via `@ConfigurationProperties`                  | Couples QuickPerf core to Spring; breaks non-Spring usage; not needed since `Environment.getProperty(String)` is already a unified API.                                                                                                                                                                      |
| Wire the Spring `disableQuickPerf` into the runner-level `quickPerfFeaturesAreDisabled` field | The field is checked outside `methodInvoker`, before the Spring context is guaranteed to be loaded. Driving it from Spring would create a timing race. Per §3.3 (12), the per-method `quickPerfIsDisabled` check is the right place.                                                                          |
| Propagate Spring-resolved `disableQuickPerf` to the fork JVM via `-D`              | Per §3.3 (13), each JVM evaluates from its own Spring context (or falls back to system property). Adding a `-D` propagation step would re-introduce the bridge problem on the fork side.                                                                                                                     |

---

## 4. Component changes

> **Java 7 constraint.** All new types live in `core/` (which targets
> `maven.compiler.source=1.7`) or in `spring/` modules (which also target 1.7).
> No default methods on interfaces, no lambdas, no method references. Static
> helpers and anonymous classes only.

### 4.1 Core — NEW

- `core/.../PropertyResolver.java` — single-abstract-method interface, Java-7 safe.
  This is the **only new interface** introduced by this plan; it abstracts
  "Spring `Environment` vs `System.getProperty`" so neither caller has to know
  the other.
  ```java
  public interface PropertyResolver {
      String resolve(String propertyName);
  }
  ```
- `core/.../SystemPropertyResolver.java`
  ```java
  public final class SystemPropertyResolver implements PropertyResolver {
      public static final SystemPropertyResolver INSTANCE = new SystemPropertyResolver();
      private SystemPropertyResolver() {}
      @Override public String resolve(String name) { return System.getProperty(name); }
  }
  ```

> **No new `PropertyAwareVerifier` capability interface.** The existing
> `VerifiablePerformanceIssue` SPI is modified directly to accept the resolver
> as a third parameter (§4.2). This is a public SPI signature break — see
> §4.2 for the migration impact and §9 for rationale.

### 4.2 Core — MODIFIED

- `core/.../TestExecutionContext.java`
  - Add overloads accepting `PropertyResolver`:
    ```java
    public static TestExecutionContext buildFrom(QuickPerfConfigs configs,
                                                 Method testMethod,
                                                 long runnerAllocationOffset,
                                                 PropertyResolver resolver) { ... }

    public static TestExecutionContext buildNewJvmFrom(QuickPerfConfigs configs,
                                                       Method testMethod,
                                                       PropertyResolver resolver) { ... }
    ```
    Both delegate internally to a private constructor that calls `quickPerfIsDisabled(perfAnnotations, resolver)`.
  - Existing 3-arg `buildFrom` and 2-arg `buildNewJvmFrom` stay as overloads that pass `SystemPropertyResolver.INSTANCE` (binary compatibility for non-Spring callers).
  - `quickPerfIsDisabled(Annotation[] perfAnnotations, PropertyResolver resolver)` reads via:
    ```java
    PropertyResolver r = (resolver != null ? resolver : SystemPropertyResolver.INSTANCE);
    String raw = r.resolve("disableQuickPerf");
    boolean disabledFromProp = (raw != null)
            ? Boolean.parseBoolean(raw)
            : SystemProperties.QUICK_PERF_DISABLED.evaluate();
    ```
    Note the explicit `null`-check on `raw`: when the resolver returns `null`
    (Spring `Environment` doesn't have it), we fall back to the system property
    via the existing `SystemProperties.QUICK_PERF_DISABLED.evaluate()`. This
    preserves today's `-DdisableQuickPerf=true` behavior exactly.
  - The existing annotation-based check (`@DisableQuickPerf` on the method/class) is unchanged — Spring property is OR'd with annotation just like today's `-D`.
  - **New field on `TestExecutionContext`:**
    `private PropertyResolver propertyResolver;` with a public getter
    `getPropertyResolver()` and **no public setter**. Assigned once by
    `buildFrom(...)` (normalised to `SystemPropertyResolver.INSTANCE` when
    the resolver argument is `null`) on **every** code path — including the
    early-disabled-return branch (today's `if (quickPerfIsDisabled(...)) {
    quickPerfDisabled = true; return testExecutionContext; }`) — so
    downstream consumers never observe `null`. This matches the existing
    assign-once-in-factory pattern of `perfAnnotations`, `workingFolder`,
    `quickPerfDisabled`, etc. The context object is per-test-method, built
    on the calling thread, never shared with another test, so there is no
    cross-thread mutation. `TestExecutionContext` is **not `Serializable`**
    (verified: no `implements Serializable` on the class declaration), so
    unlike `SqlExecutions` (§3.3 (2)) there is no `transient` concern here.

- `core/.../issue/VerifiablePerformanceIssue.java` — **public SPI signature
  change.** Modify the single abstract method to accept a `PropertyResolver`:
  ```java
  public interface VerifiablePerformanceIssue<A extends Annotation, V extends PerfMeasure> {

      VerifiablePerformanceIssue NO_VERIFIABLE_PERF_ISSUE = new VerifiablePerformanceIssue() {
          @Override
          public PerfIssue verifyPerfIssue(Annotation annotation, PerfMeasure measure,
                                           PropertyResolver propertyResolver) {
              return PerfIssue.NONE;
          }
      };

      PerfIssue verifyPerfIssue(A annotation, V measure, PropertyResolver propertyResolver);

  }
  ```
  - This is a **breaking change for any external implementation of
    `VerifiablePerformanceIssue`**. All ~30 implementations in this repository
    (across `core`, `jvm`, `sql`, `jfr`) get a mechanical signature update:
    add the third parameter; most of them ignore it. Five implementations
    (the SQL display ones plus `DisplayJvmProfilingValueVerifier`) actually
    read from the resolver.
  - Convention: when a verifier doesn't need the resolver, the parameter name
    is `propertyResolver` and it is simply unused. We do **not** introduce a
    `@SuppressWarnings("unused")` annotation since the parameter is declared
    in the interface and overrides are always considered "used" by IDEs.
  - `NO_VERIFIABLE_PERF_ISSUE` constant and any inline anonymous classes
    elsewhere in the codebase get the same parameter added.

- `core/.../SystemProperties.java` / `SystemProperty` — **no interface changes**.
  Verifiers and `SqlExecutions.format(...)` resolve the boolean inline using a
  small Java-7-safe pattern (no default methods, no `evaluateFrom` refactor):
  ```java
  // helper used by every verifier that needs it and by SqlExecutions.format(...)
  private static boolean simplifiedSqlDisplay(PropertyResolver resolver) {
      if (resolver != null) {
          String raw = resolver.resolve("limitQuickPerfSqlInfoOnConsole");
          if (raw != null) return Boolean.parseBoolean(raw);
      }
      return SystemProperties.SIMPLIFIED_SQL_DISPLAY.evaluate();
  }
  ```
  *Trade-off:* keeps the `SystemProperty` interface stable; each call site has
  one tiny helper. We deliberately **reject** the default-method refactor that
  appeared in an earlier draft because it requires Java 8.

- `core/.../issue/PerfIssuesEvaluator.java`
  - `evaluatePerfIssuesIfNoJvmIssue(...)` keeps its existing signature.
    It already receives `TestExecutionContext`; the resolver is pulled
    from there:
    ```java
    PropertyResolver resolver = testExecutionContext.getPropertyResolver();
    ```
    (Returns `SystemPropertyResolver.INSTANCE` for plain JUnit 4 / JUnit 5 /
    TestNG paths because `buildFrom(...)` normalises `null` to that
    singleton — §4.2.)
  - `evaluatePerfIssue(...)` passes the resolver to the verifier (no
    `instanceof` check needed — every verifier accepts it now):
    ```java
    perfIssue = verifier.verifyPerfIssue(annotation, measure, resolver);
    ```
    The resolver is never `null` at this site, so verifiers that read it
    can do so without a null check; verifiers that ignore it stay the same
    shape as today.
  - In `perfIssuesToFormatGroup(...)`: `if (perfRecord instanceof SqlExecutions) ((SqlExecutions) perfRecord).setPropertyResolver(resolver);` before returning. (`SqlExecutions` is one of the few `PerfRecord`s that needs the resolver at format time; the existing `instanceof` pattern is local to this site and not a new SPI.)

### 4.3 SQL — MODIFIED

- `sql/sql-annotations/.../SqlExecutions.java`
  - Add `private transient PropertyResolver propertyResolver;` (transient so it
    is null after deserialization in the parent JVM after a fork — see §3.3 (2)).
  - `public void setPropertyResolver(PropertyResolver r) { if (this != NONE) this.propertyResolver = r; }`
  - In `format(...)`: replace `SystemProperties.SIMPLIFIED_SQL_DISPLAY.evaluate()`
    with the helper:
    ```java
    boolean simplified = simplifiedSqlDisplay(this.propertyResolver);
    ```
  - In `filterByQueryType()`: copy `propertyResolver` to the new instance (defensive).
- `sql/sql-annotations/.../select/analysis/SelectAnalysis.java`
  - Static helper: `getNPlusOneSelectAlert(boolean simplifiedSqlDisplay)` overload; existing no-arg method delegates by passing `SystemProperties.SIMPLIFIED_SQL_DISPLAY.evaluate()`.
  - **Instance helper on inner class `SelectAnalysis.SameSelectTypesWithDifferentParamValues`**: `getSuggestionToFixIt(boolean simplifiedSqlDisplay)` overload; existing no-arg `getSuggestionToFixIt()` (line 46) delegates by passing `SystemProperties.SIMPLIFIED_SQL_DISPLAY.evaluate()`. **Required** because three verifiers (`HasSameSelectTypesWithDiffParamValuesVerifier:36`, `JdbcQueryExecutionVerifier:61`, `MaxJdbcQueryExecutionVerifier:61`) reach `SIMPLIFIED_SQL_DISPLAY` *transitively* through this instance method — without the boolean overload, those three verifiers would still call `System.getProperty(...)` regardless of any resolver passed to `verifyPerfIssue(...)`.
- `sql/sql-annotations/.../framework/JdbcSuggestion.java` — `getBatchingMessage(boolean simplifiedSqlDisplay)` static helper; the enum constant `BATCHING.getMessage()` (line 32-44) keeps its current shape and delegates to the new helper passing `SystemProperties.SIMPLIFIED_SQL_DISPLAY.evaluate()`. Verifiers call the static helper directly with the resolver-supplied boolean.
- `sql/sql-annotations/.../select/SelectNumberPerfIssueVerifier.java` — the
  signature is now `verifyPerfIssue(ExpectSelect, SelectAnalysis,
  PropertyResolver)`. Inside the method, resolve the boolean from the resolver
  and pass it to `SelectAnalysis.getNPlusOneSelectAlert(boolean)`.
- `sql/sql-annotations/.../select/MaxOfSelectsPerfIssueVerifier.java` — same pattern (calls static `SelectAnalysis.getNPlusOneSelectAlert(boolean)`).
- `sql/sql-annotations/.../select/HasSameSelectTypesWithDiffParamValuesVerifier.java` — same pattern, but calls the **instance** `sameSelectTypesWithDifferentParamValues.getSuggestionToFixIt(boolean)` overload (not the static `getNPlusOneSelectAlert(boolean)` — this verifier formats the per-instance suggestion text).
- `sql/sql-annotations/.../batch/SqlStatementBatchVerifier.java` — same pattern, reading `limitQuickPerfSqlInfoOnConsole` from the resolver and passing the boolean to the new `JdbcSuggestion.getBatchingMessage(boolean)` static helper.
- `sql/sql-annotations/.../execution/JdbcQueryExecutionVerifier.java` — **NEWLY in scope.** Today it reads `limitQuickPerfSqlInfoOnConsole` *transitively* via `sameSelectTypesWithDifferentParamValues.getSuggestionToFixIt()` at line 61 (annotation: `@ExpectJdbcQueryExecution`). Update its `verifyPerfIssue(...)` to read the boolean from the resolver and pass it to the new instance overload `getSuggestionToFixIt(boolean)` on `SameSelectTypesWithDifferentParamValues`.
- `sql/sql-annotations/.../execution/MaxJdbcQueryExecutionVerifier.java` — **NEWLY in scope.** Same indirection at line 61 (annotation: `@ExpectMaxJdbcQueryExecution`). Same fix as `JdbcQueryExecutionVerifier`.
- **All other ~25 `VerifiablePerformanceIssue` implementations** across `core`,
  `jvm`, `sql`, and `jfr` modules get a one-line mechanical change: add
  `PropertyResolver propertyResolver` as the third parameter; the parameter is
  not read. Listed for visibility (non-exhaustive — see grep output for the
  full set):
  - `core/.../time/MaxExecutionTimeVerifier.java`, `MeasureExecutionTimeReporter.java`
  - `jvm/jvm-annotations/.../allocation/{MaxHeapAllocation,NoHeapAllocation,MeasureHeapAllocation}PerfVerifier.java`
  - `jvm/jvm-annotations/.../rss/{MeasureRss,ExpectMaxRss}PerfVerifier.java`
  - `jvm/jfr-annotations/.../jmcrule/JmcRulesPerfVerifier.java`
  - `sql/sql-annotations/.../{bindparams,analyze,like,connection,statement}/*Verifier.java`
  - `sql/sql-annotations/.../{insert,update,delete,select,execution,time}/*PerfIssueVerifier.java`
  - `sql/sql-annotations/.../{select,update}/columns/*PerfIssueVerifier.java`

### 4.4 JFR — MODIFIED

- `jvm/jfr-annotations/.../jmc/value/DisplayJvmProfilingValueVerifier.java` —
  the verifier signature is now `verifyPerfIssue(ProfileJvm, JfrEventsMeasure,
  PropertyResolver)`. Inside the method, read `limitQuickPerfJvmInfoOnConsole`
  from the resolver (with the same `null` → fallback pattern as the SQL
  helpers).

### 4.5 Runners / extensions — MODIFIED

The wiring is more nuanced than a single hook on `QuickPerfSpringRunner`,
because that runner only handles the **fork-parent** path; the **same-JVM
Spring path** is owned by `SpringRunnerWithQuickPerfFeatures` which delegates
to an embedded `QuickPerfJUnitRunner`.

#### 4.5.a `QuickPerfJUnitRunner` (junit4-runner) — add a resolver-aware overload

- New overload `methodInvoker(FrameworkMethod, Object, PropertyResolver)` that
  passes the resolver to `TestExecutionContext.buildFrom(..., resolver)`. The
  resolver is then retained on the resulting `testExecutionContext`.
- **`withAfters(...)` does NOT need a new overload.** It already constructs
  `MainJvmAfterJUnitStatement` from the runner's `testExecutionContext` field
  (assigned by `methodInvoker` on the same thread), and that context now
  carries the resolver — so `MainJvmAfterJUnitStatement` reads it via
  `testExecutionContext.getPropertyResolver()` (§4.5.d).
- The existing `methodInvoker(FrameworkMethod, Object)` keeps working and
  delegates to the new overload with `SystemPropertyResolver.INSTANCE`
  (used by plain JUnit 4 + `QuickPerfJUnitRunner`).
- The existing `testExecutionContext` field on the runner is unchanged in
  shape; it's set inside `methodInvoker` as today.

#### 4.5.b `SpringRunnerWithQuickPerfFeatures` (junit4-spring5/4/3) — wire Spring resolver into the embedded `QuickPerfJUnitRunner`

```java
@Override
protected Statement methodInvoker(FrameworkMethod m, Object test) {
    PropertyResolver r = buildResolverFromTestContext();
    return quickPerfJUnitRunner.methodInvoker(m, test, r);
}

@Override
public Statement withAfters(FrameworkMethod m, Object test, Statement next) {
    // No resolver wiring needed here: methodInvoker (which JUnit 4 calls
    // before withAfters on the same thread, see BlockJUnit4ClassRunner.
    // methodBlock) already wired the resolver into the runner's
    // testExecutionContext field via TestExecutionContext.buildFrom.
    Statement quickPerfStatement = quickPerfJUnitRunner.withAfters(m, test, next);
    return super.withAfters(m, test, quickPerfStatement);
}

private PropertyResolver buildResolverFromTestContext() {
    try {
        final org.springframework.core.env.Environment env =
                getTestContextManager().getTestContext()
                        .getApplicationContext().getEnvironment();
        return new PropertyResolver() {                // anonymous class — Java 7
            @Override public String resolve(String name) { return env.getProperty(name); }
        };
    } catch (Exception e) {
        return SystemPropertyResolver.INSTANCE;
    }
}
```

By the time `methodInvoker` and `withAfters` run, Spring's
`SpringJUnit4ClassRunner.createTest()` has loaded the application context (via
the standard `SpringJUnit4ClassRunner` lifecycle this class extends).

#### 4.5.c `QuickPerfSpringRunner` (junit4-spring5/4/3) — fork-parent only

- The fork-parent path (`testMethodToBeLaunchedInASpecificJvm == true && !TEST_CODE_EXECUTING_IN_NEW_JVM`) lands in `methodInvoker(...)` line 86–92, which calls `buildNewJvmFrom(...)`. Spring's context is **not** loaded in this path (see §3.3 (11)). Use `SystemPropertyResolver.INSTANCE`:
  ```java
  testExecutionContext = TestExecutionContext.buildNewJvmFrom(
          quickPerfConfigs, testMethod, SystemPropertyResolver.INSTANCE);
  ```
- The same-JVM main path and the child-JVM path both reach the `buildFrom(...)` call at lines 95–97. In both cases, the test is **actually executed** by the embedded `springRunnerWithQuickPerfFeatures` (line 103) or `QUICK_PERF_SPRING_RUNNER_FOR_SPECIFIC_JVM` (line 100), which builds its own `TestExecutionContext` with the proper Spring resolver inside the embedded `QuickPerfJUnitRunner`. The `testExecutionContext` set on line 95 is only consumed by the fork-parent's `withAfters` branch (line 130–136), which is reached only via the line-86–92 path that uses `buildNewJvmFrom` and never falls through to line 95. Therefore line 95 can pass `SystemPropertyResolver.INSTANCE` safely:
  ```java
  testExecutionContext = TestExecutionContext.buildFrom(quickPerfConfigs,
          testMethod, runnerAllocationOffset, SystemPropertyResolver.INSTANCE);
  ```
  No Spring resolver is needed at this site because the **authoritative** `disableQuickPerf` evaluation happens inside the embedded `QuickPerfJUnitRunner.methodInvoker(..., resolver)` call (§4.5.b), which is what actually drives the test execution.
- `quickPerfFeaturesAreDisabled` field stays as-is (class-level annotation only — see §3.3 (12)).

#### 4.5.d `MainJvmAfterJUnitStatement` (junit4-runner)

- **No new constructor parameter.** It already holds a `final TestExecutionContext`
  reference (per-test-method, captured by the JUnit `Statement` chain). The
  resolver is read from that context when needed:
  ```java
  evaluator.evaluatePerfIssuesIfNoJvmIssue(testAnnotationConfigs,
                                           testExecutionContext, jvmOrTestIssue);
  // evaluator pulls testExecutionContext.getPropertyResolver() internally.
  ```
- Same-thread invariant: in JUnit 4, `methodInvoker(...)` (which assigns the
  runner's `testExecutionContext` field) and `withAfters(...)` (which
  constructs `MainJvmAfterJUnitStatement` from that field) run sequentially
  on the same worker thread inside `BlockJUnit4ClassRunner.methodBlock`, so
  the resolver assignment is visible without further synchronization.

#### 4.5.e `QuickPerfTestExtension` (junit5-extension) — SPI-based, **no reflection**

`QuickPerfTestExtension` lives in `junit5/junit5-extension` (artifact `quick-perf-junit5`) which intentionally does **not** depend on Spring. Rather than reach into Spring via `Class.forName` + `Method.invoke`, it loads provider implementations from the JUnit 5 module classpath using `java.util.ServiceLoader`, mirroring the repo's existing `QuickPerfConfigLoader` SPI.

**New SPI in `junit5-extension`** (package `org.quickperf.junit5.spi`):

```java
package org.quickperf.junit5.spi;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.quickperf.config.PropertyResolver; // defined in core (§4.1)

public interface PropertyResolverProvider {
    /**
     * @return a PropertyResolver for this test method, or {@code null}
     *         if this provider does not apply (e.g., no Spring context).
     *         Implementations MUST NOT throw on the "not applicable" case.
     */
    PropertyResolver tryBuild(ExtensionContext extensionContext);
}
```

In `QuickPerfTestExtension`:

```java
// Loaded once, per-classloader. Safe publication via class-init.
private static final java.util.List<PropertyResolverProvider> PROVIDERS =
        loadProviders();

private static java.util.List<PropertyResolverProvider> loadProviders() {
    java.util.List<PropertyResolverProvider> list = new java.util.ArrayList<>();
    for (PropertyResolverProvider p :
            java.util.ServiceLoader.load(PropertyResolverProvider.class)) {
        list.add(p);
    }
    return java.util.Collections.unmodifiableList(list);
}

private PropertyResolver buildResolverFromExtensionContext(ExtensionContext ctx) {
    for (PropertyResolverProvider p : PROVIDERS) {
        PropertyResolver r = p.tryBuild(ctx);
        if (r != null) return r;
    }
    return SystemPropertyResolver.INSTANCE;
}
```

In `beforeEach(ExtensionContext)`:

```java
PropertyResolver resolver = buildResolverFromExtensionContext(extensionContext);
TestExecutionContext ctx = TestExecutionContext.buildFrom(quickPerfConfigs,
        extensionContext.getRequiredTestMethod(), junit5AllocationOffset, resolver);
extensionContext.getStore(NAMESPACE).put(TestExecutionContext.class, ctx);
// The resolver is now retained on `ctx` (single-assignment private field,
// no public setter — §4.2). Downstream callbacks retrieve `ctx` from the
// thread-safe Store and call ctx.getPropertyResolver() — no second
// ServiceLoader iteration, no second Spring lookup. See §3 invariant (2).
```

In `interceptTestMethod` / `interceptTestTemplateMethod` / `interceptDynamicTest` (and any other site that calls the evaluator), the resolver is **not** rebuilt. The `TestExecutionContext` retrieved from `ExtensionContext.Store` already carries it, and `PerfIssuesEvaluator.evaluatePerfIssuesIfNoJvmIssue(...)` pulls it from the context internally (§4.2).

`processJvmOrTestIssue(...)` keeps its existing signature:

```java
private void processJvmOrTestIssue(JvmOrTestIssue jvmOrTestIssue,
                                   TestExecutionContext testExecutionContext,
                                   ExtensionContext extensionContext) throws Throwable {
    SetOfAnnotationConfigs testAnnotationConfigs = quickPerfConfigs.getTestAnnotationConfigs();
    Collection<PerfIssuesToFormat> groupOfPerfIssuesToFormat
            = perfIssuesEvaluator.evaluatePerfIssuesIfNoJvmIssue(testAnnotationConfigs,
                    testExecutionContext, jvmOrTestIssue);
    testExecutionContext.cleanResources();
    quickPerfReporter.report(jvmOrTestIssue, groupOfPerfIssuesToFormat, testExecutionContext);
}
```

- **Why retain the resolver on `TestExecutionContext` instead of rebuilding per callback?** `TestExecutionContext` is already in the `ExtensionContext.Store` and already flows to every callback that needs it. Carrying the resolver alongside the context (one extra single-assignment field) is structurally identical to how `perfAnnotations`, `quickPerfDisabled`, `workingFolder`, etc. are already retained. Rebuilding per callback would re-run the `ServiceLoader` iteration and the `SpringExtension.getApplicationContext(ctx)` lookup; even though both are O(1) hash lookups, that's plumbing without a benefit and adds a "callback forgot to rebuild" failure mode.
- **Why is this still parallel-safe?** Each JUnit 5 test method has its own `ExtensionContext` and its own `TestExecutionContext` in the per-method store; two parallel test methods cannot share either. JUnit 5's `Store` is contractually thread-safe, so the `put` in `beforeEach` and the `get` in any later callback establish a happens-before relationship — even if JUnit 5 dispatches the interceptor on a different thread under `@Execution(CONCURRENT)`. The `PROVIDERS` list is immutable after class-init (safe publication). The wrapped Spring `Environment` is read-only after refresh (§3 invariant (3)). The `propertyResolver` field on the context is assigned once in `buildFrom` and never reassigned (§4.2).
- No Spring class is referenced from `junit5-extension` — keeping the artifact Spring-free for plain JUnit 5 users.

#### 4.5.e′ `quick-perf-junit5-spring` (NEW publishable module under `spring/junit5-spring`)

A new sibling to the existing `junit4-spring3` / `junit4-spring4` / `junit4-spring5` modules, but for JUnit 5. It is the **only** place in the codebase that mentions both JUnit 5 and Spring at compile time, so the `SpringExtension` call is a plain method call.

- **Artifact:** `org.quickperf:quick-perf-junit5-spring`
- **Compile deps:** `quick-perf-junit5` (the `junit5-extension` artifact), `spring-test` (provided), `junit-jupiter-api` (provided).
- **JDK target:** 1.8 (JUnit 5 + Spring 5/6 minimum).
- **Registered with the parent `spring/pom.xml` `<modules>` list** (next to `junit4-spring5`).

**Single class** (`org.quickperf.spring.junit5.SpringPropertyResolverProvider`):

```java
package org.quickperf.spring.junit5;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.quickperf.config.PropertyResolver;
import org.quickperf.junit5.spi.PropertyResolverProvider;
import org.springframework.core.env.Environment;
import org.springframework.test.context.junit.jupiter.SpringExtension;

public final class SpringPropertyResolverProvider implements PropertyResolverProvider {
    @Override
    public PropertyResolver tryBuild(ExtensionContext ctx) {
        final Environment env;
        try {
            env = SpringExtension.getApplicationContext(ctx).getEnvironment();
        } catch (Throwable ignored) {
            // No Spring context for this test (e.g., no @SpringBootTest).
            return null;
        }
        return new PropertyResolver() {  // anonymous class — Java-7-safe per §9 (1)
            @Override
            public String resolve(String name) { return env.getProperty(name); }
        };
    }
}
```

**SPI registration:**

```
spring/junit5-spring/src/main/resources/
    META-INF/services/org.quickperf.junit5.spi.PropertyResolverProvider
```

Contents (one line):

```
org.quickperf.spring.junit5.SpringPropertyResolverProvider
```

Users opt in by adding `quick-perf-junit5-spring` to their test classpath. No code change needed — `@QuickPerfTest` keeps working unchanged because `QuickPerfTestExtension` discovers the provider at runtime via `ServiceLoader`.

The integration tests under `spring/junit5-spring-boot-3-test/` (already test-only, see its `pom.xml`) gain a `<scope>test</scope>` dependency on `quick-perf-junit5-spring` to exercise the SPI path end-to-end.

#### 4.5.f Plain runners / listeners

- `junit4/junit4-runner/.../QuickPerfJUnitRunner.java` — internal calls already
  use the new overloads with `SystemPropertyResolver.INSTANCE`.
- `testng/testng-listener/.../QuickPerfTestNGListener.java` — pass
  `SystemPropertyResolver.INSTANCE` to `TestExecutionContext.buildFrom(...)` and
  to the evaluator.
- `testng/testng-sql-listener/.../QuickPerfSqlTestNGListener.java` — same.

> **TestNG-Spring property resolution is OUT OF SCOPE for v1** (see §7).
> TestNG listeners always use `SystemPropertyResolver.INSTANCE`. Existing
> TestNG + `@SpringBootTest(properties=…)` tests that exercise `disableQuickPerf`
> or display-control properties via Spring will continue to require `-D` flags
> for those properties, or be removed/skipped in this iteration.

---

## 5. Spring `Environment` precedence (what users observe)

Per Spring Framework reference (TestPropertySource): test property sources have
higher precedence than OS env / JVM system properties / app-declared
`@PropertySource`s, and inlined properties beat file-based ones. **Crucially,
`@DynamicPropertySource` has higher precedence than `@TestPropertySource`.**
With Spring Boot, the effective ordering for users (first wins):

1. `@DynamicPropertySource`
2. `@TestPropertySource(properties = ...)` and `@SpringBootTest(properties = ...)` — *Inlined Test Properties*
3. `@TestPropertySource(locations = ...)`
4. Java system properties (`-D...`)
5. OS environment variables
6. `application-{profile}.{properties|yml}` (active profile)
7. `application.{properties|yml}` (default)
8. Defaults

Users get the natural override behavior, e.g., setting `limitQuickPerfSqlInfoOnConsole=true` in `application.yml` and overriding it on a single test class via `@SpringBootTest(properties = "limitQuickPerfSqlInfoOnConsole=false")`.

For non-Spring tests we keep raw `System.getProperty(...)`, which also respects `-D` flags.

> **Note on test coverage.** §6 only adds tests for the high-level "Spring
> source overrides system" guarantee (e.g., `application.yml` value is read
> when no `-D` is set; `@SpringBootTest(properties=…)` overrides
> `application.yml`). We do **not** add tests for every internal Spring
> precedence rule — those belong to Spring, and committing to test them would
> couple QuickPerf to Spring's internal ordering across versions.

---

## 6. Test strategy

- The existing test classes already exist but currently fail (`Plan.md` line 18 — issue #214):
  - **Display-control properties (JUnit 4 + JUnit 5 only — TestNG variants out of scope, see §7):**
    - `SpringBoot2JUnit4LimitSqlDisplayWithSpringBootTestProperties.java`
    - `SpringBoot3JUnit5LimitSqlDisplayWithSpringBootTestProperties.java`
    - `application-limitsqlprops.properties` / `application-limitsqlyml.yml` variants for JUnit 4 / JUnit 5
  - **`disableQuickPerf` (the new addition; JUnit 4 + JUnit 5 only):**
    - `SpringBoot2JUnit4DisableQuickPerfWithSpringBootTestProperties.java`
    - JUnit 5 variant under `junit5-spring-boot-3-test/`
    - Forked-JVM variant: tests that combine `@SpringBootTest(properties = "disableQuickPerf=…")` with `@HeapSize`/`@Xmx`. **Important:** with Spring-only `disableQuickPerf=true` the parent JVM still forks (see §3.3 (13)); the child evaluates Spring and no-ops QuickPerf assertions — so the test passes, just with the fork cost.
    - `application-disablequickperfprops.properties` / `application-disablequickperfyml.yml` variants
  - **TestNG variants — disabled in source via `@Test(enabled = false)` (NOT `@Ignore`).**
    The `SpringBoot3Tests` Maven profile in `spring/pom.xml:61-69` auto-activates
    on JDK 17+, pulling `testng-spring-boot-3-test` into the reactor for any
    `mvn clean install` on a modern JDK. Without explicit disabling, six
    Spring-property-driven TestNG tests would still execute and fail their
    inner assertion `assertThat(testsResult.getNumberOfPassedTest()).isOne()`,
    breaking CI on JDK 17.

    **Concrete action — apply `@Test(enabled = false)` to every test method in:**
    - **Outer aggregator classes** (the `@Test` methods that invoke
      `QuickPerfTest.test(...)` and assert a single passed inner test):
      - `spring/testng-spring-boot-3-test/.../SpringBoot3TestNGDisableQuickPerfTest.java` — 3 `@Test` methods at lines 25, 40, 55.
      - `spring/testng-spring-boot-3-test/.../SpringBoot3TestNGLimitSqlDisplayTest.java` — 3 `@Test` methods at lines 25, 45, 65.
    - **Inner sub-test classes** (TestNG `@Test` methods that the aggregator
      invokes; disable defensively in case Surefire discovers them as standalone):
      - `disablequickperf/SpringBoot3TestNGDisableQuickPerfWithApplicationProperties.java`
      - `disablequickperf/SpringBoot3TestNGDisableQuickPerfWithApplicationYml.java`
      - `disablequickperf/SpringBoot3TestNGDisableQuickPerfWithSpringBootTestProperties.java`
      - `limitsqldisplay/SpringBoot3TestNGLimitSqlDisplayWithApplicationProperties.java`
      - `limitsqldisplay/SpringBoot3TestNGLimitSqlDisplayWithApplicationYml.java`
      - `limitsqldisplay/SpringBoot3TestNGLimitSqlDisplayWithSpringBootTestProperties.java`

    **Required code comment** on every disabled `@Test`:
    ```java
    // TODO QuickPerf v2 (issue #<TRACKING_ISSUE_NUMBER>): TestNG-Spring property
    // resolution. Re-enable when TestNG listener gains Spring-context-aware
    // PropertyResolver wiring. See Plan-spring-property-resolution.md §7.
    ```
    The tracking issue number is filled in **before this plan is merged** (see
    §8 phase 0 below) — without a tracked issue, disabled tests rot
    indefinitely.

    **What NOT to do (and why):**
    - **Do not use `@Ignore`** — it is a JUnit 4 annotation; `org.junit.Ignore`
      is not on the `testng-spring-boot-3-test` classpath. Even if it were,
      TestNG ignores it. Misreading the previous draft's "`@Ignore`-equivalent"
      as literal `@Ignore` produces compile errors.
    - **Do not delete the test classes** — the `@SpringBootTest`,
      `application-*.properties`, and `application-*.yml` resources they reference
      are reused verbatim in v2 (the JUnit 4 / JUnit 5 variants of the same
      tests already share these resources via test-resource inheritance). Deletion
      forces re-creation when v2 lands.
    - **Do not convert them to use `-D` system properties** — that changes the
      test semantics from "Spring resolves the property" to "system property
      resolves it", which is a different code path. Converted tests would need
      to be reverted when v2 lands, and they no longer cover the v2 use case
      they exist to prove.
  - **CI smoke-test invariant.** Add a step to the existing CI workflow (or
    document in `CONTRIBUTING.md`) that `mvn -pl spring/testng-spring-boot-3-test
    -am verify` must be green on JDK 17. Catches any future revert that
    re-enables the disabled tests prematurely. This is the only build invariant
    that detects the JDK-17 profile-activation regression at the workflow level.
- After this plan they should all pass (excluding the moved-to-v2 TestNG cases).
- **Parallel-safety regression strategy.** The previous bridge bug
  (`pr-review-summary.txt`) manifested under (a) concurrent test execution
  on multiple worker threads AND (b) Surefire worker-thread reuse across
  sequential tests, both via JVM-global mutation by `System.setProperty(...)`.
  The pull-only design eliminates (b) by construction, but the regression
  suite must still detect (a) and the *thread-reuse* shape of (b) —
  otherwise a future refactor that reintroduces a `System.setProperty` push
  could ship undetected. **Five guardrails**, each closing one specific gap.
  A naive "two classes running concurrently with `@Test` methods asserting
  their own value" suite passes even when no concurrency happens (gaps i and
  ii) and even when the bridge-bug shape is reintroduced (gap iv) — the
  guardrails below close those gaps.

  - **(i) JUnit 4 module placement — exercise actual parallelism.** Place the
    JUnit 4 parallel-safety tests in `spring/junit4-spring-boot-test/`, which
    inherits the root `parallel=all, threadCount=5` config (root `pom.xml:109-110`).
    Do **not** place them in `spring/junit5-spring-boot-3-test/` (sets
    `<parallel>none</parallel>` at `pom.xml:144`) or
    `spring/testng-spring-boot-3-test/` (same override at `pom.xml:153`) —
    those modules execute serially regardless of root config.

  - **(ii) JUnit 5 module setup — `junit-platform.properties`.** Surefire's
    `<parallel>` setting is JUnit 4 era and ignored by Jupiter. To make
    JUnit 5 parallel-safety tests actually run concurrently in
    `junit5-spring-boot-3-test/`, add `src/test/resources/junit-platform.properties`:
    ```
    junit.jupiter.execution.parallel.enabled=true
    junit.jupiter.execution.parallel.mode.default=concurrent
    junit.jupiter.execution.parallel.mode.classes.default=concurrent
    ```
    The Surefire `<parallel>none</parallel>` override stays — it gates the
    JUnit 4 era setting and does not affect Jupiter's own parallelism.

  - **(iii) Multi-method classes — raise the concurrency floor.** Two
    single-method classes exercise at most two concurrent threads. Each
    parallel-safety test class instead has **5 `@Test` methods** with mixed
    property values (alternating expected outcomes per method); each method
    asserts its own value is observed. Any cross-method context leak fails
    an assertion regardless of which methods happened to interleave on the
    worker pool.

  - **(iv) Concurrency barrier — prove parallelism actually happened.** A
    static `CountDownLatch START_BARRIER = new CountDownLatch(N)` (where N
    is the expected concurrent test count) is decremented at the top of
    each test method, then `START_BARRIER.await(2, TimeUnit.SECONDS)` is
    awaited before the assertion runs. If the timeout fires, the test
    **fails** with "Concurrency precondition not met — parallel execution
    is not active in this module." This converts a silent loss of
    parallelism (e.g., a future Surefire/Jupiter config change) into a loud
    failure rather than a false-green run.

  - **(v) Sequential thread-reuse test — the actual bridge-bug shape.** The
    bridge-bug shape is `System.setProperty(...)` written by class A then
    observed by class B running on the same Surefire worker thread, in the
    same JVM, after A has finished. That is **sequential thread reuse**,
    not concurrency, and (i)–(iv) do not exercise it. Add a dedicated
    Surefire execution binding under `junit4-spring-boot-test/` running
    with `forkCount=1, reuseForks=true, threadCount=1` and explicit
    `<runOrder>filesystem</runOrder>` so test order is deterministic:
    - `_01_DisableQuickPerfThreadReuseTrueTest`:
      `@SpringBootTest(properties = "disableQuickPerf=true")` +
      `@ExpectSelect(0)` followed by a `SELECT` (would fail if QuickPerf
      were active) — must **pass**.
    - `_02_DisableQuickPerfThreadReuseFalseTest`:
      `@SpringBootTest(properties = "disableQuickPerf=false")` + the same
      setup — must **fail** with the QuickPerf assertion error.
    - `_03_DisableQuickPerfThreadReuseTrueAgainTest`: same as `_01`. This
      asserts that `_02`'s execution did not poison the JVM such that
      `_03` now observes a residual `disableQuickPerf` value from a
      previous test (the exact failure mode of the abandoned bridge).
    All three classes run on the same Surefire worker thread in the same
    JVM in declared order — the precise environment of the original bridge
    bug.

- **Concrete test classes for the parallel/reuse suites.**
  - `SpringBootJUnit4LimitSqlDisplayParallelTrueTest` /
    `SpringBootJUnit4LimitSqlDisplayParallelFalseTest` (5 methods each,
    in `junit4-spring-boot-test/`).
  - `SpringBootJUnit5LimitSqlDisplayParallelTrueTest` /
    `SpringBootJUnit5LimitSqlDisplayParallelFalseTest` (5 methods each,
    in `junit5-spring-boot-3-test/` alongside the new
    `junit-platform.properties`).
  - `SpringBootJUnit4DisableQuickPerfParallelTrueTest` /
    `SpringBootJUnit4DisableQuickPerfParallelFalseTest` (5 methods each).
  - `SpringBootJUnit5DisableQuickPerfParallelTrueTest` /
    `SpringBootJUnit5DisableQuickPerfParallelFalseTest` (5 methods each).
  - `_01/_02/_03_DisableQuickPerfThreadReuse...Test` (the sequential
    thread-reuse trio for guardrail (v)).
  - All assertion failures must be diagnosable: each method captures its
    own `System.out` (or uses a `TestExecutionListener`-equivalent JUnit 5
    extension) and asserts the captured output matches its expected
    value — never the sibling method's value.

- **(vi) Structural guardrail — `System.setProperty` is forbidden for these properties.**
  Add a unit test under `core/src/test/java/.../arch/NoSystemSetPropertyOnQuickPerfPropertiesTest.java`
  (a simple grep-based check; ArchUnit is not currently a dependency and we
  do not add it for this single rule):
  ```java
  // Walks the production source tree (core/, jvm/, sql/, junit4/,
  // junit5/, testng/, spring/, excluding any /src/test/) and asserts no
  // file contains:
  //   System.setProperty("disableQuickPerf", ...)
  //   System.setProperty("limitQuickPerfSqlInfoOnConsole", ...)
  //   System.setProperty("limitQuickPerfJvmInfoOnConsole", ...)
  //   System.clearProperty("...") for any of the above.
  // Failure message lists every offending file:line.
  ```
  This is the cheapest, most decisive guardrail: it catches a future
  bridge-style regression at compile-time (well, at test time, before any
  parallel-safety runtime test gets a chance to fire). It is the structural
  invariant the pull-only design relies on.

- **Why all six matter.** (i)–(iv) catch concurrent leaks where multiple
  threads run simultaneously. (v) catches sequential thread-reuse leaks —
  the actual shape of the abandoned bridge bug — which (i)–(iv) cannot
  trigger. (vi) catches any production code that reintroduces a
  `System.setProperty` push *before* runtime testing even runs, regardless
  of thread layout. Removing any one of the six leaves a class of
  regressions undetected. (vi) is the structurally strongest; (v) is the
  most behaviorally faithful to the bridge bug; (i)–(iv) are the broadest
  coverage of any future race.
- **Forked-JVM × `disableQuickPerf` test (revised expectations):**
  - `@SpringBootTest(properties = "disableQuickPerf=true") @HeapSize(...)` —
    parent forks (Spring not loaded in fork-decision path); child JVM loads
    Spring, evaluates resolver → `quickPerfDisabled=true` → child runs the test
    as a QuickPerf no-op. **Test passes.** A separate assertion may verify that
    a child JVM **was** spawned (e.g., observe `quickPerfWorkingFolder` is
    populated), documenting the known fork-cost limitation.
  - `@SpringBootTest(properties = "disableQuickPerf=false") @HeapSize(...)` —
    must fork normally; child JVM re-evaluates and runs the test with QuickPerf
    assertions active.
  - `-DdisableQuickPerf=true` (system property) `@HeapSize(...)` — parent
    short-circuits via `SystemPropertyResolver.INSTANCE` and **does not fork**.
    This documents the recommended path for users who need to skip the fork.
- Unit tests:
  - `PerfIssuesEvaluator` with a stub `VerifiablePerformanceIssue` confirms the resolver is forwarded to the verifier's `verifyPerfIssue(...)` 3rd argument.
  - `SqlExecutions.format` with `setPropertyResolver(null)` confirms the fallback to `System.getProperty`.
  - `SqlExecutions.NONE.setPropertyResolver(...)` is a no-op (assert state unchanged).
  - Forked-JVM round trip: serialize a `SqlExecutions` with a resolver set, deserialize, assert resolver is null, set a new one, format → correct output.
  - `TestExecutionContext.buildFrom(..., propertyResolver)`:
    - Resolver returns `"true"` for `disableQuickPerf` → `quickPerfDisabled == true`.
    - Resolver returns `"TRUE"` (case test) → `quickPerfDisabled == true` (`Boolean.parseBoolean` is case-insensitive — matches today's `Boolean.valueOf` behavior).
    - Resolver returns `"false"` → `quickPerfDisabled == false`.
    - Resolver returns `null` → falls back to `System.getProperty("disableQuickPerf")` via `SystemProperties.QUICK_PERF_DISABLED.evaluate()`.
    - `SystemPropertyResolver.INSTANCE` produces the same result as today's `-D` path.
- **SQL transitive-indirection regression tests** (`sql/sql-annotations/src/test/java/`).
  Three verifiers — `HasSameSelectTypesWithDiffParamValuesVerifier`,
  `JdbcQueryExecutionVerifier`, `MaxJdbcQueryExecutionVerifier` — read the
  `limitQuickPerfSqlInfoOnConsole` property *transitively* through
  `SameSelectTypesWithDifferentParamValues.getSuggestionToFixIt()`. A direct
  resolver-forwarding test (asserting `verifyPerfIssue` is called with the
  resolver) does **not** detect a future regression that drops the new
  instance overload and reverts to the no-arg call. Add one parametrised
  test per verifier that:
  1. Calls `System.clearProperty("limitQuickPerfSqlInfoOnConsole")` (Surefire
     might leave it set from a prior fixture; clear in `@Before` and restore
     in `@After`).
  2. Invokes `verifyPerfIssue(annotation, measure, resolver)` where
     `resolver.getProperty("limitQuickPerfSqlInfoOnConsole")` returns
     `"true"` (and **only** the resolver returns it — system property is
     null).
  3. Asserts the `PerfIssue` description **does not** contain the multi-line
     full-SQL block (which `simplifiedSqlDisplay=false` would emit) and
     instead matches the simplified form.
  4. Repeat with the resolver returning `"false"` and assert the full-SQL
     block **is** present.
  Without these three tests the original "Three SQL annotations silently
  ignore the new Spring resolution" regression can be reintroduced by any
  future refactor that removes either the static
  `getNPlusOneSelectAlert(boolean)` overload or the instance
  `SameSelectTypesWithDifferentParamValues.getSuggestionToFixIt(boolean)`
  overload — the test suite would catch the revert at compile time (the
  test source references the boolean overload directly) or at runtime (the
  assertion fires when the indirection re-reads `System.getProperty(...)`
  instead of the resolver).

---

## 7. Out of scope for this iteration

- **`quickPerfWorkingFolder`** stays system-only. It is set by `NewJvmTestLauncher` as a `-D` flag passed to the child JVM. The child reads it before any Spring context is initialized — coordinating Spring property resolution at that point would require a chicken-and-egg solution.
- **`quickPerfToExecInASpecificJvm`** stays system-only. Same reason: it identifies the child JVM during fork bootstrap, before Spring is involved.
- **TestNG-Spring native support for `Environment` resolution.** TestNG
  listeners (`QuickPerfTestNGListener`, `QuickPerfSqlTestNGListener`) build
  `TestExecutionContext` with no Spring context access today. Wiring
  Spring context retrieval through TestNG would require a TestNG-side hook
  similar to JUnit 4's `TestContextManager` integration and is materially
  larger than the JUnit 4/5 changes. TestNG callers always use
  `SystemPropertyResolver.INSTANCE`. Existing TestNG + Spring property tests
  are disabled in source via `@Test(enabled = false)` for v1 (see §6),
  with a `// TODO QuickPerf v2 (issue #<N>)` marker on every disabled
  method. **A v2 tracking issue must be opened on GitHub before this plan
  is merged**, and its number substituted into the TODO comments. Without
  a tracked issue, the disabled tests will rot.
- **Skipping the fork in the parent JVM for Spring-only `disableQuickPerf=true`.**
  In the JUnit 4 fork-parent path (`QuickPerfSpringRunner.runChild` → plain
  `BlockJUnit4ClassRunner.super.runChild` → `BlockJUnit4ClassRunner.createTest`),
  Spring's `ApplicationContext` is **not** loaded before the fork decision.
  Forcing a Spring context load there would be a non-trivial design change
  (parent would pay full Spring startup cost for every forked test, even when
  the test is not disabled). For v1, the parent forks unconditionally on
  forking annotations; the child JVM evaluates `disableQuickPerf` from its own
  Spring context. Users who need to avoid the fork cost should use
  `-DdisableQuickPerf=true` (documented).
- `SpringRunnerWithQuickPerfFeatures` rework beyond the resolver wiring
  described in §4.5.b.

---

## 8. Implementation order (phases)

> **Atomic-commit invariant.** Phases **1, 3, and 4 are not separable** — the
> codebase will not compile in any intermediate state between them:
>
> - Phase 1 changes the `VerifiablePerformanceIssue.verifyPerfIssue(A, V)`
>   SPI signature to add `PropertyResolver`. The instant this lands, every
>   `implements VerifiablePerformanceIssue` site (~30 production verifiers,
>   the `NO_VERIFIABLE_PERF_ISSUE` anonymous constant, and 5 test-side
>   direct callers) becomes a compile error.
> - Phase 3 updates `PerfIssuesEvaluator:191`'s call to pass the resolver
>   as the 3rd argument. Without phase 1 it is a compile error against the
>   old 2-arg interface; without phase 4 the verifier implementations don't
>   accept the new shape.
> - Phase 4 mechanically updates the ~30 verifier implementations + the
>   anonymous constant + the 5 test-code direct callers. Without phase 1
>   the new parameter is rejected (no such interface method); without
>   phase 3 the evaluator is still calling the old shape.
>
> **Implication:** phases 1, 3, 4 must land as a **single commit/PR**.
> Phase 2 is independent (adds a field on `TestExecutionContext` with no
> SPI implications) and lands first. Phases 5+ build on the resulting
> green tree.

0. **prerequisite — open v2 tracking issue.** Before the first code commit, open a GitHub issue titled "TestNG-Spring native property resolution (v2)" referencing this plan's §7 entry. **Substitute the resulting issue number** into (a) every `// TODO QuickPerf v2 (issue #<N>)` comment placed on disabled TestNG tests in §6 and (b) the README compatibility-matrix row added in phase 12. No source-code change in this phase — purely a tracking artifact, but it must exist before phases 1+ ship so the disabled tests have an actionable forward-pointer.

1. **core-spi** — add `PropertyResolver` and `SystemPropertyResolver`. **Modify** `VerifiablePerformanceIssue.verifyPerfIssue(A, V)` → `verifyPerfIssue(A, V, PropertyResolver)` and update the `NO_VERIFIABLE_PERF_ISSUE` constant accordingly. **No** changes to `SystemProperty` interface (avoids Java-7 default-method issue). All new types are Java-7 safe (no default methods, no lambdas). This is a public SPI break: any external implementation of `VerifiablePerformanceIssue` will fail to compile until the third parameter is added — accepted trade-off (see §9). **Lands together with phases 3 and 4 in one atomic commit (see invariant above).**
2. **testexecutioncontext-resolver** — add `PropertyResolver`-aware overloads to `TestExecutionContext.buildFrom(...)` and `buildNewJvmFrom(...)`; modify the private `quickPerfIsDisabled(Annotation[], PropertyResolver)` path to read `disableQuickPerf` via the resolver with `null`-safe fallback to `SystemProperties.QUICK_PERF_DISABLED.evaluate()`. Use `Boolean.parseBoolean(...)` for case-insensitive parity with today's `Boolean.valueOf` behavior. Existing 3-arg / 2-arg overloads delegate with `SystemPropertyResolver.INSTANCE` for binary compatibility. **Add a `private PropertyResolver propertyResolver;` field with a public getter and no public setter; assign it in `buildFrom(...)` on every code path including the early-disabled-return branch** (callers that need it later — `MainJvmAfterJUnitStatement`, evaluator, JUnit 5 interceptors — read it via `testExecutionContext.getPropertyResolver()`). **Independent of phases 1/3/4 — lands first.**
3. **evaluator-uses-context-resolver** — `PerfIssuesEvaluator.evaluatePerfIssuesIfNoJvmIssue(...)` keeps its existing signature; it pulls the resolver from `testExecutionContext.getPropertyResolver()` and passes it as the 3rd argument to every `verifier.verifyPerfIssue(...)` call (no `instanceof` branch needed — every verifier accepts it now); add the `SqlExecutions.setPropertyResolver(...)` injection. **Lands atomically with phases 1 and 4.** Concrete edit: `core/src/main/java/org/quickperf/issue/PerfIssuesEvaluator.java:191` — change `perfIssueVerifier.verifyPerfIssue(annotation, perfMeasure)` to `perfIssueVerifier.verifyPerfIssue(annotation, perfMeasure, propertyResolver)`.
4. **mechanical-signature-update** — add `PropertyResolver propertyResolver` as the third parameter to every `verifyPerfIssue(...)` implementation **and every direct caller** so the tree compiles. Most implementations ignore the new parameter; tests pass `SystemPropertyResolver.INSTANCE`. Concretely:

   **(a) Production implementations of `VerifiablePerformanceIssue`** — add the third parameter; most simply ignore it. Run `grep -rn 'implements VerifiablePerformanceIssue' --include='*.java'` and `grep -rn 'public PerfIssue verifyPerfIssue' --include='*.java'` to confirm the full set; the §4.3 / §4.4 lists are non-exhaustive (the §4 grep at planning time returned 31 concrete `implements` plus the `NO_VERIFIABLE_PERF_ISSUE` anonymous constant). Update spans `core/`, `jvm/jvm-annotations/`, `jvm/jfr-annotations/`, `sql/sql-annotations/`.

   **(b) Test-side direct callers** (verified at planning time via `grep -rn '\.verifyPerfIssue(' --include='*.java'`) — append `, SystemPropertyResolver.INSTANCE` to every 2-arg call so the test tree compiles:
   - `core/src/test/java/org/quickperf/time/MaxExecutionTimeVerifierTest.java:67`
   - `jvm/jvm-annotations/src/test/java/org/quickperf/jvm/allocation/MaxHeapAllocationPerfVerifierTest.java:39`
   - `sql/sql-annotations/src/test/java/org/quickperf/sql/time/SqlQueryMaxExecutionTimeVerifierTest.java:36`
   - `sql/sql-annotations/src/test/java/org/quickperf/sql/time/SqlQueryMaxExecutionTimeVerifierTest.java:56`
   - `sql/sql-annotations/src/test/java/org/quickperf/sql/time/SqlQueryMaxExecutionTimeVerifierTest.java:73`

   **(c) Verification step.** After applying (a)+(b)+phase-3 production update, run repo-wide `grep -rn '\.verifyPerfIssue(' --include='*.java'` and assert every match has 3 arguments (no 2-arg call site survives). Then run `mvn clean install` from the repo root and confirm green. Test code that *re-implements* `VerifiablePerformanceIssue` as a test stub (none found at planning time, but worth re-checking) also gets the parameter added.

   **Promise.** At the end of phases 1+3+4 (the atomic commit), `mvn clean install` is green. Phase 4 cannot deliver "green tests" on its own — it is part of the atomic 1+3+4 unit.
5. **non-Spring runners** — JUnit 4 plain runner (`QuickPerfJUnitRunner` adds a resolver-aware overload of `methodInvoker` only; existing `methodInvoker` delegates with `SystemPropertyResolver.INSTANCE`); `MainJvmAfterJUnitStatement` is **unchanged in shape** (it already holds the `TestExecutionContext`, and the resolver now travels with that context); both TestNG listeners pass `SystemPropertyResolver.INSTANCE` to `TestExecutionContext.buildFrom(...)` (compiles, all existing tests still green).
6. **sql verifiers** — make the **six** SQL verifiers actually read the resolver inside their now-3-arg `verifyPerfIssue(...)`:
   - `SelectNumberPerfIssueVerifier`, `MaxOfSelectsPerfIssueVerifier` — direct callers of static `SelectAnalysis.getNPlusOneSelectAlert()`.
   - `HasSameSelectTypesWithDiffParamValuesVerifier` — caller of instance `SameSelectTypesWithDifferentParamValues.getSuggestionToFixIt()`.
   - `SqlStatementBatchVerifier` — caller of `JdbcSuggestion.BATCHING.getMessage()`.
   - **`JdbcQueryExecutionVerifier`** (annotation `@ExpectJdbcQueryExecution`) — transitively reads `SIMPLIFIED_SQL_DISPLAY` via `sameSelectTypesWithDifferentParamValues.getSuggestionToFixIt()` at line 61.
   - **`MaxJdbcQueryExecutionVerifier`** (annotation `@ExpectMaxJdbcQueryExecution`) — same transitive indirection at line 61.

   Add `SqlExecutions` `transient` field / `format()` change + helper overloads on `SelectAnalysis` (one static `getNPlusOneSelectAlert(boolean)` plus one **instance** `SameSelectTypesWithDifferentParamValues.getSuggestionToFixIt(boolean)`) and on `JdbcSuggestion` (`getBatchingMessage(boolean)`). Skipping the instance overload would silently leave `JdbcQueryExecutionVerifier` and `MaxJdbcQueryExecutionVerifier` reading `System.getProperty(...)` even when the resolver is in play.
7. **jfr verifier** — `DisplayJvmProfilingValueVerifier` actually reads `limitQuickPerfJvmInfoOnConsole` from the resolver inside its now-3-arg `verifyPerfIssue(...)`.
8. **junit4-spring-runner** — wire the Spring resolver through `SpringRunnerWithQuickPerfFeatures.methodInvoker(...)` into the embedded `QuickPerfJUnitRunner.methodInvoker(..., resolver)` overload (resolver is then retained on `TestExecutionContext`; `withAfters` needs no overload because it already reads `testExecutionContext`). Build the resolver with an anonymous `PropertyResolver` capturing a `final Environment` (no method references — Java 7). `QuickPerfSpringRunner.methodInvoker(...)` only handles the fork-parent path with `SystemPropertyResolver.INSTANCE`. Propagate to `junit4-spring4` / `junit4-spring3` variants.
9. **junit5-extension (SPI host)** — define `org.quickperf.junit5.spi.PropertyResolverProvider`. In `QuickPerfTestExtension`, load providers once via `ServiceLoader.load(PropertyResolverProvider.class)` into a `static final List`, then expose a private helper `buildResolverFromExtensionContext(ExtensionContext)` that iterates them and falls back to `SystemPropertyResolver.INSTANCE` when every provider returns `null` (or none is registered). Call this helper **only in `beforeEach`**, pass the result to `TestExecutionContext.buildFrom(quickPerfConfigs, testMethod, junit5AllocationOffset, resolver)`, and put the resulting context in `ExtensionContext.Store` as today. The interceptor callbacks (`interceptTestMethod`, `interceptTestTemplateMethod`, `interceptDynamicTest`) read the resolver via `testExecutionContext.getPropertyResolver()` from the context retrieved out of the thread-safe `ExtensionContext.Store` — no rebuild, no second `ServiceLoader` iteration, no second Spring lookup. **No reflection, no `Class.forName`, no Spring import in `junit5-extension`.**
9a. **junit5-spring (SPI impl, NEW module)** — create `spring/junit5-spring/` (artifact `quick-perf-junit5-spring`), registered in `spring/pom.xml` `<modules>`. Single class `SpringPropertyResolverProvider` (compile-time `spring-test` dep) calling `SpringExtension.getApplicationContext(ctx).getEnvironment()` directly — plain method call, **no reflection**. Wraps the `Environment` in an anonymous `PropertyResolver` capturing it in a `final` field (Java 7 safe per §9 (1)). Catches `Throwable` from the `getApplicationContext` call and returns `null` so the SPI host falls back. Ships `META-INF/services/org.quickperf.junit5.spi.PropertyResolverProvider` with one line. Add `<scope>test</scope>` dependency on this artifact in `spring/junit5-spring-boot-3-test/pom.xml` to exercise the path end-to-end.
10. **parallel-test** — new integration test classes as in §6 (display + `disableQuickPerf` + forked-JVM × `disableQuickPerf` with revised expectations).
11. **unit-tests** — `TestExecutionContext`, `PerfIssuesEvaluator`, `SqlExecutions` unit tests as in §6, including the `Boolean.parseBoolean` case-insensitivity check, **plus the SQL transitive-indirection regression tests** (per-verifier assertions that `HasSameSelectTypesWithDiffParamValuesVerifier`, `JdbcQueryExecutionVerifier`, and `MaxJdbcQueryExecutionVerifier` honour the resolver-supplied boolean rather than `System.getProperty(...)`).
12. **docs** — README / module README brief mention, plus an explicit note: TestNG-Spring resolution is v2; Spring-only `disableQuickPerf=true` with a forking annotation still pays the fork cost (use `-D` to skip the fork). **Add a compatibility-matrix row** to the top-level README (or `spring/README.md`) of the form:

    | Test framework | `-D` system property | Spring `Environment` (`@SpringBootTest(properties=…)`, `application.properties`, `application.yml`) |
    |---|---|---|
    | JUnit 4 | ✓ | ✓ |
    | JUnit 5 | ✓ | ✓ (requires `quick-perf-junit5-spring` on test classpath) |
    | TestNG | ✓ | ✗ (planned for v2 — issue #&lt;TRACKING_ISSUE_NUMBER&gt;) |

    The TestNG row's `✗` link to the v2 tracking issue is the single source of truth users hit when their `@SpringBootTest(properties = "disableQuickPerf=true")` does not honor the property under TestNG.

Each phase (or the atomic 1+3+4 unit) compiles and runs the full test suite green before moving on.

---

## 9. Revisions (response to feedback)

This plan has been revised in response to `Plan-spring-property-resolution-feedback.md`. Material changes vs. the previous draft:

1. **Java 7 compatibility throughout.** Removed the `default T evaluateFrom(...)` method on `SystemProperty` and the `env::getProperty` method reference. All new types use anonymous inner classes; `SystemProperty` is unchanged; verifiers use a small inline helper for the `null`-safe fallback. `Boolean.parseBoolean(...)` replaces `"true".equals(...)` to preserve today's case-insensitive behavior.
2. **JUnit 4 Spring same-JVM wiring corrected.** The previous draft attempted to inject the resolver in `QuickPerfSpringRunner.methodInvoker(...)`, but that path is only used for the fork-parent case. The actual same-JVM wiring goes through `SpringRunnerWithQuickPerfFeatures` → embedded `QuickPerfJUnitRunner`. New §4.5.b spells out the exact wiring; new resolver-aware overloads of `QuickPerfJUnitRunner.methodInvoker(...)` / `withAfters(...)` (§4.5.a) accept the resolver from the Spring runner.
3. **Fork-parent skip claim dropped.** The previous draft claimed `@SpringBootTest(properties = "disableQuickPerf=true") @HeapSize(...)` would skip the fork in the parent JVM. Verified that Spring's `ApplicationContext` is not loaded in the parent's fork-decision path (`QuickPerfSpringRunner.runChild` → plain `BlockJUnit4ClassRunner.super.runChild` → `BlockJUnit4ClassRunner.createTest`). Revised §3.3 (13) and §6: parent forks unconditionally; the child loads Spring and no-ops QuickPerf. Users who need to skip the fork should use `-DdisableQuickPerf=true`. The cost trade-off is documented in §7.
4. **TestNG scope reconciled.** Previous draft contradicted itself by listing TestNG tests as expected to pass while declaring TestNG-Spring out of scope. New §6 explicitly lists JUnit 4 + JUnit 5 only; new §7 spells out why TestNG-Spring is v2.
5. **Resolver lifetime made explicit — retained on `TestExecutionContext`.** The resolver is stored on `TestExecutionContext` as a single-assignment private field with a public getter and **no public setter**, assigned once by `buildFrom(...)` (normalised to `SystemPropertyResolver.INSTANCE` when `null`) on every code path including the early-disabled-return branch. `TestExecutionContext` is per-test-method, **not `Serializable`** (verified: `core/.../TestExecutionContext.java` has no `implements Serializable`), and built on the calling thread, so the resolver inherits that lifetime exactly with no race. Downstream consumers retrieve the resolver from the context they already hold:
   - **JUnit 5 callbacks** read `extensionContext.getStore(NAMESPACE).get(TestExecutionContext.class).getPropertyResolver()`. The resolver is built **once** in `beforeEach`, not rebuilt per callback. JUnit 5's `Store` is contractually thread-safe, so the `put`/`get` chain establishes happens-before for the resolver field even if interceptors run on a different thread under `@Execution(CONCURRENT)`.
   - **JUnit 4 `MainJvmAfterJUnitStatement`** reads `testExecutionContext.getPropertyResolver()` — it already holds a `final TestExecutionContext` (per-test-method, captured by the JUnit `Statement` chain), so **no new constructor parameter is needed**. `methodInvoker(...)` (writes the runner's `testExecutionContext` field) and `withAfters(...)` (reads it) run sequentially on the same JUnit 4 worker thread inside `BlockJUnit4ClassRunner.methodBlock`, so the assignment is visible without further synchronization.
   - **Evaluator** (`PerfIssuesEvaluator.evaluatePerfIssuesIfNoJvmIssue`) keeps its existing signature; it pulls the resolver from `testExecutionContext.getPropertyResolver()` and forwards it to verifiers / `SqlExecutions.setPropertyResolver(...)`.
   Earlier drafts: (a) used a setter on `TestExecutionContext` (rejected — post-construction mutability); (b) avoided storing it on the context entirely and rebuilt per JUnit 5 callback / threaded a separate constructor param into `MainJvmAfterJUnitStatement` (rejected — pure plumbing without a benefit, since `TestExecutionContext` is already per-test and already flows to every consumer; the §3.4 "no setter" objection does **not** apply to a single-assignment field that is only ever written inside the static factory). The current design retains the resolver alongside other per-test data (`perfAnnotations`, `workingFolder`, `quickPerfDisabled`) using the same assign-once-in-factory pattern as the rest of the class.
6. **Spring precedence corrected.** `@DynamicPropertySource` is now listed above `@TestPropertySource` (per Spring reference docs).
7. **All direct and transitive call sites covered.** §4.3 / §4.4 enumerate the three SQL helper files (`SqlExecutions`, `SelectAnalysis`, `JdbcSuggestion`) and the JFR verifier (`DisplayJvmProfilingValueVerifier`) that read `SIMPLIFIED_SQL_DISPLAY` / `limitQuickPerfJvmInfoOnConsole`, **plus the six SQL verifiers** (`SelectNumberPerfIssueVerifier`, `MaxOfSelectsPerfIssueVerifier`, `HasSameSelectTypesWithDiffParamValuesVerifier`, `SqlStatementBatchVerifier`, `JdbcQueryExecutionVerifier`, `MaxJdbcQueryExecutionVerifier`) — the last two reach the property *transitively* through `SameSelectTypesWithDifferentParamValues.getSuggestionToFixIt()` and would silently bypass the resolver without the new instance overload.
8. **No reflection in JUnit 5 extension.** An earlier revision had `QuickPerfTestExtension` use `Class.forName("...SpringExtension")` + `Method.invoke(...)` to read Spring's `Environment` without a compile-time Spring dep. Per reviewer preference, this plan instead introduces a `ServiceLoader`-based SPI (`PropertyResolverProvider` in `junit5-extension`) with a Spring implementation shipped from a new `spring/junit5-spring` module that has a real compile-time `spring-test` dep. Trade-offs accepted:
   - **One new published artifact** (`quick-perf-junit5-spring`) and one `META-INF/services` file. Mirrors the existing `QuickPerfConfigLoader` SPI pattern (see `core/.../QuickPerfConfigsLoader` and the per-module service files in `jvm-annotations`, `jfr-annotations`, `sql-annotations`) and parallels the JUnit 4 split (`junit4-spring3` / `junit4-spring4` / `junit4-spring5`).
   - **Users on JUnit 5 + Spring must add `quick-perf-junit5-spring` to their test classpath** to get Spring property resolution. Without it, `@SpringBootTest(properties=…)` for QuickPerf properties is ignored and `-D` system properties are still honored — same behavior as TestNG-Spring (which is v2-only) and same behavior as today.
   - **Benefit:** no reflection in `QuickPerfTestExtension`, no Spring class loaded by plain-JUnit-5 users, identical thread-safety story to the rest of the design (immutable provider list, resolver retained on the per-test-method `TestExecutionContext` via the thread-safe `ExtensionContext.Store`), and a clean extension point if other frameworks (e.g., Quarkus, Micronaut) ever want to plug in their own resolver.
9. **No new `PropertyAwareVerifier` interface.** An earlier revision proposed a
   capability sub-interface (`PropertyAwareVerifier extends VerifiablePerformanceIssue`)
   so verifiers could opt in to receiving the resolver, mirroring the existing
   `instanceof PerfIssuesFormat` pattern. Per reviewer preference, this plan
   instead modifies `VerifiablePerformanceIssue.verifyPerfIssue(A, V)` directly
   to `verifyPerfIssue(A, V, PropertyResolver)`. Trade-offs accepted:
   - **Public SPI break.** Any external implementation of
     `VerifiablePerformanceIssue` will fail to compile until the third
     parameter is added. There are no internal stable-API guarantees published
     for this interface today, so this is acceptable for a minor version bump
     in the QuickPerf 1.x line. Documented in release notes (phase 12).
   - **~25 in-repo verifiers get a mechanical signature update** even though
     they don't read the resolver. This is purely additive (one parameter,
     ignored). The change is isolated to a single phase (phase 4) so the
     diff stays reviewable.
   - **Benefit:** uniform call site in `PerfIssuesEvaluator` (no `instanceof`
     branching), one fewer SPI type, and verifiers that gain
     property-awareness in the future don't have to migrate to a sibling
     interface.
