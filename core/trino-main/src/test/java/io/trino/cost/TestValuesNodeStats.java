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
package io.trino.cost;

import com.google.common.collect.ImmutableList;
import io.trino.sql.ir.ArithmeticBinaryExpression;
import io.trino.sql.ir.GenericLiteral;
import io.trino.sql.ir.NullLiteral;
import io.trino.sql.ir.StringLiteral;
import io.trino.sql.planner.Symbol;
import org.junit.jupiter.api.Test;

import static io.trino.cost.PlanNodeStatsEstimate.unknown;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.VarcharType.createVarcharType;
import static io.trino.sql.ir.ArithmeticBinaryExpression.Operator.ADD;
import static io.trino.sql.ir.ArithmeticBinaryExpression.Operator.DIVIDE;
import static io.trino.type.UnknownType.UNKNOWN;

public class TestValuesNodeStats
        extends BaseStatsCalculatorTest
{
    @Test
    public void testStatsForValuesNode()
    {
        tester().assertStatsFor(pb -> pb
                        .values(ImmutableList.of(pb.symbol("a", BIGINT), pb.symbol("b", DOUBLE)),
                                ImmutableList.of(
                                        ImmutableList.of(new ArithmeticBinaryExpression(ADD, GenericLiteral.constant(BIGINT, 3L), GenericLiteral.constant(BIGINT, 3L)), GenericLiteral.constant(DOUBLE, 13.5e0)),
                                        ImmutableList.of(GenericLiteral.constant(BIGINT, 55L), new NullLiteral()),
                                        ImmutableList.of(GenericLiteral.constant(BIGINT, 6L), GenericLiteral.constant(DOUBLE, 13.5e0)))))
                .check(outputStats -> outputStats.equalTo(
                        PlanNodeStatsEstimate.builder()
                                .setOutputRowCount(3)
                                .addSymbolStatistics(
                                        new Symbol("a"),
                                        SymbolStatsEstimate.builder()
                                                .setNullsFraction(0)
                                                .setLowValue(6)
                                                .setHighValue(55)
                                                .setDistinctValuesCount(2)
                                                .build())
                                .addSymbolStatistics(
                                        new Symbol("b"),
                                        SymbolStatsEstimate.builder()
                                                .setNullsFraction(0.33333333333333333)
                                                .setLowValue(13.5)
                                                .setHighValue(13.5)
                                                .setDistinctValuesCount(1)
                                                .build())
                                .build()));

        tester().assertStatsFor(pb -> pb
                        .values(ImmutableList.of(pb.symbol("v", createVarcharType(30))),
                                ImmutableList.of(
                                        ImmutableList.of(new StringLiteral("Alice")),
                                        ImmutableList.of(new StringLiteral("'has'")),
                                        ImmutableList.of(new StringLiteral("'a cat'")),
                                        ImmutableList.of(new NullLiteral()))))
                .check(outputStats -> outputStats.equalTo(
                        PlanNodeStatsEstimate.builder()
                                .setOutputRowCount(4)
                                .addSymbolStatistics(
                                        new Symbol("v"),
                                        SymbolStatsEstimate.builder()
                                                .setNullsFraction(0.25)
                                                .setDistinctValuesCount(3)
                                                // TODO .setAverageRowSize(4 + 1. / 3)
                                                .build())
                                .build()));
    }

    @Test
    public void testDivisionByZero()
    {
        tester().assertStatsFor(pb -> pb
                        .values(ImmutableList.of(pb.symbol("a", BIGINT)),
                                ImmutableList.of(ImmutableList.of(new ArithmeticBinaryExpression(DIVIDE, GenericLiteral.constant(INTEGER, 1L), GenericLiteral.constant(INTEGER, 0L))))))
                .check(outputStats -> outputStats.equalTo(unknown()));
    }

    @Test
    public void testStatsForValuesNodeWithJustNulls()
    {
        PlanNodeStatsEstimate nullAStats = PlanNodeStatsEstimate.builder()
                .setOutputRowCount(1)
                .addSymbolStatistics(new Symbol("a"), SymbolStatsEstimate.zero())
                .build();

        tester().assertStatsFor(pb -> pb
                        .values(ImmutableList.of(pb.symbol("a", BIGINT)),
                                ImmutableList.of(
                                        ImmutableList.of(new ArithmeticBinaryExpression(ADD, GenericLiteral.constant(INTEGER, 3L), new NullLiteral())))))
                .check(outputStats -> outputStats.equalTo(nullAStats));

        tester().assertStatsFor(pb -> pb
                        .values(ImmutableList.of(pb.symbol("a", BIGINT)),
                                ImmutableList.of(
                                        ImmutableList.of(new NullLiteral()))))
                .check(outputStats -> outputStats.equalTo(nullAStats));

        tester().assertStatsFor(pb -> pb
                        .values(ImmutableList.of(pb.symbol("a", UNKNOWN)),
                                ImmutableList.of(
                                        ImmutableList.of(new NullLiteral()))))
                .check(outputStats -> outputStats.equalTo(nullAStats));
    }

    @Test
    public void testStatsForEmptyValues()
    {
        tester().assertStatsFor(pb -> pb
                        .values(ImmutableList.of(pb.symbol("a", BIGINT)),
                                ImmutableList.of()))
                .check(outputStats -> outputStats.equalTo(
                        PlanNodeStatsEstimate.builder()
                                .setOutputRowCount(0)
                                .addSymbolStatistics(new Symbol("a"), SymbolStatsEstimate.zero())
                                .build()));
    }
}
