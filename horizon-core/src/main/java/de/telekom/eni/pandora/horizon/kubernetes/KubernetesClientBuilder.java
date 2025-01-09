package de.telekom.eni.pandora.horizon.kubernetes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import de.telekom.eni.pandora.horizon.exception.CouldNotConstructKubernetesClientException;
import de.telekom.eni.pandora.horizon.kubernetes.config.KubernetesProperties;
import de.telekom.eni.pandora.horizon.util.RoverToken;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

public class KubernetesClientBuilder {

    private final KubernetesProperties kubernetesProperties;

    public KubernetesClientBuilder(KubernetesProperties kubernetesProperties) {
        this.kubernetesProperties = kubernetesProperties;
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
    private Config withCustomSettings(Config config) {
        config.setRequestTimeout(kubernetesProperties.getRequestTimeoutInMs());
        config.setConnectionTimeout(kubernetesProperties.getConnectionTimeoutMs());

        return config;
    }

    public KubernetesClient createClientFromRoverToken(String roverToken) throws CouldNotConstructKubernetesClientException {
        try {
            var config = parseRoverToken(roverToken);

            return new DefaultKubernetesClient(withCustomSettings(config));
        } catch (Exception e) {
            throw new CouldNotConstructKubernetesClientException("Error: Rover token could not be parsed.", e);
        }
    }

    public KubernetesClient createClientFromeKubeconfigFile(String path) throws CouldNotConstructKubernetesClientException {
        try {
            var configFile = new File(path);
            var configYAML = String.join("\n", Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8));

            var config = Config.fromKubeconfig(configYAML);

            return new DefaultKubernetesClient(withCustomSettings(config));
        } catch (IOException e) {
            throw new CouldNotConstructKubernetesClientException(String.format("Error: Kubernetes config %1s could not be processed.", path), e);
        }
    }

    public KubernetesClient createDefaultClient() {
        var config = new ConfigBuilder().build();

        return new DefaultKubernetesClient(withCustomSettings(config));
    }
}
