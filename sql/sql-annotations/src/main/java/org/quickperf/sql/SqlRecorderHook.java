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

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * JVM-global lifecycle hook holding the currently-active {@link SqlRecorder}s.
 *
 * <p>Reactor schedulers move R2DBC query execution off the test thread, so the
 * {@link InheritableThreadLocal}-based {@link SqlRecorderRegistry} is unreliable
 * for reactive recording. This hook is JVM-global and survives Reactor scheduler
 * hops; the R2DBC listener reads from it during {@code afterQuery} callbacks.
 *
 * <p>For pure JDBC users, no R2DBC listener subscribes to the hook; the only
 * cost is two {@link CopyOnWriteArraySet#add(Object)}/{@link CopyOnWriteArraySet#remove(Object)}
 * calls per test (microsecond cost).
 *
 * <p><b>Threading note.</b> This hook protects only the active-recorder set with
 * {@link CopyOnWriteArraySet}. The recorders themselves are not made thread-safe
 * by being registered here — reactive listeners that may dispatch from multiple
 * Reactor threads concurrently must serialize per-recorder dispatch (e.g.
 * {@code synchronized (recorder) {}}).
 */
public final class SqlRecorderHook {

    private static final Set<SqlRecorder<?>> ACTIVE = new CopyOnWriteArraySet<>();

    private SqlRecorderHook() {}

    public static void register(SqlRecorder<?> recorder) {
        ACTIVE.add(recorder);
    }

    public static void unregister(SqlRecorder<?> recorder) {
        ACTIVE.remove(recorder);
    }

    public static Set<SqlRecorder<?>> getActiveRecorders() {
        return Collections.unmodifiableSet(ACTIVE);
    }

}
