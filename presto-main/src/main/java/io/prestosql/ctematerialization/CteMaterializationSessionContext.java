/*
 * Copyright (C) 2018-2022. Huawei Technologies Co., Ltd. All rights reserved.
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
package io.prestosql.ctematerialization;

import com.google.common.collect.ImmutableMap;
import io.prestosql.Session;
import io.prestosql.server.SessionContext;
import io.prestosql.spi.connector.CatalogName;
import io.prestosql.spi.security.Identity;
import io.prestosql.spi.session.ResourceEstimates;
import io.prestosql.transaction.TransactionId;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class CteMaterializationSessionContext
        implements SessionContext
{
    private Session session;

    public CteMaterializationSessionContext(Session session)
    {
        this.session = session;
    }

    @Override
    public Optional<Identity> getAuthenticatedIdentity()
    {
        return Optional.empty();
    }

    @Override
    public Identity getIdentity()
    {
        return session.getIdentity();
    }

    @Override
    public String getCatalog()
    {
        return session.getCatalog().orElse(null);
    }

    @Override
    public String getSchema()
    {
        return session.getSchema().orElse(null);
    }

    @Override
    public String getPath()
    {
        return session.getPath() != null ? session.getPath().toString() : null;
    }

    @Override
    public String getSource()
    {
        return session.getSource().orElse(null);
    }

    @Override
    public Optional<String> getTraceToken()
    {
        return session.getTraceToken();
    }

    @Override
    public String getRemoteUserAddress()
    {
        return session.getRemoteUserAddress().orElse(null);
    }

    @Override
    public String getUserAgent()
    {
        return session.getUserAgent().orElse(null);
    }

    @Override
    public String getClientInfo()
    {
        return session.getClientInfo().orElse(null);
    }

    @Override
    public Set<String> getClientTags()
    {
        return session.getClientTags();
    }

    @Override
    public Set<String> getClientCapabilities()
    {
        return session.getClientCapabilities();
    }

    @Override
    public ResourceEstimates getResourceEstimates()
    {
        return session.getResourceEstimates();
    }

    @Override
    public String getTimeZoneId()
    {
        return session.getTimeZoneKey().getId();
    }

    @Override
    public String getLanguage()
    {
        return session.getLocale().getLanguage();
    }

    @Override
    public Map<String, String> getSystemProperties()
    {
        return session.getSystemProperties();
    }

    @Override
    public Map<String, Map<String, String>> getCatalogSessionProperties()
    {
        ImmutableMap.Builder<String, Map<String, String>> catalogSessionProperties = ImmutableMap.builder();
        for (Map.Entry<CatalogName, Map<String, String>> entry : session.getConnectorProperties().entrySet()) {
            catalogSessionProperties.put(entry.getKey().getCatalogName(), entry.getValue());
        }
        return catalogSessionProperties.build();
    }

    @Override
    public Map<String, String> getPreparedStatements()
    {
        return session.getPreparedStatements();
    }

    @Override
    public Optional<TransactionId> getTransactionId()
    {
        return session.getTransactionId();
    }

    @Override
    public boolean supportClientTransaction()
    {
        return session.isClientTransactionSupport();
    }
}
