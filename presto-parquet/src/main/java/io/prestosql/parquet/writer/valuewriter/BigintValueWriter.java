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
package io.prestosql.parquet.writer.valuewriter;

import io.prestosql.spi.block.Block;
import io.prestosql.spi.type.Type;
import org.apache.parquet.column.values.ValuesWriter;
import org.apache.parquet.schema.PrimitiveType;

import static io.prestosql.spi.type.TimestampType.TIMESTAMP_MICROS;
import static io.prestosql.spi.type.TimestampType.TIMESTAMP_MILLIS;
import static io.prestosql.spi.type.Timestamps.MICROSECONDS_PER_MILLISECOND;
import static java.util.Objects.requireNonNull;

public class BigintValueWriter
        extends PrimitiveValueWriter
{
    private final Type type;

    public BigintValueWriter(ValuesWriter valuesWriter, Type type, PrimitiveType parquetType)
    {
        super(parquetType, valuesWriter);
        this.type = requireNonNull(type, "type is null");
    }

    @Override
    public void write(Block block)
    {
        for (int i = 0; i < block.getPositionCount(); i++) {
            if (!block.isNull(i)) {
                long value = type.getLong(block, i);
                if (type.getTypeId().equals(TIMESTAMP_MILLIS.getTypeId()) || type.getTypeId().equals(TIMESTAMP_MICROS.getTypeId())) {
                    value = value * MICROSECONDS_PER_MILLISECOND;
                }
                getValueWriter().writeLong(value);
                getStatistics().updateStats(value);
            }
        }
    }
}
