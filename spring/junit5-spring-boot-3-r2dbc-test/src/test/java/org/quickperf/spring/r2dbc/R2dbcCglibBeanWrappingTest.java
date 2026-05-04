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

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.spi.Closeable;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import io.r2dbc.spi.Wrapped;
import org.junit.jupiter.api.Test;
import org.quickperf.spring.boot.r2dbc.QuickPerfR2dbcProxyMarker;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import reactor.core.Disposable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms that the CGLIB outer subclass produced by
 * {@link org.quickperf.spring.boot.r2dbc.QuickPerfR2dbcProxyBeanPostProcessor} preserves the bean's
 * runtime type. With {@code spring-boot-starter-data-r2dbc} and {@code r2dbc-pool} on the
 * classpath, Spring Boot's {@code R2dbcAutoConfiguration} produces a {@link ConnectionPool} bean,
 * so user code performing {@code instanceof ConnectionPool} or casting to call
 * {@code getMetrics()} keeps working.
 */
@SpringBootTest(classes = R2dbcCglibBeanWrappingTest.TestApp.class)
class R2dbcCglibBeanWrappingTest {

    /**
     * Plain {@code @Configuration} (not {@code @SpringBootConfiguration}) so that the slice
     * {@code @DataR2dbcTest} used by sibling tests in the {@code r2dbctest/} sub-package does not
     * pick this up via {@code AnnotatedClassFinder}'s walk-up + recursive scan and bail out with
     * "Found multiple @SpringBootConfiguration annotated classes".
     */
    @Configuration
    @EnableAutoConfiguration
    static class TestApp {}

    @Autowired
    ConnectionFactory connectionFactory;

    @Test
    void connection_factory_bean_preserves_connection_pool_runtime_type() {
        assertThat(connectionFactory)
                .as("CGLIB outer subclass must preserve the ConnectionPool runtime type so user code "
                        + "performing 'instanceof ConnectionPool' or pool-specific casts keeps working")
                .isInstanceOf(ConnectionPool.class);
    }

    @Test
    void connection_factory_bean_carries_quickperf_marker_and_inherited_pool_interfaces() {
        assertThat(connectionFactory)
                .isInstanceOf(QuickPerfR2dbcProxyMarker.class)
                .isInstanceOf(Wrapped.class)
                .isInstanceOf(Closeable.class)
                .isInstanceOf(Disposable.class);
    }

    @Test
    void raw_target_connection_pool_is_reachable_via_spring_aop_advised() throws Exception {
        Advised advised = (Advised) connectionFactory;

        Object rawTarget = advised.getTargetSource().getTarget();

        assertThat(rawTarget)
                .as("Spring AOP's Advised must expose the original ConnectionPool bean Spring "
                        + "created so framework / advanced code can reach it without the QuickPerf wrap")
                .isInstanceOf(ConnectionPool.class)
                .isNotInstanceOf(QuickPerfR2dbcProxyMarker.class);
    }

    @Test
    void connection_pool_metadata_is_accessible_through_proxy() {
        ConnectionFactoryMetadata metadata = connectionFactory.getMetadata();

        assertThat(metadata).isNotNull();
        assertThat(metadata.getName()).isEqualToIgnoringCase("H2");
    }

}
