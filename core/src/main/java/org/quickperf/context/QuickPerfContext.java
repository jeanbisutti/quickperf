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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Public API for propagating QuickPerf state across thread boundaries.
 *
 * <p>Use the {@code wrap(...)} overloads to attribute work performed on a worker thread
 * to the calling thread's QuickPerf test context. Typical use from a test:
 *
 * <pre>{@code
 *   ExecutorService wrapped = QuickPerfContext.wrap(SHARED_EXECUTOR);
 *   wrapped.submit(myTask);
 * }</pre>
 *
 * <p>Capture timing:
 * <ul>
 *   <li>{@link #wrap(Executor)} / {@link #wrap(ExecutorService)} /
 *       {@link #wrap(ScheduledExecutorService)} return a wrapper that captures the
 *       calling thread's state on each {@code execute}/{@code submit}/{@code schedule}
 *       call. The wrapper itself is reusable across tests.</li>
 *   <li>{@link #wrap(Runnable)} and {@link #wrap(Callable)} capture <strong>at
 *       construction time</strong>. The returned task is one-shot in spirit; reusing it
 *       across tests preserves the original test's snapshot.</li>
 *   <li>For {@link ScheduledExecutorService#scheduleAtFixedRate} /
 *       {@link ScheduledExecutorService#scheduleWithFixedDelay}, capture happens once at
 *       schedule time; install/restore runs per fire.</li>
 * </ul>
 *
 * <p>Thread-safety: all {@code wrap(...)} methods are safe to call concurrently.
 *
 * <p>If no {@link ContextSnapshotProvider} is registered on the classpath, the
 * {@code wrap(Runnable)} and {@code wrap(Callable)} overloads return their input
 * unchanged. The executor wrappers always wrap (the per-{@code execute} capture path
 * may pick up a provider registered after class init in a multi-classloader environment,
 * though the provider list itself is loaded once).
 */
public final class QuickPerfContext {

    private static final Logger LOGGER = Logger.getLogger(QuickPerfContext.class.getName());

    private static volatile List<ContextSnapshotProvider> PROVIDERS = loadProviders();

    private QuickPerfContext() {}

    private static List<ContextSnapshotProvider> loadProviders() {
        List<ContextSnapshotProvider> providers = new ArrayList<ContextSnapshotProvider>();
        ServiceLoader<ContextSnapshotProvider> loader = ServiceLoader.load(
                ContextSnapshotProvider.class, QuickPerfContext.class.getClassLoader());
        Iterator<ContextSnapshotProvider> it = loader.iterator();
        while (true) {
            try {
                if (!it.hasNext()) {
                    break;
                }
                providers.add(it.next());
            } catch (ServiceConfigurationError e) {
                LOGGER.log(Level.WARNING,
                        "Failed to load a ContextSnapshotProvider; skipping", e);
                // ServiceLoader allows iteration to continue past a malformed entry.
            }
        }
        return Collections.unmodifiableList(providers);
    }

    /**
     * Capture snapshots from every registered provider on the calling thread. Returns
     * {@code null} when no provider has any state to propagate (zero-overhead path for
     * tests that do not exercise QuickPerf).
     */
    static List<Snapshot> captureSnapshots() {
        List<ContextSnapshotProvider> providers = PROVIDERS;
        if (providers.isEmpty()) {
            return null;
        }
        List<Snapshot> snapshots = null;
        for (int i = 0; i < providers.size(); i++) {
            ContextSnapshotProvider provider = providers.get(i);
            Snapshot snapshot;
            try {
                snapshot = provider.capture();
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING,
                        "ContextSnapshotProvider.capture() failed for "
                                + provider.getClass().getName(), e);
                continue;
            }
            if (snapshot != null) {
                if (snapshots == null) {
                    snapshots = new ArrayList<Snapshot>(providers.size());
                }
                snapshots.add(snapshot);
            }
        }
        return snapshots;
    }

    /**
     * Wrap a {@link Runnable} so that running it propagates the calling thread's
     * QuickPerf state.
     *
     * @throws NullPointerException if {@code runnable} is {@code null}.
     */
    public static Runnable wrap(Runnable runnable) {
        if (runnable == null) {
            throw new NullPointerException("runnable");
        }
        List<Snapshot> snapshots = captureSnapshots();
        if (snapshots == null) {
            return runnable;
        }
        return new QuickPerfRunnable(runnable, snapshots);
    }

    /**
     * Wrap a {@link Callable} so that calling it propagates the calling thread's
     * QuickPerf state.
     *
     * @throws NullPointerException if {@code callable} is {@code null}.
     */
    public static <V> Callable<V> wrap(Callable<V> callable) {
        if (callable == null) {
            throw new NullPointerException("callable");
        }
        List<Snapshot> snapshots = captureSnapshots();
        if (snapshots == null) {
            return callable;
        }
        return new QuickPerfCallable<V>(callable, snapshots);
    }

    /**
     * Wrap an {@link Executor} so that every submitted {@link Runnable} propagates the
     * submitting thread's QuickPerf state.
     *
     * @throws NullPointerException if {@code executor} is {@code null}.
     */
    public static Executor wrap(Executor executor) {
        if (executor == null) {
            throw new NullPointerException("executor");
        }
        return new QuickPerfExecutor(executor);
    }

    /**
     * Wrap an {@link ExecutorService} so that every submitted/invoked task propagates the
     * submitting thread's QuickPerf state. All lifecycle methods ({@code shutdown},
     * {@code awaitTermination}, etc.) delegate to the underlying service.
     *
     * <p>Note: {@link ExecutorService#shutdownNow()} returns the unstarted wrapper
     * Runnables, not the user's original tasks.
     *
     * @throws NullPointerException if {@code executorService} is {@code null}.
     */
    public static ExecutorService wrap(ExecutorService executorService) {
        if (executorService == null) {
            throw new NullPointerException("executorService");
        }
        return new QuickPerfExecutorService(executorService);
    }

    /**
     * Wrap a {@link ScheduledExecutorService}. {@code schedule(...)} captures at
     * schedule time; {@code scheduleAtFixedRate} / {@code scheduleWithFixedDelay} capture
     * once at schedule time and install/restore per fire.
     *
     * @throws NullPointerException if {@code scheduledExecutorService} is {@code null}.
     */
    public static ScheduledExecutorService wrap(ScheduledExecutorService scheduledExecutorService) {
        if (scheduledExecutorService == null) {
            throw new NullPointerException("scheduledExecutorService");
        }
        return new QuickPerfScheduledExecutorService(scheduledExecutorService);
    }

    // -------------------------------------------------------------------------
    // Test support
    // -------------------------------------------------------------------------

    /**
     * Replace the registered providers. <strong>Test-only.</strong> Returns the previous
     * list so the test can restore it in tearDown.
     */
    static synchronized List<ContextSnapshotProvider> setProvidersForTesting(
            List<ContextSnapshotProvider> providers) {
        List<ContextSnapshotProvider> old = PROVIDERS;
        if (providers == null) {
            PROVIDERS = Collections.emptyList();
        } else {
            PROVIDERS = Collections.unmodifiableList(
                    new ArrayList<ContextSnapshotProvider>(providers));
        }
        return old;
    }

}
