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
package org.quickperf.sql.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Database-agnostic alias of {@link ExpectMaxJdbcQueryExecution} that verifies
 * the maximum number of database query executions (<i>executeQuery</i>,
 * <i>executeUpdate</i>, <i>execute</i>, <i>executeLargeUpdate</i>,
 * <i>executeBatch</i>, <i>executeLargeBatch</i> and their R2DBC equivalents).
 *
 * <br><br>
 * Use this annotation in test code that targets either JDBC or R2DBC, or that
 * does not want to expose JDBC terminology in its API.
 *
 * @see ExpectMaxJdbcQueryExecution
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface ExpectMaxQueryExecution {

    /**
     * Specifies a <code>value</code> to cause the test method to fail if the number of query executions is greater.
     * Note that if left empty, the assumed value will be zero.
     */
    int value() default 0;

}
