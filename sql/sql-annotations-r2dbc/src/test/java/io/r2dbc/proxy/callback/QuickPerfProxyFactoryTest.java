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
package io.r2dbc.proxy.callback;

import io.r2dbc.proxy.core.ConnectionInfo;
import io.r2dbc.proxy.core.QueryExecutionInfo;
import io.r2dbc.proxy.core.StatementInfo;
import io.r2dbc.proxy.test.MockConnectionInfo;
import io.r2dbc.proxy.test.MockQueryExecutionInfo;
import io.r2dbc.spi.Batch;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.Statement;
import org.junit.Test;
import org.quickperf.sql.r2dbc.QuickPerfMonitoringResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class QuickPerfProxyFactoryTest {

    private final QuickPerfProxyFactory factory = new QuickPerfProxyFactory(new ProxyConfig());

    @Test
    public void wrapConnectionFactory_delegates_to_jdk_proxy_factory() {
        ConnectionFactory cf = mock(ConnectionFactory.class);

        ConnectionFactory wrapped = factory.wrapConnectionFactory(cf);

        assertThat(wrapped).isNotNull().isNotSameAs(cf);
    }

    @Test
    public void wrapConnection_delegates_to_jdk_proxy_factory() {
        Connection conn = mock(Connection.class);
        ConnectionInfo info = MockConnectionInfo.builder().connectionId("c-1").build();

        Connection wrapped = factory.wrapConnection(conn, info);

        assertThat(wrapped).isNotNull().isNotSameAs(conn);
    }

    @Test
    public void wrapBatch_delegates_to_jdk_proxy_factory() {
        Batch batch = mock(Batch.class);
        ConnectionInfo info = MockConnectionInfo.builder().connectionId("c-1").build();

        Batch wrapped = factory.wrapBatch(batch, info);

        assertThat(wrapped).isNotNull();
    }

    @Test
    public void wrapStatement_delegates_to_jdk_proxy_factory() {
        Statement statement = mock(Statement.class);
        ConnectionInfo connInfo = MockConnectionInfo.builder().connectionId("c-1").build();
        StatementInfo stmtInfo = mock(StatementInfo.class);

        Statement wrapped = factory.wrapStatement(statement, stmtInfo, connInfo);

        assertThat(wrapped).isNotNull();
    }

    @Test
    public void wrapResult_returns_a_quickperf_monitoring_result() {
        Result raw = mock(Result.class);
        ConnectionInfo connInfo = MockConnectionInfo.builder().connectionId("c-1").build();
        // JdkProxyFactory casts QueryExecutionInfo to MutableQueryExecutionInfo, so the mock
        // builder is not enough. We are in r2dbc-proxy's package and can use it directly.
        MutableQueryExecutionInfo qei = new MutableQueryExecutionInfo();
        qei.setConnectionInfo(connInfo);
        QueriesExecutionContext context = new QueriesExecutionContext(java.time.Clock.systemUTC());

        Result wrapped = factory.wrapResult(raw, qei, context);

        assertThat(wrapped).isInstanceOf(QuickPerfMonitoringResult.class);
    }

    @Test
    public void wrapRow_delegates_to_jdk_proxy_factory() {
        Row row = mock(Row.class);
        QueryExecutionInfo qei = MockQueryExecutionInfo.builder().build();

        Row wrapped = factory.wrapRow(row, qei);

        assertThat(wrapped).isNotNull();
    }

    @Test
    public void wrapRowSegment_delegates_to_jdk_proxy_factory() {
        Result.RowSegment rs = mock(Result.RowSegment.class);
        QueryExecutionInfo qei = MockQueryExecutionInfo.builder().build();

        Result.RowSegment wrapped = factory.wrapRowSegment(rs, qei);

        assertThat(wrapped).isNotNull();
    }

    @Test
    public void factory_factory_creates_quickperf_proxy_factory() {
        QuickPerfProxyFactoryFactory ff = new QuickPerfProxyFactoryFactory();

        ProxyFactory created = ff.create(new ProxyConfig());

        assertThat(created).isInstanceOf(QuickPerfProxyFactory.class);
    }

    @Test
    public void proxy_config_with_factory_factory_produces_quickperf_proxy_factory() {
        ProxyConfig pc = ProxyConfig.builder()
                .proxyFactoryFactory(new QuickPerfProxyFactoryFactory())
                .build();

        assertThat(pc.getProxyFactory()).isInstanceOf(QuickPerfProxyFactory.class);
    }
}
