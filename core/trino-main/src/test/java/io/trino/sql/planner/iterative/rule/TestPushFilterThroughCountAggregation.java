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
import io.trino.sql.ir.ArithmeticBinaryExpression;
import io.trino.sql.ir.ComparisonExpression;
import io.trino.sql.ir.GenericLiteral;
import io.trino.sql.ir.LogicalExpression;
import io.trino.sql.ir.SymbolReference;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.iterative.rule.PushFilterThroughCountAggregation.PushFilterThroughCountAggregationWithProject;
import io.trino.sql.planner.iterative.rule.PushFilterThroughCountAggregation.PushFilterThroughCountAggregationWithoutProject;
import io.trino.sql.planner.iterative.rule.test.BaseRuleTest;
import io.trino.sql.planner.iterative.rule.test.PlanBuilder;
import io.trino.sql.planner.plan.Assignments;
import org.junit.jupiter.api.Test;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.sql.ir.ArithmeticBinaryExpression.Operator.MODULUS;
import static io.trino.sql.ir.BooleanLiteral.TRUE_LITERAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.EQUAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.GREATER_THAN;
import static io.trino.sql.ir.ComparisonExpression.Operator.GREATER_THAN_OR_EQUAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.LESS_THAN;
import static io.trino.sql.ir.LogicalExpression.Operator.AND;
import static io.trino.sql.ir.LogicalExpression.Operator.OR;
import static io.trino.sql.planner.assertions.PlanMatchPattern.aggregation;
import static io.trino.sql.planner.assertions.PlanMatchPattern.aggregationFunction;
import static io.trino.sql.planner.assertions.PlanMatchPattern.expression;
import static io.trino.sql.planner.assertions.PlanMatchPattern.filter;
import static io.trino.sql.planner.assertions.PlanMatchPattern.project;
import static io.trino.sql.planner.assertions.PlanMatchPattern.values;

public class TestPushFilterThroughCountAggregation
        extends BaseRuleTest
{
    @Test
    public void testDoesNotFireWithNonGroupedAggregation()
    {
        tester().assertThat(new PushFilterThroughCountAggregationWithoutProject(tester().getPlannerContext()))
                .on(p -> {
                    Symbol g = p.symbol("g");
                    Symbol mask = p.symbol("mask");
                    Symbol count = p.symbol("count");
                    return p.filter(
                            new ComparisonExpression(GREATER_THAN, new SymbolReference("count"), GenericLiteral.constant(INTEGER, 0L)),
                            p.aggregation(builder -> builder
                                    .globalGrouping()
                                    .addAggregation(count, PlanBuilder.aggregation("count", ImmutableList.of()), ImmutableList.of(), mask)
                                    .source(p.values(g, mask))));
                })
                .doesNotFire();
    }

    @Test
    public void testDoesNotFireWithMultipleAggregations()
    {
        tester().assertThat(new PushFilterThroughCountAggregationWithoutProject(tester().getPlannerContext()))
                .on(p -> {
                    Symbol g = p.symbol("g");
                    Symbol mask = p.symbol("mask");
                    Symbol count = p.symbol("count");
                    Symbol avg = p.symbol("avg");
                    return p.filter(
                            new ComparisonExpression(GREATER_THAN, new SymbolReference("count"), GenericLiteral.constant(INTEGER, 0L)),
                            p.aggregation(builder -> builder
                                    .singleGroupingSet(g)
                                    .addAggregation(count, PlanBuilder.aggregation("count", ImmutableList.of()), ImmutableList.of(), mask)
                                    .addAggregation(avg, PlanBuilder.aggregation("avg", ImmutableList.of(new SymbolReference("g"))), ImmutableList.of(BIGINT), mask)
                                    .source(p.values(g, mask))));
                })
                .doesNotFire();
    }

    @Test
    public void testDoesNotFireWithNoAggregations()
    {
        tester().assertThat(new PushFilterThroughCountAggregationWithoutProject(tester().getPlannerContext()))
                .on(p -> {
                    Symbol g = p.symbol("g");
                    Symbol mask = p.symbol("mask");
                    return p.filter(
                            TRUE_LITERAL,
                            p.aggregation(builder -> builder
                                    .singleGroupingSet(g)
                                    .source(p.values(g, mask))));
                })
                .doesNotFire();
    }

    @Test
    public void testDoesNotFireWithNoMask()
    {
        tester().assertThat(new PushFilterThroughCountAggregationWithoutProject(tester().getPlannerContext()))
                .on(p -> {
                    Symbol g = p.symbol("g");
                    Symbol count = p.symbol("count");
                    return p.filter(
                            new ComparisonExpression(GREATER_THAN, new SymbolReference("count"), GenericLiteral.constant(INTEGER, 0L)),
                            p.aggregation(builder -> builder
                                    .singleGroupingSet(g)
                                    .addAggregation(count, PlanBuilder.aggregation("count", ImmutableList.of()), ImmutableList.of())
                                    .source(p.values(g))));
                })
                .doesNotFire();
    }

    @Test
    public void testDoesNotFireWithNoCountAggregation()
    {
        tester().assertThat(new PushFilterThroughCountAggregationWithoutProject(tester().getPlannerContext()))
                .on(p -> {
                    Symbol g = p.symbol("g");
                    Symbol mask = p.symbol("mask");
                    Symbol count = p.symbol("count");
                    return p.filter(
                            new ComparisonExpression(GREATER_THAN, new SymbolReference("count"), GenericLiteral.constant(INTEGER, 0L)),
                            p.aggregation(builder -> builder
                                    .singleGroupingSet(g)
                                    .addAggregation(count, PlanBuilder.aggregation("count", ImmutableList.of(new SymbolReference("g"))), ImmutableList.of(BIGINT), mask)
                                    .source(p.values(g, mask))));
                })
                .doesNotFire();

        tester().assertThat(new PushFilterThroughCountAggregationWithoutProject(tester().getPlannerContext()))
                .on(p -> {
                    Symbol g = p.symbol("g");
                    Symbol mask = p.symbol("mask");
                    Symbol avg = p.symbol("avg");
                    return p.filter(
                            new ComparisonExpression(GREATER_THAN, new SymbolReference("avg"), GenericLiteral.constant(INTEGER, 0L)),
                            p.aggregation(builder -> builder
                                    .singleGroupingSet(g)
                                    .addAggregation(avg, PlanBuilder.aggregation("avg", ImmutableList.of(new SymbolReference("g"))), ImmutableList.of(BIGINT), mask)
                                    .source(p.values(g, mask))));
                })
                .doesNotFire();
    }

    @Test
    public void testFilterPredicateFalse()
    {
        tester().assertThat(new PushFilterThroughCountAggregationWithoutProject(tester().getPlannerContext()))
                .on(p -> {
                    Symbol g = p.symbol("g");
                    Symbol mask = p.symbol("mask");
                    Symbol count = p.symbol("count");
                    return p.filter(
                            new LogicalExpression(AND, ImmutableList.of(new ComparisonExpression(LESS_THAN, new SymbolReference("count"), GenericLiteral.constant(BIGINT, 0L)), new ComparisonExpression(GREATER_THAN, new SymbolReference("count"), GenericLiteral.constant(BIGINT, 0L)))),
                            p.aggregation(builder -> builder
                                    .singleGroupingSet(g)
                                    .addAggregation(count, PlanBuilder.aggregation("count", ImmutableList.of()), ImmutableList.of(), mask)
                                    .source(p.values(g, mask))));
                })
                .matches(
                        values("g", "count"));
    }

    @Test
    public void testDoesNotFireWhenFilterPredicateTrue()
    {
        tester().assertThat(new PushFilterThroughCountAggregationWithoutProject(tester().getPlannerContext()))
                .on(p -> {
                    Symbol g = p.symbol("g");
                    Symbol mask = p.symbol("mask");
                    Symbol count = p.symbol("count");
                    return p.filter(
                            TRUE_LITERAL,
                            p.aggregation(builder -> builder
                                    .singleGroupingSet(g)
                                    .addAggregation(count, PlanBuilder.aggregation("count", ImmutableList.of()), ImmutableList.of(), mask)
                                    .source(p.values(g, mask))));
                })
                .doesNotFire();
    }

    @Test
    public void testDoesNotFireWhenFilterPredicateSatisfiedByAllCountValues()
    {
        tester().assertThat(new PushFilterThroughCountAggregationWithoutProject(tester().getPlannerContext()))
                .on(p -> {
                    Symbol g = p.symbol("g");
                    Symbol mask = p.symbol("mask");
                    Symbol count = p.symbol("count");
                    return p.filter(
                            new LogicalExpression(AND, ImmutableList.of(new LogicalExpression(OR, ImmutableList.of(new ComparisonExpression(LESS_THAN, new SymbolReference("count"), GenericLiteral.constant(BIGINT, 0L)), new ComparisonExpression(GREATER_THAN_OR_EQUAL, new SymbolReference("count"), GenericLiteral.constant(BIGINT, 0L)))), new ComparisonExpression(EQUAL, new SymbolReference("g"), GenericLiteral.constant(BIGINT, 5L)))),
                            p.aggregation(builder -> builder
                                    .singleGroupingSet(g)
                                    .addAggregation(count, PlanBuilder.aggregation("count", ImmutableList.of()), ImmutableList.of(), mask)
                                    .source(p.values(g, mask))));
                })
                .doesNotFire();
    }

    @Test
    public void testPushDownMaskAndRemoveFilter()
    {
        tester().assertThat(new PushFilterThroughCountAggregationWithoutProject(tester().getPlannerContext()))
                .on(p -> {
                    Symbol g = p.symbol("g");
                    Symbol mask = p.symbol("mask");
                    Symbol count = p.symbol("count");
                    return p.filter(
                            new ComparisonExpression(GREATER_THAN, new SymbolReference("count"), GenericLiteral.constant(BIGINT, 0L)),
                            p.aggregation(builder -> builder
                                    .singleGroupingSet(g)
                                    .addAggregation(count, PlanBuilder.aggregation("count", ImmutableList.of()), ImmutableList.of(), mask)
                                    .source(p.values(g, mask))));
                })
                .matches(
                        aggregation(
                                ImmutableMap.of("count", aggregationFunction("count", ImmutableList.of())),
                                filter(
                                        new SymbolReference("mask"),
                                        values("g", "mask"))));
    }

    @Test
    public void testPushDownMaskAndSimplifyFilter()
    {
        tester().assertThat(new PushFilterThroughCountAggregationWithoutProject(tester().getPlannerContext()))
                .on(p -> {
                    Symbol g = p.symbol("g");
                    Symbol mask = p.symbol("mask");
                    Symbol count = p.symbol("count");
                    return p.filter(
                            new LogicalExpression(AND, ImmutableList.of(new ComparisonExpression(GREATER_THAN, new SymbolReference("count"), GenericLiteral.constant(BIGINT, 0L)), new ComparisonExpression(GREATER_THAN, new SymbolReference("g"), GenericLiteral.constant(BIGINT, 5L)))),
                            p.aggregation(builder -> builder
                                    .singleGroupingSet(g)
                                    .addAggregation(count, PlanBuilder.aggregation("count", ImmutableList.of()), ImmutableList.of(), mask)
                                    .source(p.values(g, mask))));
                })
                .matches(
                        filter(
                                new ComparisonExpression(GREATER_THAN, new SymbolReference("g"), GenericLiteral.constant(BIGINT, 5L)),
                                aggregation(
                                        ImmutableMap.of("count", aggregationFunction("count", ImmutableList.of())),
                                        filter(
                                                new SymbolReference("mask"),
                                                values("g", "mask")))));

        tester().assertThat(new PushFilterThroughCountAggregationWithoutProject(tester().getPlannerContext()))
                .on(p -> {
                    Symbol g = p.symbol("g");
                    Symbol mask = p.symbol("mask");
                    Symbol count = p.symbol("count");
                    return p.filter(
                            new LogicalExpression(AND, ImmutableList.of(new ComparisonExpression(GREATER_THAN, new SymbolReference("count"), GenericLiteral.constant(BIGINT, 0L)), new ComparisonExpression(EQUAL, new ArithmeticBinaryExpression(MODULUS, new SymbolReference("count"), GenericLiteral.constant(INTEGER, 2L)), GenericLiteral.constant(BIGINT, 0L)))),
                            p.aggregation(builder -> builder
                                    .singleGroupingSet(g)
                                    .addAggregation(count, PlanBuilder.aggregation("count", ImmutableList.of()), ImmutableList.of(), mask)
                                    .source(p.values(g, mask))));
                })
                .matches(
                        filter(
                                new ComparisonExpression(EQUAL, new ArithmeticBinaryExpression(MODULUS, new SymbolReference("count"), GenericLiteral.constant(INTEGER, 2L)), GenericLiteral.constant(BIGINT, 0L)),
                                aggregation(
                                        ImmutableMap.of("count", aggregationFunction("count", ImmutableList.of())),
                                        filter(
                                                new SymbolReference("mask"),
                                                values("g", "mask")))));
    }

    @Test
    public void testPushDownMaskAndRetainFilter()
    {
        tester().assertThat(new PushFilterThroughCountAggregationWithoutProject(tester().getPlannerContext()))
                .on(p -> {
                    Symbol g = p.symbol("g");
                    Symbol mask = p.symbol("mask");
                    Symbol count = p.symbol("count");
                    return p.filter(
                            new ComparisonExpression(GREATER_THAN, new SymbolReference("count"), GenericLiteral.constant(BIGINT, 5L)),
                            p.aggregation(builder -> builder
                                    .singleGroupingSet(g)
                                    .addAggregation(count, PlanBuilder.aggregation("count", ImmutableList.of()), ImmutableList.of(), mask)
                                    .source(p.values(g, mask))));
                })
                .matches(
                        filter(
                                new ComparisonExpression(GREATER_THAN, new SymbolReference("count"), GenericLiteral.constant(BIGINT, 5L)),
                                aggregation(
                                        ImmutableMap.of("count", aggregationFunction("count", ImmutableList.of())),
                                        filter(
                                                new SymbolReference("mask"),
                                                values("g", "mask")))));
    }

    @Test
    public void testWithProject()
    {
        tester().assertThat(new PushFilterThroughCountAggregationWithProject(tester().getPlannerContext()))
                .on(p -> {
                    Symbol g = p.symbol("g");
                    Symbol mask = p.symbol("mask");
                    Symbol count = p.symbol("count");
                    return p.filter(
                            new ComparisonExpression(GREATER_THAN, new SymbolReference("count"), GenericLiteral.constant(BIGINT, 0L)),
                            p.project(
                                    Assignments.identity(count),
                                    p.aggregation(builder -> builder
                                            .singleGroupingSet(g)
                                            .addAggregation(count, PlanBuilder.aggregation("count", ImmutableList.of()), ImmutableList.of(), mask)
                                            .source(p.values(g, mask)))));
                })
                .matches(
                        project(
                                ImmutableMap.of("count", expression(new SymbolReference("count"))),
                                aggregation(
                                        ImmutableMap.of("count", aggregationFunction("count", ImmutableList.of())),
                                        filter(
                                                new SymbolReference("mask"),
                                                values("g", "mask")))));

        tester().assertThat(new PushFilterThroughCountAggregationWithProject(tester().getPlannerContext()))
                .on(p -> {
                    Symbol g = p.symbol("g");
                    Symbol mask = p.symbol("mask");
                    Symbol count = p.symbol("count");
                    return p.filter(
                            new LogicalExpression(AND, ImmutableList.of(new ComparisonExpression(GREATER_THAN, new SymbolReference("count"), GenericLiteral.constant(BIGINT, 0L)), new ComparisonExpression(GREATER_THAN, new SymbolReference("g"), GenericLiteral.constant(BIGINT, 5L)))),
                            p.project(
                                    Assignments.identity(count, g),
                                    p.aggregation(builder -> builder
                                            .singleGroupingSet(g)
                                            .addAggregation(count, PlanBuilder.aggregation("count", ImmutableList.of()), ImmutableList.of(), mask)
                                            .source(p.values(g, mask)))));
                })
                .matches(
                        filter(
                                new ComparisonExpression(GREATER_THAN, new SymbolReference("g"), GenericLiteral.constant(BIGINT, 5L)),
                                project(
                                        ImmutableMap.of("count", expression(new SymbolReference("count")), "g", expression(new SymbolReference("g"))),
                                        aggregation(
                                                ImmutableMap.of("count", aggregationFunction("count", ImmutableList.of())),
                                                filter(
                                                        new SymbolReference("mask"),
                                                        values("g", "mask"))))));

        tester().assertThat(new PushFilterThroughCountAggregationWithProject(tester().getPlannerContext()))
                .on(p -> {
                    Symbol g = p.symbol("g");
                    Symbol mask = p.symbol("mask");
                    Symbol count = p.symbol("count");
                    return p.filter(
                            new ComparisonExpression(GREATER_THAN, new SymbolReference("count"), GenericLiteral.constant(BIGINT, 5L)),
                            p.project(
                                    Assignments.identity(count),
                                    p.aggregation(builder -> builder
                                            .singleGroupingSet(g)
                                            .addAggregation(count, PlanBuilder.aggregation("count", ImmutableList.of()), ImmutableList.of(), mask)
                                            .source(p.values(g, mask)))));
                })
                .matches(
                        filter(
                                new ComparisonExpression(GREATER_THAN, new SymbolReference("count"), GenericLiteral.constant(BIGINT, 5L)),
                                project(
                                        ImmutableMap.of("count", expression(new SymbolReference("count"))),
                                        aggregation(
                                                ImmutableMap.of("count", aggregationFunction("count", ImmutableList.of())),
                                                filter(
                                                        new SymbolReference("mask"),
                                                        values("g", "mask"))))));
    }
}
