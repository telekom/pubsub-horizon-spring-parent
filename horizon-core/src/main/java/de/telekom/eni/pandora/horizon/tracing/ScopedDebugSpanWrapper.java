package de.telekom.eni.pandora.horizon.tracing;

import brave.ScopedSpan;
import brave.SpanCustomizer;
import org.springframework.lang.Nullable;

public class ScopedDebugSpanWrapper implements SpanCustomizer {

    private final ScopedSpan scopedSpan;

    public ScopedDebugSpanWrapper(@Nullable ScopedSpan scopedSpan) {
        this.scopedSpan = scopedSpan;
    }

    public void finish() {
        if (scopedSpan != null) {
            scopedSpan.finish();
        }
    }

    @Override
    public SpanCustomizer name(String name) {
        if (scopedSpan != null) {
            return scopedSpan.name(name);
        } else {
            return null;
        }
    }

    @Override
    public SpanCustomizer tag(String key, String value) {
        if (scopedSpan != null) {
            return scopedSpan.tag(key, value);
        } else {
            return null;
        }
    }

    @Override
    public SpanCustomizer annotate(String value) {
        if (scopedSpan != null) {
            return scopedSpan.annotate(value);
        } else {
            return null;
        }
    }

    public SpanCustomizer error(Throwable throwable) {
        if (scopedSpan != null) {
            return scopedSpan.error(throwable);
        } else {
            return null;
        }
    }

    public boolean isNoop() {
        if (scopedSpan != null) {
            return scopedSpan.isNoop();
        } else {
            return true;
        }
    }
}
