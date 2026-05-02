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
package org.quickperf.junit5.spi;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.quickperf.config.PropertyResolver;

/**
 * SPI loaded via {@link java.util.ServiceLoader} that lets framework
 * integrations (e.g. Spring) contribute a {@link PropertyResolver}
 * for a JUnit 5 test, without {@code junit5-extension} compile-depending
 * on those frameworks.
 *
 * <p>Implementations should return {@code null} when they cannot build
 * a resolver for the given context (e.g. when no Spring application
 * context is available); the caller will then fall back to system
 * properties.
 */
public interface PropertyResolverProvider {

    /**
     * Returns a resolver appropriate for the given JUnit 5 extension
     * context, or {@code null} if this provider does not apply.
     */
    PropertyResolver tryBuild(ExtensionContext extensionContext);

}
