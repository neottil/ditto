/*
 * Copyright (c) 2022 Contributors to the Eclipse Foundation
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
package org.eclipse.ditto.connectivity.service.messaging.hono;

import static org.eclipse.ditto.base.model.common.ConditionChecker.checkArgument;
import static org.eclipse.ditto.base.model.common.ConditionChecker.checkNotNull;

import java.net.URI;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.annotation.concurrent.NotThreadSafe;

import org.eclipse.ditto.connectivity.model.Connection;
import org.eclipse.ditto.connectivity.model.ConnectionType;
import org.eclipse.ditto.connectivity.model.ConnectivityModelFactory;
import org.eclipse.ditto.connectivity.model.FilteredTopic;
import org.eclipse.ditto.connectivity.model.HeaderMapping;
import org.eclipse.ditto.connectivity.model.HonoAddressAlias;
import org.eclipse.ditto.connectivity.model.ReplyTarget;
import org.eclipse.ditto.connectivity.model.Source;
import org.eclipse.ditto.connectivity.model.Target;
import org.eclipse.ditto.connectivity.model.Topic;
import org.eclipse.ditto.connectivity.model.UserPasswordCredentials;
import org.eclipse.ditto.connectivity.service.config.HonoConfig;
import org.eclipse.ditto.internal.utils.extension.DittoExtensionIds;
import org.eclipse.ditto.internal.utils.extension.DittoExtensionPoint;

import com.typesafe.config.Config;

import akka.actor.ActorSystem;

/**
 * Base implementation of a factory for getting a Hono {@link Connection}.
 * The Connection this factory supplies is based on a provided Connection with adjustments of
 * <ul>
 *     <li>the base URI,</li>
 *     <li>the "validate certificates" flag,</li>
 *     <li>the specific config including SASL mechanism, bootstrap server URIs and group ID,</li>
 *     <li>the credentials and</li>
 *     <li>the sources and targets.</li>
 * </ul>
 *
 * @since 3.0.0
 */
public abstract class HonoConnectionFactory implements DittoExtensionPoint {

    /**
     * Constructs a {@code HonoConnectionFactory}.
     */
    protected HonoConnectionFactory() {
        super();
    }

    /**
     * Loads the implementation of {@code HonoConnectionFactory} which is configured for the specified
     * {@code ActorSystem}.
     *
     * @param actorSystem the actorSystem in which the {@code HonoConnectionFactory} should be loaded.
     * @param config the configuration for this extension.
     * @return the {@code HonoConnectionFactory} implementation.
     * @throws NullPointerException if any argument is {@code null}.
     */
    public static HonoConnectionFactory get(final ActorSystem actorSystem, final Config config) {
        checkNotNull(actorSystem, "actorSystem");
        checkNotNull(config, "config");

        return DittoExtensionIds.get(actorSystem)
                .computeIfAbsent(
                        DittoExtensionPoint.ExtensionId.ExtensionIdConfig.of(HonoConnectionFactory.class,
                                config,
                                ExtensionId.CONFIG_KEY),
                        ExtensionId::new
                )
                .get(actorSystem);
    }

    /**
     * Returns a proper Hono Connection for the Connection that was used to create this factory instance.
     *
     * @param connection the connection that serves as base for the Hono connection this factory returns.
     * @return the Hono Connection.
     * @throws NullPointerException if {@code connection} is {@code null}.
     * @throws IllegalArgumentException if the type of {@code connection} is not {@link ConnectionType#HONO};
     * @throws org.eclipse.ditto.base.model.exceptions.DittoRuntimeException if converting {@code connection} to a
     * Hono connection failed for some reason.
     */
    public Connection getHonoConnection(final Connection connection) {
        checkArgument(
                checkNotNull(connection, "connection"),
                arg -> ConnectionType.HONO == arg.getConnectionType(),
                () -> MessageFormat.format("Expected type of connection to be <{0}> but it was <{1}>.",
                        ConnectionType.HONO,
                        connection.getConnectionType())
        );

        preConversion(connection);

        return ConnectivityModelFactory.newConnectionBuilder(connection)
                .uri(String.valueOf(getBaseUri()))
                .validateCertificate(isValidateCertificates())
                .specificConfig(getSpecificConfig(connection))
                .credentials(getCredentials())
                .setSources(getSources(connection.getSources()))
                .setTargets(getTargets(connection.getTargets()))
                .build();
    }

    /**
     * User overridable callback.
     * This method is called before the actual conversion of the specified {@code Connection} is performed.
     * Empty default implementation.
     *
     * @param honoConnection the connection that is guaranteed to have type {@link ConnectionType#HONO}.
     */
    protected void preConversion(final Connection honoConnection) {
        // Do nothing by default.
    }

    protected abstract URI getBaseUri();

    protected abstract boolean isValidateCertificates();

    private Map<String, String> getSpecificConfig(final Connection connection) {
        return Map.of(
                "saslMechanism", String.valueOf(getSaslMechanism()),
                "bootstrapServers", getAsCommaSeparatedListString(getBootstrapServerUris()),
                "groupId", getGroupId(connection)
        );
    }

    protected abstract HonoConfig.SaslMechanism getSaslMechanism();

    protected abstract Set<URI> getBootstrapServerUris();

    private static String getAsCommaSeparatedListString(final Collection<URI> uris) {
        return uris.stream()
                .map(URI::toString)
                .collect(Collectors.joining(","));
    }

    protected abstract String getGroupId(Connection connection);

    protected abstract UserPasswordCredentials getCredentials();

    @SuppressWarnings("unchecked")
    private List<Source> getSources(final Collection<Source> originalSources) {
        return originalSources.stream()
                .map(originalSource -> ConnectivityModelFactory.newSourceBuilder(originalSource)
                        .addresses(resolveSourceAddresses(originalSource.getAddresses()))
                        .replyTarget(getReplyTargetForSource(originalSource).orElse(null))
                        .headerMapping(getSourceHeaderMapping(originalSource))
                        .build())
                .collect(Collectors.toList());
    }

    private Set<String> resolveSourceAddresses(final Collection<String> unresolvedSourceAddresses) {
        return unresolvedSourceAddresses.stream()
                .map(unresolvedSourceAddress -> HonoAddressAlias.forAliasValue(unresolvedSourceAddress)
                        .map(this::resolveSourceAddress)
                        .orElse(unresolvedSourceAddress))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    protected abstract String resolveSourceAddress(HonoAddressAlias honoAddressAlias);

    private Optional<ReplyTarget> getReplyTargetForSource(final Source source) {
        final Optional<ReplyTarget> result;
        if (isApplyReplyTarget(source.getAddresses())) {
            result = source.getReplyTarget()
                    .map(replyTarget -> replyTarget.toBuilder()
                            .address(resolveTargetAddressOrKeepUnresolved(replyTarget.getAddress()))
                            .headerMapping(getReplyTargetHeaderMapping(replyTarget))
                            .build());
        } else {
            result = Optional.empty();
        }
        return result;
    }

    private static boolean isApplyReplyTarget(final Collection<String> sourceAddresses) {
        final Predicate<String> isTelemetryHonoAddressAlias = HonoAddressAlias.TELEMETRY.getAliasValue()::equals;
        final Predicate<String> isEventHonoAddressAlias = HonoAddressAlias.EVENT.getAliasValue()::equals;

        return sourceAddresses.stream()
                .anyMatch(isTelemetryHonoAddressAlias.or(isEventHonoAddressAlias));
    }

    private String resolveTargetAddressOrKeepUnresolved(final String unresolvedTargetAddress) {
        return HonoAddressAlias.forAliasValue(unresolvedTargetAddress)
                .map(this::resolveTargetAddress)
                .orElse(unresolvedTargetAddress);
    }

    protected abstract String resolveTargetAddress(HonoAddressAlias honoAddressAlias);

    private static HeaderMapping getReplyTargetHeaderMapping(final ReplyTarget replyTarget) {
        final var headerMappingBuilder = HeaderMappingBuilder.of(replyTarget.getHeaderMapping());
        headerMappingBuilder.putCorrelationId();
        if (isCommandHonoAddressAlias(replyTarget.getAddress())) {
            headerMappingBuilder.putDeviceId();
            headerMappingBuilder.putSubject(
                    "{{ header:subject | fn:default(topic:action-subject) | fn:default(topic:criterion) }}-response"
            );
        }
        return headerMappingBuilder.build();
    }

    private static boolean isCommandHonoAddressAlias(final String replyTargetAddress) {
        return replyTargetAddress.equals(HonoAddressAlias.COMMAND.getAliasValue());
    }

    private static HeaderMapping getSourceHeaderMapping(final Source source) {
        final HeaderMapping result;
        if (isAdjustSourceHeaderMapping(source.getAddresses())) {
            result = HeaderMappingBuilder.of(source.getHeaderMapping())
                    .putCorrelationId()
                    .putEntry("status", "{{ header:status }}")
                    .build();
        } else {
            result = source.getHeaderMapping();
        }
        return result;
    }

    private static boolean isAdjustSourceHeaderMapping(final Collection<String> sourceAddresses) {
        return sourceAddresses.contains(HonoAddressAlias.COMMAND_RESPONSE.getAliasValue());
    }

    private List<Target> getTargets(final Collection<Target> originalTargets) {
        return originalTargets.stream()
                .map(originalTarget -> ConnectivityModelFactory.newTargetBuilder(originalTarget)
                        .address(resolveTargetAddressOrKeepUnresolved(originalTarget.getAddress()))
                        .headerMapping(getTargetHeaderMapping(originalTarget))
                        .build())
                .collect(Collectors.toList());
    }

    private static HeaderMapping getTargetHeaderMapping(final Target target) {
        final var headerMappingBuilder = HeaderMappingBuilder.of(target.getHeaderMapping())
                .putDeviceId()
                .putCorrelationId()
                .putSubject("{{ header:subject | fn:default(topic:action-subject) }}");

        if (isPutResponseRequiredHeaderMapping(target.getTopics())) {
            headerMappingBuilder.putEntry("response-required", "{{ header:response-required }}");
        }
        return headerMappingBuilder.build();
    }

    private static boolean isPutResponseRequiredHeaderMapping(final Collection<FilteredTopic> targetTopics) {
        final Predicate<Topic> isLiveMessages = topic -> Topic.LIVE_MESSAGES == topic;
        final Predicate<Topic> isLiveCommands = topic -> Topic.LIVE_COMMANDS == topic;

        return targetTopics.stream()
                .map(FilteredTopic::getTopic)
                .anyMatch(isLiveMessages.or(isLiveCommands));
    }

    public static final class ExtensionId extends DittoExtensionPoint.ExtensionId<HonoConnectionFactory> {

        private static final String CONFIG_KEY = "hono-connection-factory";

        private ExtensionId(final ExtensionIdConfig<HonoConnectionFactory> extensionIdConfig) {
            super(extensionIdConfig);
        }

        @Override
        protected String getConfigKey() {
            return CONFIG_KEY;
        }

    }

    @NotThreadSafe
    private static final class HeaderMappingBuilder {

        private final Map<String, String> headerMappingDefinition;

        private HeaderMappingBuilder(final HeaderMapping existingHeaderMapping) {
            headerMappingDefinition = new LinkedHashMap<>(existingHeaderMapping.getMapping());
        }

        static HeaderMappingBuilder of(final HeaderMapping existingHeaderMapping) {
            return new HeaderMappingBuilder(checkNotNull(existingHeaderMapping, "existingHeaderMapping"));
        }

        HeaderMappingBuilder putCorrelationId() {
            headerMappingDefinition.put("correlation-id", "{{ header:correlation-id }}");
            return this;
        }

        HeaderMappingBuilder putDeviceId() {
            headerMappingDefinition.put("device_id", "{{ thing:id }}");
            return this;
        }

        HeaderMappingBuilder putSubject(final String subjectValue) {
            headerMappingDefinition.put("subject", subjectValue);
            return this;
        }

        HeaderMappingBuilder putEntry(final String key, final String value) {
            headerMappingDefinition.put(key, value);
            return this;
        }

        HeaderMapping build() {
            return ConnectivityModelFactory.newHeaderMapping(headerMappingDefinition);
        }

    }

}
