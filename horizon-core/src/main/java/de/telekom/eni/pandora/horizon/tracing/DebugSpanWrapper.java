package de.telekom.eni.pandora.horizon.tracing;

import brave.Span;
import brave.SpanCustomizer;
import lombok.Getter;
import org.springframework.lang.Nullable;

import java.util.Objects;

@Getter
public class DebugSpanWrapper implements SpanCustomizer {

    private final Span span;

    public DebugSpanWrapper(@Nullable Span span) {
        this.span = span;
    }

    public Span start() {
        if (Objects.nonNull(span)) {
            span.start();
        }
        return null;
    }

    public void finish() {
        if (Objects.nonNull(span)) {
            span.finish();
        }
    }

    public Span kind(Span.Kind kind) {
        if (Objects.nonNull(span)) {
            return span.kind(kind);
        }
        return null;
    }

    @Override
    public SpanCustomizer name(String name) {
        if (Objects.nonNull(span)) {
            return span.name(name);
        }
        return null;
    }

    @Override
    public SpanCustomizer tag(String key, String value) {
        if (Objects.nonNull(span)) {
            return span.tag(key, value);
        }
        return null;
    }

    @Override
    public SpanCustomizer annotate(String value) {
        if (Objects.nonNull(span)) {
            return span.annotate(value);
        }
        return null;
    }

    public SpanCustomizer error(Throwable throwable) {
        if (span != null) {
            return span.error(throwable);
        } else {
            return null;
        }
    }

    public boolean isNoop() {
        if (span != null) {
            return span.isNoop();
        } else {
            return true;
        }
    }
}
