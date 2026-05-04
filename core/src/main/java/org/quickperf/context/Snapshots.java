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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Internal helper for installing and restoring an ordered list of {@link Snapshot}s.
 *
 * <p>Install order matches the provider order from {@link QuickPerfContext}. Restore order
 * is reversed so that providers see their own state torn down in LIFO order, consistent
 * with try/finally resource discipline.
 */
final class Snapshots {

    private static final Logger LOGGER = Logger.getLogger(Snapshots.class.getName());

    private Snapshots() {}

    /**
     * Install all snapshots in order.
     *
     * <p>If a snapshot's {@link Snapshot#install()} throws, snapshots already installed
     * are restored in reverse order before the original exception is rethrown.
     *
     * @return an array of "previous" tokens in installation order; index {@code i}
     *         corresponds to {@code snapshots.get(i)}.
     */
    static Object[] installAll(List<Snapshot> snapshots) {
        Object[] previous = new Object[snapshots.size()];
        for (int i = 0; i < snapshots.size(); i++) {
            try {
                previous[i] = snapshots.get(i).install();
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING,
                        "Snapshot.install() failed; rolling back partial install", e);
                for (int j = i - 1; j >= 0; j--) {
                    try {
                        snapshots.get(j).restore(previous[j]);
                    } catch (Throwable t) {
                        LOGGER.log(Level.WARNING,
                                "Snapshot.restore() failed during install rollback", t);
                    }
                }
                throw e;
            }
        }
        return previous;
    }

    /**
     * Restore all snapshots in reverse order. Failures are logged and do not propagate;
     * a restore failure must not mask a task exception flowing through the surrounding
     * try/finally.
     */
    static void restoreAll(List<Snapshot> snapshots, Object[] previous) {
        for (int i = snapshots.size() - 1; i >= 0; i--) {
            try {
                snapshots.get(i).restore(previous[i]);
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "Snapshot.restore() failed", t);
            }
        }
    }

}
