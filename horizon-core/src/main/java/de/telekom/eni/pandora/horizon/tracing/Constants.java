// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.tracing;

public class Constants {
    public static final String HTTP_HEADER_X_B3_TRACEID = "X-B3-TraceId";

    public static final String HTTP_HEADER_X_B3_SPANID = "X-B3-SpanId";

    public static final String HTTP_HEADER_X_B3_SAMPLED = "X-B3-Sampled";

    public static final String HTTP_HEADER_X_B3_PARENTSPANID = "X-B3-ParentSpanId";

    public static final String HTTP_HEADER_X_B3_FLAGS = "X-B3-Flags";

    public static final String HTTP_HEADER_B3 = "b3";

    public static final String HTTP_HEADER_PLUNGER = "plunger-republish";
}
