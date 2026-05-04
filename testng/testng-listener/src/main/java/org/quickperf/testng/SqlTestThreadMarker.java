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
package org.quickperf.testng;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reflection-based bridge so the TestNG listener
 * ({@link QuickPerfTestNGListener}) can claim the current Surefire pool
 * thread as a "test thread" in {@code SqlRecorderRegistry} and
 * {@code ConnectionListenerRegistry} BEFORE any {@code @BeforeClass} /
 * {@code @BeforeMethod} method runs (e.g. Spring {@code ApplicationContext}
 * startup, Hibernate {@code EntityManagerFactory} creation, schema
 * generation, {@code @Sql} fixture loading).
 * <p>
 * Without this marker, SQL emitted before the registry's per-thread
 * recorder is installed would fall through the registry's worker
 * fallback path and broadcast to every recorder currently live in the
 * static {@code ACTIVE_RECORDERS} map, contaminating sibling tests
 * running concurrently under TestNG {@code parallel=methods} or
 * Surefire {@code parallel=all} mode.
 * <p>
 * Reflection is used so this {@code testng-listener} module does not
 * gain a hard dependency on {@code sql-annotations}: the listener is
 * also used for JVM-only tests where SQL classes are absent. When the
 * SQL classes are not on the classpath the marker call is a no-op.
 */
final class SqlTestThreadMarker {

    private static final Method SQL_REGISTRY_MARK_TEST_THREAD_METHOD;

    private static final Object SQL_REGISTRY_INSTANCE;

    private static final Method CONNECTION_LISTENER_REGISTRY_MARK_TEST_THREAD_METHOD;

    private static final Object CONNECTION_LISTENER_REGISTRY_INSTANCE;

    static {
        Method sqlMethod = null;
        Object sqlInstance = null;
        try {
            Class<?> registryClass = Class.forName("org.quickperf.sql.SqlRecorderRegistry");
            Field instanceField = registryClass.getField("INSTANCE");
            sqlInstance = instanceField.get(null);
            sqlMethod = registryClass.getMethod("markTestThread");
        } catch (Throwable ignored) {
            // sql-annotations is not on the classpath - no-op.
        }
        SQL_REGISTRY_MARK_TEST_THREAD_METHOD = sqlMethod;
        SQL_REGISTRY_INSTANCE = sqlInstance;

        Method listenerMethod = null;
        Object listenerInstance = null;
        try {
            Class<?> listenerRegistryClass = Class.forName("org.quickperf.sql.connection.ConnectionListenerRegistry");
            Field instanceField = listenerRegistryClass.getField("INSTANCE");
            listenerInstance = instanceField.get(null);
            listenerMethod = listenerRegistryClass.getMethod("markTestThread");
        } catch (Throwable ignored) {
            // sql-annotations is not on the classpath - no-op.
        }
        CONNECTION_LISTENER_REGISTRY_MARK_TEST_THREAD_METHOD = listenerMethod;
        CONNECTION_LISTENER_REGISTRY_INSTANCE = listenerInstance;
    }

    private SqlTestThreadMarker() {
    }

    /**
     * Marks the current thread as a QuickPerf test thread in both the
     * SQL recorder registry and the connection listener registry.
     * Best-effort: failures never propagate.
     */
    static void markCurrentThreadAsSqlTestThread() {
        if (SQL_REGISTRY_MARK_TEST_THREAD_METHOD != null) {
            try {
                SQL_REGISTRY_MARK_TEST_THREAD_METHOD.invoke(SQL_REGISTRY_INSTANCE);
            } catch (Throwable ignored) {
                // best-effort: marker installation must never fail a test
            }
        }
        if (CONNECTION_LISTENER_REGISTRY_MARK_TEST_THREAD_METHOD != null) {
            try {
                CONNECTION_LISTENER_REGISTRY_MARK_TEST_THREAD_METHOD.invoke(CONNECTION_LISTENER_REGISTRY_INSTANCE);
            } catch (Throwable ignored) {
                // best-effort: marker installation must never fail a test
            }
        }
    }

}
