# PR1 — Additional thread-safety fixes to address

> Scope add-on raised by review of `pr1-plan.md`: the active-set fallback
> introduced in §2.1 / §2.2 starts routing `addQueryExecution(...)` and
> `ConnectionListener` callbacks onto worker threads (Tomcat, `@Async`,
> `@Scheduled`, `CompletableFuture`, message listeners, virtual threads,
> Reactor pools, etc.). The plan only thread-safes `SqlExecutions` (F0).
> Three other touched classes also have unsafe mutable state that
> becomes reachable from worker threads after PR1 lands. Today
> (`InheritableThreadLocal`-based attribution) those classes are de-facto
> single-threaded, so the races are **regressions introduced by PR1**, not
> pre-existing bugs.

## 1. Why these fixes are PR1-blocking

Pre-PR1, `InheritableThreadLocal` only inherits at thread *creation*. Pool
threads (Tomcat, `@Async` executors, etc.) predate the test, so workers
see an empty per-thread map → these recorders / listeners are never
invoked off the test thread today → mutable state is single-threaded.

After PR1, `getSqlRecorders()` and `getConnectionListeners()` fall back to
the process-wide `ACTIVE_*` set when the per-thread map is empty.
Workers now invoke recorder / listener callbacks concurrently (and
across active tests). Without the fixes below, three new failure modes
appear:

1. **Lost batch-size entries / torn writes** in `SqlStatementBatchRecorder`
   (backs `@ExpectJdbcBatching`).
2. **`ConcurrentModificationException` and false `@ExpectNoConnectionLeak`
   failures** in `ConnectionLeakListener`.
3. **Missed "several datasource proxies" stderr warnings** from
   `DataSourceProxyVerifier` due to non-volatile fields written on workers
   and read on the test thread.

## 2. Files to change

### 2.1 `sql/sql-annotations/.../batch/SqlStatementBatchRecorder.java`

**Problem.** `addQueryExecution(...)` does check-then-act on
`differentBatchSizes` (`isNewBatchSize` → `createTableWithNewBatchSize` →
field reassignment). Under concurrent workers: lost batch-size entries.
`previousStatementsAreBatched` is a non-volatile boolean read/written
from multiple threads.

**Fix (JDK 1.7 compatible — same pattern as `ACTIVE_RECORDERS` in §2.1 of
`pr1-plan.md`).**

- Replace `private int[] differentBatchSizes = new int[0];` with
  ```java
  private final Set<Integer> differentBatchSizes =
          Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
  ```
  The set dedups by value, so `isNewBatchSize(int)` and
  `createTableWithNewBatchSize(int)` can be **deleted**.
- Make `previousStatementsAreBatched` `volatile`.
- Replace the body of `addQueryExecution` with a single
  `differentBatchSizes.add(execInfo.getBatchSize())` after the existing
  guards, and update `previousStatementsAreBatched` last (write-after-add
  preserves the existing semantics).
- `findRecord(...)` converts the set back to `int[]` (sorted is fine but
  not required by callers; keep insertion-friendly default):
  ```java
  int[] sizes = new int[differentBatchSizes.size()];
  int i = 0;
  for (Integer s : differentBatchSizes) { sizes[i++] = s; }
  return new SqlBatchSizes(sizes);
  ```
  Same conversion in `stopRecording` for the forked-JVM
  `saveCharacteristicsOfBatchExecutions(...)` call.
- Drop the now-stale comment about "int array is used to avoid boxing"
  (boxing was per-query; now it's per-distinct-batch-size, which is
  bounded and irrelevant on the hot path).

**Net production diff:** roughly **−15 / +10 lines** (the file shrinks
because the two helper methods go away).

### 2.2 `sql/sql-annotations/.../connection/ConnectionLeakListener.java`

**Problem.** `private final List<Connection> connections = new ArrayList<>();`
is mutated from `theDatasourceGetsTheConnection(...)` (add) and `close(...)`
(remove). Under PR1's listener fallback, workers from concurrent tests
can hit the same `ConnectionLeakListener` instance → unsafe `ArrayList`
mutation → `ConcurrentModificationException` and lost / spurious
entries; cross-test attribution can leave a foreign connection in the
list, causing a false `@ExpectNoConnectionLeak` failure.

**Fix.**

- Replace the `List<Connection> connections` field with a
  `Set<Connection>` backed by `ConcurrentHashMap`:
  ```java
  private final Set<Connection> connections =
          Collections.newSetFromMap(new ConcurrentHashMap<Connection, Boolean>());
  ```
  (`Connection` instances are unique by identity — list duplicates aren't
  expected today, and `Set.add` / `Set.remove` preserve the existing
  semantics. Identity equality is the documented JDBC contract for
  `Connection`.)
- All call sites (`connections.add(...)`, `connections.remove(...)`,
  `connections.isEmpty()`, `connections.clear()`) compile unchanged.
- `stopRecording` snapshot ordering already does
  `unregister → check isEmpty → clear`, which is the right order: after
  `unregister`, the listener leaves `ACTIVE_LISTENERS` and workers stop
  routing into it (per the I2 ordering invariant in `pr1-plan.md` §4).

**Net production diff:** roughly **3 lines** (field type + import).

### 2.3 `sql/sql-annotations/.../DataSourceProxyVerifier.java`

**Problem.** `private int listenerIdentifier;` and
`private boolean quickPerfBuiltSeveralDataSourceProxies;` are written in
`addQueryExecution` (now reachable from workers) and read in
`stopRecording` (test thread). No happens-before → the test thread can
miss the "several proxies" warning.

**Fix.**

- Add `volatile` to both fields.
- The double-write to `listenerIdentifier` in
  `initListenerIdentifierIfNotAlreadyInitialized(...)` is a benign
  TOCTOU (two threads racing to seed the same value) — `volatile` is
  enough; CAS isn't worth the complexity.

**Net production diff:** **2 keywords.** Existing
`DataSourceProxyVerifierTest` continues to pass unchanged.

## 3. Tests to add

Mirror the F0 unit-test pattern from `pr1-plan.md` §3.1: tight,
sub-second concurrency stress that fails on `master`-equivalent code and
turns green after the fix. Both files live under
`sql/sql-annotations/src/test/java/...`.

### 3.1 `SqlStatementBatchRecorderConcurrencyTest`

- `concurrent_addQueryExecution_does_not_lose_distinct_batch_sizes`
  — N threads × M `addQueryExecution(...)` calls, each with a deterministic
  set of batch sizes; assert the recorded `int[]` (read via `findRecord`)
  contains exactly the expected distinct values, no duplicates, no
  missing entries.
- `concurrent_addQueryExecution_does_not_throw`
  — same stress, asserts no `ArrayIndexOutOfBoundsException` /
  `NullPointerException` from torn `int[]` reassignment.

Both must fail against the current `int[]` + `createTableWithNewBatchSize`
implementation under contention and pass after the `Set<Integer>` swap.
~70 lines.

### 3.2 `ConnectionLeakListenerConcurrencyTest`

- `concurrent_acquire_and_release_does_not_throw_or_leak`
  — N threads alternating `theDatasourceGetsTheConnection(c)` and
  `close(c)` on a shared listener; assert no
  `ConcurrentModificationException`, and that after all threads finish
  with paired acquire/release, `connections.isEmpty()` is true.
- `acquire_only_leaves_connection_tracked`
  — sanity check that under a non-paired workload (acquires without
  closes), all acquired connections are present in the final set.

~50 lines.

### 3.3 `DataSourceProxyVerifier`

No new tests required for the `volatile` keyword change. Existing
`DataSourceProxyVerifierTest` is still meaningful as a single-thread
regression guard. (Adding a concurrent test would be ~30 lines and is
optional.)

## 4. Out of scope for PR1 (deferred — note in `pr1-plan.md` §5)

- **Cross-test contamination warnings on `SqlBatchSizes` /
  `BooleanMeasure`.** Neither has a `format(Collection<PerfIssue>)`
  method like `SqlExecutions`, so propagating the contamination flag
  into the assertion message requires either making them implement
  `ViewablePerfRecordIfPerfIssue` (with new `crossTestContamination`
  flag, setter, and warning text per measure type) or routing the
  warning through a different channel (e.g., `System.err`, like
  `DataSourceProxyVerifier`'s "several proxies" message). Both are
  larger architectural decisions than PR1 should carry. The
  thread-safety fixes above eliminate the **corruption** failure mode;
  cross-test **attribution** on `@ExpectJdbcBatching` /
  `@ExpectNoConnectionLeak` remains silently incorrect and is owned by
  PR3 (tightening the warning into a hard failure across all annotation
  types).

- **Fully thread-safe `DataSourceProxyVerifier` warning under genuinely
  concurrent first-write races.** `volatile` covers visibility; the
  `initListenerIdentifierIfNotAlreadyInitialized` TOCTOU is benign
  (same-value double-write). A `AtomicInteger`/`AtomicBoolean` rewrite
  is unnecessary at PR1 scope.

## 5. Updates needed in `pr1-plan.md` (when this is folded in)

1. **§1 / §2 lead-in:** stop calling `SqlStatementBatchRecorder`
   "non-asserting"; it backs `@ExpectJdbcBatching`. Group it with
   `PersistenceSqlRecorder` as an asserting recorder requiring
   thread-safe state.
2. **§2.3 recorders-touched table:** change the
   `SqlStatementBatchRecorder` row from "none" to "thread-safe
   `differentBatchSizes` + `volatile` `previousStatementsAreBatched`
   (see PR1-additional-thread-safety §2.1)".
3. **§2 (new subsection or appended):** add §2.7 `ConnectionLeakListener`
   thread-safe `connections` set and §2.8 `DataSourceProxyVerifier`
   `volatile`.
4. **§3 tests-to-add:** add §3.5 `SqlStatementBatchRecorderConcurrencyTest`
   and §3.6 `ConnectionLeakListenerConcurrencyTest` (per §3.1 / §3.2 of
   this file).
5. **§4 invariants:** add I12 ("`SqlStatementBatchRecorder.differentBatchSizes`
   uses a thread-safe set; no index-based reads") and I13
   ("`ConnectionLeakListener.connections` uses a thread-safe set"). I14
   ("`DataSourceProxyVerifier` fields are `volatile`") is optional.
6. **§5 known limitations:** add row "Cross-test attribution on
   `@ExpectJdbcBatching` / `@ExpectNoConnectionLeak` remains silently
   incorrect — PR3 tightens this".
7. **§7 commit breakdown:** add a fourth commit ("Thread-safe state on
   `SqlStatementBatchRecorder`, `ConnectionLeakListener`, and
   `DataSourceProxyVerifier`") landing before commit 2 (dual-map
   registries) so the active-set fallback never goes live without these
   classes being thread-safe. Or alternatively, fold these changes into
   commit 2 to keep the regression-introduction window closed within a
   single commit.

## 6. Estimated total surface

| Item | Lines |
|---|---|
| `SqlStatementBatchRecorder` production diff | ~−15 / +10 |
| `ConnectionLeakListener` production diff | ~3 |
| `DataSourceProxyVerifier` production diff | 2 |
| `SqlStatementBatchRecorderConcurrencyTest` (new) | ~70 |
| `ConnectionLeakListenerConcurrencyTest` (new) | ~50 |
| **Total added** | **~150 LoC, mostly tests** |

Comparable in size to the F0 `SqlExecutions` change already in PR1.
