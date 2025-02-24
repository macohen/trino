/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.sql.planner.iterative.rule;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.trino.sql.ir.ComparisonExpression;
import io.trino.sql.ir.GenericLiteral;
import io.trino.sql.ir.SymbolReference;
import io.trino.sql.planner.iterative.rule.test.BaseRuleTest;
import io.trino.sql.planner.plan.AggregationNode;
import io.trino.sql.planner.plan.FilterNode;
import io.trino.sql.planner.plan.SortNode;
import io.trino.sql.planner.plan.ValuesNode;
import org.junit.jupiter.api.Test;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.sql.ir.ComparisonExpression.Operator.GREATER_THAN;
import static io.trino.sql.planner.assertions.PlanMatchPattern.node;
import static io.trino.sql.planner.assertions.PlanMatchPattern.values;
import static io.trino.sql.planner.iterative.rule.test.PlanBuilder.aggregation;

public class TestRemoveRedundantTopN
        extends BaseRuleTest
{
    @Test
    public void test()
    {
        tester().assertThat(new RemoveRedundantTopN())
                .on(p ->
                        p.topN(
                                10,
                                ImmutableList.of(p.symbol("c")),
                                p.aggregation(builder -> builder
                                        .addAggregation(p.symbol("c"), aggregation("count", ImmutableList.of(new SymbolReference("foo"))), ImmutableList.of(BIGINT))
                                        .globalGrouping()
                                        .source(p.values(p.symbol("foo"))))))
                .matches(
                        node(AggregationNode.class,
                                node(ValuesNode.class)));

        tester().assertThat(new RemoveRedundantTopN())
                .on(p ->
                        p.topN(
                                10,
                                ImmutableList.of(p.symbol("a")),
                                p.filter(
                                        new ComparisonExpression(GREATER_THAN, new SymbolReference("b"), GenericLiteral.constant(INTEGER, 5L)),
                                        p.values(
                                                ImmutableList.of(p.symbol("a"), p.symbol("b")),
                                                ImmutableList.of(
                                                        ImmutableList.of(GenericLiteral.constant(INTEGER, 1L), GenericLiteral.constant(INTEGER, 10L)),
                                                        ImmutableList.of(GenericLiteral.constant(INTEGER, 2L), GenericLiteral.constant(INTEGER, 11L)))))))
                // TODO: verify contents
                .matches(
                        node(SortNode.class,
                                node(FilterNode.class,
                                        node(ValuesNode.class))));
    }

    @Test
    public void testZeroTopN()
    {
        tester().assertThat(new RemoveRedundantTopN())
                .on(p ->
                        p.topN(
                                0,
                                ImmutableList.of(p.symbol("a")),
                                p.filter(
                                        new ComparisonExpression(GREATER_THAN, new SymbolReference("b"), GenericLiteral.constant(INTEGER, 5L)),
                                        p.values(
                                                ImmutableList.of(p.symbol("a"), p.symbol("b")),
                                                ImmutableList.of(
                                                        ImmutableList.of(GenericLiteral.constant(INTEGER, 1L), GenericLiteral.constant(INTEGER, 10L)),
                                                        ImmutableList.of(GenericLiteral.constant(INTEGER, 2L), GenericLiteral.constant(INTEGER, 11L)))))))
                // TODO: verify contents
                .matches(values(ImmutableMap.of()));
    }

    @Test
    public void doesNotFire()
    {
        tester().assertThat(new RemoveRedundantTopN())
                .on(p ->
                        p.topN(
                                10,
                                ImmutableList.of(p.symbol("c")),
                                p.aggregation(builder -> builder
                                        .addAggregation(p.symbol("c"), aggregation("count", ImmutableList.of(new SymbolReference("foo"))), ImmutableList.of(BIGINT))
                                        .singleGroupingSet(p.symbol("foo"))
                                        .source(p.values(20, p.symbol("foo"))))))
                .doesNotFire();
    }
}
