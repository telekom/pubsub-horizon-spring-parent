// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheSnapshotException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ZooKeeperSubscriptionSnapshotHeadParserTest {

    private final ZooKeeperSubscriptionSnapshotHeadParser parser =
        new ZooKeeperSubscriptionSnapshotHeadParser(new ObjectMapper());

    @Test
    void parsesHeadIgnoringUnknownFields() {
        var head = parser.parse(bytes("""
                {
                    "snapshotId": "snapshot-1",
                    "documentCount": 3,
                    "created": "2026-01-01T00:00:00Z",
                    "resumeToken": "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
                    "history": []
                }
            """));

        assertEquals("snapshot-1", head.getSnapshotId());
        assertEquals(3L, head.getDocumentCount());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), head.getCreated().toInstant());
    }

    @Test
    void rejectsMalformedHead() {
        var truncated = "{\"snapshotId\":\"snapshot-1\",\"documentCount\":3";
        var trailingComma = "{\"snapshotId\":\"snapshot-1\",\"documentCount\":3,}";
        var unquotedValue = "{\"snapshotId\":snapshot-1,\"documentCount\":3}";
        var emptySnapshotId = "{\"snapshotId\":\"\",\"documentCount\":3}";
        var nullSnapshotId = "{\"snapshotId\":null,\"documentCount\":3}";

        assertThrows(SubscriptionCacheSnapshotException.class, () -> parser.parse(bytes(truncated)));
        assertThrows(SubscriptionCacheSnapshotException.class, () -> parser.parse(bytes(trailingComma)));
        assertThrows(SubscriptionCacheSnapshotException.class, () -> parser.parse(bytes(unquotedValue)));
        assertThrows(SubscriptionCacheSnapshotException.class, () -> parser.parse(bytes(emptySnapshotId)));
        assertThrows(SubscriptionCacheSnapshotException.class, () -> parser.parse(bytes(nullSnapshotId)));
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}