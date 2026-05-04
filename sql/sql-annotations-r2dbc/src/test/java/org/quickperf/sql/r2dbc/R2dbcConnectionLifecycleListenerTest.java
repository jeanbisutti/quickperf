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

import io.r2dbc.proxy.test.MockConnectionInfo;
import io.r2dbc.proxy.test.MockMethodExecutionInfo;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.IsolationLevel;
import org.junit.Test;
import org.quickperf.sql.connection.SqlConnectionEvent;
import org.quickperf.sql.connection.SqlConnectionListener;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class R2dbcConnectionLifecycleListenerTest {

    private final RecordingListener recorder = new RecordingListener();

    private final R2dbcConnectionLifecycleListener listener =
            new R2dbcConnectionLifecycleListener(() -> Collections.<SqlConnectionListener>singletonList(recorder));

    @Test
    public void connection_factory_create_dispatches_acquired_after_method() throws Exception {
        Method create = ConnectionFactory.class.getMethod("create");
        MockMethodExecutionInfo info = MockMethodExecutionInfo.builder()
                .method(create)
                .connectionInfo(MockConnectionInfo.builder().connectionId("c-1").build())
                .build();

        listener.afterMethod(info);

        assertThat(recorder.events).hasSize(1);
        Event event = recorder.events.get(0);
        assertThat(event.kind).isEqualTo("ACQUIRED");
        assertThat(event.connectionId).isEqualTo("r2dbc-c-1");
        assertThat(event.source).isEqualTo(SqlConnectionEvent.Source.R2DBC);
    }

    @Test
    public void create_with_thrown_does_not_dispatch() throws Exception {
        Method create = ConnectionFactory.class.getMethod("create");
        MockMethodExecutionInfo info = MockMethodExecutionInfo.builder()
                .method(create)
                .setThrown(new RuntimeException("boom"))
                .connectionInfo(MockConnectionInfo.builder().connectionId("c-2").build())
                .build();

        listener.afterMethod(info);

        assertThat(recorder.events).isEmpty();
    }

    @Test
    public void close_dispatches_released_in_before_method() throws Exception {
        Method close = Connection.class.getMethod("close");
        MockMethodExecutionInfo info = MockMethodExecutionInfo.builder()
                .method(close)
                .connectionInfo(MockConnectionInfo.builder().connectionId("c-3").build())
                .build();

        listener.beforeMethod(info);

        assertThat(recorder.events).hasSize(1);
        Event event = recorder.events.get(0);
        assertThat(event.kind).isEqualTo("RELEASED");
        assertThat(event.connectionId).isEqualTo("r2dbc-c-3");
    }

    @Test
    public void close_does_not_dispatch_in_after_method() throws Exception {
        Method close = Connection.class.getMethod("close");
        MockMethodExecutionInfo info = MockMethodExecutionInfo.builder()
                .method(close)
                .connectionInfo(MockConnectionInfo.builder().connectionId("c-4").build())
                .build();

        listener.afterMethod(info);

        assertThat(recorder.events).isEmpty();
    }

    @Test
    public void begin_transaction_dispatches_tx_began() throws Exception {
        Method begin = Connection.class.getMethod("beginTransaction");
        MockMethodExecutionInfo info = MockMethodExecutionInfo.builder()
                .method(begin)
                .connectionInfo(MockConnectionInfo.builder().connectionId("c-5").build())
                .build();

        listener.afterMethod(info);

        assertThat(recorder.events).hasSize(1);
        assertThat(recorder.events.get(0).kind).isEqualTo("TX_BEGAN");
        assertThat(recorder.events.get(0).connectionId).isEqualTo("r2dbc-c-5");
    }

    @Test
    public void commit_transaction_dispatches_tx_committed() throws Exception {
        Method commit = Connection.class.getMethod("commitTransaction");
        MockMethodExecutionInfo info = MockMethodExecutionInfo.builder()
                .method(commit)
                .connectionInfo(MockConnectionInfo.builder().connectionId("c-6").build())
                .build();

        listener.afterMethod(info);

        assertThat(recorder.events).hasSize(1);
        assertThat(recorder.events.get(0).kind).isEqualTo("TX_COMMITTED");
    }

    @Test
    public void rollback_transaction_dispatches_tx_rolled_back() throws Exception {
        Method rollback = Connection.class.getMethod("rollbackTransaction");
        MockMethodExecutionInfo info = MockMethodExecutionInfo.builder()
                .method(rollback)
                .connectionInfo(MockConnectionInfo.builder().connectionId("c-7").build())
                .build();

        listener.afterMethod(info);

        assertThat(recorder.events).hasSize(1);
        assertThat(recorder.events.get(0).kind).isEqualTo("TX_ROLLED_BACK");
    }

    @Test
    public void set_auto_commit_dispatches_with_supplied_value() throws Exception {
        Method setAutoCommit = Connection.class.getMethod("setAutoCommit", boolean.class);
        MockMethodExecutionInfo info = MockMethodExecutionInfo.builder()
                .method(setAutoCommit)
                .methodArgs(new Object[] { Boolean.FALSE })
                .connectionInfo(MockConnectionInfo.builder().connectionId("c-8").build())
                .build();

        listener.afterMethod(info);

        assertThat(recorder.events).hasSize(1);
        Event event = recorder.events.get(0);
        assertThat(event.kind).isEqualTo("AUTO_COMMIT_CHANGED");
        assertThat(event.booleanArg).isEqualTo(Boolean.FALSE);
    }

    @Test
    public void set_transaction_isolation_dispatches_with_string_form() throws Exception {
        Method setIsolation = Connection.class.getMethod("setTransactionIsolationLevel", IsolationLevel.class);
        MockMethodExecutionInfo info = MockMethodExecutionInfo.builder()
                .method(setIsolation)
                .methodArgs(new Object[] { IsolationLevel.READ_COMMITTED })
                .connectionInfo(MockConnectionInfo.builder().connectionId("c-9").build())
                .build();

        listener.afterMethod(info);

        assertThat(recorder.events).hasSize(1);
        Event event = recorder.events.get(0);
        assertThat(event.kind).isEqualTo("ISOLATION_CHANGED");
        assertThat(event.stringArg).isEqualTo(String.valueOf(IsolationLevel.READ_COMMITTED));
    }

    @Test
    public void create_savepoint_dispatches_with_name() throws Exception {
        Method createSavepoint = Connection.class.getMethod("createSavepoint", String.class);
        MockMethodExecutionInfo info = MockMethodExecutionInfo.builder()
                .method(createSavepoint)
                .methodArgs(new Object[] { "sp1" })
                .connectionInfo(MockConnectionInfo.builder().connectionId("c-10").build())
                .build();

        listener.afterMethod(info);

        assertThat(recorder.events).hasSize(1);
        assertThat(recorder.events.get(0).kind).isEqualTo("SAVEPOINT_CREATED");
        assertThat(recorder.events.get(0).stringArg).isEqualTo("sp1");
    }

    @Test
    public void release_savepoint_dispatches_with_name() throws Exception {
        Method release = Connection.class.getMethod("releaseSavepoint", String.class);
        MockMethodExecutionInfo info = MockMethodExecutionInfo.builder()
                .method(release)
                .methodArgs(new Object[] { "sp2" })
                .connectionInfo(MockConnectionInfo.builder().connectionId("c-11").build())
                .build();

        listener.afterMethod(info);

        assertThat(recorder.events).hasSize(1);
        assertThat(recorder.events.get(0).kind).isEqualTo("SAVEPOINT_RELEASED");
        assertThat(recorder.events.get(0).stringArg).isEqualTo("sp2");
    }

    @Test
    public void rollback_to_savepoint_dispatches_with_name() throws Exception {
        Method rollbackToSavepoint = Connection.class.getMethod("rollbackTransactionToSavepoint", String.class);
        MockMethodExecutionInfo info = MockMethodExecutionInfo.builder()
                .method(rollbackToSavepoint)
                .methodArgs(new Object[] { "sp3" })
                .connectionInfo(MockConnectionInfo.builder().connectionId("c-12").build())
                .build();

        listener.afterMethod(info);

        assertThat(recorder.events).hasSize(1);
        assertThat(recorder.events.get(0).kind).isEqualTo("SAVEPOINT_ROLLED_BACK");
        assertThat(recorder.events.get(0).stringArg).isEqualTo("sp3");
    }

    @Test
    public void unrelated_connection_methods_do_not_dispatch() throws Exception {
        Method getMetadata = Connection.class.getMethod("getMetadata");
        MockMethodExecutionInfo info = MockMethodExecutionInfo.builder()
                .method(getMetadata)
                .connectionInfo(MockConnectionInfo.builder().connectionId("c-13").build())
                .build();

        listener.afterMethod(info);
        listener.beforeMethod(info);

        assertThat(recorder.events).isEmpty();
    }

    @Test
    public void null_method_execution_info_is_ignored() {
        listener.afterMethod(null);
        listener.beforeMethod(null);

        assertThat(recorder.events).isEmpty();
    }

    @Test
    public void null_listeners_supplier_is_rejected() {
        try {
            new R2dbcConnectionLifecycleListener(null);
            org.assertj.core.api.Assertions.failBecauseExceptionWasNotThrown(IllegalArgumentException.class);
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("listenersSupplier");
        }
    }

    private static final class Event {
        final String kind;
        final String connectionId;
        final SqlConnectionEvent.Source source;
        final Boolean booleanArg;
        final String stringArg;

        Event(String kind, SqlConnectionEvent event, Boolean booleanArg, String stringArg) {
            this.kind = kind;
            this.connectionId = event.getConnectionId();
            this.source = event.getSource();
            this.booleanArg = booleanArg;
            this.stringArg = stringArg;
        }
    }

    private static final class RecordingListener implements SqlConnectionListener {
        final List<Event> events = new ArrayList<>();

        @Override
        public void onConnectionAcquired(SqlConnectionEvent event) {
            events.add(new Event("ACQUIRED", event, null, null));
        }

        @Override
        public void onConnectionReleased(SqlConnectionEvent event) {
            events.add(new Event("RELEASED", event, null, null));
        }

        @Override
        public void onTransactionBegan(SqlConnectionEvent event) {
            events.add(new Event("TX_BEGAN", event, null, null));
        }

        @Override
        public void onTransactionCommitted(SqlConnectionEvent event) {
            events.add(new Event("TX_COMMITTED", event, null, null));
        }

        @Override
        public void onTransactionRolledBack(SqlConnectionEvent event) {
            events.add(new Event("TX_ROLLED_BACK", event, null, null));
        }

        @Override
        public void onAutoCommitChanged(SqlConnectionEvent event, boolean autoCommit) {
            events.add(new Event("AUTO_COMMIT_CHANGED", event, autoCommit, null));
        }

        @Override
        public void onIsolationLevelChanged(SqlConnectionEvent event, String level) {
            events.add(new Event("ISOLATION_CHANGED", event, null, level));
        }

        @Override
        public void onSavepointCreated(SqlConnectionEvent event, String name) {
            events.add(new Event("SAVEPOINT_CREATED", event, null, name));
        }

        @Override
        public void onSavepointReleased(SqlConnectionEvent event, String name) {
            events.add(new Event("SAVEPOINT_RELEASED", event, null, name));
        }

        @Override
        public void onSavepointRolledBack(SqlConnectionEvent event, String name) {
            events.add(new Event("SAVEPOINT_ROLLED_BACK", event, null, name));
        }
    }

}
