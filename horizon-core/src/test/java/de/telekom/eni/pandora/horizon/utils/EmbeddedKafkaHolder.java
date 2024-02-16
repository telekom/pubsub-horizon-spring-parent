package de.telekom.eni.pandora.horizon.utils;

import org.springframework.kafka.KafkaException;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

public final class EmbeddedKafkaHolder {

    private static final EmbeddedKafkaBroker embeddedKafka = new EmbeddedKafkaKraftBroker(1,1)
            .brokerListProperty("horizon.kafka.bootstrapServers");

    private static boolean started;

    public static EmbeddedKafkaBroker getEmbeddedKafka() {
        if (!started) {
            try {
                embeddedKafka.afterPropertiesSet();
            }
            catch (Exception e) {
                throw new KafkaException("Embedded broker failed to start", e);
            }
            started = true;
        }
        return embeddedKafka;
    }

    private EmbeddedKafkaHolder() {
        super();
    }

}
