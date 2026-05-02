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

import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.quickperf.spring.boot.r2dbc.QuickPerfR2dbcProxyMarker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms the auto-configuration is loaded and the {@link ConnectionFactory}
 * bean is wrapped by QuickPerf's BPP.
 */
@SpringBootTest(classes = SpringBootR2dbcStarterJunit5Test.TestApp.class)
class SpringBootR2dbcStarterJunit5Test {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    @Autowired
    ConnectionFactory connectionFactory;

    @Test
    void connection_factory_bean_is_wrapped_by_quickperf_proxy() {
        // The JDK proxy created by QuickPerfR2dbcProxyBeanPostProcessor implements
        // both ConnectionFactory and QuickPerfR2dbcProxyMarker.
        assertThat(connectionFactory)
                .as("ConnectionFactory bean should be wrapped by QuickPerf's R2DBC BPP")
                .isInstanceOf(QuickPerfR2dbcProxyMarker.class);
    }

}
