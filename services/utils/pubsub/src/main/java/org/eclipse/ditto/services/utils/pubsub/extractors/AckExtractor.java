/*
 * Copyright (c) 2020 Contributors to the Eclipse Foundation
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
package org.eclipse.ditto.services.utils.pubsub.extractors;

import java.util.Collection;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.eclipse.ditto.json.JsonValue;
import org.eclipse.ditto.model.base.acks.AcknowledgementLabel;
import org.eclipse.ditto.model.base.acks.AcknowledgementRequest;
import org.eclipse.ditto.model.base.entity.id.EntityIdWithType;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.signals.acks.base.Acknowledgement;
import org.eclipse.ditto.signals.acks.base.Acknowledgements;

/**
 * Extract information relevant to acknowledgements from a message.
 *
 * @param <T> type of messages.
 */
public interface AckExtractor<T> {

    /**
     * Get the requested and declared  custom acknowledgement labels.
     *
     * @param requestedAcks the acknowledgement requests.
     * @param isDeclaredAck test if an ack label in string form is declared.
     * @return the intersection of requested, declared and non-built-in acknowledgement labels.
     */
    static Collection<AcknowledgementLabel> getRequestedAndDeclaredCustomAcks(
            final Set<AcknowledgementRequest> requestedAcks,
            final Predicate<String> isDeclaredAck) {
        return requestedAcks.stream()
                .map(AcknowledgementRequest::getLabel)
                .filter(label -> !AckExtractorImpl.BUILT_IN_LABELS.contains(label) &&
                        isDeclaredAck.test(label.toString()))
                .collect(Collectors.toList());
    }

    /**
     * Get the declared custom acknowledgement labels requested by a message.
     *
     * @param message the message.
     * @param isDeclaredAck test if an ack label in string form is declared.
     * @return the intersection of declared and non-built-in acknowledgement labels with the labels requested by the
     * message.
     */
    default Collection<AcknowledgementLabel> getDeclaredCustomAcksRequestedBy(final T message,
            final Predicate<String> isDeclaredAck) {
        return getRequestedAndDeclaredCustomAcks(getAckRequests(message), isDeclaredAck);
    }

    /**
     * Get the acknowledgement requests of a message.
     *
     * @param message the message.
     * @return the acknowledgement requests.
     */
    Set<AcknowledgementRequest> getAckRequests(final T message);

    /**
     * Get the entity ID of a message with entity type information.
     *
     * @param message the message.
     * @return the entity ID.
     */
    EntityIdWithType getEntityId(final T message);

    /**
     * Get the Ditto headers of a message.
     *
     * @param message the message.
     * @return the Ditto headers.
     */
    DittoHeaders getDittoHeaders(final T message);

    /**
     * Create weak acknowledgements for a message.
     *
     * @param message the message.
     * @param ackLabels the acknowledgement labels for which weak acknowledgements are to be created.
     * @return the weak acknowledgements.
     */
    default Acknowledgements toWeakAcknowledgements(final T message,
            final Collection<AcknowledgementLabel> ackLabels) {
        final EntityIdWithType entityId = getEntityId(message);
        final DittoHeaders dittoHeaders = getDittoHeaders(message);
        return Acknowledgements.of(ackLabels.stream()
                        .map(ackLabel -> Acknowledgement.weak(ackLabel, entityId, dittoHeaders,
                                JsonValue.of("Acknowledgement was issued automatically, because the subscriber " +
                                        "is not authorized to receive the signal.")))
                        .collect(Collectors.toList()),
                dittoHeaders
        );
    }

    /**
     * Create an {@code AckExtractor} from the extractor functions.
     *
     * @param getEntityId a function to extract the entity ID.
     * @param getDittoHeaders a function to extract the Ditto headers.
     * @param <T> the type of messages.
     * @return the AckExtractor.
     */
    static <T> AckExtractor<T> of(final Function<T, EntityIdWithType> getEntityId,
            final Function<T, DittoHeaders> getDittoHeaders) {

        return new AckExtractorImpl<>(getEntityId, getDittoHeaders);
    }

}
