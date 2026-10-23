package de.telekom.eni.pandora.horizon.logging;

import org.slf4j.MDC;

import java.util.Map;
import java.util.function.Function;

public class MdcDecorator {
    public static Runnable withMdcContext(Runnable runnable, Map<String, String> mdc) {
        return () -> {
            final var previousMdc = MDC.getCopyOfContextMap();
            try {
                MDC.setContextMap(mdc);
                runnable.run();
            } finally {
                if (previousMdc != null)
                    MDC.setContextMap(previousMdc);
                else
                    MDC.clear();
            }
        };
    }

    public static <T, R> Function<T, R> withMdcContext(Function<T, R> function, Map<String, String> mdc) {
        return (T input) -> {
            final var previousMdc = MDC.getCopyOfContextMap();
            try {
                MDC.setContextMap(mdc);
                return function.apply(input);
            } finally {
                if (previousMdc != null)
                    MDC.setContextMap(previousMdc);
                else
                    MDC.clear();
            }
        };
    }
}
