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

import org.junit.Before;
import org.junit.Test;
import org.quickperf.sql.connection.stack.StackTraceDisplayConfig;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link R2dbcConnectionEventsProfilerListener} translates each
 * neutral connection event into a profiler entry that mirrors the JDBC
 * profiler output format and is gated by the same enabled flag.
 */
public class R2dbcConnectionEventsProfilerListenerTest {

    private StringWriter sink;
    private ConnectionProfiler profiler;
    private R2dbcConnectionEventsProfilerListener listener;

    @Before
    public void setUp() {
        sink = new StringWriter();
        StackTraceDisplayConfig stackTraceConfig = StackTraceDisplayConfig.noStackTrace();
        profiler = new ConnectionProfiler(stackTraceConfig, new PrintWriter(sink));
        profiler.enables();
        listener = new R2dbcConnectionEventsProfilerListener(profiler);
    }

    @Test public void
    onConnectionAcquired_emits_create_event_for_r2dbc_event() {
        listener.onConnectionAcquired(SqlConnectionEvent.r2dbc("r2dbc-1"));

        assertThat(sink.toString())
                .contains("connection r2dbc-1")
                .contains("io.r2dbc.spi.ConnectionFactory.create()");
    }

    @Test public void
    onConnectionReleased_emits_close_event_for_r2dbc_event() {
        listener.onConnectionReleased(SqlConnectionEvent.r2dbc("r2dbc-2"));

        assertThat(sink.toString())
                .contains("connection r2dbc-2")
                .contains("io.r2dbc.spi.Connection.close()");
    }

    @Test public void
    onTransactionBegan_emits_beginTransaction_event() {
        listener.onTransactionBegan(SqlConnectionEvent.r2dbc("r2dbc-3"));

        assertThat(sink.toString()).contains("io.r2dbc.spi.Connection.beginTransaction()");
    }

    @Test public void
    onTransactionCommitted_emits_commitTransaction_event() {
        listener.onTransactionCommitted(SqlConnectionEvent.r2dbc("r2dbc-4"));

        assertThat(sink.toString()).contains("io.r2dbc.spi.Connection.commitTransaction()");
    }

    @Test public void
    onTransactionRolledBack_emits_rollbackTransaction_event() {
        listener.onTransactionRolledBack(SqlConnectionEvent.r2dbc("r2dbc-5"));

        assertThat(sink.toString()).contains("io.r2dbc.spi.Connection.rollbackTransaction()");
    }

    @Test public void
    onAutoCommitChanged_includes_value_in_description() {
        listener.onAutoCommitChanged(SqlConnectionEvent.r2dbc("r2dbc-6"), false);

        assertThat(sink.toString())
                .contains("io.r2dbc.spi.Connection.setAutoCommit(boolean autoCommit)")
                .contains("autoCommit: false");
    }

    @Test public void
    onIsolationLevelChanged_includes_level_in_description() {
        listener.onIsolationLevelChanged(SqlConnectionEvent.r2dbc("r2dbc-7"), "READ_COMMITTED");

        assertThat(sink.toString())
                .contains("setTransactionIsolationLevel(IsolationLevel level)")
                .contains("level: READ_COMMITTED");
    }

    @Test public void
    onSavepointCreated_includes_name_in_description() {
        listener.onSavepointCreated(SqlConnectionEvent.r2dbc("r2dbc-8"), "sp1");

        assertThat(sink.toString())
                .contains("createSavepoint(String name)")
                .contains("name: sp1");
    }

    @Test public void
    onSavepointReleased_includes_name_in_description() {
        listener.onSavepointReleased(SqlConnectionEvent.r2dbc("r2dbc-9"), "sp2");

        assertThat(sink.toString())
                .contains("releaseSavepoint(String name)")
                .contains("name: sp2");
    }

    @Test public void
    onSavepointRolledBack_includes_name_in_description() {
        listener.onSavepointRolledBack(SqlConnectionEvent.r2dbc("r2dbc-10"), "sp3");

        assertThat(sink.toString())
                .contains("rollbackTransactionToSavepoint(String name)")
                .contains("name: sp3");
    }

    @Test public void
    jdbc_events_are_ignored_to_avoid_duplicate_output() {
        listener.onConnectionAcquired(SqlConnectionEvent.jdbc("jdbc-77"));
        listener.onConnectionReleased(SqlConnectionEvent.jdbc("jdbc-77"));
        listener.onAutoCommitChanged(SqlConnectionEvent.jdbc("jdbc-77"), true);

        assertThat(sink.toString()).isEmpty();
    }

    @Test public void
    events_are_suppressed_when_profiler_is_disabled() {
        profiler.disables();

        listener.onConnectionAcquired(SqlConnectionEvent.r2dbc("r2dbc-disabled"));

        assertThat(sink.toString()).isEmpty();
    }

}
