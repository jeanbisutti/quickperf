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
package org.quickperf.spring.boot.r2dbc;

import io.r2dbc.proxy.ProxyConnectionFactory;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Auto-configuration entry point for the QuickPerf R2DBC Spring Boot starter.
 *
 * <p>Active only when both {@code io.r2dbc.spi.ConnectionFactory} and the
 * r2dbc-proxy entry point are present on the classpath, and at least one
 * {@link ConnectionFactory} bean has been declared (typically by
 * {@link R2dbcAutoConfiguration}).
 *
 * <p>The actual wrap happens in
 * {@link QuickPerfR2dbcProxyBeanPostProcessor} (registered by the imported
 * {@link QuickPerfR2dbcConfig}).
 */
@AutoConfiguration(after = R2dbcAutoConfiguration.class)
@ConditionalOnClass({ ConnectionFactory.class, ProxyConnectionFactory.class })
@ConditionalOnBean(ConnectionFactory.class)
@Import(QuickPerfR2dbcConfig.class)
public class QuickPerfR2dbcProxyBeanAutoConfiguration {
}
