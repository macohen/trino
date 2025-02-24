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
import io.trino.sql.ir.ComparisonExpression;
import io.trino.sql.ir.GenericLiteral;
import io.trino.sql.ir.LogicalExpression;
import io.trino.sql.ir.SymbolReference;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.iterative.rule.test.BaseRuleTest;
import io.trino.sql.planner.plan.JoinType;
import org.junit.jupiter.api.Test;

import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.sql.ir.BooleanLiteral.TRUE_LITERAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.EQUAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.GREATER_THAN;
import static io.trino.sql.ir.ComparisonExpression.Operator.LESS_THAN;
import static io.trino.sql.ir.LogicalExpression.Operator.AND;
import static io.trino.sql.planner.assertions.PlanMatchPattern.filter;
import static io.trino.sql.planner.assertions.PlanMatchPattern.join;
import static io.trino.sql.planner.assertions.PlanMatchPattern.values;
import static io.trino.sql.planner.plan.JoinType.INNER;
import static io.trino.sql.planner.plan.JoinType.LEFT;

public class TestTransformCorrelatedJoinToJoin
        extends BaseRuleTest
{
    @Test
    public void testRewriteInnerCorrelatedJoin()
    {
        tester().assertThat(new TransformCorrelatedJoinToJoin(tester().getPlannerContext()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    return p.correlatedJoin(
                            ImmutableList.of(a),
                            p.values(a),
                            p.filter(
                                    new ComparisonExpression(
                                            GREATER_THAN,
                                            b.toSymbolReference(),
                                            a.toSymbolReference()),
                                    p.values(b)));
                })
                .matches(
                        join(JoinType.INNER, builder -> builder
                                .filter(new ComparisonExpression(GREATER_THAN, new SymbolReference("b"), new SymbolReference("a")))
                                .left(values("a"))
                                .right(
                                        filter(
                                                TRUE_LITERAL,
                                                values("b")))));

        tester().assertThat(new TransformCorrelatedJoinToJoin(tester().getPlannerContext()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    return p.correlatedJoin(
                            ImmutableList.of(a),
                            p.values(a),
                            INNER,
                            new ComparisonExpression(
                                    LESS_THAN,
                                    b.toSymbolReference(),
                                    GenericLiteral.constant(INTEGER, 3L)),
                            p.filter(
                                    new ComparisonExpression(
                                            GREATER_THAN,
                                            b.toSymbolReference(),
                                            a.toSymbolReference()),
                                    p.values(b)));
                })
                .matches(
                        join(JoinType.INNER, builder -> builder
                                .filter(new LogicalExpression(AND, ImmutableList.of(new ComparisonExpression(GREATER_THAN, new SymbolReference("b"), new SymbolReference("a")), new ComparisonExpression(LESS_THAN, new SymbolReference("b"), GenericLiteral.constant(INTEGER, 3L)))))
                                .left(values("a"))
                                .right(
                                        filter(
                                                TRUE_LITERAL,
                                                values("b")))));
    }

    @Test
    public void testRewriteLeftCorrelatedJoin()
    {
        tester().assertThat(new TransformCorrelatedJoinToJoin(tester().getPlannerContext()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    return p.correlatedJoin(
                            ImmutableList.of(a),
                            p.values(a),
                            LEFT,
                            TRUE_LITERAL,
                            p.filter(
                                    new ComparisonExpression(
                                            GREATER_THAN,
                                            b.toSymbolReference(),
                                            a.toSymbolReference()),
                                    p.values(b)));
                })
                .matches(
                        join(JoinType.LEFT, builder -> builder
                                .filter(new ComparisonExpression(GREATER_THAN, new SymbolReference("b"), new SymbolReference("a")))
                                .left(values("a"))
                                .right(
                                        filter(
                                                TRUE_LITERAL,
                                                values("b")))));

        tester().assertThat(new TransformCorrelatedJoinToJoin(tester().getPlannerContext()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    return p.correlatedJoin(
                            ImmutableList.of(a),
                            p.values(a),
                            LEFT,
                            new ComparisonExpression(
                                    LESS_THAN,
                                    b.toSymbolReference(),
                                    GenericLiteral.constant(INTEGER, 3L)),
                            p.filter(
                                    new ComparisonExpression(
                                            GREATER_THAN,
                                            b.toSymbolReference(),
                                            a.toSymbolReference()),
                                    p.values(b)));
                })
                .matches(
                        join(JoinType.LEFT, builder -> builder
                                .filter(new LogicalExpression(AND, ImmutableList.of(new ComparisonExpression(GREATER_THAN, new SymbolReference("b"), new SymbolReference("a")), new ComparisonExpression(LESS_THAN, new SymbolReference("b"), GenericLiteral.constant(INTEGER, 3L)))))
                                .left(values("a"))
                                .right(
                                        filter(
                                                TRUE_LITERAL,
                                                values("b")))));
    }

    @Test
    public void doesNotFireForEnforceSingleRow()
    {
        tester().assertThat(new TransformCorrelatedJoinToJoin(tester().getPlannerContext()))
                .on(p -> p.correlatedJoin(
                        ImmutableList.of(p.symbol("corr")),
                        p.values(p.symbol("corr")),
                        INNER,
                        TRUE_LITERAL,
                        p.enforceSingleRow(
                                p.filter(
                                        new ComparisonExpression(EQUAL, new SymbolReference("corr"), new SymbolReference("a")),
                                        p.values(p.symbol("a"))))))
                .doesNotFire();
    }

    @Test
    public void doesNotFireOnUncorrelated()
    {
        tester().assertThat(new TransformCorrelatedJoinToJoin(tester().getPlannerContext()))
                .on(p -> p.correlatedJoin(
                        ImmutableList.of(),
                        p.values(p.symbol("a")),
                        p.values(p.symbol("b"))))
                .doesNotFire();
    }
}
