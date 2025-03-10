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
package io.prestosql.server.protocol;

import com.google.common.collect.Ordering;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.concurrent.BoundedExecutor;
import io.airlift.log.Logger;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import io.prestosql.Session;
import io.prestosql.SystemSessionProperties;
import io.prestosql.client.QueryResults;
import io.prestosql.client.QueryStatusInfo;
import io.prestosql.exchange.ExchangeId;
import io.prestosql.execution.QueryManager;
import io.prestosql.memory.context.SimpleLocalMemoryContext;
import io.prestosql.operator.ExchangeClient;
import io.prestosql.operator.ExchangeClientSupplier;
import io.prestosql.server.ForStatementResource;
import io.prestosql.snapshot.RecoveryUtils;
import io.prestosql.spi.QueryId;
import io.prestosql.spi.block.BlockEncodingSerde;

import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.net.HttpHeaders.X_FORWARDED_PROTO;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static io.airlift.concurrent.Threads.threadsNamed;
import static io.airlift.jaxrs.AsyncResponseHandler.bindAsyncResponse;
import static io.airlift.units.DataSize.Unit.MEGABYTE;
import static io.prestosql.SystemSessionProperties.getRetryPolicy;
import static io.prestosql.client.PrestoHeaders.PRESTO_ADDED_PREPARE;
import static io.prestosql.client.PrestoHeaders.PRESTO_CLEAR_SESSION;
import static io.prestosql.client.PrestoHeaders.PRESTO_CLEAR_TRANSACTION_ID;
import static io.prestosql.client.PrestoHeaders.PRESTO_DEALLOCATED_PREPARE;
import static io.prestosql.client.PrestoHeaders.PRESTO_SET_CATALOG;
import static io.prestosql.client.PrestoHeaders.PRESTO_SET_PATH;
import static io.prestosql.client.PrestoHeaders.PRESTO_SET_ROLE;
import static io.prestosql.client.PrestoHeaders.PRESTO_SET_SCHEMA;
import static io.prestosql.client.PrestoHeaders.PRESTO_SET_SESSION;
import static io.prestosql.client.PrestoHeaders.PRESTO_STARTED_TRANSACTION_ID;
import static io.prestosql.memory.context.AggregatedMemoryContext.newSimpleAggregatedMemoryContext;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static javax.ws.rs.core.MediaType.TEXT_PLAIN_TYPE;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;

@Path("/")
public class ExecutingStatementResource
{
    private static final Logger log = Logger.get(ExecutingStatementResource.class);
    private static final Duration MAX_WAIT_TIME = new Duration(1, SECONDS);
    private static final Ordering<Comparable<Duration>> WAIT_ORDERING = Ordering.natural().nullsLast();

    private static final DataSize DEFAULT_TARGET_RESULT_SIZE = new DataSize(1, MEGABYTE);
    private static final DataSize MAX_TARGET_RESULT_SIZE = new DataSize(128, MEGABYTE);

    private final QueryManager queryManager;
    private final ExchangeClientSupplier exchangeClientSupplier;
    private final BlockEncodingSerde blockEncodingSerde;
    private final BoundedExecutor responseExecutor;
    private final ScheduledExecutorService timeoutExecutor;
    private final RecoveryUtils recoveryUtils;

    private final ConcurrentMap<QueryId, Query> queries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService queryPurger = newSingleThreadScheduledExecutor(threadsNamed("execution-query-purger"));

    @Inject
    public ExecutingStatementResource(
            QueryManager queryManager,
            ExchangeClientSupplier exchangeClientSupplier,
            BlockEncodingSerde blockEncodingSerde,
            RecoveryUtils recoveryUtils,
            @ForStatementResource BoundedExecutor responseExecutor,
            @ForStatementResource ScheduledExecutorService timeoutExecutor)
    {
        this.queryManager = requireNonNull(queryManager, "queryManager is null");
        this.exchangeClientSupplier = requireNonNull(exchangeClientSupplier, "exchangeClientSupplier is null");
        this.blockEncodingSerde = requireNonNull(blockEncodingSerde, "blockEncodingSerde is null");
        this.responseExecutor = requireNonNull(responseExecutor, "responseExecutor is null");
        this.timeoutExecutor = requireNonNull(timeoutExecutor, "timeoutExecutor is null");
        this.recoveryUtils = recoveryUtils;

        queryPurger.scheduleWithFixedDelay(
                () -> {
                    try {
                        for (Entry<QueryId, Query> entry : queries.entrySet()) {
                            // forget about this query if the query manager is no longer tracking it
                            try {
                                queryManager.getQueryState(entry.getKey());
                            }
                            catch (NoSuchElementException e) {
                                // query is no longer registered
                                queries.remove(entry.getKey());
                            }
                        }
                    }
                    catch (Throwable e) {
                        log.warn(e, "Error removing old queries");
                    }
                },
                200,
                200,
                MILLISECONDS);
    }

    @PreDestroy
    public void stop()
    {
        queryPurger.shutdownNow();
    }

    @GET
    @Path("/v1/statement/executing/{queryId}/{slug}/{token}")
    @Produces(MediaType.APPLICATION_JSON)
    public void getQueryResults(
            @PathParam("queryId") QueryId queryId,
            @PathParam("slug") String slug,
            @PathParam("token") long token,
            @QueryParam("maxWait") Duration maxWait,
            @QueryParam("targetResultSize") DataSize targetResultSize,
            @HeaderParam(X_FORWARDED_PROTO) String proto,
            @Context UriInfo uriInfo,
            @Suspended AsyncResponse asyncResponse)
    {
        Query query = getQuery(queryId, slug);
        String tmpProto = proto;
        if (isNullOrEmpty(tmpProto)) {
            tmpProto = uriInfo.getRequestUri().getScheme();
        }

        asyncQueryResults(query, token, maxWait, targetResultSize, uriInfo, tmpProto, asyncResponse);
    }

    protected Query getQuery(QueryId queryId, String slug)
    {
        Query query = queries.get(queryId);
        if (query != null) {
            if (!query.isSlugValid(slug)) {
                throw badRequest(NOT_FOUND, "Query not found");
            }
            return query;
        }

        // this is the first time the query has been accessed on this coordinator
        Session session;
        try {
            if (!queryManager.isQuerySlugValid(queryId, slug)) {
                throw badRequest(NOT_FOUND, "Query not found");
            }
            session = queryManager.getQuerySession(queryId);
        }
        catch (NoSuchElementException e) {
            throw badRequest(NOT_FOUND, "Query not found");
        }

        query = queries.computeIfAbsent(queryId, id -> {
            ExchangeClient exchangeClient = exchangeClientSupplier.get(new SimpleLocalMemoryContext(newSimpleAggregatedMemoryContext(), ExecutingStatementResource.class.getSimpleName()), null, getRetryPolicy(session), new ExchangeId("direct-exchange-query-results"), queryId);
            if (SystemSessionProperties.isRecoveryEnabled(session)) {
                exchangeClient.setRecoveryEnabled(recoveryUtils.getOrCreateRecoveryManager(queryId, session));
            }
            return Query.create(
                    session,
                    slug,
                    queryManager,
                    exchangeClient,
                    responseExecutor,
                    timeoutExecutor,
                    blockEncodingSerde);
        });
        return query;
    }

    private void asyncQueryResults(
            Query query,
            long token,
            Duration maxWait,
            DataSize targetResultSize,
            UriInfo uriInfo,
            String scheme,
            AsyncResponse asyncResponse)
    {
        Duration wait = WAIT_ORDERING.min(MAX_WAIT_TIME, maxWait);
        DataSize tmpTargetResultSize = targetResultSize == null ? DEFAULT_TARGET_RESULT_SIZE : Ordering.natural().min(targetResultSize, MAX_TARGET_RESULT_SIZE);
        ListenableFuture<QueryResults> queryResultsFuture = query.waitForResults(token, uriInfo, scheme, wait, tmpTargetResultSize);

        ListenableFuture<Response> response = Futures.transform(queryResultsFuture, queryResults -> toResponse(query, queryResults), directExecutor());

        bindAsyncResponse(asyncResponse, response, responseExecutor);
    }

    public static Response toResponse(Query query, QueryStatusInfo queryResults)
    {
        ResponseBuilder response = Response.ok(queryResults);

        if (query != null) {
            query.getSetCatalog().ifPresent(catalog -> response.header(PRESTO_SET_CATALOG, catalog));
            query.getSetSchema().ifPresent(schema -> response.header(PRESTO_SET_SCHEMA, schema));
            query.getSetPath().ifPresent(path -> response.header(PRESTO_SET_PATH, path));

            // add set session properties
            query.getSetSessionProperties()
                    .forEach((key, value) -> response.header(PRESTO_SET_SESSION, key + '=' + urlEncode(value)));

            // add clear session properties
            query.getResetSessionProperties()
                    .forEach(name -> response.header(PRESTO_CLEAR_SESSION, name));

            // add set roles
            query.getSetRoles()
                    .forEach((key, value) -> response.header(PRESTO_SET_ROLE, key + '=' + urlEncode(value.toString())));

            // add added prepare statements
            for (Entry<String, String> entry : query.getAddedPreparedStatements().entrySet()) {
                String encodedKey = urlEncode(entry.getKey());
                String encodedValue = urlEncode(entry.getValue());
                response.header(PRESTO_ADDED_PREPARE, encodedKey + '=' + encodedValue);
            }

            // add deallocated prepare statements
            for (String name : query.getDeallocatedPreparedStatements()) {
                response.header(PRESTO_DEALLOCATED_PREPARE, urlEncode(name));
            }

            // add new transaction ID
            query.getStartedTransactionId()
                    .ifPresent(transactionId -> response.header(PRESTO_STARTED_TRANSACTION_ID, transactionId));

            // add clear transaction ID directive
            if (query.isClearTransactionId()) {
                response.header(PRESTO_CLEAR_TRANSACTION_ID, true);
            }
        }

        return response.build();
    }

    @DELETE
    @Path("/v1/statement/executing/{queryId}/{slug}/{token}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response cancelQuery(
            @PathParam("queryId") QueryId queryId,
            @PathParam("slug") String slug,
            @PathParam("token") long token)
    {
        Query query = queries.get(queryId);
        if (query != null) {
            if (!query.isSlugValid(slug)) {
                throw badRequest(NOT_FOUND, "Query not found");
            }
            query.cancel();
            return Response.noContent().build();
        }

        // cancel the query execution directly instead of creating the statement client
        try {
            if (!queryManager.isQuerySlugValid(queryId, slug)) {
                throw badRequest(NOT_FOUND, "Query not found");
            }
            queryManager.cancelQuery(queryId);
            return Response.noContent().build();
        }
        catch (NoSuchElementException e) {
            throw badRequest(NOT_FOUND, "Query not found");
        }
    }

    private static WebApplicationException badRequest(Status status, String message)
    {
        throw new WebApplicationException(
                Response.status(status)
                        .type(TEXT_PLAIN_TYPE)
                        .entity(message)
                        .build());
    }

    private static String urlEncode(String value)
    {
        try {
            return URLEncoder.encode(value, "UTF-8");
        }
        catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }
}
