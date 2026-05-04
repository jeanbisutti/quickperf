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
package org.quickperf.sql.r2dbc.config;

import io.r2dbc.proxy.ProxyConnectionFactory;
import io.r2dbc.proxy.callback.ProxyConfig;
import io.r2dbc.proxy.callback.QuickPerfProxyFactoryFactory;
import io.r2dbc.spi.ConnectionFactory;
import org.quickperf.sql.r2dbc.R2dbcConnectionLifecycleListener;
import org.quickperf.sql.r2dbc.R2dbcQuickPerfListener;

/**
 * Convenience builder for non-Spring callers wishing to wrap a
 * {@link ConnectionFactory} with QuickPerf's R2DBC proxy listener.
 *
 * <p>Spring Boot users should prefer the auto-configured
 * {@code spring-boot-r2dbc-sql-starter}, which discovers and wraps
 * {@code ConnectionFactory} beans automatically.
 *
 * <pre>
 *   ConnectionFactory perfFactory = QuickPerfR2dbcConnectionFactoryBuilder
 *       .aBuilder()
 *       .beanName("connectionFactory")
 *       .buildProxy(originalFactory);
 * </pre>
 */
public final class QuickPerfR2dbcConnectionFactoryBuilder {

    /** Bean name used by Spring's R2dbcAutoConfiguration; the default for non-Spring callers too. */
    private String beanName = "connectionFactory";

    private QuickPerfR2dbcConnectionFactoryBuilder() {}

    public static QuickPerfR2dbcConnectionFactoryBuilder aBuilder() {
        return new QuickPerfR2dbcConnectionFactoryBuilder();
    }

    /** Override the bean name reported in {@code ExecutionInfo.dataSourceName} ({@code "r2dbc:" + beanName}). */
    public QuickPerfR2dbcConnectionFactoryBuilder beanName(String beanName) {
        if (beanName != null && !beanName.isEmpty()) {
            this.beanName = beanName;
        }
        return this;
    }

    /** Wrap {@code original} with QuickPerf's R2DBC proxy listener. */
    public ConnectionFactory buildProxy(ConnectionFactory original) {
        if (original == null) {
            throw new IllegalArgumentException("original ConnectionFactory must not be null");
        }
        ProxyConfig proxyConfig = ProxyConfig.builder()
                .proxyFactoryFactory(new QuickPerfProxyFactoryFactory())
                .build();
        proxyConfig.addListener(new R2dbcQuickPerfListener(beanName));
        proxyConfig.addListener(new R2dbcConnectionLifecycleListener());
        return ProxyConnectionFactory.builder(original)
                .proxyConfig(proxyConfig)
                .build();
    }

}
