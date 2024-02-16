// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.util;

import de.telekom.eni.pandora.horizon.kubernetes.resource.Subscription;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QueryTest {

    static Query query;

    @Test
    @Order(1)
    void builder() {
        assertDoesNotThrow(() -> {
            query = Query.builder(Subscription.class)
                    .addMatcher("hello", "world")
                    .addMatchers("foo", "bar", "notBar")
                    .build();
        });
    }

    @Test
    @Order(2)
    void testToString() {
        var expectation = "(foo = bar OR foo = notBar) AND hello = world";
        assertEquals(expectation, query.toString());
    }


}