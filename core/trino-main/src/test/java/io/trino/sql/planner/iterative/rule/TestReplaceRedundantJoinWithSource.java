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
import io.trino.sql.ir.NullLiteral;
import io.trino.sql.ir.SymbolReference;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.assertions.PlanMatchPattern;
import io.trino.sql.planner.iterative.rule.test.BaseRuleTest;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.sql.ir.ComparisonExpression.Operator.GREATER_THAN;
import static io.trino.sql.planner.assertions.PlanMatchPattern.filter;
import static io.trino.sql.planner.assertions.PlanMatchPattern.project;
import static io.trino.sql.planner.assertions.PlanMatchPattern.values;
import static io.trino.sql.planner.plan.JoinType.FULL;
import static io.trino.sql.planner.plan.JoinType.INNER;
import static io.trino.sql.planner.plan.JoinType.LEFT;
import static io.trino.sql.planner.plan.JoinType.RIGHT;
import static java.util.Collections.nCopies;

public class TestReplaceRedundantJoinWithSource
        extends BaseRuleTest
{
    @Test
    public void testDoesNotFireOnJoinWithEmptySource()
    {
        tester().assertThat(new ReplaceRedundantJoinWithSource())
                .on(p ->
                        p.join(
                                INNER,
                                p.values(1),
                                p.values(0, p.symbol("a"))))
                .doesNotFire();

        tester().assertThat(new ReplaceRedundantJoinWithSource())
                .on(p ->
                        p.join(
                                LEFT,
                                p.values(0, p.symbol("a")),
                                p.values(1)))
                .doesNotFire();

        tester().assertThat(new ReplaceRedundantJoinWithSource())
                .on(p ->
                        p.join(
                                RIGHT,
                                p.values(1),
                                p.values(0, p.symbol("a"))))
                .doesNotFire();

        tester().assertThat(new ReplaceRedundantJoinWithSource())
                .on(p ->
                        p.join(
                                FULL,
                                p.values(1),
                                p.values(0, p.symbol("a"))))
                .doesNotFire();
    }

    @Test
    public void testDoesNotFireOnJoinWithNoScalarSource()
    {
        tester().assertThat(new ReplaceRedundantJoinWithSource())
                .on(p ->
                        p.join(
                                INNER,
                                p.values(10, p.symbol("a")),
                                p.values(10, p.symbol("b"))))
                .doesNotFire();

        tester().assertThat(new ReplaceRedundantJoinWithSource())
                .on(p ->
                        p.join(
                                LEFT,
                                p.values(10, p.symbol("a")),
                                p.values(10, p.symbol("b"))))
                .doesNotFire();

        tester().assertThat(new ReplaceRedundantJoinWithSource())
                .on(p ->
                        p.join(
                                RIGHT,
                                p.values(10, p.symbol("a")),
                                p.values(10, p.symbol("b"))))
                .doesNotFire();

        tester().assertThat(new ReplaceRedundantJoinWithSource())
                .on(p ->
                        p.join(
                                FULL,
                                p.values(10, p.symbol("a")),
                                p.values(10, p.symbol("b"))))
                .doesNotFire();
    }

    @Test
    public void testReplaceCrossJoin()
    {
        tester().assertThat(new ReplaceRedundantJoinWithSource())
                .on(p ->
                        p.join(
                                INNER,
                                p.values(10, p.symbol("a")),
                                p.values(1)))
                .matches(
                        values(ImmutableList.of("a"), nCopies(10, ImmutableList.of(new NullLiteral()))));

        tester().assertThat(new ReplaceRedundantJoinWithSource())
                .on(p ->
                        p.join(
                                INNER,
                                p.values(1),
                                p.values(10, p.symbol("b"))))
                .matches(
                        values(ImmutableList.of("b"), nCopies(10, ImmutableList.of(new NullLiteral()))));
    }

    @Test
    public void testReplaceInnerJoinWithFilter()
    {
        tester().assertThat(new ReplaceRedundantJoinWithSource())
                .on(p ->
                        p.join(
                                INNER,
                                p.values(10, p.symbol("a")),
                                p.values(1),
                                new ComparisonExpression(GREATER_THAN, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 0L))))
                .matches(
                        filter(
                                new ComparisonExpression(GREATER_THAN, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 0L)),
                                values(ImmutableList.of("a"), nCopies(10, ImmutableList.of(new NullLiteral())))));

        tester().assertThat(new ReplaceRedundantJoinWithSource())
                .on(p ->
                        p.join(
                                INNER,
                                p.values(1),
                                p.values(10, p.symbol("b")),
                                new ComparisonExpression(GREATER_THAN, new SymbolReference("b"), GenericLiteral.constant(INTEGER, 0L))))
                .matches(
                        filter(
                                new ComparisonExpression(GREATER_THAN, new SymbolReference("b"), GenericLiteral.constant(INTEGER, 0L)),
                                values(ImmutableList.of("b"), nCopies(10, ImmutableList.of(new NullLiteral())))));
    }

    @Test
    public void testReplaceLeftJoin()
    {
        tester().assertThat(new ReplaceRedundantJoinWithSource())
                .on(p ->
                        p.join(
                                LEFT,
                                p.values(10, p.symbol("a")),
                                p.values(1)))
                .matches(
                        values(ImmutableList.of("a"), nCopies(10, ImmutableList.of(new NullLiteral()))));

        // in case of outer join, filter does not affect the result
        tester().assertThat(new ReplaceRedundantJoinWithSource())
                .on(p ->
                        p.join(
                                LEFT,
                                p.values(10, p.symbol("a")),
                                p.values(1),
                                new ComparisonExpression(GREATER_THAN, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 0L))))
                .matches(
                        values(ImmutableList.of("a"), nCopies(10, ImmutableList.of(new NullLiteral()))));
    }

    @Test
    public void testReplaceRightJoin()
    {
        tester().assertThat(new ReplaceRedundantJoinWithSource())
                .on(p ->
                        p.join(
                                RIGHT,
                                p.values(1),
                                p.values(10, p.symbol("b"))))
                .matches(
                        values(ImmutableList.of("b"), nCopies(10, ImmutableList.of(new NullLiteral()))));

        // in case of outer join, filter does not affect the result
        tester().assertThat(new ReplaceRedundantJoinWithSource())
                .on(p ->
                        p.join(
                                RIGHT,
                                p.values(1),
                                p.values(10, p.symbol("b")),
                                new ComparisonExpression(GREATER_THAN, new SymbolReference("b"), GenericLiteral.constant(INTEGER, 0L))))
                .matches(
                        values(ImmutableList.of("b"), nCopies(10, ImmutableList.of(new NullLiteral()))));
    }

    @Test
    public void testReplaceFullJoin()
    {
        tester().assertThat(new ReplaceRedundantJoinWithSource())
                .on(p ->
                        p.join(
                                FULL,
                                p.values(10, p.symbol("a")),
                                p.values(1)))
                .matches(
                        values(ImmutableList.of("a"), nCopies(10, ImmutableList.of(new NullLiteral()))));

        tester().assertThat(new ReplaceRedundantJoinWithSource())
                .on(p ->
                        p.join(
                                FULL,
                                p.values(1),
                                p.values(10, p.symbol("b"))))
                .matches(
                        values(ImmutableList.of("b"), nCopies(10, ImmutableList.of(new NullLiteral()))));

        // in case of outer join, filter does not affect the result
        tester().assertThat(new ReplaceRedundantJoinWithSource())
                .on(p ->
                        p.join(
                                FULL,
                                p.values(1),
                                p.values(10, p.symbol("b")),
                                new ComparisonExpression(GREATER_THAN, new SymbolReference("b"), GenericLiteral.constant(INTEGER, 0L))))
                .matches(
                        values(ImmutableList.of("b"), nCopies(10, ImmutableList.of(new NullLiteral()))));

        // Right source is scalar with no outputs. Left source cannot be determined to be at least scalar.
        // In such case, FULL join cannot be replaced with left source. The result would be incorrect
        // if left source was empty.
        tester().assertThat(new ReplaceRedundantJoinWithSource())
                .on(p ->
                        p.join(
                                FULL,
                                p.filter(
                                        new ComparisonExpression(GREATER_THAN, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 5L)),
                                        p.values(10, p.symbol("a"))),
                                p.values(1)))
                .doesNotFire();

        // Left source is scalar with no outputs. Right source cannot be determined to be at least scalar.
        // In such case, FULL join cannot be replaced with right source. The result would be incorrect
        // if right source was empty.
        tester().assertThat(new ReplaceRedundantJoinWithSource())
                .on(p ->
                        p.join(
                                FULL,
                                p.values(1),
                                p.filter(
                                        new ComparisonExpression(GREATER_THAN, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 5L)),
                                        p.values(10, p.symbol("a")))))
                .doesNotFire();
    }

    @Test
    public void testPruneOutputs()
    {
        tester().assertThat(new ReplaceRedundantJoinWithSource())
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    return p.join(
                            LEFT,
                            p.values(10, a, b),
                            p.values(1),
                            ImmutableList.of(),
                            ImmutableList.of(a),
                            ImmutableList.of(),
                            Optional.of(new ComparisonExpression(GREATER_THAN, new SymbolReference("a"), new SymbolReference("b"))));
                })
                .matches(
                        project(
                                ImmutableMap.of("a", PlanMatchPattern.expression(new SymbolReference("a"))),
                                values(ImmutableList.of("a", "b"), nCopies(10, ImmutableList.of(new NullLiteral(), new NullLiteral())))));

        tester().assertThat(new ReplaceRedundantJoinWithSource())
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    return p.join(
                            INNER,
                            p.values(10, a, b),
                            p.values(1),
                            ImmutableList.of(),
                            ImmutableList.of(a),
                            ImmutableList.of(),
                            Optional.of(new ComparisonExpression(GREATER_THAN, new SymbolReference("a"), new SymbolReference("b"))));
                })
                .matches(
                        project(
                                ImmutableMap.of("a", PlanMatchPattern.expression(new SymbolReference("a"))),
                                filter(
                                        new ComparisonExpression(GREATER_THAN, new SymbolReference("a"), new SymbolReference("b")),
                                        values(ImmutableList.of("a", "b"), nCopies(10, ImmutableList.of(new NullLiteral(), new NullLiteral()))))));
    }
}
