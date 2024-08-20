/*
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.thingsearch.service.persistence.write.streaming;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;

import org.apache.pekko.NotUsed;
import org.apache.pekko.actor.ActorRef;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.actor.Scheduler;
import org.apache.pekko.japi.Pair;
import org.apache.pekko.japi.pf.PFBuilder;
import org.apache.pekko.pattern.AskTimeoutException;
import org.apache.pekko.stream.javadsl.Flow;
import org.apache.pekko.stream.javadsl.Keep;
import org.apache.pekko.stream.javadsl.Source;
import org.eclipse.ditto.base.model.exceptions.AskException;
import org.eclipse.ditto.internal.models.signalenrichment.CachingSignalEnrichmentFacade;
import org.eclipse.ditto.internal.models.streaming.AbstractEntityIdWithRevision;
import org.eclipse.ditto.internal.utils.cache.Cache;
import org.eclipse.ditto.internal.utils.cache.CacheFactory;
import org.eclipse.ditto.internal.utils.cache.config.CacheConfig;
import org.eclipse.ditto.internal.utils.cache.entry.Entry;
import org.eclipse.ditto.internal.utils.cacheloaders.config.AskWithRetryConfig;
import org.eclipse.ditto.internal.utils.config.DefaultScopedConfig;
import org.eclipse.ditto.internal.utils.config.ScopedConfig;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.json.JsonRuntimeException;
import org.eclipse.ditto.policies.api.PolicyTag;
import org.eclipse.ditto.policies.enforcement.PolicyCacheLoader;
import org.eclipse.ditto.policies.model.Policy;
import org.eclipse.ditto.policies.model.PolicyId;
import org.eclipse.ditto.policies.model.PolicyIdInvalidException;
import org.eclipse.ditto.things.model.Thing;
import org.eclipse.ditto.things.model.ThingId;
import org.eclipse.ditto.things.model.signals.events.ThingDeleted;
import org.eclipse.ditto.things.model.signals.events.ThingEvent;
import org.eclipse.ditto.thingsearch.service.common.config.DittoSearchConfig;
import org.eclipse.ditto.thingsearch.service.common.config.SearchConfig;
import org.eclipse.ditto.thingsearch.service.common.config.StreamCacheConfig;
import org.eclipse.ditto.thingsearch.service.common.config.StreamConfig;
import org.eclipse.ditto.thingsearch.service.persistence.write.mapping.EnforcedThingMapper;
import org.eclipse.ditto.thingsearch.service.persistence.write.model.AbstractWriteModel;
import org.eclipse.ditto.thingsearch.service.persistence.write.model.Metadata;
import org.eclipse.ditto.thingsearch.service.persistence.write.model.ThingDeleteModel;
import org.eclipse.ditto.thingsearch.service.persistence.write.model.ThingWriteModel;
import org.eclipse.ditto.thingsearch.service.updater.actors.MongoWriteModel;
import org.eclipse.ditto.thingsearch.service.updater.actors.SearchUpdateObserver;
import org.eclipse.ditto.thingsearch.service.updater.actors.ThingUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts Thing changes into write models by retrieving data and applying enforcement via an enforcer cache.
 */
final class EnforcementFlow {

    private static final Source<Entry<Pair<Policy, Set<PolicyTag>>>, NotUsed> POLICY_NONEXISTENT =
            Source.single(Entry.nonexistent());
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final CachingSignalEnrichmentFacade thingsFacade;
    private final Cache<PolicyIdResolvingImports, Entry<Pair<Policy, Set<PolicyTag>>>> policyEnforcerCache;
    private final Duration cacheRetryDelay;
    private final SearchUpdateObserver searchUpdateObserver;
    private final int maxArraySize;

    private EnforcementFlow(final ActorSystem actorSystem,
            final ActorRef thingsShardRegion,
            final Cache<PolicyIdResolvingImports, Entry<Pair<Policy, Set<PolicyTag>>>> policyEnforcerCache,
            final AskWithRetryConfig askWithRetryConfig,
            final StreamCacheConfig thingCacheConfig,
            final Executor thingCacheDispatcher) {

        thingsFacade = createThingsFacade(actorSystem, thingsShardRegion, askWithRetryConfig.getAskTimeout(),
                thingCacheConfig, thingCacheDispatcher);
        this.policyEnforcerCache = policyEnforcerCache;
        searchUpdateObserver =
                SearchUpdateObserver.get(actorSystem, ScopedConfig.dittoExtension(actorSystem.settings().config()));
        cacheRetryDelay = thingCacheConfig.getRetryDelay();
        final SearchConfig searchConfig =
                DittoSearchConfig.of(DefaultScopedConfig.dittoScoped(actorSystem.settings().config()));
        maxArraySize = searchConfig.getUpdaterConfig().getStreamConfig().getMaxArraySize();
    }

    /**
     * Create an EnforcementFlow object.
     *
     * @param actorSystem the actor system for loading the {@link CachingSignalEnrichmentFacadeProvider}
     * @param updaterStreamConfig configuration of the updater stream.
     * @param thingsShardRegion the shard region to retrieve things from.
     * @param policiesShardRegion the shard region to retrieve policies from.
     * @param scheduler the scheduler to use for retrying timed out asks for the policy enforcer cache loader.
     * @return an EnforcementFlow object.
     */
    public static EnforcementFlow of(final ActorSystem actorSystem,
            final StreamConfig updaterStreamConfig,
            final ActorRef thingsShardRegion,
            final ActorRef policiesShardRegion,
            final Scheduler scheduler) {

        final var askWithRetryConfig = updaterStreamConfig.getAskWithRetryConfig();
        final var policyCacheConfig = updaterStreamConfig.getPolicyCacheConfig();
        final var policyCacheDispatcher = actorSystem.dispatchers()
                .lookup(policyCacheConfig.getDispatcherName());

        final PolicyCacheLoader policyCacheLoader =
                PolicyCacheLoader.getNewInstance(askWithRetryConfig, scheduler, policiesShardRegion);
        final CompletableFuture<Cache<PolicyIdResolvingImports, Entry<Pair<Policy, Set<PolicyTag>>>>> cacheFuture =
                new CompletableFuture<>();
        final ResolvedPolicyCacheLoader resolvedPolicyCacheLoader =
                new ResolvedPolicyCacheLoader(policyCacheLoader, cacheFuture);
        final Cache<PolicyIdResolvingImports, Entry<Pair<Policy, Set<PolicyTag>>>> policyEnforcerCache =
                CacheFactory.createCache(resolvedPolicyCacheLoader, policyCacheConfig,
                        "things-search_enforcementflow_enforcer_cache_policy", policyCacheDispatcher);
        cacheFuture.complete(policyEnforcerCache);

        final var thingCacheConfig = updaterStreamConfig.getThingCacheConfig();
        final var thingCacheDispatcher = actorSystem.dispatchers()
                .lookup(thingCacheConfig.getDispatcherName());
        return new EnforcementFlow(actorSystem, thingsShardRegion, policyEnforcerCache, askWithRetryConfig,
                thingCacheConfig, thingCacheDispatcher);
    }

    /**
     * Decide whether to reload an enforcer entry.
     * An entry should be reloaded if it is out-of-date, nonexistent, or corresponds to a nonexistent entity.
     *
     * @param entry the enforcer cache entry
     * @param metadata the metadata
     * @param iteration how many times cache read was attempted
     * @return whether to reload the cache
     */
    private static boolean shouldReloadCache(@Nullable final Entry<Pair<Policy, Set<PolicyTag>>> entry,
            final Metadata metadata, final int iteration) {

        if (iteration <= 0) {
            return metadata.shouldInvalidatePolicy() || entry == null || !entry.exists() ||
                    entry.getRevision() < metadata.getAllReferencedPolicyTags()
                            .stream()
                            .filter(referencedPolicyTag -> referencedPolicyTag.getEntityId()
                                    .equals(entry.getValueOrThrow().first().getEntityId().orElse(null)))
                            .map(AbstractEntityIdWithRevision::getRevision)
                            .findAny()
                            .orElse(Long.MAX_VALUE);
        } else {
            // never attempt to reload cache more than once
            return false;
        }
    }

    /**
     * Create a flow from Thing changes to write models by retrieving data from Things shard region and enforcer cache.
     *
     * @param source the source of change maps.
     * @param parallelismPerBulkShard how many thing retrieves to perform in parallel to the caching facade per bulk
     * shard.
     * @return the flow.
     */
    public <T> Source<List<AbstractWriteModel>, T> create(
            final Source<Collection<Metadata>, T> source,
            final int parallelismPerBulkShard,
            final int maxBulkSize) {

        return source.flatMapConcat(changes -> Source.fromIterator(changes::iterator)
                        .flatMapMerge(parallelismPerBulkShard, changedMetadata ->
                                retrieveThingFromCachingFacade(changedMetadata.getThingId(), changedMetadata, 3)
                                        .flatMapConcat(pair -> {
                                            final JsonObject thing = pair.second();
                                            searchUpdateObserver.process(changedMetadata, thing);
                                            return computeWriteModel(changedMetadata, thing);
                                        })
                        )
                        .grouped(maxBulkSize))
                .filterNot(List::isEmpty);
    }

    /**
     * Create an enforcement flow for a thing-updater.
     *
     * @param mapper The search-update mapper.
     * @return The enforcement flow.
     */
    public Flow<ThingUpdater.Data, MongoWriteModel, NotUsed> create(final SearchUpdateMapper mapper) {
        return Flow.<ThingUpdater.Data>create()
                .flatMapConcat(data -> retrieveThingFromCachingFacade(data.metadata().getThingId(), data.metadata(), 3)
                        .flatMapConcat(pair -> {
                            final JsonObject thing = pair.second();
                            searchUpdateObserver.process(data.metadata(), thing);
                            return computeWriteModel(data.metadata(), thing);
                        })
                        .flatMapConcat(writeModel -> mapper.processWriteModel(writeModel, data.lastWriteModel())
                                .orElse(Source.lazySource(() -> {
                                    data.metadata().sendWeakAck(null);
                                    return Source.empty();
                                })))
                );
    }

    private Source<Pair<ThingId, JsonObject>, NotUsed> retrieveThingFromCachingFacade(final ThingId thingId,
            final Metadata metadata, final int leftRetryAttempts) {

        ConsistencyLag.startS3RetrieveThing(metadata);
        final CompletionStage<JsonObject> thingFuture = provideThingFuture(thingId, metadata);

        return Source.completionStage(thingFuture)
                .map(thing -> Pair.create(thingId, thing))
                .recoverWithRetries(1, new PFBuilder<Throwable, Source<Pair<ThingId, JsonObject>, NotUsed>>()
                        .match(Throwable.class, error -> {
                            if (error instanceof CompletionException completionException &&
                                    completionException.getCause() instanceof AskTimeoutException) {
                                if (leftRetryAttempts > 0) {
                                    // retry ask timeouts
                                    return retrieveThingFromCachingFacade(thingId, metadata, leftRetryAttempts - 1);
                                } else {
                                    log.warn("No retries left, try to SudoRetrieveThing via cache, therefore giving " +
                                            "up for thingId <{}>", thingId);
                                    return Source.empty();
                                }
                            }

                            log.error("Unexpected exception during SudoRetrieveThing via cache for thingId <{}> - {}",
                                    thingId, error.getClass().getSimpleName(), error);
                            return Source.empty();
                        })
                        .build());
    }

    private CompletionStage<JsonObject> provideThingFuture(final ThingId thingId, final Metadata metadata) {
        final CompletionStage<JsonObject> thingFuture;
        if (metadata.shouldInvalidateThing()) {
            thingFuture = thingsFacade.retrieveThing(thingId, List.of(), -1);
        } else {
            thingFuture = thingsFacade.retrieveThing(thingId, metadata.getEvents(), metadata.getThingRevision());
        }
        return thingFuture;
    }

    private Source<AbstractWriteModel, NotUsed> computeWriteModel(final Metadata metadata,
            @Nullable final JsonObject thing) {

        ConsistencyLag.startS4GetEnforcer(metadata);
        final ThingEvent<?> latestEvent = metadata.getEvents()
                .stream().max(Comparator.comparing(e -> e.getTimestamp().orElseGet(() -> {
                    log.warn("Event <{}> did not contain a timestamp.", e);
                    return Instant.EPOCH;
                })))
                .orElse(null);
        if (latestEvent instanceof ThingDeleted || thing == null || thing.isEmpty()) {
            log.info("Computed single ThingDeleteModel for metadata <{}> and thing <{}>", metadata, thing);
            return Source.single(ThingDeleteModel.of(metadata));
        } else {
            return getPolicy(metadata, thing)
                    .map(entry -> {
                        if (entry.exists()) {
                            try {
                                final Pair<Policy, Set<PolicyTag>> pair = entry.getValueOrThrow();
                                return EnforcedThingMapper.toWriteModel(thing, pair.first(), pair.second(),
                                        entry.getRevision(), metadata, maxArraySize);
                            } catch (final JsonRuntimeException e) {
                                log.error(e.getMessage(), e);
                                log.info(
                                        "Computed - due to <{}: {}> - 'emptied out' ThingWriteModel for metadata <{}> and thing <{}>",
                                        e.getClass().getSimpleName(), e.getMessage(), metadata, thing);
                                return ThingWriteModel.ofEmptiedOut(metadata);
                            }
                        } else {
                            if (entry.isFetchError()) {
                                final Throwable fetchErrorCause = entry.getFetchErrorCause().orElse(
                                        new IllegalStateException("No fetch error cause present when it should be")
                                );
                                log.warn(
                                        "Computed - due to fetch error <{}: {}> on policy cache - 'no op' ThingWriteModel " +
                                                "for metadata <{}> and thing <{}>",
                                        fetchErrorCause.getClass().getSimpleName(), fetchErrorCause.getMessage(),
                                        metadata, thing, fetchErrorCause
                                );
                                return ThingWriteModel.noopWriteModel(metadata);
                            } else {
                                // no enforcer; "empty out" thing in search index
                                log.warn("Computed - due to missing enforcer - 'emptied out' ThingWriteModel for " +
                                        "metadata <{}> and thing <{}>", metadata, thing);
                                return ThingWriteModel.ofEmptiedOut(metadata);
                            }
                        }
                    });
        }
    }

    /**
     * Get the enforcer of a thing or an empty source if it does not exist.
     *
     * @param metadata metadata of the thing.
     * @param thing the thing
     * @return source of an enforcer or an empty source.
     */
    private Source<Entry<Pair<Policy, Set<PolicyTag>>>, NotUsed> getPolicy(final Metadata metadata,
            final JsonObject thing) {
        try {
            return thing.getValue(Thing.JsonFields.POLICY_ID)
                    .map(PolicyId::of)
                    .map(policyId -> readCachedEnforcer(metadata, policyId, 0))
                    .orElse(POLICY_NONEXISTENT);
        } catch (final PolicyIdInvalidException e) {
            return POLICY_NONEXISTENT;
        }
    }

    private Source<Entry<Pair<Policy, Set<PolicyTag>>>, NotUsed> readCachedEnforcer(final Metadata metadata,
            final PolicyId policyId, final int iteration) {

        final Source<Entry<Pair<Policy, Set<PolicyTag>>>, ?> lazySource = Source.lazySource(() -> {
            final CompletionStage<Source<Entry<Pair<Policy, Set<PolicyTag>>>, NotUsed>> enforcerFuture =
                    policyEnforcerCache.get(new PolicyIdResolvingImports(policyId, true))
                            .thenApply(optionalEnforcerEntry -> {
                                if (shouldReloadCache(optionalEnforcerEntry.orElse(null), metadata, iteration)) {
                                    // invalid entry; invalidate and retry after delay
                                    policyEnforcerCache.invalidate(new PolicyIdResolvingImports(policyId, true));

                                    // only invalidate causing policy tag once, e.g. when a massively imported policy is changed:
                                    metadata.getCausingPolicyTag()
                                            .ifPresent(causingPolicyTag -> {
                                                final boolean invalidated = policyEnforcerCache.invalidateConditionally(
                                                        new PolicyIdResolvingImports(causingPolicyTag.getEntityId(), false),
                                                        entry -> !entry.exists() ||
                                                                entry.getRevision() < causingPolicyTag.getRevision()
                                                );
                                                log.debug("Causing policy tag was invalidated conditionally: <{}>",
                                                        invalidated);
                                            });

                                    return readCachedEnforcer(metadata, policyId, iteration + 1)
                                            .initialDelay(cacheRetryDelay);
                                } else {
                                    return optionalEnforcerEntry.map(Source::single)
                                            .orElse(POLICY_NONEXISTENT);
                                }
                            })
                            .exceptionally(error -> {
                                final Throwable cause = error instanceof CompletionException ? error.getCause() : error;
                                log.warn("Failed to read policyEnforcerCache", cause);
                                if (cause instanceof AskException askException) {
                                    return Source.single(Entry.fetchError(askException));
                                }
                                return POLICY_NONEXISTENT;
                            });

            return Source.completionStageSource(enforcerFuture);
        });

        return lazySource.viaMat(Flow.create(), Keep.none());
    }

    private static CachingSignalEnrichmentFacade createThingsFacade(final ActorSystem actorSystem,
            final ActorRef thingsShardRegion,
            final Duration timeout,
            final CacheConfig thingCacheConfig,
            final Executor thingCacheDispatcher) {

        final var sudoRetrieveThingFacade = SudoSignalEnrichmentFacade.of(thingsShardRegion, timeout);
        final var dittoExtensionsConfig = ScopedConfig.dittoExtension(actorSystem.settings().config());
        final var cachingSignalEnrichmentFacadeProvider =
                CachingSignalEnrichmentFacadeProvider.get(actorSystem, dittoExtensionsConfig);
        return cachingSignalEnrichmentFacadeProvider.getSignalEnrichmentFacade(actorSystem, sudoRetrieveThingFacade,
                thingCacheConfig, thingCacheDispatcher, "things-search_enforcementflow_enforcer_cache_things");
    }

}
