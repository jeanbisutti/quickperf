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
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When all registered providers return {@code null} from {@code capture()} (i.e. there
 * is nothing to propagate from the calling thread), {@link QuickPerfContext#wrap(Runnable)}
 * and {@link QuickPerfContext#wrap(Callable)} MUST return the input unchanged. This is
 * the contract that preserves PR1's {@code ACTIVE_RECORDERS} fallback on worker threads.
 */
public class QuickPerfContextEmptyCaptureNoOpTest {

    @Rule public final QuickPerfContextTestLock lock = new QuickPerfContextTestLock();

    private List<ContextSnapshotProvider> originalProviders;
    private StubContextSnapshotProvider stub;

    @Before public void registerStubReturningNullCapture() {
        stub = new StubContextSnapshotProvider("A");
        stub.captureReturnsNull = true;
        originalProviders = QuickPerfContext.setProvidersForTesting(
                Arrays.<ContextSnapshotProvider>asList(stub));
    }

    @After public void restoreProviders() {
        QuickPerfContext.setProvidersForTesting(originalProviders);
    }

    @Test public void wrap_runnable_returns_input_when_capture_is_null() {
        Runnable input = new Runnable() { @Override public void run() {} };
        Runnable wrapped = QuickPerfContext.wrap(input);
        assertThat(wrapped).isSameAs(input);
        assertThat(stub.captureCount.get()).isEqualTo(1);
        assertThat(stub.installCount.get()).isZero();
    }

    @Test public void wrap_callable_returns_input_when_capture_is_null() {
        Callable<String> input = new Callable<String>() {
            @Override public String call() { return "ok"; }
        };
        Callable<String> wrapped = QuickPerfContext.wrap(input);
        assertThat(wrapped).isSameAs(input);
        assertThat(stub.captureCount.get()).isEqualTo(1);
        assertThat(stub.installCount.get()).isZero();
    }

}
