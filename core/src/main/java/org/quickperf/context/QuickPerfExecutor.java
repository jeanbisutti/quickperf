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

import java.util.concurrent.Executor;

/**
 * {@link Executor} wrapper that captures the calling thread's QuickPerf state on each
 * {@link #execute(Runnable)} call.
 */
final class QuickPerfExecutor implements Executor {

    private final Executor delegate;

    QuickPerfExecutor(Executor delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(QuickPerfContext.wrap(command));
    }

}
