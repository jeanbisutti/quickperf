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
import org.quickperf.config.SystemPropertyResolver;
import org.quickperf.testng.spi.fixtures.FixtureMarkers;
import org.quickperf.testng.spi.fixtures.TypeAProvider;
import org.quickperf.testng.spi.fixtures.TypeBProvider;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ProviderIterationOrderTest {

    @Test
    public void first_matching_provider_wins() {
        // GIVEN a TypeA test instance — TypeAProvider (declared before TypeBProvider) matches.
        FixtureMarkers.TypeA testInstance = new FixtureMarkers.TypeA();

        // WHEN
        PropertyResolver resolver = TestNGPropertyResolverLoader.INSTANCE.build(testInstance, null);

        // THEN the resolver returned is the one from TypeAProvider, not TypeBProvider.
        assertThat(resolver).isSameAs(TypeAProvider.RESOLVER_A);
    }

    @Test
    public void when_first_matching_provider_returns_null_next_is_used() {
        // GIVEN a TypeB test instance — TypeAProvider returns null, TypeBProvider matches.
        FixtureMarkers.TypeB testInstance = new FixtureMarkers.TypeB();

        // WHEN
        PropertyResolver resolver = TestNGPropertyResolverLoader.INSTANCE.build(testInstance, null);

        // THEN the resolver returned is the one from TypeBProvider.
        assertThat(resolver).isSameAs(TypeBProvider.RESOLVER_B);
    }

    @Test
    public void when_no_provider_matches_falls_back_to_system_property_resolver() {
        // GIVEN a test instance type none of the registered providers care about.
        Object testInstance = new Object();

        // WHEN
        PropertyResolver resolver = TestNGPropertyResolverLoader.INSTANCE.build(testInstance, null);

        // THEN the loader falls back to SystemPropertyResolver.
        assertThat(resolver).isSameAs(SystemPropertyResolver.INSTANCE);
    }

    @Test
    public void provider_failure_does_not_stop_iteration() {
        // GIVEN ThrowingProvider is registered first in the services file. It always throws.
        // A TypeA instance must still receive RESOLVER_A from TypeAProvider declared after it.
        FixtureMarkers.TypeA testInstance = new FixtureMarkers.TypeA();

        // WHEN
        PropertyResolver resolver = TestNGPropertyResolverLoader.INSTANCE.build(testInstance, null);

        // THEN the throw was swallowed and the next applicable provider supplied the resolver.
        assertThat(resolver).isSameAs(TypeAProvider.RESOLVER_A);
    }

}
