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

import org.quickperf.context.ContextSnapshotProvider;
import org.quickperf.context.Snapshot;

import java.util.Map;

/**
 * Bridges {@link ConnectionListenerRegistry}'s per-thread listener state into the
 * {@link org.quickperf.context.QuickPerfContext} cross-thread propagation SPI.
 *
 * <p>Discovered by {@link java.util.ServiceLoader} via
 * {@code META-INF/services/org.quickperf.context.ContextSnapshotProvider}.
 *
 * <p>Returns {@code null} from {@link #capture()} when the calling thread has no
 * listeners registered, so {@code QuickPerfContext.wrap} returns the input unchanged
 * and the worker's {@code ACTIVE_LISTENERS} broadcast fallback in
 * {@link ConnectionListenerRegistry#getConnectionListeners()} is preserved.
 */
public class ConnectionListenerContextSnapshotProvider implements ContextSnapshotProvider {

    @Override
    public Snapshot capture() {
        final Map<Class<? extends ConnectionListener>, ConnectionListener> listenersFromCurrentThread
                = ConnectionListenerRegistry.INSTANCE.snapshotForCurrentThread();
        if (listenersFromCurrentThread.isEmpty()) {
            return null;
        }
        return new Snapshot() {
            @Override public Object install() {
                return ConnectionListenerRegistry.INSTANCE.installSnapshot(listenersFromCurrentThread);
            }
            @Override public void restore(Object previous) {
                ConnectionListenerRegistry.INSTANCE.restoreSnapshot(previous);
            }
        };
    }

}
