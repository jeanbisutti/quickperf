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
 * {@link PropertyResolver} backed by {@link System#getProperty(String)}.
 *
 * <p>Used as the default for non-Spring callers (plain JUnit 4, plain JUnit 5,
 * TestNG) and as a fallback when a Spring-backed resolver returns {@code null}.
 */
public final class SystemPropertyResolver implements PropertyResolver {

    public static final SystemPropertyResolver INSTANCE = new SystemPropertyResolver();

    private SystemPropertyResolver() {}

    @Override
    public String resolve(String propertyName) {
        return System.getProperty(propertyName);
    }

}
