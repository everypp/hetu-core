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
package io.prestosql.plugin.hive.util;

import com.google.common.collect.AbstractIterator;
import com.google.common.collect.Iterators;
import io.airlift.stats.TimeStat;
import io.prestosql.plugin.hive.DirectoryLister;
import io.prestosql.plugin.hive.HiveErrorCode;
import io.prestosql.plugin.hive.NamenodeStats;
import io.prestosql.plugin.hive.PrestoFileStatus;
import io.prestosql.plugin.hive.metastore.Table;
import io.prestosql.spi.PrestoException;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.fs.RemoteIterator;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;

import static java.util.Objects.requireNonNull;

public class HiveFileIterator
        extends AbstractIterator<PrestoFileStatus>
{
    public enum NestedDirectoryPolicy
    {
        IGNORED,
        RECURSE,
        FAIL
    }

    private final Deque<Path> paths = new ArrayDeque<>();
    private final Table table;
    private final FileSystem fileSystem;
    private final DirectoryLister directoryLister;
    private final NamenodeStats namenodeStats;
    private final NestedDirectoryPolicy nestedDirectoryPolicy;
    private final PathFilter pathFilter;

    private Iterator<PrestoFileStatus> remoteIterator = Collections.emptyIterator();

    public HiveFileIterator(
            Table table,
            Path path,
            FileSystem fileSystem,
            DirectoryLister directoryLister,
            NamenodeStats namenodeStats,
            NestedDirectoryPolicy nestedDirectoryPolicy,
            PathFilter pathFilter)
    {
        paths.addLast(requireNonNull(path, "path is null"));
        this.table = requireNonNull(table, "table is null");
        this.fileSystem = requireNonNull(fileSystem, "fileSystem is null");
        this.directoryLister = requireNonNull(directoryLister, "directoryLister is null");
        this.namenodeStats = requireNonNull(namenodeStats, "namenodeStats is null");
        this.nestedDirectoryPolicy = requireNonNull(nestedDirectoryPolicy, "nestedDirectoryPolicy is null");
        this.pathFilter = requireNonNull(pathFilter, "pathFilter is null");
    }

    @Override
    protected PrestoFileStatus computeNext()
    {
        while (true) {
            while (remoteIterator.hasNext()) {
                PrestoFileStatus status = getLocatedFileStatus(remoteIterator);

                // Ignore hidden files and directories. Hive ignores files starting with _ and . as well.
                String fileName = status.getPath().getName();
                if (fileName.startsWith("_") || fileName.startsWith(".")) {
                    continue;
                }

                if (status.isDirectory()) {
                    switch (nestedDirectoryPolicy) {
                        case IGNORED:
                            continue;
                        case RECURSE:
                            paths.add(status.getPath());
                            continue;
                        case FAIL:
                            throw new NestedDirectoryNotAllowedException();
                    }
                }

                return status;
            }

            if (paths.isEmpty()) {
                return endOfData();
            }
            remoteIterator = getLocatedFileStatusRemoteIterator(paths.removeFirst(), pathFilter);
        }
    }

    private Iterator<PrestoFileStatus> getLocatedFileStatusRemoteIterator(Path path, PathFilter pathFilter)
    {
        try (TimeStat.BlockTimer ignored = namenodeStats.getListLocatedStatus().time()) {
            return Iterators.filter(new FileStatusIterator(table, path, fileSystem, directoryLister, namenodeStats), input -> pathFilter.accept(input.getPath()));
        }
    }

    private PrestoFileStatus getLocatedFileStatus(Iterator<PrestoFileStatus> iterator)
    {
        try (TimeStat.BlockTimer ignored = namenodeStats.getRemoteIteratorNext().time()) {
            return iterator.next();
        }
    }

    private static class FileStatusIterator
            implements Iterator<PrestoFileStatus>
    {
        private final Path path;
        private final NamenodeStats namenodeStats;
        private final RemoteIterator<PrestoFileStatus> fileStatusIterator;

        private FileStatusIterator(Table table, Path path, FileSystem fileSystem, DirectoryLister directoryLister, NamenodeStats namenodeStats)
        {
            this.path = path;
            this.namenodeStats = namenodeStats;
            try {
                this.fileStatusIterator = directoryLister.list(fileSystem, table, path);
            }
            catch (IOException e) {
                throw processException(e);
            }
        }

        @Override
        public boolean hasNext()
        {
            try {
                return fileStatusIterator.hasNext();
            }
            catch (IOException e) {
                throw processException(e);
            }
        }

        @Override
        public PrestoFileStatus next()
        {
            try {
                return fileStatusIterator.next();
            }
            catch (IOException e) {
                throw processException(e);
            }
        }

        private PrestoException processException(IOException exception)
        {
            namenodeStats.getRemoteIteratorNext().recordException(exception);
            if (exception instanceof FileNotFoundException) {
                return new PrestoException(HiveErrorCode.HIVE_FILE_NOT_FOUND, "Partition location does not exist: " + path);
            }
            return new PrestoException(HiveErrorCode.HIVE_FILESYSTEM_ERROR, "Failed to list directory: " + path, exception);
        }
    }

    public static class NestedDirectoryNotAllowedException
            extends RuntimeException
    {
        public NestedDirectoryNotAllowedException()
        {
            super("Nested sub-directories are not allowed");
        }
    }
}
