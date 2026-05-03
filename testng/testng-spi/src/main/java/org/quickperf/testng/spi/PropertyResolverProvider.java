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
package org.quickperf.testng.spi;

import org.quickperf.config.PropertyResolver;

import java.lang.reflect.Method;

/**
 * SPI implemented by integration modules (e.g. {@code quick-perf-testng-spring})
 * that can build a {@link PropertyResolver} from a TestNG test instance.
 *
 * <p>TestNG does not have a concept analogous to JUnit 5's
 * {@code ExtensionContext}, so the provider receives the actual test instance
 * (typically from {@code IInvokedMethod.getTestMethod().getInstance()}) and
 * the reflective {@link Method}. Providers must:
 * <ul>
 *   <li>be safe to call when the test class does not extend any framework
 *       base class (return {@code null} so the caller falls back to
 *       {@link org.quickperf.config.SystemPropertyResolver#INSTANCE});</li>
 *   <li>be safe to call before the application context is loaded
 *       (e.g. during {@code @BeforeClass}; return {@code null});</li>
 *   <li>never throw &mdash; wrap and swallow all reflective failures, returning
 *       {@code null}.</li>
 * </ul>
 */
public interface PropertyResolverProvider {

    /**
     * Build a {@link PropertyResolver} for the given TestNG test invocation,
     * or return {@code null} if this provider does not apply.
     *
     * @param testInstance the TestNG test instance (may be {@code null})
     * @param testMethod   the reflective test method (may be {@code null})
     * @return a resolver or {@code null} if this provider does not apply
     */
    PropertyResolver tryBuild(Object testInstance, Method testMethod);

}
