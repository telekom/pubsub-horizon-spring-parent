package de.telekom.eni.pandora.horizon.kubernetes.util;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import lombok.Getter;

@Getter
public class InformerWrapper {

    private final SharedIndexInformer<? extends HasMetadata> informer;

    private final String namespace;

    public InformerWrapper(SharedIndexInformer<? extends HasMetadata> informer, String namespace) {
        this.informer = informer;
        this.namespace = namespace;
    }
}