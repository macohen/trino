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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import io.trino.Session;
import io.trino.execution.warnings.WarningCollector;
import io.trino.metadata.FunctionResolver;
import io.trino.metadata.ResolvedFunction;
import io.trino.security.AccessControl;
import io.trino.security.AllowAllAccessControl;
import io.trino.spi.function.BoundSignature;
import io.trino.spi.function.OperatorType;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.MapType;
import io.trino.spi.type.RowType;
import io.trino.spi.type.Type;
import io.trino.spi.type.VarcharType;
import io.trino.sql.PlannerContext;
import io.trino.sql.ir.ArithmeticBinaryExpression;
import io.trino.sql.ir.ArithmeticUnaryExpression;
import io.trino.sql.ir.Array;
import io.trino.sql.ir.BetweenPredicate;
import io.trino.sql.ir.BinaryLiteral;
import io.trino.sql.ir.BindExpression;
import io.trino.sql.ir.Cast;
import io.trino.sql.ir.CoalesceExpression;
import io.trino.sql.ir.ComparisonExpression;
import io.trino.sql.ir.Expression;
import io.trino.sql.ir.FunctionCall;
import io.trino.sql.ir.GenericLiteral;
import io.trino.sql.ir.IfExpression;
import io.trino.sql.ir.InPredicate;
import io.trino.sql.ir.IntervalLiteral;
import io.trino.sql.ir.IrVisitor;
import io.trino.sql.ir.IsNotNullPredicate;
import io.trino.sql.ir.IsNullPredicate;
import io.trino.sql.ir.LambdaExpression;
import io.trino.sql.ir.LogicalExpression;
import io.trino.sql.ir.NodeRef;
import io.trino.sql.ir.NotExpression;
import io.trino.sql.ir.NullIfExpression;
import io.trino.sql.ir.NullLiteral;
import io.trino.sql.ir.Row;
import io.trino.sql.ir.SearchedCaseExpression;
import io.trino.sql.ir.SimpleCaseExpression;
import io.trino.sql.ir.StringLiteral;
import io.trino.sql.ir.SubscriptExpression;
import io.trino.sql.ir.SymbolReference;
import io.trino.type.FunctionType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.VarbinaryType.VARBINARY;
import static io.trino.type.IntervalDayTimeType.INTERVAL_DAY_TIME;
import static io.trino.type.IntervalYearMonthType.INTERVAL_YEAR_MONTH;
import static io.trino.type.UnknownType.UNKNOWN;
import static java.util.Objects.requireNonNull;

/**
 * This class is to facilitate obtaining the type of an expression and its subexpressions
 * during planning (i.e., when interacting with IR expression). It will eventually get
 * removed when we split the AST from the IR and we encode the type directly into IR expressions.
 */
public class IrTypeAnalyzer
{
    private final PlannerContext plannerContext;

    @Inject
    public IrTypeAnalyzer(PlannerContext plannerContext)
    {
        this.plannerContext = requireNonNull(plannerContext, "plannerContext is null");
    }

    public Map<NodeRef<Expression>, Type> getTypes(Session session, TypeProvider inputTypes, Iterable<Expression> expressions)
    {
        Visitor visitor = new Visitor(plannerContext, session, inputTypes);

        for (Expression expression : expressions) {
            visitor.process(expression, new Context(ImmutableMap.of()));
        }

        return visitor.getTypes();
    }

    public Map<NodeRef<Expression>, Type> getTypes(Session session, TypeProvider inputTypes, Expression expression)
    {
        return getTypes(session, inputTypes, ImmutableList.of(expression));
    }

    public Type getType(Session session, TypeProvider inputTypes, Expression expression)
    {
        return getTypes(session, inputTypes, expression).get(NodeRef.of(expression));
    }

    private static class Visitor
            extends IrVisitor<Type, Context>
    {
        private static final AccessControl ALLOW_ALL_ACCESS_CONTROL = new AllowAllAccessControl();

        private final PlannerContext plannerContext;
        private final Session session;
        private final TypeProvider symbolTypes;
        private final FunctionResolver functionResolver;

        private final Map<NodeRef<Expression>, Type> expressionTypes = new LinkedHashMap<>();

        public Visitor(PlannerContext plannerContext, Session session, TypeProvider symbolTypes)
        {
            this.plannerContext = requireNonNull(plannerContext, "plannerContext is null");
            this.session = requireNonNull(session, "session is null");
            this.symbolTypes = requireNonNull(symbolTypes, "symbolTypes is null");
            this.functionResolver = plannerContext.getFunctionResolver(WarningCollector.NOOP);
        }

        public Map<NodeRef<Expression>, Type> getTypes()
        {
            return expressionTypes;
        }

        private Type setExpressionType(Expression expression, Type type)
        {
            requireNonNull(expression, "expression cannot be null");
            requireNonNull(type, "type cannot be null");

            expressionTypes.put(NodeRef.of(expression), type);
            return type;
        }

        @Override
        public Type process(Expression node, Context context)
        {
            // don't double process a node
            Type type = expressionTypes.get(NodeRef.of(node));
            if (type != null) {
                return type;
            }

            return super.process(node, context);
        }

        @Override
        protected Type visitRow(Row node, Context context)
        {
            List<Type> types = node.getItems().stream()
                    .map(child -> process(child, context))
                    .collect(toImmutableList());

            return setExpressionType(node, RowType.anonymous(types));
        }

        @Override
        protected Type visitSymbolReference(SymbolReference node, Context context)
        {
            Symbol symbol = new Symbol(node.getName());
            Type type = context.argumentTypes().get(symbol);
            if (type == null) {
                type = symbolTypes.get(symbol);
            }
            checkArgument(type != null, "No type for: %s", node.getName());
            return setExpressionType(node, type);
        }

        @Override
        protected Type visitNotExpression(NotExpression node, Context context)
        {
            process(node.getValue(), context);
            return setExpressionType(node, BOOLEAN);
        }

        @Override
        protected Type visitLogicalExpression(LogicalExpression node, Context context)
        {
            node.getTerms().forEach(term -> process(term, context));
            return setExpressionType(node, BOOLEAN);
        }

        @Override
        protected Type visitComparisonExpression(ComparisonExpression node, Context context)
        {
            process(node.getLeft(), context);
            process(node.getRight(), context);
            return setExpressionType(node, BOOLEAN);
        }

        @Override
        protected Type visitIsNullPredicate(IsNullPredicate node, Context context)
        {
            process(node.getValue(), context);
            return setExpressionType(node, BOOLEAN);
        }

        @Override
        protected Type visitIsNotNullPredicate(IsNotNullPredicate node, Context context)
        {
            process(node.getValue(), context);
            return setExpressionType(node, BOOLEAN);
        }

        @Override
        protected Type visitNullIfExpression(NullIfExpression node, Context context)
        {
            Type firstType = process(node.getFirst(), context);
            Type ignored = process(node.getSecond(), context);

            // TODO:
            //    NULLIF(v1, v2) = IF(v1 = v2, v1)
            //    In order to compare v1 and v2, they need to have the same (coerced) type, but
            //    the result of NULLIF should be the same as v1. It's currently not possible
            //    to represent this in the IR, so we allow the types to be different for now and
            //    rely on the execution layer to insert the necessary casts.

            return setExpressionType(node, firstType);
        }

        @Override
        protected Type visitIfExpression(IfExpression node, Context context)
        {
            Type conditionType = process(node.getCondition(), context);
            checkArgument(conditionType.equals(BOOLEAN), "Condition must be boolean: %s", conditionType);

            Type trueType = process(node.getTrueValue(), context);
            if (node.getFalseValue().isPresent()) {
                Type falseType = process(node.getFalseValue().get(), context);
                checkArgument(trueType.equals(falseType), "Types must be equal: %s vs %s", trueType, falseType);
            }

            return setExpressionType(node, trueType);
        }

        @Override
        protected Type visitSearchedCaseExpression(SearchedCaseExpression node, Context context)
        {
            Set<Type> resultTypes = node.getWhenClauses().stream()
                    .map(clause -> {
                        Type operandType = process(clause.getOperand(), context);
                        checkArgument(operandType.equals(BOOLEAN), "When clause operand must be boolean: %s", operandType);
                        return setExpressionType(clause, process(clause.getResult(), context));
                    })
                    .collect(Collectors.toSet());

            checkArgument(resultTypes.size() == 1, "All result types must be the same: %s", resultTypes);
            Type resultType = resultTypes.iterator().next();
            node.getDefaultValue().ifPresent(defaultValue -> {
                Type defaultType = process(defaultValue, context);
                checkArgument(defaultType.equals(resultType), "Default result type must be the same as WHEN result types: %s vs %s", defaultType, resultType);
            });

            return setExpressionType(node, resultType);
        }

        @Override
        protected Type visitSimpleCaseExpression(SimpleCaseExpression node, Context context)
        {
            Type operandType = process(node.getOperand(), context);

            Set<Type> resultTypes = node.getWhenClauses().stream()
                    .map(clause -> {
                        Type clauseOperandType = process(clause.getOperand(), context);
                        checkArgument(clauseOperandType.equals(operandType), "WHEN clause operand type must match CASE operand type: %s vs %s", clauseOperandType, operandType);
                        return setExpressionType(clause, process(clause.getResult(), context));
                    })
                    .collect(Collectors.toSet());

            checkArgument(resultTypes.size() == 1, "All result types must be the same: %s", resultTypes);
            Type resultType = resultTypes.iterator().next();
            node.getDefaultValue().ifPresent(defaultValue -> {
                Type defaultType = process(defaultValue, context);
                checkArgument(defaultType.equals(resultType), "Default result type must be the same as WHEN result types: %s vs %s", defaultType, resultType);
            });

            return setExpressionType(node, resultType);
        }

        @Override
        protected Type visitCoalesceExpression(CoalesceExpression node, Context context)
        {
            Set<Type> types = node.getOperands().stream()
                    .map(operand -> process(operand, context))
                    .collect(Collectors.toSet());

            checkArgument(types.size() == 1, "All operands must have the same type: %s", types);
            return setExpressionType(node, types.iterator().next());
        }

        @Override
        protected Type visitArithmeticUnary(ArithmeticUnaryExpression node, Context context)
        {
            return setExpressionType(node, process(node.getValue(), context));
        }

        @Override
        protected Type visitArithmeticBinary(ArithmeticBinaryExpression node, Context context)
        {
            ImmutableList.Builder<Type> argumentTypes = ImmutableList.builder();
            argumentTypes.add(process(node.getLeft(), context));
            argumentTypes.add(process(node.getRight(), context));

            BoundSignature operatorSignature = plannerContext.getMetadata()
                    .resolveOperator(OperatorType.valueOf(node.getOperator().name()), argumentTypes.build())
                    .getSignature();

            return setExpressionType(node, operatorSignature.getReturnType());
        }

        @Override
        protected Type visitSubscriptExpression(SubscriptExpression node, Context context)
        {
            Type baseType = process(node.getBase(), context);
            process(node.getIndex(), context);
            return setExpressionType(
                    node,
                    switch (baseType) {
                        case RowType rowType -> rowType.getFields().get(Integer.parseInt(((GenericLiteral) node.getIndex()).getValue()) - 1).getType();
                        case ArrayType arrayType -> arrayType.getElementType();
                        case MapType mapType -> mapType.getValueType();
                        default -> throw new IllegalStateException("Unexpected type: " + baseType);
                    });
        }

        @Override
        protected Type visitArray(Array node, Context context)
        {
            Set<Type> types = node.getValues().stream()
                    .map(entry -> process(entry, context))
                    .collect(Collectors.toSet());

            if (types.isEmpty()) {
                return setExpressionType(node, new ArrayType(UNKNOWN));
            }

            checkArgument(types.size() == 1, "All entries must have the same type: %s", types);
            return setExpressionType(node, new ArrayType(types.iterator().next()));
        }

        @Override
        protected Type visitStringLiteral(StringLiteral node, Context context)
        {
            return setExpressionType(node, VarcharType.createVarcharType(node.length()));
        }

        @Override
        protected Type visitBinaryLiteral(BinaryLiteral node, Context context)
        {
            return setExpressionType(node, VARBINARY);
        }

        @Override
        protected Type visitGenericLiteral(GenericLiteral node, Context context)
        {
            return setExpressionType(node, node.getType());
        }

        @Override
        protected Type visitIntervalLiteral(IntervalLiteral node, Context context)
        {
            Type type;
            if (node.isYearToMonth()) {
                type = INTERVAL_YEAR_MONTH;
            }
            else {
                type = INTERVAL_DAY_TIME;
            }
            return setExpressionType(node, type);
        }

        @Override
        protected Type visitNullLiteral(NullLiteral node, Context context)
        {
            return setExpressionType(node, UNKNOWN);
        }

        @Override
        protected Type visitFunctionCall(FunctionCall node, Context context)
        {
            // Function should already be resolved in IR
            ResolvedFunction function = functionResolver.resolveFunction(session, node.getName(), null, ALLOW_ALL_ACCESS_CONTROL);

            BoundSignature signature = function.getSignature();
            for (int i = 0; i < node.getArguments().size(); i++) {
                Expression argument = node.getArguments().get(i);
                Type formalType = signature.getArgumentTypes().get(i);

                Type unused = switch (argument) {
                    case LambdaExpression lambda -> processLambdaExpression(lambda, ((FunctionType) formalType).getArgumentTypes());
                    case BindExpression bind -> processBindExpression(bind, (FunctionType) formalType, context);
                    default -> process(argument, context);
                };

                // TODO
                // checkArgument(actualType.equals(formalType), "Actual and formal argument types do not match: %s vs %s", actualType, formalType);
            }

            return setExpressionType(node, signature.getReturnType());
        }

        private Type processBindExpression(BindExpression bind, FunctionType formalType, Context context)
        {
            List<Type> argumentTypes = new ArrayList<>();

            argumentTypes.addAll(bind.getValues().stream()
                    .map(value -> process(value, context))
                    .collect(toImmutableList()));

            argumentTypes.addAll(formalType.getArgumentTypes());

            if (bind.getFunction() instanceof LambdaExpression) {
                Type unused = processLambdaExpression((LambdaExpression) bind.getFunction(), argumentTypes);
                // TODO: validate actual type and expected type are the same
                return setExpressionType(bind, formalType);
            }

            throw new UnsupportedOperationException("not yet implemented");
        }

        private Type processLambdaExpression(LambdaExpression lambda, List<Type> argumentTypes)
        {
            ImmutableMap.Builder<Symbol, Type> typeBindings = ImmutableMap.builder();
            for (int i = 0; i < argumentTypes.size(); i++) {
                typeBindings.put(
                        new Symbol(lambda.getArguments().get(i)),
                        argumentTypes.get(i));
            }

            Type returnType = process(lambda.getBody(), new Context(typeBindings.buildOrThrow()));
            return setExpressionType(lambda, new FunctionType(argumentTypes, returnType));
        }

        @Override
        protected Type visitBetweenPredicate(BetweenPredicate node, Context context)
        {
            process(node.getValue(), context);
            process(node.getMin(), context);
            process(node.getMax(), context);

            return setExpressionType(node, BOOLEAN);
        }

        @Override
        public Type visitCast(Cast node, Context context)
        {
            process(node.getExpression(), context);
            return setExpressionType(node, node.getType());
        }

        @Override
        protected Type visitInPredicate(InPredicate node, Context context)
        {
            Expression value = node.getValue();

            Type type = process(value, context);
            for (Expression item : node.getValueList()) {
                Type itemType = process(item, context);
                checkArgument(itemType.equals(type), "Types must be equal: %s vs %s", itemType, type);
            }

            return setExpressionType(node, BOOLEAN);
        }

        @Override
        protected Type visitExpression(Expression node, Context context)
        {
            throw new UnsupportedOperationException("Not a valid IR expression: " + node.getClass().getName());
        }
    }

    private record Context(Map<Symbol, Type> argumentTypes) {}
}
