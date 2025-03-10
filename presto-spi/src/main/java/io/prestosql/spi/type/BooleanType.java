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
package io.prestosql.spi.type;

import io.airlift.slice.XxHash64;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.block.BlockBuilderStatus;
import io.prestosql.spi.block.ByteArrayBlockBuilder;
import io.prestosql.spi.block.PageBuilderStatus;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.function.ScalarOperator;

import static io.prestosql.spi.function.OperatorType.COMPARISON_UNORDERED_LAST;
import static io.prestosql.spi.function.OperatorType.EQUAL;
import static io.prestosql.spi.function.OperatorType.LESS_THAN;
import static io.prestosql.spi.function.OperatorType.LESS_THAN_OR_EQUAL;
import static io.prestosql.spi.function.OperatorType.XX_HASH_64;
import static io.prestosql.spi.type.TypeOperatorDeclaration.extractOperatorDeclaration;
import static io.prestosql.spi.type.TypeSignature.parseTypeSignature;
import static java.lang.invoke.MethodHandles.lookup;

public class BooleanType
        extends AbstractType
        implements FixedWidthType
{
    public static final BooleanType BOOLEAN = new BooleanType();

    private static final TypeOperatorDeclaration TYPE_OPERATOR_DECLARATION = extractOperatorDeclaration(BooleanType.class, lookup(), boolean.class);

    private static final long TRUE_XX_HASH = XxHash64.hash(1);
    private static final long FALSE_XX_HASH = XxHash64.hash(0);

    private BooleanType()
    {
        super(parseTypeSignature(StandardTypes.BOOLEAN), boolean.class);
    }

    @Override
    public int getFixedSize()
    {
        return Byte.BYTES;
    }

    @Override
    public BlockBuilder createBlockBuilder(BlockBuilderStatus blockBuilderStatus, int expectedEntries, int expectedBytesPerEntry)
    {
        int maxBlockSizeInBytes;
        if (blockBuilderStatus == null) {
            maxBlockSizeInBytes = PageBuilderStatus.DEFAULT_MAX_PAGE_SIZE_IN_BYTES;
        }
        else {
            maxBlockSizeInBytes = blockBuilderStatus.getMaxPageSizeInBytes();
        }
        return new ByteArrayBlockBuilder(
                blockBuilderStatus,
                Math.min(expectedEntries, maxBlockSizeInBytes / Byte.BYTES));
    }

    @Override
    public BlockBuilder createBlockBuilder(BlockBuilderStatus blockBuilderStatus, int expectedEntries)
    {
        return createBlockBuilder(blockBuilderStatus, expectedEntries, Byte.BYTES);
    }

    @Override
    public BlockBuilder createFixedSizeBlockBuilder(int positionCount)
    {
        return new ByteArrayBlockBuilder(null, positionCount);
    }

    @Override
    public boolean isComparable()
    {
        return true;
    }

    @Override
    public boolean isOrderable()
    {
        return true;
    }

    @Override
    public Object getObjectValue(ConnectorSession session, Block block, int position)
    {
        if (block.isNull(position)) {
            return null;
        }

        return block.getByte(position, 0) != 0;
    }

    @Override
    public boolean equalTo(Block leftBlock, int leftPosition, Block rightBlock, int rightPosition)
    {
        boolean leftValue = leftBlock.getByte(leftPosition, 0) != 0;
        boolean rightValue = rightBlock.getByte(rightPosition, 0) != 0;
        return leftValue == rightValue;
    }

    @Override
    public long hash(Block block, int position)
    {
        boolean value = block.getByte(position, 0) != 0;
        return value ? 1231 : 1237;
    }

    @Override
    public int compareTo(Block leftBlock, int leftPosition, Block rightBlock, int rightPosition)
    {
        boolean leftValue = leftBlock.getByte(leftPosition, 0) != 0;
        boolean rightValue = rightBlock.getByte(rightPosition, 0) != 0;
        return Boolean.compare(leftValue, rightValue);
    }

    @Override
    public void appendTo(Block block, int position, BlockBuilder blockBuilder)
    {
        if (block.isNull(position)) {
            blockBuilder.appendNull();
        }
        else {
            blockBuilder.writeByte(block.getByte(position, 0)).closeEntry();
        }
    }

    @Override
    public boolean getBoolean(Block block, int position)
    {
        return block.getByte(position, 0) != 0;
    }

    @Override
    public void writeBoolean(BlockBuilder blockBuilder, boolean value)
    {
        blockBuilder.writeByte(value ? 1 : 0).closeEntry();
    }

    @Override
    public boolean equals(Object other)
    {
        return other == BOOLEAN;
    }

    @Override
    public int hashCode()
    {
        return getClass().hashCode();
    }

    @ScalarOperator(EQUAL)
    private static boolean equalOperator(boolean left, boolean right)
    {
        return left == right;
    }

    @ScalarOperator(XX_HASH_64)
    private static long xxHash64Operator(boolean value)
    {
        return value ? TRUE_XX_HASH : FALSE_XX_HASH;
    }

    @ScalarOperator(COMPARISON_UNORDERED_LAST)
    private static long comparisonOperator(boolean left, boolean right)
    {
        return Boolean.compare(left, right);
    }

    @ScalarOperator(LESS_THAN)
    private static boolean lessThanOperator(boolean left, boolean right)
    {
        return !left && right;
    }

    @ScalarOperator(LESS_THAN_OR_EQUAL)
    private static boolean lessThanOrEqualOperator(boolean left, boolean right)
    {
        return !left || right;
    }

    @Override
    public TypeOperatorDeclaration getTypeOperatorDeclaration(TypeOperators typeOperators)
    {
        return TYPE_OPERATOR_DECLARATION;
    }
}
