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

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.beans.factory.config.BeanDefinition;

/**
 * Imports {@link QuickPerfR2dbcProxyBeanPostProcessor} as a static {@code @Bean}
 * factory method, which is mandatory for any {@code BeanPostProcessor} that
 * needs to be picked up before normal bean post-processing kicks in.
 *
 * <p>{@code proxyBeanMethods = false} is set because there is no inter-bean
 * call between this configuration's {@code @Bean} methods, so CGLIB
 * subclassing is not needed.
 */
@Configuration(proxyBeanMethods = false)
public class QuickPerfR2dbcConfig {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static QuickPerfR2dbcProxyBeanPostProcessor quickPerfR2dbcProxyBeanPostProcessor() {
        return new QuickPerfR2dbcProxyBeanPostProcessor();
    }

}
