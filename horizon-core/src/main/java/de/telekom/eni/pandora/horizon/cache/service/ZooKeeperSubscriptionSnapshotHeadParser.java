package de.telekom.eni.pandora.horizon.cache.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheSnapshotException;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Date;

public class ZooKeeperSubscriptionSnapshotHeadParser {

    private final ObjectMapper objectMapper;

    public ZooKeeperSubscriptionSnapshotHeadParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SubscriptionSnapshotHead parse(byte[] data) {
        try {
            var json = objectMapper.readTree(data);
            if (json == null || !json.isObject() || json.has("id")) {
                throw invalidHead();
            }

            var snapshotId = json.path("snapshotId");
            var documentCount = json.path("documentCount");
            var revision = json.path("revision");
            var sourceHash = json.path("sourceHash");
            var createdAt = json.path("createdAt");
            if (!snapshotId.isTextual() || snapshotId.asText().isBlank()
                    || !documentCount.isIntegralNumber() || !documentCount.canConvertToLong()
                    || documentCount.longValue() <= 0
                    || !(revision.isMissingNode() || revision.isNull()
                        || (revision.isIntegralNumber() && revision.canConvertToLong()))
                    || !sourceHash.isTextual()
                    || !createdAt.isTextual() || !createdAt.asText().endsWith("Z")) {
                throw invalidHead();
            }

            var head = new SubscriptionSnapshotHead();
            head.setSnapshotId(snapshotId.asText());
            head.setDocumentCount(documentCount.longValue());
            head.setRevision(revision.isNumber() ? revision.longValue() : null);
            head.setSourceHash(sourceHash.asText());
            head.setCreatedAt(Date.from(Instant.parse(createdAt.asText())));
            return head;
        } catch (IOException | DateTimeParseException | IllegalArgumentException exception) {
            throw new SubscriptionCacheSnapshotException("Invalid ZooKeeper subscription snapshot head", exception);
        }
    }

    private static SubscriptionCacheSnapshotException invalidHead() {
        return new SubscriptionCacheSnapshotException("Invalid ZooKeeper subscription snapshot head");
    }
}