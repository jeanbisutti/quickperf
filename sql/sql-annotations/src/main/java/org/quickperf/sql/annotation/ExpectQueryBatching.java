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
 * Database-agnostic alias of {@link ExpectJdbcBatching} that verifies insert,
 * delete and update statements are processed through query batches of
 * <code>batchSize</code> elements.
 *
 * <br><br>
 * Use this annotation in test code that targets either JDBC or R2DBC, or that
 * does not want to expose JDBC terminology in its API.
 *
 * <br><br>
 * <h3>Example:</h3>
 * <pre>
 *      <b>&#064;ExpectQueryBatching(batchSize = 30)</b>
 *      public void insert_using_query_batching() {
 *          <code>..</code>
 *      }
 * </pre>
 *
 * @see ExpectJdbcBatching
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface ExpectQueryBatching {

    /**
     * Specifies a <code>batchSize</code> (integer) to cause the test method to fail if the used batch size is not
     * equal. A zero batch size means that query batching is <b><u>disabled</u></b>. With no given batch size value,
     * the annotation will still check that insert, delete and update statements are processed through query batches
     * (but the annotation will not check the batch size).
     */
    int batchSize() default -1;

}
