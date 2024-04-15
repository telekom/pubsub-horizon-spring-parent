package de.telekom.eni.pandora.horizon.kubernetes;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.ListMeta;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.lang.Nullable;

import java.util.*;

@Slf4j
public class KubernetesClientWrapper {

    @Getter
    private final KubernetesClient kubernetesClient;

    public KubernetesClientWrapper(KubernetesClient kubernetesClient) {
        this.kubernetesClient = kubernetesClient;
    }

    public <T extends HasMetadata> List<T> get(Class<T> clazz, String namespace) {
        return get(clazz, namespace, null);
    }
    public <T extends HasMetadata> List<T> get(Class<T> clazz,  @Nullable String namespace, @Nullable Map<String, String> labels) {
        var list = new ArrayList<T>();
        var labelsMap = Optional.ofNullable(labels).orElse(new HashMap<>());
        var limit = 100L;

        var listOptions = new ListOptions();
        listOptions.setLimit(limit);
        listOptions.setContinue(null);

        do {
            KubernetesResourceList<T> l;
            try {
                if (StringUtils.isBlank(namespace)) {
                    l = kubernetesClient.resources(clazz).inAnyNamespace().withLabels(labelsMap).list(listOptions);
                } else {
                    l = kubernetesClient.resources(clazz).inNamespace(namespace).withLabels(labelsMap).list(listOptions);
                }

                list.addAll(l.getItems());

                listOptions.setContinue(l.getMetadata().getContinue());
            } catch (KubernetesClientException e) {
                if (e.getCode() != 404) {
                    throw e;
                }
            }
        } while (StringUtils.isNotBlank(listOptions.getContinue()));

        return list;
    }

    public <T extends HasMetadata> int count(Class<T> clazz) {
        return count(clazz, null, null);
    }

    public <T extends HasMetadata> int count(Class<T> clazz, @Nullable String namespace, @Nullable Map<String, String> labels) {
        var labelsMap = Optional.ofNullable(labels).orElse(new HashMap<>());

        var limit = 1L;

        var listOptions = new ListOptions();
        listOptions.setLimit(limit);
        listOptions.setContinue(null);

        ListMeta listMeta;
        try {
            if (StringUtils.isBlank(namespace)) {
                listMeta = kubernetesClient.resources(clazz).inAnyNamespace().withLabels(labelsMap).list(listOptions).getMetadata();
            } else {
                listMeta = kubernetesClient.resources(clazz).inNamespace(namespace).withLabels(labelsMap).list(listOptions).getMetadata();
            }
        } catch (KubernetesClientException e) {
            if (e.getCode() == 404) {
                return 0;
            }

            throw e;
        }

        return Optional.ofNullable(listMeta.getRemainingItemCount()).map(n -> n + limit).orElse(0L).intValue();
    }

    public <T extends HasMetadata> SharedIndexInformer<T> registerNewInformer(Class<T> clazz, @Nullable String namespace, @Nullable Map<String, String> labels, long resyncPeriodInMs) {
        if (StringUtils.isBlank(namespace)) {
            return  kubernetesClient.resources(clazz)
                    .inAnyNamespace()
                    .withLabels(Optional.ofNullable(labels).orElse(new HashMap<>()))
                    .runnableInformer(resyncPeriodInMs);
        } else {
            return  kubernetesClient.resources(clazz)
                    .inNamespace(namespace)
                    .withLabels(Optional.ofNullable(labels).orElse(new HashMap<>()))
                    .runnableInformer(resyncPeriodInMs);
        }
    }
}