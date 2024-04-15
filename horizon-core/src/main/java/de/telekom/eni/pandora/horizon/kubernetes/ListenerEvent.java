package de.telekom.eni.pandora.horizon.kubernetes;

import lombok.Getter;

@Getter
public class ListenerEvent {
    public enum Type {
        POD_RESOURCE_LISTENER,
        SUBSCRIPTION_RESOURCE_LISTENER,
        UNKNOWN_RESOURCE_LISTENER,
    }
    public enum Event {
        INFORMER_STARTED, INFORMER_INITIAL_STATE_SET, INFORMER_STOPPED, INFORMER_EXCEPTION,
    }
    private final Type type;
    private final Event event;
    public ListenerEvent(Type type, Event event) {
        this.type = type;
        this.event = event;
    }
}
