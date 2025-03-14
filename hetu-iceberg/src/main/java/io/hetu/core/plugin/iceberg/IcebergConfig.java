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
package io.hetu.core.plugin.iceberg;

import io.airlift.configuration.Config;
import io.airlift.configuration.ConfigDescription;
import io.airlift.units.Duration;
import io.prestosql.plugin.hive.HiveCompressionCodec;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

import java.util.Optional;

import static io.hetu.core.plugin.iceberg.CatalogType.HIVE_METASTORE;
import static io.hetu.core.plugin.iceberg.IcebergFileFormat.ORC;
import static io.prestosql.plugin.hive.HiveCompressionCodec.ZSTD;
import static java.util.concurrent.TimeUnit.SECONDS;

public class IcebergConfig
{
    public static final int FORMAT_VERSION_SUPPORT_MIN = 1;
    public static final int FORMAT_VERSION_SUPPORT_MAX = 2;
    private io.hetu.core.plugin.iceberg.IcebergFileFormat fileFormat = ORC;
    private HiveCompressionCodec compressionCodec = ZSTD;
    private boolean useFileSizeFromMetadata = true;
    private int maxPartitionsPerWriter = 100;
    private boolean uniqueTableLocation;
    private io.hetu.core.plugin.iceberg.CatalogType catalogType = HIVE_METASTORE;
    private Duration dynamicFilteringWaitTimeout = new Duration(0, SECONDS);
    private boolean tableStatisticsEnabled = true;
    private boolean projectionPushdownEnabled = true;
    private Optional<String> hiveCatalogName = Optional.empty();
    private double minimumAssignedSplitWeight = 0.05;
    private int formatVersion = FORMAT_VERSION_SUPPORT_MAX;

    public io.hetu.core.plugin.iceberg.CatalogType getCatalogType()
    {
        return catalogType;
    }

    @Min(FORMAT_VERSION_SUPPORT_MIN)
    @Max(FORMAT_VERSION_SUPPORT_MAX)
    public int getFormatVersion()
    {
        return formatVersion;
    }

    @Config("iceberg.catalog.type")
    public IcebergConfig setCatalogType(io.hetu.core.plugin.iceberg.CatalogType catalogType)
    {
        this.catalogType = catalogType;
        return this;
    }

    @NotNull
    public io.hetu.core.plugin.iceberg.IcebergFileFormat getFileFormat()
    {
        return fileFormat;
    }

    @Config("iceberg.file-format")
    public IcebergConfig setFileFormat(io.hetu.core.plugin.iceberg.IcebergFileFormat fileFormat)
    {
        this.fileFormat = fileFormat;
        return this;
    }

    @NotNull
    public HiveCompressionCodec getCompressionCodec()
    {
        return compressionCodec;
    }

    @Config("iceberg.compression-codec")
    public IcebergConfig setCompressionCodec(HiveCompressionCodec compressionCodec)
    {
        this.compressionCodec = compressionCodec;
        return this;
    }

    @Deprecated
    public boolean isUseFileSizeFromMetadata()
    {
        return useFileSizeFromMetadata;
    }

    /**
     * Some Iceberg writers populate incorrect file sizes in the metadata. When
     * this property is set to false, Trino ignores the stored values and fetches
     * them with a getFileStatus call. This means an additional call per split,
     * so it is recommended for a Trino admin to fix the metadata, rather than
     * relying on this property for too long.
     */
    @Deprecated
    @Config("iceberg.use-file-size-from-metadata")
    public IcebergConfig setUseFileSizeFromMetadata(boolean useFileSizeFromMetadata)
    {
        this.useFileSizeFromMetadata = useFileSizeFromMetadata;
        return this;
    }

    @Min(1)
    public int getMaxPartitionsPerWriter()
    {
        return maxPartitionsPerWriter;
    }

    @Config("iceberg.max-partitions-per-writer")
    @ConfigDescription("Maximum number of partitions per writer")
    public IcebergConfig setMaxPartitionsPerWriter(int maxPartitionsPerWriter)
    {
        this.maxPartitionsPerWriter = maxPartitionsPerWriter;
        return this;
    }

    public boolean isUniqueTableLocation()
    {
        return uniqueTableLocation;
    }

    @Config("iceberg.unique-table-location")
    @ConfigDescription("Use randomized, unique table locations")
    public IcebergConfig setUniqueTableLocation(boolean uniqueTableLocation)
    {
        this.uniqueTableLocation = uniqueTableLocation;
        return this;
    }

    @NotNull
    public Duration getDynamicFilteringWaitTimeout()
    {
        return dynamicFilteringWaitTimeout;
    }

    @Config("iceberg.dynamic-filtering.wait-timeout")
    @ConfigDescription("Duration to wait for completion of dynamic filters during split generation")
    public IcebergConfig setDynamicFilteringWaitTimeout(Duration dynamicFilteringWaitTimeout)
    {
        this.dynamicFilteringWaitTimeout = dynamicFilteringWaitTimeout;
        return this;
    }

    // In case of some queries / tables, retrieving table statistics from Iceberg
    // can take 20+ seconds. This config allows the user / operator the option
    // to opt out of retrieving table statistics in those cases to speed up query planning.
    @Config("iceberg.table-statistics-enabled")
    @ConfigDescription("Enable use of table statistics")
    public IcebergConfig setTableStatisticsEnabled(boolean tableStatisticsEnabled)
    {
        this.tableStatisticsEnabled = tableStatisticsEnabled;
        return this;
    }

    public boolean isTableStatisticsEnabled()
    {
        return tableStatisticsEnabled;
    }

    public boolean isProjectionPushdownEnabled()
    {
        return projectionPushdownEnabled;
    }

    @Config("iceberg.projection-pushdown-enabled")
    @ConfigDescription("Read only required fields from a struct")
    public IcebergConfig setProjectionPushdownEnabled(boolean projectionPushdownEnabled)
    {
        this.projectionPushdownEnabled = projectionPushdownEnabled;
        return this;
    }

    public Optional<String> getHiveCatalogName()
    {
        return hiveCatalogName;
    }

    @Config("iceberg.hive-catalog-name")
    @ConfigDescription("Catalog to redirect to when a Hive table is referenced")
    public IcebergConfig setHiveCatalogName(String hiveCatalogName)
    {
        this.hiveCatalogName = Optional.ofNullable(hiveCatalogName);
        return this;
    }

    @Config("iceberg.minimum-assigned-split-weight")
    @ConfigDescription("Minimum weight that a split can be assigned")
    public IcebergConfig setMinimumAssignedSplitWeight(double minimumAssignedSplitWeight)
    {
        this.minimumAssignedSplitWeight = minimumAssignedSplitWeight;
        return this;
    }

    @DecimalMax("1")
    @DecimalMin(value = "0", inclusive = false)
    public double getMinimumAssignedSplitWeight()
    {
        return minimumAssignedSplitWeight;
    }
}
