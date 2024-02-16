package de.telekom.eni.pandora.horizon.kubernetes;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.ListMeta;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class KubernetesClientWrapper {

    @Getter
    private final KubernetesClient kubernetesClient;

    public KubernetesClientWrapper(KubernetesClient kubernetesClient) {
        this.kubernetesClient = kubernetesClient;
    }

    public <T extends HasMetadata> int count(Class<T> clazz) {
        return count(clazz, null);
    }

    public <T extends HasMetadata> List<T> get(Class<T> clazz, String namespace) {
        var list = new ArrayList<T>();

        var limit = 100L;

        var listOptions = new ListOptions();
        listOptions.setLimit(limit);
        listOptions.setContinue(null);

        do {
            KubernetesResourceList<T> l;
            try {
                if (StringUtils.isNotBlank(namespace)) {
                    l = kubernetesClient.resources(clazz).inNamespace(namespace).list(listOptions);
                } else {
                    l = kubernetesClient.resources(clazz).list(listOptions);
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

    public <T extends HasMetadata> int count(Class<T> clazz, String namespace) {
        var limit = 1L;

        var listOptions = new ListOptions();
        listOptions.setLimit(limit);
        listOptions.setContinue(null);

        ListMeta listMeta;
        try {
            if (StringUtils.isNotBlank(namespace)) {
                listMeta = kubernetesClient.resources(clazz).inNamespace(namespace).list(listOptions).getMetadata();
            } else {
                listMeta = kubernetesClient.resources(clazz).list(listOptions).getMetadata();
            }
        } catch (KubernetesClientException e) {
            if (e.getCode() == 404) {
                return 0;
            }

            throw e;
        }

        return Optional.ofNullable(listMeta.getRemainingItemCount()).map(n -> n + limit).orElse(0L).intValue();
    }
}
