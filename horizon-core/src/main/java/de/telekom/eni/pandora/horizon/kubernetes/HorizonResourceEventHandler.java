package de.telekom.eni.pandora.horizon.kubernetes;

import io.fabric8.kubernetes.client.informers.ResourceEventHandler;

import java.util.Collection;

public interface HorizonResourceEventHandler<T> extends ResourceEventHandler<T> {
    void onInitialStateSet(Collection<T> obj);
}
