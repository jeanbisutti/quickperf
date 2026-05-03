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
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class NullSafeLoaderTest {

    @Test
    public void build_with_null_arguments_falls_back_to_system_property_resolver() {
        // WHEN
        PropertyResolver resolver = TestNGPropertyResolverLoader.INSTANCE.build(null, null);

        // THEN no provider applies for null inputs, so the loader returns the fallback.
        assertThat(resolver).isSameAs(SystemPropertyResolver.INSTANCE);
    }

}
