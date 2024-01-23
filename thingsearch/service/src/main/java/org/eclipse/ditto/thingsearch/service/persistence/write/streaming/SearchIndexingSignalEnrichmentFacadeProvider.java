/*
 * Copyright (c) 2024 Contributors to the Eclipse Foundation
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

import java.util.*;
import java.util.concurrent.Executor;

import org.eclipse.ditto.internal.models.signalenrichment.CachingSignalEnrichmentFacade;
import org.eclipse.ditto.internal.models.signalenrichment.SearchIndexingSignalEnrichmentFacade;
import org.eclipse.ditto.internal.models.signalenrichment.SignalEnrichmentFacade;
import org.eclipse.ditto.internal.utils.cache.config.CacheConfig;

import com.typesafe.config.Config;

import org.apache.pekko.actor.ActorSystem;
import org.eclipse.ditto.internal.utils.config.DefaultScopedConfig;
import org.eclipse.ditto.json.*;
import org.eclipse.ditto.things.model.Thing;

import org.eclipse.ditto.thingsearch.service.common.config.DittoSearchConfig;
import org.eclipse.ditto.thingsearch.service.common.config.NamespaceSearchIndexConfig;
import org.eclipse.ditto.thingsearch.service.common.config.SearchConfig;

/**
 * Default {@link SearchIndexingSignalEnrichmentFacadeProvider} who provides a {@link org.eclipse.ditto.internal.models.signalenrichment.SearchIndexingSignalEnrichmentFacade}.
 */
public final class SearchIndexingSignalEnrichmentFacadeProvider implements CachingSignalEnrichmentFacadeProvider {

    private static final Set<JsonFieldDefinition> REQUIRED_INDEXED_FIELDS = Set.of(
            Thing.JsonFields.ID,
            Thing.JsonFields.POLICY_ID,
            Thing.JsonFields.NAMESPACE,
            Thing.JsonFields.REVISION);

    /**
     * Instantiate this provider. Called by reflection.
     * @param actorSystem the actor system in which to load the extension.
     * @param config the configuration for this extension.
     */
    @SuppressWarnings("unused")
    public SearchIndexingSignalEnrichmentFacadeProvider(final ActorSystem actorSystem, final Config config) {
        // No-Op but required for extension initialisation
    }

    @Override
    public CachingSignalEnrichmentFacade getSignalEnrichmentFacade(
            final ActorSystem actorSystem,
            final SignalEnrichmentFacade cacheLoaderFacade,
            final CacheConfig cacheConfig,
            final Executor cacheLoaderExecutor,
            final String cacheNamePrefix) {

        final SearchConfig searchConfig =
                DittoSearchConfig.of(DefaultScopedConfig.dittoScoped(actorSystem.settings().config()));

        // Build a map of field selectors for the enrichment facade to use to quickly look up by Thing namespace.
        final Map<String, JsonFieldSelector> namespaceToFieldSelector = new HashMap<>();

        for (final NamespaceSearchIndexConfig namespaceConfig : searchConfig.getNamespaceSearchIncludeFields()) {

            if (!namespaceConfig.getSearchIncludeFields().isEmpty()) {

                // Ensure the list has the required fields needed for the search to work.
                final Set<String> set = new HashSet<>(namespaceConfig.getSearchIncludeFields());
                set.addAll(REQUIRED_INDEXED_FIELDS.stream().map(JsonFieldDefinition::getPointer).map(JsonPointer::toString).toList());

                final List<String> searchIncludeFields = new ArrayList<>(set);

                JsonFieldSelector indexedFields = JsonFactory.newFieldSelector(searchIncludeFields, JsonParseOptions.newBuilder().build());

                namespaceToFieldSelector.put(namespaceConfig.getNamespace(), indexedFields);
            }
        }

        return SearchIndexingSignalEnrichmentFacade.newInstance(
                namespaceToFieldSelector,
                cacheLoaderFacade,
                cacheConfig,
                cacheLoaderExecutor,
                cacheNamePrefix);
    }
}
