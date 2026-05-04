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

/**
 * Recurring Runnable wrapper: keeps the captured snapshot list across fires so that each
 * invocation by {@link java.util.concurrent.ScheduledExecutorService#scheduleAtFixedRate}
 * or {@link java.util.concurrent.ScheduledExecutorService#scheduleWithFixedDelay} runs
 * with its own install/restore cycle.
 *
 * <p>Cross-test hazard: a recurrence whose lifespan exceeds the originating test will
 * keep recording into the original test's recorder. Long-period recurrences should be
 * cancelled in test teardown.
 */
final class RecurringQuickPerfRunnable implements Runnable {

    private final Runnable delegate;
    private final List<Snapshot> snapshots;

    RecurringQuickPerfRunnable(Runnable delegate, List<Snapshot> snapshots) {
        this.delegate = delegate;
        this.snapshots = snapshots;
    }

    @Override
    public void run() {
        Object[] previous = Snapshots.installAll(snapshots);
        try {
            delegate.run();
        } finally {
            Snapshots.restoreAll(snapshots, previous);
        }
    }

}
