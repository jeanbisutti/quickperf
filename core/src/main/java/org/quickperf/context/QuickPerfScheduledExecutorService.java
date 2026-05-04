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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * {@link ScheduledExecutorService} wrapper. Single-shot {@code schedule} captures the
 * calling thread's QuickPerf state at schedule time. Recurring {@code scheduleAtFixedRate}
 * / {@code scheduleWithFixedDelay} capture once at schedule time, then install/restore
 * per fire so each invocation sees the captured state and the worker thread's prior state
 * is restored between fires.
 */
final class QuickPerfScheduledExecutorService extends QuickPerfExecutorService
        implements ScheduledExecutorService {

    private final ScheduledExecutorService scheduledDelegate;

    QuickPerfScheduledExecutorService(ScheduledExecutorService delegate) {
        super(delegate);
        this.scheduledDelegate = delegate;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return scheduledDelegate.schedule(QuickPerfContext.wrap(command), delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return scheduledDelegate.schedule(QuickPerfContext.wrap(callable), delay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay,
                                                   long period, TimeUnit unit) {
        List<Snapshot> snapshots = QuickPerfContext.captureSnapshots();
        if (snapshots == null) {
            return scheduledDelegate.scheduleAtFixedRate(command, initialDelay, period, unit);
        }
        return scheduledDelegate.scheduleAtFixedRate(
                new RecurringQuickPerfRunnable(command, snapshots), initialDelay, period, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay,
                                                      long delay, TimeUnit unit) {
        List<Snapshot> snapshots = QuickPerfContext.captureSnapshots();
        if (snapshots == null) {
            return scheduledDelegate.scheduleWithFixedDelay(command, initialDelay, delay, unit);
        }
        return scheduledDelegate.scheduleWithFixedDelay(
                new RecurringQuickPerfRunnable(command, snapshots), initialDelay, delay, unit);
    }

}
