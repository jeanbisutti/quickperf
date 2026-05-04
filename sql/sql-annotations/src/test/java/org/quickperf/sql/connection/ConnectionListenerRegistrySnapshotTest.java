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
import org.junit.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link ConnectionListenerRegistry#snapshotForCurrentThread()} returns a
 * defensive copy of the calling thread's registered listeners, and an empty map when
 * nothing is registered (or only the post-clear sentinel is present).
 */
public class ConnectionListenerRegistrySnapshotTest {

    @After
    public void tearDown() {
        ConnectionListenerRegistry.INSTANCE.clear();
    }

    @Test public void
    snapshot_is_empty_when_nothing_registered() {
        Map<Class<? extends ConnectionListener>, ConnectionListener> snapshot
                = ConnectionListenerRegistry.INSTANCE.snapshotForCurrentThread();
        assertThat(snapshot).isEmpty();
    }

    @Test public void
    snapshot_contains_registered_listener() {
        ConnectionLeakListener listener = new ConnectionLeakListener();
        ConnectionListenerRegistry.INSTANCE.register(listener);

        Map<Class<? extends ConnectionListener>, ConnectionListener> snapshot
                = ConnectionListenerRegistry.INSTANCE.snapshotForCurrentThread();

        assertThat(snapshot).containsEntry(ConnectionLeakListener.class, listener);
    }

    @Test public void
    snapshot_is_defensive_copy_so_caller_mutation_does_not_leak() {
        ConnectionLeakListener listener = new ConnectionLeakListener();
        ConnectionListenerRegistry.INSTANCE.register(listener);

        Map<Class<? extends ConnectionListener>, ConnectionListener> snapshot
                = ConnectionListenerRegistry.INSTANCE.snapshotForCurrentThread();
        snapshot.clear();

        Map<Class<? extends ConnectionListener>, ConnectionListener> reSnapshot
                = ConnectionListenerRegistry.INSTANCE.snapshotForCurrentThread();
        assertThat(reSnapshot).containsEntry(ConnectionLeakListener.class, listener);
    }

    @Test public void
    snapshot_after_clear_is_empty() {
        ConnectionListenerRegistry.INSTANCE.register(new ConnectionLeakListener());
        ConnectionListenerRegistry.INSTANCE.clear();

        Map<Class<? extends ConnectionListener>, ConnectionListener> snapshot
                = ConnectionListenerRegistry.INSTANCE.snapshotForCurrentThread();
        assertThat(snapshot).isEmpty();
    }

}
