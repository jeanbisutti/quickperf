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
package org.quickperf.config;

/**
 * Abstraction over a property source so that QuickPerf can read the same logical
 * property name from either Spring's {@code Environment} or the JVM system
 * properties without coupling core to Spring.
 *
 * <p>Implementations must return {@code null} when the property is not defined,
 * so callers can fall back to {@link org.quickperf.SystemProperties} as needed.
 *
 * <p>This interface is single-abstract-method, Java-7-safe (no default methods,
 * no lambdas).
 */
public interface PropertyResolver {

    /**
     * @param propertyName the property name, e.g. {@code "disableQuickPerf"}
     * @return the property value, or {@code null} if not defined
     */
    String resolve(String propertyName);

}
