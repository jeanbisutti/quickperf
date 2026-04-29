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
package org.quickperf.sql;

import org.junit.jupiter.api.Test;
import org.quickperf.junit5.JUnit5Tests;
import org.quickperf.junit5.JUnit5Tests.JUnit5TestsResult;
import org.quickperf.junit5.QuickPerfTest;
import org.quickperf.sql.annotation.ExpectSelect;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

class JUnit5ThreadingScenariosTest {

    private static ThreadFactory daemonThreadFactory(final String name) {
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, name);
                t.setDaemon(true);
                return t;
            }
        };
    }

    private static void prestartWorkerThreads(ExecutorService executorService, int numberOfWorkers) {
        final CountDownLatch workersStarted = new CountDownLatch(numberOfWorkers);
        final CountDownLatch releaseWorkers = new CountDownLatch(1);
        Future<?>[] futures = new Future<?>[numberOfWorkers];
        try {
            for (int i = 0; i < numberOfWorkers; i++) {
                futures[i] = executorService.submit(new Runnable() {
                    @Override
                    public void run() {
                        workersStarted.countDown();
                        try {
                            releaseWorkers.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                    }
                });
            }
            if (!workersStarted.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Executor did not create the expected worker threads");
            }
            releaseWorkers.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            releaseWorkers.countDown();
            throw new RuntimeException(e);
        }
    }

    private static void forceSchedulerWorkerCreation(ScheduledExecutorService scheduler) {
        try {
            scheduler.schedule(new Runnable() {
                @Override
                public void run() { }
            }, 0, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void executeSelect(EntityManagerFactory entityManagerFactory) {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            em.createQuery("FROM " + Book.class.getCanonicalName()).getResultList();
        } finally {
            em.close();
        }
    }

    private static Future<?> submitSelect(ExecutorService executorService,
                                          final EntityManagerFactory entityManagerFactory) {
        return executorService.submit(new Runnable() {
            @Override
            public void run() {
                executeSelect(entityManagerFactory);
            }
        });
    }

    private static void submitSelectAndAwait(ExecutorService executorService,
                                             final EntityManagerFactory entityManagerFactory) throws Exception {
        Future<?> future = submitSelect(executorService, entityManagerFactory);
        future.get(5, TimeUnit.SECONDS);
    }

    private static void scheduleSelectAndAwait(ScheduledExecutorService scheduler,
                                               final EntityManagerFactory entityManagerFactory) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        scheduler.schedule(selectTask(entityManagerFactory, latch), 0, TimeUnit.MILLISECONDS);
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private static void enqueueSelectAndAwait(BlockingQueue<Runnable> tasks,
                                              final EntityManagerFactory entityManagerFactory) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        tasks.put(selectTask(entityManagerFactory, latch));
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private static Runnable selectTask(final EntityManagerFactory entityManagerFactory,
                                       final CountDownLatch latch) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    executeSelect(entityManagerFactory);
                } finally {
                    latch.countDown();
                }
            }
        };
    }

    private static JUnit5TestsResult run(Class<?> testClass) {
        return JUnit5Tests.createInstance(testClass).run();
    }

    private static void assertNoFailure(Class<?> testClass, String description) {
        JUnit5TestsResult result = run(testClass);
        assertThat(result.getNumberOfFailures())
                .as(description + ": %s", result.getErrorReport())
                .isZero();
    }

    private static TestExecutionSummary runInParallel(Class<?> testClass, int parallelism) {
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(testClass))
                .configurationParameter("junit.jupiter.execution.parallel.enabled", "true")
                .configurationParameter("junit.jupiter.execution.parallel.mode.default", "concurrent")
                .configurationParameter("junit.jupiter.execution.parallel.config.strategy", "fixed")
                .configurationParameter("junit.jupiter.execution.parallel.config.fixed.parallelism", String.valueOf(parallelism))
                .build();
        Launcher launcher = LauncherFactory.create();
        launcher.execute(request, listener);
        return listener.getSummary();
    }

    // =========================================================================
    // SQL on a pre-existing Tomcat-style HTTP worker thread
    // =========================================================================
    // A Spring Boot application's Tomcat thread pool is created at startup,
    // before a test method starts. SQL triggered by an HTTP request — even one
    // sent from inside the test method — must still be recorded for the running
    // QuickPerf test.
    //
    // We simulate the Tomcat pool with a static ExecutorService started before
    // the QuickPerf test method runs. The threads are named like Tomcat's NIO
    // connector ("http-nio-8080-exec-N") for readability only — QuickPerf does
    // not key behavior off the thread name.

    @QuickPerfTest
    public static class SqlFromPreExistingTomcatStylePool extends SqlTestBaseJUnit5 {

        private static final ExecutorService TOMCAT_LIKE_POOL;
        static {
            final AtomicInteger counter = new AtomicInteger();
            TOMCAT_LIKE_POOL = Executors.newFixedThreadPool(2, new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "http-nio-8080-exec-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });
            prestartWorkerThreads(TOMCAT_LIKE_POOL, 2);
        }

        @ExpectSelect(1)
        @Test
        public void executes_sql_on_pre_existing_tomcat_thread() throws Exception {
            submitSelectAndAwait(TOMCAT_LIKE_POOL, emf);
        }
    }

    @Test
    void sql_from_pre_existing_tomcat_thread_pool_should_be_recorded() {
        assertNoFailure(SqlFromPreExistingTomcatStylePool.class,
                "SQL executed on a pre-existing Tomcat-style HTTP worker thread must be recorded");
    }

    // =========================================================================
    // @Async methods — SQL on a pre-existing executor thread pool
    // =========================================================================
    // The executor and its worker thread are created before any test method
    // runs. SQL executed from that worker during a QuickPerf test must still
    // be recorded for the running test.

    @QuickPerfTest
    public static class SqlFromPreExistingExecutorThread extends SqlTestBaseJUnit5 {

        private static final ExecutorService PRE_EXISTING_EXECUTOR;
        static {
            PRE_EXISTING_EXECUTOR = Executors.newSingleThreadExecutor(
                    daemonThreadFactory("pre-existing-executor-0"));
            prestartWorkerThreads(PRE_EXISTING_EXECUTOR, 1);
        }

        @ExpectSelect(1)
        @Test
        public void executes_sql_on_pre_existing_executor_thread() throws Exception {
            submitSelectAndAwait(PRE_EXISTING_EXECUTOR, emf);
        }
    }

    @Test
    void sql_from_pre_existing_executor_thread_should_be_recorded() {
        assertNoFailure(SqlFromPreExistingExecutorThread.class,
                "SQL executed on a pre-existing executor thread must be recorded");
    }

    // =========================================================================
    // CompletableFuture with a pre-existing custom executor
    // =========================================================================
    // CompletableFuture.runAsync(..., executor) wraps the user task in an
    // AsyncRun before handing it to a worker that already exists. SQL run
    // through that wrapper during a QuickPerf test must still be recorded
    // for the running test.

    @QuickPerfTest
    public static class SqlFromPreExistingCompletableFutureExecutor extends SqlTestBaseJUnit5 {

        private static final ExecutorService PRE_EXISTING_EXECUTOR;
        static {
            PRE_EXISTING_EXECUTOR = Executors.newFixedThreadPool(2,
                    daemonThreadFactory("pre-existing-cf-executor"));
            prestartWorkerThreads(PRE_EXISTING_EXECUTOR, 2);
        }

        @ExpectSelect(1)
        @Test
        public void executes_sql_via_completable_future_on_pre_existing_executor() throws Exception {
            final EntityManagerFactory localEmf = emf;
            CompletableFuture.runAsync(new Runnable() {
                @Override
                public void run() {
                    executeSelect(localEmf);
                }
            }, PRE_EXISTING_EXECUTOR).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void sql_from_completable_future_on_pre_existing_executor_should_be_recorded() {
        assertNoFailure(SqlFromPreExistingCompletableFutureExecutor.class,
                "SQL executed via CompletableFuture on a pre-existing executor must be recorded");
    }

    // =========================================================================
    // @Scheduled tasks — SQL on a pre-existing ScheduledExecutorService
    // =========================================================================
    // A Spring @Scheduled-style scheduler owns its worker thread for the
    // lifetime of the application. SQL executed by a scheduled task during
    // a QuickPerf test must still be recorded for the running test.

    @QuickPerfTest
    public static class SqlFromPreExistingScheduledExecutor extends SqlTestBaseJUnit5 {

        private static final ScheduledExecutorService PRE_EXISTING_SCHEDULER;
        static {
            PRE_EXISTING_SCHEDULER = Executors.newScheduledThreadPool(1,
                    daemonThreadFactory("pre-existing-scheduler-0"));
            forceSchedulerWorkerCreation(PRE_EXISTING_SCHEDULER);
        }

        @ExpectSelect(1)
        @Test
        public void executes_sql_on_pre_existing_scheduled_executor() throws Exception {
            scheduleSelectAndAwait(PRE_EXISTING_SCHEDULER, emf);
        }
    }

    @Test
    void sql_from_pre_existing_scheduled_executor_should_be_recorded() {
        assertNoFailure(SqlFromPreExistingScheduledExecutor.class,
                "SQL executed on a pre-existing scheduler thread must be recorded");
    }

    // =========================================================================
    // Reactive / WebFlux schedulers (Reactor / Netty)
    // =========================================================================
    // Project Reactor schedulers and Netty event loops typically use long-lived
    // worker threads that may already exist before a test method starts.
    // When SQL runs on such workers during a QuickPerf test, QuickPerf must
    // still record that SQL for the running test.
    //
    // We simulate that lifecycle with a static executor. The workers are named
    // like Reactor bounded-elastic threads for readability only — QuickPerf
    // does not key behavior off the thread name.

    @QuickPerfTest
    public static class SqlFromPreExistingReactorScheduler extends SqlTestBaseJUnit5 {

        private static final ExecutorService REACTOR_LIKE_SCHEDULER;
        static {
            final AtomicInteger counter = new AtomicInteger();
            REACTOR_LIKE_SCHEDULER = Executors.newFixedThreadPool(2, new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r,
                            "reactor-bounded-elastic-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });
            prestartWorkerThreads(REACTOR_LIKE_SCHEDULER, 2);
        }

        @ExpectSelect(1)
        @Test
        public void executes_sql_on_pre_existing_reactor_scheduler() throws Exception {
            submitSelectAndAwait(REACTOR_LIKE_SCHEDULER, emf);
        }
    }

    @Test
    void sql_from_reactor_scheduler_should_be_recorded() {
        assertNoFailure(SqlFromPreExistingReactorScheduler.class,
                "SQL executed on a pre-existing Reactor-style scheduler thread must be recorded");
    }

    // =========================================================================
    // Concurrent QuickPerf SQL recording
    // =========================================================================
    // Several @QuickPerfTest methods run at the same time in the same JVM.
    // Each method executes one SELECT and expects exactly one SELECT.
    // QuickPerf must keep each method's SQL count independent from the others.

    @QuickPerfTest
    public static class ConcurrentQuickPerfTests extends SqlTestBaseJUnit5 {

        @ExpectSelect(1)
        @Test
        public void runs_select_in_parallel_method_1() {
            executeSelect(emf);
        }

        @ExpectSelect(1)
        @Test
        public void runs_select_in_parallel_method_2() {
            executeSelect(emf);
        }

        @ExpectSelect(1)
        @Test
        public void runs_select_in_parallel_method_3() {
            executeSelect(emf);
        }

        @ExpectSelect(1)
        @Test
        public void runs_select_in_parallel_method_4() {
            executeSelect(emf);
        }

        @ExpectSelect(1)
        @Test
        public void runs_select_in_parallel_method_5() {
            executeSelect(emf);
        }
    }

    @Test
    void parallel_quickperf_tests_should_record_sql_independently() {
        TestExecutionSummary summary = runInParallel(ConcurrentQuickPerfTests.class, 5);

        assertThat(summary.getTestsFailedCount())
                .as("Parallel QuickPerf tests must record SQL independently")
                .isZero();
    }

    // =========================================================================
    // Persistent worker reused across QuickPerf test lifecycles
    // =========================================================================
    // The same long-lived worker thread is used by two consecutive runs of the
    // same @QuickPerfTest method in the same JVM. Each run submits one SELECT
    // and expects exactly one SELECT.
    // QuickPerf must record the SQL for the currently running test, not keep
    // associating the worker with a previous run.

    @QuickPerfTest
    public static class SqlFromPersistentSharedWorker extends SqlTestBaseJUnit5 {

        private static volatile Thread WORKER;
        private static final BlockingQueue<Runnable> TASKS = new LinkedBlockingQueue<>();

        @ExpectSelect(1)
        @Test
        public void executes_sql_on_persistent_shared_worker() throws Exception {
            ensureWorkerStarted();
            enqueueSelectAndAwait(TASKS, emf);
        }

        private static synchronized void ensureWorkerStarted() {
            if (WORKER != null) {
                return;
            }
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            TASKS.take().run();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }, "persistent-worker");
            t.setDaemon(true);
            t.start();
            WORKER = t;
        }
    }

    @Test
    void persistent_worker_should_record_sql_across_test_lifecycles() {
        // Two consecutive runs share the same JVM and the same static worker.
        JUnit5TestsResult run1 = run(SqlFromPersistentSharedWorker.class);
        JUnit5TestsResult run2 = run(SqlFromPersistentSharedWorker.class);

        int total = run1.getNumberOfFailures() + run2.getNumberOfFailures();
        assertThat(total)
                .as("Persistent worker must record SQL for the currently running test execution, "
                        + "not a previous execution. Run 1 errors: %s | Run 2 errors: %s",
                        run1.getErrorReport(), run2.getErrorReport())
                .isZero();
    }

    // =========================================================================
    // JUnit 5 parallel execution with a shared executor
    // =========================================================================
    // Two tests run concurrently and share a static ExecutorService. SQL
    // submitted by one test must count only for that test's @ExpectSelect
    // assertion, even when the shared worker was created by the other test.
    // Today, the worker keeps its original test association, so the SQL is
    // attributed to the wrong test. Equivalent to the case from the analysis
    // (RANDOM_PORT + Tomcat workers), distilled to plain Java.

    @QuickPerfTest
    public static class ConcurrentTestsWithSharedExecutor extends SqlTestBaseJUnit5 {

        private static volatile ExecutorService SHARED_EXECUTOR;
        private static final CountDownLatch EXECUTOR_READY = new CountDownLatch(1);
        private static final CountDownLatch SQL_EXECUTED_ON_SHARED_EXECUTOR = new CountDownLatch(1);

        @org.junit.jupiter.api.AfterAll
        static void shutdownSharedExecutor() {
            if (SHARED_EXECUTOR != null) {
                SHARED_EXECUTOR.shutdownNow();
                SHARED_EXECUTOR = null;
            }
        }

        @ExpectSelect(1)
        @Test
        void test_with_executor_select() throws Exception {
            // Wait until the other test has created the shared executor worker.
            assertThat(EXECUTOR_READY.await(5, TimeUnit.SECONDS)).isTrue();
            final EntityManagerFactory localEmf = emf;
            try {
                Future<?> done = submitSelect(SHARED_EXECUTOR, localEmf);
                done.get(5, TimeUnit.SECONDS);
            } finally {
                SQL_EXECUTED_ON_SHARED_EXECUTOR.countDown();
            }
        }

        @ExpectSelect(0)
        @Test
        void test_with_no_sql() throws Exception {
            // Create the worker from this test before the other test submits SQL to it.
            SHARED_EXECUTOR = Executors.newSingleThreadExecutor();
            prestartWorkerThreads(SHARED_EXECUTOR, 1);
            EXECUTOR_READY.countDown();
            // Keep this test active until the other test has run its SQL on the shared worker.
            assertThat(SQL_EXECUTED_ON_SHARED_EXECUTOR.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrent_tests_sharing_an_executor_should_not_contaminate_each_other() {
        TestExecutionSummary summary = runInParallel(ConcurrentTestsWithSharedExecutor.class, 2);

        assertThat(summary.getTestsFailedCount())
                .as("Concurrent tests sharing an executor must not see each other's SQL")
                .isZero();
    }

    // =========================================================================
    // Long-running message listener thread
    // =========================================================================
    // A message listener container (Kafka, RabbitMQ, JMS, etc.) typically owns
    // long-lived worker threads that are created before a test method starts.
    // When such a worker handles a message that executes SQL during a QuickPerf
    // test, QuickPerf must still record that SQL for the running test.

    @QuickPerfTest
    public static class SqlFromPreExistingMessageListenerThread extends SqlTestBaseJUnit5 {

        private static final BlockingQueue<Runnable> MESSAGE_QUEUE = new LinkedBlockingQueue<>();
        private static final Thread LISTENER_THREAD;
        static {
            LISTENER_THREAD = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            Runnable task = MESSAGE_QUEUE.take();
                            task.run();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }, "pre-existing-message-listener-0");
            LISTENER_THREAD.setDaemon(true);
            LISTENER_THREAD.start();
        }

        @ExpectSelect(1)
        @Test
        public void executes_sql_on_pre_existing_message_listener_thread() throws Exception {
            enqueueSelectAndAwait(MESSAGE_QUEUE, emf);
        }
    }

    @Test
    void sql_from_long_running_message_listener_should_be_recorded() {
        assertNoFailure(SqlFromPreExistingMessageListenerThread.class,
                "SQL executed on a pre-existing message-listener thread must be recorded");
    }

}
