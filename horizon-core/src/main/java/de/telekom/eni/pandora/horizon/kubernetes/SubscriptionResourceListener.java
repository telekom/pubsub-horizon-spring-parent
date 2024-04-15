package de.telekom.eni.pandora.horizon.kubernetes;

import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

@Slf4j
public class SubscriptionResourceListener extends AbstractResourceListener<SubscriptionResource> {

	public SubscriptionResourceListener(KubernetesClientWrapper kubernetesClientWrapper, ResourceEventHandler<SubscriptionResource> handler, long resyncPeriodInMs, String namespace, ApplicationEventPublisher applicationEventPublisher) {
		super(kubernetesClientWrapper, handler, resyncPeriodInMs, namespace, null, applicationEventPublisher);
	}

	@Override
	protected Class<SubscriptionResource> getApiTypeClass() {
		return SubscriptionResource.class;
	}
}