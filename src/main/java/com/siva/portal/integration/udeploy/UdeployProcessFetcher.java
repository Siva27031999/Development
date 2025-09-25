package com.siva.portal.integration.udeploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Coordinates the two UDeploy API calls for multiple regions in parallel.
 *
 * The first call fetches the latest desired inventory for an environment,
 * extracts the {@code applicationProcessRequestId}, and leverages it to invoke
 * the second API that returns the process request details. Relevant pieces of
 * both payloads are returned to the caller for further processing.
 */
public class UdeployProcessFetcher {

    private static final String LATEST_DESIRED_INVENTORY_PATH_TEMPLATE =
            "rest/deploy/environment/%s/latestDesiredInventory";
    private static final String APPLICATION_PROCESS_REQUEST_PATH_TEMPLATE =
            "rest/deploy/applicationProcessRequest/%s";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(45);
    private static final List<String> DEFAULT_USER_NAME_FIELDS =
            List.of("userName", "user", "requestedBy");

    private final HttpClient httpClient;
    private final Map<Region, URI> regionBaseUris;
    private final ObjectMapper objectMapper;
    private final String basicAuthHeaderValue;
    private final Duration requestTimeout;
    private final List<String> userNameFieldCandidates;

    public UdeployProcessFetcher(Map<Region, URI> regionBaseUris,
                                 String username,
                                 String password) {
        this(regionBaseUris, username, password, DEFAULT_TIMEOUT, DEFAULT_USER_NAME_FIELDS);
    }

    public UdeployProcessFetcher(Map<Region, URI> regionBaseUris,
                                 String username,
                                 String password,
                                 Duration requestTimeout,
                                 Collection<String> userNameFieldCandidates) {
        Objects.requireNonNull(regionBaseUris, "regionBaseUris");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");

        if (regionBaseUris.isEmpty()) {
            throw new IllegalArgumentException("At least one region base URI must be provided");
        }

        this.regionBaseUris = copyAndNormaliseUris(regionBaseUris);
        this.requestTimeout = requestTimeout == null ? DEFAULT_TIMEOUT : requestTimeout;
        this.userNameFieldCandidates = (userNameFieldCandidates == null || userNameFieldCandidates.isEmpty())
                ? DEFAULT_USER_NAME_FIELDS
                : List.copyOf(userNameFieldCandidates);
        this.basicAuthHeaderValue = buildBasicAuthHeader(username, password);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.requestTimeout)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public CompletableFuture<List<ProcessDetails>> fetchProcesses(Map<Region, List<EnvironmentRequest>> requestsByRegion) {
        Objects.requireNonNull(requestsByRegion, "requestsByRegion");
        if (requestsByRegion.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        List<CompletableFuture<ProcessDetails>> futures = new ArrayList<>();
        requestsByRegion.forEach((region, requests) -> {
            if (requests == null || requests.isEmpty()) {
                return;
            }
            requests.forEach(request -> futures.add(fetchProcessDetails(region, request)));
        });

        if (futures.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        CompletableFuture<Void> allDone = CompletableFuture
                .allOf(futures.toArray(CompletableFuture[]::new));

        return allDone.thenApply(ignored ->
                futures.stream()
                        .map(CompletableFuture::join)
                        .collect(Collectors.toList()));
    }

    public CompletableFuture<ProcessDetails> fetchProcessDetails(Region region, EnvironmentRequest request) {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(request, "request");

        URI baseUri = regionBaseUris.get(region);
        if (baseUri == null) {
            throw new IllegalArgumentException("Region " + region + " is not configured");
        }

        URI latestInventoryUri = buildUri(baseUri,
                LATEST_DESIRED_INVENTORY_PATH_TEMPLATE.formatted(request.environmentId()));

        HttpRequest latestInventoryRequest = HttpRequest.newBuilder(latestInventoryUri)
                .timeout(requestTimeout)
                .header("Authorization", basicAuthHeaderValue)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        return httpClient.sendAsync(latestInventoryRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> ensureSuccess(response, latestInventoryUri))
                .thenApply(this::parseJson)
                .thenCompose(latestInventory -> {
                    String requestId = extractApplicationProcessRequestId(latestInventory, request.matchCriteria())
                            .orElseThrow(() -> new IllegalStateException(
                                    "Unable to locate applicationProcessRequestId in latestDesiredInventory response"));

                    URI processRequestUri = buildUri(baseUri,
                            APPLICATION_PROCESS_REQUEST_PATH_TEMPLATE.formatted(requestId));

                    HttpRequest processRequest = HttpRequest.newBuilder(processRequestUri)
                            .timeout(requestTimeout)
                            .header("Authorization", basicAuthHeaderValue)
                            .header("Accept", "application/json")
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build();

                    return httpClient.sendAsync(processRequest, HttpResponse.BodyHandlers.ofString())
                            .thenApply(response -> ensureSuccess(response, processRequestUri))
                            .thenApply(this::parseJson)
                            .thenApply(processJson -> {
                                String userName = extractUserName(processJson)
                                        .orElse(null);
                                return new ProcessDetails(region,
                                        request.environmentId(),
                                        requestId,
                                        userName,
                                        latestInventory,
                                        processJson);
                            });
                });
    }

    private Map<Region, URI> copyAndNormaliseUris(Map<Region, URI> source) {
        Map<Region, URI> normalised = new EnumMap<>(Region.class);
        source.forEach((region, uri) -> {
            Objects.requireNonNull(region, "region");
            Objects.requireNonNull(uri, () -> "Base URI is null for region " + region);
            String raw = uri.toString();
            String adjusted = raw.endsWith("/") ? raw : raw + "/";
            normalised.put(region, URI.create(adjusted));
        });
        return Map.copyOf(normalised);
    }

    private String ensureSuccess(HttpResponse<String> response, URI uri) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return response.body();
        }
        throw new IllegalStateException("Request to " + uri + " failed with status " + status);
    }

    private JsonNode parseJson(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse JSON response", e);
        }
    }

    private Optional<String> extractApplicationProcessRequestId(JsonNode root, Map<String, String> matchCriteria) {
        Map<String, String> criteria = (matchCriteria == null || matchCriteria.isEmpty())
                ? Map.of()
                : Map.copyOf(matchCriteria);

        Optional<JsonNode> withCriteria = findNodeByCriteria(root, criteria);
        if (withCriteria.isPresent()) {
            JsonNode candidate = withCriteria.get().get("applicationProcessRequestId");
            if (candidate != null && candidate.isValueNode()) {
                String value = candidate.asText();
                if (!value.isBlank()) {
                    return Optional.of(value);
                }
            }
        }

        return findFirstValue(root, Set.of("applicationProcessRequestId"));
    }

    private Optional<String> extractUserName(JsonNode root) {
        return findFirstValue(root, Set.copyOf(userNameFieldCandidates));
    }

    private Optional<JsonNode> findNodeByCriteria(JsonNode node, Map<String, String> criteria) {
        if (node == null) {
            return Optional.empty();
        }

        if (node.isObject()) {
            boolean matches = criteria.entrySet().stream()
                    .allMatch(entry -> {
                        JsonNode valueNode = node.get(entry.getKey());
                        return valueNode != null
                                && valueNode.isValueNode()
                                && entry.getValue().equals(valueNode.asText());
                    });

            if (matches && node.has("applicationProcessRequestId")) {
                return Optional.of(node);
            }

            for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
                Optional<JsonNode> childResult = findNodeByCriteria(it.next(), criteria);
                if (childResult.isPresent()) {
                    return childResult;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                Optional<JsonNode> childResult = findNodeByCriteria(child, criteria);
                if (childResult.isPresent()) {
                    return childResult;
                }
            }
        }

        return Optional.empty();
    }

    private Optional<String> findFirstValue(JsonNode node, Set<String> fieldNames) {
        if (node == null || fieldNames.isEmpty()) {
            return Optional.empty();
        }

        if (node.isObject()) {
            for (String field : fieldNames) {
                JsonNode candidate = node.get(field);
                if (candidate != null && candidate.isValueNode()) {
                    String value = candidate.asText();
                    if (value != null && !value.isBlank()) {
                        return Optional.of(value);
                    }
                }
            }
            for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
                Optional<String> childValue = findFirstValue(it.next(), fieldNames);
                if (childValue.isPresent()) {
                    return childValue;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                Optional<String> childValue = findFirstValue(child, fieldNames);
                if (childValue.isPresent()) {
                    return childValue;
                }
            }
        }

        return Optional.empty();
    }

    private URI buildUri(URI baseUri, String path) {
        String normalisedPath = path.startsWith("/") ? path.substring(1) : path;
        return baseUri.resolve(normalisedPath);
    }

    private String buildBasicAuthHeader(String username, String password) {
        String credentials = username + ":" + password;
        String encoded = java.util.Base64.getEncoder()
                .encodeToString(credentials.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    public enum Region {
        ASIA,
        EMEA,
        INDIA,
        NAM
    }

    public record EnvironmentRequest(String environmentId, Map<String, String> matchCriteria) {
        public EnvironmentRequest {
            Objects.requireNonNull(environmentId, "environmentId");
            matchCriteria = matchCriteria == null ? Map.of() : Map.copyOf(matchCriteria);
        }
    }

    public record ProcessDetails(Region region,
                                 String environmentId,
                                 String applicationProcessRequestId,
                                 String userName,
                                 JsonNode latestDesiredInventory,
                                 JsonNode applicationProcessRequest) {
    }
}