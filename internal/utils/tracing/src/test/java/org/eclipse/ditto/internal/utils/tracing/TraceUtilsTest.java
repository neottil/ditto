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
package org.eclipse.ditto.internal.utils.tracing;

import static org.mutabilitydetector.unittesting.MutabilityAssert.assertInstancesOf;
import static org.mutabilitydetector.unittesting.MutabilityMatchers.areImmutable;

import org.junit.Ignore;
import org.junit.Test;

/**
 * Unit test for {@link TraceUtils}.
 */
public final class TraceUtilsTest {

    @Test
    @Ignore("https://github.com/MutabilityDetector/MutabilityDetector/issues/185")
    public void assertImmutability() {
        assertInstancesOf(TraceUtils.class, areImmutable());
    }

}
