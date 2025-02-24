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
package io.trino.sql.planner;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.trino.Session;
import io.trino.cost.StatsProvider;
import io.trino.metadata.Metadata;
import io.trino.operator.RetryPolicy;
import io.trino.sql.DynamicFilters;
import io.trino.sql.ir.ArithmeticBinaryExpression;
import io.trino.sql.ir.BetweenPredicate;
import io.trino.sql.ir.ComparisonExpression;
import io.trino.sql.ir.GenericLiteral;
import io.trino.sql.ir.LogicalExpression;
import io.trino.sql.ir.SymbolReference;
import io.trino.sql.planner.OptimizerConfig.JoinDistributionType;
import io.trino.sql.planner.OptimizerConfig.JoinReorderingStrategy;
import io.trino.sql.planner.assertions.BasePlanTest;
import io.trino.sql.planner.assertions.MatchResult;
import io.trino.sql.planner.assertions.Matcher;
import io.trino.sql.planner.assertions.SymbolAliases;
import io.trino.sql.planner.plan.DynamicFilterSourceNode;
import io.trino.sql.planner.plan.FilterNode;
import io.trino.sql.planner.plan.PlanNode;
import io.trino.sql.planner.plan.SemiJoinNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;

import static io.trino.SystemSessionProperties.ENABLE_DYNAMIC_FILTERING;
import static io.trino.SystemSessionProperties.FILTERING_SEMI_JOIN_TO_INNER;
import static io.trino.SystemSessionProperties.JOIN_DISTRIBUTION_TYPE;
import static io.trino.SystemSessionProperties.JOIN_REORDERING_STRATEGY;
import static io.trino.SystemSessionProperties.RETRY_POLICY;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.sql.ir.ArithmeticBinaryExpression.Operator.MODULUS;
import static io.trino.sql.ir.ArithmeticBinaryExpression.Operator.SUBTRACT;
import static io.trino.sql.ir.BooleanLiteral.TRUE_LITERAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.EQUAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.GREATER_THAN_OR_EQUAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.LESS_THAN_OR_EQUAL;
import static io.trino.sql.ir.LogicalExpression.Operator.AND;
import static io.trino.sql.planner.OptimizerConfig.JoinDistributionType.BROADCAST;
import static io.trino.sql.planner.OptimizerConfig.JoinDistributionType.PARTITIONED;
import static io.trino.sql.planner.assertions.PlanMatchPattern.DynamicFilterPattern;
import static io.trino.sql.planner.assertions.PlanMatchPattern.anyTree;
import static io.trino.sql.planner.assertions.PlanMatchPattern.exchange;
import static io.trino.sql.planner.assertions.PlanMatchPattern.expression;
import static io.trino.sql.planner.assertions.PlanMatchPattern.filter;
import static io.trino.sql.planner.assertions.PlanMatchPattern.join;
import static io.trino.sql.planner.assertions.PlanMatchPattern.node;
import static io.trino.sql.planner.assertions.PlanMatchPattern.project;
import static io.trino.sql.planner.assertions.PlanMatchPattern.semiJoin;
import static io.trino.sql.planner.assertions.PlanMatchPattern.tableScan;
import static io.trino.sql.planner.plan.ExchangeNode.Scope.LOCAL;
import static io.trino.sql.planner.plan.ExchangeNode.Scope.REMOTE;
import static io.trino.sql.planner.plan.ExchangeNode.Type.REPARTITION;
import static io.trino.sql.planner.plan.ExchangeNode.Type.REPLICATE;
import static io.trino.sql.planner.plan.JoinType.INNER;

public class TestAddDynamicFilterSource
        extends BasePlanTest
{
    public TestAddDynamicFilterSource()
    {
        super(ImmutableMap.of(
                RETRY_POLICY, RetryPolicy.TASK.name(),
                ENABLE_DYNAMIC_FILTERING, "true",
                JOIN_REORDERING_STRATEGY, JoinReorderingStrategy.NONE.name()));
    }

    @Test
    public void testBroadcastInnerJoin()
    {
        assertDistributedPlan(
                "SELECT l.suppkey FROM lineitem l, supplier s WHERE l.suppkey = s.suppkey",
                withJoinDistributionType(BROADCAST),
                anyTree(
                        join(INNER, builder -> builder
                                .equiCriteria("LINEITEM_SK", "SUPPLIER_SK")
                                .dynamicFilter("LINEITEM_SK", "SUPPLIER_SK")
                                .left(
                                        node(
                                                FilterNode.class,
                                                tableScan("lineitem", ImmutableMap.of("LINEITEM_SK", "suppkey")))
                                                .with(numberOfDynamicFilters(1)))
                                .right(
                                        exchange(
                                                LOCAL,
                                                exchange(
                                                        REMOTE,
                                                        REPLICATE,
                                                        node(
                                                                DynamicFilterSourceNode.class,
                                                                tableScan("supplier", ImmutableMap.of("SUPPLIER_SK", "suppkey")))))))));
    }

    @Test
    public void testPartitionedInnerJoin()
    {
        assertDistributedPlan(
                "SELECT l.suppkey FROM lineitem l, supplier s WHERE l.suppkey = s.suppkey",
                withJoinDistributionType(PARTITIONED),
                anyTree(
                        join(INNER, builder -> builder
                                .equiCriteria("LINEITEM_SK", "SUPPLIER_SK")
                                .dynamicFilter("LINEITEM_SK", "SUPPLIER_SK")
                                .left(
                                        exchange(
                                                REMOTE,
                                                REPARTITION,
                                                node(
                                                        FilterNode.class,
                                                        tableScan("lineitem", ImmutableMap.of("LINEITEM_SK", "suppkey")))
                                                        .with(numberOfDynamicFilters(1))))
                                .right(
                                        exchange(
                                                LOCAL,
                                                exchange(
                                                        REMOTE,
                                                        REPARTITION,
                                                        node(
                                                                DynamicFilterSourceNode.class,
                                                                tableScan("supplier", ImmutableMap.of("SUPPLIER_SK", "suppkey")))))))));
    }

    @Test
    public void testSemiJoin()
    {
        for (JoinDistributionType joinDistributionType : Arrays.asList(BROADCAST, PARTITIONED)) {
            SemiJoinNode.DistributionType semiJoinDistributionType = joinDistributionType == PARTITIONED
                    ? SemiJoinNode.DistributionType.PARTITIONED
                    : SemiJoinNode.DistributionType.REPLICATED;
            assertDistributedPlan(
                    "SELECT * FROM orders WHERE orderkey IN (SELECT orderkey FROM lineitem WHERE linenumber % 4 = 0)",
                    noSemiJoinRewrite(joinDistributionType),
                    anyTree(
                            filter(
                                    new SymbolReference("S"),
                                    semiJoin("X", "Y", "S", Optional.of(semiJoinDistributionType), Optional.of(true),
                                            node(
                                                    FilterNode.class,
                                                    tableScan("orders", ImmutableMap.of("X", "orderkey")))
                                                    .with(numberOfDynamicFilters(1)),
                                            exchange(
                                                    LOCAL,
                                                    exchange(
                                                            REMOTE,
                                                            joinDistributionType == PARTITIONED ? REPARTITION : REPLICATE,
                                                            node(
                                                                    DynamicFilterSourceNode.class,
                                                                    project(
                                                                            filter(
                                                                                    new ComparisonExpression(EQUAL, new ArithmeticBinaryExpression(MODULUS, new SymbolReference("Z"), GenericLiteral.constant(INTEGER, 4L)), GenericLiteral.constant(INTEGER, 0L)),
                                                                                    tableScan("lineitem", ImmutableMap.of("Y", "orderkey", "Z", "linenumber")))))))))));
        }
    }

    @Test
    public void testInnerJoinWithUnionAllOnBuild()
    {
        assertDistributedPlan(
                "SELECT l.suppkey FROM lineitem l JOIN (SELECT suppkey FROM supplier UNION ALL SELECT suppkey FROM supplier) s ON l.suppkey = s.suppkey",
                withJoinDistributionType(BROADCAST),
                anyTree(
                        join(INNER, builder -> builder
                                .equiCriteria("LINEITEM_SK", "SUPPLIER_SK")
                                .dynamicFilter("LINEITEM_SK", "SUPPLIER_SK")
                                .left(
                                        anyTree(
                                                tableScan("lineitem", ImmutableMap.of("LINEITEM_SK", "suppkey"))))
                                .right(
                                        exchange(
                                                LOCAL,
                                                exchange(
                                                        REMOTE,
                                                        REPLICATE,
                                                        node(
                                                                DynamicFilterSourceNode.class,
                                                                exchange(
                                                                        LOCAL,
                                                                        Optional.empty(),
                                                                        Optional.empty(),
                                                                        ImmutableList.of(),
                                                                        ImmutableSet.of(),
                                                                        Optional.empty(),
                                                                        ImmutableList.of("SUPPLIER_SK"),
                                                                        Optional.empty(),
                                                                        tableScan("supplier", ImmutableMap.of("SUPPLIER_SK_1", "suppkey")),
                                                                        tableScan("supplier", ImmutableMap.of("SUPPLIER_SK_2", "suppkey"))))))))));

        // TODO: Add support for cases where the build side has multiple sources
        assertDistributedPlan(
                "SELECT l.suppkey FROM lineitem l JOIN (SELECT suppkey FROM supplier UNION ALL SELECT suppkey FROM supplier) s ON l.suppkey = s.suppkey",
                withJoinDistributionType(PARTITIONED),
                anyTree(
                        join(INNER, builder -> builder
                                .equiCriteria("LINEITEM_SK", "SUPPLIER_SK")
                                .left(
                                        anyTree(
                                                tableScan("lineitem", ImmutableMap.of("LINEITEM_SK", "suppkey"))))
                                .right(
                                        exchange(
                                                LOCAL,
                                                Optional.empty(),
                                                Optional.empty(),
                                                ImmutableList.of(),
                                                ImmutableSet.of(),
                                                Optional.empty(),
                                                ImmutableList.of("SUPPLIER_SK"),
                                                Optional.empty(),
                                                exchange(tableScan("supplier", ImmutableMap.of("SUPPLIER_SK_1", "suppkey"))),
                                                exchange(tableScan("supplier", ImmutableMap.of("SUPPLIER_SK_2", "suppkey"))))))));
    }

    @Test
    public void testCrossJoinInequality()
    {
        assertDistributedPlan(
                "SELECT o.orderkey FROM orders o, lineitem l WHERE o.orderkey BETWEEN l.orderkey AND l.partkey",
                anyTree(
                        filter(
                                new BetweenPredicate(new SymbolReference("O_ORDERKEY"), new SymbolReference("L_ORDERKEY"), new SymbolReference("L_PARTKEY")),
                                join(INNER, builder -> builder
                                        .dynamicFilter(ImmutableList.of(
                                                new DynamicFilterPattern(new SymbolReference("L_ORDERKEY"), LESS_THAN_OR_EQUAL, "O_ORDERKEY"),
                                                new DynamicFilterPattern(new SymbolReference("L_PARTKEY"), GREATER_THAN_OR_EQUAL, "O_ORDERKEY")))
                                        .left(
                                                filter(
                                                        TRUE_LITERAL,
                                                        tableScan("lineitem", ImmutableMap.of("L_ORDERKEY", "orderkey", "L_PARTKEY", "partkey"))))
                                        .right(
                                                exchange(
                                                        LOCAL,
                                                        exchange(
                                                                REMOTE,
                                                                node(DynamicFilterSourceNode.class,
                                                                        tableScan("orders", ImmutableMap.of("O_ORDERKEY", "orderkey"))))))))));

        // TODO: Add support for dynamic filters in the below case
        assertDistributedPlan(
                "SELECT o.orderkey FROM orders o, lineitem l WHERE o.orderkey >= l.orderkey AND o.orderkey <= l.partkey - 1",
                withJoinDistributionType(PARTITIONED),
                anyTree(
                        filter(
                                new LogicalExpression(AND, ImmutableList.of(new ComparisonExpression(GREATER_THAN_OR_EQUAL, new SymbolReference("O_ORDERKEY"), new SymbolReference("L_ORDERKEY")), new ComparisonExpression(LESS_THAN_OR_EQUAL, new SymbolReference("O_ORDERKEY"), new SymbolReference("expr")))),
                                join(INNER, builder -> builder
                                        .left(
                                                tableScan("orders", ImmutableMap.of("O_ORDERKEY", "orderkey")))
                                        .right(
                                                exchange(
                                                        LOCAL,
                                                        project(
                                                                ImmutableMap.of("expr", expression(new ArithmeticBinaryExpression(SUBTRACT, new SymbolReference("L_PARTKEY"), GenericLiteral.constant(BIGINT, 1L)))),
                                                                exchange(
                                                                        REMOTE,
                                                                        tableScan("lineitem", ImmutableMap.of("L_ORDERKEY", "orderkey", "L_PARTKEY", "partkey"))))))))));
    }

    @Test
    public void testJoinWithPrePartitionedBuild()
    {
        // TODO: Add support for dynamic filters in the below case
        assertDistributedPlan(
                "SELECT * FROM lineitem JOIN (SELECT suppkey FROM supplier GROUP BY 1) s ON lineitem.suppkey = s.suppkey",
                withJoinDistributionType(PARTITIONED),
                anyTree(
                        join(INNER, builder -> builder
                                .equiCriteria("LINEITEM_SK", "SUPPLIER_SK")
                                .left(
                                        exchange(
                                                REMOTE,
                                                REPARTITION,
                                                tableScan("lineitem", ImmutableMap.of("LINEITEM_SK", "suppkey"))))
                                .right(
                                        anyTree(
                                                tableScan("supplier", ImmutableMap.of("SUPPLIER_SK", "suppkey")))))));
    }

    private Matcher numberOfDynamicFilters(int numberOfDynamicFilters)
    {
        return new Matcher()
        {
            @Override
            public boolean shapeMatches(PlanNode node)
            {
                return node instanceof FilterNode;
            }

            @Override
            public MatchResult detailMatches(PlanNode node, StatsProvider stats, Session session, Metadata metadata, SymbolAliases symbolAliases)
            {
                FilterNode filterNode = (FilterNode) node;
                return new MatchResult(DynamicFilters.extractDynamicFilters(filterNode.getPredicate()).getDynamicConjuncts().size() == numberOfDynamicFilters);
            }
        };
    }

    private Session noSemiJoinRewrite(JoinDistributionType distributionType)
    {
        return Session.builder(getPlanTester().getDefaultSession())
                .setSystemProperty(FILTERING_SEMI_JOIN_TO_INNER, "false")
                .setSystemProperty(JOIN_DISTRIBUTION_TYPE, distributionType.name())
                .build();
    }

    private Session withJoinDistributionType(JoinDistributionType distributionType)
    {
        return Session.builder(getPlanTester().getDefaultSession())
                .setSystemProperty(JOIN_REORDERING_STRATEGY, JoinReorderingStrategy.NONE.name())
                .setSystemProperty(JOIN_DISTRIBUTION_TYPE, distributionType.name())
                .build();
    }
}
