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
 * An opaque snapshot of QuickPerf state captured by a {@link ContextSnapshotProvider}.
 *
 * <p>Typical usage from a worker thread:
 * <pre>{@code
 *   Snapshot s = provider.capture(); // on the originating thread
 *   // ... handed off to the worker thread ...
 *   Object previous = s.install();   // on the worker thread
 *   try {
 *       // worker work
 *   } finally {
 *       s.restore(previous);
 *   }
 * }</pre>
 *
 * <p>{@code Snapshot} instances are not thread-safe in the sense that a single instance
 * MUST be installed and restored on the same thread; however, distinct snapshots returned
 * from a single {@code capture()} may be used independently on multiple worker threads.
 */
public interface Snapshot {

    /**
     * Install this snapshot on the calling (worker) thread.
     *
     * @return an opaque token capturing the worker's previous state. The token MUST be
     *         passed back to {@link #restore(Object)} in a {@code finally} block.
     */
    Object install();

    /**
     * Restore the worker thread to the state recorded in {@code previous}.
     *
     * <p>Implementations should not throw under normal conditions; failures should be
     * logged. Throwing here would mask a task exception in the surrounding {@code finally}
     * block.
     *
     * @param previous the token returned by {@link #install()}.
     */
    void restore(Object previous);

}
