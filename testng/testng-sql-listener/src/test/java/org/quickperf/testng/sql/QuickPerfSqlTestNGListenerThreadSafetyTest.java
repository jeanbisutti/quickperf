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
package org.quickperf.testng.sql;

import org.quickperf.sql.annotation.ExpectSelect;
import org.quickperf.testng.QuickPerfSqlTestNGListener;
import org.testng.IInvokedMethod;
import org.testng.ITestNGMethod;
import org.testng.ITestResult;
import org.testng.annotations.Test;
import org.testng.internal.ConstructorOrMethod;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproducer for the bug where {@link QuickPerfSqlTestNGListener} stored the
 * per-invocation {@link org.quickperf.TestExecutionContext} in a singleton-listener
 * instance field. Under {@code parallel="methods"} two concurrent
 * {@code beforeInvocation} calls would stomp the shared field, so when each
 * {@code afterInvocation} ran it could read the OTHER invocation's context.
 *
 * <p>The fix stores the context on the per-invocation {@link ITestResult} using
 * {@link ITestResult#setAttribute}, so each invocation owns its own context and no
 * shared mutable state is involved. This test invokes {@code beforeInvocation}
 * twice in a row with two different {@link ITestResult} stubs, then asserts that
 * each result has its OWN context attached. With the buggy field-based code,
 * neither {@code ITestResult} carries the context, so the assertions fail.
 */
public class QuickPerfSqlTestNGListenerThreadSafetyTest {

    @SuppressWarnings("unused")
    public static class TwoMethodsFixture {

        @ExpectSelect(1)
        public void first_method() { }

        @ExpectSelect(2)
        public void second_method() { }

    }

    @Test public void
    each_invocation_should_have_its_own_test_execution_context() throws Exception {

        // GIVEN
        QuickPerfSqlTestNGListener listener = new QuickPerfSqlTestNGListener();

        TwoMethodsFixture instance = new TwoMethodsFixture();
        Method firstMethod = TwoMethodsFixture.class.getDeclaredMethod("first_method");
        Method secondMethod = TwoMethodsFixture.class.getDeclaredMethod("second_method");

        IInvokedMethod firstInvoked = stubInvokedMethod(firstMethod, instance);
        IInvokedMethod secondInvoked = stubInvokedMethod(secondMethod, instance);
        StubTestResultHandler firstHandler = new StubTestResultHandler();
        StubTestResultHandler secondHandler = new StubTestResultHandler();
        ITestResult firstResult = firstHandler.proxy();
        ITestResult secondResult = secondHandler.proxy();

        // WHEN: simulate two interleaved invocations starting before either finishes
        listener.beforeInvocation(firstInvoked, firstResult);
        try {
            listener.beforeInvocation(secondInvoked, secondResult);
            try {

                // THEN: each ITestResult must carry its own context, not a shared one
                Object firstContext = firstHandler.getOnlyAttributeValue();
                Object secondContext = secondHandler.getOnlyAttributeValue();

                assertThat(firstContext).isNotNull();
                assertThat(secondContext).isNotNull();
                assertThat(firstContext).isNotSameAs(secondContext);

            } finally {
                listener.afterInvocation(secondInvoked, secondResult);
            }
        } finally {
            listener.afterInvocation(firstInvoked, firstResult);
        }

    }

    private static IInvokedMethod stubInvokedMethod(final Method method, final Object instance) {
        final ITestNGMethod testNGMethod = stubTestNGMethod(method, instance);
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method invokedProxyMethod, Object[] args) {
                if ("getTestMethod".equals(invokedProxyMethod.getName())) {
                    return testNGMethod;
                }
                return defaultReturnValue(invokedProxyMethod);
            }
        };
        return (IInvokedMethod) Proxy.newProxyInstance(
                QuickPerfSqlTestNGListenerThreadSafetyTest.class.getClassLoader(),
                new Class<?>[]{IInvokedMethod.class},
                handler);
    }

    private static ITestNGMethod stubTestNGMethod(final Method method, final Object instance) {
        final ConstructorOrMethod constructorOrMethod = new ConstructorOrMethod(method);
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method invokedProxyMethod, Object[] args) {
                String name = invokedProxyMethod.getName();
                if ("getConstructorOrMethod".equals(name)) {
                    return constructorOrMethod;
                }
                if ("getInstance".equals(name)) {
                    return instance;
                }
                return defaultReturnValue(invokedProxyMethod);
            }
        };
        return (ITestNGMethod) Proxy.newProxyInstance(
                QuickPerfSqlTestNGListenerThreadSafetyTest.class.getClassLoader(),
                new Class<?>[]{ITestNGMethod.class},
                handler);
    }

    /**
     * Stub {@link ITestResult} that records {@link ITestResult#setAttribute} calls,
     * implements {@link ITestResult#getAttribute} from the same store, and treats
     * everything else as a no-op returning a primitive default. Holds the proxy
     * separately so the test can read back the stored context.
     */
    private static final class StubTestResultHandler implements InvocationHandler {

        private final Map<String, Object> attributes = new HashMap<String, Object>();
        private final ITestResult proxy;

        StubTestResultHandler() {
            this.proxy = (ITestResult) Proxy.newProxyInstance(
                    QuickPerfSqlTestNGListenerThreadSafetyTest.class.getClassLoader(),
                    new Class<?>[]{ITestResult.class},
                    this);
        }

        @Override
        public Object invoke(Object proxyArg, Method invokedProxyMethod, Object[] args) {
            String name = invokedProxyMethod.getName();
            if ("setAttribute".equals(name)) {
                attributes.put((String) args[0], args[1]);
                return null;
            }
            if ("getAttribute".equals(name)) {
                return attributes.get(args[0]);
            }
            return defaultReturnValue(invokedProxyMethod);
        }

        ITestResult proxy() { return proxy; }

        Object getOnlyAttributeValue() {
            assertThat(attributes).hasSize(1);
            return attributes.values().iterator().next();
        }

    }

    private static Object defaultReturnValue(Method method) {
        Class<?> returnType = method.getReturnType();
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) return Boolean.FALSE;
        if (returnType == void.class) return null;
        return 0;
    }

}
