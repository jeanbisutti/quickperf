# PR1 — Plan

> Foundation: drop `InheritableThreadLocal`, add a process-wide active-recorder
> set so worker threads (Tomcat, `@Async`, `@Scheduled`, `CompletableFuture`,
> message listeners, virtual threads, gRPC, Reactor pools, etc.) can find
> registered recorders, surface a cross-test contamination warning when ≥ 2
> tests are concurrently active, and migrate `SqlExecutions` from
> `ArrayDeque` to `ConcurrentLinkedDeque` for thread-safe concurrent appends.
> Internal-only changes. Cross-test contamination state is owned by
> `SqlRecorderRegistry` itself (not by the `SqlRecorder` interface),
> so the `SqlRecorder` interface and the two display-only recorders
> (`DisplaySqlRecorder`, `DisplaySqlOfTestMethodBodyRecorder`) are
> unchanged. JDK 1.7 source level therefore forces no design
> compromises.
>
> **Companion document.** `SqlStatementBatchRecorder` (which backs
> `@ExpectJdbcBatching`), `ConnectionLeakListener` (which backs
> `@ExpectNoConnectionLeak`), and `DataSourceProxyVerifier` all hold
> mutable state that is single-threaded today but becomes reachable
> from worker threads once the active-set fallback in §2.1 / §2.2
> goes live. Their thread-safety fixes are tracked in the companion
> plan **`pr1-additional-thread-safety.md`** and ship together with
> this PR. They are intentionally kept out of this document so each
> plan stays focused; see §1 "Out of scope" and §7 for the integration
> point.

---

## 1. Goal

Replace **thread-as-attribution-key** (broken `InheritableThreadLocal`) with:

1. A **dual-map registry**: per-thread fast-path for parallel test isolation +
   process-wide active-recorder set for cross-thread visibility.
2. A **cross-test contamination flag** raised when ≥ 2 tests are concurrently
   active and a worker thread takes the active-set fallback. The flag is
   surfaced in the assertion error message so users get an actionable
   warning instead of a silent wrong count.
3. A **thread-safe `SqlExecutions` buffer**: replace the `ArrayDeque`
   backing field with a `ConcurrentLinkedDeque` so concurrent `addLast`
   from worker threads (Tomcat, `@Async`, `CompletableFuture`, etc.) can
   no longer corrupt the buffer (lost / duplicated entries,
   `ArrayIndexOutOfBoundsException`). This is the **F0 fix**
   (foundational data-race fix #0) — orthogonal to attribution but required
   by every routing variant since the active-set fallback now legitimately
   appends from multiple threads.

Out of scope (deferred to later PRs):
- **Per-Connection recorder snapshot** in `QuickPerfDatabaseConnection.buildFrom(...)`.
  Originally drafted for PR1, but it cannot work via `DataSourceQuickPerfListener.afterQuery`
  alone: `executionInfo.getStatement().getConnection()` returns the inner
  datasource-proxy connection, **not** the outer `QuickPerfDatabaseConnection`,
  and `Connection.unwrap` walks toward driver-specific types, never outward
  to wrappers. Implementing this properly requires either wrapping every
  Statement returned by `QuickPerfDatabaseConnection.createStatement(...)`
  so `Statement.getConnection()` round-trips back to the QuickPerf wrapper,
  or attaching the snapshot at pool-checkout time via a Quarkus
  `PoolInterceptor` / Micronaut `DataSource` listener. Both are larger
  changes than PR1 should carry. The snapshot now lives in PR4 (Quarkus)
  and PR5 (Micronaut). *(2025-11: this deferral was re-validated by an
  end-to-end synthesis attempt — see `pr1-per-connection-snapshot-plan.md`
  §12 for the hostile review that confirmed listener-side identity
  dispatch on `wrapper.delegate` also fails, because dsproxy stamps
  `ExecutionInfo` with the raw underlying `Statement` whose
  `getConnection()` returns the raw pool connection — one level **below**
  `wrapper.delegate`. The original deferral analysis was correct;
  `threading-fix-pr-sequence.md §PR1` has been amended to remove the
  third PR1 commitment.)*
- `QuickPerfContext.wrap(...)` API (PR2).
- Tightening the warning into a hard failure (PR3).
- Reactive / R2DBC modules (PR6 / PR7).

In scope for the same PR, tracked in a sibling document:
- **Thread-safe state on `SqlStatementBatchRecorder`,
  `ConnectionLeakListener`, and `DataSourceProxyVerifier`.** These three
  classes are de-facto single-threaded today (`InheritableThreadLocal`
  prevents workers from invoking them) but become reachable from worker
  threads as soon as the active-set fallback in §2.1 / §2.2 goes live.
  Their fixes — thread-safe `differentBatchSizes` set + `volatile`
  `previousStatementsAreBatched` for batch tracking, thread-safe
  `connections` set for connection-leak tracking, `volatile` fields on
  the proxy verifier — are tracked in **`pr1-additional-thread-safety.md`**
  and ship in the same PR. Order requirement: those changes must land
  **before or together with** the dual-map registry commit (§7), so the
  active-set fallback never goes live against unsafe state.

---

## 2. Files touched

### 2.1 `sql/sql-annotations/.../SqlRecorderRegistry.java`

Source level for this module is **JDK 1.7** (`maven.compiler.source=1.7`),
so no lambdas, no `computeIfAbsent`, no `default` methods, no diamond on
field initializers in places where 1.7 doesn't allow it (1.7 supports
diamond on local vars and field initializers — OK).

Changes:

- Remove the `InheritableThreadLocal<Map<Class, SqlRecorder>>` field.
- Replace with three static fields:
  - `private static final ConcurrentMap<Long, ConcurrentMap<Class<? extends SqlRecorder>, SqlRecorder>> PER_THREAD_RECORDERS = new ConcurrentHashMap<…>();`
  - `private static final ConcurrentMap<SqlRecorder, Long> ACTIVE_RECORDERS = new ConcurrentHashMap<SqlRecorder, Long>();`
    Maps each live recorder (identity-keyed — `SqlRecorder` does not
    override `equals`/`hashCode`, so `ConcurrentHashMap` falls back to
    `Object.equals`/`hashCode`, which is identity for these recorders)
    to the **thread id of the registering test thread** ("owner tid").
    The owner tid is the contamination trigger axis (see I4 below):
    counting distinct owner tids in a worker-fallback snapshot tells us
    "how many distinct tests have a recorder live right now." The
    previous design used a `Set<SqlRecorder>` and read the trigger from
    a separate `PER_THREAD_RECORDERS.size()`; that version had two
    silent-wrong-count races (snapshot-vs-trigger drift; single-test
    multi-recorder false positives — see I4 prose). Storing the owner
    tid alongside the recorder collapses snapshot and trigger into one
    observation and gets the trigger semantics right. (`ConcurrentHashMap.newKeySet`
    is JDK 8+ and would be unsuitable anyway since we now need an
    associated value, not just membership — JDK 1.7 compatible.)
  - `private static final Set<SqlRecorder> CROSS_TEST_CONTAMINATED = Collections.newSetFromMap(new ConcurrentHashMap<SqlRecorder, Boolean>());`
    Tracks recorders that have observed at least one cross-test
    worker-thread fallback. Read by `PersistenceSqlRecorder.findRecord`;
    never inspected by other recorders. Does **not** mirror
    `ACTIVE_RECORDERS` membership — its entries persist past
    `unregister` so that `findRecord` (which the framework calls *after*
    `stopRecording` / `unregister`) can still observe the flag, and are
    evicted by `findRecord`'s own `finally` block (primary, exception-safe
    — see I14) with `cleanResources` and `clear()` as defensive backstops
    (see I11).
- Replace the existing `private final Map<Class, SqlRecorder> sqlRecorderByTypeOfTestJvm = new HashMap<>()`
  (the forked-JVM branch) with a `ConcurrentHashMap` to remove the latent
  data race when a forked JVM runs SQL on Tomcat / executor threads.
  The forked-JVM `unregister` branch — which is currently a no-op
  (`SqlRecorderRegistry.java:51-55`) — must now actually remove the
  recorder via `sqlRecorderByTypeOfTestJvm.remove(recorder.getClass(), recorder)`
  (CAS variant — only removes if the same instance is still mapped).
  Without this, I10 (unregister-first ordering in `stopRecording`) is a
  no-op in forked-JVM mode and workers can still find the recorder while
  `flush(...)` runs, producing torn file/in-memory state.
- Cache the `Long`-boxed thread id once per public registry method
  invocation (one local variable threaded through the per-thread map
  lookup, the `ACTIVE_RECORDERS` updates, and any follow-up reads). The
  cache reuses one boxed `Long` across multiple `ConcurrentMap` calls
  within one invocation rather than re-boxing for each call; pre-existing
  pool thread ids are typically well above the JDK's `LongCache` range
  (-128..127), so the boxing itself is unavoidable — this micro-optimization
  is about avoiding *re-boxing* within one call, not about cache hits.
- Recorders are deduplicated **by identity** in `ACTIVE_RECORDERS`'s key
  set (`SqlRecorder` does not override `equals`/`hashCode` — the existing
  `PersistenceSqlRecorder`, `DisplaySqlRecorder`, etc. all rely on
  identity equality). This is the intended behavior.

Class-level Javadoc to drop on `SqlRecorderRegistry` (explains the
dual-map design for future readers of the source — independent of this
plan):

```java
/*
 * Dual-map registry rationale
 * ---------------------------
 * QuickPerf historically attributed a SQL execution to a test via
 * InheritableThreadLocal: the test thread registered a recorder and
 * child threads inherited it. That model breaks in practice because
 * the SQL of a realistic Spring / Jakarta / Quarkus / Micronaut app is
 * almost never executed on a *child* of the test thread — it runs on
 * threads from pre-existing pools (Tomcat HTTP workers, @Async and
 * @Scheduled executors, JMS / Kafka message listeners, virtual
 * threads, gRPC handlers, Reactor schedulers, ...). Those threads have
 * no inheritance relation to the test thread, so they observed null
 * and the SQL was silently dropped.
 *
 * We therefore look up recorders along TWO axes:
 *
 *   1) PER_THREAD_RECORDERS  ::  Long(threadId) -> recorders by class.
 *      Fast-path for the test thread itself. Guarantees parallel-test
 *      isolation: under Surefire `parallel=all, threadCount=5`, each
 *      test's assertions only ever see its own recorders, never a
 *      neighbour test's.
 *
 *   2) ACTIVE_RECORDERS  ::  process-wide identity set of every
 *      recorder currently live (between register() and unregister()).
 *      Worker-thread fallback: a Tomcat / @Async / virtual / gRPC
 *      thread executing SQL on behalf of a test does not appear in
 *      PER_THREAD_RECORDERS (its id is not the test thread's id), so
 *      we fall back to this set and the SQL is still attributed to a
 *      live recorder.
 *
 * Why we need BOTH, and why neither alone is enough:
 *
 *   - Per-thread map only  =>  regression to the InheritableThreadLocal
 *     era: pool-owned worker threads observe nothing, SQL is silently
 *     lost, and assertions like @ExpectSelect see 0 instead of N.
 *   - Active set only      =>  parallel tests cross-contaminate: a
 *     worker thread from test A would observe test B's recorders with
 *     no way to disambiguate, producing wrong counts under
 *     `parallel=all`.
 *
 * The dual-map design gives the test thread a clean, isolated fast
 * path while still letting unrelated worker threads find the recorders
 * they need to record SQL against.
 *
 * When a worker actually takes the fallback AND >= 2 tests are
 * concurrently active, attribution is genuinely ambiguous — we cannot
 * tell which test the worker belongs to. We then raise the recorder
 * into CROSS_TEST_CONTAMINATED; the assertion-error builder surfaces
 * this as a "results may include SQL from a concurrent test" warning,
 * so the user gets an actionable signal instead of a silent wrong
 * count.
 */
```

Operations (compound-write ordering matters — see §4):

```
register(recorder):
    Long tid = Long.valueOf(Thread.currentThread().getId())
    if TEST_CODE_EXECUTING_IN_NEW_JVM:
        sqlRecorderByTypeOfTestJvm.put(recorder.getClass(), recorder)
        ACTIVE_RECORDERS.put(recorder, tid)        # owner-tid map populated in BOTH branches (I7)
        return
    ConcurrentMap perThread = PER_THREAD_RECORDERS.get(tid)
    if perThread == null:
        # putIfAbsent is defensive: only the thread itself can call
        # register for its own tid (one thread, one execution at a time),
        # but the CAS is cheap and guards against a future change to
        # call register from a different thread on behalf of `tid`.
        ConcurrentMap fresh = new ConcurrentHashMap<>()
        ConcurrentMap previous = PER_THREAD_RECORDERS.putIfAbsent(tid, fresh)
        perThread = (previous != null) ? previous : fresh
    SqlRecorder displaced = perThread.put(recorder.getClass(), recorder)
    if displaced != null && displaced != recorder:
        ACTIVE_RECORDERS.remove(displaced)   # avoid leaking the displaced one
        CROSS_TEST_CONTAMINATED.remove(displaced)       # evict stale contamination too (I3)
    ACTIVE_RECORDERS.put(recorder, tid)      # MUST be after the per-thread put (I1) — value = owner tid

unregister(recorder):
    if TEST_CODE_EXECUTING_IN_NEW_JVM:
        # Forked-JVM unregister: previously a no-op, now actually removes.
        # Required so I10 (unregister-first ordering in stopRecording)
        # actually stops worker visibility before flush(...) in forked mode.
        sqlRecorderByTypeOfTestJvm.remove(recorder.getClass(), recorder)   # CAS — only if same instance
        ACTIVE_RECORDERS.remove(recorder, ?)       # remove only if mapped (any owner tid)
        # ConcurrentMap has no "remove key, any value"; emulate via:
        #   Long owner = ACTIVE_RECORDERS.get(recorder);
        #   if (owner != null) ACTIVE_RECORDERS.remove(recorder, owner);
        # This is race-free against a same-thread re-register (which
        # would observe a fully-removed entry then put a fresh one).
        return
    ACTIVE_RECORDERS.remove(recorder, ?)     # same emulation as above; remove FIRST (I2)
    Long tid = Long.valueOf(Thread.currentThread().getId())
    ConcurrentMap perThread = PER_THREAD_RECORDERS.get(tid)
    if perThread != null:
        perThread.remove(recorder.getClass(), recorder)   # only if same instance
        if perThread.isEmpty():
            PER_THREAD_RECORDERS.remove(tid, perThread)   # CAS to avoid races

getSqlRecorders():
    if TEST_CODE_EXECUTING_IN_NEW_JVM:
        return sqlRecorderByTypeOfTestJvm.values()
    Long tid = Long.valueOf(Thread.currentThread().getId())
    ConcurrentMap perThread = PER_THREAD_RECORDERS.get(tid)
    if perThread != null:
        # Test-thread fast path. Returning here even when `perThread`
        # is empty (post-`clear()` sentinel — see clear() below) means
        # the test thread NEVER takes the worker fallback, so it cannot
        # accidentally observe and mark sibling tests' recorders.
        # This closes CR3 / S2 from the synthesis review:
        # `SqlRecorderRegistryTest.should_clear_sql_recorder_registry`
        # registers, clears, then reads — under parallel Surefire,
        # falling through to the active-set fallback would inflate the
        # result with sibling tests' recorders.
        return new ArrayList<SqlRecorder>(perThread.values())
    # worker-thread fallback (perThread == null — i.e. a thread that
    # has never registered AND has not been explicitly cleared by a
    # caller on this thread). Snapshot FIRST, then derive trigger from
    # the SAME snapshot, then mark, then return (I12).
    # The previous design read the trigger from PER_THREAD_RECORDERS.size()
    # AFTER the snapshot, opening a snapshot-vs-trigger race: a sibling
    # test could unregister between snapshot and trigger-read, dropping
    # PER_THREAD_RECORDERS.size() to 1 while the snapshot still contained
    # both recorders — worker would route SQL to both with no warning.
    # Snapshotting <recorder, owner-tid> entries closes that window: the
    # owner-tid count comes from the same observation as the recorder list.
    List<Map.Entry<SqlRecorder, Long>> snapshot =
        new ArrayList<Map.Entry<SqlRecorder, Long>>(ACTIVE_RECORDERS.entrySet())   # weakly-consistent
    Set<Long> distinctOwners = new HashSet<Long>()
    List<SqlRecorder> recorders = new ArrayList<SqlRecorder>(snapshot.size())
    for entry in snapshot:
        recorders.add(entry.getKey())
        distinctOwners.add(entry.getValue())
    # Trigger criterion: count distinct *owner test threads* in the
    # snapshot. NOT recorder count (would false-positive when a single
    # test registers @ExpectSelect + @ExpectJdbcBatching = 2 recorders).
    # NOT a separate read of PER_THREAD_RECORDERS.size() (would race the
    # snapshot — see C2 in the pre-PR1 review). Owner-tid count derived
    # from the same snapshot is the only race-free formulation. (I4)
    if distinctOwners.size() >= 2:
        # Mark only consumers (I12 / I14). PersistenceSqlRecorder is the
        # sole reader of the flag (§2.3, findRecord); marking other
        # recorder classes here would leak them in CROSS_TEST_CONTAMINATED
        # indefinitely — only PersistenceSqlRecorder.findRecord (I14)
        # evicts entries.
        for r in recorders:
            if r instanceof PersistenceSqlRecorder:
                CROSS_TEST_CONTAMINATED.add(r)
    return recorders

getSqlRecorderOfType(Class<T> type):
    if TEST_CODE_EXECUTING_IN_NEW_JVM:
        return type.cast(sqlRecorderByTypeOfTestJvm.get(type))
    Long tid = Long.valueOf(Thread.currentThread().getId())
    ConcurrentMap perThread = PER_THREAD_RECORDERS.get(tid)
    if perThread != null:
        # Test-thread fast path. Return null on a type miss WITHOUT
        # falling through to the worker fallback — a test thread that
        # has registered SOMETHING (or holds the post-`clear()`
        # sentinel) MUST NOT enter the contamination-marking path
        # (CR4 in the synthesis review). Without this guard, a test
        # that registers `DisplaySqlRecorder` and asks for
        # `PersistenceSqlRecorder` would fall through, snapshot
        # ACTIVE_RECORDERS, observe a sibling test's PersistenceSqlRecorder,
        # and mark contamination on it — a false positive originating
        # from a registry test asking about a type it has not registered.
        SqlRecorder hit = perThread.get(type)
        return hit != null ? type.cast(hit) : null
    # worker-thread fallback — snapshot FIRST, derive trigger, mark,
    # return (I12 / §3.2). Same snapshot-derived owner-tid trigger as
    # getSqlRecorders().
    List<Map.Entry<SqlRecorder, Long>> snapshot =
        new ArrayList<Map.Entry<SqlRecorder, Long>>(ACTIVE_RECORDERS.entrySet())
    Set<Long> distinctOwners = new HashSet<Long>()
    SqlRecorder match = null
    for entry in snapshot:
        Long owner = entry.getValue()
        SqlRecorder r = entry.getKey()
        distinctOwners.add(owner)
        if match == null && type.isInstance(r):
            match = r
    if distinctOwners.size() >= 2:
        # Mark every PersistenceSqlRecorder in the snapshot (I12 / I14) —
        # not just `match`. A worker that pulled @ExpectSelect's persistence
        # recorder out of the snapshot is still a contamination source for
        # every other live PersistenceSqlRecorder it routed SQL to.
        for entry in snapshot:
            SqlRecorder r = entry.getKey()
            if r instanceof PersistenceSqlRecorder:
                CROSS_TEST_CONTAMINATED.add(r)
    return match != null ? type.cast(match) : null

clear():
    Long tid = Long.valueOf(Thread.currentThread().getId())
    if TEST_CODE_EXECUTING_IN_NEW_JVM:
        # Symmetrically clear ACTIVE_RECORDERS for forked-JVM recorders too.
        for r in new ArrayList<SqlRecorder>(sqlRecorderByTypeOfTestJvm.values()):
            ACTIVE_RECORDERS.remove(r)
            CROSS_TEST_CONTAMINATED.remove(r)
        sqlRecorderByTypeOfTestJvm.clear()
        return
    ConcurrentMap perThread = PER_THREAD_RECORDERS.remove(tid)
    if perThread != null:
        for r in perThread.values():
            ACTIVE_RECORDERS.remove(r)
            # Backstop only — primary CROSS_TEST_CONTAMINATED eviction is
            # in PersistenceSqlRecorder.findRecord's finally block (I14).
            # On the production lifecycle path, unregister has already
            # emptied perThread before clear() runs, so this branch is a
            # no-op. It fires only when a test utility calls
            # SqlRecorderRegistry.INSTANCE.clear() directly (e.g. an
            # @After hook), keeping ad-hoc cleanup paths leak-free.
            CROSS_TEST_CONTAMINATED.remove(r)   # I11 / I14
    # Install an empty-marker submap so subsequent reads from THIS
    # thread (registry tests' "register; clear; read" pattern) still
    # observe `perThread != null` and DO NOT fall through to the
    # worker-thread fallback (CR3 in the synthesis review). Without
    # this, a post-clear read under Surefire `parallel=all,
    # threadCount=5` would snapshot ACTIVE_RECORDERS, see sibling
    # tests' recorders, and inflate the result. Subsequent
    # `register(recorder)` on this thread observes the empty submap
    # via the `putIfAbsent` "previous != null" branch and reuses it —
    # production paths keep their existing behavior.
    PER_THREAD_RECORDERS.put(tid, new ConcurrentHashMap<Class<? extends SqlRecorder>, SqlRecorder>())
```

Notes:
- `PER_THREAD_RECORDERS` is keyed by `Thread.getId()` (a `long`) rather than
  the `Thread` object — avoids retaining dead-thread references, but
  introduces a thread-id-reuse risk (see §5). The unregister-first
  discipline in `PersistenceSqlRecorder.stopRecording` (§2.4 / I10)
  closes the **normal** failure path (test method returns or throws —
  `unregister` runs before `flush` could throw, and the per-thread
  submap is cleaned). It does **not** close the path where
  (a) a test thread is killed without lifecycle, (b) an `Error` propagates
  past the framework's extension boundary, or (c) a custom test runner
  bypasses `QuickPerfTestExtension` / `QuickPerfJUnitRunner` entirely;
  those are explicitly deferred per §5.
- `getSqlRecorders()` worker fallback returns a defensive `ArrayList` copy;
  callers can iterate without `ConcurrentModificationException`.
- `unregister` uses `remove(key, value)` and `remove(tid, map)` (CAS
  variants — JDK 1.5+) to avoid wiping a freshly-installed entry from a
  re-registering thread. The `ACTIVE_RECORDERS.remove(recorder, owner)`
  CAS uses the value the registering thread itself stored (the test
  thread's tid), which is stable for the recorder's lifetime — no other
  thread mutates the owner-tid for an existing key, so the CAS succeeds
  unless `register` has displaced the recorder under a different
  (re-registered) entry, in which case the CAS no-ops correctly.
- `CROSS_TEST_CONTAMINATED` is intentionally **not** touched by `unregister`. The
  framework-level lifecycle is `startRecording → test → stopRecording
  (which calls unregister) → findRecord → cleanResources`, so
  `findRecord` runs *after* `unregister`. Clearing contamination on
  `unregister` would erase the flag before the only consumer reads it.
  Lifecycle discipline lives in `findRecord`'s own `finally` block
  (the consumer recorder's primary, exception-safe eviction point —
  see I14), in `cleanResources` (defensive backstop for the case where
  `findRecord` was never called), and in `clear()` (per-thread teardown,
  backstop for ad-hoc test utilities) — see §2.4, I11, and I14.
- Marking in the worker-thread fallback (I12) is restricted to
  `PersistenceSqlRecorder` instances. Other recorder classes
  (`DisplaySqlRecorder`, `SqlStatementBatchRecorder`,
  `DisplaySqlOfTestMethodBodyRecorder`) are returned to the worker
  for SQL routing but never enter `CROSS_TEST_CONTAMINATED` — they
  are not consumers of the flag (§2.3) and would leak indefinitely
  since no per-class `findRecord`/`cleanResources` evicts them.
- `ACTIVE_RECORDERS` is populated in **both** the forked-JVM and
  single-JVM branches of `register` (using the registering thread's
  tid as the owner key), so `getSqlRecorders` is unaffected by which
  branch fired registration. In forked-JVM mode the contamination
  trigger is structurally `false` — only one test runs per JVM, so
  `distinctOwners.size() <= 1` always — but populating the map keeps
  the code path uniform and the forked-JVM `unregister` symmetric.

### 2.2 `sql/sql-annotations/.../connection/ConnectionListenerRegistry.java`

Apply the same dual-map rewrite for `ConnectionListener` (mirror of
`SqlRecorderRegistry`). Same compound-write ordering. Same
forked-JVM `unregister` fix (currently a no-op — see
`ConnectionListenerRegistry.java:52-58` — must remove via
`connectionListenersOfTestJvm.remove(connectionListener)` so I10's
unregister-first discipline applies in forked mode too). The
forked-JVM `Collection<ConnectionListener>` (currently a plain
`ArrayList`) must become a **`CopyOnWriteArrayList`**.

The shape of the rewrite mirrors §2.1 line-for-line — same fields,
same compound-write ordering, same fall-through fix on
`getConnectionListenerOfType`. Pseudocode (replaces the existing
`InheritableThreadLocal<Map<Class<? extends ConnectionListener>,
ConnectionListener>>`):

```java
public class ConnectionListenerRegistry {

    public static final ConnectionListenerRegistry INSTANCE = new ConnectionListenerRegistry();

    private final ConcurrentMap<Long,
                                ConcurrentMap<Class<? extends ConnectionListener>,
                                              ConnectionListener>>
            PER_THREAD_LISTENERS = new ConcurrentHashMap<Long,
                                                         ConcurrentMap<Class<? extends ConnectionListener>,
                                                                       ConnectionListener>>();

    private final Set<ConnectionListener> ACTIVE_LISTENERS =
            Collections.newSetFromMap(new ConcurrentHashMap<ConnectionListener, Boolean>());

    // Forked-JVM branch (becomes thread-safe in PR1).
    private final List<ConnectionListener> connectionListenersOfTestJvm =
            new CopyOnWriteArrayList<ConnectionListener>();

    public void register(ConnectionListener listener) {
        // Compound-write ordering matches I3 / I12 in §2.1:
        //   1) ACTIVE first, 2) per-thread submap second.
        ACTIVE_LISTENERS.add(listener);

        long tid = Thread.currentThread().getId();
        ConcurrentMap<Class<? extends ConnectionListener>, ConnectionListener> perThread =
                PER_THREAD_LISTENERS.get(tid);
        if (perThread == null) {
            ConcurrentMap<Class<? extends ConnectionListener>, ConnectionListener> created =
                    new ConcurrentHashMap<Class<? extends ConnectionListener>, ConnectionListener>();
            ConcurrentMap<Class<? extends ConnectionListener>, ConnectionListener> previous =
                    PER_THREAD_LISTENERS.putIfAbsent(tid, created);
            perThread = (previous != null) ? previous : created;
        }

        // No displaced-listener eviction here: ConnectionListener has no
        // contamination flag in PR1. The previous mapping (if any) is simply
        // overwritten.
        perThread.put(listener.getClass(), listener);
    }

    public void unregister(ConnectionListener listener) {
        // ACTIVE first (mirror of register's compound-write order, reversed).
        ACTIVE_LISTENERS.remove(listener);

        long tid = Thread.currentThread().getId();
        ConcurrentMap<Class<? extends ConnectionListener>, ConnectionListener> perThread =
                PER_THREAD_LISTENERS.get(tid);
        if (perThread != null) {
            // Identity-aware remove: only evict if the same instance is mapped
            // (a sibling test on the same reused thread id may have replaced it).
            perThread.remove(listener.getClass(), listener);
            if (perThread.isEmpty()) {
                PER_THREAD_LISTENERS.remove(tid, perThread);
            }
        }
    }

    public Collection<ConnectionListener> getConnectionListeners() {
        if (QuickPerfState.isInTestJvm()) {
            return connectionListenersOfTestJvm;
        }
        long tid = Thread.currentThread().getId();
        ConcurrentMap<Class<? extends ConnectionListener>, ConnectionListener> perThread =
                PER_THREAD_LISTENERS.get(tid);
        if (perThread != null) {
            // Test thread (or descendant via worker that re-registered) — empty
            // submap is a sentinel for "post-clear" or "post-unregister", do
            // NOT fall through to the active-set fallback.
            return new ArrayList<ConnectionListener>(perThread.values());
        }
        // Worker-thread fallback: snapshot ACTIVE_LISTENERS once and return
        // the defensive copy — same I12 semantics as getSqlRecorders.
        return new ArrayList<ConnectionListener>(ACTIVE_LISTENERS);
    }

    public <T extends ConnectionListener> T getConnectionListenerOfType(Class<T> listenerClass) {
        long tid = Thread.currentThread().getId();
        ConcurrentMap<Class<? extends ConnectionListener>, ConnectionListener> perThread =
                PER_THREAD_LISTENERS.get(tid);
        if (perThread != null) {
            // Test-thread branch. CR3 / CR4-Opus discipline mirroring §2.1's
            // getSqlRecorderOfType: even on a test-thread MISS we MUST NOT
            // fall through to the active-set fallback. A test thread that
            // has unregistered (but not cleared) sees perThread == null
            // and falls through; a test thread that still has a non-empty
            // submap but for a different listener class (e.g. asking for
            // ConnectionLeakListener.class on a thread that only ever
            // registered DisplayConnectionLeakListener) would otherwise
            // route to a sibling test's listener via the active set.
            T hit = listenerClass.cast(perThread.get(listenerClass));
            return hit;  // null if not present — explicit null return, no fall-through
        }
        // Worker-thread fallback: snapshot ACTIVE_LISTENERS, find the first
        // matching instance.
        for (ConnectionListener candidate : ACTIVE_LISTENERS) {
            if (listenerClass.isInstance(candidate)) {
                return listenerClass.cast(candidate);
            }
        }
        return null;
    }

    public void clear() {
        // Same empty-marker submap discipline as §2.1: install a fresh empty
        // submap on the current thread so subsequent reads in the same test
        // (e.g. a `@Before`/`@After` chain that calls clear() then re-registers)
        // see perThread != null and take the test-thread branch — never the
        // active-set fallback. Matches the SqlRecorderRegistryTest
        // should_clear_sql_recorder_registry contract.
        long tid = Thread.currentThread().getId();
        // Drain (capture-and-clear) the submap first, then re-install an
        // empty marker.
        ConcurrentMap<Class<? extends ConnectionListener>, ConnectionListener> previous =
                PER_THREAD_LISTENERS.put(tid,
                    new ConcurrentHashMap<Class<? extends ConnectionListener>, ConnectionListener>());
        if (previous != null) {
            for (ConnectionListener listener : previous.values()) {
                ACTIVE_LISTENERS.remove(listener);
            }
        }
        // Forked-JVM branch is per-JVM, not per-thread — no need for a
        // marker, just clear the list.
        if (QuickPerfState.isInTestJvm()) {
            connectionListenersOfTestJvm.clear();
        }
    }
}
```

The shape is identical to §2.1's `SqlRecorderRegistry` (same field
declarations, same compound-write ordering, same `clear()`
empty-marker discipline, same test-thread-no-fall-through rule on
`getConnectionListenerOfType`) — the only deltas are (a) no
displaced-listener eviction in `register` because there is no
`CROSS_TEST_CONTAMINATED` set on the listener side, (b) the
`Collection<ConnectionListener>` return type uses an `ArrayList`
defensive copy rather than a derived `Set` because some listeners
register twice and rely on iteration-order semantics (see
`CopyOnWriteArrayList` rationale below), and (c) the forked-JVM
branch returns the live `connectionListenersOfTestJvm` reference
(it is per-JVM, not per-thread, and `CopyOnWriteArrayList` is
already snapshot-iteration-safe — no defensive copy needed).

**Why `CopyOnWriteArrayList` (and not a `ConcurrentHashMap`-backed `Set`
or a synchronized list):**
- *Read-mostly access pattern.* `getConnectionListeners()` is called on
  every JDBC operation that goes through `QuickPerfDatabaseConnection`
  (`createStatement`, `prepareStatement`, `commit`, `rollback`, `close`,
  …) — i.e., on every SQL statement and every connection lifecycle
  event. Writes happen only at test setup / teardown (a handful of
  registrations per test). `CopyOnWriteArrayList` makes the hot read
  path lock-free and allocation-free; the cost of array-copy on write
  is amortized over the entire test.
- *Safe iteration without external synchronization.* Every read site in
  `QuickPerfDatabaseConnection` iterates the collection
  (`for (ConnectionListener l : connectionListeners) { … }`). With a
  plain `ArrayList`, a concurrent `register` from a worker thread would
  raise `ConcurrentModificationException`. A synchronized list would
  require callers to hold the monitor for the duration of iteration,
  which is intrusive and easy to forget. `CopyOnWriteArrayList`
  iterators are inherently snapshot-based and never throw CME.
- *Preserves duplicates and insertion order.* The existing code uses
  `add(connectionListener)` (`ArrayList`) and tolerates duplicates;
  iteration order is insertion order. A `Set` would silently dedupe
  and/or randomize order. Some listeners may legitimately register
  twice (different identity, same class) and depend on being invoked
  twice. `CopyOnWriteArrayList` matches `ArrayList` semantics exactly,
  which is the minimum-surprise migration.

`getConnectionListenerOfType` gets the same active-set fallback as
`getSqlRecorderOfType`, with one key difference: **the contamination
flag (`CROSS_TEST_CONTAMINATED`) is `SqlRecorder`-only and is not
extended to `ConnectionListener` in PR1.** This is a deliberate
scope limitation, not a claim that listeners are immune. See the
caveat box below.

> **Note on `ConnectionLeakListener` cross-test attribution (PR1
> residual).** `ConnectionLeakListener` *does* hold per-test mutable
> state — its `connections` field (`ConnectionLeakListener.java:28`)
> is mutated on `theDatasourceGetsTheConnection(...)` and `close(...)`
> from worker threads. Once the dual-map listener registry's
> active-set fallback is live, a worker executing `Connection.close`
> on behalf of test B can be dispatched to test A's
> `ConnectionLeakListener` (and vice versa), with two visible
> failure modes:
>
>   1. **False-positive leak.** Test A's listener saw the open; test
>      B closes the connection on a worker thread; if B's close
>      arrives at A's listener after A has already unregistered, A's
>      `connectionLeak = !connections.isEmpty()` fires on a connection
>      that *was* in fact closed. Reverse direction: B's listener
>      sees a `close(...)` for a connection it never saw open
>      (silent no-op via `List.remove(Object)`).
>   2. **False-negative leak.** Test A leaks; test B closes; B's
>      listener accidentally absorbs the close that should have failed
>      A.
>
> The companion plan `pr1-additional-thread-safety.md` makes
> `ConnectionLeakListener.connections` thread-safe (so the data
> structure no longer corrupts under concurrent writes), but it does
> **not** fix attribution — the wrong listener still receives the
> event. The proper attribution fix is the per-`Connection` recorder
> snapshot at pool-checkout time (Quarkus `PoolInterceptor`,
> Micronaut `DataSource` listener, or wrapping
> `QuickPerfDatabaseConnection.createStatement(...)` so
> `Statement.getConnection()` round-trips back to the QuickPerf
> wrapper); that fix is deferred to PR4 (Quarkus) and PR5 (Micronaut).
> PR1 documents this as a known limitation in §5; users running
> `@ExpectNoConnectionLeak` under Surefire `parallel=all` should
> isolate via `@HeapSize` / `@Xmx` (forced JVM fork — one test per
> JVM) or `@ResourceLock("quickperf.sql")` for now. A
> `ConnectionListener`-side contamination flag analogous to
> `CROSS_TEST_CONTAMINATED` is intentionally **not** added in PR1
> because (a) `ConnectionLeakListener.findRecord` already reduces to
> `BooleanMeasure`, leaving no natural carrier for a warning-text
> prefix, and (b) the proper fix lives in PR4/PR5 anyway — adding a
> warning here would freeze a contract that PR4/PR5 will obsolete.

The snapshot-FIRST pattern from `getSqlRecorders` (I12) still applies
to the listener registry's fallback path even without contamination
marking: the snapshot is what is iterated on the JDBC hot path, so the
fallback returns a defensive `new ArrayList<ConnectionListener>(...)`
to avoid `ConcurrentModificationException` against concurrent
register/unregister.

> **Note on forked-JVM listener lifetime.** Once forked-JVM
> `unregister` is wired (see above), it actually removes the listener
> from `connectionListenersOfTestJvm`. With the standard `@HeapSize` /
> `@Xmx` pattern (one test per forked JVM) this is normally a no-op
> at end-of-life — the JVM exits — but it correctly closes the I10
> "stop being visible to the active-set fallback before `flush`"
> window for forked-JVM tests that start `@Async` / scheduler /
> Tomcat workers and rely on lifecycle ordering.

> **Note on listener-level thread-safety.** Once the active-set fallback
> in this section goes live, `ConnectionLeakListener.theDatasourceGetsTheConnection(...)`
> and `close(...)` are reachable from worker threads. Its internal
> `List<Connection>` field becomes a data race. The fix (a thread-safe
> `Set<Connection>`) is tracked in `pr1-additional-thread-safety.md` §2.2
> and ships in the same PR; the dual-map registry change here must not
> land before that fix.

### 2.3 Cross-test contamination flag plumbing — registry-owned (`SqlRecorder` unchanged)

The `SqlRecorder` interface, `DisplaySqlRecorder`,
`DisplaySqlOfTestMethodBodyRecorder` and `SqlStatementBatchRecorder` are
**not modified**. Cross-test contamination state is owned by `SqlRecorderRegistry`
in the new `CROSS_TEST_CONTAMINATED` set (§2.1) and exposed through three small
static methods:

```java
// sql/sql-annotations/.../SqlRecorderRegistry.java
static void markCrossTestContamination(SqlRecorder r) {
    CROSS_TEST_CONTAMINATED.add(r);
}

static boolean hasCrossTestContamination(SqlRecorder r) {
    return CROSS_TEST_CONTAMINATED.contains(r);
}

static void clearCrossTestContaminationFor(SqlRecorder r) {
    CROSS_TEST_CONTAMINATED.remove(r);
}
```

Visibility:

- `markCrossTestContamination`, `hasCrossTestContamination`, and
  `clearCrossTestContaminationFor` are all **package-private** —
  only the registry's own fallback paths and `PersistenceSqlRecorder`
  (same package) call them. `hasCrossTestContamination` is
  *intentionally* package-private rather than `public`: making it
  public would imply a stable public API contract that PR3 may
  evolve (when the warning hardens into a forced-failure
  `PerfIssue`), and there is no caller outside the package today
  (`PersistenceSqlRecorder.findRecord` lives in the same package).
  The argument-form (`SqlRecorder r`) keeps the door open for
  sibling-package recorders to opt in later by adding a
  package-private cross-package shim if needed, without changing
  the `SqlRecorder` interface.

Why the `SqlRecorder` interface stays clean:

- The two methods originally proposed (`markCrossTestContamination`,
  `hasCrossTestContamination`) had **no polymorphic call site
  outside `PersistenceSqlRecorder` itself** — `findRecord` reads on
  `this`, and the registry only writes. Putting them on the interface
  forced three no-op stubs on recorders that have no contamination
  semantics, for no behavioral benefit.
- Cross-test contamination is conceptually a **registry-level** concept — it's
  detected by registry state (≥ 2 distinct owner tids in the worker
  fallback's snapshot of `ACTIVE_RECORDERS` — see I4), so the registry
  is the natural owner. `PersistenceSqlRecorder` asks "was *I*
  cross-test contaminated?" rather than "do I track cross-test contamination?".
- `SqlRecorder` keeps its single behavioral responsibility
  (`addQueryExecution(...)`); no JDK 1.7 default-method workaround
  required.

Why `CROSS_TEST_CONTAMINATED` is restricted to `PersistenceSqlRecorder`
(and **not** extended to other recorder/listener classes that also
hold per-test mutable state):

- `DisplaySqlRecorder` and `DisplaySqlOfTestMethodBodyRecorder` produce
  `SqlExecutions.NONE` in `findRecord` — they have no count to inflate
  and no assertion to violate. Display side-effects on the wrong test
  are visually noisy but not silently wrong.
- `SqlStatementBatchRecorder` produces `SqlBatchSizes` (not
  `SqlExecutions`), which has no `format(...)` carrier to attach a
  warning prefix to. Cross-test contamination of batch size info is
  deferred to PR3 (where the warning model evolves).
- `ConnectionLeakListener` *does* hold per-test state
  (`connections` field — see §2.2 caveat box). Cross-test
  contamination of leak detection is a **real** failure mode, but it
  is out of scope for PR1's contamination flag because (a) the
  natural carrier (`BooleanMeasure`) has no warning-prefix slot,
  and (b) the proper fix is per-`Connection` recorder snapshot at
  pool-checkout, deferred to PR4/PR5. Documented in §2.2 and §5.

So `CROSS_TEST_CONTAMINATED` covers `PersistenceSqlRecorder` because
it is (i) the recorder class whose *count* assertions
(`@ExpectSelect`, `@ExpectMaxSelect`, `@ExpectInsert`, …) are most
silently corrupted by cross-test SQL routing, (ii) the only recorder
whose `findRecord` returns a `SqlExecutions` instance with a
`format(...)` carrier capable of surfacing the warning prefix
(§2.6), and (iii) the only recorder whose lifecycle currently has
the lifecycle hook (`findRecord`'s `finally` — I14) needed to evict
the flag exception-safely.

Recorders touched in this commit:

| Class | Change |
|---|---|
| `SqlRecorder` (interface) | none |
| `PersistenceSqlRecorder` | `startRecording` initializes `sqlRepository` and clears stale contamination before `register(this)`; `findRecord` consults `hasCrossTestContamination(this)`, propagates onto `SqlExecutions` (§2.4), emits `System.err`, and **evicts the flag in a `finally` block** (I14 — primary, exception-safe eviction); `cleanResources` is a defensive backstop only |
| `DisplaySqlRecorder` | none |
| `DisplaySqlOfTestMethodBodyRecorder` | none |
| `SqlStatementBatchRecorder` | none in this document — thread-safe `differentBatchSizes` set + `volatile previousStatementsAreBatched` are handled in `pr1-additional-thread-safety.md` (same PR). Cross-test contamination on `SqlBatchSizes` is deferred to PR3 (no `format(...)` hook on that record). |
| `ConnectionLeakListener` | none in this document — thread-safe `connections` set handled in `pr1-additional-thread-safety.md` (same PR). Cross-test attribution is a real PR1 residual (§2.2 caveat / §5); proper fix in PR4/PR5. |

**Misc design notes** (synthesis-review minor findings, gathered for
future maintainers):

- **`DisplaySqlRecorder` constructor-time JMM safety.**
  `DisplaySqlRecorder` is a stateless side-effect-only recorder
  (its `addQueryExecution` formats and prints; it has no per-test
  mutable state to corrupt under cross-thread access). It is
  registered through `SqlRecorderRegistry.INSTANCE.register(this)`
  the same way as `PersistenceSqlRecorder`, but the publish-last
  reorder (I13) is unnecessary for it: there is no analogue of
  `sqlRepository` to be observed mid-construction. Final
  initialisation of stateless fields (`PrintStream`, format flags)
  is `final` / set in the constructor before `this` escapes via
  `register`, which the JMM publishes safely under JLS §17.5
  (final-field semantics). No change needed.
- **I14 same-instance scope clarification.** The "exception-safe
  eviction" claim for `findRecord` (I14) holds for
  `PersistenceSqlRecorder` specifically because its `findRecord`
  returns an instance of `SqlExecutions` produced by
  `SqlMemoryRepository`. Other recorders'`findRecord` may return
  different `RecordablePerformance` types (`SqlBatchSizes`,
  `BooleanMeasure`) and have their own lifecycle stories — I14's
  `try { ... } finally { clearCrossTestContaminationFor(this); }`
  shape applies *only* to `PersistenceSqlRecorder`; the other
  recorders are not in `CROSS_TEST_CONTAMINATED` at all (per the
  scope discussion above), so no `clear` is needed for them.
- **`ACTIVE_RECORDERS.remove` recorder-instance freshness.** Every
  `PersistenceSqlRecorder` is constructed fresh per test method
  via `QuickPerfTestExtension` / `QuickPerfJUnitRunner` — there is
  no recorder pooling, no `ThreadLocal<PersistenceSqlRecorder>`
  reuse. So `ACTIVE_RECORDERS.remove(this)` in `unregister` is
  guaranteed to operate on the same identity-keyed instance that
  was added in `register(this)` (no `equals/hashCode` aliasing
  pitfalls). The `ConcurrentHashMap`-backed identity set does the
  right thing here even without the extra `==` discipline that the
  `PER_THREAD_RECORDERS` submap's `remove(class, instance)` uses.
- **Displacement-only eviction in `register`.** When
  `register(newRecorder)` finds an existing same-class entry on
  the same thread id (e.g. a previous test's
  `PersistenceSqlRecorder` left over because the test thread was
  reused without `unregister` running — see §5 "thread-id reuse"
  caveat), the new recorder *displaces* the old in the per-thread
  submap, but the old instance's
  `CROSS_TEST_CONTAMINATED.remove(displacedRecorder)` MUST also
  fire (I3) to prevent a stuck contamination flag. The plan's §2.1
  pseudocode handles this; the rule is that **eviction is keyed on
  displacement, not on `unregister`**: if `unregister` never runs
  for a stranded test, the next test's `register` cleans up via
  the displacement path. Any future refactor that splits
  displacement-eviction out of `register` must preserve this
  property or the `unregister`-leak case in §5 widens.

### 2.4 `sql/sql-annotations/.../PersistenceSqlRecorder.java`

- **No new fields on the production data model, no new interface
  methods.** The cross-test contamination flag lives in
  `SqlRecorderRegistry.CROSS_TEST_CONTAMINATED` (§2.1);
  `PersistenceSqlRecorder` is a consumer, not an owner. The only new
  field is the package-private warning-sink seam used by the
  `findRecord` warning emission and the unit tests in §3.5:
  ```java
  // Package-private test seam. Production runs default to System.err;
  // unit tests inject a per-instance ByteArrayOutputStream-backed
  // PrintStream and restore the previous value in @After / try-finally.
  // Volatile because tests on Surefire pool threads write it while the
  // production findRecord path on a worker thread may read it. See §3.5
  // for the rationale (System.setErr capture is unsafe under
  // parallel=all, threadCount=5 — sibling tests would corrupt the
  // captured buffer).
  static volatile PrintStream WARNING_SINK = System.err;
  ```
- **Initialize state, then publish in `startRecording`** — fully build
  the recorder's mutable state (`sqlRepository`) and clear stale
  contamination from any prior lifecycle of this same instance
  **before** calling `register(this)`. Publishing last is mandatory
  under the new active-set fallback (see I13 for the full rationale,
  including the NPE on a still-`null` `sqlRepository`, the silenced
  contamination warning, and the JMM publication argument that makes
  `volatile sqlRepository` unnecessary):
  ```java
  @Override
  public void startRecording(TestExecutionContext ctx) {
      sqlRepository = SqlRepositoryFactory.getSqlRepository(ctx);
      SqlRecorderRegistry.clearCrossTestContaminationFor(this);
      SqlRecorderRegistry.INSTANCE.register(this);   // publish LAST (I13)
  }
  ```
  The `clearCrossTestContaminationFor(this)` call is purely defensive
  here — at this point `this` is not yet in `ACTIVE_RECORDERS`, so no
  worker can have marked it during this lifecycle; the clear only
  evicts a stale flag from a previous use of the same recorder
  instance. Symmetric with the `unregister`-first ordering in
  `stopRecording` (I2).
- **Unregister first in `stopRecording`** — `unregister(this)` must run
  **before** `flush`, matching today's code and satisfying I2 at the
  recorder level ("active-set visibility ends as soon as `stopRecording`
  begins"). Flushing while the recorder is still in `ACTIVE_RECORDERS`
  would let a worker thread (Tomcat, `@Async`, scheduler, virtual
  thread) executing SQL on behalf of *another* test take the active-set
  fallback, find this still-live recorder, and call `addQueryExecution(...)`
  concurrently with `flush`'s iteration over `sqlRepository` — racing
  the deque / `executionCount` pair (F0) and inflating the contamination
  window per I12. Unregister-first closes both windows immediately.
  ```java
  @Override
  public void stopRecording(TestExecutionContext ctx) {
      SqlRecorderRegistry.unregister(this);   // unpublish FIRST (I2 / I10)
      sqlRepository.flush(ctx.getWorkingFolder());
      if (datasourceProxyVerifier.hasQuickPerfBuiltSeveralDataSourceProxies()) {
          System.out.println();   // newline-flush before the warning, matching pre-PR1 behavior
          System.err.println(DataSourceProxyVerifier.SEVERAL_PROXIES_WARNING);
      }
  }
  ```
  No `try/finally` is required: `unregister` is the first statement, so
  it runs unconditionally and a later throw from `flush` cannot leave
  the recorder in `ACTIVE_RECORDERS`; `ConcurrentHashMap.remove` on
  identity-keyed entries does not throw. Contamination survives
  unregister by design (I11 — `CROSS_TEST_CONTAMINATED` is never cleared
  by `unregister`), so `findRecord`, which the framework calls *after*
  `stopRecording`, can still observe the flag.
- **Propagate the flag onto `SqlExecutions` in `findRecord` and evict
  in a `finally` block** by reading the registry rather than a local
  field. The `finally` is mandatory: it makes the eviction
  exception-safe so that a throw from the framework's downstream
  evaluation pipeline (`PerfIssuesEvaluator`, measure extractors, the
  reporter) cannot leak the recorder in `CROSS_TEST_CONTAMINATED` for
  the JVM lifetime. **The `try {` opens at the very top of the method**
  (right after the null-init guard) so that even a throw from
  `sqlRepository.findExecutedQueries(...)` (forked-JVM file-IO failure,
  deserialization failure, repository-bug) is caught by the `finally`
  and the recorder is still evicted. The framework lifecycle is
  `startRecording → test → stopRecording → findRecord → cleanResources`,
  but downstream callers (`QuickPerfTestExtension.java:173-177`,
  `MainJvmAfterJUnitStatement.java:66-71`,
  `QuickPerfTestNGListener.java:65-69`, `QuickPerfSqlTestNGListener.java:77-83`)
  evaluate issues *before* calling `cleanResources` *without* a
  `try/finally`, so any exception in evaluation skips
  `cleanResources` and would otherwise retain the recorder + its
  `sqlRepository` (in-memory: the full `SqlExecutions` deque) in the
  static contamination set forever (see I14 for the full memory-leak
  walk-through). The warning emission goes through the
  `WARNING_SINK` seam (§3.5) rather than `System.err` directly, so unit
  tests can capture it without touching the JVM-global stream:
  ```java
  @Override
  public SqlExecutions findRecord(TestExecutionContext ctx) {
      if (sqlRepository == null) {
          sqlRepository = SqlRepositoryFactory.getSqlRepository(ctx);
      }
      try {
          SqlExecutions executions =
              sqlRepository.findExecutedQueries(ctx.getWorkingFolder());
          if (SqlRecorderRegistry.hasCrossTestContamination(this)) {
              if (executions == SqlExecutions.NONE) {
                  // SqlExecutions.NONE is a JVM-wide-shared singleton (§2.5);
                  // never mutate it. Swap for a fresh empty instance so the
                  // warning still surfaces in format(...).  See I15.
                  executions = new SqlExecutions();
              }
              executions.markCrossTestContamination();   // package-private setter on SqlExecutions (§2.5)
              // Dual visibility (I16): also emit the warning to the sink so
              // it surfaces on contaminated tests that happen to PASS (e.g.
              // inflated count still satisfies @ExpectMaxSelect, or no count-
              // style assertion at all). The format(...) prefix only runs for
              // PerfRecords whose perfIssues list is non-empty
              // (PerfIssuesEvaluator.java:139), so prefix-only would be silent
              // on passing-but-contaminated tests. WARNING_SINK defaults to
              // System.err; tests inject a per-instance PrintStream (§3.5).
              WARNING_SINK.println(SqlExecutions.CROSS_TEST_CONTAMINATION_WARNING);
          }
          return executions;
      } finally {
          // Primary, exception-safe eviction (I14). Runs even if a
          // downstream caller throws between `findRecord` and
          // `cleanResources`, AND even if `findExecutedQueries` itself
          // throws before any propagation onto `SqlExecutions`.
          // Idempotent: `clearCrossTestContaminationFor(this)` is a
          // `Set.remove` on identity-keyed entries, no-op when the
          // entry is absent (e.g. an uncontaminated lifecycle reaches
          // this finally and removes nothing). Evicting here is safe
          // because:
          //   (a) the flag has already been propagated onto the
          //       returned `SqlExecutions` instance — `format(...)` will
          //       still see it,
          //   (b) `SqlMemoryRepository.findExecutedQueries` returns the
          //       SAME `SqlExecutions` instance on every call (line 36),
          //       so subsequent `findRecord` calls (e.g. from a second
          //       `RecordablePerformance` bound to this recorder) get the
          //       already-marked instance — `format(...)` still works,
          //   (c) it dedupes the warning sink emission to one per
          //       contaminated lifecycle (was N for N annotations).
          //   (b) above applies to `SqlMemoryRepository` (single-JVM
          //       mode); `SqlFileRepository.findExecutedQueries`
          //       deserializes a fresh instance each call, but in
          //       forked-JVM mode the contamination flag is structurally
          //       false (see I7 / I14), so the same-instance dedup
          //       reasoning is moot — only single-JVM mode reaches the
          //       contaminated branch.
          SqlRecorderRegistry.clearCrossTestContaminationFor(this);
      }
  }
  ```
  `findRecord` is where the `SqlExecutions` instance is materialised
  (read back from `SqlRepository.findExecutedQueries`), so this is the
  natural propagation point. In single-JVM mode the repository
  (`SqlMemoryRepository`) hands back a fresh per-recorder `SqlExecutions`,
  never `NONE`. In forked-JVM mode cross-test contamination is impossible
  (only one test per JVM), so `hasCrossTestContamination(this)` is always
  `false` and the `NONE`-returning paths in `SqlFileRepository`
  (file missing / object-store empty, lines 63 / 67) are never reached
  while the contamination flag is `true`. The `executions == NONE` guard is
  therefore a **defence-in-depth check, not a happy-path branch**: it
  ensures that any future caller wiring (e.g. a new code path that calls
  `markCrossTestContamination` from outside `findRecord`, a regression
  in `hasCrossTestContamination`'s gating, or a sibling-PR that lets
  in-memory mode return `NONE`) cannot poison the singleton. Together
  with the no-op safety net inside `markCrossTestContamination()`
  itself (§2.5), this enforces I15.
- **`cleanResources` is a defensive backstop only** — it evicts the
  contamination entry as a no-op-on-the-happy-path safety net, in case
  `findRecord` was never called (e.g. a lifecycle path that bypasses
  evaluation entirely). Primary eviction is the `findRecord` `finally`
  above (I14):
  ```java
  @Override
  public void cleanResources() {
      // Backstop: findRecord's finally is the primary eviction point
      // (I14). This call is a no-op on the production lifecycle path
      // (findRecord has already cleared `this` from CROSS_TEST_CONTAMINATED
      // before cleanResources runs), and evicts only when findRecord
      // was skipped — e.g. a custom lifecycle where the framework
      // calls cleanResources without prior evaluation.
      SqlRecorderRegistry.clearCrossTestContaminationFor(this);
  }
  ```
  The same `clearCrossTestContaminationFor(SqlRecorder)` helper from
  §2.1 is used in three places now, each playing a distinct role:
  in `startRecording` (defensive reset against stale state from a
  prior lifecycle of the same recorder instance — I13), in
  `findRecord`'s `finally` (primary eviction — I14), and in
  `cleanResources` (defensive backstop). All are idempotent.

### 2.5 `sql/sql-annotations/.../SqlExecutions.java`

Three changes:

1. **Thread-safe buffer (F0)**: replace the existing
   `private final Deque<SqlExecution> sqlExecutions = new ArrayDeque<>();`
   with `new ConcurrentLinkedDeque<>();`. Keep the declared type as
   `Deque<SqlExecution>` so callers that iterate / drain are unaffected.
   - There is **no cap / no cyclic-buffer trim today**: `add(...)` simply
     calls `addLast(...)` and never trims. Nothing to migrate. (This
     plan deliberately does **not** introduce a cap — that would be a
     behaviour change and belongs in a separate proposal.)
   - All iteration sites in `SqlExecutions` already use enhanced-for /
     `Iterable<SqlExecution>`, which is safe with `ConcurrentLinkedDeque`'s
     weakly-consistent iterator. No index-based access exists.
   - **Guard `add(...)` and `addWithoutCall(...)` against the JVM-wide
     `SqlExecutions.NONE` singleton.** Both methods today
     (`SqlExecutions.java:34-44`) blindly delegate to
     `sqlExecutions.addLast(...)` with no sentinel check. The real
     signatures are `public void add(ExecutionInfo execInfo, List<QueryInfo> queries)`
     and `private void addWithoutCall(ExecutionInfo execInfo, List<QueryInfo> queries)`
     — note that `addWithoutCall` is **private** and is invoked only
     by `filterByQueryType` (line 61) on a freshly-constructed
     `SqlExecutions` instance (never `NONE`), so its guard is purely
     defence-in-depth. `add` is the production write path called by
     `PersistenceSqlRecorder.addQueryExecution(...)` on every JDBC
     event. That is safe under the current ITL design because no live
     recorder ever sees `NONE` from `SqlMemoryRepository`. Under the
     dual-map design **a worker thread can reach this path during the
     contamination window** (sibling test's recorder still in
     `ACTIVE_RECORDERS`), so any future repository wiring that hands
     a worker a `NONE` reference would mutate the singleton. Add an
     early-return:
     ```java
     public void add(ExecutionInfo execInfo, List<QueryInfo> queries) {
         if (this == NONE) {
             return;   // I15: never mutate the JVM-wide singleton
         }
         SqlExecution sqlExecution = new SqlExecution(execInfo, queries);
         sqlExecutions.addLast(sqlExecution);
         executionCount.incrementAndGet();
     }

     // Workaround: avoid duplicate call to retrieveNumberOfReturnedColumns
     // within SqlExecution constructor in add method by forcing the column
     // count. Related commit https://github.com/quick-perf/quickperf/issues/141
     private void addWithoutCall(ExecutionInfo execInfo, List<QueryInfo> queries) {
         if (this == NONE) {
             return;   // defence-in-depth; only filterByQueryType calls this
         }
         SqlExecution sqlExecution = new SqlExecution(execInfo, queries, 0);
         sqlExecutions.addLast(sqlExecution);
         executionCount.incrementAndGet();
     }
     ```
     Like the `markCrossTestContamination` no-op (§2.5 item 3), this is
     defence-in-depth — `NONE` should never reach a live recorder
     under the dual-map design, but a silent drop is preferable to
     poisoning the singleton if a regression ever does. See I15 / I17.
2. **O(1) size**: `ConcurrentLinkedDeque.size()` is O(n). Add
   `private final AtomicInteger executionCount = new AtomicInteger();`,
   increment in `add(...)` / `addWithoutCall(...)`, and have
   `getNumberOfExecutions()` return `executionCount.get()`. Callers on
   the hot reporting path:
   - `SqlReport.java:96, 100, 105, 188`
   - `SqlAnalysisExtractor.java:30` — backs the `Count` measure for
     `@ExpectSelect`/`@ExpectInsert`/etc. **Hot path.**
   - `SqlExecutions.noJdbcExecution()` — change to
     `return executionCount.get() == 0;`
   No public API change.

   **Pinned write order — `addLast` first, then `incrementAndGet`.**
   This pairing has a tearing window worth documenting because tests
   on the hot path (`@ExpectSelect`) read both:

   - **Order**: append the element to the deque *first*, increment
     the counter *second*. A reader that observes `executionCount == N`
     is guaranteed to see at least N elements when iterating the
     deque (happens-before from `incrementAndGet`'s release to a
     subsequent `get`'s acquire propagates the deque writes).
   - **Tearing window**: between `addLast(...)` returning and
     `incrementAndGet()` being applied, the deque holds N+1 elements
     while the counter still reads N. A concurrent reader iterating
     the deque may see N+1 elements but compute size as N (or vice
     versa under reversed order). **The chosen ordering keeps the
     counter as a lower bound**, never an upper bound — `format(...)`
     and count-style assertions (`@ExpectSelect`, `@ExpectMaxSelect`)
     read `executionCount`, so the lower bound is the safe direction
     (no spurious failures from reading "too many"). The reverse
     order would let `executionCount` transiently exceed deque size,
     turning a benign tear into a spurious assertion failure.
   - **Test thread reads after barrier**: the assertion-evaluating
     test thread reads `executionCount` only *after*
     `stopRecording → findRecord` runs (which involves a
     volatile-read happens-before chain through registry lookups
     and `SqlExecutions` field reads), so all worker `addLast` /
     `incrementAndGet` ordered-pairs are fully visible by then.
     The tearing window matters only for *concurrent* observers
     (e.g. a hypothetical future feature that reads counts
     mid-test) — none exist today, but pinning the order makes
     the invariant explicit for future maintainers. See I17.
3. **Cross-test contamination flag**: add a `private boolean crossTestContamination`
   (plain field — set once on the test thread in
   `PersistenceSqlRecorder.findRecord` before any reporter reads it;
   no concurrent writers on this field). Add a package-private setter
   `markCrossTestContamination()` and a getter
   `hasCrossTestContamination()` (used by `format(...)` in §2.6).
   These are accessors on `SqlExecutions` itself — they are **not**
   added to the `SqlRecorder` interface (see §2.3).

   The setter must **early-return when invoked on the JVM-wide singleton
   `SqlExecutions.NONE`** (line 30, `public static final SqlExecutions NONE
   = new SqlExecutions()`):

   ```java
   void markCrossTestContamination() {
       // SqlExecutions.NONE is a JVM-wide-shared sentinel returned by
       // SqlFileRepository when the persisted SQL file is missing/empty
       // (lines 63, 67) and by DisplaySqlRecorder / DisplaySqlOfTestMethodBodyRecorder.
       // Mutating it would set a sticky warning flag for every future test
       // that reads the singleton.  See I15.
       if (this == NONE) {
           return;
       }
       this.crossTestContamination = true;
   }
   ```

   This is the **inner** half of a defence-in-depth pair: the caller
   in `PersistenceSqlRecorder.findRecord` (§2.4) already swaps `NONE`
   for a fresh `SqlExecutions` before marking, so warnings on the happy
   path still propagate to `format(...)`. The setter no-op is the
   safety net — silent rather than throwing, so a stray mutation from a
   future code path (refactor, new feature, faulty
   `hasCrossTestContamination` gating) is dropped instead of poisoning
   the singleton JVM-wide.

`SqlExecutions` is `Serializable` and `ConcurrentLinkedDeque` is also
`Serializable` (snapshot semantics) — known-safe across forked-JVM
serialization boundaries. `AtomicInteger` is `Serializable` too.
Declare `private static final long serialVersionUID = 1L` explicitly
on `SqlExecutions` to bound the IDE / branch-hop dev loop where a
working folder serialised pre-PR1 might be deserialised post-PR1
(adding the `executionCount` and `crossTestContamination` fields
changes the auto-computed UID). On CI this is a non-issue (Surefire
wipes `target/` between builds), but a developer switching between
this PR's branch and `master` while reusing a working folder would
otherwise see a spurious `InvalidClassException` (N5 in the synthesis
review).

**`filterByQueryType` mutator path.** `SqlExecutions.filterByQueryType`
(lines 46-65) iterates `this.sqlExecutions` and calls `addWithoutCall(...)`
on a freshly-constructed `SqlExecutions` instance. This is a third
mutator path beyond `add` and `addWithoutCall` direct calls (those
two are covered by I17). Iteration of `this.sqlExecutions` is
weakly-consistent under concurrent writers (`ConcurrentLinkedDeque`'s
documented semantics: every element existing at construction time is
observed at most once; elements added during iteration may or may
not be observed). The output's `executionCount` is built lazily as
`addWithoutCall` invocations append to the fresh instance and follow
the I17 ordering normally. `filterByQueryType` is invoked from the
test thread *after* `stopRecording → findRecord` (e.g. by
`SqlAnalysisExtractor` materialising query-type-specific counts), at
which point all worker `addLast → incrementAndGet` pairs are
observed-coherently via the registry's `unregister`-then-`flush`
happens-before chain (I10), so the weak iteration guarantee is
sufficient. No code change needed for `filterByQueryType` itself
(S3 in the synthesis review — call out the path explicitly so a
future maintainer doesn't add a fourth without revisiting I17).

### 2.6 Cross-test contamination warning surfaced in `SqlExecutions.format(...)`

The existing `SqlExecutions.format(Collection<PerfIssue>)` already builds
the full SQL report via `ViewablePerfRecordIfPerfIssue`. The current
implementation has an early return for
`SystemProperties.SIMPLIFIED_SQL_DISPLAY` that returns just the standard
`PerfIssuesFormat.STANDARD.format(...)` without the JDBC execution dump
(intended for CI logs where the dump is too noisy). The contamination
warning **must be prepended in both branches**, because users running
with simplified display still need to know their count is contaminated;
otherwise the warning silently disappears under
`-Dquickperf.simplifiedSqlDisplay=true`. The cleanest minimal change
extracts the existing body into a private helper and prepends the
warning around it (no `instanceof` leakage in `core`):

```java
@Override
public String format(Collection<PerfIssue> perfIssues) {
    String body = formatBody(perfIssues);
    if (crossTestContamination) {
        return CROSS_TEST_CONTAMINATION_WARNING
                + System.lineSeparator()
                + System.lineSeparator()
                + body;
    }
    return body;
}

private String formatBody(Collection<PerfIssue> perfIssues) {
    String standardFormatting = PerfIssuesFormat.STANDARD.format(perfIssues);
    if (SystemProperties.SIMPLIFIED_SQL_DISPLAY.evaluate()) {
        return standardFormatting;
    }
    return    standardFormatting
            + System.lineSeparator()
            + System.lineSeparator()
            + "[JDBC QUERY EXECUTION (executeQuery, executeBatch, ...)]"
            + System.lineSeparator()
            + (noJdbcExecution() ? new DataSourceConfig().getMessage()
                                 : toString());
}

private boolean noJdbcExecution() {
    return executionCount.get() == 0;   // §2.5
}

// Package-private constant — referenced by PersistenceSqlRecorder.findRecord
// for the System.err emission (§2.4 / I16) and by format(...) above. Both
// channels share this single source of truth.
static final String CROSS_TEST_CONTAMINATION_WARNING =
    "WARNING: SQL was recorded from a worker thread (e.g. @Async, executor,"
    + System.lineSeparator()
    + "@Scheduled, message listener) while another test was running in"
    + System.lineSeparator()
    + "parallel. The SQL count above may include statements from that other"
    + System.lineSeparator()
    + "test."
    + System.lineSeparator()
    + System.lineSeparator()
    + "To get an accurate count, isolate this test so no sibling test runs"
    + System.lineSeparator()
    + "concurrently in the same JVM. The most robust option (works on any"
    + System.lineSeparator()
    + "test framework): force a dedicated JVM with @HeapSize or @Xmx, e.g."
    + System.lineSeparator()
    + "  @HeapSize(value = 20, unit = AllocationUnit.MEGA_BYTE)"
    + System.lineSeparator()
    + "  @Xmx(value = 50, unit = AllocationUnit.MEGA_BYTE)"
    + System.lineSeparator()
    + "Alternatively, disable parallel execution at the build-tool level"
    + System.lineSeparator()
    + "(Maven Surefire <parallel>none</parallel>; or set"
    + System.lineSeparator()
    + "junit.jupiter.execution.parallel.enabled=false on JUnit Jupiter), or"
    + System.lineSeparator()
    + "annotate every test sharing the DataSource with JUnit Jupiter's"
    + System.lineSeparator()
    + "@ResourceLock(value = \"quickperf.sql\")."
    + System.lineSeparator()
    + System.lineSeparator()
    + "Note: @Execution(ExecutionMode.SAME_THREAD) alone is NOT sufficient."
    + System.lineSeparator()
    + "It only constrains parallelism within the annotated class, and does"
    + System.lineSeparator()
    + "not affect @Async / @Scheduled / Tomcat / executor thread routing.";
```

(Reference to `QuickPerfContext.wrap(...)` is intentionally **not** added
in PR1 — that API ships in PR2.)

The contamination warning is surfaced through **two independent channels**
(I16) so it is visible regardless of whether the test passes or fails:

1. **`format(...)` prefix** (above) — composed into the assertion failure
   message via `ThrowableBuilder` → `PerfIssuesEvaluator` →
   `SqlExecutions.format(perfIssues)`. Inline with the failure, but only
   runs when the recorder has at least one `PerfIssue` attached
   (`PerfIssuesEvaluator.java:139` filters out records with empty
   `perfIssues` lists). Silent on contaminated tests that pass —
   which is exactly the gap channel 2 closes.
2. **`System.err` at `findRecord` time** (§2.4) — emitted unconditionally
   when `hasCrossTestContamination(this)` is `true`, before `format(...)`
   ever runs. Surefire / JUnit Platform / IntelliJ / IDEA / Eclipse all
   capture stderr per test method and surface it in the per-test report
   block, so the warning reaches the user even on passing tests
   (e.g. inflated count satisfies `@ExpectMaxSelect(N)`, or the test
   only carries structural assertions like
   `@DisableLikeWithLeadingWildcard` / `@DisableSameSelects` that don't
   inspect the count). On the failure path the user sees the warning
   twice — stderr line + inline prefix — which is intentional: the
   inline prefix makes the assertion message self-explanatory; the
   stderr line guarantees universal visibility.

Modelling contamination as its own `PerfIssue` (which would force `format`
to run by injecting a synthetic issue into the perfIssues list) was
considered and rejected for PR1: every existing `PerfIssue` fails the
test (`QuickPerfReporter` throws on non-empty `perfIssues`), so a
synthetic issue would convert silent passes into hard failures —
contradicting the plan's PR3 deferral of "tighten the warning into a
forced failure" and changing the `PerfIssue` model semantically (severity
levels, a new evaluator path). Dual-channel `System.err` + `format`
prefix achieves the same visibility outcome without the model change.

`core/.../reporter/ThrowableBuilder.java` is **not touched**: it already
calls `perfIssuesToFormat.format()`, and `SqlExecutions.format(...)` is
the per-record formatter that gets composed into the final assertion
message. Keeping the SQL-specific warning out of `core` avoids cross-
module coupling and matches the existing layering.

---

## 3. Tests to add

Most of the user-visible threading scenarios (Tomcat / pre-existing
executor pools, `@Async`, `CompletableFuture`, `@Scheduled`, concurrent
random-port HTTP, cross-test contamination warning text) are already
covered by commit `d42e057` ("Add threading scenario tests…"):
`JUnit5ThreadingScenariosTest`, `SpringBootThreadingScenariosJunit5Test`,
`CrossTestContaminationWithConcurrentAsync`,
`CrossTestContaminationWithConcurrentRandomPort`, plus the supporting
`@Async` / `CompletableFuture` / `@Scheduled` controllers and services.
PR1 therefore only needs to add the tests that those integration-level
scenarios cannot reliably cover:

1. The **F0 unit tests** that drive the `ArrayDeque → ConcurrentLinkedDeque`
   migration (must demonstrably fail on `master` before the swap).
2. A **minimal worker-fallback smoke test** on `SqlRecorderRegistry`
   itself, to lock in the new dual-map contract at unit level (cheap
   regression guard against accidental refactors back to ITL).

The forked-JVM branch (`sqlRecorderByTypeOfTestJvm`, `ConnectionListener`
collection) is **not** stress-tested with synthetic
N-threads-× -M-iterations harnesses. Coverage of the SQL-annotation ×
forked-JVM path is provided by the existing `sql/sql-memory-test`
integration tests listed in §3.3 — they exercise real SQL annotations
(`@ExpectNoConnectionLeak`, `@ExpectMaxSelect`, `@ExpectJdbcBatching`,
`@ExpectMaxSelectedColumn`, `@DisableStatements`) combined with
`@HeapSize` / `@Xmx`, which is precisely the production pattern the
thread-safe forked-JVM collections must keep working.

Everything below lives under `sql/sql-annotations/src/test/java/...`
unless noted. Mandatory cleanup discipline: every test that calls
`register` must call `unregister` (or `clear`) in a `finally`. Maven
Surefire runs this module with `parallel=all, threadCount=5`, so
leaked entries contaminate sibling tests.

### 3.0 Test scaffolding boilerplate

The `sql-annotations` module is JUnit 4 + AssertJ (see existing
`SqlRecorderRegistryTest` and the module `pom.xml` —
`maven.compiler.source = 1.7`, so **no lambdas, no try-with-resources
on `ExecutorService`, no `var`**). Every concurrency-style test in
§3.1 / §3.2 follows this fixed harness:

```java
package org.quickperf.sql;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

public class SqlExecutionsConcurrencyTest {

    // Tunables — chosen low enough to keep the test sub-second under
    // Surefire `parallel=all, threadCount=5`, high enough to reliably
    // surface ArrayDeque corruption on master.
    private static final int WRITER_THREADS  = 8;
    private static final int OPS_PER_THREAD  = 10_000;
    private static final int AWAIT_SECONDS   = 30;

    private ExecutorService pool;

    @After
    public void tearDown() {
        if (pool != null) {
            pool.shutdownNow();
        }
        // Keep the registry clean so this test cannot leak into
        // sibling tests under Surefire `parallel=all`.
        SqlRecorderRegistry.INSTANCE.clear();
    }

    private void runConcurrentlyAndAwait(Runnable task) throws Exception {
        pool = Executors.newFixedThreadPool(WRITER_THREADS);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done  = new CountDownLatch(WRITER_THREADS);
        final AtomicReference<Throwable> firstFailure = new AtomicReference<Throwable>();
        for (int t = 0; t < WRITER_THREADS; t++) {
            pool.submit(new WorkerWrapper(task, start, done, firstFailure));
        }
        start.countDown();                              // release all workers at once
        boolean finished = done.await(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(finished).as("workers timed out").isTrue();
        Throwable failure = firstFailure.get();
        if (failure != null) {
            throw new AssertionError("worker thread threw", failure);
        }
    }

    // Static nested class because JDK 1.7 has no lambdas.
    private static final class WorkerWrapper implements Runnable {
        private final Runnable delegate;
        private final CountDownLatch start;
        private final CountDownLatch done;
        private final AtomicReference<Throwable> firstFailure;

        WorkerWrapper(Runnable delegate,
                      CountDownLatch start,
                      CountDownLatch done,
                      AtomicReference<Throwable> firstFailure) {
            this.delegate = delegate;
            this.start = start;
            this.done = done;
            this.firstFailure = firstFailure;
        }

        @Override public void run() {
            try {
                start.await();
                delegate.run();
            } catch (Throwable t) {
                firstFailure.compareAndSet(null, t);
            } finally {
                done.countDown();
            }
        }
    }
}
```

Reuse this skeleton verbatim for every concurrency test in §3.1 and
§3.2; the per-test `Runnable` is the only thing that varies.
`@After` + `firstFailure.compareAndSet` are non-negotiable: without
them, an `ArrayIndexOutOfBoundsException` thrown from a worker
thread is swallowed by `ExecutorService` and the test goes silently
green.

### 3.1 `SqlExecutions` thread-safety unit tests (F0)

New file:
`sql/sql-annotations/src/test/java/org/quickperf/sql/SqlExecutionsConcurrencyTest.java`

- `sqlExecutions_addLast_under_concurrent_writers_does_not_throw_or_lose_entries`
  — `WRITER_THREADS × OPS_PER_THREAD` calls to
  `sqlExecutions.add(execInfo, queries)` (the real signature —
  `SqlExecutions.add(ExecutionInfo, List<QueryInfo>)`; constructs a
  `SqlExecution` internally). Stub `ExecutionInfo` and a
  `Collections.<QueryInfo>emptyList()` are sufficient for stress —
  the harness exercises the deque + counter, not query parsing. After
  the harness joins all workers, assert (a) no worker rethrew via
  `firstFailure`, (b) iterating the deque yields exactly
  `WRITER_THREADS * OPS_PER_THREAD` items.

  *Expected failure on `master`:* the harness fails via `firstFailure`
  with `java.lang.ArrayIndexOutOfBoundsException` thrown from
  `java.util.ArrayDeque.addLast` (or `iterator().next()` returns
  `null` / a count mismatch). After the swap, asserts pass.

  *Verify:*
  ```
  mvn -pl sql/sql-annotations -am test \
      -Dtest=SqlExecutionsConcurrencyTest#sqlExecutions_addLast_under_concurrent_writers_does_not_throw_or_lose_entries
  ```

- `sqlExecutions_getNumberOfExecutions_matches_actual_count_under_concurrent_writes`
  — `WRITER_THREADS × OPS_PER_THREAD` writes; after the latch,
  assert `sqlExecutions.getNumberOfExecutions()` equals
  `WRITER_THREADS * OPS_PER_THREAD` and equals the iterator-based
  count. Guards invariant I8.

  *Expected failure on `master`:* `getNumberOfExecutions()` returns
  a value below the expected total (lost increments under
  `ArrayDeque`'s racy `size++`). After the `AtomicInteger` migration,
  the two counts agree.

  *Verify:*
  ```
  mvn -pl sql/sql-annotations -am test \
      -Dtest=SqlExecutionsConcurrencyTest#sqlExecutions_getNumberOfExecutions_matches_actual_count_under_concurrent_writes
  ```

- `sqlExecutions_getNumberOfExecutions_progresses_safely_during_concurrent_writes`
  — forward-looking regression guard: `WRITER_THREADS - 1` writers
  hammer the buffer; one reader thread loops calling
  `sqlExecutions.getNumberOfExecutions()` in a tight loop until all
  writers complete, asserting it never throws and that successive reads
  are monotonically non-decreasing. The two tests above only validate
  the **final** count after the latch; this test guards against a
  future change that replaces the `AtomicInteger` with a non-atomic
  counter or a non-monotonic source.

  *Verify:*
  ```
  mvn -pl sql/sql-annotations -am test \
      -Dtest=SqlExecutionsConcurrencyTest#sqlExecutions_getNumberOfExecutions_progresses_safely_during_concurrent_writes
  ```

Both must fail on `master` and turn green after the
`ConcurrentLinkedDeque` + `AtomicInteger` swap. This is the F0 commit's
TDD gate. Sub-second; precisely localizes the failure to the buffer.
The end-to-end concurrent-HTTP coverage is already provided by
`CrossTestContaminationWithConcurrentRandomPort`.

### 3.2 Minimal worker-fallback smoke test

One additional method on `SqlRecorderRegistryTest`:

- `register_then_get_returns_recorder_on_unrelated_worker_thread` —
  register on the test thread, spawn an unrelated worker (not a child
  of the registering thread), assert the worker sees the recorder via
  the active-set fallback. Locks in the worker-thread contract that
  the dual-map registry exists for.

Also update the existing `SqlRecorderRegistryTest`:

- Add `@After` calling `SqlRecorderRegistry.INSTANCE.clear()` so the
  pre-existing methods (`should_get_a_sql_recorder_from_its_type`,
  `should_clear_sql_recorder_registry`) don't leak into sibling
  Surefire-parallel tests under the new dual-map semantics.

Other registry-level tests (re-register dedup, contamination-flag
assertions, `clear` cross-thread isolation, `getSqlRecorderOfType`
fast-path/fallback matrix, scenario-7 clear-then-reregister) are
deliberately **not** added: their behavior is already exercised end-
to-end by the d42e057 integration tests
(`JUnit5ThreadingScenariosTest`, `SpringBootThreadingScenariosJunit5Test`,
`CrossTestContaminationWithConcurrent…`, `QuickPerfParallelExecutionTest`),
and adding white-box mirrors would couple the test suite to internals
without buying additional bug-detection power.

### 3.3 Regression guards (must keep passing)

- `JUnit5ThreadingScenariosTest`,
  `SpringBootThreadingScenariosJunit5Test`,
  `DetectionOfNPlusOneSelectWithAsync`,
  `DetectionOfNPlusOneSelectWithCompletableFuture`,
  `DetectionOfSelectWithScheduled` — added in d42e057; these are the
  scenario regression guards. The
  `CrossTestContaminationWithConcurrentAsync` /
  `CrossTestContaminationWithConcurrentRandomPort` classes from the
  same commit are **reproducers** orchestrated from
  `SpringBootThreadingScenariosJunit5Test#concurrent_tests_with_async`
  / `concurrent_tests_with_random_port`. The wrappers (not the inner
  classes) are the actual end-to-end regression guards; they currently
  assert `summary.getTestsFailedCount() == 0` and **must be updated in
  commit 4** to assert exactly one failure carrying the warning text
  (see §7 commit 4 — without that update they go red on the PR
  branch).
- `QuickPerfParallelExecutionTest` — 10 concurrent `@QuickPerfTest`
  methods, each with their own EMF; per-thread fast-path keeps them
  isolated.
- **SQL annotation × forked-JVM coverage** (under
  `sql/sql-memory-test/src/test/java/`) — these already exercise the
  `TEST_CODE_EXECUTING_IN_NEW_JVM` branch with a real SQL annotation
  (assertions / formatting / connection-listener wiring), which is the
  primary thing we must not regress when swapping the forked-JVM
  collections to thread-safe types (`ConcurrentHashMap`,
  `CopyOnWriteArrayList`):
  - `ConnectionLeakTest.ConnectionLeakInNewJvm` /
    `ConnectionLeakTest.NoConnectionLeakInNewJvm` —
    `@ExpectNoConnectionLeak` + `@HeapSize`. Drives
    `ConnectionListenerRegistry` through the forked-JVM branch.
  - `ExpectMaxSelectTest.OneSelectButNoExpectedInSpecificJvm` —
    `@Xmx` + `@ExpectMaxSelect`.
  - `ExpectJdbcBatchingWithBatchSizeTest` and
    `ExpectJdbcBatchingWithoutBatchSizeTest` — `@ExpectJdbcBatching` +
    `@HeapSize`.
  - `ExpectSelectedColumnTest` — `@HeapSize` +
    `@ExpectMaxSelectedColumn`.
  - `DisableStatementsTest` — `@DisableStatements` + `@HeapSize`.
- The pre-existing `SqlRecorderRegistryTest`
  (`should_get_a_sql_recorder_from_its_type` and
  `should_clear_sql_recorder_registry`) keeps passing under the new
  semantics: the same-thread `register + retrieve` path hits the
  per-thread fast-path, and the same-thread `register; clear; read`
  path is protected by the empty-marker submap installed by `clear()`
  (§2.1) — the post-clear read still observes `perThread != null` and
  takes the test-thread branch (returning an empty list / `null`)
  rather than falling through to the worker-thread active-set fallback.
  Without that sentinel, under Surefire `parallel=all, threadCount=5`,
  a sibling test's recorder would show up in the post-clear read and
  break `should_clear_sql_recorder_registry`'s `hasSize(0)` /
  `isNull()` assertions (CR3 in the synthesis review).

### 3.4 `SqlExecutions.NONE` immutability unit test (I15)

New file:
`sql/sql-annotations/src/test/java/org/quickperf/sql/SqlExecutionsNoneImmutabilityTest.java`

- `markCrossTestContamination_on_NONE_singleton_is_silently_dropped`
  — call `SqlExecutions.NONE.markCrossTestContamination()` directly
  and assert `SqlExecutions.NONE.hasCrossTestContamination()` returns
  `false` (the setter must early-return on `NONE`). Belt-and-suspenders
  guard against any future caller that does not pre-swap `NONE`
  (the `findRecord` swap in §2.4 is the primary defence; this is the
  inner safety net).

  *Expected failure on `master`:* test does not exist on `master`
  (no contamination flag on `SqlExecutions` at all). After §2.5
  lands, the field exists but the setter must include the
  `if (this == NONE) return;` guard for this test to pass; without the
  guard, `NONE.crossTestContamination` becomes `true` and the assertion
  fails — and any subsequent test in the same JVM that reads
  `SqlFileRepository`-returned `NONE` (or
  `DisplaySqlRecorder.findRecord`) would see the warning. The test
  must run **before** any other test in `SqlExecutionsNoneImmutabilityTest`
  could pollute `NONE` — the test class touches no other recorder code,
  so this is automatic.

- `findRecord_swaps_NONE_for_fresh_SqlExecutions_when_marking_contamination`
  — exercises the §2.4 swap in isolation: stub a `PersistenceSqlRecorder`
  whose `sqlRepository.findExecutedQueries(...)` returns
  `SqlExecutions.NONE`, populate `CROSS_TEST_CONTAMINATED` with the
  recorder via `SqlRecorderRegistry.markCrossTestContamination(...)`
  (package-private), call `findRecord`, and assert the returned
  `SqlExecutions` is **not** `NONE` (i.e. `result != SqlExecutions.NONE`),
  is empty (`result.getNumberOfExecutions() == 0`), and reports
  `result.hasCrossTestContamination() == true`. Also assert
  `SqlExecutions.NONE.hasCrossTestContamination() == false` afterwards
  (the singleton was not touched).

  This is a white-box assertion — coupled to the `findRecord`
  implementation — but the swap is the keystone of I15's defence-in-
  depth, and the cost (one stubbed recorder, no Spring/JUnit harness)
  is small enough to justify the coupling. The end-to-end behaviour
  is also covered indirectly by `CrossTestContaminationWithConcurrent…`
  (§3.3 regression guards), but those tests cannot distinguish "`NONE`
  was swapped" from "`SqlMemoryRepository` returned a fresh instance
  on its own" — only this test pins the swap behaviour.

  *Verify:*
  ```
  mvn -pl sql/sql-annotations -am test \
      -Dtest=SqlExecutionsNoneImmutabilityTest
  ```

Sub-second; precisely localizes the I15 contract to the two changes
(setter no-op in §2.5, caller swap in §2.4). The test class is
intentionally **not** added to `SqlExecutionsConcurrencyTest`: that
file is dedicated to F0 (concurrent-buffer thread-safety) and the
NONE-immutability concern is orthogonal — bundling them would couple
unrelated regression signals.

### 3.5 Warning-sink channel unit test (I16)

**Why a sink seam, not `System.setErr` capture.** `System.err` is a
JVM-global stream. The `sql-annotations` module Surefire config runs
`parallel=all, threadCount=5` (`pom.xml:104-113`); other tests in the
same JVM that exercise contamination paths (`SqlRecorderRegistryTest`'s
new worker-fallback smoke test, the `CrossTestContaminationWith…`
inner classes when the orchestrator wrappers run in the same JVM, any
test that loads `DataSourceProxyVerifier`'s `SEVERAL_PROXIES_WARNING`)
would write into the captured buffer while it is the active stderr,
corrupting the assertion non-deterministically. A global lock around
the test is **not** sufficient — unrelated tests can still emit to
`System.err`. Run-in-own-fork is heavyweight and still doesn't protect
against same-JVM contamination of the global stream.

The clean fix is a **package-private warning sink seam** on
`PersistenceSqlRecorder`:

```java
// sql/sql-annotations/.../PersistenceSqlRecorder.java
static volatile PrintStream WARNING_SINK = System.err;
```

Production callers do `WARNING_SINK.println(...)` instead of
`System.err.println(...)` (§2.4). Tests inject a per-instance
`ByteArrayOutputStream`-backed `PrintStream`, save the previous sink,
and restore it in `@After` / `try-finally`. Concurrent tests that hit
the production path keep writing to the previous (real `System.err`)
sink — no buffer corruption.

New file:
`sql/sql-annotations/src/test/java/org/quickperf/sql/PersistenceSqlRecorderStderrWarningTest.java`

Each test saves and restores `PersistenceSqlRecorder.WARNING_SINK`
inside `@Before` / `@After` (or `try/finally`); the tests do **not**
touch `System.err`.

- `findRecord_emits_warning_to_sink_when_contamination_observed`
  — set up a stubbed `PersistenceSqlRecorder` with a fake
  `SqlRepository` returning a fresh non-`NONE` `SqlExecutions`, mark the
  recorder in `CROSS_TEST_CONTAMINATED` via the package-private helper,
  call `findRecord(ctx)`, and assert the captured sink text contains
  the key phrases of `SqlExecutions.CROSS_TEST_CONTAMINATION_WARNING`:
  `"WARNING: SQL was recorded from a worker thread"` (header) and
  `"@HeapSize"` + `"force a dedicated JVM"` (recommended remediation —
  asserting on the **positive** advice rather than the
  `@Execution(ExecutionMode.SAME_THREAD)` negative-disclaimer line, so
  the test breaks if a future edit drops the recommendation rather
  than passing on the disclaimer alone). Locks in the channel-2
  visibility contract: the warning text must reach the sink at
  `findRecord` time, before any `format(...)` invocation, regardless of
  whether the test passes or fails downstream.

- `findRecord_does_not_emit_to_sink_when_no_contamination`
  — same scaffolding, but the recorder is not marked contaminated.
  Assert the captured sink is empty. Guards against an unconditional
  emission that would spam logs for every test (would defeat the point
  of the channel — a warning users learn to ignore is a warning that
  isn't there).

- `findRecord_dedupes_sink_emission_to_one_per_contaminated_lifecycle`
  — call `findRecord(ctx)` **twice** on the same contaminated recorder
  and assert the captured sink contains the warning prefix **exactly
  once**. Rationale (I14 / I16): the first `findRecord` call emits the
  warning *and* evicts the contamination flag in its `finally` block
  (primary, exception-safe eviction — see I14), so the second call
  observes `hasCrossTestContamination(this) == false` and emits
  nothing. This pins the dedup contract that I14's `findRecord`-finally
  eviction adds beyond a hypothetical "evict only in `cleanResources`"
  design: under multi-annotation tests where two `RecordablePerformance`
  instances each call `findRecord` on the same recorder
  (e.g. `@ExpectSelect` + `@ExpectMaxSelect` both bound to the same
  `PersistenceSqlRecorder`), the user sees **one** warning per
  contaminated test, not N. Without I14's finally, this would emit
  twice.

- `findRecord_evicts_contamination_even_if_repository_throws` — F7
  exception-safety test (S5). Inject a stub `SqlRepository` whose
  `findExecutedQueries(...)` throws `RuntimeException("repo down")`.
  Mark the recorder in `CROSS_TEST_CONTAMINATED` via the
  package-private helper, then call `findRecord(ctx)` inside a
  `try/catch`. After the throw, assert
  `SqlRecorderRegistry.hasCrossTestContamination(recorder) == false`
  — the `finally` clause must have evicted the flag even though
  `findExecutedQueries` aborted before any propagation onto a
  `SqlExecutions` instance. Pins the I14 invariant ("primary,
  exception-safe eviction") against future refactors that quietly
  relocate the eviction back outside the try block. Without this test,
  F7's keystone property is unverified.

  *Verify:*
  ```
  mvn -pl sql/sql-annotations -am test \
      -Dtest=PersistenceSqlRecorderStderrWarningTest
  ```

The end-to-end warning signal is also asserted in
`SpringBootThreadingScenariosJunit5Test#concurrent_tests_with_async`
(per §7 commit 4 wrapper update — that wrapper inspects the assertion
failure message, which is composed by `format(...)` and so is
independent of the sink seam), but that test runs the full
JUnit Platform launcher and is a slow black-box guard. The sink unit
tests are the fast TDD gate for the channel-2 emission itself: ~50 ms,
no Spring, no JUnit Platform.

---

## 4. Correctness invariants to preserve

| # | Invariant | Why |
|---|---|---|
| I1 | In `register`, write `PER_THREAD_RECORDERS` **before** `ACTIVE_RECORDERS.add`. | Otherwise the registering thread can briefly observe the active set populated while its own per-thread fast-path is empty and route via the fallback. |
| I2 | In `unregister`, remove from `ACTIVE_RECORDERS` **before** removing from `PER_THREAD_RECORDERS`. | A worker that has just observed `PER_THREAD_RECORDERS.get(workerTid) == null` falls through to the active-set fallback. We want that fallback to stop seeing this recorder as soon as possible after `stopRecording` begins; reversing the order leaves a window where the recorder is still live in the fallback after the per-thread map already says it's gone. |
| I3 | When `register` displaces a previous same-class recorder from the per-thread map (line 221), the displaced recorder must be evicted from `ACTIVE_RECORDERS` **and** `CROSS_TEST_CONTAMINATED` too. Under the production framework lifecycle this branch is **not normally exercised**: a test thread runs one test at a time, the framework calls `register` once at `startRecording` and `unregister` once at `stopRecording`, so by the time the next test reuses the same Surefire thread, `unregister` has already cleaned the per-thread submap and the `displaced != null` check is `false`. The branch fires only on edge paths — (a) a test that registers two recorders of the same class on the same thread (no current QuickPerf annotation does this; possible if a user-defined `SpecifiableGlobalAnnotations` adds a second `@DisplaySql` etc.), (b) a Surefire fork that re-runs `startRecording` twice on the same recorder before `stopRecording`, (c) a custom test runner that bypasses `unregister` for one test and re-runs `register` on a recycled thread id — but the eviction is still required so those edge paths do not leak. | Without it, the displaced recorder lingers in `ACTIVE_RECORDERS` indefinitely, where it can (a) be returned by `getSqlRecorderOfType`'s active-set fallback in place of the freshly-registered one (both share the same class — iteration order is unspecified) and (b) accumulate stale contamination flags that leak memory. (Workers iterating `ACTIVE_RECORDERS` concurrently with a re-register may still observe the stale recorder for one iteration; this is acceptable — its `addQueryExecution` is a no-op into a soon-to-be-discarded repository.) The displacement-branch eviction does not race a concurrent `unregister(displaced)` because the displaced recorder is identity-keyed and `ACTIVE_RECORDERS.remove(displaced)` is idempotent — at most one of the two callers does the actual removal, the other no-ops. |
| I4 | Cross-test contamination is recorded into `CROSS_TEST_CONTAMINATED` based on the **count of distinct owner thread ids** observed in a single weakly-consistent snapshot of `ACTIVE_RECORDERS.entrySet()` (line 264-277). Specifically: the worker-fallback branches in `getSqlRecorders` and `getSqlRecorderOfType` snapshot `Map.Entry<SqlRecorder, Long>` pairs once into a local list, derive `distinctOwners.size()` from that same list, and fire the warning when it is `>= 2`. The trigger is **never** read from `PER_THREAD_RECORDERS.size()`, **never** from `ACTIVE_RECORDERS.size()`, and **never** from a second observation of the registry. | Two failure modes the obvious alternatives have. **(a) `ACTIVE_RECORDERS.size() >= 2` would false-positive** for any single test that legitimately registers more than one recorder — `@ExpectSelect + @ExpectJdbcBatching` registers `PersistenceSqlRecorder` + `SqlStatementBatchRecorder`, `@DisplaySql + @ExpectSelect` registers `DisplaySqlRecorder` + `PersistenceSqlRecorder`, etc. (see `SqlConfigLoader` for the full set of recorder classes). The active-set size counts *recorders*, not *tests*. **(b) Reading `PER_THREAD_RECORDERS.size() >= 2` after an independent `ACTIVE_RECORDERS` snapshot opens a snapshot-vs-trigger race**: a sibling test could `unregister` between the two reads, dropping `PER_THREAD_RECORDERS.size()` to 1 while the snapshot still contained both recorders — the worker would route SQL to both with no warning fired (silent wrong count, exactly the failure mode this invariant prevents). **(c) Storing the owner tid as the value for each recorder key in `ACTIVE_RECORDERS`** is the only formulation that makes the trigger derivable from the same observation as the returned recorder list, collapsing both failure modes into one: counting distinct owner tids in the snapshot measures distinct test threads (the actual condition), and the count comes from the same in-memory list as the returned recorders, so there is no second observation to race against. The trigger is structurally race-free under `ConcurrentHashMap`'s weakly-consistent iterator. There remains a **fundamental** detection limit, deferred to PR4/PR5 and documented in §5: a sibling test that runs SQL but registers no QuickPerf recorder (e.g. a non-QuickPerf-annotated test sharing the same JVM and DataSource) contributes 0 to `distinctOwners` — its SQL still routes through the worker fallback to *our* recorder, but the trigger does not fire. The proper fix is per-`Connection` recorder snapshot at pool-checkout, out of scope here. |
| I5 | `getSqlRecorderOfType` falls back to the active set when the per-thread map has no entry for that class, **and** adds the active recorders to `CROSS_TEST_CONTAMINATED` if applicable. | Some call sites (e.g. SQL recording on Tomcat workers that look up `PersistenceSqlRecorder` directly) currently rely on ITL inheritance and would otherwise return null. Recording contamination here matches `getSqlRecorders` and avoids silent under-reporting. |
| I6 | `clear()` removes only the calling thread's entries from `PER_THREAD_RECORDERS`, `ACTIVE_RECORDERS`, and `CROSS_TEST_CONTAMINATED`. | Two parallel tests must not be able to wipe each other. |
| I7 | The forked-JVM branch (`sqlRecorderByTypeOfTestJvm`, the connection-listeners collection, **and `ACTIVE_RECORDERS`**) uses thread-safe types (`ConcurrentHashMap`, `CopyOnWriteArrayList`). The forked-JVM `register` populates `ACTIVE_RECORDERS` (line 210) and the forked-JVM `unregister` actually removes the recorder from both `sqlRecorderByTypeOfTestJvm` and `ACTIVE_RECORDERS` (line 232-238) — was a no-op pre-PR1 (`SqlRecorderRegistry.java:51-55`). | Forked-JVM tests still run SQL on Tomcat / executor threads concurrently with the test thread; before PR1 the registry's forked-JVM branch never removed the recorder, leaving every worker's `getSqlRecorders()` call returning the recorder for the entire JVM lifetime. With the active-set fallback added in §2.1, this latent issue would also leave the recorder in `ACTIVE_RECORDERS` forever (the `unregister` no-op skipped both maps), so I10's unregister-first ordering would have been ineffective in forked mode (workers could still find this recorder during `flush(...)`, with the same tearing window as in single-JVM mode). The CAS variant `sqlRecorderByTypeOfTestJvm.remove(class, recorder)` and `ACTIVE_RECORDERS.remove(recorder, owner)` (CAS-emulated, see §2.1 prose) ensures we only remove the same instance — guards against a second `register` on the same recorder class displacing the prior entry mid-teardown. The forked-JVM branch contamination trigger is structurally `false` (only one test runs per JVM, so `distinctOwners.size() <= 1`), so the symmetric `ACTIVE_RECORDERS` population is purely for code-path uniformity and to keep `unregister` symmetric — it does not affect contamination semantics in forked mode. Realistic SQL-annotation × forked-JVM coverage is provided by the `sql/sql-memory-test` regression guards listed in §3.3 (`ConnectionLeakTest.*InNewJvm`, `ExpectMaxSelectTest.OneSelectButNoExpectedInSpecificJvm`, `ExpectJdbcBatching*Test`, `ExpectSelectedColumnTest`, `DisableStatementsTest`); no synthetic concurrency-stress test is added in PR1. |
| I8 | `SqlExecutions`' backing deque is `ConcurrentLinkedDeque`; size queries — including `getNumberOfExecutions()` and the `noJdbcExecution()` predicate at the head of `format(...)` (§2.6) — go through the `AtomicInteger`, **not** `Deque.size()`. | `ArrayDeque` is unsafe under concurrent `addLast`; `ConcurrentLinkedDeque.size()` is O(n) and is on the hot reporting path (`SqlAnalysisExtractor`, `SqlReport`, and the `noJdbcExecution` branch of `SqlExecutions.format`). |
| I9 | All `SqlExecutions` consumers iterate via the deque's iterator / for-each — never by index — and tolerate the deque's weakly-consistent semantics: an iteration may see writes that arrive mid-traversal, but each element is observed at most once. | `ConcurrentLinkedDeque` does not support `O(1)` random access; index-based reads would either fail or be silently inconsistent under concurrent writers. The weakly-consistent guarantee is what makes it safe to format a report while a worker thread (e.g. Tomcat) is still appending — at worst the iteration tears between the deque snapshot and the `AtomicInteger` count, but never throws `ConcurrentModificationException`. (Verified in PR1 against the current code: no index-based access exists; this invariant is a regression guard.) |
| I10 | `PersistenceSqlRecorder.stopRecording` calls `SqlRecorderRegistry.unregister(this)` **before** `sqlRepository.flush(...)` (and before the proxy-verifier warning). Applies in **both single-JVM and forked-JVM mode** (post-PR1: forked-JVM `unregister` actually removes — see I7). | Mirrors I2 at the recorder level: stop being visible to the active-set fallback as soon as teardown begins. Two distinct reasons. **(a) Worker writes during `flush`** — with flush first, any pool thread (Tomcat, `@Async`, scheduler, virtual thread) executing SQL on behalf of another test takes the active-set fallback, finds this still-live recorder, and calls `addQueryExecution(...)` concurrent with `flush`'s iteration over the repository. F0 makes `SqlExecutions.add` thread-safe against concurrent appends, but a write *concurrent with flush* still risks tearing between the deque snapshot and `executionCount`, and the late entry is misattributed to the wrong test. The same window also lets I12 mark `this` in `CROSS_TEST_CONTAMINATED` long after the test method has returned, inflating the contamination signal beyond what the test actually observed. **(b) Leak immunity** — unregister-first runs unconditionally, before any fallible operation, so a `flush` failure cannot leave the recorder in `ACTIVE_RECORDERS` forever (which would cause every subsequent test to see a permanent contamination warning). This is what the original code already did pre-PR1 in single-JVM mode (because `unregister` was a no-op in forked mode anyway, ordering was moot there); the dual-map registry just makes the consequence of getting it wrong sharper, and PR1 extends I10 to forked mode by activating forked-JVM unregister (I7). No `try/finally` is required because `unregister`'s body (`ConcurrentHashMap.remove` on identity-keyed entries) does not throw. |
| I11 | `CROSS_TEST_CONTAMINATED` is cleared by `startRecording` (defensive reset for the calling recorder), by `findRecord`'s `finally` block (the consumer recorder's primary, exception-safe eviction point — see I14), by `cleanResources` (defensive backstop for the case where `findRecord` was skipped — see I14), and by `clear()` (per-thread teardown, backstop for ad-hoc test utilities) — but **never** by `unregister`. | The framework calls `findRecord` *after* `stopRecording` (and therefore after `unregister`). Clearing contamination on `unregister` would erase the flag before its only consumer (`PersistenceSqlRecorder.findRecord`) reads it. The clear must happen at or after `findRecord`; placing the primary eviction inside `findRecord`'s own `finally` block is the only formulation that is exception-safe — see I14 for the full motivation (downstream evaluation can throw between `findRecord` and `cleanResources`, leaking the recorder if eviction lives only in `cleanResources`). |
| I12 | In the worker-thread fallback (`getSqlRecorders` and `getSqlRecorderOfType`), snapshot `ACTIVE_RECORDERS.entrySet()` once into a local `List<Map.Entry<SqlRecorder, Long>>`, then derive the contamination trigger (`distinctOwners.size() >= 2`) **from the same snapshot**, then mark only the `PersistenceSqlRecorder` entries in `CROSS_TEST_CONTAMINATED`, then return the **full** snapshot. The mark and the read **and the trigger derivation** must all see the same set. | `ACTIVE_RECORDERS` is a `ConcurrentMap` whose iterator is weakly consistent. Iterating it twice (once to snapshot, once to mark, once to trigger-read) opens multiple race windows: a recorder added between snapshot and mark is returned to the worker but never marked (silent wrong count), and a sibling-test `unregister` between snapshot and trigger-read flips `distinctOwners.size()` from 2 to 1 in the second observation while the first observation still has both — worker routes SQL to both with no warning fired (silent wrong count, exactly the failure mode I4 prevents). A single snapshot collapses all observations into one weakly-consistent read and closes both windows. The marking is **restricted to `PersistenceSqlRecorder`** because it is the sole consumer of the flag (§2.3, `findRecord`); marking other recorder classes (`DisplaySqlRecorder`, `SqlStatementBatchRecorder`, `DisplaySqlOfTestMethodBodyRecorder`) would leak them in `CROSS_TEST_CONTAMINATED` indefinitely — no per-class `findRecord`/`cleanResources` evicts them, and `unregister` deliberately does not (I11). The full snapshot is still returned so worker SQL is recorded by all active recorders, regardless of consumer-of-flag status. |
| I13 | In `PersistenceSqlRecorder.startRecording`, fully initialize the recorder's state (`sqlRepository = SqlRepositoryFactory.getSqlRepository(ctx)`) **and** clear stale `CROSS_TEST_CONTAMINATED` for `this` **before** calling `SqlRecorderRegistry.INSTANCE.register(this)`. Publish last. | Two distinct failure modes if `register(this)` runs first: **(a) NPE in `addQueryExecution`** — once the recorder is in `ACTIVE_RECORDERS`, any pre-existing pool thread (Tomcat HTTP worker, `@Async` / `@Scheduled` executor, virtual thread, gRPC handler, Reactor scheduler, JMS / Kafka listener) executing SQL at that instant takes the worker-thread fallback in `getSqlRecorders` (its tid is not in `PER_THREAD_RECORDERS`), receives this recorder, and calls `addQueryExecution(...)`. Unlike `findRecord` (which has a `sqlRepository == null` re-init guard for the forked-JVM branch), `addQueryExecution` has no null guard — so it dereferences a still-`null` `sqlRepository` and throws NPE on the worker thread. The window is exactly the scenario PR1 is designed to enable, so it is **not** theoretical. **(b) Silenced contamination warning** — during the window between `register` and `clearCrossTestContaminationFor(this)`, if a concurrent worker fallback observes a snapshot of `ACTIVE_RECORDERS` whose entries span ≥ 2 distinct owner tids (the new owner-tid trigger from I4 — a sibling test's recorder is also live), it may legitimately mark this fresh recorder in `CROSS_TEST_CONTAMINATED` (per I12); the subsequent clear then erases that real flag, and the test reports an inflated count with no warning — exactly the silent-wrong-count failure mode I12 was added to prevent. **Bonus: safe publication of `sqlRepository`.** The field is non-`final`, non-`volatile`. With `register` last, `ACTIVE_RECORDERS.put(this, tid)` (a `ConcurrentHashMap.put` underneath) happens-before any worker's iteration of `ACTIVE_RECORDERS` per the `ConcurrentMap` javadoc, so the prior write to `sqlRepository` is guaranteed visible — no `volatile` modifier is required. Reverse the order and there is no happens-before edge between the test thread's later assignment and a worker's read; a worker could observe `null` indefinitely. The fix is symmetric with I2 (unregister removes from active set first) and with the publication-ordering principle: build everything, then publish atomically. |
| I14 | `PersistenceSqlRecorder.findRecord(...)` evicts the recorder from `CROSS_TEST_CONTAMINATED` in a **`finally` block** that wraps the contamination read, the `markCrossTestContamination()` propagation, and the `System.err` emission. `cleanResources()` performs an idempotent backstop call to the same eviction helper, in case `findRecord` is skipped by a custom lifecycle. The eviction helper (`SqlRecorderRegistry.clearCrossTestContaminationFor(recorder)` — defined in §2.1) is the same in both call sites. | Without the `finally`, every contaminated recorder is retained by `CROSS_TEST_CONTAMINATED` (a `static final Set<SqlRecorder>` on `SqlRecorderRegistry`) for the entire JVM lifetime — leaking the `PersistenceSqlRecorder` instance and its `sqlRepository` reference, which for the in-memory case (`SqlMemoryRepository`) holds the full `SqlExecutions` deque (every `SqlExecution` captured during the contaminated test). Under Surefire `parallel=all, threadCount=5` running the new contamination scenarios, growth is ≥ 1 leaked recorder per contaminated test, unbounded over the lifetime of the JVM. **Why the `finally` is necessary, not just `cleanResources`:** the framework lifecycle is `startRecording → test → stopRecording → findRecord → cleanResources`, but the downstream callers that bridge `findRecord` and `cleanResources` (`QuickPerfTestExtension.java:173-177`, `MainJvmAfterJUnitStatement.java:66-71`, `QuickPerfTestNGListener.java:65-69`, `QuickPerfSqlTestNGListener.java:77-83`) evaluate `PerfIssuesEvaluator` between them *without* a `try/finally` around `cleanResources`. So any exception thrown by issue evaluation, measure extraction, or the reporter (e.g. an `AssertionError` formatting bug, a custom `VerifiablePerformanceIssue` that throws `RuntimeException`, or a Spring `BeanCreationException` from a misconfigured per-test bean) skips `cleanResources` entirely and the recorder leaks forever. **Why eviction inside `findRecord` is safe:** the contamination flag has already been propagated onto the returned `SqlExecutions` instance before the `finally` runs, so `format(...)` will still see the flag and emit the warning prefix; and `SqlMemoryRepository.findExecutedQueries` returns the **same** `SqlExecutions` instance on every call (`SqlMemoryRepository.java:36`) — so a hypothetical second `findRecord` invocation (e.g. a future `RecordablePerformance` that consumes the same recorder twice) would receive the already-marked instance and `format(...)` would still emit the warning, even with the registry entry already evicted. Eviction inside `findRecord` also dedupes the `System.err` warning emission (I16) to one per contaminated lifecycle (was N for N annotations consuming the same recorder, since each `findRecord` call emitted before evicting). **Why `cleanResources` keeps a backstop call:** the `findRecord`-finally only fires on lifecycle paths that actually invoke `findRecord` — e.g. a custom test runner that stops at `stopRecording` and never evaluates issues would skip the primary eviction. The backstop is a one-line idempotent call (`clearCrossTestContaminationFor(this)` is a `Set.remove` on identity-keyed entries — no-op when the entry is already gone), cost-free on the production path. **Why the `clear()` helper in §2.1 is *not* a rescue on the production lifecycle path:** by the time anything could call `clear()`, `unregister` has already emptied the per-thread submap, so `CROSS_TEST_CONTAMINATED.removeAll(perThread.values())` operates on an empty set; only `findRecord`'s `finally` and `cleanResources` evict at the right time. The narrowing in I12 (only `PersistenceSqlRecorder` instances enter `CROSS_TEST_CONTAMINATED`) is a co-requisite — without it, sibling recorders (`DisplaySqlRecorder`, `SqlStatementBatchRecorder`, `DisplaySqlOfTestMethodBodyRecorder`) would also be retained by the set indefinitely, since none of them have a `findRecord` body that evicts. |
| I15 | `SqlExecutions.NONE` (`public static final SqlExecutions NONE = new SqlExecutions()`, `SqlExecutions.java:30`) is JVM-wide-shared and **must never be mutated**. Defence-in-depth: (a) `PersistenceSqlRecorder.findRecord` swaps `NONE` for a fresh `new SqlExecutions()` before calling `markCrossTestContamination()` so the warning still surfaces in `format(...)` (§2.4); (b) `SqlExecutions.markCrossTestContamination()` itself early-returns when `this == NONE` (§2.5) as a safety net for any future caller. | `NONE` is returned by `SqlFileRepository.retrieveExecutedQueriesFromFile` (lines 63 and 67) when the persisted SQL file is missing or `null`, and by `DisplaySqlRecorder.findRecord` / `DisplaySqlOfTestMethodBodyRecorder.findRecord`. A single mutation flips `NONE.crossTestContamination = true` permanently, and every subsequent `format(...)` call that observes the singleton would print the contamination warning prefix from §2.6 — a JVM-wide false positive on unrelated tests for the rest of the process lifetime (sticky in CI runs, sticky inside Surefire forks, sticky inside a long-lived IDE test session). On the happy path the bug is unreachable: in single-JVM mode `SqlMemoryRepository.findExecutedQueries` returns a fresh per-recorder `SqlExecutions` (never `NONE`), and in forked-JVM mode the contamination trigger is structurally `false` (only one test runs per JVM, so any `ACTIVE_RECORDERS` snapshot has `distinctOwners.size() <= 1` — see I4 / I7) so `hasCrossTestContamination(this)` is always `false` and the `NONE`-returning paths in `SqlFileRepository` are never reached while the flag is `true`. The fix is therefore explicitly defensive — it guards against future regressions (a refactor that lets in-memory mode return `NONE`, a new feature that invokes `markCrossTestContamination` from a code path other than `findRecord`, a faulty `hasCrossTestContamination` gating) rather than a current bug, but the cost is two lines and the failure mode (silent JVM-wide warning poisoning) is severe enough to warrant the belt-and-suspenders approach. The setter is a silent no-op rather than an exception so that a stray mutation cannot crash the test runner. |
| I16 | The cross-test contamination warning surfaces via **two independent channels**, both keyed off the same `SqlExecutions.CROSS_TEST_CONTAMINATION_WARNING` constant (package-private, §2.5): (i) `System.err.println` emitted from `PersistenceSqlRecorder.findRecord` immediately after `markCrossTestContamination()` (§2.4) — fires unconditionally on every contaminated `findRecord` call, ensuring visibility on **passing** contaminated tests; (ii) the `format(perfIssues)` prefix in `SqlExecutions.format(...)` (§2.6) — composed into the assertion failure message, ensuring inline visibility on **failing** contaminated tests. Removing either channel silently regresses one of the two paths. | `PerfIssuesEvaluator.buildPerfIssuesByPerfRecord` (`PerfIssuesEvaluator.java:139`) only retains a `PerfRecord` in `perfIssuesByPerfRecord` (and therefore only invokes `format(perfIssues)` on it later) when its associated `perfIssues` list is non-empty — i.e. when at least one annotation-driven assertion was violated. So a contaminated test whose inflated count happens to satisfy `@ExpectMaxSelect(N)`, or a contaminated test whose only annotations are structural (`@DisableLikeWithLeadingWildcard`, `@DisableSameSelects`, `@DisableExactSameSelectTypeWithDifferentParameters`, `@DisableStatements`) and whose contaminated extra queries happen to be benign, would have its `findRecord` called and its `markCrossTestContamination()` set, but `format(...)` would never run — the warning would be silent. The `System.err` channel closes that gap: Surefire (per `<reportFormat>brief</reportFormat>` and standard XML reporters), JUnit Platform's `@Execution`-aware reporter, IntelliJ / IDEA, Eclipse JDT, and `mvn test`'s own per-test stderr block all capture `System.err` per test method and surface it in the per-test report section, so the warning reaches the user regardless of pass/fail. The channels are **not** combined into a single emission point for two reasons: (a) the inline prefix makes the assertion failure message self-explanatory without requiring the user to cross-reference the stderr block; (b) the stderr emission survives any future change to the `format(...)` call path (e.g. a `core` refactor that bypasses `PerfIssuesFormat` for non-issue records). The intentional duplication on the failure path (one stderr line + one inline prefix) is the cost; severing the duplication would require a `PerfIssue.Severity` model in `core` (rejected — see §2.6 prose) or always running `format(...)` even with zero issues (a `core`-package contract change). |
| I17 | `SqlExecutions.add(ExecutionInfo, List<QueryInfo>)` and the package-private `SqlExecutions.addWithoutCall(ExecutionInfo, List<QueryInfo>)` early-return when `this == SqlExecutions.NONE` (§2.5 item 1) and otherwise execute `sqlExecutions.addLast(...)` **first**, then `executionCount.incrementAndGet()` **second**. The order is reversed for no callers and must not be reordered to "increment first" or batched into a non-atomic compound. | The `NONE` early-return is the same defence-in-depth principle as I15 applied to the append path: the `SqlExecutions.NONE` singleton must never be mutated. Pre-PR1 the ITL-based design ensured no live recorder ever held a `NONE` reference, so `add`/`addWithoutCall` blindly delegated to `sqlExecutions.addLast(...)` (verified at lines 34-44 of the existing source). Under the dual-map design a worker thread can take the active-set fallback during a brief contamination window and find a sibling test's recorder; a future regression in repository wiring (e.g. a code path that lets `SqlMemoryRepository` return `NONE` to a live recorder, or a reactive/R2DBC adapter that hands `NONE` to a worker on cold start) would then call `add(...)` on `NONE`, mutating its deque and counter for the entire JVM lifetime — every subsequent test that reads `noJdbcExecution()` or iterates the deque against `NONE` would observe leaked `SqlExecution` entries from past contaminated tests, silently inflating counts on unrelated tests and breaking `@ExpectSelect(0)` JVM-wide. The fix is two `if (this == NONE) return;` checks, no behavioural change on the happy path. (`addWithoutCall` is **private** and is only called by `filterByQueryType` on a freshly-constructed instance, never `NONE`, so its guard is purely defence-in-depth — see the §2.5 `filterByQueryType` mutator-path note.) **Why the pinned write order matters:** a reader that observes `executionCount.get() == N` is guaranteed to see at least N elements in the deque on iteration (release/acquire happens-before chain via `incrementAndGet`'s volatile-write semantics). With `addLast` first, the only tearing direction is "deque has N+1 elements while counter still reads N" — i.e. the counter is a **lower bound** on deque size. Reversing the order would make the counter an upper bound, a strictly worse direction: count-style assertions (`@ExpectSelect(N)`, `@ExpectMaxSelect(N)`) read `executionCount` and would fail spuriously when reading "N+1 in counter but only N in deque." Today the assertion-evaluating test thread reads `executionCount` only *after* `stopRecording → findRecord` (which involves a `ConcurrentHashMap.remove` happens-before chain), so all worker append-then-increment pairs are fully visible by then; the tearing window matters only for hypothetical mid-test concurrent observers, but pinning the order makes the invariant explicit for future maintainers and resilient against future readers (e.g. a streaming reporter, a pluggable progress hook). |

---

## 5. Known limitations after PR1 (deferred to later PRs)

| Limitation | Resolution |
|---|---|
| Concurrent tests sharing a Spring context + Tomcat pool: the warning fires (visible to the user via `System.err` on passing tests and inline in the assertion message on failing tests — I16) but the test still uses an inflated count. | PR2 (`QuickPerfContext.wrap` + Spring `TaskDecorator` autoconfig) gives the user a remediation path — but **only for execution paths that flow through Spring's `TaskExecutor` SPI**: `@Async` (handled by `AsyncConfigurer.getTaskExecutor()` → decorated `ThreadPoolTaskExecutor`), `@Scheduled` (handled by `TaskScheduler`'s `taskDecorator`), and explicit `TaskExecutor` injection. Out of PR2's reach: Tomcat HTTP request worker threads (HTTP attribution requires a Servlet `Filter` or `HandlerInterceptor`, slated for PR3 / PR4), JMS / Kafka / RabbitMQ message-listener pools (`@JmsListener` / `@KafkaListener` / `@RabbitListener` use their own listener-container threads, not `TaskExecutor`s), raw `java.util.concurrent.Executor` / `ExecutorService` / `ForkJoinPool.commonPool()` instances created by user code, Reactor (`Schedulers.parallel()` / `boundedElastic()`) and gRPC executor pools, and any vendor-specific async pool (Apache HTTP client, Netty event loops). Those remain reachable only via the active-set fallback's "two distinct owners" detection until PR4 / PR5 ship per-`Connection` snapshot at pool-checkout. PR3 tightens the warning into a forced failure (synthetic `PerfIssue` of WARNING severity, conditional on opt-in or fail-by-default — exact contract TBD in PR3). |
| **Late-worker mis-attribution after a sibling test's `unregister`** (silent-wrong-count window): a worker thread executing SQL on behalf of test A may take the active-set fallback at a moment where test B has already returned and `unregister`ed but test A is still mid-execution. The fallback snapshots `ACTIVE_RECORDERS` and observes only one owner tid (test A's), so `distinctOwners.size() == 1` — no contamination warning fires — but the snapshot still contains test A's recorder, so the worker's late SQL is appended to A's `SqlExecutions`. If the SQL was actually triggered by test B (e.g. a deferred `@Async` callback queued during B's execution), it inflates A's count silently. The window is small (between B's `unregister` and the worker's `addQueryExecution`), but on `parallel=all, threadCount=5` with realistic latency it is observable. | Fundamental detection limit of the dual-map model under PR1: the warning trigger requires ≥ 2 distinct owners visible *in the snapshot*, which a late worker cannot observe. **Resolution**: PR4/PR5 per-`Connection` recorder snapshot at pool-checkout — the worker captures the recorder reference at the moment SQL begins (before B unregisters), eliminating the timing-dependence. The user-facing remediation in PR2 (`QuickPerfContext.wrap` + Spring `TaskDecorator`) propagates the ITL via decorated `Runnable`/`Callable` so `@Async` callbacks return to the originating test thread's recorder by name rather than by active-set fallback, which closes this window for the Spring case. |
| **Sibling tests that run SQL but register no QuickPerf recorder** (e.g. a non-QuickPerf-annotated JUnit test sharing the same JVM, classloader, and DataSource — common in Surefire `parallel=all` mixed-test-suite layouts, or in IDE "run all tests" mode): they contribute 0 entries to `ACTIVE_RECORDERS`, so `distinctOwners.size()` of any worker-fallback snapshot is unaffected by them. The QuickPerf-annotated test's worker may still receive that test's SQL via the active-set fallback (worker tid not in `PER_THREAD_RECORDERS`, so it iterates `ACTIVE_RECORDERS` and finds the one live recorder = ours), inflating our test's count silently. | Fundamental detection limit of registry-only attribution: detection by definition requires both contaminating tests to be QuickPerf-annotated (the only signal we have). **Resolution**: PR4/PR5 per-`Connection` recorder snapshot at pool-checkout — captures the recorder reference at the moment a `Connection` is acquired, so non-QuickPerf-annotated tests acquire the JVM-wide `null` recorder and their SQL is dropped instead of routed to ours. The PR2 `QuickPerfContext.wrap` decorator plus a "non-QuickPerf tests must run in their own JVM" recommendation in the PR2 / PR3 release notes is the user-facing intermediate mitigation. |
| Quarkus, Micronaut, reactive, R2DBC: no automatic DataSource wrapping; users still call `QuickPerfSqlDataSourceBuilder` manually. | PR4 / PR5 / PR6 / PR7. The per-Connection snapshot originally drafted for PR1 lives there — it requires either intercepting at pool-checkout (Agroal `PoolInterceptor`, Micronaut `DataSource` listener) or wrapping `QuickPerfDatabaseConnection.createStatement(...)` to round-trip back to the QuickPerf wrapper, both of which are out of scope here. |
| Thread-id reuse on a thread that died mid-test without `unregister`, OR a sibling-class stale recorder displaced by a sibling annotation pair (e.g. leftover `SqlStatementBatchRecorder` from a previously-killed test on the same reused thread id). | Mitigated by I10 (unregister-first ordering in `PersistenceSqlRecorder.stopRecording`). Unregister-first covers the **normal** failure path — the test method returns or throws, `unregister` runs (before `flush` could throw), and the per-thread submap is cleaned. It does **not** cover (a) test threads killed without lifecycle (e.g. `Thread.stop`, OOME on the test thread, JVM signals), (b) `Error` propagating past the framework's extension boundary if a custom listener swallows the lifecycle, or (c) custom test runners that bypass `QuickPerfTestExtension` / `QuickPerfJUnitRunner` entirely. Surefire's `parallel=all, threadCount=5` reuses test threads, so the residual risk is bounded in practice; a stronger fix (e.g. `WeakReference<Thread>` keys, or an aggressive sweep of all displaced same-tid entries on `register`) is not in PR1 scope. |
| Buffering Quarkus startup-SQL (DDL, Flyway). | Out of scope — startup SQL should not count toward `@ExpectSelect`, identical to Spring Boot behavior. |
| Test threads killed without reaching `cleanResources` (e.g. `Thread.stop`, OOME on the test thread, JVM signals, custom listeners that swallow the lifecycle, custom test runners that bypass `QuickPerfTestExtension` / `QuickPerfJUnitRunner`) leak the contaminated `PersistenceSqlRecorder` in `CROSS_TEST_CONTAMINATED`. | Same residual category as the `unregister`-leak row above; the `findRecord`-finally primary eviction (I14) catches the common case where downstream evaluation throws (`PerfIssuesEvaluator` / measure extractors / reporter), but does not catch lifecycles where `findRecord` itself is never invoked. The `cleanResources` backstop and the `clear()` helper catch the case where a test utility explicitly invokes `SqlRecorderRegistry.INSTANCE.clear()` (e.g. an `@After` hook); under Surefire `parallel=all, threadCount=5` test threads are reused, so the residual risk is bounded in practice. A stronger fix (e.g. weak references in `CROSS_TEST_CONTAMINATED`, or wiring `cleanResources` into a JVM shutdown hook) is not in PR1 scope. |
| **Cross-test attribution for connection-leak detection** (`ConnectionLeakListener.connections`): the listener is shared across recorders (`ConnectionListenerRegistry` is also process-wide), and PR1 makes its `connections` set thread-safe (handled in `pr1-additional-thread-safety.md`) but does **not** route open/close events to the originating test's listener instance. A worker-thread `Connection.close` triggered by test A may fire on a listener entry registered by test B, producing false-negative leak reports for B (a leaked B-connection counted as closed because A closed it) or false-positive leak reports for A (an A-connection still open at A's `findRecord` time appears leaked even if A's logic intended to keep it for follow-up cleanup). The `BooleanMeasure` carrier produced by `ExpectNoConnectionsLeak` has no `format(...)` warning-prefix slot, so PR1's contamination flag does not extend to this carrier. | Documented in §2.2 caveat box. **Resolution**: PR4/PR5 per-`Connection` listener snapshot at pool-checkout — captures the listener reference at the moment a `Connection` is acquired, eliminating cross-test routing. PR3 may add a `BooleanMeasure`-side warning channel (likely a structured measure-level warning surface that all measures opt into) to give users an inline failure mode parallel to I16's `format(...)` prefix; the exact contract is TBD in PR3 and out of scope here. |

---

## 6. Risk and rollback

### Scope

Production code (all under `sql/sql-annotations/src/main/java/`):
- **Dual-map registry rewrite (this document):**
  `SqlRecorderRegistry`, `ConnectionListenerRegistry`,
  `PersistenceSqlRecorder` (lifecycle ordering, `findRecord`
  contamination flag + `NONE` swap + `System.err` emission,
  `cleanResources` eviction), `SqlExecutions` (deque migration,
  `AtomicInteger` counter, contamination flag/setter/getter,
  `format(...)` prefix, `CROSS_TEST_CONTAMINATION_WARNING` constant).
- **F1 companion thread-safety (`pr1-additional-thread-safety.md`,
  ships in this PR):** `SqlStatementBatchRecorder`
  (thread-safe `differentBatchSizes` set + `volatile
  previousStatementsAreBatched`), `ConnectionLeakListener`
  (thread-safe `connections` set), `DataSourceProxyVerifier`
  (`volatile` fields). Required because the new active-set fallback
  makes these classes reachable from worker threads (§1, §7 commit 2).

Test code:
- `sql/sql-annotations/src/test/java/org/quickperf/sql/`:
  - **New (5 files):** `SqlExecutionsConcurrencyTest` (§3.1, F0 TDD
    gate), `SqlExecutionsNoneImmutabilityTest` (§3.4, I15),
    `PersistenceSqlRecorderStderrWarningTest` (§3.5, I16),
    plus `SqlStatementBatchRecorderConcurrencyTest` and
    `ConnectionLeakListenerConcurrencyTest` (companion plan §3.1 / §3.2,
    F1 TDD gates).
  - **Modified (1 file):** `SqlRecorderRegistryTest` (§3.2 — adds
    `@After` cleanup and the worker-fallback smoke test
    `register_then_get_returns_recorder_on_unrelated_worker_thread`).
- `spring/junit5-spring-boot-3-test/src/test/java/.../SpringBootThreadingScenariosJunit5Test.java`:
  - **Modified (commit 4):** `concurrent_tests_with_async` and
    `concurrent_tests_with_random_port` wrapper assertions
    (`getTestsFailedCount().isZero()` →
    `getTestsFailedCount().isOne()` + warning-text checks). Required
    because the inner `CrossTestContaminationWithConcurrent…` tests
    (pre-staged in d42e057) now genuinely fail with the warning.

Regression guards kept green (no modifications, just listed in §3.3 as
must-pass): `sql/sql-memory-test/**` (forked-JVM × SQL annotation
suite), `junit5/junit5-sql-test/**`
(`JUnit5ThreadingScenariosTest`, `QuickPerfParallelExecutionTest`),
plus the unmodified `DetectionOfNPlusOneSelectWith*` /
`DetectionOfSelectWithScheduled` classes in the same Spring Boot 3
test module.

User-facing public API surface (`@QuickPerfTest`, `@ExpectSelect`,
`@ExpectJdbcBatching`, `@ExpectNoConnectionLeak`, the runners, the
JUnit 4/5 / TestNG extensions, the listeners, the `SqlRecorder`
interface, the three other recorders' public method signatures) —
**unchanged**.

### Risk level

| Surface | Risk | Mitigation |
|---|---|---|
| Public API breakage | None | No public type / method signatures change; `SqlRecorder` interface is not touched (§2.3). |
| Internal correctness (registry, contamination, deque) | Moderate | I1–I16 invariants (§4) plus the §3 unit tests + §3.3 end-to-end regression guards. The dual-map registry replaces an `InheritableThreadLocal` design that has been in place since the project's early commits — there is genuine model-change risk, partially mitigated by keeping the read-side API (`getSqlRecorders`, `getSqlRecorderOfType`) backward-compatible. |
| Test-suite churn | Moderate | One Spring Boot 3 module's wrappers genuinely change behavior at commit 4 (zero failures → one failure with warning). The change is intentional — it validates the warning channel — but it does mean the PR branch goes red until commit 4 lands the wrapper update. |
| Cross-module ordering hazard | Mitigated by §7 commit order | F1 (commit 2) must land **before** the dual-map registry (commit 3); see §7 commit 2 / I7. Reversing the order opens a `git bisect` window where workers reach unsafe state. |
| Memory leak via `CROSS_TEST_CONTAMINATED` | Bounded | I14 + `cleanResources` evict; I12-narrowing prevents non-consumer recorder retention; under Surefire `parallel=all, threadCount=5` thread reuse keeps residual leaks bounded. Documented in §5. |
| Forked-JVM regressions | Low | The forked-JVM branch already has dedicated regression coverage in `sql/sql-memory-test` (§3.3); contamination is impossible in forked-JVM mode (§2.4). |

### Rollback

Single-PR revert is straightforward, but commits are **only top-down
independently revertable** (the §7 ordering encodes a forward
dependency, so reverts must walk it in reverse):

- **Commit 4** (contamination warning + wrapper assertions) reverts
  cleanly → registry remains dual-map, no warning.
- **Commit 3** (dual-map registries + active-set fallback) reverts
  cleanly **after** commit 4 → ITL-based registry restored, F1
  thread-safety still in place (harmless on top of the original
  single-threaded design).
- **Commit 2** (F1) reverts cleanly **only** after commit 3 is
  reverted; reverting it while commit 3 is still in place re-exposes
  the worker-reachable unsafe state.
- **Commit 1** (F0 — `ConcurrentLinkedDeque` + `AtomicInteger`)
  reverts cleanly **only** after commits 2-4 are reverted; reverting
  it while the active-set fallback is live brings back the racy
  `ArrayDeque.addLast` under concurrent writers.

Equivalently, a single full-PR revert (`git revert -m 1 <merge-sha>`,
or `git revert <c4>..<c1>` if rebased) restores the pre-PR1 state in
one step and is the recommended path. Per-commit reverts are
supported only in reverse order.

---

## 7. Suggested commit breakdown (within the single PR)

All tests listed in §3 are written before any implementation work
begins. Each commit then ships its tests and the implementation that
makes them pass together, atomic and bisection-friendly.

The commit sequence is therefore:

1. **F0 — `SqlExecutions` thread-safety.**
   - Tests (§3.1):
     `sqlExecutions_addLast_under_concurrent_writers_does_not_throw_or_lose_entries`
     and `sqlExecutions_getNumberOfExecutions_matches_actual_count_under_concurrent_writes`.
   - Implementation: `ArrayDeque` → `ConcurrentLinkedDeque`; add
     `AtomicInteger executionCount`; replace `noJdbcExecution()` body.

   Lands first because every later commit exercises the buffer
   concurrently and would otherwise be flaky.

   **Bisection isolation.** F0 is safe to ship before the dual-map
   registry: pre-dual-map, `SqlRecorderRegistry` still uses
   `InheritableThreadLocal`, so a worker thread `@Async` only ever
   reaches `PersistenceSqlRecorder.executions` via ITL inheritance —
   one writer per test thread plus its inherited workers. The
   `ArrayDeque` → `ConcurrentLinkedDeque` swap is therefore a
   semantically null change at this commit (no behavior depends on
   `ArrayDeque`'s identity, only on `Deque<SqlExecution>`); it merely
   pre-positions the buffer to tolerate the **multiple** writers per
   recorder that the dual-map active-set fallback introduces in
   commit 3. A `git bisect` landing on F0 runs the entire existing
   test suite unchanged.

   *Verify (must fail before, pass after):*
   ```
   mvn -pl sql/sql-annotations -am test -Dtest=SqlExecutionsConcurrencyTest
   ```

2. **F1 — Thread-safe state on `SqlStatementBatchRecorder`,
   `ConnectionLeakListener`, and `DataSourceProxyVerifier`** (specified
   in `pr1-additional-thread-safety.md`).

   - Tests: `SqlStatementBatchRecorderConcurrencyTest`,
     `ConnectionLeakListenerConcurrencyTest` (per the companion plan
     §3.1 / §3.2).
   - Implementation: thread-safe `differentBatchSizes` set + `volatile
     previousStatementsAreBatched` on the batch recorder; thread-safe
     `connections` set on the connection-leak listener; `volatile`
     fields on the proxy verifier.

   Lands **before** the dual-map registry commit so the active-set
   fallback (which makes these classes reachable from worker threads)
   never goes live against unsafe state. Bisection failure mode if
   reversed: a `git bisect` landing between commit 3 (dual-map registry)
   and a later F1 commit would expose `SqlStatementBatchRecorder`,
   `ConnectionLeakListener`, and `DataSourceProxyVerifier` to concurrent
   writes from `@Async` / Tomcat / scheduler threads via the new
   active-set fallback while their internal state (`int[]
   differentBatchSizes`, `List<Connection> connections`, non-`volatile`
   verifier fields) is still single-threaded — producing intermittent
   `ArrayIndexOutOfBoundsException` from `Arrays.copyOf`,
   `ConcurrentModificationException` from `connections` iteration, and
   stale-value reads on the verifier — the very symptoms F1 fixes,
   manifesting on commits between two passing PR1 commits and confusing
   bisection. Equivalently F1 can be folded into commit 3 as a single
   atomic change — but a separate commit keeps each plan independently
   bisectable.

   *Verify (must fail before, pass after — see companion plan §3 for
   exact commands):*
   ```
   mvn -pl sql/sql-annotations -am test \
       -Dtest=SqlStatementBatchRecorderConcurrencyTest,ConnectionLeakListenerConcurrencyTest
   ```

3. **Dual-map registries + active-set fallback.**
   - Tests (§3.2): the worker-fallback smoke test
     (`register_then_get_returns_recorder_on_unrelated_worker_thread`)
     and the `@After` cleanup added to the existing
     `SqlRecorderRegistryTest`.
   - Implementation: refactor `SqlRecorderRegistry` to dual-map;
     mirror in `ConnectionListenerRegistry`. Make forked-JVM
     collections thread-safe (`ConcurrentHashMap`,
     `CopyOnWriteArrayList`). Update `getSqlRecorderOfType` /
     `getConnectionListenerOfType` with the active-set fallback.
     Activate the forked-JVM `unregister` path (was a no-op pre-PR1
     — `SqlRecorderRegistry.java:51-55` — see I7).
   - **`PersistenceSqlRecorder.startRecording` publish-last reorder
     ships in this commit (CR2 in the synthesis review).** Today's
     `startRecording` calls `register(this)` **before** assigning
     `sqlRepository = new SqlMemoryRepository(this)` — pre-PR1 that
     ordering was harmless because no other thread could observe the
     recorder via the per-thread ITL during its own
     `startRecording`. Post-commit-3 a worker thread that takes the
     active-set fallback **can** observe the recorder mid-construction
     and call `addQueryExecution(...)` → `sqlRepository.add(...)` →
     NPE on the still-`null` field. The fix is a one-line reorder:
     `sqlRepository = new SqlMemoryRepository(this);
     SqlRecorderRegistry.INSTANCE.register(this);` — the recorder is
     never published into the active set or the per-thread submap
     until its `sqlRepository` field is fully initialised (I13).
     Do **not** add the `clearCrossTestContaminationFor(this)` call
     here — that helper does not exist until commit 4 — but the
     reorder itself MUST land in commit 3 to close the NPE window
     for any bisect that lands between commits 3 and 4.
   - **Replace `Thread.sleep(2000)` with `CountDownLatch`
     orchestration in
     `CrossTestContaminationWithConcurrentAsync.test_with_no_sql`
     and
     `CrossTestContaminationWithConcurrentRandomPort.test_with_no_sql`
     (CR5 in the synthesis review).** Today both tests sleep 2 s
     to "give the SQL-firing sibling time to finish" — that is a
     wall-clock guess, not an ordering guarantee, and is brittle
     under cold JIT, slow CI executors, debug-port attach delays,
     or any future test that finishes faster than 2 s and races the
     no-SQL test back to the registry. Replace with two
     `CountDownLatch` instances scoped to the inner test class:
     `BOTH_REGISTERED = new CountDownLatch(2)` decremented in each
     inner test's `@BeforeEach` (gates the SQL-firing test from
     starting work until both recorders are registered, which is
     what triggers the active-set fallback's "two distinct owners"
     contamination trigger), and `WORKER_DONE = new CountDownLatch(1)`
     released by the SQL-firing test's `@AfterEach` after its
     `@Async` worker has completed and its recorder has been
     unregistered. `test_with_no_sql` then `await(...)` s on
     `WORKER_DONE` (with a generous timeout — 30 s — so a
     genuine deadlock surfaces as a clear test failure, not a
     2-second silent skip) before its assertion. The orchestration
     guarantees: (a) both tests are simultaneously registered when
     the SQL fires (the contamination trigger condition), (b)
     `test_with_no_sql` runs strictly **after** the SQL-firing
     worker has flushed and unregistered (so its observation
     reflects the contamination flag, not pre-flush state), and
     (c) no wall-clock dependency. The fan-out semantics of
     `DataSourceQuickPerfListener.afterQuery` (see "Why the wrapper
     update lives in commit 3" below) make the assertion robust
     even without the latch — but the latch removes a class of
     debug-port and slow-CI flakes that would otherwise dog the
     PR's regression-guard story.
   - **Wrapper assertion update — count only, no warning text yet**
     (`SpringBootThreadingScenariosJunit5Test#concurrent_tests_with_async`
     and `#concurrent_tests_with_random_port`): flip
     `summary.getTestsFailedCount().isZero()` to
     `summary.getTestsFailedCount().isOne()`, but **do not** add
     `.contains("WARNING: ...")` checks in this commit. The
     warning-text assertions land in commit 4 alongside the
     `CROSS_TEST_CONTAMINATION_WARNING` constant they reference.

   **Why the wrapper update lives in commit 3, not commit 4** — at
   this commit the dual-map active-set fallback is live but no
   contamination flag exists yet. The
   `CrossTestContaminationWithConcurrentAsync` inner test is
   annotated `@ExpectSelect(0)` and fires SELECTs on an `@Async`
   worker. Pre-PR1 (ITL) the worker observed no recorder via
   inheritance and the SELECT was silently dropped, so
   `@ExpectSelect(0)` passed (false negative — the silent-wrong-
   count failure mode the whole PR exists to fix). Post-commit-3
   the worker takes the active-set fallback, finds **every**
   currently-registered recorder via `getSqlRecorders()` ; the
   `DataSourceQuickPerfListener.afterQuery` hook iterates that
   collection and calls `addQueryExecution(...)` on **every**
   recorder (fan-out — verified at
   `DataSourceQuickPerfListener.java`'s `afterQuery` body: the
   listener does not pick a single recorder, it broadcasts each
   query). Concretely: in the concurrent-async scenario the SQL-
   firing test produces 3 SELECTs from its `@Async` worker, the
   no-SQL test (`@ExpectSelect(0)`) is concurrently registered, and
   all 3 SELECTs are appended to **both** recorders' `executions`
   buffers. The `@ExpectSelect(0)` assertion on the no-SQL test
   then sees 3 executions and fails — independent of CountDownLatch
   timing. The orchestrator wrapper observes one failed inner test
   in the `TestExecutionSummary` and the `.isZero()` assertion in
   the wrapper goes red unless flipped to `.isOne()` in this same
   commit. Leaving the flip for commit 4 would break PR1 bisection:
   `git bisect` landing on commit 3 would surface the wrapper
   failure as a regression in commit 3 even though the underlying
   contract (workers must see recorders) is exactly what commit 3
   ships. Pinning the count flip to commit 3 keeps each commit
   green; the `.contains("WARNING: ...")` checks layer on top in
   commit 4 once the warning text constant exists.

   Regression guards in §3.3 (`QuickPerfParallelExecutionTest`,
   `JUnit5ThreadingScenariosTest`,
   `SpringBootThreadingScenariosJunit5Test` — whose
   `concurrent_tests_with_async` / `concurrent_tests_with_random_port`
   wrappers orchestrate the `CrossTestContaminationWithConcurrent…`
   reproducers — plus the SQL-annotation × forked-JVM tests under
   `sql/sql-memory-test`: `ConnectionLeakTest.*InNewJvm`,
   `ExpectMaxSelectTest.OneSelectButNoExpectedInSpecificJvm`,
   `ExpectJdbcBatching*Test`, `ExpectSelectedColumnTest`,
   `DisableStatementsTest`) must remain green after the wrapper
   `.isOne()` flip.

   *Verify:*
   ```
   # New + existing registry unit tests:
   mvn -pl sql/sql-annotations -am test \
       -Dtest=SqlRecorderRegistryTest,ConnectionListenerRegistryTest

   # Threading / parallel regression guards:
   mvn -pl junit5/junit5-sql-test -am test \
       -Dtest=JUnit5ThreadingScenariosTest,QuickPerfParallelExecutionTest
   mvn -pl spring/junit5-spring-boot-3-test -am test \
       -Dtest=SpringBootThreadingScenariosJunit5Test,DetectionOfNPlusOneSelectWithAsync,DetectionOfNPlusOneSelectWithCompletableFuture,DetectionOfSelectWithScheduled

   # Forked-JVM × SQL-annotation regression guards:
   mvn -pl sql/sql-memory-test -am test \
       -Dtest=ConnectionLeakTest,ExpectMaxSelectTest,ExpectJdbcBatchingWithBatchSizeTest,ExpectJdbcBatchingWithoutBatchSizeTest,ExpectSelectedColumnTest,DisableStatementsTest
   ```
   *(Adjust the `-pl` module paths if a regression-guard test lives
   in a different submodule — `git grep -l <ClassName>` resolves it
   in one call. Do NOT add `-DfailIfNoTests=false` blindly: a
   misspelled test name should fail the verify step, not silently
   skip.)*

4. **Cross-test contamination flag + warning text.**

   Single atomic commit — no interface scaffolding step needed because
   `SqlRecorder` is not modified.

   1. Add the `CROSS_TEST_CONTAMINATED` set and the three static methods
      (`markCrossTestContamination`, `hasCrossTestContamination`, `clearCrossTestContaminationFor`)
      to `SqlRecorderRegistry`. Update the `register` displaced-
      recorder eviction (I3), the `getSqlRecorders` /
      `getSqlRecorderOfType` worker fallback (snapshot once, derive
      `distinctOwners.size() >= 2` trigger from the same snapshot
      — I4 / I12, mark only `instanceof PersistenceSqlRecorder`
      entries — I12 / I14), and `clear()` (I11) to keep the new set
      in sync.
   2. In `PersistenceSqlRecorder`: `startRecording`'s publish-last
      reorder already shipped in commit 3 (I13 — initialise
      `sqlRepository` before `register(this)`); this commit only
      layers the contamination-flag clear *between* the
      initialisation and the publish, i.e. `sqlRepository = ...;
      SqlRecorderRegistry.clearCrossTestContaminationFor(this);
      SqlRecorderRegistry.INSTANCE.register(this);` —
      `clearCrossTestContaminationFor` does not exist before this
      commit, so commit 3 cannot include it. `stopRecording`
      continues to call `unregister` **before** `flush` (I10) — no
      `try/finally`. **Add the package-private warning-sink seam**
      (`static volatile PrintStream WARNING_SINK = System.err;` on
      `PersistenceSqlRecorder`, used by tests in §3.5 instead of
      `System.setErr` — see §2.4 / §3.5 for the rationale).
      `findRecord` consults
      `SqlRecorderRegistry.hasCrossTestContamination(this)` inside a
      `try { ... }` block whose **`try {` opens at the very top of
      the method** (right after the `sqlRepository == null`
      null-init guard — see §2.4) so that even a throw from
      `sqlRepository.findExecutedQueries(...)` is caught (CR4 in the
      synthesis review); inside the try, swap `SqlExecutions.NONE`
      for a fresh `new SqlExecutions()` if needed (I15), call
      `executions.markCrossTestContamination()`, emit
      `WARNING_SINK.println(SqlExecutions.CROSS_TEST_CONTAMINATION_WARNING)`
      so the warning reaches passing-but-contaminated tests (I16);
      and **wrap eviction unconditionally in `finally {
      clearCrossTestContaminationFor(this); }`** (idempotent
      `Set.remove` — no `if (contaminated)` guard) so eviction is
      exception-safe even if downstream evaluation OR
      `findExecutedQueries` itself throws (I14 — primary eviction
      point); `cleanResources` calls
      `SqlRecorderRegistry.clearCrossTestContaminationFor(this)` as
      a defensive backstop only (I14 — no-op on the production
      path because `findRecord`'s finally has already evicted).
   3. Add the package-private setter
      (`markCrossTestContamination()`, with an `if (this == NONE) return;`
      guard — I15) and getter (`hasCrossTestContamination()`) on
      `SqlExecutions`. Add `if (this == NONE) return;` guards to
      `add(ExecutionInfo, List<QueryInfo>)` and the (private)
      `addWithoutCall(ExecutionInfo, List<QueryInfo>)` (I17), and
      pin the order: `sqlExecutions.addLast(...)` first, then
      `executionCount.incrementAndGet()` (I17). Add the warning
      prefix in `format(...)` and the package-private
      `CROSS_TEST_CONTAMINATION_WARNING` constant shared with
      `PersistenceSqlRecorder`'s `WARNING_SINK` emission (single
      source of truth — I16). Declare `private static final long
      serialVersionUID = 1L` on `SqlExecutions` (N5 — bounds
      branch-hop `InvalidClassException`).

   The `CrossTestContaminationWithConcurrentAsync` /
   `CrossTestContaminationWithConcurrentRandomPort` classes are
   `@QuickPerfTest`-annotated **inner classes** orchestrated from
   `SpringBootThreadingScenariosJunit5Test#concurrent_tests_with_async`
   / `concurrent_tests_with_random_port` (which run them via
   `runInParallel(...)` and inspect the JUnit Platform
   `TestExecutionSummary`). On `master`, those wrappers currently
   assert `summary.getTestsFailedCount().isZero()`; commit 3
   already flipped that to `.isOne()` (see commit 3 prose). **This
   commit adds the warning-text `.contains(...)` checks** on top of
   the now-failing inner test, completing the contract that the
   failure message carries the `CROSS_TEST_CONTAMINATION_WARNING`
   prefix from §2.6:

   ```java
   // SpringBootThreadingScenariosJunit5Test.concurrent_tests_with_async
   //   (and .concurrent_tests_with_random_port — same shape)
   assertThat(summary.getTestsFailedCount()).isOne();   // already added in commit 3
   String message = summary.getFailures().get(0)
                           .getException().getMessage();
   assertThat(message)                                  // NEW in commit 4
       .contains("WARNING: SQL was recorded from a worker thread")
       .contains("@HeapSize")             // recommended remediation
       .contains("force a dedicated JVM");// recommended remediation
   ```

   The two-step split (count flip in commit 3, text check in commit
   4) makes each commit independently bisectable: at commit 3 the
   wrapper goes from `.isZero()` to `.isOne()` reflecting the new
   active-set-fallback semantics (no warning text yet — the
   constant doesn't exist yet); at commit 4 the warning constant
   lands together with the `.contains(...)` check that consumes it.
   Folding both into commit 4 would leave commit 3 red on
   `SpringBootThreadingScenariosJunit5Test` (sub-tree fails because
   the inner test now fails its `@ExpectSelect(0)` but the wrapper
   still asserts `.isZero()`), breaking bisection.

   *Verify:*
   ```
   # End-to-end warning-text regression guards — must run via the
   # wrappers (the inner classes are @QuickPerfTest classes whose
   # failures are observed by SpringBootThreadingScenariosJunit5Test;
   # running them directly would surface the failure as an actual
   # test failure rather than the asserted contract):
   mvn -pl spring/junit5-spring-boot-3-test -am test \
       -Dtest=SpringBootThreadingScenariosJunit5Test#concurrent_tests_with_async+concurrent_tests_with_random_port

   # Full module suite — last gate before opening the PR:
   mvn -pl sql/sql-annotations -am test
   ```

Each step compiles and tests green on its own — useful for bisection.

After all four commits land, run a final repo-wide build before the
PR opens:
```
mvn clean install
```
This is the same command CI runs and the only one that exercises every
module simultaneously.
