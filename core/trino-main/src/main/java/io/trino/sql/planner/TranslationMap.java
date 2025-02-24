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
import io.trino.Session;
import io.trino.json.ir.IrJsonPath;
import io.trino.metadata.ResolvedFunction;
import io.trino.operator.scalar.ArrayConstructor;
import io.trino.operator.scalar.FormatFunction;
import io.trino.operator.scalar.TryFunction;
import io.trino.spi.type.DecimalParseResult;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.Decimals;
import io.trino.spi.type.RowType;
import io.trino.spi.type.TimeType;
import io.trino.spi.type.TimeWithTimeZoneType;
import io.trino.spi.type.TimestampType;
import io.trino.spi.type.TimestampWithTimeZoneType;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeId;
import io.trino.sql.PlannerContext;
import io.trino.sql.analyzer.Analysis;
import io.trino.sql.analyzer.ResolvedField;
import io.trino.sql.analyzer.Scope;
import io.trino.sql.analyzer.TypeSignatureTranslator;
import io.trino.sql.ir.SymbolReference;
import io.trino.sql.tree.ArithmeticBinaryExpression;
import io.trino.sql.tree.ArithmeticUnaryExpression;
import io.trino.sql.tree.Array;
import io.trino.sql.tree.AtTimeZone;
import io.trino.sql.tree.BetweenPredicate;
import io.trino.sql.tree.BinaryLiteral;
import io.trino.sql.tree.BindExpression;
import io.trino.sql.tree.BooleanLiteral;
import io.trino.sql.tree.Cast;
import io.trino.sql.tree.CoalesceExpression;
import io.trino.sql.tree.ComparisonExpression;
import io.trino.sql.tree.CurrentCatalog;
import io.trino.sql.tree.CurrentDate;
import io.trino.sql.tree.CurrentPath;
import io.trino.sql.tree.CurrentSchema;
import io.trino.sql.tree.CurrentTime;
import io.trino.sql.tree.CurrentTimestamp;
import io.trino.sql.tree.CurrentUser;
import io.trino.sql.tree.DecimalLiteral;
import io.trino.sql.tree.DereferenceExpression;
import io.trino.sql.tree.DoubleLiteral;
import io.trino.sql.tree.Expression;
import io.trino.sql.tree.Extract;
import io.trino.sql.tree.FieldReference;
import io.trino.sql.tree.Format;
import io.trino.sql.tree.FunctionCall;
import io.trino.sql.tree.GenericLiteral;
import io.trino.sql.tree.Identifier;
import io.trino.sql.tree.IfExpression;
import io.trino.sql.tree.InListExpression;
import io.trino.sql.tree.InPredicate;
import io.trino.sql.tree.IntervalLiteral;
import io.trino.sql.tree.IsNotNullPredicate;
import io.trino.sql.tree.IsNullPredicate;
import io.trino.sql.tree.JsonArray;
import io.trino.sql.tree.JsonArrayElement;
import io.trino.sql.tree.JsonExists;
import io.trino.sql.tree.JsonObject;
import io.trino.sql.tree.JsonObjectMember;
import io.trino.sql.tree.JsonPathParameter;
import io.trino.sql.tree.JsonQuery;
import io.trino.sql.tree.JsonValue;
import io.trino.sql.tree.LambdaArgumentDeclaration;
import io.trino.sql.tree.LambdaExpression;
import io.trino.sql.tree.LikePredicate;
import io.trino.sql.tree.LocalTime;
import io.trino.sql.tree.LocalTimestamp;
import io.trino.sql.tree.LogicalExpression;
import io.trino.sql.tree.LongLiteral;
import io.trino.sql.tree.NodeRef;
import io.trino.sql.tree.NotExpression;
import io.trino.sql.tree.NullIfExpression;
import io.trino.sql.tree.NullLiteral;
import io.trino.sql.tree.Parameter;
import io.trino.sql.tree.Row;
import io.trino.sql.tree.SearchedCaseExpression;
import io.trino.sql.tree.SimpleCaseExpression;
import io.trino.sql.tree.StringLiteral;
import io.trino.sql.tree.SubscriptExpression;
import io.trino.sql.tree.Trim;
import io.trino.sql.tree.TryExpression;
import io.trino.sql.tree.WhenClause;
import io.trino.type.FunctionType;
import io.trino.type.JsonPath2016Type;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.trino.spi.StandardErrorCode.TOO_MANY_ARGUMENTS;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.SmallintType.SMALLINT;
import static io.trino.spi.type.TimeWithTimeZoneType.createTimeWithTimeZoneType;
import static io.trino.spi.type.TimestampWithTimeZoneType.createTimestampWithTimeZoneType;
import static io.trino.spi.type.TinyintType.TINYINT;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.sql.analyzer.ExpressionAnalyzer.JSON_NO_PARAMETERS_ROW_TYPE;
import static io.trino.sql.ir.BooleanLiteral.FALSE_LITERAL;
import static io.trino.sql.ir.BooleanLiteral.TRUE_LITERAL;
import static io.trino.sql.ir.GenericLiteral.constant;
import static io.trino.sql.planner.ScopeAware.scopeAwareKey;
import static io.trino.sql.tree.JsonQuery.EmptyOrErrorBehavior.ERROR;
import static io.trino.sql.tree.JsonQuery.QuotesBehavior.KEEP;
import static io.trino.sql.tree.JsonQuery.QuotesBehavior.OMIT;
import static io.trino.type.LikeFunctions.LIKE_FUNCTION_NAME;
import static io.trino.type.LikeFunctions.LIKE_PATTERN_FUNCTION_NAME;
import static io.trino.type.LikePatternType.LIKE_PATTERN;
import static io.trino.util.Failures.checkCondition;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Keeps mappings of fields and AST expressions to symbols in the current plan within query boundary.
 * <p>
 * AST and IR expressions use the same class hierarchy ({@link Expression},
 * but differ in the following ways:
 * <li>AST expressions contain Identifiers, while IR expressions contain SymbolReferences</li>
 * <li>FunctionCalls in AST expressions are SQL function names. In IR expressions, they contain an encoded name representing a resolved function</li>
 */
public class TranslationMap
{
    // all expressions are rewritten in terms of fields declared by this relation plan
    private final Scope scope;
    private final Analysis analysis;
    private final Map<NodeRef<LambdaArgumentDeclaration>, Symbol> lambdaArguments;
    private final Optional<TranslationMap> outerContext;
    private final Session session;
    private final PlannerContext plannerContext;

    // current mappings of underlying field -> symbol for translating direct field references
    private final Symbol[] fieldSymbols;

    // current mappings of sub-expressions -> symbol
    private final Map<ScopeAware<Expression>, Symbol> astToSymbols;
    private final Map<NodeRef<Expression>, Symbol> substitutions;

    public TranslationMap(Optional<TranslationMap> outerContext, Scope scope, Analysis analysis, Map<NodeRef<LambdaArgumentDeclaration>, Symbol> lambdaArguments, List<Symbol> fieldSymbols, Session session, PlannerContext plannerContext)
    {
        this(outerContext, scope, analysis, lambdaArguments, fieldSymbols.toArray(new Symbol[0]).clone(), ImmutableMap.of(), ImmutableMap.of(), session, plannerContext);
    }

    public TranslationMap(Optional<TranslationMap> outerContext, Scope scope, Analysis analysis, Map<NodeRef<LambdaArgumentDeclaration>, Symbol> lambdaArguments, List<Symbol> fieldSymbols, Map<ScopeAware<Expression>, Symbol> astToSymbols, Session session, PlannerContext plannerContext)
    {
        this(outerContext, scope, analysis, lambdaArguments, fieldSymbols.toArray(new Symbol[0]), astToSymbols, ImmutableMap.of(), session, plannerContext);
    }

    public TranslationMap(
            Optional<TranslationMap> outerContext,
            Scope scope,
            Analysis analysis,
            Map<NodeRef<LambdaArgumentDeclaration>, Symbol> lambdaArguments,
            Symbol[] fieldSymbols,
            Map<ScopeAware<Expression>, Symbol> astToSymbols,
            Map<NodeRef<Expression>, Symbol> substitutions,
            Session session,
            PlannerContext plannerContext)
    {
        this.outerContext = requireNonNull(outerContext, "outerContext is null");
        this.scope = requireNonNull(scope, "scope is null");
        this.analysis = requireNonNull(analysis, "analysis is null");
        this.lambdaArguments = requireNonNull(lambdaArguments, "lambdaArguments is null");
        this.session = requireNonNull(session, "session is null");
        this.plannerContext = requireNonNull(plannerContext, "plannerContext is null");
        this.substitutions = ImmutableMap.copyOf(substitutions);

        requireNonNull(fieldSymbols, "fieldSymbols is null");
        this.fieldSymbols = fieldSymbols.clone();

        requireNonNull(astToSymbols, "astToSymbols is null");
        this.astToSymbols = ImmutableMap.copyOf(astToSymbols);

        checkArgument(scope.getLocalScopeFieldCount() == fieldSymbols.length,
                "scope: %s, fields mappings: %s",
                scope.getRelationType().getAllFieldCount(),
                fieldSymbols.length);
    }

    public TranslationMap withScope(Scope scope, List<Symbol> fields)
    {
        return new TranslationMap(outerContext, scope, analysis, lambdaArguments, fields.toArray(new Symbol[0]), astToSymbols, substitutions, session, plannerContext);
    }

    public TranslationMap withNewMappings(Map<ScopeAware<Expression>, Symbol> mappings, List<Symbol> fields)
    {
        return new TranslationMap(outerContext, scope, analysis, lambdaArguments, fields, mappings, session, plannerContext);
    }

    public TranslationMap withAdditionalMappings(Map<ScopeAware<Expression>, Symbol> mappings)
    {
        Map<ScopeAware<Expression>, Symbol> newMappings = new HashMap<>();
        newMappings.putAll(this.astToSymbols);
        newMappings.putAll(mappings);

        return new TranslationMap(outerContext, scope, analysis, lambdaArguments, fieldSymbols, newMappings, substitutions, session, plannerContext);
    }

    public TranslationMap withAdditionalIdentityMappings(Map<NodeRef<Expression>, Symbol> mappings)
    {
        Map<NodeRef<Expression>, Symbol> newMappings = new HashMap<>();
        newMappings.putAll(this.substitutions);
        newMappings.putAll(mappings);

        return new TranslationMap(outerContext, scope, analysis, lambdaArguments, fieldSymbols, astToSymbols, newMappings, session, plannerContext);
    }

    public List<Symbol> getFieldSymbols()
    {
        return Collections.unmodifiableList(Arrays.asList(fieldSymbols));
    }

    public Map<ScopeAware<Expression>, Symbol> getMappings()
    {
        return astToSymbols;
    }

    public Analysis getAnalysis()
    {
        return analysis;
    }

    public boolean canTranslate(Expression expression)
    {
        if (astToSymbols.containsKey(scopeAwareKey(expression, analysis, scope)) ||
                substitutions.containsKey(NodeRef.of(expression)) ||
                expression instanceof FieldReference) {
            return true;
        }

        if (analysis.isColumnReference(expression)) {
            ResolvedField field = analysis.getColumnReferenceFields().get(NodeRef.of(expression));
            return scope.isLocalScope(field.getScope());
        }

        return false;
    }

    public io.trino.sql.ir.Expression rewrite(Expression root)
    {
        verify(analysis.isAnalyzed(root), "Expression is not analyzed (%s): %s", root.getClass().getName(), root);

        return translate(root, true);
    }

    private io.trino.sql.ir.Expression translateExpression(Expression expression)
    {
        return translate(expression, false);
    }

    private io.trino.sql.ir.Expression translate(Expression expr, boolean isRoot)
    {
        Optional<SymbolReference> mapped = tryGetMapping(expr);

        io.trino.sql.ir.Expression result;
        if (mapped.isPresent()) {
            result = mapped.get();
        }
        else {
            result = switch (expr) {
                case FieldReference expression -> translate(expression);
                case Identifier expression -> translate(expression);
                case FunctionCall expression -> translate(expression);
                case DereferenceExpression expression -> translate(expression);
                case Array expression -> translate(expression);
                case CurrentCatalog expression -> translate(expression);
                case CurrentSchema expression -> translate(expression);
                case CurrentPath expression -> translate(expression);
                case CurrentUser expression -> translate(expression);
                case CurrentDate expression -> translate(expression);
                case CurrentTime expression -> translate(expression);
                case CurrentTimestamp expression -> translate(expression);
                case LocalTime expression -> translate(expression);
                case LocalTimestamp expression -> translate(expression);
                case Extract expression -> translate(expression);
                case AtTimeZone expression -> translate(expression);
                case Format expression -> translate(expression);
                case TryExpression expression -> translate(expression);
                case LikePredicate expression -> translate(expression);
                case Trim expression -> translate(expression);
                case SubscriptExpression expression -> translate(expression);
                case LambdaExpression expression -> translate(expression);
                case Parameter expression -> translate(expression);
                case JsonExists expression -> translate(expression);
                case JsonValue expression -> translate(expression);
                case JsonQuery expression -> translate(expression);
                case JsonObject expression -> translate(expression);
                case JsonArray expression -> translate(expression);
                case LongLiteral expression -> translate(expression);
                case DoubleLiteral expression -> translate(expression);
                case StringLiteral expression -> translate(expression);
                case BooleanLiteral expression -> translate(expression);
                case DecimalLiteral expression -> translate(expression);
                case GenericLiteral expression -> translate(expression);
                case BinaryLiteral expression -> translate(expression);
                case IntervalLiteral expression -> translate(expression);
                case ArithmeticBinaryExpression expression -> translate(expression);
                case ArithmeticUnaryExpression expression -> translate(expression);
                case ComparisonExpression expression -> translate(expression);
                case Cast expression -> translate(expression);
                case Row expression -> translate(expression);
                case NotExpression expression -> translate(expression);
                case LogicalExpression expression -> translate(expression);
                case NullLiteral expression -> new io.trino.sql.ir.NullLiteral();
                case CoalesceExpression expression -> translate(expression);
                case IsNullPredicate expression -> translate(expression);
                case IsNotNullPredicate expression -> translate(expression);
                case BetweenPredicate expression -> translate(expression);
                case IfExpression expression -> translate(expression);
                case InPredicate expression -> translate(expression);
                case SimpleCaseExpression expression -> translate(expression);
                case SearchedCaseExpression expression -> translate(expression);
                case WhenClause expression -> translate(expression);
                case NullIfExpression expression -> translate(expression);
                case BindExpression expression -> translate(expression);
                default -> throw new IllegalArgumentException("Unsupported expression (%s): %s".formatted(expr.getClass().getName(), expr));
            };
        }

        // Don't add a coercion for the top-level expression. That depends on the context
        // the expression is used and it's the responsibility of the caller.
        return isRoot ? result : QueryPlanner.coerceIfNecessary(analysis, expr, result);
    }

    private io.trino.sql.ir.Expression translate(BindExpression expression)
    {
        return new io.trino.sql.ir.BindExpression(
                expression.getValues().stream()
                        .map(this::translateExpression)
                        .collect(toImmutableList()),
                translateExpression(expression.getFunction()));
    }

    private io.trino.sql.ir.Expression translate(NullIfExpression expression)
    {
        return new io.trino.sql.ir.NullIfExpression(
                translateExpression(expression.getFirst()),
                translateExpression(expression.getSecond()));
    }

    private io.trino.sql.ir.Expression translate(ArithmeticUnaryExpression expression)
    {
        return new io.trino.sql.ir.ArithmeticUnaryExpression(
                switch (expression.getSign()) {
                    case PLUS -> io.trino.sql.ir.ArithmeticUnaryExpression.Sign.PLUS;
                    case MINUS -> io.trino.sql.ir.ArithmeticUnaryExpression.Sign.MINUS;
                },
                translateExpression(expression.getValue()));
    }

    private io.trino.sql.ir.Expression translate(IntervalLiteral expression)
    {
        return new io.trino.sql.ir.IntervalLiteral(
                expression.getValue(),
                switch (expression.getSign()) {
                    case POSITIVE -> io.trino.sql.ir.IntervalLiteral.Sign.POSITIVE;
                    case NEGATIVE -> io.trino.sql.ir.IntervalLiteral.Sign.NEGATIVE;
                },
                translate(expression.getStartField()),
                expression.getEndField().map(this::translate));
    }

    private io.trino.sql.ir.IntervalLiteral.IntervalField translate(IntervalLiteral.IntervalField field)
    {
        return switch (field) {
            case YEAR -> io.trino.sql.ir.IntervalLiteral.IntervalField.YEAR;
            case MONTH -> io.trino.sql.ir.IntervalLiteral.IntervalField.MONTH;
            case DAY -> io.trino.sql.ir.IntervalLiteral.IntervalField.DAY;
            case HOUR -> io.trino.sql.ir.IntervalLiteral.IntervalField.HOUR;
            case MINUTE -> io.trino.sql.ir.IntervalLiteral.IntervalField.MINUTE;
            case SECOND -> io.trino.sql.ir.IntervalLiteral.IntervalField.SECOND;
        };
    }

    private io.trino.sql.ir.WhenClause translate(WhenClause expression)
    {
        return new io.trino.sql.ir.WhenClause(
                translateExpression(expression.getOperand()),
                translateExpression(expression.getResult()));
    }

    private io.trino.sql.ir.Expression translate(SearchedCaseExpression expression)
    {
        return new io.trino.sql.ir.SearchedCaseExpression(
                expression.getWhenClauses().stream()
                        .map(this::translate)
                        .collect(toImmutableList()),
                expression.getDefaultValue().map(this::translateExpression));
    }

    private io.trino.sql.ir.Expression translate(SimpleCaseExpression expression)
    {
        return new io.trino.sql.ir.SimpleCaseExpression(
                translateExpression(expression.getOperand()),
                expression.getWhenClauses().stream()
                        .map(this::translate)
                        .collect(toImmutableList()),
                expression.getDefaultValue().map(this::translateExpression));
    }

    private io.trino.sql.ir.Expression translate(InPredicate expression)
    {
        return new io.trino.sql.ir.InPredicate(
                translateExpression(expression.getValue()),
                ((InListExpression) expression.getValueList()).getValues().stream()
                        .map(this::translateExpression)
                        .collect(toImmutableList()));
    }

    private io.trino.sql.ir.Expression translate(IfExpression expression)
    {
        return new io.trino.sql.ir.IfExpression(
                translateExpression(expression.getCondition()),
                translateExpression(expression.getTrueValue()),
                expression.getFalseValue().map(this::translateExpression));
    }

    private io.trino.sql.ir.Expression translate(BinaryLiteral expression)
    {
        return new io.trino.sql.ir.BinaryLiteral(expression.getValue());
    }

    private io.trino.sql.ir.Expression translate(BetweenPredicate expression)
    {
        return new io.trino.sql.ir.BetweenPredicate(
                translateExpression(expression.getValue()),
                translateExpression(expression.getMin()),
                translateExpression(expression.getMax()));
    }

    private io.trino.sql.ir.Expression translate(IsNullPredicate expression)
    {
        return new io.trino.sql.ir.IsNullPredicate(translateExpression(expression.getValue()));
    }

    private io.trino.sql.ir.Expression translate(IsNotNullPredicate expression)
    {
        return new io.trino.sql.ir.IsNotNullPredicate(translateExpression(expression.getValue()));
    }

    private io.trino.sql.ir.Expression translate(CoalesceExpression expression)
    {
        return new io.trino.sql.ir.CoalesceExpression(expression.getOperands().stream()
                .map(this::translateExpression)
                .collect(toImmutableList()));
    }

    private io.trino.sql.ir.Expression translate(GenericLiteral expression)
    {
        // TODO: record the parsed values in the analyzer and pull them out here
        Type type = analysis.getType(expression);

        if (type.equals(TINYINT) || type.equals(SMALLINT) || type.equals(INTEGER) || type.equals(BIGINT)) {
            return constant(type, Long.parseLong(expression.getValue()));
        }

        if (type.equals(DOUBLE)) {
            return constant(type, Double.parseDouble(expression.getValue()));
        }

        if (type.equals(BOOLEAN)) {
            return constant(type, Boolean.valueOf(expression.getValue()));
        }

        return new io.trino.sql.ir.GenericLiteral(type, expression.getValue());
    }

    private io.trino.sql.ir.Expression translate(DecimalLiteral expression)
    {
        DecimalType type = (DecimalType) analysis.getType(expression);

        // TODO: record the parsed values in the analyzer and pull them out here
        DecimalParseResult parsed = Decimals.parse(expression.getValue());
        checkState(parsed.getType().equals(type));

        return io.trino.sql.ir.GenericLiteral.constant(type, parsed.getObject());
    }

    private io.trino.sql.ir.Expression translate(LogicalExpression expression)
    {
        return new io.trino.sql.ir.LogicalExpression(
                switch (expression.getOperator()) {
                    case AND -> io.trino.sql.ir.LogicalExpression.Operator.AND;
                    case OR -> io.trino.sql.ir.LogicalExpression.Operator.OR;
                },
                expression.getTerms().stream()
                        .map(this::translateExpression)
                        .collect(toImmutableList()));
    }

    private io.trino.sql.ir.Expression translate(BooleanLiteral expression)
    {
        if (expression.equals(BooleanLiteral.TRUE_LITERAL)) {
            return TRUE_LITERAL;
        }

        if (expression.equals(BooleanLiteral.FALSE_LITERAL)) {
            return FALSE_LITERAL;
        }

        throw new IllegalArgumentException("Unknown boolean literal: " + expression);
    }

    private io.trino.sql.ir.Expression translate(NotExpression expression)
    {
        return new io.trino.sql.ir.NotExpression(translateExpression(expression.getValue()));
    }

    private io.trino.sql.ir.Expression translate(Row expression)
    {
        return new io.trino.sql.ir.Row(expression.getItems().stream()
                .map(this::translateExpression)
                .collect(toImmutableList()));
    }

    private io.trino.sql.ir.Expression translate(ComparisonExpression expression)
    {
        return new io.trino.sql.ir.ComparisonExpression(
                switch (expression.getOperator()) {
                    case EQUAL -> io.trino.sql.ir.ComparisonExpression.Operator.EQUAL;
                    case NOT_EQUAL -> io.trino.sql.ir.ComparisonExpression.Operator.NOT_EQUAL;
                    case LESS_THAN -> io.trino.sql.ir.ComparisonExpression.Operator.LESS_THAN;
                    case LESS_THAN_OR_EQUAL -> io.trino.sql.ir.ComparisonExpression.Operator.LESS_THAN_OR_EQUAL;
                    case GREATER_THAN -> io.trino.sql.ir.ComparisonExpression.Operator.GREATER_THAN;
                    case GREATER_THAN_OR_EQUAL -> io.trino.sql.ir.ComparisonExpression.Operator.GREATER_THAN_OR_EQUAL;
                    case IS_DISTINCT_FROM -> io.trino.sql.ir.ComparisonExpression.Operator.IS_DISTINCT_FROM;
                },
                translateExpression(expression.getLeft()),
                translateExpression(expression.getRight()));
    }

    private io.trino.sql.ir.Expression translate(Cast expression)
    {
        return new io.trino.sql.ir.Cast(
                translateExpression(expression.getExpression()),
                analysis.getType(expression),
                expression.isSafe());
    }

    private io.trino.sql.ir.Expression translate(DoubleLiteral expression)
    {
        return constant(DOUBLE, expression.getValue());
    }

    private io.trino.sql.ir.Expression translate(ArithmeticBinaryExpression expression)
    {
        return new io.trino.sql.ir.ArithmeticBinaryExpression(
                switch (expression.getOperator()) {
                    case ADD -> io.trino.sql.ir.ArithmeticBinaryExpression.Operator.ADD;
                    case SUBTRACT -> io.trino.sql.ir.ArithmeticBinaryExpression.Operator.SUBTRACT;
                    case MULTIPLY -> io.trino.sql.ir.ArithmeticBinaryExpression.Operator.MULTIPLY;
                    case DIVIDE -> io.trino.sql.ir.ArithmeticBinaryExpression.Operator.DIVIDE;
                    case MODULUS -> io.trino.sql.ir.ArithmeticBinaryExpression.Operator.MODULUS;
                },
                translateExpression(expression.getLeft()),
                translateExpression(expression.getRight()));
    }

    private io.trino.sql.ir.Expression translate(StringLiteral expression)
    {
        return new io.trino.sql.ir.StringLiteral(expression.getValue());
    }

    private io.trino.sql.ir.Expression translate(LongLiteral expression)
    {
        return constant(analysis.getType(expression), expression.getParsedValue());
    }

    private io.trino.sql.ir.Expression translate(FieldReference expression)
    {
        return getSymbolForColumn(expression)
                .map(Symbol::toSymbolReference)
                .orElseThrow(() -> new IllegalStateException(format("No symbol mapping for node '%s' (%s)", expression, expression.getFieldIndex())));
    }

    private io.trino.sql.ir.Expression translate(Identifier expression)
    {
        LambdaArgumentDeclaration referencedLambdaArgumentDeclaration = analysis.getLambdaArgumentReference(expression);
        if (referencedLambdaArgumentDeclaration != null) {
            Symbol symbol = lambdaArguments.get(NodeRef.of(referencedLambdaArgumentDeclaration));
            return symbol.toSymbolReference();
        }

        return getSymbolForColumn(expression)
                .map(Symbol::toSymbolReference)
                .get();
    }

    private io.trino.sql.ir.Expression translate(FunctionCall expression)
    {
        if (analysis.isPatternNavigationFunction(expression)) {
            return translate(expression.getArguments().getFirst(), false);
        }

        ResolvedFunction resolvedFunction = analysis.getResolvedFunction(expression);
        checkArgument(resolvedFunction != null, "Function has not been analyzed: %s", expression);

        return new io.trino.sql.ir.FunctionCall(
                resolvedFunction.toQualifiedName(),
                expression.getArguments().stream()
                        .map(this::translateExpression)
                        .collect(toImmutableList()));
    }

    private io.trino.sql.ir.Expression translate(DereferenceExpression expression)
    {
        if (analysis.isColumnReference(expression)) {
            return getSymbolForColumn(expression)
                    .map(Symbol::toSymbolReference)
                    .orElseThrow(() -> new IllegalStateException(format("No mapping for %s", expression)));
        }

        RowType rowType = (RowType) analysis.getType(expression.getBase());
        String fieldName = expression.getField().orElseThrow().getValue();

        List<RowType.Field> fields = rowType.getFields();
        int index = -1;
        for (int i = 0; i < fields.size(); i++) {
            RowType.Field field = fields.get(i);
            if (field.getName().isPresent() && field.getName().get().equalsIgnoreCase(fieldName)) {
                checkArgument(index < 0, "Ambiguous field %s in type %s", field, rowType.getDisplayName());
                index = i;
            }
        }

        checkState(index >= 0, "could not find field name: %s", fieldName);

        return new io.trino.sql.ir.SubscriptExpression(
                translateExpression(expression.getBase()),
                constant(INTEGER, (long) (index + 1)));
    }

    private io.trino.sql.ir.Expression translate(Array expression)
    {
        checkCondition(expression.getValues().size() <= 254, TOO_MANY_ARGUMENTS, "Too many arguments for array constructor");

        List<Type> types = expression.getValues().stream()
                .map(analysis::getType)
                .collect(toImmutableList());

        List<io.trino.sql.ir.Expression> values = expression.getValues().stream()
                .map(this::translateExpression)
                .collect(toImmutableList());

        return BuiltinFunctionCallBuilder.resolve(plannerContext.getMetadata())
                .setName(ArrayConstructor.NAME)
                .setArguments(types, values)
                .build();
    }

    private io.trino.sql.ir.Expression translate(CurrentCatalog unused)
    {
        return new io.trino.sql.ir.FunctionCall(
                plannerContext.getMetadata()
                        .resolveBuiltinFunction("$current_catalog", ImmutableList.of())
                        .toQualifiedName(),
                ImmutableList.of());
    }

    private io.trino.sql.ir.Expression translate(CurrentSchema unused)
    {
        return new io.trino.sql.ir.FunctionCall(
                plannerContext.getMetadata()
                        .resolveBuiltinFunction("$current_schema", ImmutableList.of())
                        .toQualifiedName(),
                ImmutableList.of());
    }

    private io.trino.sql.ir.Expression translate(CurrentPath unused)
    {
        return new io.trino.sql.ir.FunctionCall(
                plannerContext.getMetadata()
                        .resolveBuiltinFunction("$current_path", ImmutableList.of())
                        .toQualifiedName(),
                ImmutableList.of());
    }

    private io.trino.sql.ir.Expression translate(CurrentUser unused)
    {
        return new io.trino.sql.ir.FunctionCall(
                plannerContext.getMetadata()
                        .resolveBuiltinFunction("$current_user", ImmutableList.of())
                        .toQualifiedName(),
                ImmutableList.of());
    }

    private io.trino.sql.ir.Expression translate(CurrentDate unused)
    {
        return BuiltinFunctionCallBuilder.resolve(plannerContext.getMetadata())
                .setName("current_date")
                .build();
    }

    private io.trino.sql.ir.Expression translate(CurrentTime node)
    {
        return BuiltinFunctionCallBuilder.resolve(plannerContext.getMetadata())
                .setName("$current_time")
                .setArguments(
                        ImmutableList.of(analysis.getType(node)),
                        ImmutableList.of(new io.trino.sql.ir.Cast(new io.trino.sql.ir.NullLiteral(), analysis.getType(node))))
                .build();
    }

    private io.trino.sql.ir.Expression translate(CurrentTimestamp node)
    {
        return BuiltinFunctionCallBuilder.resolve(plannerContext.getMetadata())
                .setName("$current_timestamp")
                .setArguments(
                        ImmutableList.of(analysis.getType(node)),
                        ImmutableList.of(new io.trino.sql.ir.Cast(new io.trino.sql.ir.NullLiteral(), analysis.getType(node))))
                .build();
    }

    private io.trino.sql.ir.Expression translate(LocalTime node)
    {
        return BuiltinFunctionCallBuilder.resolve(plannerContext.getMetadata())
                .setName("$localtime")
                .setArguments(
                        ImmutableList.of(analysis.getType(node)),
                        ImmutableList.of(new io.trino.sql.ir.Cast(new io.trino.sql.ir.NullLiteral(), analysis.getType(node))))
                .build();
    }

    private io.trino.sql.ir.Expression translate(LocalTimestamp node)
    {
        return BuiltinFunctionCallBuilder.resolve(plannerContext.getMetadata())
                .setName("$localtimestamp")
                .setArguments(
                        ImmutableList.of(analysis.getType(node)),
                        ImmutableList.of(new io.trino.sql.ir.Cast(new io.trino.sql.ir.NullLiteral(), analysis.getType(node))))
                .build();
    }

    private io.trino.sql.ir.Expression translate(Extract node)
    {
        io.trino.sql.ir.Expression value = translateExpression(node.getExpression());
        Type type = analysis.getType(node.getExpression());

        return switch (node.getField()) {
            case YEAR -> BuiltinFunctionCallBuilder.resolve(plannerContext.getMetadata())
                    .setName("year")
                    .addArgument(type, value)
                    .build();
            case QUARTER -> BuiltinFunctionCallBuilder.resolve(plannerContext.getMetadata())
                    .setName("quarter")
                    .addArgument(type, value)
                    .build();
            case MONTH -> BuiltinFunctionCallBuilder.resolve(plannerContext.getMetadata())
                    .setName("month")
                    .addArgument(type, value)
                    .build();
            case WEEK -> BuiltinFunctionCallBuilder.resolve(plannerContext.getMetadata())
                    .setName("week")
                    .addArgument(type, value)
                    .build();
            case DAY, DAY_OF_MONTH -> BuiltinFunctionCallBuilder.resolve(plannerContext.getMetadata())
                    .setName("day")
                    .addArgument(type, value)
                    .build();
            case DAY_OF_WEEK, DOW -> BuiltinFunctionCallBuilder.resolve(plannerContext.getMetadata())
                    .setName("day_of_week")
                    .addArgument(type, value)
                    .build();
            case DAY_OF_YEAR, DOY -> BuiltinFunctionCallBuilder.resolve(plannerContext.getMetadata())
                    .setName("day_of_year")
                    .addArgument(type, value)
                    .build();
            case YEAR_OF_WEEK, YOW -> BuiltinFunctionCallBuilder.resolve(plannerContext.getMetadata())
                    .setName("year_of_week")
                    .addArgument(type, value)
                    .build();
            case HOUR -> BuiltinFunctionCallBuilder.resolve(plannerContext.getMetadata())
                    .setName("hour")
                    .addArgument(type, value)
                    .build();
            case MINUTE -> BuiltinFunctionCallBuilder.resolve(plannerContext.getMetadata())
                    .setName("minute")
                    .addArgument(type, value)
                    .build();
            case SECOND -> BuiltinFunctionCallBuilder.resolve(plannerContext.getMetadata())
                    .setName("second")
                    .addArgument(type, value)
                    .build();
            case TIMEZONE_MINUTE -> BuiltinFunctionCallBuilder.resolve(plannerContext.getMetadata())
                    .setName("timezone_minute")
                    .addArgument(type, value)
                    .build();
            case TIMEZONE_HOUR -> BuiltinFunctionCallBuilder.resolve(plannerContext.getMetadata())
                    .setName("timezone_hour")
                    .addArgument(type, value)
                    .build();
        };
    }

    private io.trino.sql.ir.Expression translate(AtTimeZone node)
    {
        Type valueType = analysis.getType(node.getValue());
        io.trino.sql.ir.Expression value = translateExpression(node.getValue());

        Type timeZoneType = analysis.getType(node.getTimeZone());
        io.trino.sql.ir.Expression timeZone = translateExpression(node.getTimeZone());

        io.trino.sql.ir.FunctionCall call;
        if (valueType instanceof TimeType type) {
            call = BuiltinFunctionCallBuilder.resolve(plannerContext.getMetadata())
                    .setName("$at_timezone")
                    .addArgument(createTimeWithTimeZoneType(type.getPrecision()), new io.trino.sql.ir.Cast(value, createTimeWithTimeZoneType(((TimeType) valueType).getPrecision())))
                    .addArgument(timeZoneType, timeZone)
                    .build();
        }
        else if (valueType instanceof TimeWithTimeZoneType) {
            call = BuiltinFunctionCallBuilder.resolve(plannerContext.getMetadata())
                    .setName("$at_timezone")
                    .addArgument(valueType, value)
                    .addArgument(timeZoneType, timeZone)
                    .build();
        }
        else if (valueType instanceof TimestampType type) {
            call = BuiltinFunctionCallBuilder.resolve(plannerContext.getMetadata())
                    .setName("at_timezone")
                    .addArgument(createTimestampWithTimeZoneType(type.getPrecision()), new io.trino.sql.ir.Cast(value, createTimestampWithTimeZoneType(((TimestampType) valueType).getPrecision())))
                    .addArgument(timeZoneType, timeZone)
                    .build();
        }
        else if (valueType instanceof TimestampWithTimeZoneType) {
            call = BuiltinFunctionCallBuilder.resolve(plannerContext.getMetadata())
                    .setName("at_timezone")
                    .addArgument(valueType, value)
                    .addArgument(timeZoneType, timeZone)
                    .build();
        }
        else {
            throw new IllegalArgumentException("Unexpected type: " + valueType);
        }

        return call;
    }

    private io.trino.sql.ir.Expression translate(Format node)
    {
        List<io.trino.sql.ir.Expression> arguments = node.getArguments().stream()
                .map(this::translateExpression)
                .collect(toImmutableList());
        List<Type> argumentTypes = node.getArguments().stream()
                .map(analysis::getType)
                .collect(toImmutableList());

        io.trino.sql.ir.FunctionCall call = BuiltinFunctionCallBuilder.resolve(plannerContext.getMetadata())
                .setName(FormatFunction.NAME)
                .addArgument(VARCHAR, arguments.get(0))
                .addArgument(RowType.anonymous(argumentTypes.subList(1, arguments.size())), new io.trino.sql.ir.Row(arguments.subList(1, arguments.size())))
                .build();

        return call;
    }

    private io.trino.sql.ir.Expression translate(TryExpression node)
    {
        Type type = analysis.getType(node);
        io.trino.sql.ir.Expression expression = translateExpression(node.getInnerExpression());

        return BuiltinFunctionCallBuilder.resolve(plannerContext.getMetadata())
                .setName(TryFunction.NAME)
                .addArgument(new FunctionType(ImmutableList.of(), type), new io.trino.sql.ir.LambdaExpression(ImmutableList.of(), expression))
                .build();
    }

    private io.trino.sql.ir.Expression translate(LikePredicate node)
    {
        io.trino.sql.ir.Expression value = translateExpression(node.getValue());
        io.trino.sql.ir.Expression pattern = translateExpression(node.getPattern());
        Optional<io.trino.sql.ir.Expression> escape = node.getEscape().map(this::translateExpression);

        io.trino.sql.ir.FunctionCall patternCall;
        if (escape.isPresent()) {
            patternCall = BuiltinFunctionCallBuilder.resolve(plannerContext.getMetadata())
                    .setName(LIKE_PATTERN_FUNCTION_NAME)
                    .addArgument(analysis.getType(node.getPattern()), pattern)
                    .addArgument(analysis.getType(node.getEscape().get()), escape.get())
                    .build();
        }
        else {
            patternCall = BuiltinFunctionCallBuilder.resolve(plannerContext.getMetadata())
                    .setName(LIKE_PATTERN_FUNCTION_NAME)
                    .addArgument(analysis.getType(node.getPattern()), pattern)
                    .build();
        }

        io.trino.sql.ir.FunctionCall call = BuiltinFunctionCallBuilder.resolve(plannerContext.getMetadata())
                .setName(LIKE_FUNCTION_NAME)
                .addArgument(analysis.getType(node.getValue()), value)
                .addArgument(LIKE_PATTERN, patternCall)
                .build();

        return call;
    }

    private io.trino.sql.ir.Expression translate(Trim node)
    {
        ResolvedFunction resolvedFunction = analysis.getResolvedFunction(node);
        checkArgument(resolvedFunction != null, "Function has not been analyzed: %s", node);

        ImmutableList.Builder<io.trino.sql.ir.Expression> arguments = ImmutableList.builder();
        arguments.add(translateExpression(node.getTrimSource()));
        node.getTrimCharacter()
                .map(this::translateExpression)
                .ifPresent(arguments::add);

        return new io.trino.sql.ir.FunctionCall(resolvedFunction.toQualifiedName(), arguments.build());
    }

    private io.trino.sql.ir.Expression translate(SubscriptExpression node)
    {
        Type baseType = analysis.getType(node.getBase());
        if (baseType instanceof RowType) {
            // Do not rewrite subscript index into symbol. Row subscript index is required to be a literal.
            io.trino.sql.ir.Expression rewrittenBase = translateExpression(node.getBase());
            LongLiteral index = (LongLiteral) node.getIndex();
            return new io.trino.sql.ir.SubscriptExpression(rewrittenBase, constant(INTEGER, index.getParsedValue()));
        }

        return new io.trino.sql.ir.SubscriptExpression(
                translateExpression(node.getBase()),
                translateExpression(node.getIndex()));
    }

    private io.trino.sql.ir.Expression translate(LambdaExpression node)
    {
        checkState(analysis.getCoercion(node) == null, "cannot coerce a lambda expression");

        ImmutableList.Builder<String> newArguments = ImmutableList.builder();
        for (LambdaArgumentDeclaration argument : node.getArguments()) {
            Symbol symbol = lambdaArguments.get(NodeRef.of(argument));
            newArguments.add(symbol.getName());
        }
        io.trino.sql.ir.Expression rewrittenBody = translateExpression(node.getBody());
        return new io.trino.sql.ir.LambdaExpression(newArguments.build(), rewrittenBody);
    }

    private io.trino.sql.ir.Expression translate(Parameter node)
    {
        checkState(analysis.getParameters().size() > node.getId(), "Too few parameter values");
        return translateExpression(analysis.getParameters().get(NodeRef.of(node)));
    }

    private io.trino.sql.ir.Expression translate(JsonExists node)
    {
        ResolvedFunction resolvedFunction = analysis.getResolvedFunction(node);
        checkArgument(resolvedFunction != null, "Function has not been analyzed: %s", node);

        //  apply the input function to the input expression
        io.trino.sql.ir.GenericLiteral failOnError = constant(BOOLEAN, node.getErrorBehavior() == JsonExists.ErrorBehavior.ERROR);
        ResolvedFunction inputToJson = analysis.getJsonInputFunction(node.getJsonPathInvocation().getInputExpression());
        io.trino.sql.ir.Expression input = new io.trino.sql.ir.FunctionCall(inputToJson.toQualifiedName(), ImmutableList.of(
                translateExpression(node.getJsonPathInvocation().getInputExpression()),
                failOnError));

        // apply the input functions to the JSON path parameters having FORMAT,
        // and collect all JSON path parameters in a Row
        ParametersRow orderedParameters = getParametersRow(
                node.getJsonPathInvocation().getPathParameters(),
                node.getJsonPathInvocation().getPathParameters().stream()
                        .map(parameter -> translateExpression(parameter.getParameter()))
                        .toList(),
                resolvedFunction.getSignature().getArgumentType(2),
                failOnError);

        IrJsonPath path = new JsonPathTranslator(session, plannerContext).rewriteToIr(analysis.getJsonPathAnalysis(node), orderedParameters.getParametersOrder());
        io.trino.sql.ir.Expression pathExpression = new LiteralEncoder(plannerContext).toExpression(path, plannerContext.getTypeManager().getType(TypeId.of(JsonPath2016Type.NAME)));

        ImmutableList.Builder<io.trino.sql.ir.Expression> arguments = ImmutableList.<io.trino.sql.ir.Expression>builder()
                .add(input)
                .add(pathExpression)
                .add(orderedParameters.getParametersRow())
                .add(constant(TINYINT, (long) node.getErrorBehavior().ordinal()));

        return new io.trino.sql.ir.FunctionCall(resolvedFunction.toQualifiedName(), arguments.build());
    }

    private io.trino.sql.ir.Expression translate(JsonValue node)
    {
        ResolvedFunction resolvedFunction = analysis.getResolvedFunction(node);
        checkArgument(resolvedFunction != null, "Function has not been analyzed: %s", node);

        //  apply the input function to the input expression
        io.trino.sql.ir.GenericLiteral failOnError = constant(BOOLEAN, node.getErrorBehavior() == JsonValue.EmptyOrErrorBehavior.ERROR);
        ResolvedFunction inputToJson = analysis.getJsonInputFunction(node.getJsonPathInvocation().getInputExpression());
        io.trino.sql.ir.Expression input = new io.trino.sql.ir.FunctionCall(inputToJson.toQualifiedName(), ImmutableList.of(
                translateExpression(node.getJsonPathInvocation().getInputExpression()),
                failOnError));

        // apply the input functions to the JSON path parameters having FORMAT,
        // and collect all JSON path parameters in a Row
        ParametersRow orderedParameters = getParametersRow(
                node.getJsonPathInvocation().getPathParameters(),
                node.getJsonPathInvocation().getPathParameters().stream()
                        .map(parameter -> translateExpression(parameter.getParameter()))
                        .toList(),
                resolvedFunction.getSignature().getArgumentType(2),
                failOnError);

        IrJsonPath path = new JsonPathTranslator(session, plannerContext).rewriteToIr(analysis.getJsonPathAnalysis(node), orderedParameters.getParametersOrder());
        io.trino.sql.ir.Expression pathExpression = new LiteralEncoder(plannerContext).toExpression(path, plannerContext.getTypeManager().getType(TypeId.of(JsonPath2016Type.NAME)));

        ImmutableList.Builder<io.trino.sql.ir.Expression> arguments = ImmutableList.<io.trino.sql.ir.Expression>builder()
                .add(input)
                .add(pathExpression)
                .add(orderedParameters.getParametersRow())
                .add(constant(TINYINT, (long) node.getEmptyBehavior().ordinal()))
                .add(node.getEmptyDefault()
                        .map(this::translateExpression)
                        .orElseGet(() -> new io.trino.sql.ir.Cast(new io.trino.sql.ir.NullLiteral(), resolvedFunction.getSignature().getReturnType())))
                .add(constant(TINYINT, (long) node.getErrorBehavior().ordinal()))
                .add(node.getErrorDefault()
                        .map(this::translateExpression)
                        .orElseGet(() -> new io.trino.sql.ir.Cast(new io.trino.sql.ir.NullLiteral(), resolvedFunction.getSignature().getReturnType())));

        return new io.trino.sql.ir.FunctionCall(resolvedFunction.toQualifiedName(), arguments.build());
    }

    private io.trino.sql.ir.Expression translate(JsonQuery node)
    {
        ResolvedFunction resolvedFunction = analysis.getResolvedFunction(node);
        checkArgument(resolvedFunction != null, "Function has not been analyzed: %s", node);

        //  apply the input function to the input expression
        io.trino.sql.ir.GenericLiteral failOnError = constant(BOOLEAN, node.getErrorBehavior() == JsonQuery.EmptyOrErrorBehavior.ERROR);
        ResolvedFunction inputToJson = analysis.getJsonInputFunction(node.getJsonPathInvocation().getInputExpression());
        io.trino.sql.ir.Expression input = new io.trino.sql.ir.FunctionCall(inputToJson.toQualifiedName(), ImmutableList.of(
                translateExpression(node.getJsonPathInvocation().getInputExpression()),
                failOnError));

        // apply the input functions to the JSON path parameters having FORMAT,
        // and collect all JSON path parameters in a Row
        ParametersRow orderedParameters = getParametersRow(
                node.getJsonPathInvocation().getPathParameters(),
                node.getJsonPathInvocation().getPathParameters().stream()
                        .map(parameter -> translateExpression(parameter.getParameter()))
                        .toList(),
                resolvedFunction.getSignature().getArgumentType(2),
                failOnError);

        IrJsonPath path = new JsonPathTranslator(session, plannerContext).rewriteToIr(analysis.getJsonPathAnalysis(node), orderedParameters.getParametersOrder());
        io.trino.sql.ir.Expression pathExpression = new LiteralEncoder(plannerContext).toExpression(path, plannerContext.getTypeManager().getType(TypeId.of(JsonPath2016Type.NAME)));

        ImmutableList.Builder<io.trino.sql.ir.Expression> arguments = ImmutableList.<io.trino.sql.ir.Expression>builder()
                .add(input)
                .add(pathExpression)
                .add(orderedParameters.getParametersRow())
                .add(constant(TINYINT, (long) node.getWrapperBehavior().ordinal()))
                .add(constant(TINYINT, (long) node.getEmptyBehavior().ordinal()))
                .add(constant(TINYINT, (long) node.getErrorBehavior().ordinal()));

        io.trino.sql.ir.Expression function = new io.trino.sql.ir.FunctionCall(resolvedFunction.toQualifiedName(), arguments.build());

        // apply function to format output
        io.trino.sql.ir.GenericLiteral errorBehavior = constant(TINYINT, (long) node.getErrorBehavior().ordinal());
        io.trino.sql.ir.GenericLiteral omitQuotes = constant(BOOLEAN, node.getQuotesBehavior().orElse(KEEP) == OMIT);
        ResolvedFunction outputFunction = analysis.getJsonOutputFunction(node);
        io.trino.sql.ir.Expression result = new io.trino.sql.ir.FunctionCall(outputFunction.toQualifiedName(), ImmutableList.of(function, errorBehavior, omitQuotes));

        // cast to requested returned type
        Type returnedType = node.getReturnedType()
                .map(TypeSignatureTranslator::toTypeSignature)
                .map(plannerContext.getTypeManager()::getType)
                .orElse(VARCHAR);

        Type resultType = outputFunction.getSignature().getReturnType();
        if (!resultType.equals(returnedType)) {
            result = new io.trino.sql.ir.Cast(result, returnedType);
        }

        return result;
    }

    private io.trino.sql.ir.Expression translate(JsonObject node)
    {
        ResolvedFunction resolvedFunction = analysis.getResolvedFunction(node);
        checkArgument(resolvedFunction != null, "Function has not been analyzed: %s", node);

        io.trino.sql.ir.Expression keysRow;
        io.trino.sql.ir.Expression valuesRow;

        // prepare keys and values as rows
        if (node.getMembers().isEmpty()) {
            checkState(JSON_NO_PARAMETERS_ROW_TYPE.equals(resolvedFunction.getSignature().getArgumentType(0)));
            checkState(JSON_NO_PARAMETERS_ROW_TYPE.equals(resolvedFunction.getSignature().getArgumentType(1)));
            keysRow = new io.trino.sql.ir.Cast(new io.trino.sql.ir.NullLiteral(), JSON_NO_PARAMETERS_ROW_TYPE);
            valuesRow = new io.trino.sql.ir.Cast(new io.trino.sql.ir.NullLiteral(), JSON_NO_PARAMETERS_ROW_TYPE);
        }
        else {
            ImmutableList.Builder<io.trino.sql.ir.Expression> keys = ImmutableList.builder();
            ImmutableList.Builder<io.trino.sql.ir.Expression> values = ImmutableList.builder();
            for (JsonObjectMember member : node.getMembers()) {
                Expression value = member.getValue();

                io.trino.sql.ir.Expression rewrittenKey = translateExpression(member.getKey());
                keys.add(rewrittenKey);

                io.trino.sql.ir.Expression rewrittenValue = translateExpression(value);
                ResolvedFunction valueToJson = analysis.getJsonInputFunction(value);
                if (valueToJson != null) {
                    values.add(new io.trino.sql.ir.FunctionCall(valueToJson.toQualifiedName(), ImmutableList.of(rewrittenValue, TRUE_LITERAL)));
                }
                else {
                    values.add(rewrittenValue);
                }
            }
            keysRow = new io.trino.sql.ir.Row(keys.build());
            valuesRow = new io.trino.sql.ir.Row(values.build());
        }

        List<io.trino.sql.ir.Expression> arguments = ImmutableList.<io.trino.sql.ir.Expression>builder()
                .add(keysRow)
                .add(valuesRow)
                .add(node.isNullOnNull() ? TRUE_LITERAL : FALSE_LITERAL)
                .add(node.isUniqueKeys() ? TRUE_LITERAL : FALSE_LITERAL)
                .build();

        io.trino.sql.ir.Expression function = new io.trino.sql.ir.FunctionCall(resolvedFunction.toQualifiedName(), arguments);

        // apply function to format output
        ResolvedFunction outputFunction = analysis.getJsonOutputFunction(node);
        io.trino.sql.ir.Expression result = new io.trino.sql.ir.FunctionCall(outputFunction.toQualifiedName(), ImmutableList.of(
                function,
                constant(TINYINT, (long) ERROR.ordinal()),
                FALSE_LITERAL));

        // cast to requested returned type
        Type returnedType = node.getReturnedType()
                .map(TypeSignatureTranslator::toTypeSignature)
                .map(plannerContext.getTypeManager()::getType)
                .orElse(VARCHAR);

        Type resultType = outputFunction.getSignature().getReturnType();
        if (!resultType.equals(returnedType)) {
            result = new io.trino.sql.ir.Cast(result, returnedType);
        }

        return result;
    }

    private io.trino.sql.ir.Expression translate(JsonArray node)
    {
        ResolvedFunction resolvedFunction = analysis.getResolvedFunction(node);
        checkArgument(resolvedFunction != null, "Function has not been analyzed: %s", node);

        io.trino.sql.ir.Expression elementsRow;

        // prepare elements as row
        if (node.getElements().isEmpty()) {
            checkState(JSON_NO_PARAMETERS_ROW_TYPE.equals(resolvedFunction.getSignature().getArgumentType(0)));
            elementsRow = new io.trino.sql.ir.Cast(new io.trino.sql.ir.NullLiteral(), JSON_NO_PARAMETERS_ROW_TYPE);
        }
        else {
            ImmutableList.Builder<io.trino.sql.ir.Expression> elements = ImmutableList.builder();
            for (JsonArrayElement arrayElement : node.getElements()) {
                Expression element = arrayElement.getValue();
                io.trino.sql.ir.Expression rewrittenElement = translateExpression(element);
                ResolvedFunction elementToJson = analysis.getJsonInputFunction(element);
                if (elementToJson != null) {
                    elements.add(new io.trino.sql.ir.FunctionCall(elementToJson.toQualifiedName(), ImmutableList.of(rewrittenElement, TRUE_LITERAL)));
                }
                else {
                    elements.add(rewrittenElement);
                }
            }
            elementsRow = new io.trino.sql.ir.Row(elements.build());
        }

        List<io.trino.sql.ir.Expression> arguments = ImmutableList.<io.trino.sql.ir.Expression>builder()
                .add(elementsRow)
                .add(node.isNullOnNull() ? TRUE_LITERAL : FALSE_LITERAL)
                .build();

        io.trino.sql.ir.Expression function = new io.trino.sql.ir.FunctionCall(resolvedFunction.toQualifiedName(), arguments);

        // apply function to format output
        ResolvedFunction outputFunction = analysis.getJsonOutputFunction(node);
        io.trino.sql.ir.Expression result = new io.trino.sql.ir.FunctionCall(outputFunction.toQualifiedName(), ImmutableList.of(
                function,
                constant(TINYINT, (long) ERROR.ordinal()),
                FALSE_LITERAL));

        // cast to requested returned type
        Type returnedType = node.getReturnedType()
                .map(TypeSignatureTranslator::toTypeSignature)
                .map(plannerContext.getTypeManager()::getType)
                .orElse(VARCHAR);

        Type resultType = outputFunction.getSignature().getReturnType();
        if (!resultType.equals(returnedType)) {
            result = new io.trino.sql.ir.Cast(result, returnedType);
        }

        return result;
    }

    private Optional<SymbolReference> tryGetMapping(Expression expression)
    {
        Symbol symbol = substitutions.get(NodeRef.of(expression));
        if (symbol == null) {
            symbol = astToSymbols.get(scopeAwareKey(expression, analysis, scope));
        }

        return Optional.ofNullable(symbol)
                .map(Symbol::toSymbolReference);
    }

    private Optional<Symbol> getSymbolForColumn(Expression expression)
    {
        if (!analysis.isColumnReference(expression)) {
            // Expression can be a reference to lambda argument (or DereferenceExpression based on lambda argument reference).
            // In such case, the expression might still be resolvable with plan.getScope() but we should not resolve it.
            return Optional.empty();
        }

        ResolvedField field = analysis.getColumnReferenceFields().get(NodeRef.of(expression));

        if (scope.isLocalScope(field.getScope())) {
            return Optional.of(fieldSymbols[field.getHierarchyFieldIndex()]);
        }

        if (outerContext.isPresent()) {
            return Optional.of(Symbol.from(outerContext.get().rewrite(expression)));
        }

        return Optional.empty();
    }

    public Scope getScope()
    {
        return scope;
    }

    public ParametersRow getParametersRow(
            List<JsonPathParameter> pathParameters,
            List<io.trino.sql.ir.Expression> rewrittenPathParameters,
            Type parameterRowType,
            io.trino.sql.ir.GenericLiteral failOnError)
    {
        io.trino.sql.ir.Expression parametersRow;
        List<String> parametersOrder;
        if (!pathParameters.isEmpty()) {
            ImmutableList.Builder<io.trino.sql.ir.Expression> parameters = ImmutableList.builder();
            for (int i = 0; i < pathParameters.size(); i++) {
                ResolvedFunction parameterToJson = analysis.getJsonInputFunction(pathParameters.get(i).getParameter());
                io.trino.sql.ir.Expression rewrittenParameter = rewrittenPathParameters.get(i);
                if (parameterToJson != null) {
                    parameters.add(new io.trino.sql.ir.FunctionCall(parameterToJson.toQualifiedName(), ImmutableList.of(rewrittenParameter, failOnError)));
                }
                else {
                    parameters.add(rewrittenParameter);
                }
            }
            parametersRow = new io.trino.sql.ir.Cast(new io.trino.sql.ir.Row(parameters.build()), parameterRowType);
            parametersOrder = pathParameters.stream()
                    .map(parameter -> parameter.getName().getCanonicalValue())
                    .collect(toImmutableList());
        }
        else {
            checkState(JSON_NO_PARAMETERS_ROW_TYPE.equals(parameterRowType), "invalid type of parameters row when no parameters are passed");
            parametersRow = new io.trino.sql.ir.Cast(new io.trino.sql.ir.NullLiteral(), JSON_NO_PARAMETERS_ROW_TYPE);
            parametersOrder = ImmutableList.of();
        }

        return new ParametersRow(parametersRow, parametersOrder);
    }

    public static class ParametersRow
    {
        private final io.trino.sql.ir.Expression parametersRow;
        private final List<String> parametersOrder;

        public ParametersRow(io.trino.sql.ir.Expression parametersRow, List<String> parametersOrder)
        {
            this.parametersRow = requireNonNull(parametersRow, "parametersRow is null");
            this.parametersOrder = requireNonNull(parametersOrder, "parametersOrder is null");
        }

        public io.trino.sql.ir.Expression getParametersRow()
        {
            return parametersRow;
        }

        public List<String> getParametersOrder()
        {
            return parametersOrder;
        }
    }
}
