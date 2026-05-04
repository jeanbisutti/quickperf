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
package org.quickperf.junit4;

import junit.runner.Version;
import org.junit.Test;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;
import org.quickperf.SystemProperties;
import org.quickperf.TestExecutionContext;
import org.quickperf.config.library.QuickPerfConfigs;
import org.quickperf.config.library.QuickPerfConfigsLoader;
import org.quickperf.jvm.JVM;

import java.lang.reflect.Method;
import java.util.List;

public class QuickPerfJUnitRunner extends BlockJUnit4ClassRunner {

    private static final Statement NO_STATEMENT = new Statement() {
        @Override
        public void evaluate() {
        }
    };

    private final QuickPerfConfigs quickPerfConfigs = QuickPerfConfigsLoader.INSTANCE.loadQuickPerfConfigs();

    private TestExecutionContext testExecutionContext;

    // Reflection-based hook into SqlRecorderRegistry.markTestThread() so that
    // the SQL recorder registry can claim the current Surefire pool thread
    // BEFORE @Before runs. Without this, a @Before that emits SQL (Hibernate
    // EntityManagerFactory creation, schema generation, etc.) before
    // start()'s register() call would fall through the registry's worker
    // fallback and broadcast its DDL/DML to every recorder currently live
    // in the static ACTIVE_RECORDERS map - contaminating sibling tests
    // running concurrently under Surefire parallel=all. Reflection is used
    // so this junit4-runner module does not gain a hard dependency on
    // sql-annotations (the runner is also used for JVM-only tests).
    private static final java.lang.reflect.Method SQL_REGISTRY_MARK_TEST_THREAD_METHOD;
    private static final Object SQL_REGISTRY_INSTANCE;
    private static final java.lang.reflect.Method CONNECTION_LISTENER_REGISTRY_MARK_TEST_THREAD_METHOD;
    private static final Object CONNECTION_LISTENER_REGISTRY_INSTANCE;
    static {
        java.lang.reflect.Method method = null;
        Object instance = null;
        try {
            Class<?> registryClass = Class.forName("org.quickperf.sql.SqlRecorderRegistry");
            java.lang.reflect.Field instanceField = registryClass.getField("INSTANCE");
            instance = instanceField.get(null);
            method = registryClass.getMethod("markTestThread");
        } catch (Throwable ignored) {
            // sql-annotations is not on the classpath - this is a JVM-only
            // setup. No-op: there is no SqlRecorderRegistry to mark.
        }
        SQL_REGISTRY_MARK_TEST_THREAD_METHOD = method;
        SQL_REGISTRY_INSTANCE = instance;

        java.lang.reflect.Method listenerMethod = null;
        Object listenerInstance = null;
        try {
            Class<?> listenerRegistryClass = Class.forName("org.quickperf.sql.connection.ConnectionListenerRegistry");
            java.lang.reflect.Field instanceField = listenerRegistryClass.getField("INSTANCE");
            listenerInstance = instanceField.get(null);
            listenerMethod = listenerRegistryClass.getMethod("markTestThread");
        } catch (Throwable ignored) {
            // sql-annotations is not on the classpath - this is a JVM-only
            // setup. No-op: there is no ConnectionListenerRegistry to mark.
        }
        CONNECTION_LISTENER_REGISTRY_MARK_TEST_THREAD_METHOD = listenerMethod;
        CONNECTION_LISTENER_REGISTRY_INSTANCE = listenerInstance;
    }

    private static void markCurrentThreadAsSqlTestThread() {
        if (SQL_REGISTRY_MARK_TEST_THREAD_METHOD != null) {
            try {
                SQL_REGISTRY_MARK_TEST_THREAD_METHOD.invoke(SQL_REGISTRY_INSTANCE);
            } catch (Throwable ignored) {
                // best-effort: marker installation must never fail a test
            }
        }
        if (CONNECTION_LISTENER_REGISTRY_MARK_TEST_THREAD_METHOD != null) {
            try {
                CONNECTION_LISTENER_REGISTRY_MARK_TEST_THREAD_METHOD.invoke(CONNECTION_LISTENER_REGISTRY_INSTANCE);
            } catch (Throwable ignored) {
                // best-effort: marker installation must never fail a test
            }
        }
    }

    public QuickPerfJUnitRunner(Class<?> klass) throws InitializationError {
        super(klass);
    }

    @Override
    protected void runChild(FrameworkMethod method, RunNotifier notifier) {
        // Claim the current Surefire pool thread as a QuickPerf test thread
        // BEFORE methodBlock runs createTest() (which may load a Spring
        // ApplicationContext and emit DDL) and BEFORE @Before runs (which
        // may emit Hibernate / EntityManagerFactory schema generation SQL).
        // Without this, that pre-recording SQL would fall through the
        // registry's worker fallback and broadcast to sibling tests
        // running concurrently under Surefire parallel=all.
        SqlTestThreadMarker.markCurrentThreadAsSqlTestThread();
        super.runChild(method, notifier);
    }

    @Override
    protected void validateTestMethods(List<Throwable> errors) {
        validatePublicVoidNoArgMethods(Test.class, false, errors);
    }

    @Override
    public List<FrameworkMethod> computeTestMethods() {
        return getTestClass().getAnnotatedMethods(Test.class);
    }

    @Override
    public Statement methodInvoker(FrameworkMethod frameworkMethod, Object test) {
        Method testMethod = frameworkMethod.getMethod();

        int runnerAllocationOffset = findJUnit4AllocationOffset();

        testExecutionContext = TestExecutionContext.buildFrom(quickPerfConfigs
                                                            , testMethod
                                                            , runnerAllocationOffset);

        if(testExecutionContext.isQuickPerfDisabled()) {
            return super.methodInvoker(frameworkMethod, test);
        }

        if  (       SystemProperties.TEST_CODE_EXECUTING_IN_NEW_JVM.evaluate()
                || testExecutionContext.testExecutionUsesOneJVM()
            ) {
            QuickPerfMethod quickPerfMethod = new QuickPerfMethod( testMethod
                                                                 , testExecutionContext);
            return super.methodInvoker(quickPerfMethod, test);
        }

        return NO_STATEMENT;
    }

    private int findJUnit4AllocationOffset() {
        int allocationOffsetBeforeJava16 = findAllocationOffsetBeforeJava16();
        JVM.Version jvmVersion = JVM.INSTANCE.version;
        if(jvmVersion.isLessThanTo16()) {
            return allocationOffsetBeforeJava16;
        }
        return allocationOffsetBeforeJava16 + 8;
    }

    private int findAllocationOffsetBeforeJava16() {
        JVM.Version jvmVersion = JVM.INSTANCE.version;
        if (jvmVersion.isGreaterThanOrEqualTo12() && !junit4_13IsUsed()) {
            return 72;
        }
        return 40;
    }

    private boolean junit4_13IsUsed() {
        String junit4Version = Version.id();
        return junit4Version.startsWith("4.13");
    }

    @Override
    public Statement withBefores(FrameworkMethod frameworkMethod, Object testInstance, Statement statement) {

        if (       SystemProperties.TEST_CODE_EXECUTING_IN_NEW_JVM.evaluate()
                || testExecutionContext.testExecutionUsesOneJVM()
                || testExecutionContext.isQuickPerfDisabled()
           ) {
            final Statement junitBefores = super.withBefores(frameworkMethod, testInstance, statement);
            return new Statement() {
                @Override
                public void evaluate() throws Throwable {
                    markCurrentThreadAsSqlTestThread();
                    junitBefores.evaluate();
                }
            };
        }

        return NO_STATEMENT;

    }

    @Override
    public Statement withAfters(FrameworkMethod frameworkMethod, Object testInstance, Statement statement) {
        Statement junitAfters = super.withAfters(frameworkMethod, testInstance, statement);
        if(   SystemProperties.TEST_CODE_EXECUTING_IN_NEW_JVM.evaluate()
           || testExecutionContext.isQuickPerfDisabled() ) {
            return junitAfters;
        }
        return new MainJvmAfterJUnitStatement(  frameworkMethod
                                              , getTestClass().getJavaClass()
                                              , testExecutionContext
                                              , quickPerfConfigs
                                              , junitAfters);
    }

}
