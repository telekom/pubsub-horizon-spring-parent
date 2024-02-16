// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import de.telekom.eni.pandora.horizon.exception.CouldNotConstructKubernetesClientException;
import de.telekom.eni.pandora.horizon.kubernetes.InformerStoreInitHandler;
import de.telekom.eni.pandora.horizon.kubernetes.KubernetesClientWrapper;
import de.telekom.eni.pandora.horizon.kubernetes.PodResourceListener;
import de.telekom.eni.pandora.horizon.kubernetes.SubscriptionResourceListener;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.kubernetes.util.RoverToken;
import de.telekom.jsonfilter.operator.Operator;
import de.telekom.jsonfilter.serde.OperatorDeserializer;
import de.telekom.jsonfilter.serde.OperatorSerializer;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.stream.Collectors;

@ConditionalOnProperty(value = "kubernetes.enabled")
@Configuration
@Slf4j
public class KubernetesClientConfiguration {
	
	private static final String KUBERNETES_DEV_CONFIG_FILE_PATH = "kubernetes/config/config.laptop-dev-dev-system";

	@Value("${spring.profiles.active:}")
	private String activeProfile;

	@Value("${kubernetes.rover.token:}")
	private String roverToken;

	@Value("${kubernetes.kubeConfigPath:}")
	private String kubeConfigPath;

	@Value("${kubernetes.informer.resyncperiod.ms:600000}")
	private long resyncPeriodInMs;

	@Value("${kubernetes.informer.namespace:}")
	private String namespace;

	@Value("${kubernetes.informer.pods.namespace:}")
	private String podsNamespace;

	@Value("${kubernetes.informer.pods.appname:}")
	private String appName;

	@Value("${kubernetes.requestTimeoutMs:120000}")
	private int requestTimeoutInMs;

	@Value("${kubernetes.connectionTimeoutMs:120000}")
	private int connectionTimeoutMs;

	private String getResourceFileAsString(String fileName) throws IOException {
        var cl = Thread.currentThread().getContextClassLoader();
                
        try (var is = cl.getResourceAsStream(fileName)) {
            if (is == null) {
            	return null;
            }
            
            try (var isr = new InputStreamReader(is, StandardCharsets.UTF_8); BufferedReader reader = new BufferedReader(isr)) {
                return reader.lines().collect(Collectors.joining(System.lineSeparator()));
            }
        }
    }
    
    private Config parseRoverToken(String roverToken) throws JsonProcessingException {
    	byte[] decodedBytes = Base64.getDecoder().decode(roverToken);
    	
    	RoverToken tokenObject = new YAMLMapper().readValue(new String(decodedBytes), RoverToken.class);
    	
    	return new ConfigBuilder()
    			.withMasterUrl(tokenObject.getMasterUrl())
    			.withOauthToken(tokenObject.getToken())
    			.withCaCertData(tokenObject.getCaCertificate())
    			.withDisableHostnameVerification(true)
    			.build();
    }
    
    private KubernetesClient createClientFromRoverToken(String roverToken) throws CouldNotConstructKubernetesClientException {
    	try {
			var config = parseRoverToken(roverToken);

        	return new DefaultKubernetesClient(withCustomSettings(config));
		} catch (Exception e) {
			throw new CouldNotConstructKubernetesClientException("Error: Rover token could not be parsed.", e);
		}        	
    }
    
    private KubernetesClient createClientFromBundledConfig() throws CouldNotConstructKubernetesClientException {
    	try {
    		var configYAML = getResourceFileAsString(KUBERNETES_DEV_CONFIG_FILE_PATH);
        	if (configYAML == null) {
        		throw new FileNotFoundException(String.format("Error: Kubernetes config %1s could not be found.", KUBERNETES_DEV_CONFIG_FILE_PATH));
        	}
        	
        	var config = Config.fromKubeconfig(configYAML);
      	
        	return new DefaultKubernetesClient(withCustomSettings(config));
		} catch (Exception e) {
			throw new CouldNotConstructKubernetesClientException(String.format("Error: Kubernetes config %1s could not be processed.", KUBERNETES_DEV_CONFIG_FILE_PATH), e);
		}     	
    }
    
    private KubernetesClient createClientFromeKubeconfigFile(String path) throws CouldNotConstructKubernetesClientException {
    	try {    		
    		var configFile = new File(path);
            var configYAML = String.join("\n", Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8));
                		
        	var config = Config.fromKubeconfig(configYAML);
        	        	            	
        	return new DefaultKubernetesClient(withCustomSettings(config));
		} catch (IOException e) {
			throw new CouldNotConstructKubernetesClientException(String.format("Error: Kubernetes config %1s could not be processed.", path), e);
		}     	
    }

	private Config withCustomSettings(Config config) {
		config.setRequestTimeout(requestTimeoutInMs);
		config.setConnectionTimeout(connectionTimeoutMs);

		return config;
	}

	@Bean
    public KubernetesClient kubernetesClient() throws CouldNotConstructKubernetesClientException {
        KubernetesClient client;

        if (!StringUtils.isBlank(roverToken)) {
        	client = createClientFromRoverToken(roverToken);
    		log.info("Using configured rover token for configuring the Kubernetes client");
        } else if (!StringUtils.isBlank(kubeConfigPath)) {
        	client = createClientFromeKubeconfigFile(kubeConfigPath);
    		log.info("Using configured Kubernetes config file ({}) for configuring the Kubernetes client", kubeConfigPath);
        } else if ("dev".equals(activeProfile) || "test".equals(activeProfile)) {
        	client = createClientFromBundledConfig();
        	log.info("Using bundled Kubernetes dev config");
        } else {
			var config = new ConfigBuilder().build();
        	client = new DefaultKubernetesClient(withCustomSettings(config));

    		log.info("Using default Kubernetes config");
        }

		log.info("Using cluster {}", client.getConfiguration().getMasterUrl());

		SimpleModule operatorModule = new SimpleModule();
		operatorModule.addDeserializer(Operator.class, new OperatorDeserializer());
		operatorModule.addSerializer(Operator.class, new OperatorSerializer());
		Serialization.jsonMapper().registerModule(operatorModule);

        return client;
    }

    @Bean
	@ConditionalOnMissingBean(value = SubscriptionResource.class, parameterizedContainer = ResourceEventHandler.class)
	public ResourceEventHandler<SubscriptionResource> defaultEventHandler() {
    	return new ResourceEventHandler<>() {

			@Override
			public void onAdd(SubscriptionResource obj) {
				log.warn("This is the default SubscriptionResource ResourceEventHandler!");
			}

			@Override
			public void onUpdate(SubscriptionResource oldObj, SubscriptionResource newObj) {
				log.warn("This is the default SubscriptionResource ResourceEventHandler!");
			}

			@Override
			public void onDelete(SubscriptionResource obj, boolean deletedFinalStateUnknown) {
				log.warn("This is the default SubscriptionResource ResourceEventHandler!");
			}
		};
	}

	@Bean
	@ConditionalOnMissingBean(value = Pod.class, parameterizedContainer = ResourceEventHandler.class)
	public ResourceEventHandler<Pod> defaultPodEventHandler() {
		return new ResourceEventHandler<>() {

			@Override
			public void onAdd(Pod obj) {
				log.warn("This is the default Pod ResourceEventHandler!");
			}

			@Override
			public void onUpdate(Pod oldObj, Pod newObj) {
				log.warn("This is the default Pod ResourceEventHandler!");
			}

			@Override
			public void onDelete(Pod obj, boolean deletedFinalStateUnknown) {
				log.warn("This is the default Pod ResourceEventHandler!");
			}
		};
	}

	@Bean
	public InformerStoreInitHandler cacheInitHandler(KubernetesClient kubernetesClient) {
		return new InformerStoreInitHandler(new KubernetesClientWrapper(kubernetesClient));
	}

	@Bean
	public SubscriptionResourceListener subscriptionResourceListener(KubernetesClient kubernetesClient, ResourceEventHandler<SubscriptionResource> eventHandler, InformerStoreInitHandler informerStoreInitHandler) {
    	var listener = new SubscriptionResourceListener(kubernetesClient, eventHandler, informerStoreInitHandler, resyncPeriodInMs, namespace);
    	listener.init();
    	return listener;
	}

	@Bean
	public PodResourceListener podResourceListener(KubernetesClient kubernetesClient, ResourceEventHandler<Pod> podEventHandler) {
		var listener = new PodResourceListener(kubernetesClient, podEventHandler, resyncPeriodInMs, podsNamespace, appName);
		listener.init();
		return listener;
	}
}