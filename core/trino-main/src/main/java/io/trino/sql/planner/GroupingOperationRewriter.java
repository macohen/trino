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

import io.trino.spi.type.BigintType;
import io.trino.spi.type.IntegerType;
import io.trino.spi.type.Type;
import io.trino.sql.analyzer.FieldId;
import io.trino.sql.analyzer.RelationId;
import io.trino.sql.analyzer.ResolvedField;
import io.trino.sql.ir.ArithmeticBinaryExpression;
import io.trino.sql.ir.Array;
import io.trino.sql.ir.Expression;
import io.trino.sql.ir.GenericLiteral;
import io.trino.sql.ir.SubscriptExpression;
import io.trino.sql.tree.GroupingOperation;
import io.trino.sql.tree.NodeRef;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.sql.ir.ArithmeticBinaryExpression.Operator.ADD;
import static java.util.Objects.requireNonNull;

public final class GroupingOperationRewriter
{
    private GroupingOperationRewriter() {}

    public static Expression rewriteGroupingOperation(GroupingOperation expression, Type type, List<Set<Integer>> groupingSets, Map<NodeRef<io.trino.sql.tree.Expression>, ResolvedField> columnReferenceFields, Optional<Symbol> groupIdSymbol)
    {
        requireNonNull(groupIdSymbol, "groupIdSymbol is null");

        // No GroupIdNode and a GROUPING() operation imply a single grouping, which
        // means that any columns specified as arguments to GROUPING() will be included
        // in the group and none of them will be aggregated over. Hence, re-write the
        // GroupingOperation to a constant literal of 0.
        // See SQL:2011:4.16.2 and SQL:2011:6.9.10.
        if (groupingSets.size() == 1) {
            return switch (type) {
                case BigintType unused -> GenericLiteral.constant(BIGINT, 0L);
                case IntegerType unused -> GenericLiteral.constant(INTEGER, 0L);
                default -> throw new IllegalArgumentException("Unexpected type for GROUPING operation: " + type);
            };
        }
        checkState(groupIdSymbol.isPresent(), "groupId symbol is missing");

        RelationId relationId = columnReferenceFields.get(NodeRef.of(expression.getGroupingColumns().get(0))).getFieldId().getRelationId();

        List<Integer> columns = expression.getGroupingColumns().stream()
                .map(NodeRef::of)
                .peek(groupingColumn -> checkState(columnReferenceFields.containsKey(groupingColumn), "the grouping column is not in the columnReferencesField map"))
                .map(columnReferenceFields::get)
                .map(ResolvedField::getFieldId)
                .map(fieldId -> translateFieldToInteger(fieldId, relationId))
                .collect(toImmutableList());

        List<Expression> groupingResults = groupingSets.stream()
                .map(groupingSet -> calculateGrouping(groupingSet, columns))
                .map(value -> switch (type) {
                    case BigintType unused -> GenericLiteral.constant(BIGINT, value);
                    case IntegerType unused -> GenericLiteral.constant(INTEGER, value);
                    default -> throw new IllegalArgumentException("Unexpected type for GROUPING operation: " + type);
                })
                .collect(toImmutableList());

        // It is necessary to add a 1 to the groupId because the underlying array is indexed starting at 1
        return new SubscriptExpression(
                new Array(groupingResults),
                new ArithmeticBinaryExpression(ADD, groupIdSymbol.get().toSymbolReference(), GenericLiteral.constant(BIGINT, 1L)));
    }

    private static int translateFieldToInteger(FieldId fieldId, RelationId requiredOriginRelationId)
    {
        // TODO: this section should be rewritten when support is added for GROUP BY columns to reference an outer scope
        checkState(fieldId.getRelationId().equals(requiredOriginRelationId), "grouping arguments must all come from the same relation");
        return fieldId.getFieldIndex();
    }

    /**
     * The grouping function is used in conjunction with GROUPING SETS, ROLLUP and CUBE to
     * indicate which columns are present in that grouping.
     *
     * <p>The grouping function must be invoked with arguments that exactly match the columns
     * referenced in the corresponding GROUPING SET, ROLLUP or CUBE clause at the associated
     * query level. Those column arguments are not evaluated and instead the function is
     * re-written with the arguments below.
     *
     * <p>To compute the resulting bit set for a particular row, bits are assigned to the
     * argument columns with the rightmost column being the most significant bit. For a
     * given grouping, a bit is set to 0 if the corresponding column is included in the
     * grouping and 1 otherwise. For an example, see the SQL documentation for the
     * function.
     *
     * @param columns The column arguments with which the function was invoked
     * converted to ordinals with respect to the base table column ordering.
     * @param groupingSet A collection containing the ordinals of the
     * columns present in the grouping.
     * @return A bit set converted to decimal indicating which columns are present in
     * the grouping. If a column is NOT present in the grouping its corresponding
     * bit is set to 1 and to 0 if the column is present in the grouping.
     */
    static long calculateGrouping(Set<Integer> groupingSet, List<Integer> columns)
    {
        long grouping = (1L << columns.size()) - 1;

        for (int index = 0; index < columns.size(); index++) {
            int column = columns.get(index);

            if (groupingSet.contains(column)) {
                // Leftmost argument to grouping() (i.e. when index = 0) corresponds to
                // the most significant bit in the result. That is why we shift 1L starting
                // from the columns.size() - 1 bit index.
                grouping = grouping & ~(1L << (columns.size() - 1 - index));
            }
        }

        return grouping;
    }
}
