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
 * Bridges R2DBC connection-lifecycle events fired against
 * {@link ConnectionListenerHook} into the shared {@link ConnectionProfiler}
 * used by {@link ConnectionEventsProfiler} so that {@code @ProfileConnection}
 * produces the same profiling output for reactive connections as for JDBC
 * connections.
 *
 * <p>Only events whose {@link SqlConnectionEvent#getSource() source} is
 * {@link SqlConnectionEvent.Source#R2DBC} are surfaced; JDBC events are
 * already handled by the legacy {@link ConnectionEventsProfiler} path
 * registered with {@link ConnectionListenerRegistry}, so filtering here
 * prevents double output when both paths are active.
 */
public class R2dbcConnectionEventsProfilerListener implements SqlConnectionListener {

    private static final String CONNECTION_FACTORY = "io.r2dbc.spi.ConnectionFactory";
    private static final String CONNECTION = "io.r2dbc.spi.Connection";

    private final ConnectionProfiler profiler;

    public R2dbcConnectionEventsProfilerListener(ConnectionProfiler profiler) {
        this.profiler = profiler;
    }

    private static String describe(SqlConnectionEvent event) {
        return "connection " + event.getConnectionId();
    }

    private static boolean isR2dbc(SqlConnectionEvent event) {
        return event.getSource() == SqlConnectionEvent.Source.R2DBC;
    }

    @Override
    public void onConnectionAcquired(SqlConnectionEvent event) {
        if (isR2dbc(event)) {
            profiler.profile(describe(event), CONNECTION_FACTORY + ".create()");
        }
    }

    @Override
    public void onConnectionReleased(SqlConnectionEvent event) {
        if (isR2dbc(event)) {
            profiler.profile(describe(event), CONNECTION + ".close()");
        }
    }

    @Override
    public void onTransactionBegan(SqlConnectionEvent event) {
        if (isR2dbc(event)) {
            profiler.profile(describe(event), CONNECTION + ".beginTransaction()");
        }
    }

    @Override
    public void onTransactionCommitted(SqlConnectionEvent event) {
        if (isR2dbc(event)) {
            profiler.profile(describe(event), CONNECTION + ".commitTransaction()");
        }
    }

    @Override
    public void onTransactionRolledBack(SqlConnectionEvent event) {
        if (isR2dbc(event)) {
            profiler.profile(describe(event), CONNECTION + ".rollbackTransaction()");
        }
    }

    @Override
    public void onAutoCommitChanged(SqlConnectionEvent event, boolean autoCommit) {
        if (isR2dbc(event)) {
            profiler.profile(describe(event),
                    CONNECTION + ".setAutoCommit(boolean autoCommit) [autoCommit: " + autoCommit + "]");
        }
    }

    @Override
    public void onIsolationLevelChanged(SqlConnectionEvent event, String isolationLevel) {
        if (isR2dbc(event)) {
            profiler.profile(describe(event),
                    CONNECTION + ".setTransactionIsolationLevel(IsolationLevel level) [level: "
                            + isolationLevel + "]");
        }
    }

    @Override
    public void onSavepointCreated(SqlConnectionEvent event, String savepointName) {
        if (isR2dbc(event)) {
            profiler.profile(describe(event),
                    CONNECTION + ".createSavepoint(String name) [name: " + savepointName + "]");
        }
    }

    @Override
    public void onSavepointReleased(SqlConnectionEvent event, String savepointName) {
        if (isR2dbc(event)) {
            profiler.profile(describe(event),
                    CONNECTION + ".releaseSavepoint(String name) [name: " + savepointName + "]");
        }
    }

    @Override
    public void onSavepointRolledBack(SqlConnectionEvent event, String savepointName) {
        if (isR2dbc(event)) {
            profiler.profile(describe(event),
                    CONNECTION + ".rollbackTransactionToSavepoint(String name) [name: "
                            + savepointName + "]");
        }
    }

}
