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
package io.prestosql.plugin.hive.orc;

import io.airlift.slice.Slice;
import io.airlift.units.DataSize;
import io.prestosql.orc.AbstractOrcDataSource;
import io.prestosql.orc.OrcDataSourceId;
import io.prestosql.orc.OrcReaderOptions;
import io.prestosql.plugin.hive.FileFormatDataSourceStats;
import io.prestosql.plugin.hive.HiveErrorCode;
import io.prestosql.plugin.hive.util.FSDataInputStreamTail;
import io.prestosql.spi.PrestoException;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.hdfs.BlockMissingException;

import java.io.IOException;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class HdfsOrcDataSource
        extends AbstractOrcDataSource
{
    private final FSDataInputStream inputStream;
    private final FileFormatDataSourceStats stats;

    public HdfsOrcDataSource(
            OrcDataSourceId id,
            long size,
            DataSize maxMergeDistance,
            DataSize maxReadSize,
            DataSize streamBufferSize,
            boolean lazyReadSmallRanges,
            FSDataInputStream inputStream,
            FileFormatDataSourceStats stats,
            long lastModifiedTime)
    {
        super(id, size, maxMergeDistance, maxReadSize, streamBufferSize, lazyReadSmallRanges, lastModifiedTime);
        this.inputStream = requireNonNull(inputStream, "inputStream is null");
        this.stats = requireNonNull(stats, "stats is null");
    }

    public HdfsOrcDataSource(
            OrcDataSourceId id,
            long size,
            OrcReaderOptions options,
            FSDataInputStream inputStream,
            FileFormatDataSourceStats stats)
    {
        super(id, size, options);
        this.inputStream = requireNonNull(inputStream, "inputStream is null");
        this.stats = requireNonNull(stats, "stats is null");
    }

    @Override
    public void close()
            throws IOException
    {
        inputStream.close();
    }

    @Override
    public Slice readTail(int length)
            throws IOException
    {
        //  Handle potentially imprecise file lengths by reading the footer
        long readStart = System.nanoTime();
        FSDataInputStreamTail fileTail = FSDataInputStreamTail.readTail(getId().toString(), getEstimatedSize(), inputStream, length);
        Slice tailSlice = fileTail.getTailSlice();
        stats.readDataBytesPerSecond(tailSlice.length(), System.nanoTime() - readStart);
        return tailSlice;
    }

    @Override
    protected void readInternal(long position, byte[] buffer, int bufferOffset, int bufferLength)
    {
        try {
            long readStart = System.nanoTime();
            inputStream.readFully(position, buffer, bufferOffset, bufferLength);
            stats.readDataBytesPerSecond(bufferLength, System.nanoTime() - readStart);
        }
        catch (PrestoException e) {
            // just in case there is a Presto wrapper or hook
            throw e;
        }
        catch (Exception e) {
            String message = format("Error reading from %s at position %s", this, position);
            if (e instanceof BlockMissingException) {
                throw new PrestoException(HiveErrorCode.HIVE_MISSING_DATA, message, e);
            }
            if (e instanceof IOException) {
                throw new PrestoException(HiveErrorCode.HIVE_FILESYSTEM_ERROR, message, e);
            }
            throw new PrestoException(HiveErrorCode.HIVE_UNKNOWN_ERROR, message, e);
        }
    }
}
