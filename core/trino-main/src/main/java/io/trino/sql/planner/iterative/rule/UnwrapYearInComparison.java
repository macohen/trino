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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import io.trino.Session;
import io.trino.spi.type.LongTimestamp;
import io.trino.spi.type.TimestampType;
import io.trino.spi.type.TimestampWithTimeZoneType;
import io.trino.spi.type.Type;
import io.trino.sql.PlannerContext;
import io.trino.sql.ir.BetweenPredicate;
import io.trino.sql.ir.Cast;
import io.trino.sql.ir.ComparisonExpression;
import io.trino.sql.ir.Expression;
import io.trino.sql.ir.ExpressionTreeRewriter;
import io.trino.sql.ir.FunctionCall;
import io.trino.sql.ir.InPredicate;
import io.trino.sql.ir.IsNotNullPredicate;
import io.trino.sql.ir.IsNullPredicate;
import io.trino.sql.ir.NodeRef;
import io.trino.sql.ir.NotExpression;
import io.trino.sql.ir.NullLiteral;
import io.trino.sql.planner.IrExpressionInterpreter;
import io.trino.sql.planner.IrTypeAnalyzer;
import io.trino.sql.planner.LiteralEncoder;
import io.trino.sql.planner.NoOpSymbolResolver;
import io.trino.sql.planner.TypeProvider;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static com.google.common.collect.Iterables.getOnlyElement;
import static io.trino.metadata.GlobalFunctionCatalog.builtinFunctionName;
import static io.trino.metadata.ResolvedFunction.extractFunctionName;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.Timestamps.MICROSECONDS_PER_SECOND;
import static io.trino.sql.ir.ComparisonExpression.Operator.EQUAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.GREATER_THAN;
import static io.trino.sql.ir.ComparisonExpression.Operator.GREATER_THAN_OR_EQUAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.LESS_THAN;
import static io.trino.sql.ir.ComparisonExpression.Operator.LESS_THAN_OR_EQUAL;
import static io.trino.sql.ir.IrUtils.or;
import static io.trino.type.DateTimes.PICOSECONDS_PER_MICROSECOND;
import static io.trino.type.DateTimes.scaleFactor;
import static java.lang.Math.multiplyExact;
import static java.lang.Math.toIntExact;
import static java.time.ZoneOffset.UTC;
import static java.time.temporal.TemporalAdjusters.lastDayOfYear;
import static java.util.Objects.requireNonNull;

/**
 * Transforms a constant date/time expression t
 * <pre>
 *     year(date_time) = t
 * </pre>
 * <p>
 * into
 * <pre>
 *     date_time BETWEEN (beginning of the year t) AND (end of the year t)
 * </pre>
 * <p>
 *
 * @see UnwrapCastInComparison
 */
public class UnwrapYearInComparison
        extends ExpressionRewriteRuleSet
{
    public UnwrapYearInComparison(PlannerContext plannerContext, IrTypeAnalyzer typeAnalyzer)
    {
        super(createRewrite(plannerContext, typeAnalyzer));
    }

    private static ExpressionRewriter createRewrite(PlannerContext plannerContext, IrTypeAnalyzer typeAnalyzer)
    {
        requireNonNull(plannerContext, "plannerContext is null");
        requireNonNull(typeAnalyzer, "typeAnalyzer is null");

        return (expression, context) -> unwrapYear(context.getSession(), plannerContext, typeAnalyzer, context.getSymbolAllocator().getTypes(), expression);
    }

    private static Expression unwrapYear(Session session,
            PlannerContext plannerContext,
            IrTypeAnalyzer typeAnalyzer,
            TypeProvider types,
            Expression expression)
    {
        return ExpressionTreeRewriter.rewriteWith(new Visitor(plannerContext, typeAnalyzer, session, types), expression);
    }

    private static class Visitor
            extends io.trino.sql.ir.ExpressionRewriter<Void>
    {
        private final PlannerContext plannerContext;
        private final IrTypeAnalyzer typeAnalyzer;
        private final Session session;
        private final TypeProvider types;
        private final LiteralEncoder literalEncoder;

        public Visitor(PlannerContext plannerContext, IrTypeAnalyzer typeAnalyzer, Session session, TypeProvider types)
        {
            this.plannerContext = requireNonNull(plannerContext, "plannerContext is null");
            this.typeAnalyzer = requireNonNull(typeAnalyzer, "typeAnalyzer is null");
            this.session = requireNonNull(session, "session is null");
            this.types = requireNonNull(types, "types is null");
            this.literalEncoder = new LiteralEncoder(plannerContext);
        }

        @Override
        public Expression rewriteComparisonExpression(ComparisonExpression node, Void context, ExpressionTreeRewriter<Void> treeRewriter)
        {
            ComparisonExpression expression = treeRewriter.defaultRewrite(node, null);
            return unwrapYear(expression);
        }

        @Override
        public Expression rewriteInPredicate(InPredicate node, Void context, ExpressionTreeRewriter<Void> treeRewriter)
        {
            InPredicate inPredicate = treeRewriter.defaultRewrite(node, null);
            Expression value = inPredicate.getValue();

            if (!(value instanceof FunctionCall call) ||
                    !extractFunctionName(call.getName()).equals(builtinFunctionName("year")) ||
                    call.getArguments().size() != 1) {
                return inPredicate;
            }

            // Convert each value to a comparison expression and try to unwrap it.
            // unwrap the InPredicate only in case we manage to unwrap the entire value list
            ImmutableList.Builder<Expression> comparisonExpressions = ImmutableList.builderWithExpectedSize(node.getValueList().size());
            for (Expression rightExpression : node.getValueList()) {
                ComparisonExpression comparisonExpression = new ComparisonExpression(EQUAL, value, rightExpression);
                Expression unwrappedExpression = unwrapYear(comparisonExpression);
                if (unwrappedExpression == comparisonExpression) {
                    return inPredicate;
                }
                comparisonExpressions.add(unwrappedExpression);
            }

            return or(comparisonExpressions.build());
        }

        // Simplify `year(d) ? value`
        private Expression unwrapYear(ComparisonExpression expression)
        {
            // Expect year on the left side and value on the right side of the comparison.
            // This is provided by CanonicalizeExpressionRewriter.
            if (!(expression.getLeft() instanceof FunctionCall call) ||
                    !extractFunctionName(call.getName()).equals(builtinFunctionName("year")) ||
                    call.getArguments().size() != 1) {
                return expression;
            }

            Map<NodeRef<Expression>, Type> expressionTypes = typeAnalyzer.getTypes(session, types, expression);

            Expression argument = getOnlyElement(call.getArguments());
            Type argumentType = expressionTypes.get(NodeRef.of(argument));

            Object right = new IrExpressionInterpreter(expression.getRight(), plannerContext, session, expressionTypes)
                    .optimize(NoOpSymbolResolver.INSTANCE);

            if (right == null || right instanceof NullLiteral) {
                return switch (expression.getOperator()) {
                    case EQUAL, NOT_EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL -> new Cast(new NullLiteral(), BOOLEAN);
                    case IS_DISTINCT_FROM -> new IsNotNullPredicate(argument);
                };
            }

            if (right instanceof Expression) {
                return expression;
            }
            if (argumentType instanceof TimestampWithTimeZoneType) {
                // Cannot replace with a range due to how year operates on value's local date/time.
                // I.e. unwrapping is possible only when values are all of some fixed zone and the zone is known.
                return expression;
            }
            if (argumentType != DATE && !(argumentType instanceof TimestampType)) {
                // e.g. year(INTERVAL) not handled here
                return expression;
            }

            int year = toIntExact((Long) right);
            return switch (expression.getOperator()) {
                case EQUAL -> between(argument, argumentType, calculateRangeStartInclusive(year, argumentType), calculateRangeEndInclusive(year, argumentType));
                case NOT_EQUAL -> new NotExpression(between(argument, argumentType, calculateRangeStartInclusive(year, argumentType), calculateRangeEndInclusive(year, argumentType)));
                case IS_DISTINCT_FROM -> or(
                        new IsNullPredicate(argument),
                        new NotExpression(between(argument, argumentType, calculateRangeStartInclusive(year, argumentType), calculateRangeEndInclusive(year, argumentType))));
                case LESS_THAN -> new ComparisonExpression(LESS_THAN, argument, toExpression(calculateRangeStartInclusive(year, argumentType), argumentType));
                case LESS_THAN_OR_EQUAL -> new ComparisonExpression(LESS_THAN_OR_EQUAL, argument, toExpression(calculateRangeEndInclusive(year, argumentType), argumentType));
                case GREATER_THAN -> new ComparisonExpression(GREATER_THAN, argument, toExpression(calculateRangeEndInclusive(year, argumentType), argumentType));
                case GREATER_THAN_OR_EQUAL -> new ComparisonExpression(GREATER_THAN_OR_EQUAL, argument, toExpression(calculateRangeStartInclusive(year, argumentType), argumentType));
            };
        }

        private BetweenPredicate between(Expression argument, Type type, Object minInclusive, Object maxInclusive)
        {
            return new BetweenPredicate(
                    argument,
                    toExpression(minInclusive, type),
                    toExpression(maxInclusive, type));
        }

        private Expression toExpression(Object value, Type type)
        {
            return literalEncoder.toExpression(value, type);
        }
    }

    private static Object calculateRangeStartInclusive(int year, Type type)
    {
        if (type == DATE) {
            LocalDate firstDay = LocalDate.ofYearDay(year, 1);
            return firstDay.toEpochDay();
        }
        if (type instanceof TimestampType timestampType) {
            long yearStartEpochSecond = LocalDateTime.of(year, 1, 1, 0, 0).toEpochSecond(UTC);
            long yearStartEpochMicros = multiplyExact(yearStartEpochSecond, MICROSECONDS_PER_SECOND);
            if (timestampType.isShort()) {
                return yearStartEpochMicros;
            }
            return new LongTimestamp(yearStartEpochMicros, 0);
        }
        throw new UnsupportedOperationException("Unsupported type: " + type);
    }

    @VisibleForTesting
    public static Object calculateRangeEndInclusive(int year, Type type)
    {
        if (type == DATE) {
            LocalDate lastDay = LocalDate.ofYearDay(year, 1).with(lastDayOfYear());
            return lastDay.toEpochDay();
        }
        if (type instanceof TimestampType timestampType) {
            long nextYearStartEpochSecond = LocalDateTime.of(year + 1, 1, 1, 0, 0).toEpochSecond(UTC);
            long nextYearStartEpochMicros = multiplyExact(nextYearStartEpochSecond, MICROSECONDS_PER_SECOND);
            if (timestampType.isShort()) {
                return nextYearStartEpochMicros - scaleFactor(timestampType.getPrecision(), 6);
            }
            int picosOfMicro = toIntExact(PICOSECONDS_PER_MICROSECOND - scaleFactor(timestampType.getPrecision(), 12));
            return new LongTimestamp(nextYearStartEpochMicros - 1, picosOfMicro);
        }
        throw new UnsupportedOperationException("Unsupported type: " + type);
    }
}
