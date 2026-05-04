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
package org.quickperf.sql.connection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.quickperf.SystemProperties.TEST_CODE_EXECUTING_IN_NEW_JVM;

/*
 * Mirror of {@link org.quickperf.sql.SqlRecorderRegistry}'s dual-map design.
 * Same per-thread fast path + process-wide active-set fallback. See that
 * class's Javadoc for the full rationale.
 *
 * Difference from SqlRecorderRegistry: there is no contamination flag on
 * connection listeners in PR1. Listener cross-test attribution is a real
 * residual covered separately - see pr1-plan.md sections 2.2 and 5.
 */
public class ConnectionListenerRegistry {

    public static final ConnectionListenerRegistry INSTANCE = new ConnectionListenerRegistry();

    // Forked-JVM branch (becomes thread-safe in PR1 - read on every JDBC
    // operation; writes only at test setup / teardown).
    private final List<ConnectionListener> connectionListenersOfTestJvm
            = new CopyOnWriteArrayList<ConnectionListener>();

    // Single-JVM branch: per-thread fast-path keyed by Thread.getId().
    private static final ConcurrentMap<Long,
                                       ConcurrentMap<Class<? extends ConnectionListener>, ConnectionListener>>
            PER_THREAD_LISTENERS = new ConcurrentHashMap<Long,
                                                        ConcurrentMap<Class<? extends ConnectionListener>, ConnectionListener>>();

    // Process-wide identity set of every live listener; iterated by worker
    // threads on the JDBC hot path, written only on register / unregister.
    private static final Set<ConnectionListener> ACTIVE_LISTENERS
            = Collections.newSetFromMap(new ConcurrentHashMap<ConnectionListener, Boolean>());

    private ConnectionListenerRegistry() { }

    public void register(ConnectionListener connectionListener) {
        Long tid = Long.valueOf(Thread.currentThread().getId());
        if (TEST_CODE_EXECUTING_IN_NEW_JVM.evaluate()) {
            connectionListenersOfTestJvm.add(connectionListener);
            ACTIVE_LISTENERS.add(connectionListener);
            return;
        }
        ConcurrentMap<Class<? extends ConnectionListener>, ConnectionListener> perThread
                = PER_THREAD_LISTENERS.get(tid);
        if (perThread == null) {
            ConcurrentMap<Class<? extends ConnectionListener>, ConnectionListener> fresh
                    = new ConcurrentHashMap<Class<? extends ConnectionListener>, ConnectionListener>();
            ConcurrentMap<Class<? extends ConnectionListener>, ConnectionListener> previous
                    = PER_THREAD_LISTENERS.putIfAbsent(tid, fresh);
            perThread = (previous != null) ? previous : fresh;
        }
        // No displaced-listener eviction here: ConnectionListener has no
        // contamination flag in PR1. The previous mapping (if any) is simply
        // overwritten.
        perThread.put(connectionListener.getClass(), connectionListener);
        ACTIVE_LISTENERS.add(connectionListener);
    }

    public static void unregister(ConnectionListener connectionListener) {
        // Pinned compound-write order (I2): ACTIVE_LISTENERS first.
        ACTIVE_LISTENERS.remove(connectionListener);
        if (TEST_CODE_EXECUTING_IN_NEW_JVM.evaluate()) {
            // Forked-JVM unregister does NOT remove the listener from
            // connectionListenersOfTestJvm: in forked-JVM mode, lifecycle
            // teardown such as @After's emf.close() runs AFTER stopRecording()
            // saves the captured data, but before the JVM exits. Removing
            // the listener here would silently change runtime behaviour
            // observed in pre-existing tests.
            return;
        }
        Long tid = Long.valueOf(Thread.currentThread().getId());
        ConcurrentMap<Class<? extends ConnectionListener>, ConnectionListener> perThread
                = PER_THREAD_LISTENERS.get(tid);
        if (perThread != null) {
            perThread.remove(connectionListener.getClass(), connectionListener);
            // Intentionally NOT removing the empty submap from
            // PER_THREAD_LISTENERS: same discipline as SqlRecorderRegistry.
            // The empty submap acts as a "this is a test pool thread"
            // sentinel so the next test's @Before SQL on the same Surefire
            // pool thread takes the test-thread fast path (returning the
            // empty submap's values) instead of falling through to the
            // worker-thread broadcast fallback.
        }
    }

    public Collection<ConnectionListener> getConnectionListeners() {
        if (TEST_CODE_EXECUTING_IN_NEW_JVM.evaluate()) {
            // CopyOnWriteArrayList iterators are snapshot-based; safe to
            // return the live reference.
            return connectionListenersOfTestJvm;
        }
        Long tid = Long.valueOf(Thread.currentThread().getId());
        ConcurrentMap<Class<? extends ConnectionListener>, ConnectionListener> perThread
                = PER_THREAD_LISTENERS.get(tid);
        if (perThread != null) {
            // Test-thread fast path. Empty submap is the post-clear /
            // post-unregister sentinel - do NOT fall through to the
            // active-set fallback.
            return new ArrayList<ConnectionListener>(perThread.values());
        }
        // Worker-thread fallback: defensive copy avoids
        // ConcurrentModificationException against concurrent register /
        // unregister on the JDBC hot path.
        return new ArrayList<ConnectionListener>(ACTIVE_LISTENERS);
    }

    public <T extends ConnectionListener> T getConnectionListenerOfType(Class<T> type) {
        if (TEST_CODE_EXECUTING_IN_NEW_JVM.evaluate()) {
            for (ConnectionListener candidate : connectionListenersOfTestJvm) {
                if (type.isInstance(candidate)) {
                    return type.cast(candidate);
                }
            }
            return null;
        }
        Long tid = Long.valueOf(Thread.currentThread().getId());
        ConcurrentMap<Class<? extends ConnectionListener>, ConnectionListener> perThread
                = PER_THREAD_LISTENERS.get(tid);
        if (perThread != null) {
            // Test-thread branch. Return null on a type miss WITHOUT
            // falling through to the worker fallback.
            ConnectionListener hit = perThread.get(type);
            return hit != null ? type.cast(hit) : null;
        }
        // Worker-thread fallback.
        for (ConnectionListener candidate : ACTIVE_LISTENERS) {
            if (type.isInstance(candidate)) {
                return type.cast(candidate);
            }
        }
        return null;
    }

    public void clear() {
        Long tid = Long.valueOf(Thread.currentThread().getId());
        if (TEST_CODE_EXECUTING_IN_NEW_JVM.evaluate()) {
            for (ConnectionListener listener : connectionListenersOfTestJvm) {
                ACTIVE_LISTENERS.remove(listener);
            }
            connectionListenersOfTestJvm.clear();
            return;
        }
        ConcurrentMap<Class<? extends ConnectionListener>, ConnectionListener> previous
                = PER_THREAD_LISTENERS.remove(tid);
        if (previous != null) {
            for (ConnectionListener listener : previous.values()) {
                ACTIVE_LISTENERS.remove(listener);
            }
        }
        // Empty-marker sentinel - same discipline as SqlRecorderRegistry.
        PER_THREAD_LISTENERS.put(tid,
                new ConcurrentHashMap<Class<? extends ConnectionListener>, ConnectionListener>());
    }

    /**
     * Claim the current thread as a test pool thread by installing an empty
     * sentinel submap in {@code PER_THREAD_LISTENERS} (if absent). Mirror of
     * {@link org.quickperf.sql.SqlRecorderRegistry#markTestThread()} - see
     * that class for the full rationale. Test runners call this <em>before</em>
     * any {@code @Before}/{@code @BeforeEach} method runs to prevent a
     * @Before that emits SQL from broadcasting through the worker fallback
     * to every connection listener currently live in the static
     * {@code ACTIVE_LISTENERS} set.
     */
    public void markTestThread() {
        if (TEST_CODE_EXECUTING_IN_NEW_JVM.evaluate()) {
            return;
        }
        Long tid = Long.valueOf(Thread.currentThread().getId());
        if (PER_THREAD_LISTENERS.get(tid) != null) {
            return;
        }
        PER_THREAD_LISTENERS.putIfAbsent(tid,
                new ConcurrentHashMap<Class<? extends ConnectionListener>, ConnectionListener>());
    }

}
