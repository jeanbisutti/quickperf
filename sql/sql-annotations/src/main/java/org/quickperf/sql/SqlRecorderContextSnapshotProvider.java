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
package org.quickperf.sql;

import org.quickperf.context.ContextSnapshotProvider;
import org.quickperf.context.Snapshot;

import java.util.Map;

/**
 * Bridges {@link SqlRecorderRegistry}'s per-thread recorder state into the
 * {@link org.quickperf.context.QuickPerfContext} cross-thread propagation SPI.
 *
 * <p>Discovered by {@link java.util.ServiceLoader} via
 * {@code META-INF/services/org.quickperf.context.ContextSnapshotProvider}.
 *
 * <p>Returning {@code null} from {@link #capture()} when the calling thread
 * has no registered recorders is intentional: it prevents
 * {@link org.quickperf.context.QuickPerfContext#wrap} from installing an
 * empty snapshot on the worker, which would mask the
 * {@code ACTIVE_RECORDERS} worker-thread broadcast fallback in
 * {@code SqlRecorderRegistry.getSqlRecorders}.
 */
public class SqlRecorderContextSnapshotProvider implements ContextSnapshotProvider {

    @Override
    public Snapshot capture() {
        final Map<Class<? extends SqlRecorder>, SqlRecorder> recordersFromCurrentThread
                = SqlRecorderRegistry.INSTANCE.snapshotForCurrentThread();
        if (recordersFromCurrentThread.isEmpty()) {
            return null;
        }
        return new Snapshot() {
            @Override public Object install() {
                return SqlRecorderRegistry.INSTANCE.installSnapshot(recordersFromCurrentThread);
            }
            @Override public void restore(Object previous) {
                SqlRecorderRegistry.INSTANCE.restoreSnapshot(previous);
            }
        };
    }

}
