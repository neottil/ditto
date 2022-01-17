/*
 * Copyright (c) 2017 Contributors to the Eclipse Foundation
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
package org.eclipse.ditto.connectivity.service.mapping;

import static org.mutabilitydetector.unittesting.MutabilityAssert.assertInstancesOf;
import static org.mutabilitydetector.unittesting.MutabilityMatchers.areImmutable;

import org.junit.Ignore;
import org.junit.Test;
import org.mutabilitydetector.unittesting.AllowedReason;

import nl.jqno.equalsverifier.EqualsVerifier;

/**
 * Unit test for {@link DefaultMessageMapperConfiguration}.
 */
public final class DefaultMessageMapperConfigurationTest {

    @Test
    @Ignore("https://github.com/MutabilityDetector/MutabilityDetector/issues/185")
    public void assertImmutability() {
        assertInstancesOf(DefaultMessageMapperConfiguration.class, areImmutable(),
                AllowedReason.provided(MergedJsonObjectMap.class).isAlsoImmutable());
    }

    @Test
    public void testHashCodeAndEquals() {
        EqualsVerifier.forClass(DefaultMessageMapperConfiguration.class)
                .usingGetClass()
                .verify();
    }

}
