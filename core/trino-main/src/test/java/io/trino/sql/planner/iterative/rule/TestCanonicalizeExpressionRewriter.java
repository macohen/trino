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
import io.trino.security.AllowAllAccessControl;
import io.trino.spi.type.Type;
import io.trino.sql.PlannerContext;
import io.trino.sql.ir.ArithmeticBinaryExpression;
import io.trino.sql.ir.Cast;
import io.trino.sql.ir.ComparisonExpression;
import io.trino.sql.ir.Expression;
import io.trino.sql.ir.FunctionCall;
import io.trino.sql.ir.GenericLiteral;
import io.trino.sql.ir.IfExpression;
import io.trino.sql.ir.IsNotNullPredicate;
import io.trino.sql.ir.IsNullPredicate;
import io.trino.sql.ir.NotExpression;
import io.trino.sql.ir.SearchedCaseExpression;
import io.trino.sql.ir.SymbolReference;
import io.trino.sql.ir.WhenClause;
import io.trino.sql.planner.IrTypeAnalyzer;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.TypeProvider;
import io.trino.sql.planner.assertions.SymbolAliases;
import io.trino.transaction.TransactionManager;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static io.trino.SessionTestUtils.TEST_SESSION;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.DecimalType.createDecimalType;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.TimestampType.createTimestampType;
import static io.trino.spi.type.TimestampWithTimeZoneType.createTimestampWithTimeZoneType;
import static io.trino.spi.type.VarcharType.createVarcharType;
import static io.trino.sql.ExpressionTestUtils.assertExpressionEquals;
import static io.trino.sql.analyzer.TypeSignatureProvider.fromTypes;
import static io.trino.sql.ir.ArithmeticBinaryExpression.Operator.ADD;
import static io.trino.sql.ir.ArithmeticBinaryExpression.Operator.MULTIPLY;
import static io.trino.sql.ir.ComparisonExpression.Operator.EQUAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.GREATER_THAN;
import static io.trino.sql.ir.ComparisonExpression.Operator.GREATER_THAN_OR_EQUAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.IS_DISTINCT_FROM;
import static io.trino.sql.ir.ComparisonExpression.Operator.LESS_THAN;
import static io.trino.sql.ir.ComparisonExpression.Operator.LESS_THAN_OR_EQUAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.NOT_EQUAL;
import static io.trino.sql.planner.TestingPlannerContext.plannerContextBuilder;
import static io.trino.sql.planner.iterative.rule.CanonicalizeExpressionRewriter.rewrite;
import static io.trino.testing.TransactionBuilder.transaction;
import static io.trino.transaction.InMemoryTransactionManager.createTestTransactionManager;

public class TestCanonicalizeExpressionRewriter
{
    private static final TransactionManager TRANSACTION_MANAGER = createTestTransactionManager();
    private static final PlannerContext PLANNER_CONTEXT = plannerContextBuilder()
            .withTransactionManager(TRANSACTION_MANAGER)
            .build();
    private static final IrTypeAnalyzer TYPE_ANALYZER = new IrTypeAnalyzer(PLANNER_CONTEXT);
    private static final AllowAllAccessControl ACCESS_CONTROL = new AllowAllAccessControl();

    @Test
    public void testRewriteIsNotNullPredicate()
    {
        assertRewritten(
                new IsNotNullPredicate(new SymbolReference("x")),
                new NotExpression(new IsNullPredicate(new SymbolReference("x"))));
    }

    @Test
    public void testRewriteIfExpression()
    {
        assertRewritten(
                new IfExpression(new ComparisonExpression(EQUAL, new SymbolReference("x"), GenericLiteral.constant(INTEGER, 0L)), GenericLiteral.constant(INTEGER, 0L), GenericLiteral.constant(INTEGER, 1L)),
                new SearchedCaseExpression(ImmutableList.of(new WhenClause(new ComparisonExpression(EQUAL, new SymbolReference("x"), GenericLiteral.constant(INTEGER, 0L)), GenericLiteral.constant(INTEGER, 0L))), Optional.of(GenericLiteral.constant(INTEGER, 1L))));
    }

    @Test
    public void testCanonicalizeArithmetic()
    {
        assertRewritten(
                new ArithmeticBinaryExpression(ADD, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L)),
                new ArithmeticBinaryExpression(ADD, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L)));

        assertRewritten(
                new ArithmeticBinaryExpression(ADD, GenericLiteral.constant(INTEGER, 1L), new SymbolReference("a")),
                new ArithmeticBinaryExpression(ADD, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L)));

        assertRewritten(
                new ArithmeticBinaryExpression(MULTIPLY, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L)),
                new ArithmeticBinaryExpression(MULTIPLY, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L)));

        assertRewritten(
                new ArithmeticBinaryExpression(MULTIPLY, GenericLiteral.constant(INTEGER, 1L), new SymbolReference("a")),
                new ArithmeticBinaryExpression(MULTIPLY, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L)));
    }

    @Test
    public void testCanonicalizeComparison()
    {
        assertRewritten(
                new ComparisonExpression(EQUAL, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L)),
                new ComparisonExpression(EQUAL, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L)));

        assertRewritten(
                new ComparisonExpression(EQUAL, GenericLiteral.constant(INTEGER, 1L), new SymbolReference("a")),
                new ComparisonExpression(EQUAL, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L)));

        assertRewritten(
                new ComparisonExpression(NOT_EQUAL, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L)),
                new ComparisonExpression(NOT_EQUAL, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L)));

        assertRewritten(
                new ComparisonExpression(NOT_EQUAL, GenericLiteral.constant(INTEGER, 1L), new SymbolReference("a")),
                new ComparisonExpression(NOT_EQUAL, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L)));

        assertRewritten(
                new ComparisonExpression(GREATER_THAN, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L)),
                new ComparisonExpression(GREATER_THAN, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L)));

        assertRewritten(
                new ComparisonExpression(GREATER_THAN, GenericLiteral.constant(INTEGER, 1L), new SymbolReference("a")),
                new ComparisonExpression(LESS_THAN, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L)));

        assertRewritten(
                new ComparisonExpression(LESS_THAN, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L)),
                new ComparisonExpression(LESS_THAN, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L)));

        assertRewritten(
                new ComparisonExpression(LESS_THAN, GenericLiteral.constant(INTEGER, 1L), new SymbolReference("a")),
                new ComparisonExpression(GREATER_THAN, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L)));

        assertRewritten(
                new ComparisonExpression(GREATER_THAN_OR_EQUAL, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L)),
                new ComparisonExpression(GREATER_THAN_OR_EQUAL, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L)));

        assertRewritten(
                new ComparisonExpression(GREATER_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 1L), new SymbolReference("a")),
                new ComparisonExpression(LESS_THAN_OR_EQUAL, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L)));

        assertRewritten(
                new ComparisonExpression(LESS_THAN_OR_EQUAL, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L)),
                new ComparisonExpression(LESS_THAN_OR_EQUAL, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L)));

        assertRewritten(
                new ComparisonExpression(LESS_THAN_OR_EQUAL, GenericLiteral.constant(INTEGER, 1L), new SymbolReference("a")),
                new ComparisonExpression(GREATER_THAN_OR_EQUAL, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L)));

        assertRewritten(
                new ComparisonExpression(IS_DISTINCT_FROM, GenericLiteral.constant(INTEGER, 1L), new SymbolReference("a")),
                new ComparisonExpression(IS_DISTINCT_FROM, GenericLiteral.constant(INTEGER, 1L), new SymbolReference("a")));

        assertRewritten(
                new ComparisonExpression(IS_DISTINCT_FROM, GenericLiteral.constant(INTEGER, 1L), new SymbolReference("a")),
                new ComparisonExpression(IS_DISTINCT_FROM, GenericLiteral.constant(INTEGER, 1L), new SymbolReference("a")));
    }

    @Test
    public void testTypedLiteral()
    {
        // typed literals are encoded as Cast(Literal) in current IR

        assertRewritten(
                new ComparisonExpression(EQUAL, new SymbolReference("a"), new Cast(GenericLiteral.constant(INTEGER, 1L), createDecimalType(5, 2))),
                new ComparisonExpression(EQUAL, new SymbolReference("a"), new Cast(GenericLiteral.constant(INTEGER, 1L), createDecimalType(5, 2))));

        assertRewritten(
                new ComparisonExpression(EQUAL, new Cast(GenericLiteral.constant(INTEGER, 1L), createDecimalType(5, 2)), new SymbolReference("a")),
                new ComparisonExpression(EQUAL, new SymbolReference("a"), new Cast(GenericLiteral.constant(INTEGER, 1L), createDecimalType(5, 2))));

        assertRewritten(
                new ArithmeticBinaryExpression(ADD, new SymbolReference("a"), new Cast(GenericLiteral.constant(INTEGER, 1L), createDecimalType(5, 2))),
                new ArithmeticBinaryExpression(ADD, new SymbolReference("a"), new Cast(GenericLiteral.constant(INTEGER, 1L), createDecimalType(5, 2))));

        assertRewritten(
                new ArithmeticBinaryExpression(ADD, new Cast(GenericLiteral.constant(INTEGER, 1L), createDecimalType(5, 2)), new SymbolReference("a")),
                new ArithmeticBinaryExpression(ADD, new SymbolReference("a"), new Cast(GenericLiteral.constant(INTEGER, 1L), createDecimalType(5, 2))));
    }

    @Test
    public void testCanonicalizeRewriteDateFunctionToCast()
    {
        assertCanonicalizedDate(createTimestampType(3), "ts");
        assertCanonicalizedDate(createTimestampWithTimeZoneType(3), "tstz");
        assertCanonicalizedDate(createVarcharType(100), "v");
    }

    private static void assertCanonicalizedDate(Type type, String symbolName)
    {
        FunctionCall date = new FunctionCall(
                PLANNER_CONTEXT.getMetadata().resolveBuiltinFunction("date", fromTypes(type)).toQualifiedName(),
                ImmutableList.of(new SymbolReference(symbolName)));
        assertRewritten(date, new Cast(new SymbolReference(symbolName), DATE));
    }

    private static void assertRewritten(Expression from, Expression to)
    {
        assertExpressionEquals(
                transaction(TRANSACTION_MANAGER, PLANNER_CONTEXT.getMetadata(), ACCESS_CONTROL).execute(TEST_SESSION, transactedSession -> {
                    return rewrite(
                            from,
                            transactedSession,
                            PLANNER_CONTEXT,
                            TYPE_ANALYZER,
                                    TypeProvider.copyOf(ImmutableMap.<Symbol, Type>builder()
                                            .put(new Symbol("x"), BIGINT)
                                            .put(new Symbol("a"), BIGINT)
                                            .put(new Symbol("ts"), createTimestampType(3))
                                            .put(new Symbol("tstz"), createTimestampWithTimeZoneType(3))
                                            .put(new Symbol("v"), createVarcharType(100))
                                            .buildOrThrow()));
                }),
                to,
                SymbolAliases.builder()
                        .put("x", new SymbolReference("x"))
                        .put("a", new SymbolReference("a"))
                        .put("ts", new SymbolReference("ts"))
                        .put("tstz", new SymbolReference("tstz"))
                        .put("v", new SymbolReference("v"))
                        .build());
    }
}
