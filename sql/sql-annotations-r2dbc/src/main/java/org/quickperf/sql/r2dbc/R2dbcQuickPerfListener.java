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
package org.quickperf.sql.r2dbc;

import io.r2dbc.proxy.core.QueryExecutionInfo;
import io.r2dbc.proxy.listener.ProxyExecutionListener;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import org.quickperf.sql.SqlRecorder;
import org.quickperf.sql.SqlRecorderHook;

import java.util.List;
import java.util.function.Supplier;

/**
 * R2DBC proxy listener that bridges r2dbc-proxy's {@link ProxyExecutionListener}
 * callbacks into QuickPerf's JDBC-shaped {@link SqlRecorder} stack via
 * {@link R2dbcExecutionAdapter}.
 *
 * <p>The listener resolves active recorders through a configurable
 * {@link Supplier} so unit tests can inject a private set instead of touching
 * the JVM-global {@link SqlRecorderHook} (Surefire runs with
 * {@code parallel=all,threadCount=5}).
 *
 * <p>Each {@code afterQuery} dispatch synchronizes on the recorder, because
 * recorders such as {@link org.quickperf.sql.PersistenceSqlRecorder} accumulate
 * executions into a non-thread-safe {@code ArrayDeque}-backed
 * {@link org.quickperf.sql.SqlExecutions}, and Reactor schedulers may invoke
 * the listener from any worker thread.
 */
public final class R2dbcQuickPerfListener implements ProxyExecutionListener {

    private final String beanName;
    private final Supplier<Iterable<SqlRecorder<?>>> recordersSupplier;
    private final int listenerId;

    /**
     * Construct a listener attached to the JVM-global {@link SqlRecorderHook}.
     *
     * @param beanName the Spring bean name of the proxied {@code ConnectionFactory},
     *                 used to populate {@link ExecutionInfo#setDataSourceName(String)}
     *                 as {@code "r2dbc:" + beanName}.
     */
    public R2dbcQuickPerfListener(String beanName) {
        this(beanName, new Supplier<Iterable<SqlRecorder<?>>>() {
            @Override
            public Iterable<SqlRecorder<?>> get() {
                return SqlRecorderHook.getActiveRecorders();
            }
        });
    }

    /**
     * Construct a listener with a custom recorder source. Intended for unit tests
     * that need to keep registration scoped per test under {@code parallel=all}.
     */
    public R2dbcQuickPerfListener(String beanName,
                                  Supplier<Iterable<SqlRecorder<?>>> recordersSupplier) {
        if (recordersSupplier == null) {
            throw new IllegalArgumentException("recordersSupplier must not be null");
        }
        this.beanName = beanName;
        this.recordersSupplier = recordersSupplier;
        this.listenerId = System.identityHashCode(this);
    }

    @Override
    public void afterQuery(QueryExecutionInfo info) {
        if (info == null) {
            return;
        }
        Iterable<SqlRecorder<?>> recorders = recordersSupplier.get();
        if (recorders == null) {
            return;
        }
        boolean anyActive = false;
        for (@SuppressWarnings("unused") SqlRecorder<?> r : recorders) {
            anyActive = true;
            break;
        }
        if (!anyActive) {
            return;
        }

        R2dbcExecutionAdapter.Adapted adapted = R2dbcExecutionAdapter.adapt(info, beanName);
        ExecutionInfo execInfo = adapted.executionInfo;
        List<QueryInfo> queries = adapted.queries;

        for (SqlRecorder<?> recorder : recorders) {
            // Reactor schedulers may dispatch this listener from any worker thread.
            // Recorders accumulate into non-thread-safe ArrayDeque-backed structures,
            // so per-recorder serialization is required.
            synchronized (recorder) {
                recorder.addQueryExecution(execInfo, queries, listenerId);
            }
        }
    }

}
