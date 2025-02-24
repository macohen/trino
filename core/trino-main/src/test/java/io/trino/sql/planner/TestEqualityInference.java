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

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import io.trino.metadata.Metadata;
import io.trino.metadata.TestingFunctionResolution;
import io.trino.operator.scalar.TryFunction;
import io.trino.sql.ir.ArithmeticBinaryExpression;
import io.trino.sql.ir.Array;
import io.trino.sql.ir.Cast;
import io.trino.sql.ir.ComparisonExpression;
import io.trino.sql.ir.Expression;
import io.trino.sql.ir.GenericLiteral;
import io.trino.sql.ir.IfExpression;
import io.trino.sql.ir.InPredicate;
import io.trino.sql.ir.IsNotNullPredicate;
import io.trino.sql.ir.LambdaExpression;
import io.trino.sql.ir.NullIfExpression;
import io.trino.sql.ir.NullLiteral;
import io.trino.sql.ir.SearchedCaseExpression;
import io.trino.sql.ir.SimpleCaseExpression;
import io.trino.sql.ir.SubscriptExpression;
import io.trino.sql.ir.SymbolReference;
import io.trino.sql.ir.WhenClause;
import io.trino.type.FunctionType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Predicates.not;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.sql.ir.ComparisonExpression.Operator.EQUAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.GREATER_THAN;
import static io.trino.sql.ir.IrUtils.and;
import static io.trino.sql.planner.EqualityInference.isInferenceCandidate;
import static org.assertj.core.api.Assertions.assertThat;

public class TestEqualityInference
{
    private final TestingFunctionResolution functionResolution = new TestingFunctionResolution();
    private final Metadata metadata = functionResolution.getMetadata();

    @Test
    public void testDoesNotInferRedundantStraddlingPredicates()
    {
        EqualityInference inference = new EqualityInference(
                metadata,
                equals("a1", "b1"),
                equals(add(nameReference("a1"), number(1)), number(0)),
                equals(nameReference("a2"), add(nameReference("a1"), number(2))),
                equals(nameReference("a1"), add("a3", "b3")),
                equals(nameReference("b2"), add("a4", "b4")));
        EqualityInference.EqualityPartition partition = inference.generateEqualitiesPartitionedBy(symbols("a1", "a2", "a3", "a4"));
        assertThat(partition.getScopeEqualities()).containsExactly(
                equals(number(0), add(nameReference("a1"), number(1))),
                equals(nameReference("a2"), add(nameReference("a1"), number(2))));
        assertThat(partition.getScopeComplementEqualities()).containsExactly(
                equals(number(0), add(nameReference("b1"), number(1))));
        // there shouldn't be equality a2 = b1 + 1 as it can be derived from a2 = a1 + 1, a1 = b1
        assertThat(partition.getScopeStraddlingEqualities()).containsExactly(
                equals("a1", "b1"),
                equals(nameReference("a1"), add("a3", "b3")),
                equals(nameReference("b2"), add("a4", "b4")));
    }

    @Test
    public void testTransitivity()
    {
        EqualityInference inference = new EqualityInference(
                metadata,
                equals("a1", "b1"),
                equals("b1", "c1"),
                equals("d1", "c1"),
                equals("a2", "b2"),
                equals("b2", "a2"),
                equals("b2", "c2"),
                equals("d2", "b2"),
                equals("c2", "d2"));

        assertThat(inference.rewrite(someExpression("a1", "a2"), symbols("d1", "d2"))).isEqualTo(someExpression("d1", "d2"));

        assertThat(inference.rewrite(someExpression("a1", "c1"), symbols("b1"))).isEqualTo(someExpression("b1", "b1"));

        assertThat(inference.rewrite(someExpression("a1", "a2"), symbols("b1", "d2", "c3"))).isEqualTo(someExpression("b1", "d2"));

        // Both starting expressions should canonicalize to the same expression
        assertThat(inference.getScopedCanonical(nameReference("a2"), matchesSymbols("c2", "d2"))).isEqualTo(inference.getScopedCanonical(nameReference("b2"), matchesSymbols("c2", "d2")));
        Expression canonical = inference.getScopedCanonical(nameReference("a2"), matchesSymbols("c2", "d2"));

        // Given multiple translatable candidates, should choose the canonical
        assertThat(inference.rewrite(someExpression("a2", "b2"), symbols("c2", "d2"))).isEqualTo(someExpression(canonical, canonical));
    }

    @Test
    public void testTriviallyRewritable()
    {
        Expression expression = new EqualityInference(metadata)
                .rewrite(someExpression("a1", "a2"), symbols("a1", "a2"));

        assertThat(expression).isEqualTo(someExpression("a1", "a2"));
    }

    @Test
    public void testUnrewritable()
    {
        EqualityInference inference = new EqualityInference(
                metadata,
                equals("a1", "b1"),
                equals("a2", "b2"));

        assertThat(inference.rewrite(someExpression("a1", "a2"), symbols("b1", "c1"))).isNull();
        assertThat(inference.rewrite(someExpression("c1", "c2"), symbols("a1", "a2"))).isNull();
    }

    @Test
    public void testParseEqualityExpression()
    {
        EqualityInference inference = new EqualityInference(
                metadata,
                equals("a1", "b1"),
                equals("a1", "c1"),
                equals("c1", "a1"));

        Expression expression = inference.rewrite(someExpression("a1", "b1"), symbols("c1"));
        assertThat(expression).isEqualTo(someExpression("c1", "c1"));
    }

    @Test
    public void testExtractInferrableEqualities()
    {
        EqualityInference inference = new EqualityInference(
                metadata,
                and(equals("a1", "b1"), equals("b1", "c1"), someExpression("c1", "d1")));

        // Able to rewrite to c1 due to equalities
        assertThat(nameReference("c1")).isEqualTo(inference.rewrite(nameReference("a1"), symbols("c1")));

        // But not be able to rewrite to d1 which is not connected via equality
        assertThat(inference.rewrite(nameReference("a1"), symbols("d1"))).isNull();
    }

    @Test
    public void testEqualityPartitionGeneration()
    {
        EqualityInference inference = new EqualityInference(
                metadata,
                equals(nameReference("a1"), nameReference("b1")),
                equals(add("a1", "a1"), multiply(nameReference("a1"), number(2))),
                equals(nameReference("b1"), nameReference("c1")),
                equals(add("a1", "a1"), nameReference("c1")),
                equals(add("a1", "b1"), nameReference("c1")));

        EqualityInference.EqualityPartition emptyScopePartition = inference.generateEqualitiesPartitionedBy(ImmutableSet.of());
        // Cannot generate any scope equalities with no matching symbols
        assertThat(emptyScopePartition.getScopeEqualities().isEmpty()).isTrue();
        // All equalities should be represented in the inverse scope
        assertThat(emptyScopePartition.getScopeComplementEqualities().isEmpty()).isFalse();
        // There should be no equalities straddling the scope
        assertThat(emptyScopePartition.getScopeStraddlingEqualities().isEmpty()).isTrue();

        EqualityInference.EqualityPartition equalityPartition = inference.generateEqualitiesPartitionedBy(symbols("c1"));

        // There should be equalities in the scope, that only use c1 and are all inferrable equalities
        assertThat(equalityPartition.getScopeEqualities().isEmpty()).isFalse();
        assertThat(Iterables.all(equalityPartition.getScopeEqualities(), matchesSymbolScope(matchesSymbols("c1")))).isTrue();
        assertThat(Iterables.all(equalityPartition.getScopeEqualities(), expression -> isInferenceCandidate(metadata, expression))).isTrue();

        // There should be equalities in the inverse scope, that never use c1 and are all inferrable equalities
        assertThat(equalityPartition.getScopeComplementEqualities().isEmpty()).isFalse();
        assertThat(Iterables.all(equalityPartition.getScopeComplementEqualities(), matchesSymbolScope(not(matchesSymbols("c1"))))).isTrue();
        assertThat(Iterables.all(equalityPartition.getScopeComplementEqualities(), expression -> isInferenceCandidate(metadata, expression))).isTrue();

        // There should be equalities in the straddling scope, that should use both c1 and not c1 symbols
        assertThat(equalityPartition.getScopeStraddlingEqualities().isEmpty()).isFalse();
        assertThat(Iterables.any(equalityPartition.getScopeStraddlingEqualities(), matchesStraddlingScope(matchesSymbols("c1")))).isTrue();
        assertThat(Iterables.all(equalityPartition.getScopeStraddlingEqualities(), expression -> isInferenceCandidate(metadata, expression))).isTrue();

        // There should be a "full cover" of all of the equalities used
        // THUS, we should be able to plug the generated equalities back in and get an equivalent set of equalities back the next time around
        EqualityInference newInference = new EqualityInference(
                metadata,
                ImmutableList.<Expression>builder()
                        .addAll(equalityPartition.getScopeEqualities())
                        .addAll(equalityPartition.getScopeComplementEqualities())
                        .addAll(equalityPartition.getScopeStraddlingEqualities())
                        .build());

        EqualityInference.EqualityPartition newEqualityPartition = newInference.generateEqualitiesPartitionedBy(symbols("c1"));

        assertThat(setCopy(equalityPartition.getScopeEqualities())).isEqualTo(setCopy(newEqualityPartition.getScopeEqualities()));
        assertThat(setCopy(equalityPartition.getScopeComplementEqualities())).isEqualTo(setCopy(newEqualityPartition.getScopeComplementEqualities()));
        assertThat(setCopy(equalityPartition.getScopeStraddlingEqualities())).isEqualTo(setCopy(newEqualityPartition.getScopeStraddlingEqualities()));
    }

    @Test
    public void testMultipleEqualitySetsPredicateGeneration()
    {
        EqualityInference inference = new EqualityInference(
                metadata,
                equals("a1", "b1"),
                equals("b1", "c1"),
                equals("c1", "d1"),
                equals("a2", "b2"),
                equals("b2", "c2"),
                equals("c2", "d2"));

        // Generating equalities for disjoint groups
        EqualityInference.EqualityPartition equalityPartition = inference.generateEqualitiesPartitionedBy(symbols("a1", "a2", "b1", "b2"));

        // There should be equalities in the scope, that only use a* and b* symbols and are all inferrable equalities
        assertThat(equalityPartition.getScopeEqualities().isEmpty()).isFalse();
        assertThat(Iterables.all(equalityPartition.getScopeEqualities(), matchesSymbolScope(symbolBeginsWith("a", "b")))).isTrue();
        assertThat(Iterables.all(equalityPartition.getScopeEqualities(), expression -> isInferenceCandidate(metadata, expression))).isTrue();

        // There should be equalities in the inverse scope, that never use a* and b* symbols and are all inferrable equalities
        assertThat(equalityPartition.getScopeComplementEqualities().isEmpty()).isFalse();
        assertThat(Iterables.all(equalityPartition.getScopeComplementEqualities(), matchesSymbolScope(not(symbolBeginsWith("a", "b"))))).isTrue();
        assertThat(Iterables.all(equalityPartition.getScopeComplementEqualities(), expression -> isInferenceCandidate(metadata, expression))).isTrue();

        // There should be equalities in the straddling scope, that should use both c1 and not c1 symbols
        assertThat(equalityPartition.getScopeStraddlingEqualities().isEmpty()).isFalse();
        assertThat(Iterables.any(equalityPartition.getScopeStraddlingEqualities(), matchesStraddlingScope(symbolBeginsWith("a", "b")))).isTrue();
        assertThat(Iterables.all(equalityPartition.getScopeStraddlingEqualities(), expression -> isInferenceCandidate(metadata, expression))).isTrue();

        // Again, there should be a "full cover" of all of the equalities used
        // THUS, we should be able to plug the generated equalities back in and get an equivalent set of equalities back the next time around
        EqualityInference newInference = new EqualityInference(
                metadata,
                ImmutableList.<Expression>builder()
                        .addAll(equalityPartition.getScopeEqualities())
                        .addAll(equalityPartition.getScopeComplementEqualities())
                        .addAll(equalityPartition.getScopeStraddlingEqualities())
                        .build());

        EqualityInference.EqualityPartition newEqualityPartition = newInference.generateEqualitiesPartitionedBy(symbols("a1", "a2", "b1", "b2"));

        assertThat(setCopy(equalityPartition.getScopeEqualities())).isEqualTo(setCopy(newEqualityPartition.getScopeEqualities()));
        assertThat(setCopy(equalityPartition.getScopeComplementEqualities())).isEqualTo(setCopy(newEqualityPartition.getScopeComplementEqualities()));
        assertThat(setCopy(equalityPartition.getScopeStraddlingEqualities())).isEqualTo(setCopy(newEqualityPartition.getScopeStraddlingEqualities()));
    }

    @Test
    public void testSubExpressionRewrites()
    {
        EqualityInference inference = new EqualityInference(
                metadata,
                equals(nameReference("a1"), add("b", "c")), // a1 = b + c
                equals(nameReference("a2"), multiply(nameReference("b"), add("b", "c"))), // a2 = b * (b + c)
                equals(nameReference("a3"), multiply(nameReference("a1"), add("b", "c")))); // a3 = a1 * (b + c)

        // Expression (b + c) should get entirely rewritten as a1
        assertThat(inference.rewrite(add("b", "c"), symbols("a1", "a2"))).isEqualTo(nameReference("a1"));

        // Only the sub-expression (b + c) should get rewritten in terms of a*
        assertThat(inference.rewrite(multiply(nameReference("ax"), add("b", "c")), symbols("ax", "a1", "a2", "a3"))).isEqualTo(multiply(nameReference("ax"), nameReference("a1")));

        // To be compliant, could rewrite either the whole expression, or just the sub-expression. Rewriting larger expressions are preferred
        assertThat(inference.rewrite(multiply(nameReference("a1"), add("b", "c")), symbols("a1", "a2", "a3"))).isEqualTo(nameReference("a3"));
    }

    @Test
    public void testConstantEqualities()
    {
        EqualityInference inference = new EqualityInference(
                metadata,
                equals("a1", "b1"),
                equals("b1", "c1"),
                equals(nameReference("c1"), number(1)));

        // Should always prefer a constant if available (constant is part of all scopes)
        assertThat(inference.rewrite(nameReference("a1"), symbols("a1", "b1"))).isEqualTo(number(1));

        // All scope equalities should utilize the constant if possible
        EqualityInference.EqualityPartition equalityPartition = inference.generateEqualitiesPartitionedBy(symbols("a1", "b1"));
        assertThat(equalitiesAsSets(equalityPartition.getScopeEqualities())).isEqualTo(set(set(nameReference("a1"), number(1)), set(nameReference("b1"), number(1))));
        assertThat(equalitiesAsSets(equalityPartition.getScopeComplementEqualities())).isEqualTo(set(set(nameReference("c1"), number(1))));

        // There should be no scope straddling equalities as the full set of equalities should be already represented by the scope and inverse scope
        assertThat(equalityPartition.getScopeStraddlingEqualities().isEmpty()).isTrue();
    }

    @Test
    public void testEqualityGeneration()
    {
        EqualityInference inference = new EqualityInference(
                metadata,
                equals(nameReference("a1"), add("b", "c")), // a1 = b + c
                equals(nameReference("e1"), add("b", "d")), // e1 = b + d
                equals("c", "d"));

        Expression scopedCanonical = inference.getScopedCanonical(nameReference("e1"), symbolBeginsWith("a"));
        assertThat(scopedCanonical).isEqualTo(nameReference("a1"));
    }

    @Test
    public void testExpressionsThatMayReturnNullOnNonNullInput()
    {
        List<Expression> candidates = ImmutableList.of(
                new Cast(nameReference("b"), BIGINT, true), // try_cast
                functionResolution
                        .functionCallBuilder(TryFunction.NAME)
                        .addArgument(new FunctionType(ImmutableList.of(), VARCHAR), new LambdaExpression(ImmutableList.of(), nameReference("b")))
                        .build(),
                new NullIfExpression(nameReference("b"), number(1)),
                new IfExpression(nameReference("b"), number(1), new NullLiteral()),
                new InPredicate(nameReference("b"), ImmutableList.of(new NullLiteral())),
                new SearchedCaseExpression(ImmutableList.of(new WhenClause(new IsNotNullPredicate(nameReference("b")), new NullLiteral())), Optional.empty()),
                new SimpleCaseExpression(nameReference("b"), ImmutableList.of(new WhenClause(number(1), new NullLiteral())), Optional.empty()),
                new SubscriptExpression(new Array(ImmutableList.of(new NullLiteral())), nameReference("b")));

        for (Expression candidate : candidates) {
            EqualityInference inference = new EqualityInference(
                    metadata,
                    equals(nameReference("b"), nameReference("x")),
                    equals(nameReference("a"), candidate));

            List<Expression> equalities = inference.generateEqualitiesPartitionedBy(symbols("b")).getScopeStraddlingEqualities();
            assertThat(equalities.size()).isEqualTo(1);
            assertThat(equalities.get(0).equals(equals(nameReference("x"), nameReference("b"))) || equalities.get(0).equals(equals(nameReference("b"), nameReference("x")))).isTrue();
        }
    }

    private static Predicate<Expression> matchesSymbolScope(Predicate<Symbol> symbolScope)
    {
        return expression -> Iterables.all(SymbolsExtractor.extractUnique(expression), symbolScope);
    }

    private static Predicate<Expression> matchesStraddlingScope(Predicate<Symbol> symbolScope)
    {
        return expression -> {
            Set<Symbol> symbols = SymbolsExtractor.extractUnique(expression);
            return Iterables.any(symbols, symbolScope) && Iterables.any(symbols, not(symbolScope));
        };
    }

    private static Expression someExpression(String symbol1, String symbol2)
    {
        return someExpression(nameReference(symbol1), nameReference(symbol2));
    }

    private static Expression someExpression(Expression expression1, Expression expression2)
    {
        return new ComparisonExpression(GREATER_THAN, expression1, expression2);
    }

    private static Expression add(String symbol1, String symbol2)
    {
        return add(nameReference(symbol1), nameReference(symbol2));
    }

    private static Expression add(Expression expression1, Expression expression2)
    {
        return new ArithmeticBinaryExpression(ArithmeticBinaryExpression.Operator.ADD, expression1, expression2);
    }

    private static Expression multiply(String symbol1, String symbol2)
    {
        return multiply(nameReference(symbol1), nameReference(symbol2));
    }

    private static Expression multiply(Expression expression1, Expression expression2)
    {
        return new ArithmeticBinaryExpression(ArithmeticBinaryExpression.Operator.MULTIPLY, expression1, expression2);
    }

    private static Expression equals(String symbol1, String symbol2)
    {
        return equals(nameReference(symbol1), nameReference(symbol2));
    }

    private static Expression equals(Expression expression1, Expression expression2)
    {
        return new ComparisonExpression(EQUAL, expression1, expression2);
    }

    private static SymbolReference nameReference(String symbol)
    {
        return new SymbolReference(symbol);
    }

    private static GenericLiteral number(long number)
    {
        if (number >= Integer.MIN_VALUE && number < Integer.MAX_VALUE) {
            return GenericLiteral.constant(INTEGER, number);
        }

        return GenericLiteral.constant(BIGINT, number);
    }

    private static Set<Symbol> symbols(String... symbols)
    {
        return Arrays.stream(symbols)
                .map(Symbol::new)
                .collect(toImmutableSet());
    }

    private static Predicate<Symbol> matchesSymbols(String... symbols)
    {
        return matchesSymbols(Arrays.asList(symbols));
    }

    private static Predicate<Symbol> matchesSymbols(Collection<String> symbols)
    {
        Set<Symbol> symbolSet = symbols.stream()
                .map(Symbol::new)
                .collect(toImmutableSet());

        return Predicates.in(symbolSet);
    }

    private static Predicate<Symbol> symbolBeginsWith(String... prefixes)
    {
        return symbolBeginsWith(Arrays.asList(prefixes));
    }

    private static Predicate<Symbol> symbolBeginsWith(Iterable<String> prefixes)
    {
        return symbol -> {
            for (String prefix : prefixes) {
                if (symbol.getName().startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        };
    }

    private static Set<Set<Expression>> equalitiesAsSets(Iterable<Expression> expressions)
    {
        ImmutableSet.Builder<Set<Expression>> builder = ImmutableSet.builder();
        for (Expression expression : expressions) {
            builder.add(equalityAsSet(expression));
        }
        return builder.build();
    }

    private static Set<Expression> equalityAsSet(Expression expression)
    {
        checkArgument(expression instanceof ComparisonExpression);
        ComparisonExpression comparisonExpression = (ComparisonExpression) expression;
        checkArgument(comparisonExpression.getOperator() == EQUAL);
        return ImmutableSet.of(comparisonExpression.getLeft(), comparisonExpression.getRight());
    }

    @SafeVarargs
    private static <E> Set<E> set(E... elements)
    {
        return ImmutableSet.copyOf(elements);
    }

    private static <E> Set<E> setCopy(Iterable<E> elements)
    {
        return ImmutableSet.copyOf(elements);
    }
}
