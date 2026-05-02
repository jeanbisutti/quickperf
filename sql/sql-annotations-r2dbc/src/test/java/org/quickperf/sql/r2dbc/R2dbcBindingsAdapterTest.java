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

import io.r2dbc.proxy.core.Bindings;
import io.r2dbc.proxy.core.BoundValue;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class R2dbcBindingsAdapterTest {

    @Test
    public void positional_index_lookup_by_zero_based_key_returns_bound_value() {
        Bindings b = new Bindings();
        b.addIndexBinding(Bindings.indexBinding(0, BoundValue.value("hello")));
        b.addIndexBinding(Bindings.indexBinding(1, BoundValue.value(42)));

        List<ParameterSetOperation> ops =
                R2dbcBindingsAdapter.toParameterSet(b, Arrays.asList("0", "1"));

        assertThat(ops).hasSize(2);
        assertThat(ops.get(0).getArgs()).containsExactly(1, "hello");
        assertThat(ops.get(1).getArgs()).containsExactly(2, 42);
    }

    @Test
    public void named_lookup_uses_source_order_not_sorted_set_alphabetical_order() {
        // Source order is z, a — but Bindings.getNamedBindings() returns alphabetical (a, z).
        // The adapter must follow the orderedKeys list, not the SortedSet iteration order.
        Bindings b = new Bindings();
        b.addNamedBinding(Bindings.namedBinding("a", BoundValue.value("alpha")));
        b.addNamedBinding(Bindings.namedBinding("z", BoundValue.value("zulu")));

        List<ParameterSetOperation> ops =
                R2dbcBindingsAdapter.toParameterSet(b, Arrays.asList("z", "a"));

        assertThat(ops.get(0).getArgs()).containsExactly(1, "zulu");
        assertThat(ops.get(1).getArgs()).containsExactly(2, "alpha");
    }

    @Test
    public void null_bound_value_is_unwrapped_to_java_null() {
        Bindings b = new Bindings();
        b.addNamedBinding(Bindings.namedBinding("x", BoundValue.nullValue(Object.class)));

        List<ParameterSetOperation> ops =
                R2dbcBindingsAdapter.toParameterSet(b, Arrays.asList("x"));

        assertThat(ops.get(0).getArgs()).containsExactly(1, null);
    }

    @Test
    public void missing_key_falls_back_to_null_value_rather_than_throwing() {
        Bindings b = new Bindings();
        b.addNamedBinding(Bindings.namedBinding("present", BoundValue.value("ok")));

        List<ParameterSetOperation> ops =
                R2dbcBindingsAdapter.toParameterSet(b, Arrays.asList("missing"));

        assertThat(ops.get(0).getArgs()).containsExactly(1, null);
    }

    @Test
    public void empty_ordered_keys_returns_empty_list() {
        Bindings b = new Bindings();
        b.addNamedBinding(Bindings.namedBinding("x", BoundValue.value("anything")));

        List<ParameterSetOperation> ops =
                R2dbcBindingsAdapter.toParameterSet(b, java.util.Collections.<String>emptyList());

        assertThat(ops).isEmpty();
    }

    @Test
    public void to_parameters_list_handles_one_bindings_per_batch_row() {
        Bindings row1 = new Bindings();
        row1.addNamedBinding(Bindings.namedBinding("x", BoundValue.value(1)));
        Bindings row2 = new Bindings();
        row2.addNamedBinding(Bindings.namedBinding("x", BoundValue.value(2)));

        List<List<ParameterSetOperation>> all = R2dbcBindingsAdapter.toParametersList(
                Arrays.asList(row1, row2), Arrays.asList("x"));

        assertThat(all).hasSize(2);
        assertThat(all.get(0).get(0).getArgs()).containsExactly(1, 1);
        assertThat(all.get(1).get(0).getArgs()).containsExactly(1, 2);
    }

}
