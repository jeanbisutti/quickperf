# Plan — TestNG-Spring property resolution (v2)

> **Status:** v2 follow-up to `spring/Plan-spring-property-resolution.md`.
> **Predecessor commit:** `c01bbcb` ("Resolve QuickPerf properties from Spring `Environment`").
> **v1 deferral reference:** `spring/Plan-spring-property-resolution.md` §7, lines 991–1006.
> **Branch:** `feature/spring-property-resolution`.
>
> This v2 plan executes the deferred TestNG portion of the v1 architecture **for the SQL-listener path only**. The reasons for limiting the scope are documented in §3 and §10.

---

## 1. Goal

Make the QuickPerf properties that v1 already resolves from Spring `Environment` for **JUnit 4** and **JUnit 5** also resolvable from Spring `Environment` for **TestNG** tests that extend `org.springframework.test.context.testng.AbstractTestNGSpringContextTests` and use `QuickPerfSqlTestNGListener`.

The properties in scope are exactly the v1 set:

| Property | Read by |
|---|---|
| `disableQuickPerf` | `TestExecutionContext.isQuickPerfDisabled()` (`core/src/main/java/org/quickperf/TestExecutionContext.java:247-262`) |
| `limitQuickPerfSqlInfoOnConsole` | `SqlExecutions.format`, `SelectAnalysis`, `JdbcSuggestion.BATCHING.getMessage` (3 call sites) |
| `quickPerfDisplaySqlInResultsAsInLog` | `SqlExecutions.format` |

After this plan ships, a TestNG test class extending `AbstractTestNGSpringContextTests` and registering `QuickPerfSqlTestNGListener` can put the values in `application.properties`, `application.yml`, `@SpringBootTest(properties = …)`, `@TestPropertySource`, or any other source resolved by Spring `Environment`, and QuickPerf will honor them with system-property overrides taking precedence (same precedence as v1: `System.getProperty` > Spring `Environment`).

The full QuickPerf TestNG listener (`QuickPerfTestNGListener`, used for `@HeapSize`, `@ProfileQuery`, `@ExpectMaxHeapAllocation` and any annotation that may fork a JVM) is **out of scope**. See §3 for why and §10 for the scope rationale.

---

## 2. Architecture (single source of truth: Spring `Environment`, reflectively read)

The v1 architecture is reused without modification. v2 adds two new artifacts (`quick-perf-testng-spi`, `quick-perf-testng-spring`) and a single new dependency in `quick-perf-testng-sql-listener`.

```
quick-perf-core
├── PropertyResolver                              (v1 — unchanged)
├── SystemPropertyResolver                        (v1 — unchanged)
├── PropertyResolverAware                         (v1 — unchanged)
├── TestExecutionContext.buildFrom(..., PropertyResolver)   (v1 — unchanged)
└── PerfIssuesEvaluator.evaluatePerfIssuesIfNoJvmIssue      (v1 — unchanged)

quick-perf-testng-spi              (NEW)
└── PropertyResolverProvider       — TestNG-flavored SPI
└── TestNGPropertyResolverLoader   — ServiceLoader wrapper, static-cached, thread-safe

quick-perf-testng                  (testng/testng-listener)
└── unchanged in v2 (does not consume the SPI; see §6.4)

quick-perf-testng-sql-listener     (testng/testng-sql-listener)
└── consumes TestNGPropertyResolverLoader from beforeInvocation
└── wires the resolver into TestExecutionContext.buildFrom

quick-perf-testng-spring           (NEW — spring/testng-spring)
└── SpringTestNGPropertyResolverProvider
└── META-INF/services/org.quickperf.testng.spi.PropertyResolverProvider
```

The Spring provider walks the test class via reflection (no `spring-test` compile dep) to retrieve the `Environment` and exposes a `PropertyResolver` whose `resolve(String)` calls `Environment.getProperty(String)`.

---

## 3. Why TestNG-Spring support is constrained to the SQL listener path

The v1 plan deferred TestNG entirely. While preparing this v2 plan, an additional architectural constraint was discovered that **makes the full listener path (`QuickPerfTestNGListener`) infeasible without a user-facing inheritance change**:

* **`AbstractTestNGSpringContextTests` itself implements `IHookable`** (Spring 6.1.6 `AbstractTestNGSpringContextTests.java:63`):
  ```java
  public abstract class AbstractTestNGSpringContextTests implements IHookable, ApplicationContextAware { ... }
  ```
* **TestNG resolves exactly one `IHookable` per test method** — winner-takes-all, **no chaining** (TestNG 7.0 `internal/TestInvoker.java:572-585`, identical in 7.8 at `:645-666`):
  ```java
  IHookable hookableInstance =
      IHookable.class.isAssignableFrom(arguments.getTestMethod().getRealClass())
          ? (IHookable) arguments.getInstance()
          : m_configuration.getHookable();
  ```
  `MethodInvocationHelper.invokeHookable` then calls a single `IHookable.run(...)`. Listeners registered as `IHookable` (which is what `QuickPerfTestNGListener` is) are bypassed when the test class itself implements `IHookable`.

**Consequence:** for any TestNG test class that extends `AbstractTestNGSpringContextTests`, Spring's `IHookable.run` wins; `QuickPerfTestNGListener.run` is **never called by TestNG**, regardless of how it was registered (`@Listeners`, SPI auto-registration, `addListener(...)`).

**`QuickPerfSqlTestNGListener` is not affected** — it implements `IInvokedMethodListener`, which TestNG dispatches in sequence (no winner-takes-all), so it composes cleanly with Spring's `IHookable`.

**v2 scope decision:** ship Spring property resolution for the SQL-listener path only. Re-enable the 6 currently disabled tests in `spring/testng-spring-boot-3-test/`. Document the full-listener limitation loudly in §10 and link to a v3 issue covering options (a published `QuickPerfAbstractTestNGSpringContextTests` base class; or re-architecting the full listener as `IInvokedMethodListener`).

---

## 4. v1 wiring reused unchanged

| v1 component | Module | Reused by v2 |
|---|---|---|
| `org.quickperf.config.PropertyResolver` (interface) | `core` | Yes — Spring resolver implements it. |
| `org.quickperf.config.SystemPropertyResolver` (singleton) | `core` | Yes — fallback when no Spring context. |
| `org.quickperf.config.PropertyResolverAware` (mixin) | `core` | Yes — flowed by `PerfIssuesEvaluator` into measures/verifiers. |
| `TestExecutionContext.buildFrom(quickPerfConfigs, method, allocOffset, propertyResolver)` (4-arg overload, lines 106-122) | `core` | Yes — called by the SQL listener with the Spring resolver. |
| `TestExecutionContext.isQuickPerfDisabled()` (lines 247-262) | `core` | Yes — reads `disableQuickPerf` via the resolver. |
| `PerfIssuesEvaluator.evaluatePerfIssuesIfNoJvmIssue(...)` flowing the resolver | `core` | Yes — unchanged dispatch. |

Nothing in `core/` changes for v2.

---

## 5. New TestNG-flavored `PropertyResolverProvider` SPI

### 5.1 Why a dedicated `testng-spi` module instead of placing it in `testng-listener`

The `testng-listener` module ships `META-INF/services/org.testng.ITestNGListener` (line 12) which auto-registers `QuickPerfTestNGListener` as a global TestNG listener. If the SPI lived in `testng-listener` and `testng-sql-listener` depended on `testng-listener`, then every existing user of `quick-perf-testng-sql-listener` would silently get `QuickPerfTestNGListener` auto-registered on their next upgrade — for plain (non-Spring) TestNG SQL test classes that are not `IHookable`, the full listener would win via `m_configuration.getHookable()` and would, among other side effects, **trigger JVM forking on `@HeapSize` / `@ProfileQuery`** that the user never opted into.

To avoid this regression, the SPI lives in its own tiny module that ships **no listener registrations**:

```
testng/
├── testng-spi          (NEW)        artifact: quick-perf-testng-spi
├── testng-listener                  artifact: quick-perf-testng       (unchanged)
└── testng-sql-listener              artifact: quick-perf-testng-sql-listener
```

* `testng-sql-listener` adds a runtime dep on `quick-perf-testng-spi` (no transitive registration risk).
* `testng-spring` (new, see §6) declares its provider via `META-INF/services/org.quickperf.testng.spi.PropertyResolverProvider`.

### 5.2 SPI interface — `org.quickperf.testng.spi.PropertyResolverProvider`

Lives in `testng/testng-spi/src/main/java/org/quickperf/testng/spi/PropertyResolverProvider.java`.

```java
package org.quickperf.testng.spi;

import org.quickperf.config.PropertyResolver;

import java.lang.reflect.Method;

/**
 * SPI implemented by integration modules (e.g. {@code quick-perf-testng-spring})
 * that can build a {@link PropertyResolver} from a TestNG test instance.
 *
 * <p>TestNG does not have a concept analogous to JUnit 5's
 * {@code ExtensionContext}, so the provider receives the actual test instance
 * (typically from {@code IInvokedMethod.getTestMethod().getInstance()}) and
 * the reflective {@link Method}. Providers must:
 * <ul>
 *   <li>be safe to call when the test class does not extend any framework
 *       base class (return {@code null} so the caller falls back to
 *       {@link org.quickperf.config.SystemPropertyResolver#INSTANCE});</li>
 *   <li>be safe to call before the application context is loaded
 *       (e.g. during {@code @BeforeClass}; return {@code null});</li>
 *   <li>never throw — wrap and swallow all reflective failures, returning
 *       {@code null}.</li>
 * </ul>
 */
public interface PropertyResolverProvider {

    /**
     * Build a {@link PropertyResolver} for the given TestNG test invocation,
     * or return {@code null} if this provider does not apply.
     */
    PropertyResolver tryBuild(Object testInstance, Method testMethod);
}
```

The signature takes both `testInstance` and `testMethod` for symmetry with JUnit 4/5 (where the runner/extension also has both available) and for forward compatibility with providers that may need method-level annotations.

### 5.3 Loader — `org.quickperf.testng.spi.TestNGPropertyResolverLoader`

Singleton, ServiceLoader-backed, static-cached at class-init time, mirroring JUnit 5's `QuickPerfTestExtension.loadPropertyResolverProviders()` (`junit5/junit5-extension/.../QuickPerfTestExtension.java:60-71`).

```java
package org.quickperf.testng.spi;

import org.quickperf.config.PropertyResolver;
import org.quickperf.config.SystemPropertyResolver;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;

public final class TestNGPropertyResolverLoader {

    public static final TestNGPropertyResolverLoader INSTANCE = new TestNGPropertyResolverLoader();

    private final List<PropertyResolverProvider> providers;

    private TestNGPropertyResolverLoader() {
        this.providers = loadProviders();
    }

    private static List<PropertyResolverProvider> loadProviders() {
        List<PropertyResolverProvider> result = new ArrayList<>();
        try {
            ServiceLoader<PropertyResolverProvider> loader =
                    ServiceLoader.load(PropertyResolverProvider.class);
            for (Iterator<PropertyResolverProvider> it = loader.iterator(); it.hasNext(); ) {
                result.add(it.next());
            }
        } catch (Throwable t) {
            // SPI loading must never break TestNG dispatch.
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Iterate providers in ServiceLoader order. First non-null wins.
     * Falls back to {@link SystemPropertyResolver#INSTANCE}.
     */
    public PropertyResolver build(Object testInstance, Method testMethod) {
        for (PropertyResolverProvider provider : providers) {
            try {
                PropertyResolver resolver = provider.tryBuild(testInstance, testMethod);
                if (resolver != null) {
                    return resolver;
                }
            } catch (Throwable t) {
                // Provider failure is non-fatal: try the next provider.
            }
        }
        return SystemPropertyResolver.INSTANCE;
    }
}
```

The static `INSTANCE` ensures the `ServiceLoader` walk happens once per classloader (matching JUnit 5's behavior). Static-final initialization is JVM-thread-safe.

### 5.4 `testng/testng-spi/pom.xml`

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0" ...>
    <parent>
        <groupId>org.quickperf</groupId>
        <artifactId>quick-perf-testng-parent</artifactId>
        <version>1.1.1-SNAPSHOT</version>
    </parent>
    <modelVersion>4.0.0</modelVersion>
    <artifactId>quick-perf-testng-spi</artifactId>

    <properties>
        <maven.compiler.source>1.8</maven.compiler.source>
        <maven.compiler.target>1.8</maven.compiler.target>
        <dependencies.max.jdk.version>1.8</dependencies.max.jdk.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.quickperf</groupId>
            <artifactId>quick-perf-core</artifactId>
            <version>1.1.1-SNAPSHOT</version>
        </dependency>
    </dependencies>
</project>
```

No TestNG dep (the SPI does not reference any TestNG type). No reflection (the loader just uses `ServiceLoader`).

### 5.5 Add the new module to `testng/pom.xml`

`testng/pom.xml` lists the child modules. `testng-spi` must be declared **before** `testng-listener` and `testng-sql-listener` so that Maven reactor builds it first.

---

## 6. Component changes

### 6.1 New module — `spring/testng-spring`

Mirrors the structure of `spring/junit5-spring`. Produces `quick-perf-testng-spring`.

#### 6.1.1 `spring/testng-spring/pom.xml`

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0" ...>
    <parent>
        <groupId>org.quickperf</groupId>
        <artifactId>quick-perf-spring</artifactId>
        <version>1.1.1-SNAPSHOT</version>
    </parent>
    <modelVersion>4.0.0</modelVersion>
    <artifactId>quick-perf-testng-spring</artifactId>
    <version>1.1.1-SNAPSHOT</version>

    <properties>
        <maven.compiler.source>1.8</maven.compiler.source>
        <maven.compiler.target>1.8</maven.compiler.target>
        <dependencies.max.jdk.version>1.8</dependencies.max.jdk.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.quickperf</groupId>
            <artifactId>quick-perf-testng-spi</artifactId>
            <version>1.1.1-SNAPSHOT</version>
        </dependency>

        <!-- Reflection only; provided so users supply their own TestNG / spring-test versions. -->
        <dependency>
            <groupId>org.testng</groupId>
            <artifactId>testng</artifactId>
            <version>7.0.0</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-test</artifactId>
            <version>${spring5-version}</version>
            <scope>provided</scope>
        </dependency>

        <!-- Test deps. -->
        <dependency>
            <groupId>org.testng</groupId>
            <artifactId>testng</artifactId>
            <version>7.0.0</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-test</artifactId>
            <version>${spring5-version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-context</artifactId>
            <version>${spring5-version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

`spring-test` is `provided` because the production code only references it reflectively. The test scope brings in real Spring types for unit tests.

#### 6.1.2 `SpringTestNGPropertyResolverProvider`

```java
package org.quickperf.spring.testng;

import org.quickperf.config.PropertyResolver;
import org.quickperf.testng.spi.PropertyResolverProvider;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * {@link PropertyResolverProvider} that reads QuickPerf properties from the
 * Spring {@code Environment} of a TestNG test instance extending
 * {@code AbstractTestNGSpringContextTests}.
 *
 * <p>All access to Spring types is reflective so {@code spring-test} is a
 * compile-time {@code provided} dependency only.
 *
 * <p>This provider returns {@code null} (caller falls back to
 * {@link org.quickperf.config.SystemPropertyResolver}) when:
 * <ul>
 *   <li>the test instance does not have a {@code testContextManager} field
 *       (i.e. does not extend {@code AbstractTestNGSpringContextTests});</li>
 *   <li>the application context is not yet loaded (e.g. during
 *       {@code @BeforeClass(alwaysRun=true)}); or</li>
 *   <li>any reflective step throws.</li>
 * </ul>
 */
public class SpringTestNGPropertyResolverProvider implements PropertyResolverProvider {

    private static final String TEST_CONTEXT_MANAGER_FIELD = "testContextManager";

    @Override
    public PropertyResolver tryBuild(Object testInstance, Method testMethod) {
        if (testInstance == null) {
            return null;
        }
        try {
            Field tcmField = findFieldUpHierarchy(testInstance.getClass(), TEST_CONTEXT_MANAGER_FIELD);
            if (tcmField == null) {
                return null;
            }
            tcmField.setAccessible(true);
            Object testContextManager = tcmField.get(testInstance);
            if (testContextManager == null) {
                return null;
            }
            Object testContext = testContextManager.getClass().getMethod("getTestContext").invoke(testContextManager);
            if (testContext == null) {
                return null;
            }
            Object applicationContext = testContext.getClass().getMethod("getApplicationContext").invoke(testContext);
            if (applicationContext == null) {
                return null;
            }
            final Object environment = applicationContext.getClass().getMethod("getEnvironment").invoke(applicationContext);
            if (environment == null) {
                return null;
            }
            final Method getPropertyMethod = environment.getClass().getMethod("getProperty", String.class);
            return new PropertyResolver() {
                @Override
                public String resolve(String propertyName) {
                    try {
                        return (String) getPropertyMethod.invoke(environment, propertyName);
                    } catch (Throwable t) {
                        return null;
                    }
                }
            };
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field findFieldUpHierarchy(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
```

**Key design notes:**

* `testContextManager` is `private` on `AbstractTestNGSpringContextTests` (Spring 6.1.6 `AbstractTestNGSpringContextTests.java:75`), so `setAccessible(true)` is required. This is **not** symmetric with the JUnit 4 approach in `SpringRunnerWithQuickPerfFeatures.buildSpringPropertyResolver()`, which calls `setAccessible` on a public-superclass `Method` — the TestNG path reaches into a `private final` field. The accepted justification is that the field name is part of Spring's stable contract; §8.5 below adds a unit test pinning this name to fail-fast on a future Spring rename.
* The `findFieldUpHierarchy` walk is required because user test classes extend `AbstractTestNGSpringContextTests`, not the test class itself (so `testInstance.getClass().getDeclaredField` would throw).
* The provider returns `null` when `testContext.getApplicationContext()` returns `null` — this happens before Spring loads the context (e.g. during the very first `IInvokedMethodListener.beforeInvocation` call for a `@BeforeClass(alwaysRun=true)` configuration method). See §8.6.

#### 6.1.3 SPI registration

`spring/testng-spring/src/main/resources/META-INF/services/org.quickperf.testng.spi.PropertyResolverProvider`:

```
org.quickperf.spring.testng.SpringTestNGPropertyResolverProvider
```

#### 6.1.4 Add the new module to `spring/pom.xml`

`spring/pom.xml` lists Spring submodules. Add `<module>testng-spring</module>` at the end of the `<modules>` block.

### 6.2 `testng-sql-listener` — wire the resolver

`QuickPerfSqlTestNGListener.beforeInvocation` is the integration point. It already has `IInvokedMethod`, from which both the test instance and the reflective method are reachable.

#### 6.2.1 Add Maven dep

`testng/testng-sql-listener/pom.xml` — add:

```xml
<dependency>
    <groupId>org.quickperf</groupId>
    <artifactId>quick-perf-testng-spi</artifactId>
    <version>1.1.1-SNAPSHOT</version>
</dependency>
```

This adds **only** the SPI module. It does **not** transitively pull `testng-listener`, so existing SQL-only users do not get `QuickPerfTestNGListener` auto-registered (see §5.1).

#### 6.2.2 Modify `QuickPerfSqlTestNGListener`

Diff (against `testng/testng-sql-listener/src/main/java/org/quickperf/testng/QuickPerfSqlTestNGListener.java`):

```diff
 import org.quickperf.TestExecutionContext;
+import org.quickperf.config.PropertyResolver;
 import org.quickperf.config.library.QuickPerfConfigs;
 import org.quickperf.config.library.QuickPerfConfigsLoader;
 import org.quickperf.config.library.SetOfAnnotationConfigs;
 import org.quickperf.issue.JvmOrTestIssue;
 import org.quickperf.issue.PerfIssuesEvaluator;
 import org.quickperf.issue.PerfIssuesToFormat;
 import org.quickperf.issue.TestIssue;
 import org.quickperf.perfrecording.PerformanceRecording;
 import org.quickperf.reporter.QuickPerfReporter;
+import org.quickperf.testng.spi.TestNGPropertyResolverLoader;
 import org.testng.IInvokedMethod;
 import org.testng.IInvokedMethodListener;
 import org.testng.ITestNGMethod;
 import org.testng.ITestResult;
@@
-    private TestExecutionContext buildTestExecutionContextFrom(IInvokedMethod method) {
-        ITestNGMethod testNGMethod = method.getTestMethod();
-        Method testMethod = testNGMethod.getConstructorOrMethod().getMethod();
-        int noAllocationOffsetBecauseOnlyOneJvm = 0;
-        return TestExecutionContext.buildFrom(quickPerfConfigs, testMethod, noAllocationOffsetBecauseOnlyOneJvm);
-    }
+    private TestExecutionContext buildTestExecutionContextFrom(IInvokedMethod method) {
+        ITestNGMethod testNGMethod = method.getTestMethod();
+        Method testMethod = testNGMethod.getConstructorOrMethod().getMethod();
+        Object testInstance = testNGMethod.getInstance();
+        int noAllocationOffsetBecauseOnlyOneJvm = 0;
+        PropertyResolver propertyResolver =
+                TestNGPropertyResolverLoader.INSTANCE.build(testInstance, testMethod);
+        return TestExecutionContext.buildFrom(
+                quickPerfConfigs,
+                testMethod,
+                noAllocationOffsetBecauseOnlyOneJvm,
+                propertyResolver);
+    }
```

The 4-arg `TestExecutionContext.buildFrom` overload was added in v1 (`core/.../TestExecutionContext.java:106-122`). It accepts a `PropertyResolver` and is what the resolver flows through (`isQuickPerfDisabled` reads it, `PerfIssuesEvaluator` flows it to `PropertyResolverAware` measures and verifiers).

**No other changes** are required in `QuickPerfSqlTestNGListener`. `afterInvocation` consumes the `TestExecutionContext` already built in `beforeInvocation`; no new state is introduced.

#### 6.2.3 Pre-existing thread-safety risk on `testExecutionContext` field — **not addressed in v2**

`QuickPerfSqlTestNGListener.testExecutionContext` (line 43) is a mutable instance field written in `beforeInvocation` and read in `afterInvocation`. Under TestNG `parallel="methods"` two `IInvokedMethod` invocations could race on the same listener instance. This is a **pre-existing** issue (predates v1 and v2). It is masked in v2 tests because `spring/testng-spring-boot-3-test/pom.xml:153` pins `<parallel>none</parallel>`.

v2 makes the bug **slightly worse in principle** (per-invocation Spring resolvers could be mixed across threads), but does not introduce it. v2 does not fix it. A separate issue should be opened to migrate `testExecutionContext` to a per-invocation pattern (e.g. attach to `ITestResult` attributes via `IInvokedMethod#getTestResult().setAttribute(...)`). **Listed loudly in §10.**

### 6.3 `testng-listener` — no functional change in v2

`QuickPerfTestNGListener` is **not** modified by v2. Justification:

1. For test classes extending `AbstractTestNGSpringContextTests`, TestNG bypasses `QuickPerfTestNGListener.run` entirely (Spring's `IHookable` wins) — see §3. Wiring a Spring resolver into an unreachable code path would be dead code and would mislead readers.
2. For plain (non-Spring) TestNG tests, `QuickPerfTestNGListener` runs and the existing `SystemPropertyResolver`-based behavior is correct.
3. The `quick-perf-testng-spi` module is **deliberately not wired into `testng-listener`**. Adding a `testng-listener → testng-spi` dep would not be harmful, but yields no value in v2 and risks conveying that v2 supports a Spring-flavored full-listener path (it does not).

A v3 issue (linked from §10) tracks the future work to make `@HeapSize`, `@ProfileQuery`, and other forking annotations interoperate with Spring TestNG.

### 6.4 BOM updates (`bom/pom.xml`)

`bom/pom.xml` is missing **two** Spring artifacts that should be there:

* `quick-perf-junit5-spring` — already shipped in v1, but never added to BOM (a v1 oversight). v2 fixes this.
* `quick-perf-testng-spring` — new in v2.

Both entries are added between `quick-perf-junit4-spring5` (line 76-78) and `quick-perf-springboot1-sql-starter` (line 80-83):

```xml
<dependency>
    <groupId>org.quickperf</groupId>
    <artifactId>quick-perf-junit5-spring</artifactId>
    <version>1.1.1-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>org.quickperf</groupId>
    <artifactId>quick-perf-testng-spring</artifactId>
    <version>1.1.1-SNAPSHOT</version>
</dependency>
```

`quick-perf-testng-spi` is **not** added to the BOM; it is a transitive of `quick-perf-testng-sql-listener` and not a user-facing artifact.

### 6.5 No `SpringBootTestPropertiesReader` extraction

The original draft of this plan proposed extracting `QuickPerfSpringRunner.readSpringBootTestProperties` (3 duplicate copies in junit4-spring3/4/5 at lines 139-163) into a `core/SpringBootTestPropertiesReader`. Under Path A (SQL-listener-only scope) this extraction is **not needed**:

* The SQL listener does not fork the JVM — there is no fork-parent decision branch to populate from `@SpringBootTest(properties = …)` reflectively.
* By the time `IInvokedMethodListener.beforeInvocation` fires for an `@Test` method, Spring's `@BeforeClass(alwaysRun=true) springTestContextBeforeTestClass` (Spring 6.1.6 `AbstractTestNGSpringContextTests.java:117`) has already loaded the application context. `Environment.getProperty("…")` already exposes everything `@SpringBootTest(properties = …)` declares — Spring places those values in the environment as a `MapPropertySource`.

The 3 JUnit 4 copies of `readSpringBootTestProperties` are kept as-is. Their consolidation can be a separate, scope-limited refactor in the future.

---

## 7. Spring `Environment` precedence — what users observe

Identical to v1 (`Plan-spring-property-resolution.md` §5):

| Source | Wins over |
|---|---|
| `-DdisableQuickPerf=true` (system property) | Spring `Environment` |
| `@SpringBootTest(properties = "disableQuickPerf=true")` | `application-<profile>.yml` / `application.yml` / `application.properties` |
| `@TestPropertySource(properties = …)` | `@SpringBootTest(properties = …)` (Spring's own ordering) |
| `application.properties` / `application.yml` | (lowest) |

The system-property override comes from `SystemPropertyResolver` being checked **first** by `core/...isQuickPerfDisabled` (and the SQL display properties' equivalents in `SqlExecutions.format`, `SelectAnalysis.getNPlusOneSelectAlert`, `JdbcSuggestion.BATCHING.getMessage`). This is unchanged from v1.

---

## 8. Test strategy

All tests live in `spring/testng-spring-boot-3-test/` (the existing module). Tests use `@Listeners(QuickPerfSqlTestNGListener.class)` because §3 forbids the full listener.

### 8.1 Re-enable the 6 currently-disabled outer tests

Six outer test methods are currently disabled with `@Test(enabled = false)` and a `// TODO QuickPerf v2 — TestNG-Spring property resolution out of scope per Plan-spring-property-resolution.md §7` marker:

* `SpringBoot3TestNGDisableQuickPerfTest` — methods at lines 25, 40, 55 (3 properties: `application.yml`, `@SpringBootTest(properties=…)`, sys-prop override).
* `SpringBoot3TestNGLimitSqlDisplayTest` — methods at lines 25, 45, 65 (3 properties: same triple, but for `limitQuickPerfSqlInfoOnConsole`).

Each outer method dispatches to an inner sub-test class via `TestNGTests.createInstance(...).run(...)` (the test pattern used elsewhere in the repo). The 6 inner sub-test classes already exist under `disablequickperf/` and `limitsqldisplay/` and already extend `AbstractTestNGSpringContextTests` and use `@Listeners(QuickPerfSqlTestNGListener.class)`.

For each of the 6 outer methods, the v2 changes are:

1. Remove `enabled = false` from `@Test(...)`.
2. Remove the `// TODO QuickPerf v2 …` comment line.

After the change, each outer method asserts the same expectation it was originally written to assert (counted passed/failed/skipped tests, expected `@ExpectSelect(...)` outcome).

### 8.2 Precedence tests (system property override)

Add one test class `SpringBoot3TestNGPropertyOverrideTest` in `spring/testng-spring-boot-3-test/.../testngspringboottest/` with one outer method per property, each dispatching to an inner that sets `application.yml` / `@SpringBootTest(properties = "X=true")` and an outer that sets `System.setProperty("X", "false")` before launching the inner.

Pattern (paraphrased):

```java
@Test
public void system_property_overrides_spring_environment_for_disable_quick_perf() {
    System.setProperty("disableQuickPerf", "false");
    try {
        TestNGTests testsResult = TestNGTests.createInstance(InnerWithSpringDisableTrue.class).run();
        assertThat(testsResult.getNumberOfFailedTest()).isOne(); // QuickPerf re-enabled, @ExpectSelect(0) fails
    } finally {
        System.clearProperty("disableQuickPerf");
    }
}
```

These tests are scoped to confirm the precedence order observed in §7.

### 8.3 Plain-TestNG fallback (no Spring on classpath)

Tests in `testng/testng-sql-listener/src/test/...` exercise the SQL listener with a plain TestNG class (no Spring inheritance, no `spring-test` on the test classpath of the `testng-sql-listener` module). After v2, the listener's `beforeInvocation` calls `TestNGPropertyResolverLoader.INSTANCE.build(...)` which finds **no** registered providers (the `META-INF/services` from `testng-spring` is not on this module's classpath) and returns `SystemPropertyResolver.INSTANCE`. Behavior must match the pre-v2 `SystemPropertyResolver`-based path exactly.

Two tests:

* `QuickPerfSqlTestNGListenerTest` (existing) — must continue to pass unchanged.
* New: assert that `TestNGPropertyResolverLoader.INSTANCE.build(plainInstance, plainMethod) == SystemPropertyResolver.INSTANCE`.

### 8.4 Provider iteration order

`testng-spi` ships a unit test with **two** synthetic providers via `META-INF/services` declared in test resources; the first returns non-null, the second is unreached. The test asserts:

* Order of declaration in the `services` file is honored.
* When the first returns `null`, the second's resolver is used.
* When both return `null`, `SystemPropertyResolver.INSTANCE` is returned.

### 8.5 Field-name pinning unit test (`testContextManager`)

`spring/testng-spring/src/test/java/.../FieldPinningTest.java`:

```java
public class FieldPinningTest {
    @Test
    public void abstract_testng_spring_context_tests_has_test_context_manager_field() throws Exception {
        Field f = AbstractTestNGSpringContextTests.class.getDeclaredField("testContextManager");
        assertThat(f).isNotNull();
        assertThat(f.getType().getName())
            .isEqualTo("org.springframework.test.context.TestContextManager");
    }
}
```

Cheap insurance against silent breakage on a future Spring rename. Required test classpath: `spring-test`.

### 8.6 Pre-context `@BeforeClass(alwaysRun=true)` safety

`SpringTestNGPropertyResolverProvider` must return `null` (not throw) when the application context is not yet loaded. Add a unit test that constructs a synthetic test instance with a `testContextManager` field whose `getTestContext().getApplicationContext()` returns `null` (using a Mockito mock or hand-rolled stub), and asserts `tryBuild(...)` returns `null`.

This ensures the listener falls back to `SystemPropertyResolver` for `@BeforeClass`-time invocations and only uses the Spring resolver once the context is loaded for the first `@Test` method.

### 8.7 Provider class-loading isolation

A small unit test that calls `TestNGPropertyResolverLoader.INSTANCE.build(null, null)` with `null` arguments — must return `SystemPropertyResolver.INSTANCE` without throwing.

---

## 9. SPI break analysis

`PropertyResolverProvider` and `TestNGPropertyResolverLoader` are **new** types in a **new** module. No existing public API is modified.

| Existing public API | Change |
|---|---|
| `core/...PropertyResolver` | None |
| `core/...SystemPropertyResolver` | None |
| `core/...PropertyResolverAware` | None |
| `core/...TestExecutionContext.buildFrom` (3-arg overload) | None — still callable; the SQL listener migrates to the 4-arg overload. |
| `core/...TestExecutionContext.buildFrom` (4-arg overload, v1) | None — already public from v1. |
| `org.quickperf.testng.QuickPerfSqlTestNGListener` (public class) | Internal method body change only. Public methods (`beforeInvocation`, `afterInvocation`) keep their signatures. |
| `org.quickperf.testng.QuickPerfTestNGListener` (public class) | None |
| `META-INF/services/org.testng.ITestNGListener` in `testng-listener` | None |
| `bom/pom.xml` | Two new managed deps. Strictly additive. |

Adding `quick-perf-testng-spi` as a runtime dep of `quick-perf-testng-sql-listener` is binary-compatible: SQL-listener users gain a new transitive `org.quickperf:quick-perf-testng-spi` jar, but no existing class is removed or repackaged. Verified that `testng-spi` does not contain `META-INF/services/org.testng.*` (so no auto-registered listener is added to existing users).

---

## 10. Out of scope for v2

**Loud documentation: items below are NOT supported by this plan and require a v3 issue.**

* **`QuickPerfTestNGListener` + `extends AbstractTestNGSpringContextTests`.** TestNG `IHookable` selection is winner-takes-all (§3). The full listener is bypassed when the test class extends `AbstractTestNGSpringContextTests`, regardless of how the listener is registered. As a consequence, the following annotations are **not** supported on Spring TestNG test classes: `@HeapSize`, `@Xmx`, `@ProfileQuery`, `@ProfileJvm`, `@ExpectMaxHeapAllocation`, `@ExpectNoJvmIssue`, and any annotation that triggers JVM forking (`testExecutionUsesTwoJVMs() == true`). v3 options under consideration:
  * Ship a published `org.quickperf.spring.testng.QuickPerfAbstractTestNGSpringContextTests` base class (analog to JUnit 4's `SpringRunnerWithQuickPerfFeatures`) that overrides `IHookable.run(...)` to delegate through QuickPerf, then calls `super.run(...)` (which executes the test inside Spring's `TestContextManager` lifecycle).
  * Re-architect `QuickPerfTestNGListener` as `IInvokedMethodListener` and split forking responsibilities across `before`/`after` hooks. Significantly more invasive.

* **TestNG test classes using standalone `@ContextConfiguration` without extending `AbstractTestNGSpringContextTests`.** v2's reflective walk depends on the `testContextManager` field. Standalone `@ContextConfiguration` users fall back to `SystemPropertyResolver` silently. Documented; no v3 commitment.

* **`QuickPerfSqlTestNGListener.testExecutionContext` thread-safety under `parallel="methods"`.** Pre-existing; documented in §6.2.3. Masked in v2 tests by `<parallel>none</parallel>`. Tracked in a separate issue.

* **`quickPerfWorkingFolder` and `quickPerfToExecInASpecificJvm`** stay system-only. Same chicken-and-egg justification as v1 §7. (Not relevant under v2 scope anyway, since the SQL listener does not fork.)

* **Skipping the parent JVM fork on Spring `disableQuickPerf=true`.** Inherited from v1. Not relevant under v2 scope.

* **BOM publication of `quick-perf-testng-spi`.** Internal SPI module; surfaced only as a transitive of `quick-perf-testng-sql-listener` and `quick-perf-testng-spring`. Not exposed to BOM consumers.

---

## 11. Risks

| # | Risk | Mitigation |
|---|---|---|
| 1 | Spring renames `testContextManager` field on `AbstractTestNGSpringContextTests` | §8.5 adds a fail-fast pinning test. The `findFieldUpHierarchy` walk also fails predictably (provider returns `null`, fall back to `SystemPropertyResolver`). |
| 2 | `Environment.getProperty(String)` semantics differ across Spring versions for properties declared via `@SpringBootTest(properties = …)` | Spring places these in a `MapPropertySource` named `Inlined Test Properties` since Spring Boot 1.4 and across 5.x/6.x. v1 already validates this for JUnit 4/5. The reflective `getProperty(String)` call is identical. |
| 3 | Adding `testng-spi` as a `testng-sql-listener` dep accidentally triggers `META-INF/services/org.testng.ITestNGListener` registration | §5.4 — the new module ships **no** `META-INF/services/org.testng.*` files. CI build verifies this (see §13). |
| 4 | `IInvokedMethodListener.beforeInvocation` fires for `@BeforeClass(alwaysRun=true)` methods, before the application context is loaded | Provider returns `null` (§6.1.2 + §8.6). The listener falls back to `SystemPropertyResolver`. The first `@Test` invocation gets the Spring resolver. |
| 5 | Static-cached `ServiceLoader` walk in `TestNGPropertyResolverLoader.INSTANCE` runs once per JVM classloader; provider state is shared across tests | Spring provider is stateless. ServiceLoader semantics are identical to JUnit 5's loader (`QuickPerfTestExtension.PROPERTY_RESOLVER_PROVIDERS`, line 58). |
| 6 | `SpringBootTestPropertiesReader` extraction kept as future work — risk that 3 JUnit 4 copies drift | Lower priority. v2 does not rely on this code path; can be revisited. |

---

## 12. Implementation order (phases)

Each phase ends with a passing build before the next begins. Build commands are at §13.

### Phase 0 — preparation

* Open a v3 GitHub issue tracking the full-listener path described in §10 first bullet. Substitute the issue number into the marker comment on every test that references "TestNG-Spring property resolution v3" (currently the disabled tests reference v2 — they will be re-enabled in Phase 6, so this is mostly bookkeeping for §10).
* Update `spring/Plan-spring-property-resolution.md` §7 (lines 991–1006) to add a forward reference: "TestNG-Spring property resolution implemented in v2; see `Plan-testng-spring-property-resolution.md`. Full-listener support deferred to v3, tracked in #<v3-issue>."

### Phase 1 — `quick-perf-testng-spi` module

* Create `testng/testng-spi/` with `pom.xml` (§5.4), `PropertyResolverProvider.java` (§5.2), `TestNGPropertyResolverLoader.java` (§5.3).
* Add `<module>testng-spi</module>` to `testng/pom.xml` **before** `testng-listener` and `testng-sql-listener`.
* Add §8.4 (provider iteration order) and §8.7 (null-safe loader) tests in `testng/testng-spi/src/test/java/...`.
* `mvn license:format`.
* Phase ends green: `mvn -pl testng/testng-spi -am clean install`.

### Phase 2 — `quick-perf-testng-sql-listener` integration

* Add `quick-perf-testng-spi` dep to `testng/testng-sql-listener/pom.xml`.
* Apply the diff in §6.2.2 to `QuickPerfSqlTestNGListener`.
* Add §8.3 second assertion in `testng-sql-listener` test resources.
* `mvn license:format`.
* Phase ends green: `mvn -pl testng/testng-sql-listener -am clean install`.

### Phase 3 — `quick-perf-testng-spring` module

* Create `spring/testng-spring/` with `pom.xml` (§6.1.1), `SpringTestNGPropertyResolverProvider.java` (§6.1.2), `META-INF/services/org.quickperf.testng.spi.PropertyResolverProvider` (§6.1.3).
* Add `<module>testng-spring</module>` to `spring/pom.xml`.
* Add §8.5 (field-pinning) and §8.6 (pre-context) tests in `spring/testng-spring/src/test/java/...`.
* `mvn license:format`.
* Phase ends green: `mvn -pl spring/testng-spring -am clean install`.

### Phase 4 — BOM

* Add `quick-perf-junit5-spring` (fixing v1 omission per §6.4) and `quick-perf-testng-spring` to `bom/pom.xml`.
* Phase ends green: `mvn -pl bom -am clean install`.

### Phase 5 — re-enable disabled tests

* For each of the 6 outer test methods listed in §8.1, remove `enabled = false` and the v2-TODO comment.
* Phase ends green: `mvn -pl spring/testng-spring-boot-3-test -am -P SpringBoot3Tests clean install` (JDK 17+ required for SpringBoot3Tests profile; pin via `-Dtoolchains` as per repo conventions if needed).

### Phase 6 — precedence tests

* Add `SpringBoot3TestNGPropertyOverrideTest` per §8.2.
* Phase ends green: same command as Phase 5.

### Phase 7 — full-suite verification & docs

* Full repo build with all profiles enabled (per `CLAUDE.md` build commands): `mvn clean install` and `mvn clean install -P SpringBoot3Tests`.
* Update `README.md` (if it mentions Spring property resolution coverage; v1 likely added a section — extend it with TestNG SQL listener coverage and the §10 limitation).
* Verify `spring/Plan-spring-property-resolution.md` §7 reflects the v2 implementation.

---

## 13. Verification commands

| What | Command |
|---|---|
| New `testng-spi` module builds and tests pass | `mvn -pl testng/testng-spi -am clean install` |
| `testng-sql-listener` tests pass with new dep | `mvn -pl testng/testng-sql-listener -am clean install` |
| New `testng-spring` module builds and tests pass | `mvn -pl spring/testng-spring -am clean install` |
| BOM resolves | `mvn -pl bom -am clean install` |
| `testng-spring-boot-3-test` runs with re-enabled tests (JDK 17+) | `mvn -pl spring/testng-spring-boot-3-test -am -P SpringBoot3Tests clean install` |
| `testng-spi` jar contains no `org.testng.ITestNGListener` SPI registration | `unzip -l testng/testng-spi/target/quick-perf-testng-spi-1.1.1-SNAPSHOT.jar \| grep -i META-INF/services` (must show **only** files outside `org.testng.*`) |
| `testng-spring` jar registers exactly one provider | `unzip -p spring/testng-spring/target/quick-perf-testng-spring-1.1.1-SNAPSHOT.jar META-INF/services/org.quickperf.testng.spi.PropertyResolverProvider` |
| Full repo build (every profile that v1 ran) | `mvn clean install` and `mvn clean install -P SpringBoot3Tests` |
| License headers | `mvn license:format` (must not produce diff after Phase N is committed) |

Each phase's verification is the cumulative one (later phases re-run earlier commands as part of `clean install`).

---

## 14. Documentation updates

| File | Change |
|---|---|
| `spring/Plan-spring-property-resolution.md` §7 (lines 991-1006) | Add forward reference to this plan; mark v2 as implemented; link v3 issue for full-listener path. |
| `README.md` (root) | If v1 added a "Spring property resolution" section, extend it: TestNG support is via `quick-perf-testng-sql-listener` and `quick-perf-testng-spring` (mentioning the SQL-listener-only scope). |
| `spring/testng-spring/README.md` (new) | Short module README mirroring `spring/junit5-spring/README.md` (if one exists; otherwise skip). |

---

## 15. Files touched

### Created

```
testng/testng-spi/
├── pom.xml
└── src/main/java/org/quickperf/testng/spi/
    ├── PropertyResolverProvider.java
    └── TestNGPropertyResolverLoader.java
└── src/test/java/org/quickperf/testng/spi/
    ├── ProviderIterationOrderTest.java                 (§8.4)
    └── NullSafeLoaderTest.java                         (§8.7)
└── src/test/resources/META-INF/services/
    └── org.quickperf.testng.spi.PropertyResolverProvider (§8.4 fixture)

spring/testng-spring/
├── pom.xml
└── src/main/java/org/quickperf/spring/testng/
    └── SpringTestNGPropertyResolverProvider.java
└── src/main/resources/META-INF/services/
    └── org.quickperf.testng.spi.PropertyResolverProvider
└── src/test/java/org/quickperf/spring/testng/
    ├── FieldPinningTest.java                           (§8.5)
    └── PreContextLoadSafetyTest.java                   (§8.6)

spring/testng-spring-boot-3-test/src/test/java/org/quickperf/spring/testngspringboottest/
└── SpringBoot3TestNGPropertyOverrideTest.java          (§8.2)
```

### Modified

```
testng/pom.xml                                 — add <module>testng-spi</module>
testng/testng-sql-listener/pom.xml             — add quick-perf-testng-spi dep
testng/testng-sql-listener/src/main/java/org/quickperf/testng/QuickPerfSqlTestNGListener.java
                                               — wire the resolver (§6.2.2)
testng/testng-sql-listener/src/test/java/...   — add §8.3 assertion

spring/pom.xml                                 — add <module>testng-spring</module>
bom/pom.xml                                    — add quick-perf-junit5-spring and quick-perf-testng-spring (§6.4)

spring/testng-spring-boot-3-test/src/test/java/org/quickperf/spring/testngspringboottest/
├── SpringBoot3TestNGDisableQuickPerfTest.java          — re-enable methods at lines 25, 40, 55
└── SpringBoot3TestNGLimitSqlDisplayTest.java           — re-enable methods at lines 25, 45, 65

spring/Plan-spring-property-resolution.md      — §7 forward reference (Phase 0)
README.md                                      — §14 row 2 (if applicable)
```

### Untouched

```
core/                                          — entire module unchanged
junit4/, junit5/                               — entire modules unchanged
spring/junit4-spring{3,4,5}/                   — readSpringBootTestProperties not extracted (§6.5)
spring/junit5-spring/                          — entire module unchanged
testng/testng-listener/                        — no functional change (§6.3)
testng/testng-test-util/                       — unchanged
```

---

## 16. Open items the human must confirm before execution

1. **v3 issue number** — Phase 0 substitutes a real GitHub issue number into the v1 plan §7 forward reference and the §10 first bullet.
2. **v3 design choice** — out of scope for this plan, but should be referenced in the v3 issue: "ship `QuickPerfAbstractTestNGSpringContextTests`" vs. "re-architect listener as `IInvokedMethodListener`".
3. **Phase 5 / Phase 6 split** — can be merged into a single PR if reviewers prefer; phases are listed separately to minimize the diff per checkpoint.
