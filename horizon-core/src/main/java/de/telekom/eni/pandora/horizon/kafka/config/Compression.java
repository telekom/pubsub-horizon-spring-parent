package de.telekom.eni.pandora.horizon.kafka.config;

import lombok.Data;

@Data
public class Compression {

    private boolean enabled = false;

    private String type = "none";

}
