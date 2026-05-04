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

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When no {@link ContextSnapshotProvider} is registered, the task wrap overloads return
 * the input unchanged so wrap is zero-overhead for non-QuickPerf code paths.
 */
public class QuickPerfContextNoProvidersTest {

    @Rule public final QuickPerfContextTestLock lock = new QuickPerfContextTestLock();

    private List<ContextSnapshotProvider> originalProviders;

    @Before public void replaceProvidersWithEmptyList() {
        originalProviders = QuickPerfContext.setProvidersForTesting(
                Collections.<ContextSnapshotProvider>emptyList());
    }

    @After public void restoreProviders() {
        QuickPerfContext.setProvidersForTesting(originalProviders);
    }

    @Test public void wrap_runnable_returns_input_unchanged() {
        Runnable input = new Runnable() { @Override public void run() {} };
        assertThat(QuickPerfContext.wrap(input)).isSameAs(input);
    }

    @Test public void wrap_callable_returns_input_unchanged() {
        Callable<String> input = new Callable<String>() {
            @Override public String call() { return "ok"; }
        };
        assertThat(QuickPerfContext.wrap(input)).isSameAs(input);
    }

}
