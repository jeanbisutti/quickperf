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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only recording provider used to verify capture/install/restore semantics.
 *
 * <p>Records every lifecycle event into {@link #events} as a {@code label:event} string.
 * Multiple stub providers in a single test register distinct labels so ordering is
 * verifiable. All event collections are thread-safe because install/restore typically
 * runs on a worker thread.
 */
final class StubContextSnapshotProvider implements ContextSnapshotProvider {

    final String label;
    final AtomicInteger captureCount = new AtomicInteger();
    final AtomicInteger installCount = new AtomicInteger();
    final AtomicInteger restoreCount = new AtomicInteger();
    final List<String> events = Collections.synchronizedList(new ArrayList<String>());

    boolean captureReturnsNull;
    RuntimeException captureThrows;
    RuntimeException installThrows;
    RuntimeException restoreThrows;

    StubContextSnapshotProvider(String label) {
        this.label = label;
    }

    @Override
    public Snapshot capture() {
        captureCount.incrementAndGet();
        events.add(label + ":capture");
        if (captureThrows != null) {
            throw captureThrows;
        }
        if (captureReturnsNull) {
            return null;
        }
        return new StubSnapshot();
    }

    private final class StubSnapshot implements Snapshot {
        @Override
        public Object install() {
            installCount.incrementAndGet();
            events.add(label + ":install");
            if (installThrows != null) {
                throw installThrows;
            }
            return label + ":previous";
        }

        @Override
        public void restore(Object previous) {
            restoreCount.incrementAndGet();
            events.add(label + ":restore:" + previous);
            if (restoreThrows != null) {
                throw restoreThrows;
            }
        }
    }

}
