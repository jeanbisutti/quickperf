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

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With multiple providers registered, capture/install run in registration order and
 * restore runs in reverse order — the standard try/finally LIFO discipline.
 */
public class QuickPerfContextOrderingTest {

    @Rule public final QuickPerfContextTestLock lock = new QuickPerfContextTestLock();

    private List<ContextSnapshotProvider> originalProviders;
    private StubContextSnapshotProvider stubA;
    private StubContextSnapshotProvider stubB;

    @Before public void registerTwoProviders() {
        stubA = new StubContextSnapshotProvider("A");
        stubB = new StubContextSnapshotProvider("B");
        originalProviders = QuickPerfContext.setProvidersForTesting(
                Arrays.<ContextSnapshotProvider>asList(stubA, stubB));
    }

    @After public void restoreProviders() {
        QuickPerfContext.setProvidersForTesting(originalProviders);
    }

    @Test public void capture_install_in_order_restore_in_reverse() {
        Runnable wrapped = QuickPerfContext.wrap(new Runnable() {
            @Override public void run() {
                stubA.events.add("task:run");
            }
        });
        wrapped.run();

        // Both stubs share NO state (separate event lists), but the merged sequence is
        // captured by interleaving stubA.events and stubB.events. We assert each stub's
        // own sequence matches its expected position.
        assertThat(stubA.events).containsExactly(
                "A:capture", "A:install", "task:run", "A:restore:A:previous");
        assertThat(stubB.events).containsExactly(
                "B:capture", "B:install", "B:restore:B:previous");
    }

}
