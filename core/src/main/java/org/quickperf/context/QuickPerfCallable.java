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

import java.util.List;
import java.util.concurrent.Callable;

/**
 * One-shot Callable wrapper. See {@link QuickPerfRunnable} for the lifecycle contract.
 */
final class QuickPerfCallable<V> implements Callable<V> {

    private final Callable<V> delegate;
    private List<Snapshot> snapshots;

    QuickPerfCallable(Callable<V> delegate, List<Snapshot> snapshots) {
        this.delegate = delegate;
        this.snapshots = snapshots;
    }

    @Override
    public V call() throws Exception {
        List<Snapshot> toInstall = snapshots;
        if (toInstall == null) {
            return delegate.call();
        }
        Object[] previous = Snapshots.installAll(toInstall);
        try {
            return delegate.call();
        } finally {
            Snapshots.restoreAll(toInstall, previous);
            snapshots = null;
        }
    }

}
