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

import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-thread carrier between {@link QuickPerfMonitoringResult} and
 * {@link R2dbcExecutionAdapter} for the column count of an R2DBC query
 * execution.
 *
 * <p>r2dbc-proxy emits {@link io.r2dbc.proxy.listener.ProxyExecutionListener#afterQuery(QueryExecutionInfo)}
 * synchronously after the user's reactive subscription drains the {@code Result}
 * publisher. Column metadata is observed during the user's row mapping
 * callbacks (which may run on a different Reactor scheduler thread). This
 * store serves as the bridge: the decorator records the count once per
 * execution; {@link R2dbcExecutionAdapter} drains it.
 *
 * <p>The keying scheme combines {@code connectionId} with
 * {@link System#identityHashCode(Object)} of the {@link QueryExecutionInfo}
 * so two concurrent executions on the same connection (different
 * {@code QueryExecutionInfo} instances) keep separate counts.
 */
final class ColumnCountStore {

    private static final ConcurrentHashMap<String, long[]> COUNTS = new ConcurrentHashMap<>();

    static {
        // Best-effort cleanup on JVM exit; in practice every entry is drained
        // synchronously inside R2dbcExecutionAdapter.adapt(...) so the map is
        // empty between tests.
        Runtime.getRuntime().addShutdownHook(new Thread("quickperf-r2dbc-column-count-cleanup") {
            @Override
            public void run() {
                COUNTS.clear();
            }
        });
    }

    private ColumnCountStore() {}

    /**
     * Record {@code count} for {@code (connectionId, qei)} if no count has been
     * recorded yet. First-writer wins: subsequent calls for the same
     * {@code QueryExecutionInfo} are ignored. This matches the JDBC
     * {@link org.quickperf.sql.SqlExecution#getColumnCount()} semantic of
     * returning the column count of the first {@code ResultSet} when a single
     * statement execution emits multiple results.
     */
    static void recordOnce(String connectionId, QueryExecutionInfo qei, long count) {
        if (qei == null) {
            return;
        }
        COUNTS.putIfAbsent(key(connectionId, qei), new long[]{count});
    }

    /**
     * Remove and return the column count previously recorded for
     * {@code (connectionId, qei)}. Returns {@code 0} when no count has been
     * recorded — for example for {@code getRowsUpdated()} executions which
     * never carry row metadata.
     */
    static long drain(String connectionId, QueryExecutionInfo qei) {
        if (qei == null) {
            return 0L;
        }
        long[] removed = COUNTS.remove(key(connectionId, qei));
        return removed == null ? 0L : removed[0];
    }

    /** Visible for tests: clear all entries. */
    static void clear() {
        COUNTS.clear();
    }

    /** Visible for tests: report current size. */
    static int size() {
        return COUNTS.size();
    }

    private static String key(String connectionId, QueryExecutionInfo qei) {
        return (connectionId == null ? "" : connectionId) + "#" + System.identityHashCode(qei);
    }
}
