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
import io.trino.plugin.tpch.TpchColumnHandle;
import io.trino.sql.ir.ArithmeticBinaryExpression;
import io.trino.sql.ir.Cast;
import io.trino.sql.ir.GenericLiteral;
import io.trino.sql.ir.NullLiteral;
import io.trino.sql.ir.Row;
import io.trino.sql.ir.StringLiteral;
import io.trino.sql.ir.SymbolReference;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.assertions.PlanMatchPattern;
import io.trino.sql.planner.iterative.rule.test.BaseRuleTest;
import io.trino.sql.planner.plan.Assignments;
import org.junit.jupiter.api.Test;

import static io.trino.plugin.tpch.TpchMetadata.TINY_SCHEMA_NAME;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.RowType.field;
import static io.trino.spi.type.RowType.rowType;
import static io.trino.sql.ir.ArithmeticBinaryExpression.Operator.ADD;
import static io.trino.sql.planner.assertions.PlanMatchPattern.project;
import static io.trino.sql.planner.assertions.PlanMatchPattern.tableScan;
import static io.trino.sql.planner.assertions.PlanMatchPattern.values;

public class TestTransformCorrelatedSingleRowSubqueryToProject
        extends BaseRuleTest
{
    @Test
    public void testDoesNotFire()
    {
        tester().assertThat(new TransformCorrelatedSingleRowSubqueryToProject())
                .on(p -> p.values(p.symbol("a")))
                .doesNotFire();
    }

    @Test
    public void testRewrite()
    {
        tester().assertThat(new TransformCorrelatedSingleRowSubqueryToProject())
                .on(p ->
                        p.correlatedJoin(
                                ImmutableList.of(p.symbol("l_nationkey")),
                                p.tableScan(
                                        tester().getCurrentCatalogTableHandle(TINY_SCHEMA_NAME, "nation"),
                                        ImmutableList.of(p.symbol("l_nationkey")),
                                        ImmutableMap.of(p.symbol("l_nationkey"), new TpchColumnHandle("nationkey",
                                                BIGINT))),
                                p.project(
                                        Assignments.of(p.symbol("l_expr2"), new ArithmeticBinaryExpression(ADD, new SymbolReference("l_nationkey"), GenericLiteral.constant(INTEGER, 1L))),
                                        p.values(
                                                ImmutableList.of(),
                                                ImmutableList.of(
                                                        ImmutableList.of())))))
                .matches(project(
                        ImmutableMap.of(
                                "l_expr2", PlanMatchPattern.expression(new ArithmeticBinaryExpression(ADD, new SymbolReference("l_nationkey"), GenericLiteral.constant(INTEGER, 1L))),
                                "l_nationkey", PlanMatchPattern.expression(new SymbolReference("l_nationkey"))),
                        tableScan("nation", ImmutableMap.of("l_nationkey", "nationkey"))));
    }

    @Test
    public void testDoesNotFireWithEmptyValuesNode()
    {
        tester().assertThat(new TransformCorrelatedSingleRowSubqueryToProject())
                .on(p ->
                        p.correlatedJoin(
                                ImmutableList.of(p.symbol("a")),
                                p.values(p.symbol("a")),
                                p.values(p.symbol("a"))))
                .doesNotFire();
    }

    @Test
    public void testCorrelatedValues()
    {
        tester().assertThat(new TransformCorrelatedSingleRowSubqueryToProject())
                .on(p -> {
                    Symbol a = p.symbol("a");
                    return p.correlatedJoin(
                            ImmutableList.of(a),
                            p.values(3, a),
                            p.values(ImmutableList.of(a), ImmutableList.of(ImmutableList.of(new SymbolReference("a")))));
                })
                .matches(
                        project(
                                ImmutableMap.of("a", PlanMatchPattern.expression(new SymbolReference("a"))),
                                values(ImmutableList.of("a"), ImmutableList.of(
                                        ImmutableList.of(new NullLiteral()),
                                        ImmutableList.of(new NullLiteral()),
                                        ImmutableList.of(new NullLiteral())))));

        tester().assertThat(new TransformCorrelatedSingleRowSubqueryToProject())
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    Symbol c = p.symbol("c");
                    return p.correlatedJoin(
                            ImmutableList.of(a),
                            p.values(3, a, b),
                            p.values(ImmutableList.of(a, c), ImmutableList.of(ImmutableList.of(new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L)))));
                })
                .matches(
                        project(
                                ImmutableMap.of(
                                        "a", PlanMatchPattern.expression(new SymbolReference("a")),
                                        "b", PlanMatchPattern.expression(new SymbolReference("b")),
                                        "c", PlanMatchPattern.expression(GenericLiteral.constant(INTEGER, 1L))),
                                values(ImmutableList.of("a", "b"), ImmutableList.of(
                                        ImmutableList.of(new NullLiteral(), new NullLiteral()),
                                        ImmutableList.of(new NullLiteral(), new NullLiteral()),
                                        ImmutableList.of(new NullLiteral(), new NullLiteral())))));
    }

    @Test
    public void testUncorrelatedValues()
    {
        tester().assertThat(new TransformCorrelatedSingleRowSubqueryToProject())
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    return p.correlatedJoin(
                            ImmutableList.of(),
                            p.values(3, a),
                            p.values(1, b));
                })
                .matches(
                        project(
                                ImmutableMap.of(
                                        "a", PlanMatchPattern.expression(new SymbolReference("a")),
                                        "b", PlanMatchPattern.expression(new NullLiteral())),
                                values(ImmutableList.of("a"), ImmutableList.of(
                                        ImmutableList.of(new NullLiteral()),
                                        ImmutableList.of(new NullLiteral()),
                                        ImmutableList.of(new NullLiteral())))));
    }

    @Test
    public void testMultipleRowValues()
    {
        tester().assertThat(new TransformCorrelatedSingleRowSubqueryToProject())
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    return p.correlatedJoin(
                            ImmutableList.of(a),
                            p.values(3, a),
                            p.values(2, b));
                })
                .doesNotFire();
    }

    @Test
    public void testNonRowValues()
    {
        tester().assertThat(new TransformCorrelatedSingleRowSubqueryToProject())
                .on(p -> {
                    Symbol a = p.symbol("a");
                    return p.correlatedJoin(
                            ImmutableList.of(a),
                            p.values(3, a),
                            p.valuesOfExpressions(ImmutableList.of(p.symbol("b")), ImmutableList.of(new Cast(new Row(ImmutableList.of(new StringLiteral("true"))), rowType(field("col", BOOLEAN))))));
                })
                .doesNotFire();
    }
}
