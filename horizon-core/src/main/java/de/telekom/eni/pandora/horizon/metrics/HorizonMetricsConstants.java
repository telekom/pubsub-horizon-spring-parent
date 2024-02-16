package de.telekom.eni.pandora.horizon.metrics;

public class HorizonMetricsConstants {
    //Metrics
    public static final String METRIC_PUBLISHED_EVENTS = "published_events";
    public static final String METRIC_MULTIPLEXED_EVENTS = "multiplexed_events";
    public static final String METRIC_SUBSCRIPTION_COUNT = "subscription_count";
    public static final String METRIC_OPEN_SSE_CONNECTIONS = "open_sse_connections";
    public static final String METRIC_OPEN_CIRCUIT_BREAKERS = "open_circuit_breakers";
    public static final String METRIC_CALLBACK_HTTP_CODE_COUNT = "callback_http_code_count";
    public static final String METRIC_INTERNAL_EXCEPTION_COUNT = "internal_exception_count";
    public static final String METRIC_PAYLOAD_SIZE_INCOMING = "payload_size_incoming";
    public static final String METRIC_PAYLOAD_SIZE_OUTGOING = "payload_size_outgoing";

    //Tags
    public static final String TAG_EVENT_TYPE = "event_type";
    public static final String TAG_SUBSCRIPTION_ID = "subscription_id";
    public static final String TAG_SUBSCRIBER_ID = "subscriber_id";
    public static final String TAG_DELIVERY_TYPE = "delivery_type";
    public static final String TAG_ENVIRONMENT = "environment";
    public static final String TAG_HTTP_CODE = "http_code";
    public static final String TAG_CALLBACK_URL = "callback_url";
}

