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
package io.trino.sql.query;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.trino.sql.ir.ComparisonExpression;
import io.trino.sql.ir.GenericLiteral;
import io.trino.sql.ir.LogicalExpression;
import io.trino.sql.ir.SymbolReference;
import io.trino.sql.planner.assertions.BasePlanTest;
import io.trino.sql.planner.assertions.PlanMatchPattern;
import io.trino.sql.planner.plan.FilterNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.sql.ir.ComparisonExpression.Operator.GREATER_THAN;
import static io.trino.sql.ir.LogicalExpression.Operator.OR;
import static io.trino.sql.planner.LogicalPlanner.Stage.OPTIMIZED;
import static io.trino.sql.planner.assertions.PlanMatchPattern.anyTree;
import static io.trino.sql.planner.assertions.PlanMatchPattern.filter;
import static io.trino.sql.planner.assertions.PlanMatchPattern.tableScan;
import static io.trino.sql.planner.optimizations.PlanNodeSearcher.searchFrom;
import static org.assertj.core.api.Assertions.assertThat;

public class TestFilteredAggregations
        extends BasePlanTest
{
    private final QueryAssertions assertions = new QueryAssertions();

    @AfterAll
    public void teardown()
    {
        assertions.close();
    }

    @Test
    public void testAddPredicateForFilterClauses()
    {
        assertThat(assertions.query(
                "SELECT sum(x) FILTER(WHERE x > 0) FROM (VALUES 1, 1, 0, 2, 3, 3) t(x)"))
                .matches("VALUES (BIGINT '10')");

        assertThat(assertions.query(
                "SELECT sum(x) FILTER(WHERE x > 0), sum(x) FILTER(WHERE x < 3) FROM (VALUES 1, 1, 0, 5, 3, 8) t(x)"))
                .matches("VALUES (BIGINT '18', BIGINT '2')");

        assertThat(assertions.query(
                "SELECT sum(x) FILTER(WHERE x > 1), sum(x) FROM (VALUES 1, 1, 0, 2, 3, 3) t(x)"))
                .matches("VALUES (BIGINT '8', BIGINT '10')");
    }

    @Test
    public void testGroupAll()
    {
        assertThat(assertions.query(
                "SELECT count(DISTINCT x) FILTER (WHERE x > 1) " +
                        "FROM (VALUES 1, 1, 1, 2, 3, 3) t(x)"))
                .matches("VALUES BIGINT '2'");

        assertThat(assertions.query(
                "SELECT count(DISTINCT x) FILTER (WHERE x > 1), sum(DISTINCT x) " +
                        "FROM (VALUES 1, 1, 1, 2, 3, 3) t(x)"))
                .matches("VALUES (BIGINT '2', BIGINT '6')");

        assertThat(assertions.query(
                "SELECT count(DISTINCT x) FILTER (WHERE x > 1), sum(DISTINCT y) FILTER (WHERE x < 3)" +
                        "FROM (VALUES " +
                        "(1, 10)," +
                        "(1, 20)," +
                        "(1, 20)," +
                        "(2, 20)," +
                        "(3, 30)) t(x, y)"))
                .matches("VALUES (BIGINT '2', BIGINT '30')");

        assertThat(assertions.query(
                "SELECT count(x) FILTER (WHERE x > 1), sum(DISTINCT x) " +
                        "FROM (VALUES 1, 2, 3, 3) t(x)"))
                .matches("VALUES (BIGINT '3', BIGINT '6')");
    }

    @Test
    public void testGroupingSets()
    {
        assertThat(assertions.query(
                "SELECT k, count(DISTINCT x) FILTER (WHERE y = 100), count(DISTINCT x) FILTER (WHERE y = 200) FROM " +
                        "(VALUES " +
                        "   (1, 1, 100)," +
                        "   (1, 1, 200)," +
                        "   (1, 2, 100)," +
                        "   (1, 3, 300)," +
                        "   (2, 1, 100)," +
                        "   (2, 10, 100)," +
                        "   (2, 20, 100)," +
                        "   (2, 20, 200)," +
                        "   (2, 30, 300)," +
                        "   (2, 40, 100)" +
                        ") t(k, x, y) " +
                        "GROUP BY GROUPING SETS ((), (k))"))
                .matches("VALUES " +
                        "(1, BIGINT '2', BIGINT '1'), " +
                        "(2, BIGINT '4', BIGINT '1'), " +
                        "(CAST(NULL AS INTEGER), BIGINT '5', BIGINT '2')");
    }

    @Test
    public void rewriteAddFilterWithMultipleFilters()
    {
        PlanMatchPattern source = tableScan(
                "orders", ImmutableMap.of("totalprice", "totalprice",
                        "custkey", "custkey"));
        assertPlan(
                "SELECT sum(totalprice) FILTER(WHERE totalprice > 0), sum(custkey) FILTER(WHERE custkey > 0) FROM orders",
                anyTree(
                        filter(
                                new LogicalExpression(OR, ImmutableList.of(new ComparisonExpression(GREATER_THAN, new SymbolReference("totalprice"), GenericLiteral.constant(DOUBLE, 0.0)), new ComparisonExpression(GREATER_THAN, new SymbolReference("custkey"), GenericLiteral.constant(BIGINT, 0L)))),
                                source)));
    }

    @Test
    public void testDoNotPushdownPredicateIfNonFilteredAggregateIsPresent()
    {
        assertPlanContainsNoFilter("SELECT sum(totalprice) FILTER(WHERE totalprice > 0), sum(custkey) FROM orders");
    }

    @Test
    public void testPushDownConstantFilterPredicate()
    {
        assertPlanContainsNoFilter("SELECT sum(totalprice) FILTER(WHERE FALSE) FROM orders");

        assertPlanContainsNoFilter("SELECT sum(totalprice) FILTER(WHERE TRUE) FROM orders");
    }

    @Test
    public void testNoFilterAddedForConstantValueFilters()
    {
        assertPlanContainsNoFilter("SELECT sum(x) FILTER(WHERE x > 0) FROM (VALUES 1, 1, 0, 2, 3, 3) t(x) GROUP BY x");

        assertPlanContainsNoFilter("SELECT sum(totalprice) FILTER(WHERE totalprice > 0) FROM orders GROUP BY totalprice");
    }

    private void assertPlanContainsNoFilter(String sql)
    {
        assertThat(searchFrom(plan(sql, OPTIMIZED).getRoot())
                .whereIsInstanceOfAny(FilterNode.class)
                .matches())
                .describedAs("Unexpected node for query: " + sql)
                .isFalse();
    }
}
