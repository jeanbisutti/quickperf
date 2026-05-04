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
 * One-shot Runnable wrapper that installs a captured snapshot on the worker thread
 * before delegating, and restores the previous worker state afterwards.
 *
 * <p>After {@link #run()} completes, the captured snapshot list is released so a
 * still-reachable wrapper does not pin recorder references.
 */
final class QuickPerfRunnable implements Runnable {

    private final Runnable delegate;
    private List<Snapshot> snapshots;

    QuickPerfRunnable(Runnable delegate, List<Snapshot> snapshots) {
        this.delegate = delegate;
        this.snapshots = snapshots;
    }

    @Override
    public void run() {
        List<Snapshot> toInstall = snapshots;
        if (toInstall == null) {
            // Already ran once: snapshot was released. Re-running silently delegates
            // without context propagation. Reusing a wrapped Runnable across tests is
            // not supported; QuickPerfContext Javadoc warns against it.
            delegate.run();
            return;
        }
        Object[] previous = Snapshots.installAll(toInstall);
        try {
            delegate.run();
        } finally {
            Snapshots.restoreAll(toInstall, previous);
            snapshots = null;
        }
    }

}
