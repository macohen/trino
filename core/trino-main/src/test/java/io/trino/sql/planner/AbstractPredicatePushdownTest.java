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
import io.trino.Session;
import io.trino.sql.ir.ArithmeticBinaryExpression;
import io.trino.sql.ir.Cast;
import io.trino.sql.ir.ComparisonExpression;
import io.trino.sql.ir.FunctionCall;
import io.trino.sql.ir.GenericLiteral;
import io.trino.sql.ir.InPredicate;
import io.trino.sql.ir.LogicalExpression;
import io.trino.sql.ir.StringLiteral;
import io.trino.sql.ir.SymbolReference;
import io.trino.sql.planner.assertions.BasePlanTest;
import io.trino.sql.planner.assertions.PlanMatchPattern;
import io.trino.sql.planner.optimizations.PlanOptimizer;
import io.trino.sql.planner.plan.ExchangeNode;
import io.trino.sql.planner.plan.JoinNode;
import io.trino.sql.planner.plan.ProjectNode;
import io.trino.sql.planner.plan.WindowNode;
import io.trino.sql.tree.QualifiedName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.trino.SystemSessionProperties.ENABLE_DYNAMIC_FILTERING;
import static io.trino.SystemSessionProperties.FILTERING_SEMI_JOIN_TO_INNER;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.VarcharType.createVarcharType;
import static io.trino.sql.ir.ArithmeticBinaryExpression.Operator.ADD;
import static io.trino.sql.ir.ArithmeticBinaryExpression.Operator.DIVIDE;
import static io.trino.sql.ir.ArithmeticBinaryExpression.Operator.MULTIPLY;
import static io.trino.sql.ir.ArithmeticBinaryExpression.Operator.SUBTRACT;
import static io.trino.sql.ir.BooleanLiteral.TRUE_LITERAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.EQUAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.GREATER_THAN;
import static io.trino.sql.ir.ComparisonExpression.Operator.LESS_THAN;
import static io.trino.sql.ir.ComparisonExpression.Operator.NOT_EQUAL;
import static io.trino.sql.ir.LogicalExpression.Operator.AND;
import static io.trino.sql.ir.LogicalExpression.Operator.OR;
import static io.trino.sql.planner.assertions.PlanMatchPattern.anyTree;
import static io.trino.sql.planner.assertions.PlanMatchPattern.assignUniqueId;
import static io.trino.sql.planner.assertions.PlanMatchPattern.expression;
import static io.trino.sql.planner.assertions.PlanMatchPattern.filter;
import static io.trino.sql.planner.assertions.PlanMatchPattern.join;
import static io.trino.sql.planner.assertions.PlanMatchPattern.node;
import static io.trino.sql.planner.assertions.PlanMatchPattern.output;
import static io.trino.sql.planner.assertions.PlanMatchPattern.project;
import static io.trino.sql.planner.assertions.PlanMatchPattern.semiJoin;
import static io.trino.sql.planner.assertions.PlanMatchPattern.tableScan;
import static io.trino.sql.planner.assertions.PlanMatchPattern.values;
import static io.trino.sql.planner.plan.JoinType.INNER;
import static io.trino.sql.planner.plan.JoinType.LEFT;

public abstract class AbstractPredicatePushdownTest
        extends BasePlanTest
{
    private final boolean enableDynamicFiltering;

    protected AbstractPredicatePushdownTest(boolean enableDynamicFiltering)
    {
        super(ImmutableMap.of(ENABLE_DYNAMIC_FILTERING, Boolean.toString(enableDynamicFiltering)));
        this.enableDynamicFiltering = enableDynamicFiltering;
    }

    @Test
    public abstract void testCoercions();

    @Test
    public void testPushDownToLhsOfSemiJoin()
    {
        assertPlan("SELECT quantity FROM (SELECT * FROM lineitem WHERE orderkey IN (SELECT orderkey FROM orders)) " +
                        "WHERE linenumber = 2",
                noSemiJoinRewrite(),
                anyTree(
                        semiJoin("LINE_ORDER_KEY", "ORDERS_ORDER_KEY", "SEMI_JOIN_RESULT", enableDynamicFiltering,
                                anyTree(
                                        filter(
                                                new ComparisonExpression(EQUAL, new SymbolReference("LINE_NUMBER"), GenericLiteral.constant(INTEGER, 2L)),
                                                tableScan("lineitem", ImmutableMap.of(
                                                        "LINE_ORDER_KEY", "orderkey",
                                                        "LINE_NUMBER", "linenumber",
                                                        "LINE_QUANTITY", "quantity")))),
                                anyTree(tableScan("orders", ImmutableMap.of("ORDERS_ORDER_KEY", "orderkey"))))));
    }

    @Test
    public void testNonDeterministicPredicatePropagatesOnlyToSourceSideOfSemiJoin()
    {
        assertPlan("SELECT * FROM lineitem WHERE orderkey IN (SELECT orderkey FROM orders) AND orderkey = random(5)",
                noSemiJoinRewrite(),
                anyTree(
                        semiJoin("LINE_ORDER_KEY", "ORDERS_ORDER_KEY", "SEMI_JOIN_RESULT", enableDynamicFiltering,
                                filter(
                                        new ComparisonExpression(EQUAL, new SymbolReference("LINE_ORDER_KEY"), new Cast(new FunctionCall(QualifiedName.of("random"), ImmutableList.of(GenericLiteral.constant(INTEGER, 5L))), BIGINT)),
                                        tableScan("lineitem", ImmutableMap.of(
                                                "LINE_ORDER_KEY", "orderkey"))),
                                node(ExchangeNode.class, // NO filter here
                                        tableScan("orders", ImmutableMap.of("ORDERS_ORDER_KEY", "orderkey"))))));

        assertPlan("SELECT * FROM lineitem WHERE orderkey NOT IN (SELECT orderkey FROM orders) AND orderkey = random(5)",
                anyTree(
                        semiJoin("LINE_ORDER_KEY", "ORDERS_ORDER_KEY", "SEMI_JOIN_RESULT",
                                filter(
                                        new ComparisonExpression(EQUAL, new SymbolReference("LINE_ORDER_KEY"), new Cast(new FunctionCall(QualifiedName.of("random"), ImmutableList.of(GenericLiteral.constant(INTEGER, 5L))), BIGINT)),
                                        tableScan("lineitem", ImmutableMap.of(
                                                "LINE_ORDER_KEY", "orderkey"))),
                                anyTree(
                                        tableScan("orders", ImmutableMap.of("ORDERS_ORDER_KEY", "orderkey"))))));
    }

    @Test
    public void testGreaterPredicateFromFilterSidePropagatesToSourceSideOfSemiJoin()
    {
        assertPlan("SELECT quantity FROM (SELECT * FROM lineitem WHERE orderkey IN (SELECT orderkey FROM orders WHERE orderkey > 2))",
                noSemiJoinRewrite(),
                anyTree(
                        semiJoin("LINE_ORDER_KEY", "ORDERS_ORDER_KEY", "SEMI_JOIN_RESULT", enableDynamicFiltering,
                                filter(new ComparisonExpression(GREATER_THAN, new SymbolReference("LINE_ORDER_KEY"), GenericLiteral.constant(BIGINT, 2L)),
                                        tableScan("lineitem", ImmutableMap.of(
                                                "LINE_ORDER_KEY", "orderkey",
                                                "LINE_QUANTITY", "quantity"))),
                                anyTree(
                                        filter(
                                                new ComparisonExpression(GREATER_THAN, new SymbolReference("ORDERS_ORDER_KEY"), GenericLiteral.constant(BIGINT, 2L)),
                                                tableScan("orders", ImmutableMap.of("ORDERS_ORDER_KEY", "orderkey")))))));
    }

    @Test
    public void testEqualsPredicateFromFilterSidePropagatesToSourceSideOfSemiJoin()
    {
        assertPlan("SELECT quantity FROM (SELECT * FROM lineitem WHERE orderkey IN (SELECT orderkey FROM orders WHERE orderkey = 2))",
                noSemiJoinRewrite(),
                anyTree(
                        semiJoin("LINE_ORDER_KEY", "ORDERS_ORDER_KEY", "SEMI_JOIN_RESULT", enableDynamicFiltering,
                                filter(
                                        new ComparisonExpression(EQUAL, new SymbolReference("LINE_ORDER_KEY"), GenericLiteral.constant(BIGINT, 2L)),
                                        tableScan("lineitem", ImmutableMap.of(
                                                "LINE_ORDER_KEY", "orderkey",
                                                "LINE_QUANTITY", "quantity"))),
                                anyTree(
                                        filter(
                                                new ComparisonExpression(EQUAL, new SymbolReference("ORDERS_ORDER_KEY"), GenericLiteral.constant(BIGINT, 2L)),
                                                tableScan("orders", ImmutableMap.of("ORDERS_ORDER_KEY", "orderkey")))))));
    }

    @Test
    public void testPredicateFromFilterSideNotPropagatesToSourceSideOfSemiJoinIfNotIn()
    {
        assertPlan("SELECT quantity FROM (SELECT * FROM lineitem WHERE orderkey NOT IN (SELECT orderkey FROM orders WHERE orderkey > 2))",
                anyTree(
                        semiJoin("LINE_ORDER_KEY", "ORDERS_ORDER_KEY", "SEMI_JOIN_RESULT",
                                // There should be no Filter above table scan, because we don't know whether SemiJoin's filtering source is empty.
                                // And filter would filter out NULLs from source side which is not what we need then.
                                tableScan("lineitem", ImmutableMap.of(
                                        "LINE_ORDER_KEY", "orderkey",
                                        "LINE_QUANTITY", "quantity")),
                                anyTree(
                                        filter(
                                                new ComparisonExpression(GREATER_THAN, new SymbolReference("ORDERS_ORDER_KEY"), GenericLiteral.constant(BIGINT, 2L)),
                                                tableScan("orders", ImmutableMap.of("ORDERS_ORDER_KEY", "orderkey")))))));
    }

    @Test
    public void testGreaterPredicateFromSourceSidePropagatesToFilterSideOfSemiJoin()
    {
        assertPlan("SELECT quantity FROM (SELECT * FROM lineitem WHERE orderkey IN (SELECT orderkey FROM orders) AND orderkey > 2)",
                noSemiJoinRewrite(),
                anyTree(
                        semiJoin("LINE_ORDER_KEY", "ORDERS_ORDER_KEY", "SEMI_JOIN_RESULT", enableDynamicFiltering,
                                filter(
                                        new ComparisonExpression(GREATER_THAN, new SymbolReference("LINE_ORDER_KEY"), GenericLiteral.constant(BIGINT, 2L)),
                                        tableScan("lineitem", ImmutableMap.of(
                                                "LINE_ORDER_KEY", "orderkey",
                                                "LINE_QUANTITY", "quantity"))),
                                anyTree(
                                        filter(
                                                new ComparisonExpression(GREATER_THAN, new SymbolReference("ORDERS_ORDER_KEY"), GenericLiteral.constant(BIGINT, 2L)),
                                                tableScan("orders", ImmutableMap.of("ORDERS_ORDER_KEY", "orderkey")))))));
    }

    @Test
    public void testEqualPredicateFromSourceSidePropagatesToFilterSideOfSemiJoin()
    {
        assertPlan("SELECT quantity FROM (SELECT * FROM lineitem WHERE orderkey IN (SELECT orderkey FROM orders) AND orderkey = 2)",
                noSemiJoinRewrite(),
                anyTree(
                        semiJoin("LINE_ORDER_KEY", "ORDERS_ORDER_KEY", "SEMI_JOIN_RESULT", enableDynamicFiltering,
                                filter(
                                        new ComparisonExpression(EQUAL, new SymbolReference("LINE_ORDER_KEY"), GenericLiteral.constant(BIGINT, 2L)),
                                        tableScan("lineitem", ImmutableMap.of(
                                                "LINE_ORDER_KEY", "orderkey",
                                                "LINE_QUANTITY", "quantity"))),
                                anyTree(
                                        filter(
                                                new ComparisonExpression(EQUAL, new SymbolReference("ORDERS_ORDER_KEY"), GenericLiteral.constant(BIGINT, 2L)),
                                                tableScan("orders", ImmutableMap.of("ORDERS_ORDER_KEY", "orderkey")))))));
    }

    @Test
    public void testPredicateFromSourceSideNotPropagatesToFilterSideOfSemiJoinIfNotIn()
    {
        assertPlan("SELECT quantity FROM (SELECT * FROM lineitem WHERE orderkey NOT IN (SELECT orderkey FROM orders) AND orderkey > 2)",
                anyTree(
                        semiJoin("LINE_ORDER_KEY", "ORDERS_ORDER_KEY", "SEMI_JOIN_RESULT",
                                filter(
                                        new ComparisonExpression(GREATER_THAN, new SymbolReference("LINE_ORDER_KEY"), GenericLiteral.constant(BIGINT, 2L)),
                                        tableScan("lineitem", ImmutableMap.of(
                                                "LINE_ORDER_KEY", "orderkey",
                                                "LINE_QUANTITY", "quantity"))),
                                node(ExchangeNode.class, // NO filter here
                                        tableScan("orders", ImmutableMap.of("ORDERS_ORDER_KEY", "orderkey"))))));
    }

    @Test
    public void testPredicateFromFilterSideNotPropagatesToSourceSideOfSemiJoinUsedInProjection()
    {
        assertPlan("SELECT orderkey IN (SELECT orderkey FROM orders WHERE orderkey > 2) FROM lineitem",
                anyTree(
                        semiJoin("LINE_ORDER_KEY", "ORDERS_ORDER_KEY", "SEMI_JOIN_RESULT",
                                // NO filter here
                                tableScan("lineitem", ImmutableMap.of(
                                        "LINE_ORDER_KEY", "orderkey")),
                                anyTree(
                                        filter(
                                                new ComparisonExpression(GREATER_THAN, new SymbolReference("ORDERS_ORDER_KEY"), GenericLiteral.constant(BIGINT, 2L)),
                                                tableScan("orders", ImmutableMap.of("ORDERS_ORDER_KEY", "orderkey")))))));
    }

    @Test
    public void testFilteredSelectFromPartitionedTable()
    {
        // use all optimizers, including AddExchanges
        List<PlanOptimizer> allOptimizers = getPlanTester().getPlanOptimizers(false);

        assertPlan(
                "SELECT DISTINCT orderstatus FROM orders",
                // TODO this could be optimized to VALUES with values from partitions
                anyTree(
                        tableScan("orders")),
                allOptimizers);

        assertPlan(
                "SELECT orderstatus FROM orders WHERE orderstatus = 'O'",
                // predicate matches exactly single partition, no FilterNode needed
                output(
                        tableScan("orders")),
                allOptimizers);

        assertPlan(
                "SELECT orderstatus FROM orders WHERE orderstatus = 'no_such_partition_value'",
                output(
                        values("orderstatus")),
                allOptimizers);
    }

    @Test
    public void testPredicatePushDownThroughMarkDistinct()
    {
        assertPlan(
                "SELECT (SELECT a FROM (VALUES 1, 2, 3) t(a) WHERE a = b) FROM (VALUES 0, 1) p(b) WHERE b = 1",
                // TODO this could be optimized to VALUES with values from partitions
                anyTree(
                        join(LEFT, builder -> builder
                                .equiCriteria("A", "B")
                                .left(
                                        assignUniqueId("unique", filter(new ComparisonExpression(EQUAL, new SymbolReference("A"), GenericLiteral.constant(INTEGER, 1L)), values("A"))))
                                .right(
                                        filter(new ComparisonExpression(EQUAL, GenericLiteral.constant(INTEGER, 1L), new SymbolReference("B")), values("B"))))));
    }

    @Test
    public void testPredicatePushDownOverProjection()
    {
        // Non-singletons should not be pushed down
        assertPlan(
                "WITH t AS (SELECT orderkey * 2 x FROM orders) " +
                        "SELECT * FROM t WHERE x + x > 1",
                anyTree(
                        filter(
                                new ComparisonExpression(GREATER_THAN, new ArithmeticBinaryExpression(ADD, new SymbolReference("expr"), new SymbolReference("expr")), GenericLiteral.constant(BIGINT, 1L)),
                                project(ImmutableMap.of("expr", expression(new ArithmeticBinaryExpression(MULTIPLY, new SymbolReference("orderkey"), GenericLiteral.constant(BIGINT, 2L)))),
                                        tableScan("orders", ImmutableMap.of("orderkey", "orderkey"))))));

        // constant non-singleton should be pushed down
        assertPlan(
                "with t AS (SELECT orderkey * 2 x, 1 y FROM orders) " +
                        "SELECT * FROM t WHERE x + y + y >1",
                anyTree(
                        project(
                                filter(
                                        new ComparisonExpression(GREATER_THAN, new ArithmeticBinaryExpression(ADD, new ArithmeticBinaryExpression(ADD, new ArithmeticBinaryExpression(MULTIPLY, new SymbolReference("orderkey"), GenericLiteral.constant(BIGINT, 2L)), GenericLiteral.constant(BIGINT, 1L)), GenericLiteral.constant(BIGINT, 1L)), GenericLiteral.constant(BIGINT, 1L)),
                                        tableScan("orders", ImmutableMap.of(
                                                "orderkey", "orderkey"))))));

        // singletons should be pushed down
        assertPlan(
                "WITH t AS (SELECT orderkey * 2 x FROM orders) " +
                        "SELECT * FROM t WHERE x > 1",
                anyTree(
                        project(
                                filter(
                                        new ComparisonExpression(GREATER_THAN, new ArithmeticBinaryExpression(MULTIPLY, new SymbolReference("orderkey"), GenericLiteral.constant(BIGINT, 2L)), GenericLiteral.constant(BIGINT, 1L)),
                                        tableScan("orders", ImmutableMap.of(
                                                "orderkey", "orderkey"))))));

        // composite singletons should be pushed down
        assertPlan(
                "with t AS (SELECT orderkey * 2 x, orderkey y FROM orders) " +
                        "SELECT * FROM t WHERE x + y > 1",
                anyTree(
                        project(
                                filter(
                                        new ComparisonExpression(GREATER_THAN, new ArithmeticBinaryExpression(ADD, new ArithmeticBinaryExpression(MULTIPLY, new SymbolReference("orderkey"), GenericLiteral.constant(BIGINT, 2L)), new SymbolReference("orderkey")), GenericLiteral.constant(BIGINT, 1L)),
                                        tableScan("orders", ImmutableMap.of(
                                                "orderkey", "orderkey"))))));

        // Identities should be pushed down
        assertPlan(
                "WITH t AS (SELECT orderkey x FROM orders) " +
                        "SELECT * FROM t WHERE x >1",
                anyTree(
                        filter(
                                new ComparisonExpression(GREATER_THAN, new SymbolReference("orderkey"), GenericLiteral.constant(BIGINT, 1L)),
                                tableScan("orders", ImmutableMap.of(
                                        "orderkey", "orderkey")))));

        // Non-deterministic predicate should not be pushed down
        assertPlan(
                "WITH t AS (SELECT rand() * orderkey x FROM orders) " +
                        "SELECT * FROM t WHERE x > 5000",
                anyTree(
                        filter(
                                new ComparisonExpression(GREATER_THAN, new SymbolReference("expr"), GenericLiteral.constant(DOUBLE, 5000.0)),
                                project(ImmutableMap.of("expr", expression(new ArithmeticBinaryExpression(MULTIPLY, new FunctionCall(QualifiedName.of("random"), ImmutableList.of()), new Cast(new SymbolReference("orderkey"), DOUBLE)))),
                                        tableScan("orders", ImmutableMap.of(
                                                "orderkey", "orderkey"))))));
    }

    @Test
    public void testPredicatePushDownOverSymbolReferences()
    {
        // Identities should be pushed down
        assertPlan(
                "WITH t AS (SELECT orderkey x, (orderkey + 1) x2 FROM orders) " +
                        "SELECT * FROM t WHERE x > 1 OR x < 0",
                anyTree(
                        filter(
                                new LogicalExpression(OR, ImmutableList.of(new ComparisonExpression(LESS_THAN, new SymbolReference("orderkey"), GenericLiteral.constant(BIGINT, 0L)), new ComparisonExpression(GREATER_THAN, new SymbolReference("orderkey"), GenericLiteral.constant(BIGINT, 1L)))),
                                tableScan("orders", ImmutableMap.of(
                                        "orderkey", "orderkey")))));
    }

    @Test
    public void testConjunctsOrder()
    {
        assertPlan(
                "select partkey " +
                        "from (" +
                        "  select" +
                        "    partkey," +
                        "    100/(size-1) x" +
                        "  from part" +
                        "  where size <> 1" +
                        ") " +
                        "where x = 2",
                anyTree(
                        // Order matters: size<>1 should be before 100/(size-1)=2.
                        // In this particular example, reversing the order leads to div-by-zero error.
                        filter(
                                new LogicalExpression(AND, ImmutableList.of(new ComparisonExpression(NOT_EQUAL, new SymbolReference("size"), GenericLiteral.constant(INTEGER, 1L)), new ComparisonExpression(EQUAL, new ArithmeticBinaryExpression(DIVIDE, GenericLiteral.constant(INTEGER, 100L), new ArithmeticBinaryExpression(SUBTRACT, new SymbolReference("size"), GenericLiteral.constant(INTEGER, 1L))), GenericLiteral.constant(INTEGER, 2L)))),
                                tableScan("part", ImmutableMap.of(
                                        "partkey", "partkey",
                                        "size", "size")))));
    }

    @Test
    public void testPredicateOnPartitionSymbolsPushedThroughWindow()
    {
        PlanMatchPattern tableScan = tableScan(
                "orders",
                ImmutableMap.of(
                        "CUST_KEY", "custkey",
                        "ORDER_KEY", "orderkey"));
        assertPlan(
                "SELECT * FROM (" +
                        "SELECT custkey, orderkey, rank() OVER (PARTITION BY custkey  ORDER BY orderdate ASC)" +
                        "FROM orders" +
                        ") WHERE custkey = 0 AND orderkey > 0",
                anyTree(
                        filter(
                                new ComparisonExpression(GREATER_THAN, new SymbolReference("ORDER_KEY"), GenericLiteral.constant(BIGINT, 0L)),
                                anyTree(
                                        node(WindowNode.class,
                                                anyTree(
                                                        filter(
                                                                new ComparisonExpression(EQUAL, new SymbolReference("CUST_KEY"), GenericLiteral.constant(BIGINT, 0L)),
                                                                tableScan)))))));
    }

    @Test
    public void testPredicateOnNonDeterministicSymbolsPushedDown()
    {
        assertPlan(
                "SELECT * FROM (" +
                        "SELECT random_column, orderkey, rank() OVER (PARTITION BY random_column  ORDER BY orderdate ASC)" +
                        "FROM (select round(custkey*rand()) random_column, * from orders) " +
                        ") WHERE random_column > 100",
                anyTree(
                        node(WindowNode.class,
                                anyTree(
                                        filter(
                                                new ComparisonExpression(GREATER_THAN, new SymbolReference("ROUND"), GenericLiteral.constant(DOUBLE, 100.0)),
                                                project(ImmutableMap.of("ROUND", expression(new FunctionCall(QualifiedName.of("round"), ImmutableList.of(new ArithmeticBinaryExpression(MULTIPLY, new Cast(new SymbolReference("CUST_KEY"), DOUBLE), new FunctionCall(QualifiedName.of("random"), ImmutableList.of())))))),
                                                        tableScan(
                                                                "orders",
                                                                ImmutableMap.of("CUST_KEY", "custkey"))))))));
    }

    @Test
    public void testNonDeterministicPredicateNotPushedDown()
    {
        assertPlan(
                "SELECT * FROM (" +
                        "SELECT custkey, orderkey, rank() OVER (PARTITION BY custkey  ORDER BY orderdate ASC)" +
                        "FROM orders" +
                        ") WHERE custkey > 100*rand()",
                anyTree(
                        filter(
                                new ComparisonExpression(GREATER_THAN, new Cast(new SymbolReference("CUST_KEY"), DOUBLE), new ArithmeticBinaryExpression(MULTIPLY, new FunctionCall(QualifiedName.of("random"), ImmutableList.of()), GenericLiteral.constant(DOUBLE, 100.0))),
                                anyTree(
                                        node(WindowNode.class,
                                                anyTree(
                                                        tableScan(
                                                                "orders",
                                                                ImmutableMap.of("CUST_KEY", "custkey"))))))));
    }

    @Test
    public void testRemovesRedundantTableScanPredicate()
    {
        assertPlan(
                "SELECT t1.orderstatus " +
                        "FROM (SELECT orderstatus FROM orders WHERE rand() = orderkey AND orderkey = 123) t1, (VALUES 'F', 'K') t2(col) " +
                        "WHERE t1.orderstatus = t2.col AND (t2.col = 'F' OR t2.col = 'K') AND length(t1.orderstatus) < 42",
                anyTree(
                        node(
                                JoinNode.class,
                                node(ProjectNode.class,
                                        filter(
                                                new LogicalExpression(AND, ImmutableList.of(new ComparisonExpression(EQUAL, new SymbolReference("ORDERKEY"), GenericLiteral.constant(BIGINT, 123L)), new ComparisonExpression(EQUAL, new FunctionCall(QualifiedName.of("random"), ImmutableList.of()), new Cast(new SymbolReference("ORDERKEY"), DOUBLE)), new ComparisonExpression(LESS_THAN, new FunctionCall(QualifiedName.of("length"), ImmutableList.of(new SymbolReference("ORDERSTATUS"))), GenericLiteral.constant(BIGINT, 42L)))),
                                                tableScan(
                                                        "orders",
                                                        ImmutableMap.of(
                                                                "ORDERSTATUS", "orderstatus",
                                                                "ORDERKEY", "orderkey")))),
                                anyTree(
                                        values("COL")))));
    }

    @Test
    public void testTablePredicateIsExtracted()
    {
        assertPlan(
                "SELECT * FROM orders, nation WHERE orderstatus = CAST(nation.name AS varchar(1)) AND orderstatus BETWEEN 'A' AND 'O'",
                anyTree(
                        node(JoinNode.class,
                                filter(
                                        new InPredicate(new SymbolReference("ORDERSTATUS"), ImmutableList.of(new StringLiteral("F"), new StringLiteral("O"))),
                                        tableScan("orders", ImmutableMap.of("ORDERSTATUS", "orderstatus"))),
                                anyTree(
                                        filter(
                                                new InPredicate(new Cast(new SymbolReference("NAME"), createVarcharType(1)), ImmutableList.of(new StringLiteral("F"), new StringLiteral("O"))),
                                                tableScan(
                                                        "nation",
                                                        ImmutableMap.of("NAME", "name")))))));

        PlanMatchPattern ordersTableScan = tableScan("orders", ImmutableMap.of("ORDERSTATUS", "orderstatus"));
        assertPlan(
                "SELECT * FROM orders JOIN nation ON orderstatus = CAST(nation.name AS varchar(1))",
                anyTree(
                        node(JoinNode.class,
                                enableDynamicFiltering ? filter(TRUE_LITERAL, ordersTableScan) : ordersTableScan,
                                anyTree(
                                        filter(
                                                new InPredicate(new Cast(new SymbolReference("NAME"), createVarcharType(1)), ImmutableList.of(new StringLiteral("F"), new StringLiteral("O"), new StringLiteral("P"))),
                                                tableScan(
                                                        "nation",
                                                        ImmutableMap.of("NAME", "name")))))));
    }

    @Test
    public void testOnlyNullPredicateIsPushDownThroughJoinFilters()
    {
        assertPlan(
                """
                        WITH t(a) AS (VALUES 'a', 'b')
                        SELECT *
                        FROM t t1 JOIN t t2 ON true
                        WHERE t1.a = 'aa'
                        """,
                output(values("field", "field_0")));
    }

    @Test
    public void testSimplifyNonInferrableInheritedPredicate()
    {
        assertPlan("SELECT * FROM (SELECT * FROM nation WHERE nationkey = regionkey AND regionkey = 5) a, nation b WHERE a.nationkey = b.nationkey AND a.nationkey + 11 > 15",
                output(
                        join(INNER, builder -> builder
                                .equiCriteria(ImmutableList.of())
                                .left(
                                        filter(
                                                new LogicalExpression(AND, ImmutableList.of(new ComparisonExpression(EQUAL, new SymbolReference("L_NATIONKEY"), new SymbolReference("L_REGIONKEY")), new ComparisonExpression(EQUAL, new SymbolReference("L_REGIONKEY"), GenericLiteral.constant(BIGINT, 5L)))),
                                                tableScan("nation", ImmutableMap.of("L_NATIONKEY", "nationkey", "L_REGIONKEY", "regionkey"))))
                                .right(
                                        anyTree(
                                                filter(
                                                        new ComparisonExpression(EQUAL, new SymbolReference("R_NATIONKEY"), GenericLiteral.constant(BIGINT, 5L)),
                                                        tableScan("nation", ImmutableMap.of("R_NATIONKEY", "nationkey"))))))));
    }

    @Test
    public void testDoesNotCreatePredicateFromInferredPredicate()
    {
        assertPlan("SELECT * FROM (SELECT *, nationkey + 1 as nationkey2 FROM nation) a JOIN nation b ON a.nationkey2 = b.nationkey",
                output(
                        join(INNER, builder -> builder
                                .equiCriteria("L_NATIONKEY2", "R_NATIONKEY")
                                .left(
                                        project(ImmutableMap.of("L_NATIONKEY2", expression(new ArithmeticBinaryExpression(ADD, new SymbolReference("L_NATIONKEY"), GenericLiteral.constant(BIGINT, 1L)))),
                                                tableScan("nation", ImmutableMap.of("L_NATIONKEY", "nationkey"))))
                                .right(
                                        anyTree(
                                                tableScan("nation", ImmutableMap.of("R_NATIONKEY", "nationkey")))))));

        assertPlan("SELECT * FROM (SELECT * FROM nation WHERE nationkey = 5) a JOIN (SELECT * FROM nation WHERE nationkey = 5) b ON a.nationkey = b.nationkey",
                output(
                        join(INNER, builder -> builder
                                .equiCriteria(ImmutableList.of())
                                .left(
                                        filter(
                                                new ComparisonExpression(EQUAL, new SymbolReference("L_NATIONKEY"), GenericLiteral.constant(BIGINT, 5L)),
                                                tableScan("nation", ImmutableMap.of("L_NATIONKEY", "nationkey"))))
                                .right(
                                        anyTree(
                                                filter(
                                                        new ComparisonExpression(EQUAL, new SymbolReference("R_NATIONKEY"), GenericLiteral.constant(BIGINT, 5L)),
                                                        tableScan("nation", ImmutableMap.of("R_NATIONKEY", "nationkey"))))))));
    }

    @Test
    public void testSimplifiesStraddlingPredicate()
    {
        assertPlan("SELECT * FROM (SELECT * FROM NATION WHERE nationkey = 5) a JOIN nation b ON a.nationkey = b.nationkey AND a.nationkey = a.regionkey + b.regionkey",
                output(
                        filter(
                                new ComparisonExpression(EQUAL, new ArithmeticBinaryExpression(ADD, new SymbolReference("L_REGIONKEY"), new SymbolReference("R_REGIONKEY")), GenericLiteral.constant(BIGINT, 5L)),
                                join(INNER, builder -> builder
                                        .equiCriteria(ImmutableList.of())
                                        .left(
                                                filter(
                                                        new ComparisonExpression(EQUAL, new SymbolReference("L_NATIONKEY"), GenericLiteral.constant(BIGINT, 5L)),
                                                        tableScan("nation", ImmutableMap.of("L_NATIONKEY", "nationkey", "L_REGIONKEY", "regionkey"))))
                                        .right(
                                                anyTree(
                                                        filter(
                                                                new ComparisonExpression(EQUAL, new SymbolReference("R_NATIONKEY"), GenericLiteral.constant(BIGINT, 5L)),
                                                                tableScan("nation", ImmutableMap.of("R_NATIONKEY", "nationkey", "R_REGIONKEY", "regionkey")))))))));
    }

    protected Session noSemiJoinRewrite()
    {
        return Session.builder(getPlanTester().getDefaultSession())
                .setSystemProperty(FILTERING_SEMI_JOIN_TO_INNER, "false")
                .build();
    }
}
