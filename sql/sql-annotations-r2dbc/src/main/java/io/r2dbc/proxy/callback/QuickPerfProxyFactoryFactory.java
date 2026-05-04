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

// Placed in r2dbc-proxy's "io.r2dbc.proxy.callback" package so QuickPerfProxyFactory
// can invoke the package-private JdkProxyFactory(ProxyConfig) constructor.
// Verified against r2dbc-proxy 1.1.4.
package io.r2dbc.proxy.callback;

/**
 * QuickPerf {@link ProxyFactoryFactory} that produces {@link QuickPerfProxyFactory}
 * instances. Install via
 * {@code ProxyConfig.builder().proxyFactoryFactory(new QuickPerfProxyFactoryFactory()).build()}
 * and pass the resulting {@link ProxyConfig} to
 * {@code ProxyConnectionFactory.builder(...).proxyConfig(pc)}.
 */
public final class QuickPerfProxyFactoryFactory implements ProxyFactoryFactory {

    @Override
    public ProxyFactory create(ProxyConfig proxyConfig) {
        return new QuickPerfProxyFactory(proxyConfig);
    }
}
