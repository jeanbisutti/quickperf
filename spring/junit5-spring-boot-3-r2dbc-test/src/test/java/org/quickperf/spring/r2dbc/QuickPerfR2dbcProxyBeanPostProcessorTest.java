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
package org.quickperf.spring.r2dbc;

import io.r2dbc.spi.Closeable;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import io.r2dbc.spi.Wrapped;
import org.junit.jupiter.api.Test;
import org.quickperf.spring.boot.r2dbc.QuickPerfR2dbcProxyBeanPostProcessor;
import org.quickperf.spring.boot.r2dbc.QuickPerfR2dbcProxyMarker;
import org.reactivestreams.Publisher;
import org.springframework.aop.framework.Advised;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link QuickPerfR2dbcProxyBeanPostProcessor} that exercise both wrap paths:
 *
 * <ul>
 *   <li>The CGLIB outer subclass path (the target class is non-final).</li>
 *   <li>The JDK dynamic proxy fallback (the target class is {@code final}, so CGLIB cannot
 *       subclass it).</li>
 * </ul>
 */
class QuickPerfR2dbcProxyBeanPostProcessorTest {

    private final QuickPerfR2dbcProxyBeanPostProcessor bpp = new QuickPerfR2dbcProxyBeanPostProcessor();

    @Test
    void non_connection_factory_bean_is_returned_unchanged() {
        Object bean = new Object();

        Object processed = bpp.postProcessAfterInitialization(bean, "anyBean");

        assertThat(processed).isSameAs(bean);
    }

    @Test
    void already_wrapped_bean_is_not_double_wrapped() {
        ConnectionFactory target = new TestConnectionFactory();

        Object first = bpp.postProcessAfterInitialization(target, "cf");
        Object second = bpp.postProcessAfterInitialization(first, "cf");

        assertThat(second).isSameAs(first);
    }

    @Test
    void non_final_target_is_wrapped_by_cglib_subclass_preserving_runtime_type() throws Exception {
        ConnectionFactory target = new TestConnectionFactory();

        Object proxy = bpp.postProcessAfterInitialization(target, "cf");

        assertThat(proxy)
                .as("CGLIB subclass must preserve the original target type")
                .isInstanceOf(TestConnectionFactory.class)
                .isInstanceOf(QuickPerfR2dbcProxyMarker.class);
        assertThat(((Advised) proxy).getTargetSource().getTarget())
                .as("Spring AOP Advised must expose the raw target")
                .isSameAs(target);
    }

    @Test
    void final_target_falls_back_to_jdk_proxy_when_cglib_cannot_subclass() {
        ConnectionFactory target = new FinalConnectionFactory();

        Object proxy = bpp.postProcessAfterInitialization(target, "cf");

        assertThat(proxy)
                .as("CGLIB cannot subclass a final class; the BPP must fall back to a JDK proxy")
                .isNotInstanceOf(FinalConnectionFactory.class)
                .isInstanceOf(ConnectionFactory.class)
                .isInstanceOf(QuickPerfR2dbcProxyMarker.class);
    }

    @Test
    void jdk_proxy_fallback_adds_wrapped_only_when_target_is_wrapped() {
        ConnectionFactory wrappedTarget = new FinalWrappedConnectionFactory();
        ConnectionFactory bareTarget = new FinalConnectionFactory();

        Object wrappedProxy = bpp.postProcessAfterInitialization(wrappedTarget, "cf");
        Object bareProxy = bpp.postProcessAfterInitialization(bareTarget, "cf");

        assertThat(wrappedProxy)
                .as("JDK proxy must expose Wrapped only when the target itself implements it; "
                        + "exposing Wrapped on a non-Wrapped target would lead to AbstractMethodError")
                .isInstanceOf(Wrapped.class);
        assertThat(bareProxy).isNotInstanceOf(Wrapped.class);
    }

    @Test
    void jdk_proxy_fallback_adds_disposable_interface_when_target_is_disposable() {
        ConnectionFactory target = new FinalDisposableConnectionFactory();

        Object proxy = bpp.postProcessAfterInitialization(target, "cf");

        assertThat(proxy).isInstanceOf(Disposable.class);
    }

    @Test
    void jdk_proxy_fallback_adds_closeable_interface_when_target_is_closeable() {
        ConnectionFactory target = new FinalCloseableConnectionFactory();

        Object proxy = bpp.postProcessAfterInitialization(target, "cf");

        assertThat(proxy).isInstanceOf(Closeable.class);
    }

    @Test
    void jdk_proxy_fallback_does_not_add_disposable_when_target_is_not_disposable() {
        ConnectionFactory target = new FinalConnectionFactory();

        Object proxy = bpp.postProcessAfterInitialization(target, "cf");

        assertThat(proxy).isNotInstanceOf(Disposable.class);
    }

    @Test
    void jdk_proxy_fallback_does_not_add_closeable_when_target_is_not_closeable() {
        ConnectionFactory target = new FinalConnectionFactory();

        Object proxy = bpp.postProcessAfterInitialization(target, "cf");

        assertThat(proxy).isNotInstanceOf(Closeable.class);
    }

    @Test
    void jdk_proxy_fallback_forwards_unwrap_to_target_when_target_is_wrapped() {
        FinalWrappedConnectionFactory target = new FinalWrappedConnectionFactory();

        Object proxy = bpp.postProcessAfterInitialization(target, "cf");

        Object unwrapped = ((Wrapped<?>) proxy).unwrap();

        assertThat(unwrapped).isSameAs(target.innerFactory);
    }

    static class TestConnectionFactory implements ConnectionFactory {
        @Override
        public Publisher<? extends Connection> create() {
            return Mono.empty();
        }

        @Override
        public ConnectionFactoryMetadata getMetadata() {
            return () -> "test";
        }
    }

    static final class FinalConnectionFactory implements ConnectionFactory {
        @Override
        public Publisher<? extends Connection> create() {
            return Mono.empty();
        }

        @Override
        public ConnectionFactoryMetadata getMetadata() {
            return () -> "final";
        }
    }

    static final class FinalDisposableConnectionFactory implements ConnectionFactory, Disposable {
        @Override
        public Publisher<? extends Connection> create() {
            return Mono.empty();
        }

        @Override
        public ConnectionFactoryMetadata getMetadata() {
            return () -> "final-disposable";
        }

        @Override
        public void dispose() {
            // no-op
        }
    }

    static final class FinalCloseableConnectionFactory implements ConnectionFactory, Closeable {
        @Override
        public Publisher<? extends Connection> create() {
            return Mono.empty();
        }

        @Override
        public ConnectionFactoryMetadata getMetadata() {
            return () -> "final-closeable";
        }

        @Override
        public Publisher<Void> close() {
            return Mono.empty();
        }
    }

    static final class FinalWrappedConnectionFactory implements ConnectionFactory, Wrapped<ConnectionFactory> {

        final ConnectionFactory innerFactory = new TestConnectionFactory();

        @Override
        public Publisher<? extends Connection> create() {
            return Mono.empty();
        }

        @Override
        public ConnectionFactoryMetadata getMetadata() {
            return () -> "final-wrapped";
        }

        @Override
        public ConnectionFactory unwrap() {
            return innerFactory;
        }
    }

}
