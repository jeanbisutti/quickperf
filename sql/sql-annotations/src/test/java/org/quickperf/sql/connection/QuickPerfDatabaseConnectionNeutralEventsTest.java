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

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link QuickPerfDatabaseConnection} dispatches neutral
 * {@link SqlConnectionListener} events from each lifecycle/state-change site
 * in addition to the legacy JDBC-typed {@link ConnectionListener} callbacks.
 */
public class QuickPerfDatabaseConnectionNeutralEventsTest {

    private RecordingNeutralListener neutralListener;

    private Connection delegate;
    private QuickPerfDatabaseConnection wrapper;

    @Before
    public void setUp() {
        delegate = mock(Connection.class);
        wrapper = QuickPerfDatabaseConnection.buildFrom(delegate);
        neutralListener = new RecordingNeutralListener(expectedId());
        ConnectionListenerHook.register(neutralListener);
    }

    @After
    public void tearDown() {
        ConnectionListenerHook.unregister(neutralListener);
    }

    private String expectedId() {
        return "jdbc-" + System.identityHashCode(wrapper);
    }

    @Test public void
    the_datasource_gets_the_connection_dispatches_acquired() {
        wrapper.theDatasourceGetsTheConnection();

        assertThat(neutralListener.eventsFor(expectedId()))
                .containsExactly(EventOf.ACQUIRED);
    }

    @Test public void
    the_datasource_gets_the_connection_with_username_and_password_dispatches_acquired() {
        wrapper.theDatasourceGetsTheConnectionWithUserNameAndPassword();

        assertThat(neutralListener.eventsFor(expectedId()))
                .containsExactly(EventOf.ACQUIRED);
    }

    @Test public void
    close_dispatches_released() throws SQLException {
        wrapper.close();

        assertThat(neutralListener.eventsFor(expectedId()))
                .containsExactly(EventOf.RELEASED);
    }

    @Test public void
    set_auto_commit_dispatches_after_successful_delegate_call() throws SQLException {
        wrapper.setAutoCommit(false);

        assertThat(neutralListener.eventsFor(expectedId()))
                .containsExactly(EventOf.AUTO_COMMIT_CHANGED);
        assertThat(neutralListener.lastBoolean).isFalse();
    }

    @Test public void
    set_auto_commit_does_not_dispatch_when_delegate_throws() throws SQLException {
        org.mockito.Mockito.doThrow(new SQLException("boom")).when(delegate).setAutoCommit(true);

        try {
            wrapper.setAutoCommit(true);
        } catch (SQLException expected) {
            // delegate failure must propagate
        }

        assertThat(neutralListener.eventsFor(expectedId())).isEmpty();
    }

    @Test public void
    commit_dispatches_after_successful_delegate_call() throws SQLException {
        wrapper.commit();

        assertThat(neutralListener.eventsFor(expectedId()))
                .containsExactly(EventOf.TX_COMMITTED);
    }

    @Test public void
    rollback_dispatches_after_successful_delegate_call() throws SQLException {
        wrapper.rollback();

        assertThat(neutralListener.eventsFor(expectedId()))
                .containsExactly(EventOf.TX_ROLLED_BACK);
    }

    @Test public void
    set_transaction_isolation_dispatches_with_string_form() throws SQLException {
        wrapper.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

        assertThat(neutralListener.eventsFor(expectedId()))
                .containsExactly(EventOf.ISOLATION_CHANGED);
        assertThat(neutralListener.lastIsolationLevel)
                .isEqualTo(String.valueOf(Connection.TRANSACTION_READ_COMMITTED));
    }

    @Test public void
    set_savepoint_named_dispatches_with_supplied_name() throws SQLException {
        Savepoint savepoint = mock(Savepoint.class);
        when(delegate.setSavepoint("sp1")).thenReturn(savepoint);

        wrapper.setSavepoint("sp1");

        assertThat(neutralListener.eventsFor(expectedId()))
                .containsExactly(EventOf.SAVEPOINT_CREATED);
        assertThat(neutralListener.lastSavepointName).isEqualTo("sp1");
    }

    @Test public void
    set_savepoint_unnamed_dispatches_with_resolved_name() throws SQLException {
        Savepoint savepoint = mock(Savepoint.class);
        when(savepoint.getSavepointName()).thenReturn("auto-sp");
        when(delegate.setSavepoint()).thenReturn(savepoint);

        wrapper.setSavepoint();

        assertThat(neutralListener.eventsFor(expectedId()))
                .containsExactly(EventOf.SAVEPOINT_CREATED);
        assertThat(neutralListener.lastSavepointName).isEqualTo("auto-sp");
    }

    @Test public void
    set_savepoint_unnamed_dispatches_with_null_name_when_savepoint_throws() throws SQLException {
        Savepoint savepoint = mock(Savepoint.class);
        when(savepoint.getSavepointName()).thenThrow(new SQLException("unnamed"));
        when(delegate.setSavepoint()).thenReturn(savepoint);

        wrapper.setSavepoint();

        assertThat(neutralListener.eventsFor(expectedId()))
                .containsExactly(EventOf.SAVEPOINT_CREATED);
        assertThat(neutralListener.lastSavepointName).isNull();
    }

    @Test public void
    rollback_to_savepoint_dispatches_with_savepoint_name() throws SQLException {
        Savepoint savepoint = mock(Savepoint.class);
        when(savepoint.getSavepointName()).thenReturn("sp2");

        wrapper.rollback(savepoint);

        assertThat(neutralListener.eventsFor(expectedId()))
                .containsExactly(EventOf.SAVEPOINT_ROLLED_BACK);
        assertThat(neutralListener.lastSavepointName).isEqualTo("sp2");
    }

    @Test public void
    release_savepoint_dispatches_with_savepoint_name() throws SQLException {
        Savepoint savepoint = mock(Savepoint.class);
        when(savepoint.getSavepointName()).thenReturn("sp3");

        wrapper.releaseSavepoint(savepoint);

        assertThat(neutralListener.eventsFor(expectedId()))
                .containsExactly(EventOf.SAVEPOINT_RELEASED);
        assertThat(neutralListener.lastSavepointName).isEqualTo("sp3");
    }

    @Test public void
    every_neutral_event_uses_the_jdbc_source_marker() throws SQLException {
        wrapper.theDatasourceGetsTheConnection();

        SqlConnectionEvent event = neutralListener.findFirst(expectedId());
        assertThat(event).isNotNull();
        assertThat(event.getSource()).isEqualTo(SqlConnectionEvent.Source.JDBC);
    }

    enum EventOf {
        ACQUIRED, RELEASED, TX_COMMITTED, TX_ROLLED_BACK, AUTO_COMMIT_CHANGED,
        ISOLATION_CHANGED, SAVEPOINT_CREATED, SAVEPOINT_RELEASED, SAVEPOINT_ROLLED_BACK
    }

    private static final class RecordedEvent {
        final EventOf kind;
        final SqlConnectionEvent event;

        RecordedEvent(EventOf kind, SqlConnectionEvent event) {
            this.kind = kind;
            this.event = event;
        }
    }

    private static final class RecordingNeutralListener implements SqlConnectionListener {
        private final String acceptedConnectionId;
        private final List<RecordedEvent> events = new ArrayList<>();
        Boolean lastBoolean;
        String lastIsolationLevel;
        String lastSavepointName;

        RecordingNeutralListener(String acceptedConnectionId) {
            this.acceptedConnectionId = acceptedConnectionId;
        }

        private boolean accepts(SqlConnectionEvent event) {
            return acceptedConnectionId.equals(event.getConnectionId());
        }

        synchronized List<EventOf> eventsFor(String connectionId) {
            List<EventOf> matched = new ArrayList<>();
            for (RecordedEvent ev : events) {
                if (connectionId.equals(ev.event.getConnectionId())) {
                    matched.add(ev.kind);
                }
            }
            return matched;
        }

        synchronized SqlConnectionEvent findFirst(String connectionId) {
            for (RecordedEvent ev : events) {
                if (connectionId.equals(ev.event.getConnectionId())) {
                    return ev.event;
                }
            }
            return null;
        }

        @Override public synchronized void onConnectionAcquired(SqlConnectionEvent event) {
            if (accepts(event)) {
                events.add(new RecordedEvent(EventOf.ACQUIRED, event));
            }
        }
        @Override public synchronized void onConnectionReleased(SqlConnectionEvent event) {
            if (accepts(event)) {
                events.add(new RecordedEvent(EventOf.RELEASED, event));
            }
        }
        @Override public synchronized void onTransactionBegan(SqlConnectionEvent event) {
            if (accepts(event)) {
                events.add(new RecordedEvent(EventOf.ACQUIRED, event));
            }
        }
        @Override public synchronized void onTransactionCommitted(SqlConnectionEvent event) {
            if (accepts(event)) {
                events.add(new RecordedEvent(EventOf.TX_COMMITTED, event));
            }
        }
        @Override public synchronized void onTransactionRolledBack(SqlConnectionEvent event) {
            if (accepts(event)) {
                events.add(new RecordedEvent(EventOf.TX_ROLLED_BACK, event));
            }
        }
        @Override public synchronized void onAutoCommitChanged(SqlConnectionEvent event, boolean autoCommit) {
            if (accepts(event)) {
                events.add(new RecordedEvent(EventOf.AUTO_COMMIT_CHANGED, event));
                this.lastBoolean = autoCommit;
            }
        }
        @Override public synchronized void onIsolationLevelChanged(SqlConnectionEvent event, String level) {
            if (accepts(event)) {
                events.add(new RecordedEvent(EventOf.ISOLATION_CHANGED, event));
                this.lastIsolationLevel = level;
            }
        }
        @Override public synchronized void onSavepointCreated(SqlConnectionEvent event, String name) {
            if (accepts(event)) {
                events.add(new RecordedEvent(EventOf.SAVEPOINT_CREATED, event));
                this.lastSavepointName = name;
            }
        }
        @Override public synchronized void onSavepointReleased(SqlConnectionEvent event, String name) {
            if (accepts(event)) {
                events.add(new RecordedEvent(EventOf.SAVEPOINT_RELEASED, event));
                this.lastSavepointName = name;
            }
        }
        @Override public synchronized void onSavepointRolledBack(SqlConnectionEvent event, String name) {
            if (accepts(event)) {
                events.add(new RecordedEvent(EventOf.SAVEPOINT_ROLLED_BACK, event));
                this.lastSavepointName = name;
            }
        }
    }

}
