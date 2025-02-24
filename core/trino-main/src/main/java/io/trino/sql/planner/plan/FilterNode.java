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
package io.trino.sql.planner.plan;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.errorprone.annotations.Immutable;
import io.trino.sql.ir.Expression;
import io.trino.sql.ir.NullLiteral;
import io.trino.sql.planner.Symbol;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

@Immutable
public class FilterNode
        extends PlanNode
{
    private final PlanNode source;
    private final Expression predicate;

    @JsonCreator
    public FilterNode(@JsonProperty("id") PlanNodeId id,
            @JsonProperty("source") PlanNode source,
            @JsonProperty("predicate") Expression predicate)
    {
        super(id);

        this.source = source;
        requireNonNull(predicate, "predicate is null");
        // The condition doesn't guarantee that predicate is of type boolean, but was found to be a practical way to identify
        // places where FilterNode was created without appropriate coercions.
        checkArgument(!(predicate instanceof NullLiteral), "Predicate must be an expression of boolean type: %s", predicate);
        this.predicate = predicate;
    }

    @JsonProperty("predicate")
    public Expression getPredicate()
    {
        return predicate;
    }

    @Override
    public List<Symbol> getOutputSymbols()
    {
        return source.getOutputSymbols();
    }

    @Override
    public List<PlanNode> getSources()
    {
        return ImmutableList.of(source);
    }

    @JsonProperty("source")
    public PlanNode getSource()
    {
        return source;
    }

    @Override
    public <R, C> R accept(PlanVisitor<R, C> visitor, C context)
    {
        return visitor.visitFilter(this, context);
    }

    @Override
    public PlanNode replaceChildren(List<PlanNode> newChildren)
    {
        return new FilterNode(getId(), Iterables.getOnlyElement(newChildren), predicate);
    }
}
