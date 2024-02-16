package de.telekom.eni.pandora.horizon.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.telekom.eni.pandora.horizon.auth.exception.BadTokenResponseException;
import de.telekom.eni.pandora.horizon.auth.exception.TokenRequestErrorException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class OAuth2TokenCache {
	public static final String IRIS_REALM_PLACEHOLDER = "<realm>";
    public static final String DEFAULT_REALM = "default";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String GRANT_TYPE_FIELD = "grant_type";
    private static final String GRANT_TYPE = "client_credentials";
    private static final String CLIENT_ID_FIELD = "client_id";
    private static final String CLIENT_SECRET_FIELD = "client_secret";
    private final RestTemplate restTemplate;
    private final String accessTokenUrl;
    private final String clientId;
    private final Map<String, String> clientSecretMap = new HashMap<>();
    private final Map<String, AccessToken> accessTokenMap = new HashMap<>();

    public OAuth2TokenCache(String accessTokenUrl, String clientId, String clientSecret) {
        this.restTemplate = new RestTemplate();
        this.accessTokenUrl = accessTokenUrl;
        this.clientId = clientId;

        Arrays.stream(clientSecret.split(",")).forEach(s -> this.clientSecretMap.put(s.split("=")[0], s.split("=")[1]));
    }

    public synchronized String getToken(String environment) {
        if (!clientSecretMap.containsKey(environment)){
            environment = DEFAULT_REALM;
        }
        if (isNotValidToken(environment)) {
            retrieveAccessToken(environment);
        }
        return accessTokenMap.get(environment).getToken();
    }

    private boolean isNotValidToken(String environment) {
        return accessTokenMap.get(environment) == null || accessTokenMap.get(environment).isExpired();
    }

    public void retrieveAllAccessTokens() {
        clientSecretMap.keySet().forEach(this::retrieveAccessToken);
    }

    public void retrieveAccessToken(String environment) {
        var secret = clientSecretMap.get(environment);
        var exchangeUrl = accessTokenUrl.replace(IRIS_REALM_PLACEHOLDER, environment);
        log.info("Trying to retrieve oidc token from {} for realm {}", exchangeUrl, environment);

        final ResponseEntity<String> response = restTemplate.exchange(
            exchangeUrl,
            HttpMethod.POST,
            createRequest(secret),
            String.class);

        AccessToken accessToken;
        try {
            accessToken = convertResponseToAccessToken(response);
        } catch (BadTokenResponseException | TokenRequestErrorException e) {
            throw new RuntimeException(e);
        }

        accessTokenMap.put(environment, accessToken);
    }

    private HttpEntity<MultiValueMap<String, String>> createRequest(String secret) {
        HttpHeaders headers = createHeader();
        MultiValueMap<String, String> body = createBody(secret);

        return new HttpEntity<>(body, headers);
    }

    private AccessToken convertResponseToAccessToken(final ResponseEntity<String> response) throws BadTokenResponseException, TokenRequestErrorException {
        final var statusCode = response.getStatusCode();
        final String responseAsJson = response.getBody();
        if (statusCode.is2xxSuccessful()) {
            log.info("Successfully retrieved oidc token");
            final Map<String, Object> responseAsMap = parseResponse(responseAsJson);
            return AccessToken.of(responseAsMap);
        } else {
            log.warn("Error occurred while requesting oidc token: {}", response);
            throw TokenRequestErrorException.of(response.toString());
        }
    }

    private HttpHeaders createHeader() {
        final var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBearerAuth(accessTokenUrl);
        return headers;
    }

    private MultiValueMap<String, String> createBody(String secret) {
        final MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add(GRANT_TYPE_FIELD, GRANT_TYPE);
        body.add(CLIENT_ID_FIELD, clientId);
        body.add(CLIENT_SECRET_FIELD, secret);
        return body;
    }

    private Map<String, Object> parseResponse(final String responseAsJson) throws BadTokenResponseException {
        try {
            return OBJECT_MAPPER.readValue(responseAsJson, Map.class);
        } catch (IOException e) {
            throw BadTokenResponseException.of(responseAsJson, e);
        }
    }
}
