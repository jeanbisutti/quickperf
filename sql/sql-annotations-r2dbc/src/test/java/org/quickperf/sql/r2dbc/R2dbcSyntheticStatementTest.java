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
package org.quickperf.sql.r2dbc;

import org.junit.Test;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class R2dbcSyntheticStatementTest {

    @Test
    public void result_set_metadata_returns_recorded_column_count() throws SQLException {
        ResultSet rs = R2dbcSyntheticStatement.resultWithColumnCount(7L);

        ResultSetMetaData metaData = rs.getMetaData();

        assertThat(metaData.getColumnCount()).isEqualTo(7);
    }

    @Test
    public void zero_column_count_is_supported() throws SQLException {
        ResultSet rs = R2dbcSyntheticStatement.resultWithColumnCount(0L);

        assertThat(rs.getMetaData().getColumnCount()).isZero();
    }

    @Test
    public void other_resultset_calls_throw_unsupported() {
        ResultSet rs = R2dbcSyntheticStatement.resultWithColumnCount(3L);

        assertThatThrownBy(rs::next)
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(rs::close)
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    public void other_metadata_calls_throw_unsupported() throws SQLException {
        ResultSetMetaData metaData = R2dbcSyntheticStatement.resultWithColumnCount(3L).getMetaData();

        assertThatThrownBy(() -> metaData.getColumnName(1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    public void hashCode_and_equals_match_proxy_identity() throws SQLException {
        ResultSet rs1 = R2dbcSyntheticStatement.resultWithColumnCount(1L);
        ResultSet rs2 = R2dbcSyntheticStatement.resultWithColumnCount(1L);

        assertThat(rs1).isEqualTo(rs1).isNotEqualTo(rs2);
        assertThat(rs1.hashCode()).isEqualTo(System.identityHashCode(rs1));
        assertThat(rs1.toString()).contains("columnCount=1");
    }
}
