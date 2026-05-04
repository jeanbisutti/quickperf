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
 * Verifies the basic capture/install/restore lifecycle invoked by
 * {@link QuickPerfContext#wrap(Runnable)} and {@link QuickPerfContext#wrap(Callable)}.
 */
public class QuickPerfContextWithStubProviderTest {

    @Rule public final QuickPerfContextTestLock lock = new QuickPerfContextTestLock();

    private List<ContextSnapshotProvider> originalProviders;
    private StubContextSnapshotProvider stub;

    @Before public void registerStubProvider() {
        stub = new StubContextSnapshotProvider("A");
        originalProviders = QuickPerfContext.setProvidersForTesting(
                Arrays.<ContextSnapshotProvider>asList(stub));
    }

    @After public void restoreProviders() {
        QuickPerfContext.setProvidersForTesting(originalProviders);
    }

    @Test public void wrap_runnable_captures_at_construction_and_installs_then_restores_on_run() {
        final boolean[] taskRan = {false};
        Runnable wrapped = QuickPerfContext.wrap(new Runnable() {
            @Override public void run() {
                taskRan[0] = true;
            }
        });
        // Capture happened at wrap()-time.
        assertThat(stub.captureCount.get()).isEqualTo(1);
        assertThat(stub.installCount.get()).isZero();
        assertThat(stub.restoreCount.get()).isZero();

        wrapped.run();

        assertThat(taskRan[0]).isTrue();
        assertThat(stub.installCount.get()).isEqualTo(1);
        assertThat(stub.restoreCount.get()).isEqualTo(1);
        assertThat(stub.events).containsExactly("A:capture", "A:install", "A:restore:A:previous");
    }

    @Test public void wrap_callable_captures_at_construction_and_installs_then_restores_on_call() throws Exception {
        Callable<String> wrapped = QuickPerfContext.wrap(new Callable<String>() {
            @Override public String call() {
                return "ok";
            }
        });
        assertThat(stub.captureCount.get()).isEqualTo(1);

        String result = wrapped.call();

        assertThat(result).isEqualTo("ok");
        assertThat(stub.installCount.get()).isEqualTo(1);
        assertThat(stub.restoreCount.get()).isEqualTo(1);
        assertThat(stub.events).containsExactly("A:capture", "A:install", "A:restore:A:previous");
    }

}
