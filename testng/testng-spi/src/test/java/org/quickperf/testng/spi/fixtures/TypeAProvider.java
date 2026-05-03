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
package org.quickperf.testng.spi.fixtures;

import org.quickperf.config.PropertyResolver;
import org.quickperf.testng.spi.PropertyResolverProvider;

import java.lang.reflect.Method;

/**
 * Test fixture provider that returns {@link #RESOLVER_A} when the test instance
 * is a {@link FixtureMarkers.TypeA}, and {@code null} otherwise.
 */
public class TypeAProvider implements PropertyResolverProvider {

    public static final PropertyResolver RESOLVER_A = new PropertyResolver() {
        @Override
        public String resolve(String propertyName) {
            return "A:" + propertyName;
        }
    };

    @Override
    public PropertyResolver tryBuild(Object testInstance, Method testMethod) {
        if (testInstance instanceof FixtureMarkers.TypeA) {
            return RESOLVER_A;
        }
        return null;
    }

}
