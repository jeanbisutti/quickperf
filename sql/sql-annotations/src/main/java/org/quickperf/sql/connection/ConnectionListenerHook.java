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

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * JVM-global lifecycle hook holding the currently-active
 * {@link SqlConnectionListener}s.
 *
 * <p>Reactor schedulers move R2DBC connection acquisition off the test thread,
 * so the {@link InheritableThreadLocal}-based {@link ConnectionListenerRegistry}
 * is unreliable for reactive recording (the InheritableThreadLocal value is
 * captured at thread <em>creation</em> time, so listeners registered after the
 * Reactor scheduler thread first started are invisible from that thread).
 *
 * <p>This hook is JVM-global and survives Reactor scheduler hops; the R2DBC
 * connection-lifecycle listener reads from it during reactive {@code
 * beforeMethod}/{@code afterMethod} callbacks.
 *
 * <p>For pure JDBC users, the new code-path consults this hook in addition to
 * the legacy {@link ConnectionListenerRegistry}; the only cost is two
 * {@link CopyOnWriteArraySet#add(Object)}/{@link CopyOnWriteArraySet#remove(Object)}
 * calls per test (microsecond cost).
 *
 * <p><b>Threading note.</b> This hook protects only the active-listener set
 * with {@link CopyOnWriteArraySet}. The listeners themselves are not made
 * thread-safe by being registered here — listeners that may be invoked from
 * multiple Reactor threads concurrently must serialize per-listener dispatch
 * (e.g. {@code synchronized (listener) {}}).
 */
public final class ConnectionListenerHook {

    private static final Set<SqlConnectionListener> ACTIVE = new CopyOnWriteArraySet<>();

    private ConnectionListenerHook() {
    }

    public static void register(SqlConnectionListener listener) {
        ACTIVE.add(listener);
    }

    public static void unregister(SqlConnectionListener listener) {
        ACTIVE.remove(listener);
    }

    public static Set<SqlConnectionListener> getActiveListeners() {
        return Collections.unmodifiableSet(ACTIVE);
    }

}
