# R2DBC support

QuickPerf supports reactive databases via [r2dbc-proxy](https://github.com/r2dbc/r2dbc-proxy). All `@Expect*`/`@Disable*`/`@Display*`/`@Analyze*` SQL annotations work transparently against R2DBC `ConnectionFactory`s — the reactive query path is bridged into the same `SqlExecutions` measure that JDBC tests use, so per-query rendering, counts, column counts, transaction events and connection lifecycle events are all captured.

## Getting started

### Spring Boot 3 — `spring-boot-r2dbc-sql-starter`

The starter wires every `ConnectionFactory` bean with r2dbc-proxy and the QuickPerf listener via a `BeanPostProcessor`. Add it to your test runtime dependencies:

```xml
<dependency>
    <groupId>org.quickperf</groupId>
    <artifactId>quick-perf-springboot-r2dbc-sql-starter</artifactId>
    <version>1.1.1-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

Combined with `quick-perf-junit5` (or `quick-perf-junit4` / `quick-perf-testng`) on the test classpath, every `@Test` method is automatically able to use the SQL annotations.

### Manual wiring (no Spring)

Wrap your `ConnectionFactory` with `QuickPerfProxyConnectionFactoryBuilder` (defined by `quick-perf-sql-annotations-r2dbc`) and register the result as your test database:

```java
ConnectionFactory original = ConnectionFactories.get(...);
ConnectionFactory monitored =
    QuickPerfProxyConnectionFactoryBuilder.wrap(original, "myConnectionFactory");
```

### Dependency management

Two BOMs are available; pick the one that matches your project:

* **`quick-perf-bom`** — umbrella BOM for every QuickPerf module (JFR, JVM, SQL JDBC + R2DBC, JUnit 4/5, TestNG, every Spring/Spring Boot adapter). Use it when your project mixes JDBC and R2DBC tests, or when you already import this BOM for non-R2DBC reasons.

  ```xml
  <dependencyManagement>
      <dependencies>
          <dependency>
              <groupId>org.quickperf</groupId>
              <artifactId>quick-perf-bom</artifactId>
              <version>1.1.1-SNAPSHOT</version>
              <type>pom</type>
              <scope>import</scope>
          </dependency>
      </dependencies>
  </dependencyManagement>
  ```

* **`quick-perf-r2dbc-bom`** *(recommended for R2DBC-only projects)* — a smaller BOM that pins:
  * the three QuickPerf R2DBC-related modules (`quick-perf-sql-annotations`, `quick-perf-sql-annotations-r2dbc`, `quick-perf-springboot-r2dbc-sql-starter`);
  * the three QuickPerf test runners (`quick-perf-junit4`, `quick-perf-junit5`, `quick-perf-testng`);
  * the three external R2DBC libraries against which the QuickPerf R2DBC code is verified — `io.r2dbc:r2dbc-spi:1.0.0.RELEASE`, `io.r2dbc:r2dbc-proxy:1.1.4.RELEASE`, `io.r2dbc:r2dbc-pool:1.0.1.RELEASE`.

  ```xml
  <dependencyManagement>
      <dependencies>
          <dependency>
              <groupId>org.quickperf</groupId>
              <artifactId>quick-perf-r2dbc-bom</artifactId>
              <version>1.1.1-SNAPSHOT</version>
              <type>pom</type>
              <scope>import</scope>
          </dependency>
      </dependencies>
  </dependencyManagement>
  ```

The QuickPerf coordinates that overlap (`quick-perf-sql-annotations` etc.) appear in both BOMs at the same version, so importing both is safe but rarely needed.

## Surefire requirement — `parallel=none`

The R2DBC adapter dispatches r2dbc-proxy events to active `SqlRecorder` instances via a JVM-global hook (`SqlRecorderHook`). Recorder activation, in contrast, is per-thread (an `InheritableThreadLocal`), and Reactor schedulers are free to dispatch r2dbc-proxy callbacks on threads that are not children of the test thread. Running R2DBC tests under Surefire `parallel=all` (the QuickPerf project default) therefore risks recorders missing events or — under heavy contention — capturing events from a sibling test.

For modules that contain reactive SQL tests, override Surefire to single-threaded execution:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <parallel>none</parallel>
    </configuration>
</plugin>
```

This is what `spring/junit5-spring-boot-3-r2dbc-test` and the other R2DBC test modules in this repository do.

## System properties — `-D` only

QuickPerf reads its configuration switches (e.g. `org.quickperf.simplifiedSqlDisplay`) directly via `System.getProperty`. The Spring `Environment` is **not** consulted — `application.properties`, `application.yml` and `@SpringBootTest(properties=...)` overrides have no effect on QuickPerf's behaviour, even in R2DBC tests where Spring otherwise drives every other piece of configuration.

If you need to flip a QuickPerf property in an R2DBC test, set it as a JVM `-D` system property in your Surefire (or local) configuration:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <systemPropertyVariables>
            <org.quickperf.simplifiedSqlDisplay>true</org.quickperf.simplifiedSqlDisplay>
        </systemPropertyVariables>
    </configuration>
</plugin>
```

## ConnectionPool wrapping guarantee

QuickPerf needs to keep the runtime type of a wrapped `ConnectionFactory` assignable to `io.r2dbc.pool.ConnectionPool`, because Spring's R2DBC auto-configuration and several application frameworks call `instanceof ConnectionPool`/cast to it (for example, to flag a pool as eligible for liveness probes). The Spring Boot 3 starter therefore creates a CGLIB subclass of `ConnectionPool` that delegates every method to an inner `ProxyConnectionFactory.builder(target)`-wrapped `ConnectionFactory`.

* When CGLIB is on the classpath (the default for Spring Boot 3 applications via `spring-core`), the wrapped bean is a true subclass of `ConnectionPool`, satisfying any downstream `instanceof` check.
* When CGLIB is **not** on the classpath (an unusual configuration), the BPP falls back to a plain JDK `Proxy` that implements `ConnectionFactory` + `Wrapped` + `Disposable` + `Closeable`. In this fallback mode `instanceof ConnectionPool` returns `false`. Downstream code that depends on the cast must keep CGLIB available.

`ConnectionPool.dispose()` and `close()` are non-final at version `1.0.1.RELEASE` (the version pinned by `quick-perf-r2dbc-bom`), which is what makes the CGLIB subclassing path possible.

## Pool validation queries are not counted

`ConnectionPool` issues a "validation query" against each idle connection at acquisition time when `ConnectionFactoryOptions.VALIDATION_QUERY` is configured. These queries are an internal pool concern — they are filtered out by the QuickPerf R2DBC listener and are **not** counted by `@ExpectSelect`, `@ExpectMaxSelect`, `@AnalyzeSql`, etc. Only the queries explicitly issued by your test (or by the application code under test) appear in `SqlExecutions`.

## Connection acquisition timing caveat (r2dbc-proxy 1.1.4)

`r2dbc-proxy 1.1.4`'s `ConnectionFactoryCallbackHandler` constructs a `StopWatch` for `ConnectionFactory.create()` but never calls `start()` on it before reading `getElapsedDuration()`. As a result, `MethodExecutionInfo.getExecuteDuration()` always returns `Duration.ZERO` for connection-create events at this version of r2dbc-proxy.

QuickPerf works around this by timing connection acquisitions itself, in `R2dbcConnectionLifecycleListener`, using `System.nanoTime()` stashed in the per-execution `ValueStore`. The `[time: Xms]` suffix that `@ProfileConnection` prints next to each `io.r2dbc.spi.ConnectionFactory.create()` event is therefore measured by QuickPerf, not by r2dbc-proxy. If a future r2dbc-proxy release fixes the bug, the existing fallback (`info.getExecuteDuration()`) will simply produce the same number.

## Supported annotations

The annotations below are verified against R2DBC in `spring/junit5-spring-boot-3-r2dbc-test` and `sql/sql-annotations-r2dbc`:

| Annotation | Status |
|---|---|
| `@AnalyzeSql` | full |
| `@DisableLikeWithLeadingWildcard` | full |
| `@DisableQueriesWithoutBindParameters` | full |
| `@DisableSameSelects` | full |
| `@DisableSameSelectTypesWithDifferentParamValues` | full |
| `@DisableStatements` | full |
| `@DisplaySql` | full |
| `@DisplaySqlOfTestMethodBody` | full |
| `@EnableLikeWithLeadingWildcard` | full |
| `@EnableQueriesWithoutBindParameters` | full |
| `@EnableSameSelects` | full |
| `@EnableSameSelectTypesWithDifferentParamValues` | full |
| `@EnableStatements` | full |
| `@ExpectDelete` | full |
| `@ExpectInsert` | full |
| `@ExpectJdbcBatching` | full |
| `@ExpectJdbcQueryExecution` | full |
| `@ExpectMaxDelete` | full |
| `@ExpectMaxInsert` | full |
| `@ExpectMaxJdbcQueryExecution` | full |
| `@ExpectMaxQueryExecutionTime` | full |
| `@ExpectMaxSelect` | full |
| `@ExpectMaxSelectedColumn` | full |
| `@ExpectMaxUpdate` | full |
| `@ExpectMaxUpdatedColumn` | full |
| `@ExpectNoConnectionLeak` | full |
| `@ExpectSelect` | full |
| `@ExpectSelectedColumn` | full |
| `@ExpectUpdate` | full |
| `@ExpectUpdatedColumn` | full |
| `@ProfileConnection` | full |
| `@ExpectQueryBatching` *(database-agnostic alias of `@ExpectJdbcBatching`)* | full |
| `@ExpectQueryExecution` *(alias of `@ExpectJdbcQueryExecution`)* | full |
| `@ExpectMaxQueryExecution` *(alias of `@ExpectMaxJdbcQueryExecution`)* | full |
