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

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CRITICAL test — locks in the PR1 worker-thread {@code ACTIVE_LISTENERS} broadcast
 * fallback against an accidental future "install empty snapshot on workers" regression.
 * Mirror of {@code SqlRecorderRegistryEmptySnapshotPreservesActiveFallbackTest}.
 */
public class ConnectionListenerRegistryEmptySnapshotPreservesActiveFallbackTest {

    @After
    public void tearDown() {
        ConnectionListenerRegistry.INSTANCE.clear();
    }

    @Test public void
    empty_install_then_worker_still_sees_listener_via_active_fallback() throws Exception {
        final ConnectionLeakListener testThreadListener = new ConnectionLeakListener();
        ConnectionListenerRegistry.INSTANCE.register(testThreadListener);

        final AtomicReference<Collection<ConnectionListener>> workerView
                = new AtomicReference<Collection<ConnectionListener>>();
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                Object token = ConnectionListenerRegistry.INSTANCE.installSnapshot(
                        Collections.<Class<? extends ConnectionListener>, ConnectionListener>emptyMap());
                try {
                    workerView.set(ConnectionListenerRegistry.INSTANCE.getConnectionListeners());
                } finally {
                    ConnectionListenerRegistry.INSTANCE.restoreSnapshot(token);
                }
            }
        }, "listener-active-fallback-preservation");
        worker.start();
        worker.join();

        assertThat(workerView.get()).contains(testThreadListener);
    }

}
