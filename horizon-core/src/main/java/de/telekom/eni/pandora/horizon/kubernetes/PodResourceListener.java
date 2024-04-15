package de.telekom.eni.pandora.horizon.kubernetes;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;

@Slf4j
public class PodResourceListener extends AbstractResourceListener<Pod>  {

	public PodResourceListener(KubernetesClientWrapper kubernetesClientWrapper, ResourceEventHandler<Pod> handler, long resyncPeriodInMs, String namespace, Map<String, String> labels, ApplicationEventPublisher applicationEventPublisher) {
		super(kubernetesClientWrapper, handler, resyncPeriodInMs, namespace, labels, applicationEventPublisher);
	}

	@Override
	protected Class<Pod> getApiTypeClass() {
		return Pod.class;
	}
}