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
import io.trino.sql.ir.Cast;
import io.trino.sql.ir.Expression;
import io.trino.sql.ir.GenericLiteral;
import io.trino.sql.ir.Row;
import io.trino.sql.ir.SubscriptExpression;
import io.trino.sql.planner.assertions.PlanMatchPattern;
import io.trino.sql.planner.iterative.rule.test.BaseRuleTest;
import io.trino.sql.planner.plan.Assignments;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.RowType.anonymousRow;
import static io.trino.spi.type.RowType.field;
import static io.trino.spi.type.RowType.rowType;
import static io.trino.spi.type.SmallintType.SMALLINT;
import static io.trino.sql.planner.assertions.PlanMatchPattern.project;
import static io.trino.sql.planner.assertions.PlanMatchPattern.values;

public class TestUnwrapRowSubscript
        extends BaseRuleTest
{
    @Test
    public void testSimpleSubscript()
    {
        test(new SubscriptExpression(new Row(ImmutableList.of(GenericLiteral.constant(INTEGER, 1L))), GenericLiteral.constant(INTEGER, 1L)), GenericLiteral.constant(INTEGER, 1L));
        test(new SubscriptExpression(new Row(ImmutableList.of(GenericLiteral.constant(INTEGER, 1L), GenericLiteral.constant(INTEGER, 2L))), GenericLiteral.constant(INTEGER, 1L)), GenericLiteral.constant(INTEGER, 1L));
        test(new SubscriptExpression(new SubscriptExpression(new Row(ImmutableList.of(new Row(ImmutableList.of(GenericLiteral.constant(INTEGER, 1L), GenericLiteral.constant(INTEGER, 2L))), GenericLiteral.constant(INTEGER, 3L))), GenericLiteral.constant(INTEGER, 1L)), GenericLiteral.constant(INTEGER, 2L)), GenericLiteral.constant(INTEGER, 2L));
    }

    @Test
    public void testWithCast()
    {
        test(
                new SubscriptExpression(new Cast(new Row(ImmutableList.of(GenericLiteral.constant(INTEGER, 1L), GenericLiteral.constant(INTEGER, 2L))), rowType(field("a", BIGINT), field("b", BIGINT))), GenericLiteral.constant(INTEGER, 1L)),
                new Cast(GenericLiteral.constant(INTEGER, 1L), BIGINT));

        test(
                new SubscriptExpression(new Cast(new Row(ImmutableList.of(GenericLiteral.constant(INTEGER, 1L), GenericLiteral.constant(INTEGER, 2L))), anonymousRow(BIGINT, BIGINT)), GenericLiteral.constant(INTEGER, 1L)),
                new Cast(GenericLiteral.constant(INTEGER, 1L), BIGINT));

        test(
                new SubscriptExpression(new Cast(new SubscriptExpression(new Cast(new Row(ImmutableList.of(new Row(ImmutableList.of(GenericLiteral.constant(INTEGER, 1L), GenericLiteral.constant(INTEGER, 2L))), GenericLiteral.constant(INTEGER, 3L))), anonymousRow(anonymousRow(SMALLINT, SMALLINT), BIGINT)), GenericLiteral.constant(INTEGER, 1L)), rowType(field("x", BIGINT), field("y", BIGINT))), GenericLiteral.constant(INTEGER, 2L)),
                new Cast(new Cast(GenericLiteral.constant(INTEGER, 2L), SMALLINT), BIGINT));
    }

    @Test
    public void testWithTryCast()
    {
        test(
                new SubscriptExpression(new Cast(new Row(ImmutableList.of(GenericLiteral.constant(INTEGER, 1L), GenericLiteral.constant(INTEGER, 2L))), rowType(field("a", BIGINT), field("b", BIGINT)), true), GenericLiteral.constant(INTEGER, 1L)),
                new Cast(GenericLiteral.constant(INTEGER, 1L), BIGINT, true));

        test(
                new SubscriptExpression(new Cast(new Row(ImmutableList.of(GenericLiteral.constant(INTEGER, 1L), GenericLiteral.constant(INTEGER, 2L))), anonymousRow(BIGINT, BIGINT), true), GenericLiteral.constant(INTEGER, 1L)),
                new Cast(GenericLiteral.constant(INTEGER, 1L), BIGINT, true));

        test(
                new SubscriptExpression(new Cast(new SubscriptExpression(new Cast(new Row(ImmutableList.of(new Row(ImmutableList.of(GenericLiteral.constant(INTEGER, 1L), GenericLiteral.constant(INTEGER, 2L))), GenericLiteral.constant(INTEGER, 3L))), anonymousRow(anonymousRow(SMALLINT, SMALLINT), BIGINT), true), GenericLiteral.constant(INTEGER, 1L)), rowType(field("x", BIGINT), field("y", BIGINT)), true), GenericLiteral.constant(INTEGER, 2L)),
                new Cast(new Cast(GenericLiteral.constant(INTEGER, 2L), SMALLINT, true), BIGINT, true));
    }

    private void test(Expression original, Expression unwrapped)
    {
        tester().assertThat(new UnwrapRowSubscript().projectExpressionRewrite())
                .on(p -> p.project(
                        Assignments.builder()
                                .put(p.symbol("output"), original)
                                .build(),
                        p.values()))
                .matches(
                        project(Map.of("output", PlanMatchPattern.expression(unwrapped)),
                                values()));
    }
}
