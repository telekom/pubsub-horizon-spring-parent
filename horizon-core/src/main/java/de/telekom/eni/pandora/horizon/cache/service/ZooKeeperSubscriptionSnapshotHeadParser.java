package de.telekom.eni.pandora.horizon.cache.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheSnapshotException;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Date;

public class ZooKeeperSubscriptionSnapshotHeadParser {

    private final ObjectMapper objectMapper;

    public ZooKeeperSubscriptionSnapshotHeadParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parses a ZooKeeper head; {@code id} and unknown fields are ignored, {@code createdAt} accepts any ISO-8601 offset.
     *
     * @param data serialized JSON head
     * @return the validated head
     * @throws SubscriptionCacheSnapshotException if the payload is malformed or invalid
     */
    public SubscriptionSnapshotHead parse(byte[] data) {
        try {
            var json = objectMapper.readTree(data);
            if (json == null || !json.isObject()) {
                throw invalidHead();
            }

            var snapshotId = json.path("snapshotId");
            var documentCount = json.path("documentCount");
            var revision = json.path("revision");
            var sourceHash = json.path("sourceHash");
            var createdAt = json.path("createdAt");
            if (!snapshotId.isTextual()
                    || !documentCount.isIntegralNumber() || !documentCount.canConvertToLong()
                    || !(isUnset(revision) || (revision.isIntegralNumber() && revision.canConvertToLong()))
                    || !(isUnset(sourceHash) || sourceHash.isTextual())
                    || !createdAt.isTextual()) {
                throw invalidHead();
            }

            var head = new SubscriptionSnapshotHead();
            head.setSnapshotId(snapshotId.asText());
            head.setDocumentCount(documentCount.longValue());
            head.setRevision(isUnset(revision) ? null : revision.longValue());
            head.setSourceHash(isUnset(sourceHash) ? null : sourceHash.asText());
            head.setCreatedAt(Date.from(OffsetDateTime.parse(createdAt.asText()).toInstant()));
            return SubscriptionSnapshotHeads.requireValid(head);
        } catch (IOException | DateTimeParseException | IllegalArgumentException exception) {
            throw new SubscriptionCacheSnapshotException("Invalid ZooKeeper subscription snapshot head", exception);
        }
    }

    private static boolean isUnset(JsonNode node) {
        return node.isMissingNode() || node.isNull();
    }

    private static SubscriptionCacheSnapshotException invalidHead() {
        return new SubscriptionCacheSnapshotException("Invalid ZooKeeper subscription snapshot head");
    }
}