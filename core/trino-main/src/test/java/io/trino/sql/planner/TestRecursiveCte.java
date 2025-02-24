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
import io.trino.sql.ir.IfExpression;
import io.trino.sql.ir.SymbolReference;
import io.trino.sql.planner.assertions.BasePlanTest;
import io.trino.sql.planner.assertions.PlanMatchPattern;
import io.trino.sql.tree.QualifiedName;
import io.trino.testing.PlanTester;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.sql.ir.ArithmeticBinaryExpression.Operator.ADD;
import static io.trino.sql.ir.BooleanLiteral.TRUE_LITERAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.GREATER_THAN_OR_EQUAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.LESS_THAN;
import static io.trino.sql.planner.LogicalPlanner.Stage.CREATED;
import static io.trino.sql.planner.assertions.PlanMatchPattern.anyTree;
import static io.trino.sql.planner.assertions.PlanMatchPattern.expression;
import static io.trino.sql.planner.assertions.PlanMatchPattern.filter;
import static io.trino.sql.planner.assertions.PlanMatchPattern.project;
import static io.trino.sql.planner.assertions.PlanMatchPattern.union;
import static io.trino.sql.planner.assertions.PlanMatchPattern.values;
import static io.trino.sql.planner.assertions.PlanMatchPattern.window;
import static io.trino.sql.planner.assertions.PlanMatchPattern.windowFunction;
import static io.trino.sql.planner.plan.WindowNode.Frame.DEFAULT_FRAME;
import static io.trino.testing.TestingSession.testSessionBuilder;

public class TestRecursiveCte
        extends BasePlanTest
{
    @Override
    protected PlanTester createPlanTester()
    {
        Session.SessionBuilder sessionBuilder = testSessionBuilder()
                .setSystemProperty("max_recursion_depth", "1");

        return PlanTester.create(sessionBuilder.build());
    }

    @Test
    public void testRecursiveQuery()
    {
        @Language("SQL") String sql = "WITH RECURSIVE t(n) AS (" +
                "                SELECT 1" +
                "                UNION ALL" +
                "                SELECT n + 2 FROM t WHERE n < 6" +
                "                )" +
                "                SELECT * from t";

        PlanMatchPattern pattern =
                anyTree(
                        union(
                                // base term
                                project(project(project(
                                        ImmutableMap.of("expr", expression(GenericLiteral.constant(INTEGER, 1L))),
                                        values()))),
                                // first recursion step
                                project(project(project(
                                        ImmutableMap.of("expr_0", expression(new ArithmeticBinaryExpression(ADD, new SymbolReference("expr"), GenericLiteral.constant(INTEGER, 2L)))),
                                        filter(
                                                new ComparisonExpression(LESS_THAN, new SymbolReference("expr"), GenericLiteral.constant(INTEGER, 6L)),
                                                project(project(project(
                                                        ImmutableMap.of("expr", expression(GenericLiteral.constant(INTEGER, 1L))),
                                                        values()))))))),
                                // "post-recursion" step with convergence assertion
                                filter(
                                        new IfExpression(
                                                new ComparisonExpression(GREATER_THAN_OR_EQUAL, new SymbolReference("count"), GenericLiteral.constant(BIGINT, 0L)),
                                                new Cast(new FunctionCall(QualifiedName.of("fail"), ImmutableList.of(GenericLiteral.constant(INTEGER, (long) NOT_SUPPORTED.toErrorCode().getCode()), new GenericLiteral(VARCHAR, "Recursion depth limit exceeded (1). Use 'max_recursion_depth' session property to modify the limit."))), BOOLEAN),
                                                TRUE_LITERAL),
                                        window(windowBuilder -> windowBuilder
                                                        .addFunction(
                                                                "count",
                                                                windowFunction("count", ImmutableList.of(), DEFAULT_FRAME)),
                                                project(project(project(
                                                        ImmutableMap.of("expr_1", expression(new ArithmeticBinaryExpression(ADD, new SymbolReference("expr"), GenericLiteral.constant(INTEGER, 2L)))),
                                                        filter(
                                                                new ComparisonExpression(LESS_THAN, new SymbolReference("expr"), GenericLiteral.constant(INTEGER, 6L)),
                                                                project(
                                                                        ImmutableMap.of("expr", expression(new SymbolReference("expr_0"))),
                                                                        project(project(project(
                                                                                ImmutableMap.of("expr_0", expression(new ArithmeticBinaryExpression(ADD, new SymbolReference("expr"), GenericLiteral.constant(INTEGER, 2L)))),
                                                                                filter(
                                                                                        new ComparisonExpression(LESS_THAN, new SymbolReference("expr"), GenericLiteral.constant(INTEGER, 6L)),
                                                                                        project(project(project(
                                                                                                ImmutableMap.of("expr", expression(GenericLiteral.constant(INTEGER, 1L))),
                                                                                                values()))))))))))))))));

        assertPlan(sql, CREATED, pattern);
    }
}
