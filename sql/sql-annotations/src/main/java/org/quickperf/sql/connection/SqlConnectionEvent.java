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

/**
 * Immutable value object describing a connection lifecycle event observed by
 * QuickPerf for either JDBC or R2DBC.
 *
 * <p>The {@link #getConnectionId() connection id} is opaque (a non-null
 * {@link String}) and is stable across all events emitted for the same
 * underlying connection. JDBC connections build it from
 * {@link System#identityHashCode(Object)} of the JDBC wrapper; R2DBC
 * connections build it from {@link System#identityHashCode(Object)} of the
 * reactive {@code Connection} instance, cached in the proxy's
 * {@code ConnectionInfo} value store.
 *
 * <p>The {@link #getStackTraceMarker() stack-trace marker} is captured at
 * acquisition time when the {@code quickperf.sql.r2dbc.diagnostics} system
 * property is enabled; it is otherwise {@code null} to avoid the cost of
 * stack capture on every connection event.
 */
public final class SqlConnectionEvent {

    /**
     * Origin of the connection lifecycle event.
     */
    public enum Source {
        JDBC,
        R2DBC
    }

    private final String connectionId;

    private final Source source;

    private final Throwable stackTraceMarker;

    private final long timestampMillis;

    private final long acquisitionDurationNanos;

    private SqlConnectionEvent(String connectionId,
                               Source source,
                               Throwable stackTraceMarker,
                               long timestampMillis,
                               long acquisitionDurationNanos) {
        if (connectionId == null) {
            throw new IllegalArgumentException("connectionId must not be null");
        }
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        this.connectionId = connectionId;
        this.source = source;
        this.stackTraceMarker = stackTraceMarker;
        this.timestampMillis = timestampMillis;
        this.acquisitionDurationNanos = acquisitionDurationNanos;
    }

    /** Builds a JDBC event without stack-trace capture. */
    public static SqlConnectionEvent jdbc(String connectionId) {
        return new SqlConnectionEvent(connectionId, Source.JDBC, null, System.currentTimeMillis(), 0L);
    }

    /** Builds a JDBC event with the supplied stack-trace marker. */
    public static SqlConnectionEvent jdbc(String connectionId, Throwable stackTraceMarker) {
        return new SqlConnectionEvent(connectionId, Source.JDBC, stackTraceMarker, System.currentTimeMillis(), 0L);
    }

    /** Builds an R2DBC event without stack-trace capture. */
    public static SqlConnectionEvent r2dbc(String connectionId) {
        return new SqlConnectionEvent(connectionId, Source.R2DBC, null, System.currentTimeMillis(), 0L);
    }

    /** Builds an R2DBC event with the supplied stack-trace marker. */
    public static SqlConnectionEvent r2dbc(String connectionId, Throwable stackTraceMarker) {
        return new SqlConnectionEvent(connectionId, Source.R2DBC, stackTraceMarker, System.currentTimeMillis(), 0L);
    }

    /**
     * Builds an R2DBC connection-acquired event annotated with the duration
     * of the underlying {@code ConnectionFactory.create()} reactive call,
     * measured in nanoseconds. Used by the R2DBC connection-lifecycle listener
     * to surface acquisition timing through {@code @ProfileConnection}.
     *
     * @param connectionId opaque connection id assigned by the R2DBC bridge.
     * @param acquisitionDurationNanos elapsed time of {@code create()} in
     *                                 nanoseconds; values {@code <= 0} are
     *                                 treated as unknown/unavailable.
     */
    public static SqlConnectionEvent r2dbcAcquired(String connectionId, long acquisitionDurationNanos) {
        return new SqlConnectionEvent(connectionId, Source.R2DBC, null, System.currentTimeMillis(), acquisitionDurationNanos);
    }

    public String getConnectionId() {
        return connectionId;
    }

    public Source getSource() {
        return source;
    }

    /** May be {@code null} when stack-trace capture is disabled. */
    public Throwable getStackTraceMarker() {
        return stackTraceMarker;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    /**
     * @return the elapsed time of the connection acquisition in nanoseconds,
     *         or {@code 0} when not measured (every event other than the
     *         R2DBC connection-acquired event).
     */
    public long getAcquisitionDurationNanos() {
        return acquisitionDurationNanos;
    }

    @Override
    public String toString() {
        return "SqlConnectionEvent{"
                + "connectionId='" + connectionId + '\''
                + ", source=" + source
                + ", timestampMillis=" + timestampMillis
                + ", acquisitionDurationNanos=" + acquisitionDurationNanos
                + '}';
    }

}
