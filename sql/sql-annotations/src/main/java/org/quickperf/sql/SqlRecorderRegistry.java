/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * Copyright 2019-2022 the original author or authors.
 */
package org.quickperf.sql;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.quickperf.SystemProperties.TEST_CODE_EXECUTING_IN_NEW_JVM;

/*
 * Dual-map registry rationale
 * ---------------------------
 * QuickPerf historically attributed a SQL execution to a test via
 * InheritableThreadLocal: the test thread registered a recorder and
 * child threads inherited it. That model breaks in practice because
 * the SQL of a realistic Spring / Jakarta / Quarkus / Micronaut app is
 * almost never executed on a *child* of the test thread - it runs on
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
 *      we fall back to this map and the SQL is still attributed to a
 *      live recorder. The map's value is the registering test thread's
 *      id (the "owner tid"); it is the contamination-detection axis
 *      that arrives with the warning flag in a follow-up commit.
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
 */
public class SqlRecorderRegistry {

    public static final SqlRecorderRegistry INSTANCE = new SqlRecorderRegistry();

    // Forked-JVM branch: thread-safe so workers in the forked JVM can read it
    // safely while the test thread mutates it during register / unregister.
    private final ConcurrentMap<Class<? extends SqlRecorder>, SqlRecorder> sqlRecorderByTypeOfTestJvm
            = new ConcurrentHashMap<Class<? extends SqlRecorder>, SqlRecorder>();

    // Single-JVM branch: per-thread fast-path keyed by Thread.getId().
    private static final ConcurrentMap<Long,
                                       ConcurrentMap<Class<? extends SqlRecorder>, SqlRecorder>>
            PER_THREAD_RECORDERS = new ConcurrentHashMap<Long,
                                                        ConcurrentMap<Class<? extends SqlRecorder>, SqlRecorder>>();

    // Process-wide: every live recorder mapped to its owner test thread id.
    // Used by worker-thread fallback paths when PER_THREAD_RECORDERS has no
    // entry for the calling thread.
    private static final ConcurrentMap<SqlRecorder, Long> ACTIVE_RECORDERS
            = new ConcurrentHashMap<SqlRecorder, Long>();

    private SqlRecorderRegistry() {}

    public void register(SqlRecorder sqlRecorder) {
        Long tid = Long.valueOf(Thread.currentThread().getId());
        if (TEST_CODE_EXECUTING_IN_NEW_JVM.evaluate()) {
            sqlRecorderByTypeOfTestJvm.put(sqlRecorder.getClass(), sqlRecorder);
            ACTIVE_RECORDERS.put(sqlRecorder, tid);
            return;
        }
        ConcurrentMap<Class<? extends SqlRecorder>, SqlRecorder> perThread = PER_THREAD_RECORDERS.get(tid);
        if (perThread == null) {
            ConcurrentMap<Class<? extends SqlRecorder>, SqlRecorder> fresh
                    = new ConcurrentHashMap<Class<? extends SqlRecorder>, SqlRecorder>();
            ConcurrentMap<Class<? extends SqlRecorder>, SqlRecorder> previous
                    = PER_THREAD_RECORDERS.putIfAbsent(tid, fresh);
            perThread = (previous != null) ? previous : fresh;
        }
        // Pinned compound-write order (I1): per-thread map first, ACTIVE_RECORDERS last.
        SqlRecorder displaced = perThread.put(sqlRecorder.getClass(), sqlRecorder);
        if (displaced != null && displaced != sqlRecorder) {
            // Avoid leaking the displaced recorder in the active set (I3).
            ACTIVE_RECORDERS.remove(displaced);
        }
        ACTIVE_RECORDERS.put(sqlRecorder, tid);
    }

    public static void unregister(SqlRecorder sqlRecorder) {
        // Pinned compound-write order (I2): ACTIVE_RECORDERS first, per-thread map last.
        Long owner = ACTIVE_RECORDERS.get(sqlRecorder);
        if (owner != null) {
            ACTIVE_RECORDERS.remove(sqlRecorder, owner);
        }
        if (TEST_CODE_EXECUTING_IN_NEW_JVM.evaluate()) {
            // Forked-JVM unregister: was previously a no-op; required for I10
            // (unregister-first ordering) to actually unpublish the recorder
            // before flush() runs in forked mode.
            INSTANCE.sqlRecorderByTypeOfTestJvm.remove(sqlRecorder.getClass(), sqlRecorder);
            return;
        }
        Long tid = Long.valueOf(Thread.currentThread().getId());
        ConcurrentMap<Class<? extends SqlRecorder>, SqlRecorder> perThread = PER_THREAD_RECORDERS.get(tid);
        if (perThread != null) {
            perThread.remove(sqlRecorder.getClass(), sqlRecorder);
            if (perThread.isEmpty()) {
                PER_THREAD_RECORDERS.remove(tid, perThread);
            }
        }
    }

    public Collection<SqlRecorder> getSqlRecorders() {
        if (TEST_CODE_EXECUTING_IN_NEW_JVM.evaluate()) {
            return sqlRecorderByTypeOfTestJvm.values();
        }
        Long tid = Long.valueOf(Thread.currentThread().getId());
        ConcurrentMap<Class<? extends SqlRecorder>, SqlRecorder> perThread = PER_THREAD_RECORDERS.get(tid);
        if (perThread != null) {
            // Test-thread fast path. Returning here even on an empty submap
            // (post-clear() sentinel) keeps the test thread off the worker
            // fallback so it cannot observe sibling tests' recorders under
            // Surefire parallel=all.
            return new ArrayList<SqlRecorder>(perThread.values());
        }
        // Worker-thread fallback: snapshot ACTIVE_RECORDERS (weakly-consistent)
        // and return the recorders. Cross-test contamination detection
        // arrives in a follow-up commit; for now the snapshot is the
        // attribution channel only.
        return new ArrayList<SqlRecorder>(ACTIVE_RECORDERS.keySet());
    }

    public <T extends SqlRecorder> T getSqlRecorderOfType(Class<T> type) {
        if (TEST_CODE_EXECUTING_IN_NEW_JVM.evaluate()) {
            return type.cast(sqlRecorderByTypeOfTestJvm.get(type));
        }
        Long tid = Long.valueOf(Thread.currentThread().getId());
        ConcurrentMap<Class<? extends SqlRecorder>, SqlRecorder> perThread = PER_THREAD_RECORDERS.get(tid);
        if (perThread != null) {
            // Test-thread branch. Return null on a type miss WITHOUT
            // falling through to the worker fallback - a test thread
            // that has registered something (or holds the post-clear()
            // sentinel) MUST NOT observe sibling tests' recorders.
            SqlRecorder hit = perThread.get(type);
            return hit != null ? type.cast(hit) : null;
        }
        // Worker-thread fallback: snapshot ACTIVE_RECORDERS, find the first
        // matching instance.
        for (Map.Entry<SqlRecorder, Long> entry : ACTIVE_RECORDERS.entrySet()) {
            SqlRecorder candidate = entry.getKey();
            if (type.isInstance(candidate)) {
                return type.cast(candidate);
            }
        }
        return null;
    }

    public void clear() {
        Long tid = Long.valueOf(Thread.currentThread().getId());
        if (TEST_CODE_EXECUTING_IN_NEW_JVM.evaluate()) {
            // Symmetrically clear ACTIVE_RECORDERS for forked-JVM recorders too.
            for (SqlRecorder r : new ArrayList<SqlRecorder>(sqlRecorderByTypeOfTestJvm.values())) {
                ACTIVE_RECORDERS.remove(r);
            }
            sqlRecorderByTypeOfTestJvm.clear();
            return;
        }
        ConcurrentMap<Class<? extends SqlRecorder>, SqlRecorder> previous = PER_THREAD_RECORDERS.remove(tid);
        if (previous != null) {
            for (SqlRecorder r : previous.values()) {
                ACTIVE_RECORDERS.remove(r);
            }
        }
        // Install an empty-marker submap so subsequent reads from THIS thread
        // (registry tests' "register; clear; read" pattern) still observe
        // perThread != null and DO NOT fall through to the worker-thread
        // fallback. Subsequent register(...) on this thread observes the empty
        // submap via putIfAbsent's "previous != null" branch and reuses it.
        PER_THREAD_RECORDERS.put(tid, new ConcurrentHashMap<Class<? extends SqlRecorder>, SqlRecorder>());
    }

}
