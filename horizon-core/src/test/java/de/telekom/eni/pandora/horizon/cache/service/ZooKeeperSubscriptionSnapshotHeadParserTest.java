package de.telekom.eni.pandora.horizon.cache.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheSnapshotException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ZooKeeperSubscriptionSnapshotHeadParserTest {

    private final ZooKeeperSubscriptionSnapshotHeadParser parser =
        new ZooKeeperSubscriptionSnapshotHeadParser(new ObjectMapper());

    @Test
    void parsesHeadWithoutMongoIdAndWithNullableRevision() {
        var head = parser.parse(bytes("""
            {"snapshotId":"snapshot-1","documentCount":3,"revision":null,
             "sourceHash":"hash","createdAt":"2026-09-30T12:30:00Z"}
            """));

        assertNull(head.getId());
        assertEquals("snapshot-1", head.getSnapshotId());
        assertEquals(3L, head.getDocumentCount());
        assertNull(head.getRevision());
        assertEquals("hash", head.getSourceHash());
        assertEquals(Instant.parse("2026-09-30T12:30:00Z"), head.getCreatedAt().toInstant());

        var revised = parser.parse(bytes("""
            {"snapshotId":"snapshot-2","documentCount":1,"revision":7,
             "sourceHash":"hash-2","createdAt":"2026-09-30T12:30:00.123Z"}
            """));
        assertEquals(7L, revised.getRevision());
        assertEquals(Instant.parse("2026-09-30T12:30:00.123Z"), revised.getCreatedAt().toInstant());

        var withoutRevision = parser.parse(bytes("""
            {"snapshotId":"snapshot-3","documentCount":1,
             "sourceHash":"hash-3","createdAt":"2026-09-30T12:30:00Z"}
            """));
        assertNull(withoutRevision.getRevision());
    }

    @Test
    void rejectsMalformedAndInvalidHeads() {
        var invalidHeads = new String[] {
            "not JSON", "null", "[]", "{}",
            "{\"snapshotId\":\"snapshot\",\"documentCount\":1,\"sourceHash\":\"hash\"}",
            "{\"snapshotId\":\"snapshot\",\"documentCount\":1,\"createdAt\":\"2026-09-30T12:30:00Z\"}",
            head("\" \"", "1", "null", "\"hash\"", "\"2026-09-30T12:30:00Z\""),
            head("\"snapshot\"", "0", "null", "\"hash\"", "\"2026-09-30T12:30:00Z\""),
            head("\"snapshot\"", "-1", "null", "\"hash\"", "\"2026-09-30T12:30:00Z\""),
            head("\"snapshot\"", "1.5", "null", "\"hash\"", "\"2026-09-30T12:30:00Z\""),
            head("\"snapshot\"", "9223372036854775808", "null", "\"hash\"", "\"2026-09-30T12:30:00Z\""),
            head("\"snapshot\"", "1", "1.5", "\"hash\"", "\"2026-09-30T12:30:00Z\""),
            head("\"snapshot\"", "1", "null", "null", "\"2026-09-30T12:30:00Z\""),
            head("\"snapshot\"", "1", "null", "\"hash\"", "\"2026-09-30T14:30:00+02:00\""),
            head("\"snapshot\"", "1", "null", "\"hash\"", "\"yesterday\""),
            "{\"id\":\"head\",\"snapshotId\":\"snapshot\",\"documentCount\":1,"
                + "\"sourceHash\":\"hash\",\"createdAt\":\"2026-09-30T12:30:00Z\"}"
        };

        for (var json : invalidHeads) {
            assertThrows(SubscriptionCacheSnapshotException.class, () -> parser.parse(bytes(json)), json);
        }
        assertThrows(SubscriptionCacheSnapshotException.class, () -> parser.parse(null));
    }

    private static String head(String snapshotId, String count, String revision, String sourceHash, String createdAt) {
        return "{\"snapshotId\":" + snapshotId + ",\"documentCount\":" + count
            + ",\"revision\":" + revision + ",\"sourceHash\":" + sourceHash
            + ",\"createdAt\":" + createdAt + "}";
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}