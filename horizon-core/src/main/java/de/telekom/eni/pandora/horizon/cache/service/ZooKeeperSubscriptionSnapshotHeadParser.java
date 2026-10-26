// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheSnapshotException;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Date;

/** Parses the ZooKeeper JSON representation of a subscription snapshot head. */
public class ZooKeeperSubscriptionSnapshotHeadParser {

    private final ObjectMapper objectMapper;

    /**
     * Creates a parser using the supplied JSON mapper.
     *
     * @param objectMapper mapper used to read the head payload
     */
    public ZooKeeperSubscriptionSnapshotHeadParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parses a ZooKeeper head; {@code id} and unknown fields are ignored, {@code created} accepts any ISO-8601 offset.
     *
     * @param data serialized JSON head
     * @return the validated head
     * @throws SubscriptionCacheSnapshotException if the payload is malformed or invalid
     */
    public SubscriptionSnapshotHead parse(byte[] data) {
        try {
            var snapshotHead = objectMapper.readValue(data, SubscriptionSnapshotHead.class);
            if (StringUtils.isEmpty(snapshotHead.getSnapshotId())) {
                throw new SubscriptionCacheSnapshotException("Field validation of zookeeper subscription head failed");
            }

            return snapshotHead;
        } catch (IOException ex) {
            throw new SubscriptionCacheSnapshotException("Invalid ZooKeeper subscription head", ex);
        }
    }

}