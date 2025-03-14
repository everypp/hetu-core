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
package io.prestosql.metadata;

import io.prestosql.connector.informationschema.InformationSchemaHandleResolver;
import io.prestosql.connector.system.SystemHandleResolver;
import io.prestosql.snapshot.MarkerSplitHandleResolver;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.connector.ConnectorDeleteAsInsertTableHandle;
import io.prestosql.spi.connector.ConnectorHandleResolver;
import io.prestosql.spi.connector.ConnectorIndexHandle;
import io.prestosql.spi.connector.ConnectorInsertTableHandle;
import io.prestosql.spi.connector.ConnectorOutputTableHandle;
import io.prestosql.spi.connector.ConnectorPartitioningHandle;
import io.prestosql.spi.connector.ConnectorSplit;
import io.prestosql.spi.connector.ConnectorTableExecuteHandle;
import io.prestosql.spi.connector.ConnectorTableHandle;
import io.prestosql.spi.connector.ConnectorTableLayoutHandle;
import io.prestosql.spi.connector.ConnectorTransactionHandle;
import io.prestosql.spi.connector.ConnectorUpdateTableHandle;
import io.prestosql.spi.connector.ConnectorVacuumTableHandle;
import io.prestosql.spi.function.FunctionHandle;
import io.prestosql.spi.function.FunctionHandleResolver;
import io.prestosql.split.EmptySplitHandleResolver;

import javax.inject.Inject;

import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.prestosql.operator.ExchangeOperator.REMOTE_CONNECTOR_ID;
import static java.util.Objects.requireNonNull;

public class HandleResolver
{
    private final ConcurrentMap<String, MaterializedHandleResolver> handleResolvers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, MaterializedFunctionHandleResolver> functionHandleResolvers = new ConcurrentHashMap<>();

    @Inject
    public HandleResolver()
    {
        handleResolvers.put(REMOTE_CONNECTOR_ID.toString(), new MaterializedHandleResolver(new RemoteHandleResolver()));
        handleResolvers.put("$system", new MaterializedHandleResolver(new SystemHandleResolver()));
        handleResolvers.put("$info_schema", new MaterializedHandleResolver(new InformationSchemaHandleResolver()));
        handleResolvers.put("$empty", new MaterializedHandleResolver(new EmptySplitHandleResolver()));
        handleResolvers.put("$marker", new MaterializedHandleResolver(new MarkerSplitHandleResolver()));

        functionHandleResolvers.put("$static", new MaterializedFunctionHandleResolver(new BuiltInFunctionNamespaceHandleResolver()));
    }

    public void addConnectorName(String name, ConnectorHandleResolver resolver)
    {
        requireNonNull(name, "name is null");
        requireNonNull(resolver, "resolver is null");
        MaterializedHandleResolver existingResolver = handleResolvers.putIfAbsent(name, new MaterializedHandleResolver(resolver));
        checkState(existingResolver == null || existingResolver.equals(resolver),
                "Connector '%s' is already assigned to resolver: %s", name, existingResolver);
    }

    public void addFunctionNamespace(String name, FunctionHandleResolver resolver)
    {
        requireNonNull(name, "name is null");
        requireNonNull(resolver, "resolver is null");
        MaterializedFunctionHandleResolver existingResolver = functionHandleResolvers.putIfAbsent(name, new MaterializedFunctionHandleResolver(resolver));
        checkState(existingResolver == null || existingResolver.equals(resolver), "Name %s is already assigned to function resolver: %s", name, existingResolver);
    }

    public String getId(ConnectorTableHandle tableHandle)
    {
        return getId(tableHandle, MaterializedHandleResolver::getTableHandleClass);
    }

    public String getId(ConnectorTableLayoutHandle handle)
    {
        return getId(handle, MaterializedHandleResolver::getTableLayoutHandleClass);
    }

    public String getId(ColumnHandle columnHandle)
    {
        return getId(columnHandle, MaterializedHandleResolver::getColumnHandleClass);
    }

    public String getId(ConnectorSplit split)
    {
        return getId(split, MaterializedHandleResolver::getSplitClass);
    }

    public String getId(ConnectorIndexHandle indexHandle)
    {
        return getId(indexHandle, MaterializedHandleResolver::getIndexHandleClass);
    }

    public String getId(ConnectorOutputTableHandle outputHandle)
    {
        return getId(outputHandle, MaterializedHandleResolver::getOutputTableHandleClass);
    }

    public String getId(ConnectorInsertTableHandle insertHandle)
    {
        return getId(insertHandle, MaterializedHandleResolver::getInsertTableHandleClass);
    }

    public String getId(ConnectorUpdateTableHandle updateHandle)
    {
        return getId(updateHandle, MaterializedHandleResolver::getUpdateTableHandleClass);
    }

    public String getId(ConnectorDeleteAsInsertTableHandle deleteHandle)
    {
        return getId(deleteHandle, MaterializedHandleResolver::getDeleteAsInsertTableHandleClass);
    }

    public String getId(ConnectorVacuumTableHandle vacuumTableHandle)
    {
        return getId(vacuumTableHandle, MaterializedHandleResolver::getVacuumTableHandleClass);
    }

    public String getId(ConnectorPartitioningHandle partitioningHandle)
    {
        return getId(partitioningHandle, MaterializedHandleResolver::getPartitioningHandleClass);
    }

    public String getId(ConnectorTransactionHandle transactionHandle)
    {
        return getId(transactionHandle, MaterializedHandleResolver::getTransactionHandleClass);
    }

    public String getId(ConnectorTableExecuteHandle connectorTableExecuteHandle)
    {
        return getId(connectorTableExecuteHandle, MaterializedHandleResolver::getTableExecuteHandleClass);
    }

    public Class<? extends ConnectorTableHandle> getTableHandleClass(String id)
    {
        return resolverFor(id).getTableHandleClass().orElseThrow(() -> new IllegalArgumentException("No resolver for " + id));
    }

    public Class<? extends ConnectorTableLayoutHandle> getTableLayoutHandleClass(String id)
    {
        return resolverFor(id).getTableLayoutHandleClass().orElseThrow(() -> new IllegalArgumentException("No resolver for " + id));
    }

    public Class<? extends ColumnHandle> getColumnHandleClass(String id)
    {
        return resolverFor(id).getColumnHandleClass().orElseThrow(() -> new IllegalArgumentException("No resolver for " + id));
    }

    public Class<? extends ConnectorSplit> getSplitClass(String id)
    {
        return resolverFor(id).getSplitClass().orElseThrow(() -> new IllegalArgumentException("No resolver for " + id));
    }

    public Class<? extends ConnectorIndexHandle> getIndexHandleClass(String id)
    {
        return resolverFor(id).getIndexHandleClass().orElseThrow(() -> new IllegalArgumentException("No resolver for " + id));
    }

    public Class<? extends ConnectorOutputTableHandle> getOutputTableHandleClass(String id)
    {
        return resolverFor(id).getOutputTableHandleClass().orElseThrow(() -> new IllegalArgumentException("No resolver for " + id));
    }

    public Class<? extends ConnectorTableExecuteHandle> getTableExecuteHandleClass(String id)
    {
        return resolverFor(id).getTableExecuteHandleClass().orElseThrow(() -> new IllegalArgumentException("No resolver for " + id));
    }

    public Class<? extends ConnectorInsertTableHandle> getInsertTableHandleClass(String id)
    {
        return resolverFor(id).getInsertTableHandleClass().orElseThrow(() -> new IllegalArgumentException("No resolver for " + id));
    }

    public Class<? extends ConnectorUpdateTableHandle> getUpdateTableHandleClass(String id)
    {
        return resolverFor(id).getUpdateTableHandleClass().orElseThrow(() -> new IllegalArgumentException("No resolver for " + id));
    }

    public Class<? extends ConnectorDeleteAsInsertTableHandle> getDeleteAsInsertTableHandleClass(String id)
    {
        return resolverFor(id).getDeleteAsInsertTableHandleClass().orElseThrow(() -> new IllegalArgumentException("No resolver for " + id));
    }

    public Class<? extends ConnectorVacuumTableHandle> getVacuumTableHandleClass(String id)
    {
        return resolverFor(id).getVacuumTableHandleClass().orElseThrow(() -> new IllegalArgumentException("No resolver for " + id));
    }

    public Class<? extends ConnectorPartitioningHandle> getPartitioningHandleClass(String id)
    {
        return resolverFor(id).getPartitioningHandleClass().orElseThrow(() -> new IllegalArgumentException("No resolver for " + id));
    }

    public Class<? extends ConnectorTransactionHandle> getTransactionHandleClass(String id)
    {
        return resolverFor(id).getTransactionHandleClass().orElseThrow(() -> new IllegalArgumentException("No resolver for " + id));
    }

    private MaterializedHandleResolver resolverFor(String id)
    {
        MaterializedHandleResolver resolver = handleResolvers.get(id);
        checkArgument(resolver != null, "No handle resolver for connector: %s", id);
        return resolver;
    }

    private <T> String getId(T handle, Function<MaterializedHandleResolver, Optional<Class<? extends T>>> getter)
    {
        for (Entry<String, MaterializedHandleResolver> entry : handleResolvers.entrySet()) {
            try {
                if (getter.apply(entry.getValue()).map(clazz -> clazz.isInstance(handle)).orElse(false)) {
                    return entry.getKey();
                }
            }
            catch (UnsupportedOperationException ignored) {
                // could be ignored
            }
        }
        throw new IllegalArgumentException("No connector for handle: " + handle);
    }

    private <T> String getAnyId(T handle, Function<MaterializedHandleResolver, Optional<List<Class<? extends T>>>> getter)
    {
        for (Entry<String, MaterializedHandleResolver> entry : handleResolvers.entrySet()) {
            try {
                Optional<List<Class<? extends T>>> optionals = getter.apply(entry.getValue());
                if (optionals.map(list -> list.stream().anyMatch(clazz -> clazz.isInstance(handle))).orElse(false)) {
                    return entry.getKey();
                }
            }
            catch (UnsupportedOperationException ignored) {
                // could be ignored
            }
        }
        throw new IllegalArgumentException("No connector for handle: " + handle);
    }

    private static class MaterializedFunctionHandleResolver
    {
        private final Optional<Class<? extends FunctionHandle>> functionHandle;

        public MaterializedFunctionHandleResolver(FunctionHandleResolver resolver)
        {
            functionHandle = getHandleClass(resolver::getFunctionHandleClass);
        }

        private static <T> Optional<Class<? extends T>> getHandleClass(Supplier<Class<? extends T>> callable)
        {
            try {
                return Optional.of(callable.get());
            }
            catch (UnsupportedOperationException e) {
                return Optional.empty();
            }
        }

        public Optional<Class<? extends FunctionHandle>> getFunctionHandleClass()
        {
            return functionHandle;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            MaterializedFunctionHandleResolver that = (MaterializedFunctionHandleResolver) o;
            return Objects.equals(functionHandle, that.functionHandle);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(functionHandle);
        }
    }

    private static class MaterializedHandleResolver
    {
        private final Optional<Class<? extends ConnectorTableHandle>> tableHandle;
        private final Optional<Class<? extends ConnectorTableLayoutHandle>> layoutHandle;
        private final Optional<Class<? extends ColumnHandle>> columnHandle;
        private final Optional<Class<? extends ConnectorSplit>> split;
        private final Optional<Class<? extends ConnectorIndexHandle>> indexHandle;
        private final Optional<Class<? extends ConnectorOutputTableHandle>> outputTableHandle;
        private final Optional<Class<? extends ConnectorInsertTableHandle>> insertTableHandle;
        private final Optional<Class<? extends ConnectorUpdateTableHandle>> updateTableHandle;
        private final Optional<Class<? extends ConnectorVacuumTableHandle>> vacuumTableHandle;
        private final Optional<Class<? extends ConnectorPartitioningHandle>> partitioningHandle;
        private final Optional<Class<? extends ConnectorTransactionHandle>> transactionHandle;
        private final Optional<Class<? extends ConnectorDeleteAsInsertTableHandle>> deleteAsInsertTableHandle;
        private final Optional<Class<? extends ConnectorTableExecuteHandle>> connectorTableExecuteHandle;

        public MaterializedHandleResolver(ConnectorHandleResolver resolver)
        {
            tableHandle = getHandleClass(resolver::getTableHandleClass);
            layoutHandle = getHandleClass(resolver::getTableLayoutHandleClass);
            columnHandle = getHandleClass(resolver::getColumnHandleClass);
            split = getHandleClass(resolver::getSplitClass);
            indexHandle = getHandleClass(resolver::getIndexHandleClass);
            outputTableHandle = getHandleClass(resolver::getOutputTableHandleClass);
            insertTableHandle = getHandleClass(resolver::getInsertTableHandleClass);
            updateTableHandle = getHandleClass(resolver::getUpdateTableHandleClass);
            vacuumTableHandle = getHandleClass(resolver::getVacuumTableHandleClass);
            partitioningHandle = getHandleClass(resolver::getPartitioningHandleClass);
            transactionHandle = getHandleClass(resolver::getTransactionHandleClass);
            deleteAsInsertTableHandle = getHandleClass(resolver::getDeleteAsInsertTableHandleClass);
            connectorTableExecuteHandle = getHandleClass(resolver::getTableExecuteHandleClass);
        }

        private static <T> Optional<Class<? extends T>> getHandleClass(Supplier<Class<? extends T>> callable)
        {
            try {
                return Optional.of(callable.get());
            }
            catch (UnsupportedOperationException e) {
                return Optional.empty();
            }
        }

        public Optional<Class<? extends ConnectorTableHandle>> getTableHandleClass()
        {
            return tableHandle;
        }

        public Optional<Class<? extends ConnectorTableLayoutHandle>> getTableLayoutHandleClass()
        {
            return layoutHandle;
        }

        public Optional<Class<? extends ColumnHandle>> getColumnHandleClass()
        {
            return columnHandle;
        }

        public Optional<Class<? extends ConnectorSplit>> getSplitClass()
        {
            return split;
        }

        public Optional<Class<? extends ConnectorIndexHandle>> getIndexHandleClass()
        {
            return indexHandle;
        }

        public Optional<Class<? extends ConnectorOutputTableHandle>> getOutputTableHandleClass()
        {
            return outputTableHandle;
        }

        public Optional<Class<? extends ConnectorInsertTableHandle>> getInsertTableHandleClass()
        {
            return insertTableHandle;
        }

        public Optional<Class<? extends ConnectorUpdateTableHandle>> getUpdateTableHandleClass()
        {
            return updateTableHandle;
        }

        public Optional<Class<? extends ConnectorDeleteAsInsertTableHandle>> getDeleteAsInsertTableHandleClass()
        {
            return deleteAsInsertTableHandle;
        }

        public Optional<Class<? extends ConnectorVacuumTableHandle>> getVacuumTableHandleClass()
        {
            return vacuumTableHandle;
        }

        public Optional<Class<? extends ConnectorPartitioningHandle>> getPartitioningHandleClass()
        {
            return partitioningHandle;
        }

        public Optional<Class<? extends ConnectorTransactionHandle>> getTransactionHandleClass()
        {
            return transactionHandle;
        }

        public Optional<Class<? extends ConnectorTableExecuteHandle>> getTableExecuteHandleClass()
        {
            return connectorTableExecuteHandle;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            MaterializedHandleResolver that = (MaterializedHandleResolver) o;
            return Objects.equals(tableHandle, that.tableHandle) &&
                    Objects.equals(layoutHandle, that.layoutHandle) &&
                    Objects.equals(columnHandle, that.columnHandle) &&
                    Objects.equals(split, that.split) &&
                    Objects.equals(indexHandle, that.indexHandle) &&
                    Objects.equals(outputTableHandle, that.outputTableHandle) &&
                    Objects.equals(insertTableHandle, that.insertTableHandle) &&
                    Objects.equals(partitioningHandle, that.partitioningHandle) &&
                    Objects.equals(transactionHandle, that.transactionHandle);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(tableHandle, layoutHandle, columnHandle, split, indexHandle, outputTableHandle, insertTableHandle, partitioningHandle, transactionHandle);
        }
    }
}
