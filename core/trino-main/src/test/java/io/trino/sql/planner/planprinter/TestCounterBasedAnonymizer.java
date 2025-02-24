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
package io.trino.sql.planner.planprinter;

import com.google.common.collect.ImmutableList;
import io.trino.sql.ir.BinaryLiteral;
import io.trino.sql.ir.ComparisonExpression;
import io.trino.sql.ir.GenericLiteral;
import io.trino.sql.ir.IntervalLiteral;
import io.trino.sql.ir.LogicalExpression;
import io.trino.sql.ir.NullLiteral;
import io.trino.sql.ir.StringLiteral;
import io.trino.sql.ir.SymbolReference;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.TimestampWithTimeZoneType.TIMESTAMP_TZ_MILLIS;
import static io.trino.sql.ir.BooleanLiteral.TRUE_LITERAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.EQUAL;
import static io.trino.sql.ir.ComparisonExpression.Operator.GREATER_THAN;
import static io.trino.sql.ir.ComparisonExpression.Operator.LESS_THAN;
import static io.trino.sql.ir.LogicalExpression.Operator.AND;
import static org.assertj.core.api.Assertions.assertThat;

public class TestCounterBasedAnonymizer
{
    @Test
    public void testTimestampWithTimeZoneValueAnonymization()
    {
        CounterBasedAnonymizer anonymizer = new CounterBasedAnonymizer();
        assertThat(anonymizer.anonymize(TIMESTAMP_TZ_MILLIS, "2012-10-30 18:00:00.000 America/Los_Angeles"))
                .isEqualTo("timestamp_3_with_time_zone_value_1");
    }

    @Test
    public void testSymbolReferenceAnonymization()
    {
        LogicalExpression expression = new LogicalExpression(AND, ImmutableList.of(
                new ComparisonExpression(GREATER_THAN, new SymbolReference("a"), GenericLiteral.constant(INTEGER, 1L)),
                new ComparisonExpression(LESS_THAN, new SymbolReference("b"), GenericLiteral.constant(INTEGER, 2L)),
                new ComparisonExpression(EQUAL, new SymbolReference("c"), GenericLiteral.constant(INTEGER, 3L))));
        CounterBasedAnonymizer anonymizer = new CounterBasedAnonymizer();
        assertThat(anonymizer.anonymize(expression))
                .isEqualTo("((\"symbol_1\" > 'integer_literal_1') AND (\"symbol_2\" < 'integer_literal_2') AND (\"symbol_3\" = 'integer_literal_3'))");
    }

    @Test
    public void testLiteralAnonymization()
    {
        CounterBasedAnonymizer anonymizer = new CounterBasedAnonymizer();

        assertThat(anonymizer.anonymize(new BinaryLiteral(new byte[] {1, 2, 3})))
                .isEqualTo("'binary_literal_1'");

        assertThat(anonymizer.anonymize(new StringLiteral("abc")))
                .isEqualTo("'string_literal_2'");

        assertThat(anonymizer.anonymize(GenericLiteral.constant(BIGINT, 1L)))
                .isEqualTo("'bigint_literal_3'");

        assertThat(anonymizer.anonymize(TRUE_LITERAL))
                .isEqualTo("true");

        assertThat(anonymizer.anonymize(new NullLiteral()))
                .isEqualTo("null");

        assertThat(anonymizer.anonymize(new IntervalLiteral("33", IntervalLiteral.Sign.POSITIVE, IntervalLiteral.IntervalField.DAY, Optional.empty())))
                .isEqualTo("'interval_literal_4'");
    }
}
