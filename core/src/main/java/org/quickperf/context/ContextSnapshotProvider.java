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
package org.quickperf.context;

/**
 * SPI for propagating QuickPerf state across thread boundaries.
 *
 * <p>Implementations are discovered via {@link java.util.ServiceLoader} from the file
 * {@code META-INF/services/org.quickperf.context.ContextSnapshotProvider}.
 *
 * <p>A provider snapshots the calling thread's QuickPerf state in {@link #capture()}; the
 * snapshot can later install itself onto a worker thread and restore the worker's prior
 * state via the returned {@link Snapshot}.
 *
 * <p>Implementations MUST be thread-safe: {@link #capture()} may be invoked from any thread,
 * and a captured snapshot may be installed on any thread (typically a worker thread that is
 * not the one that called {@link #capture()}).
 *
 * <p>Returning {@code null} from {@link #capture()} indicates "no state to propagate".
 * Callers must treat {@code null} as a no-op (do not install anything on the worker) so
 * that fallback behaviour built into the registry is preserved.
 */
public interface ContextSnapshotProvider {

    /**
     * Capture the current thread's QuickPerf state.
     *
     * @return an opaque snapshot, or {@code null} if there is no state to propagate.
     */
    Snapshot capture();

}
