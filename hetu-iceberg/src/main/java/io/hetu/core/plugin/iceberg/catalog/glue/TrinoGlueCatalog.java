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
package io.hetu.core.plugin.iceberg.catalog.glue;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.glue.AWSGlueAsync;
import com.amazonaws.services.glue.model.AlreadyExistsException;
import com.amazonaws.services.glue.model.CreateDatabaseRequest;
import com.amazonaws.services.glue.model.CreateTableRequest;
import com.amazonaws.services.glue.model.Database;
import com.amazonaws.services.glue.model.DatabaseInput;
import com.amazonaws.services.glue.model.DeleteDatabaseRequest;
import com.amazonaws.services.glue.model.DeleteTableRequest;
import com.amazonaws.services.glue.model.EntityNotFoundException;
import com.amazonaws.services.glue.model.GetDatabaseRequest;
import com.amazonaws.services.glue.model.GetDatabasesRequest;
import com.amazonaws.services.glue.model.GetDatabasesResult;
import com.amazonaws.services.glue.model.GetTableRequest;
import com.amazonaws.services.glue.model.GetTablesRequest;
import com.amazonaws.services.glue.model.GetTablesResult;
import com.amazonaws.services.glue.model.TableInput;
import com.amazonaws.services.glue.model.UpdateTableRequest;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.hetu.core.plugin.iceberg.catalog.AbstractTrinoCatalog;
import io.hetu.core.plugin.iceberg.catalog.IcebergTableOperationsProvider;
import io.prestosql.plugin.hive.HdfsEnvironment;
import io.prestosql.plugin.hive.ViewAlreadyExistsException;
import io.prestosql.plugin.hive.metastore.glue.GlueMetastoreStats;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.connector.CatalogSchemaTableName;
import io.prestosql.spi.connector.ConnectorMaterializedViewDefinition;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.ConnectorViewDefinition;
import io.prestosql.spi.connector.SchemaAlreadyExistsException;
import io.prestosql.spi.connector.SchemaNotFoundException;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.connector.TableNotFoundException;
import io.prestosql.spi.connector.ViewNotFoundException;
import io.prestosql.spi.security.PrestoPrincipal;
import io.prestosql.spi.security.PrincipalType;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.apache.hadoop.fs.Path;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.Transaction;
import org.apache.orc.impl.BufferChunk;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.hetu.core.plugin.iceberg.IcebergErrorCode.ICEBERG_CATALOG_ERROR;
import static io.hetu.core.plugin.iceberg.IcebergSchemaProperties.LOCATION_PROPERTY;
import static io.hetu.core.plugin.iceberg.IcebergSessionProperties.getHiveCatalogName;
import static io.hetu.core.plugin.iceberg.IcebergUtil.getIcebergTableWithMetadata;
import static io.hetu.core.plugin.iceberg.IcebergUtil.quotedTableName;
import static io.hetu.core.plugin.iceberg.IcebergUtil.validateTableCanBeDropped;
import static io.hetu.core.plugin.iceberg.catalog.glue.GlueIcebergUtil.getTableInput;
import static io.hetu.core.plugin.iceberg.catalog.glue.GlueIcebergUtil.getViewTableInput;
import static io.prestosql.plugin.hive.HiveErrorCode.HIVE_DATABASE_LOCATION_ERROR;
import static io.prestosql.plugin.hive.HiveErrorCode.HIVE_METASTORE_ERROR;
import static io.prestosql.plugin.hive.HiveUtil.isHiveSystemSchema;
import static io.prestosql.plugin.hive.ViewReaderUtil.encodeViewData;
import static io.prestosql.plugin.hive.ViewReaderUtil.isPrestoView;
import static io.prestosql.plugin.hive.metastore.glue.AwsSdkUtil.getPaginatedResults;
import static io.prestosql.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.prestosql.spi.connector.SchemaTableName.schemaTableName;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.apache.hadoop.hive.metastore.TableType.VIRTUAL_VIEW;
import static org.apache.iceberg.BaseMetastoreTableOperations.ICEBERG_TABLE_TYPE_VALUE;
import static org.apache.iceberg.BaseMetastoreTableOperations.TABLE_TYPE_PROP;
import static org.apache.iceberg.CatalogUtil.dropTableData;

public class TrinoGlueCatalog
        extends AbstractTrinoCatalog
{
    private final HdfsEnvironment hdfsEnvironment;
    private final Optional<String> defaultSchemaLocation;
    private final AWSGlueAsync glueClient;
    private final GlueMetastoreStats stats;
    private static final io.prestosql.hive.$internal.org.slf4j.Logger LOG =
            io.prestosql.hive.$internal.org.slf4j.LoggerFactory.getLogger(BufferChunk.class);

    private final Map<SchemaTableName, TableMetadata> tableMetadataCache = new ConcurrentHashMap<>();

    public TrinoGlueCatalog(
            HdfsEnvironment hdfsEnvironment,
            IcebergTableOperationsProvider tableOperationsProvider,
            String trinoVersion,
            AWSGlueAsync glueClient,
            GlueMetastoreStats stats,
            Optional<String> defaultSchemaLocation,
            boolean useUniqueTableLocation)
    {
        super(tableOperationsProvider, trinoVersion, useUniqueTableLocation);
        this.hdfsEnvironment = requireNonNull(hdfsEnvironment, "hdfsEnvironment is null");
        this.glueClient = requireNonNull(glueClient, "glueClient is null");
        this.stats = requireNonNull(stats, "stats is null");
        this.defaultSchemaLocation = requireNonNull(defaultSchemaLocation, "defaultSchemaLocation is null");
    }

    @Override
    public List<String> listNamespaces(ConnectorSession session)
    {
        try {
            return getPaginatedResults(
                    glueClient::getDatabases,
                    new GetDatabasesRequest(),
                    GetDatabasesRequest::setNextToken,
                    GetDatabasesResult::getNextToken,
                    stats.getGetDatabases())
                    .map(GetDatabasesResult::getDatabaseList)
                    .flatMap(List::stream)
                    .map(com.amazonaws.services.glue.model.Database::getName)
                    .collect(toImmutableList());
        }
        catch (AmazonServiceException e) {
            throw new PrestoException(ICEBERG_CATALOG_ERROR, e);
        }
    }

    @Override
    public void dropNamespace(ConnectorSession session, String namespace)
    {
        try {
            stats.getDeleteDatabase().call(() ->
                    glueClient.deleteDatabase(new DeleteDatabaseRequest().withName(namespace)));
        }
        catch (EntityNotFoundException e) {
            throw new SchemaNotFoundException(namespace);
        }
        catch (AmazonServiceException e) {
            throw new PrestoException(ICEBERG_CATALOG_ERROR, e);
        }
    }

    @Override
    public Map<String, Object> loadNamespaceMetadata(ConnectorSession session, String namespace)
    {
        try {
            GetDatabaseRequest getDatabaseRequest = new GetDatabaseRequest().withName(namespace);
            Database database = stats.getGetDatabase().call(() ->
                    glueClient.getDatabase(getDatabaseRequest).getDatabase());
            ImmutableMap.Builder<String, Object> metadata = ImmutableMap.builder();
            if (database.getLocationUri() != null) {
                metadata.put(LOCATION_PROPERTY, database.getLocationUri());
            }
            if (database.getParameters() != null) {
                metadata.putAll(database.getParameters());
            }
            return metadata.build();
        }
        catch (EntityNotFoundException e) {
            throw new SchemaNotFoundException(namespace);
        }
        catch (AmazonServiceException e) {
            throw new PrestoException(ICEBERG_CATALOG_ERROR, e);
        }
    }

    @Override
    public Optional<PrestoPrincipal> getNamespacePrincipal(ConnectorSession session, String namespace)
    {
        return Optional.empty();
    }

    @Override
    public void createNamespace(ConnectorSession session, String namespace, Map<String, Object> properties, PrestoPrincipal owner)
    {
        checkArgument(owner.getType() == PrincipalType.USER, "Owner type must be USER");
        checkArgument(owner.getName().equals(session.getUser()), "Explicit schema owner is not supported");

        try {
            stats.getCreateDatabase().call(() ->
                    glueClient.createDatabase(new CreateDatabaseRequest()
                            .withDatabaseInput(createDatabaseInput(namespace, properties))));
        }
        catch (AlreadyExistsException e) {
            throw new SchemaAlreadyExistsException(namespace);
        }
        catch (AmazonServiceException e) {
            throw new PrestoException(ICEBERG_CATALOG_ERROR, e);
        }
    }

    private DatabaseInput createDatabaseInput(String namespace, Map<String, Object> properties)
    {
        DatabaseInput databaseInput = new DatabaseInput().withName(namespace);
        Object location = properties.get(LOCATION_PROPERTY);
        if (location != null) {
            databaseInput.setLocationUri((String) location);
        }

        return databaseInput;
    }

    @Override
    public void setNamespacePrincipal(ConnectorSession session, String namespace, PrestoPrincipal principal)
    {
        throw new PrestoException(NOT_SUPPORTED, "setNamespacePrincipal is not supported for Iceberg Glue catalogs");
    }

    @Override
    public void renameNamespace(ConnectorSession session, String source, String target)
    {
        throw new PrestoException(NOT_SUPPORTED, "renameNamespace is not supported for Iceberg Glue catalogs");
    }

    @Override
    public List<SchemaTableName> listTables(ConnectorSession session, Optional<String> namespace)
    {
        ImmutableList.Builder<SchemaTableName> tables = ImmutableList.builder();
        try {
            List<String> namespaces = namespace.map(ImmutableList::of).orElseGet(() -> (ImmutableList<String>) listNamespaces(session));
            for (String glueNamespace : namespaces) {
                try {
                    // Add all tables from a namespace together, in case it is removed while fetching paginated results
                    tables.addAll(
                            getPaginatedResults(
                                    glueClient::getTables,
                                    new GetTablesRequest().withDatabaseName(glueNamespace),
                                    GetTablesRequest::setNextToken,
                                    GetTablesResult::getNextToken,
                                    stats.getGetTables())
                                    .map(GetTablesResult::getTableList)
                                    .flatMap(List::stream)
                                    .map(table -> new SchemaTableName(glueNamespace, table.getName()))
                                    .collect(toImmutableList()));
                }
                catch (EntityNotFoundException e) {
                    LOG.error("Entity Not Found Exception");
                    // Namespace may have been deleted
                }
            }
        }
        catch (AmazonServiceException e) {
            throw new PrestoException(ICEBERG_CATALOG_ERROR, e);
        }
        return tables.build();
    }

    @Override
    public Table loadTable(ConnectorSession session, SchemaTableName table)
    {
        TableMetadata metadata = tableMetadataCache.computeIfAbsent(
                table,
                ignore -> {
                    TableOperations operations = tableOperationsProvider.createTableOperations(
                            this,
                            session,
                            table.getSchemaName(),
                            table.getTableName(),
                            Optional.empty(),
                            Optional.empty());
                    return new BaseTable(operations, quotedTableName(table)).operations().current();
                });

        return getIcebergTableWithMetadata(
                this,
                tableOperationsProvider,
                session,
                table,
                metadata);
    }

    @Override
    public void dropTable(ConnectorSession session, SchemaTableName schemaTableName)
    {
        BaseTable table = (BaseTable) loadTable(session, schemaTableName);
        validateTableCanBeDropped(table);
        try {
            deleteTable(schemaTableName.getSchemaName(), schemaTableName.getTableName());
        }
        catch (AmazonServiceException e) {
            throw new PrestoException(HIVE_METASTORE_ERROR, e);
        }
        dropTableData(table.io(), table.operations().current());
        deleteTableDirectory(session, schemaTableName, hdfsEnvironment, new Path(table.location()));
    }

    @Override
    public Transaction newCreateTableTransaction(
            ConnectorSession session,
            SchemaTableName schemaTableName,
            Schema schema,
            PartitionSpec partitionSpec,
            String location,
            Map<String, String> properties)
    {
        return newCreateTableTransaction(
                session,
                schemaTableName,
                schema,
                partitionSpec,
                location,
                properties,
                Optional.of(session.getUser()));
    }

    @Override
    public void renameTable(ConnectorSession session, SchemaTableName from, SchemaTableName to)
    {
        boolean newTableCreated = false;
        try {
            Optional<com.amazonaws.services.glue.model.Table> table = getTable(from);
            if (!table.isPresent()) {
                throw new TableNotFoundException(from);
            }
            TableInput tableInput = getTableInput(to.getTableName(), Optional.ofNullable(table.get().getOwner()), table.get().getParameters());
            CreateTableRequest createTableRequest = new CreateTableRequest()
                    .withDatabaseName(to.getSchemaName())
                    .withTableInput(tableInput);
            stats.getCreateTable().call(() -> glueClient.createTable(createTableRequest));
            newTableCreated = true;
            deleteTable(from.getSchemaName(), from.getTableName());
        }
        catch (RuntimeException e) {
            if (newTableCreated) {
                try {
                    deleteTable(to.getSchemaName(), to.getTableName());
                }
                catch (RuntimeException cleanupException) {
                    if (!cleanupException.equals(e)) {
                        e.addSuppressed(cleanupException);
                    }
                }
            }
            throw e;
        }
    }

    private Optional<com.amazonaws.services.glue.model.Table> getTable(SchemaTableName schemaTableName)
    {
        try {
            return Optional.of(
                    stats.getGetTable().call(() ->
                            glueClient.getTable(new GetTableRequest()
                                    .withDatabaseName(schemaTableName.getSchemaName())
                                    .withName(schemaTableName.getTableName()))
                                    .getTable()));
        }
        catch (EntityNotFoundException e) {
            return Optional.empty();
        }
    }

    private void deleteTable(String schema, String table)
    {
        stats.getDeleteTable().call(() ->
                glueClient.deleteTable(new DeleteTableRequest()
                        .withDatabaseName(schema)
                        .withName(table)));
    }

    @Override
    public String defaultTableLocation(ConnectorSession session, SchemaTableName schemaTableName)
    {
        GetDatabaseRequest getDatabaseRequest = new GetDatabaseRequest()
                .withName(schemaTableName.getSchemaName());
        String databaseLocation = stats.getGetDatabase().call(() ->
                glueClient.getDatabase(getDatabaseRequest)
                        .getDatabase()
                        .getLocationUri());

        String tableName = createNewTableName(schemaTableName.getTableName());

        Path location;
        if (databaseLocation == null) {
            if (!defaultSchemaLocation.isPresent()) {
                throw new PrestoException(
                        HIVE_DATABASE_LOCATION_ERROR,
                        format("Schema '%s' location cannot be determined. " +
                                        "Either set the 'location' property when creating the schema, or set the 'hive.metastore.glue.default-warehouse-dir' " +
                                        "catalog property.",
                                schemaTableName.getSchemaName()));
            }
            String schemaDirectoryName = schemaTableName.getSchemaName() + ".db";
            location = new Path(new Path(defaultSchemaLocation.get(), schemaDirectoryName), tableName);
        }
        else {
            location = new Path(databaseLocation, tableName);
        }

        return location.toString();
    }

    @Override
    public void setTablePrincipal(ConnectorSession session, SchemaTableName schemaTableName, PrestoPrincipal principal)
    {
        throw new PrestoException(NOT_SUPPORTED, "setTablePrincipal is not supported for Iceberg Glue catalogs");
    }

    @Override
    public void createView(ConnectorSession session, SchemaTableName schemaViewName, ConnectorViewDefinition definition, boolean replace)
    {
        // If a view is created between listing the existing view and calling createTable, retry
        TableInput viewTableInput = getViewTableInput(schemaViewName.getTableName(), encodeViewData(definition), session.getUser(), createViewProperties(session));
        Failsafe.with(new RetryPolicy<>()
                .withMaxRetries(3)
                .withDelay(Duration.ofMillis(100))
                .abortIf(throwable -> !replace || throwable instanceof ViewAlreadyExistsException))
                .run(() -> doCreateView(schemaViewName, viewTableInput, replace));
    }

    private void doCreateView(SchemaTableName schemaViewName, TableInput viewTableInput, boolean replace)
    {
        Optional<com.amazonaws.services.glue.model.Table> existing = getTable(schemaViewName);
        if (existing.isPresent()) {
            if (!replace || !isPrestoView(existing.get().getParameters())) {
                // TODO: ViewAlreadyExists is misleading if the name is used by a table https://github.com/trinodb/trino/issues/10037
                throw new ViewAlreadyExistsException(schemaViewName);
            }

            stats.getUpdateTable().call(() ->
                    glueClient.updateTable(new UpdateTableRequest()
                            .withDatabaseName(schemaViewName.getSchemaName())
                            .withTableInput(viewTableInput)));
            return;
        }

        try {
            stats.getCreateTable().call(() ->
                    glueClient.createTable(new CreateTableRequest()
                            .withDatabaseName(schemaViewName.getSchemaName())
                            .withTableInput(viewTableInput)));
        }
        catch (AlreadyExistsException e) {
            throw new ViewAlreadyExistsException(schemaViewName);
        }
    }

    @Override
    public void renameView(ConnectorSession session, SchemaTableName source, SchemaTableName target)
    {
        boolean newTableCreated = false;
        try {
            Optional<com.amazonaws.services.glue.model.Table> existingView = getTable(source);
            if (!existingView.isPresent()) {
                throw new TableNotFoundException(source);
            }

            TableInput viewTableInput = getViewTableInput(
                    target.getTableName(),
                    existingView.get().getViewOriginalText(),
                    existingView.get().getOwner(),
                    createViewProperties(session));
            CreateTableRequest createTableRequest = new CreateTableRequest()
                    .withDatabaseName(target.getSchemaName())
                    .withTableInput(viewTableInput);
            stats.getCreateTable().call(() -> glueClient.createTable(createTableRequest));
            newTableCreated = true;
            deleteTable(source.getSchemaName(), source.getTableName());
        }
        catch (Exception e) {
            if (newTableCreated) {
                try {
                    deleteTable(target.getSchemaName(), target.getTableName());
                }
                catch (Exception cleanupException) {
                    if (!cleanupException.equals(e)) {
                        e.addSuppressed(cleanupException);
                    }
                }
            }
            throw e;
        }
    }

    @Override
    public void setViewPrincipal(ConnectorSession session, SchemaTableName schemaViewName, PrestoPrincipal principal)
    {
        throw new PrestoException(NOT_SUPPORTED, "setViewPrincipal is not supported for Iceberg Glue catalogs");
    }

    @Override
    public void dropView(ConnectorSession session, SchemaTableName schemaViewName)
    {
        if (!getView(session, schemaViewName).isPresent()) {
            throw new ViewNotFoundException(schemaViewName);
        }

        try {
            deleteTable(schemaViewName.getSchemaName(), schemaViewName.getTableName());
        }
        catch (AmazonServiceException e) {
            throw new PrestoException(HIVE_METASTORE_ERROR, e);
        }
    }

    @Override
    public List<SchemaTableName> listViews(ConnectorSession session, Optional<String> namespace)
    {
        try {
            List<String> namespaces = namespace.map(ImmutableList::of).orElseGet(() -> (ImmutableList<String>) listNamespaces(session));
            return namespaces.stream()
                    .flatMap(glueNamespace -> {
                        try {
                            return getPaginatedResults(
                                    glueClient::getTables,
                                    new GetTablesRequest().withDatabaseName(glueNamespace),
                                    GetTablesRequest::setNextToken,
                                    GetTablesResult::getNextToken,
                                    stats.getGetTables())
                                    .map(GetTablesResult::getTableList)
                                    .flatMap(List::stream)
                                    .filter(table -> isPrestoView(table.getParameters()))
                                    .map(table -> new SchemaTableName(glueNamespace, table.getName()));
                        }
                        catch (EntityNotFoundException e) {
                            // Namespace may have been deleted
                            return Stream.empty();
                        }
                    })
                    .collect(toImmutableList());
        }
        catch (AmazonServiceException e) {
            throw new PrestoException(ICEBERG_CATALOG_ERROR, e);
        }
    }

    @Override
    public Optional<ConnectorViewDefinition> getView(ConnectorSession session, SchemaTableName viewName)
    {
        Optional<com.amazonaws.services.glue.model.Table> table = getTable(viewName);
        if (!table.isPresent()) {
            return Optional.empty();
        }
        com.amazonaws.services.glue.model.Table viewDefinition = table.get();
        return getView(
                viewName,
                Optional.ofNullable(viewDefinition.getViewOriginalText()),
                viewDefinition.getTableType(),
                viewDefinition.getParameters(),
                Optional.ofNullable(viewDefinition.getOwner()));
    }

    @Override
    public List<SchemaTableName> listMaterializedViews(ConnectorSession session, Optional<String> namespace)
    {
        return ImmutableList.of();
    }

    @Override
    public void createMaterializedView(ConnectorSession session, SchemaTableName schemaViewName, ConnectorMaterializedViewDefinition definition, boolean replace, boolean ignoreExisting)
    {
        throw new PrestoException(NOT_SUPPORTED, "createMaterializedView is not supported for Iceberg Glue catalogs");
    }

    @Override
    public void dropMaterializedView(ConnectorSession session, SchemaTableName schemaViewName)
    {
        throw new PrestoException(NOT_SUPPORTED, "dropMaterializedView is not supported for Iceberg Glue catalogs");
    }

    @Override
    public Optional<ConnectorMaterializedViewDefinition> getMaterializedView(ConnectorSession session, SchemaTableName schemaViewName)
    {
        return Optional.empty();
    }

    @Override
    public void renameMaterializedView(ConnectorSession session, SchemaTableName source, SchemaTableName target)
    {
        throw new PrestoException(NOT_SUPPORTED, "renameMaterializedView is not supported for Iceberg Glue catalogs");
    }

    @Override
    public Optional<CatalogSchemaTableName> redirectTable(ConnectorSession session, SchemaTableName tableName)
    {
        requireNonNull(session, "session is null");
        requireNonNull(tableName, "tableName is null");
        Optional<String> targetCatalogName = getHiveCatalogName(session);
        if (!targetCatalogName.isPresent()) {
            return Optional.empty();
        }
        if (isHiveSystemSchema(tableName.getSchemaName())) {
            return Optional.empty();
        }

        // we need to chop off any "$partitions" and similar suffixes from table name while querying the metastore for the Table object
        int metadataMarkerIndex = tableName.getTableName().lastIndexOf('$');
        SchemaTableName tableNameBase = (metadataMarkerIndex == -1) ? tableName : schemaTableName(
                tableName.getSchemaName(),
                tableName.getTableName().substring(0, metadataMarkerIndex));

        Optional<com.amazonaws.services.glue.model.Table> table = getTable(new SchemaTableName(tableNameBase.getSchemaName(), tableNameBase.getTableName()));

        if (!table.isPresent() || VIRTUAL_VIEW.name().equals(table.get().getTableType())) {
            return Optional.empty();
        }
        if (!isIcebergTable(table.get())) {
            // After redirecting, use the original table name, with "$partitions" and similar suffixes
            return targetCatalogName.map(catalog -> new CatalogSchemaTableName(catalog, tableName));
        }
        return Optional.empty();
    }

    private static boolean isIcebergTable(com.amazonaws.services.glue.model.Table table)
    {
        return ICEBERG_TABLE_TYPE_VALUE.equalsIgnoreCase(table.getParameters().get(TABLE_TYPE_PROP));
    }
}
