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
import io.trino.sql.ir.Cast;
import io.trino.sql.ir.GenericLiteral;
import io.trino.sql.ir.Row;
import io.trino.sql.ir.StringLiteral;
import io.trino.sql.ir.SymbolReference;
import io.trino.sql.planner.assertions.PlanMatchPattern;
import io.trino.sql.planner.iterative.rule.test.BaseRuleTest;
import io.trino.sql.planner.plan.Assignments;
import org.junit.jupiter.api.Test;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.CharType.createCharType;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.RowType.anonymousRow;
import static io.trino.sql.planner.assertions.PlanMatchPattern.project;
import static io.trino.sql.planner.assertions.PlanMatchPattern.values;

public class TestPruneValuesColumns
        extends BaseRuleTest
{
    @Test
    public void testNotAllOutputsReferenced()
    {
        tester().assertThat(new PruneValuesColumns())
                .on(p ->
                        p.project(
                                Assignments.of(p.symbol("y"), new SymbolReference("x")),
                                p.values(
                                        ImmutableList.of(p.symbol("unused"), p.symbol("x")),
                                        ImmutableList.of(
                                                ImmutableList.of(GenericLiteral.constant(INTEGER, 1L), GenericLiteral.constant(INTEGER, 2L)),
                                                ImmutableList.of(GenericLiteral.constant(INTEGER, 3L), GenericLiteral.constant(INTEGER, 4L))))))
                .matches(
                        project(
                                ImmutableMap.of("y", PlanMatchPattern.expression(new SymbolReference("x"))),
                                values(
                                        ImmutableList.of("x"),
                                        ImmutableList.of(
                                                ImmutableList.of(GenericLiteral.constant(INTEGER, 2L)),
                                                ImmutableList.of(GenericLiteral.constant(INTEGER, 4L))))));
    }

    @Test
    public void testAllOutputsReferenced()
    {
        tester().assertThat(new PruneValuesColumns())
                .on(p ->
                        p.project(
                                Assignments.of(p.symbol("y"), new SymbolReference("x")),
                                p.values(p.symbol("x"))))
                .doesNotFire();
    }

    @Test
    public void testPruneAllOutputs()
    {
        tester().assertThat(new PruneValuesColumns())
                .on(p ->
                        p.project(
                                Assignments.of(),
                                p.values(5, p.symbol("x"))))
                .matches(
                        project(
                                ImmutableMap.of(),
                                values(5)));
    }

    @Test
    public void testPruneAllOutputsWhenValuesExpressionIsNotRow()
    {
        tester().assertThat(new PruneValuesColumns())
                .on(p ->
                        p.project(
                                Assignments.of(),
                                p.valuesOfExpressions(
                                        ImmutableList.of(p.symbol("x")),
                                        ImmutableList.of(new Cast(new Row(ImmutableList.of(GenericLiteral.constant(INTEGER, 1L))), anonymousRow(BIGINT))))))
                .matches(
                        project(
                                ImmutableMap.of(),
                                values(1)));
    }

    @Test
    public void testDoNotPruneWhenValuesExpressionIsNotRow()
    {
        tester().assertThat(new PruneValuesColumns())
                .on(p ->
                        p.project(
                                Assignments.of(p.symbol("x"), new SymbolReference("x")),
                                p.valuesOfExpressions(
                                        ImmutableList.of(p.symbol("x"), p.symbol("y")),
                                        ImmutableList.of(new Cast(new Row(ImmutableList.of(GenericLiteral.constant(INTEGER, 1L), new StringLiteral("a"))), anonymousRow(BIGINT, createCharType(2)))))))
                .doesNotFire();
    }
}
