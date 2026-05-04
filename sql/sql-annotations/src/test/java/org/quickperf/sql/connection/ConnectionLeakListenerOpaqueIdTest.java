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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.quickperf.TestExecutionContext;
import org.quickperf.measure.BooleanMeasure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the opaque-id tracking implementation of {@link ConnectionLeakListener}:
 * neutral events from both JDBC and R2DBC drive the same internal set, the
 * counter is consistent across mixed sources, and the {@link BooleanMeasure}
 * captured by {@link ConnectionLeakListener#stopRecording(TestExecutionContext)}
 * reflects whether any acquired-but-not-released ids remained.
 */
public class ConnectionLeakListenerOpaqueIdTest {

    private final ConnectionLeakListener listener = new ConnectionLeakListener();

    private TestExecutionContext singleJvmContext() {
        TestExecutionContext context = mock(TestExecutionContext.class);
        when(context.testExecutionUsesTwoJVMs()).thenReturn(false);
        return context;
    }

    @Before
    public void setUp() {
        listener.startRecording(singleJvmContext());
    }

    @After
    public void tearDown() {
        // Ensure the listener is unregistered even when assertions fail mid-test.
        ConnectionListenerHook.unregister(listener);
    }

    @Test public void
    register_with_connection_listener_hook_on_start_recording() {
        assertThat(ConnectionListenerHook.getActiveListeners()).contains(listener);
    }

    @Test public void
    onConnectionAcquired_jdbc_event_increments_counter() {
        listener.onConnectionAcquired(SqlConnectionEvent.jdbc("jdbc-1"));

        assertThat(listener.countLeakedConnections()).isEqualTo(1);
    }

    @Test public void
    onConnectionReleased_jdbc_event_decrements_counter() {
        listener.onConnectionAcquired(SqlConnectionEvent.jdbc("jdbc-2"));
        listener.onConnectionReleased(SqlConnectionEvent.jdbc("jdbc-2"));

        assertThat(listener.countLeakedConnections()).isZero();
    }

    @Test public void
    onConnectionAcquired_is_idempotent_for_repeated_ids() {
        SqlConnectionEvent event = SqlConnectionEvent.jdbc("jdbc-3");
        listener.onConnectionAcquired(event);
        listener.onConnectionAcquired(event);

        assertThat(listener.countLeakedConnections()).isEqualTo(1);
    }

    @Test public void
    onConnectionReleased_is_safe_for_unknown_ids() {
        listener.onConnectionReleased(SqlConnectionEvent.jdbc("never-acquired"));

        assertThat(listener.countLeakedConnections()).isZero();
    }

    @Test public void
    counter_tracks_jdbc_and_r2dbc_ids_independently() {
        listener.onConnectionAcquired(SqlConnectionEvent.jdbc("jdbc-4"));
        listener.onConnectionAcquired(SqlConnectionEvent.r2dbc("r2dbc-1"));
        listener.onConnectionAcquired(SqlConnectionEvent.r2dbc("r2dbc-2"));

        assertThat(listener.countLeakedConnections()).isEqualTo(3);

        listener.onConnectionReleased(SqlConnectionEvent.r2dbc("r2dbc-1"));

        assertThat(listener.countLeakedConnections()).isEqualTo(2);
    }

    @Test public void
    stop_recording_captures_no_leak_when_all_connections_released() {
        listener.onConnectionAcquired(SqlConnectionEvent.jdbc("jdbc-5"));
        listener.onConnectionReleased(SqlConnectionEvent.jdbc("jdbc-5"));

        listener.stopRecording(singleJvmContext());

        BooleanMeasure measure = listener.findRecord(singleJvmContext());
        assertThat(measure).isNotNull();
        assertThat(measure.getValue()).isFalse();
    }

    @Test public void
    stop_recording_captures_leak_when_connection_not_released() {
        listener.onConnectionAcquired(SqlConnectionEvent.r2dbc("r2dbc-3"));

        listener.stopRecording(singleJvmContext());

        BooleanMeasure measure = listener.findRecord(singleJvmContext());
        assertThat(measure).isNotNull();
        assertThat(measure.getValue()).isTrue();
    }

    @Test public void
    stop_recording_unregisters_from_hook() {
        listener.stopRecording(singleJvmContext());

        assertThat(ConnectionListenerHook.getActiveListeners()).doesNotContain(listener);
    }

    @Test public void
    stop_recording_clears_counter() {
        listener.onConnectionAcquired(SqlConnectionEvent.jdbc("jdbc-6"));

        listener.stopRecording(singleJvmContext());

        assertThat(listener.countLeakedConnections()).isZero();
    }

}
