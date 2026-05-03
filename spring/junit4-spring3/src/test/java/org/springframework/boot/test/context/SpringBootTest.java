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
package org.springframework.boot.test.context;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test-only stub of Spring Boot's {@code @SpringBootTest} annotation, declared
 * with the same fully qualified name. The {@code QuickPerfSpringRunner}
 * fork-parent property resolver reads {@code properties()} reflectively by
 * looking up an annotation whose type name matches
 * {@code org.springframework.boot.test.context.SpringBootTest}, which lets us
 * exercise that code path here without having to put {@code spring-boot} on
 * the classpath of the {@code junit4-spring3} module.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SpringBootTest {

    String[] properties() default {};

}
