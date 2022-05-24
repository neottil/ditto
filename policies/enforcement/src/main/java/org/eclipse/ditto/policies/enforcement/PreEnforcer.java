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
package org.eclipse.ditto.policies.enforcement;

import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import org.eclipse.ditto.base.model.auth.AuthorizationContext;
import org.eclipse.ditto.base.model.auth.AuthorizationSubject;
import org.eclipse.ditto.base.model.exceptions.DittoInternalErrorException;
import org.eclipse.ditto.base.model.exceptions.DittoRuntimeException;
import org.eclipse.ditto.base.model.headers.DittoHeaderDefinition;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.base.model.headers.DittoHeadersSettable;
import org.eclipse.ditto.internal.utils.akka.controlflow.WithSender;
import org.eclipse.ditto.internal.utils.akka.logging.DittoLogger;
import org.eclipse.ditto.internal.utils.akka.logging.DittoLoggerFactory;

import akka.actor.ActorRef;

/**
 * Create processing units of Akka stream graph before enforcement from an asynchronous function that may abort
 * enforcement by throwing exceptions.
 *
 * @param <T> the type of the signals to pre-enforce.
 */
public interface PreEnforcer<T extends DittoHeadersSettable<?>> extends Function<T, CompletionStage<T>>{

    /**
     * Logger of pre-enforcers.
     */
    DittoLogger LOGGER = DittoLoggerFactory.getLogger(PreEnforcer.class);

    /**
     * Perform pre-enforcement with error handling.
     *
     * @param withSender input signal together with its sender.
     * @param onError result after pre-enforcement failure.
     * @param andThen what happens after pre-enforcement success.
     * @param <S> argument type of {@code andThen}.
     * @param <F> future value type returned by {@code andThen}.
     * @return result of the pre-enforcement.
     */
    @SuppressWarnings({"unchecked", "rawtypes", "java:S3740"})
    default <S extends WithSender<? extends DittoHeadersSettable<?>>, F> CompletionStage<F> withErrorHandlingAsync(
            final S withSender,
            final F onError,
            final Function<S, CompletionStage<F>> andThen) {

        final T message = (T) withSender.getMessage();
        return apply(message)
                // the cast to (S) is safe if the post-condition of this.apply(DittoHeadersSettable<?>) holds.
                .thenCompose(msg -> andThen.apply((S) ((WithSender) withSender).withMessage(msg)))
                .exceptionally(error -> {
                    final ActorRef sender = withSender.getSender();
                    final DittoHeaders dittoHeaders = message.getDittoHeaders();
                    final DittoRuntimeException dittoRuntimeException =
                            DittoRuntimeException.asDittoRuntimeException(error, cause -> {
                                LOGGER.withCorrelationId(dittoHeaders)
                                        .error("Unexpected non-DittoRuntimeException error - responding with" +
                                                        " DittoInternalErrorException: {} - {} - {}",
                                                error.getClass().getSimpleName(), error.getMessage(), error);

                                return DittoInternalErrorException.newBuilder()
                                        .dittoHeaders(dittoHeaders)
                                        .cause(cause)
                                        .build();
                            });
                    sender.tell(dittoRuntimeException, ActorRef.noSender());
                    return onError;
                });
    }

}
