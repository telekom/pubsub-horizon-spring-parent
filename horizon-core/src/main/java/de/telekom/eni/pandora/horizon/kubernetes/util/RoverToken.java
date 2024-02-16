package de.telekom.eni.pandora.horizon.kubernetes.util;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.HashMap;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RoverToken {
	
	private String masterUrl;
	
	private String token;
	
	private String caCertificate;

	private HashMap<String, String> scope;
}
