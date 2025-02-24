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
import io.trino.spi.connector.SortOrder;
import io.trino.sql.ir.ComparisonExpression;
import io.trino.sql.ir.GenericLiteral;
import io.trino.sql.ir.SymbolReference;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.iterative.rule.test.BaseRuleTest;
import org.junit.jupiter.api.Test;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.sql.ir.ComparisonExpression.Operator.LESS_THAN_OR_EQUAL;
import static io.trino.sql.planner.assertions.PlanMatchPattern.expression;
import static io.trino.sql.planner.assertions.PlanMatchPattern.filter;
import static io.trino.sql.planner.assertions.PlanMatchPattern.specification;
import static io.trino.sql.planner.assertions.PlanMatchPattern.strictProject;
import static io.trino.sql.planner.assertions.PlanMatchPattern.values;
import static io.trino.sql.planner.assertions.PlanMatchPattern.window;
import static io.trino.sql.planner.assertions.PlanMatchPattern.windowFunction;
import static io.trino.sql.planner.plan.WindowNode.Frame.DEFAULT_FRAME;

public class TestImplementLimitWithTies
        extends BaseRuleTest
{
    @Test
    public void testReplaceLimitWithTies()
    {
        tester().assertThat(new ImplementLimitWithTies(tester().getMetadata()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol b = p.symbol("b");
                    return p.limit(
                            2,
                            ImmutableList.of(a),
                            p.values(a, b));
                })
                .matches(
                        strictProject(
                                ImmutableMap.of("a", expression(new SymbolReference("a")), "b", expression(new SymbolReference("b"))),
                                filter(
                                        new ComparisonExpression(LESS_THAN_OR_EQUAL, new SymbolReference("rank_num"), GenericLiteral.constant(BIGINT, 2L)),
                                        window(
                                                windowMatcherBuilder -> windowMatcherBuilder
                                                        .specification(specification(
                                                                ImmutableList.of(),
                                                                ImmutableList.of("a"),
                                                                ImmutableMap.of("a", SortOrder.ASC_NULLS_FIRST)))
                                                        .addFunction(
                                                                "rank_num",
                                                                windowFunction("rank", ImmutableList.of(), DEFAULT_FRAME)),
                                                values("a", "b")))));
    }
}
