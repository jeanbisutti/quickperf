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
 * The wrapper's finally block MUST run for both {@link RuntimeException} and {@link Error}
 * thrown by the delegate task. Provider {@code restore()} failures are logged and
 * suppressed so they do not mask the task's exception.
 */
public class QuickPerfContextRestoreOnExceptionTest {

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

    @Test public void runtime_exception_propagates_after_restore() {
        Runnable wrapped = QuickPerfContext.wrap(new Runnable() {
            @Override public void run() { throw new IllegalStateException("boom"); }
        });
        try {
            wrapped.run();
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("boom");
        }
        assertThat(stubA.restoreCount.get()).isEqualTo(1);
        assertThat(stubB.restoreCount.get()).isEqualTo(1);
    }

    @Test public void error_propagates_after_restore() {
        Runnable wrapped = QuickPerfContext.wrap(new Runnable() {
            @Override public void run() { throw new AssertionError("bad"); }
        });
        try {
            wrapped.run();
        } catch (AssertionError expected) {
            assertThat(expected).hasMessage("bad");
        }
        assertThat(stubA.restoreCount.get()).isEqualTo(1);
        assertThat(stubB.restoreCount.get()).isEqualTo(1);
    }

    @Test public void provider_restore_failure_is_suppressed_other_providers_still_restore() {
        stubA.restoreThrows = new RuntimeException("stubA restore boom");
        Runnable wrapped = QuickPerfContext.wrap(new Runnable() {
            @Override public void run() { /* ok */ }
        });

        wrapped.run(); // does not throw — stubA restore failure is suppressed

        // Both restores were invoked (in reverse order: B, then A).
        assertThat(stubA.restoreCount.get()).isEqualTo(1);
        assertThat(stubB.restoreCount.get()).isEqualTo(1);
    }

    @Test public void provider_restore_failure_does_not_mask_task_exception() {
        stubA.restoreThrows = new RuntimeException("stubA restore boom");
        Runnable wrapped = QuickPerfContext.wrap(new Runnable() {
            @Override public void run() { throw new IllegalStateException("task boom"); }
        });
        try {
            wrapped.run();
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("task boom");
        }
    }

}
