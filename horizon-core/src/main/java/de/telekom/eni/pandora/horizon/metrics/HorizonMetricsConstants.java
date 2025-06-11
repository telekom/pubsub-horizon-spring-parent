// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.metrics;

public class HorizonMetricsConstants {
    //Metrics
    public static final String METRIC_PUBLISHED_EVENTS = "published_events";
    public static final String METRIC_MULTIPLEXED_EVENTS = "multiplexed_events";
    public static final String METRIC_SUBSCRIPTION_COUNT = "subscription_count";
    public static final String METRIC_SENT_SSE_EVENTS = "sent_sse_events";
    public static final String METRIC_OPEN_SSE_CONNECTIONS = "open_sse_connections";
    public static final String METRIC_OPEN_CIRCUIT_BREAKERS = "open_circuit_breakers";
    public static final String METRIC_CALLBACK_HTTP_CODE_COUNT = "callback_http_code_count";
    public static final String METRIC_INTERNAL_EXCEPTION_COUNT = "internal_exception_count";
    public static final String METRIC_PAYLOAD_SIZE_INCOMING = "payload_size_incoming";
    public static final String METRIC_PAYLOAD_SIZE_OUTGOING = "payload_size_outgoing";
    public static final String METRIC_SCHEMA_VALIDATION_FAILURES = "schema_validation_failures";
    public static final String METRIC_SCHEMA_VALIDATION_SUCCESS = "schema_validation_success";

    // Not used yet, but reserved for future use with the new control-plane.
    public static final String METRIC_SCHEMA_VALIDATION_INVALID_SCHEMA = "schema_validation_invalid_schema";

    //Tags
    public static final String TAG_EVENT_TYPE = "event_type";
    public static final String TAG_SUBSCRIPTION_ID = "subscription_id";
    public static final String TAG_SUBSCRIBER_ID = "subscriber_id";
    public static final String TAG_DELIVERY_TYPE = "delivery_type";
    public static final String TAG_ENVIRONMENT = "environment";
    public static final String TAG_HTTP_CODE = "http_code";
    public static final String TAG_CALLBACK_URL = "callback_url";
}

