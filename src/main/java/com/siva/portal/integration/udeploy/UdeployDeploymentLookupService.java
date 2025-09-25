package com.siva.portal.integration.udeploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Facade for the UDeploy APIs used by the portal.
 *
 * It caches the expensive inventory lookup per region and resolves the deployment requester
 * (user name) for a given pipeline by chaining inventory and application process request calls.
 */
@Service
public class UdeployDeploymentLookupService {

    private static final Logger LOGGER = LoggerFactory.getLogger(UdeployDeploymentLookupService.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final String INVENTORY_PATH_TEMPLATE = "rest/deploy/environment/%s/latestDesiredInventory";
    private static final String PROCESS_REQUEST_PATH_TEMPLATE = "rest/deploy/applicationProcessRequest/%s";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI baseUri;
    private final String basicAuthHeader;
    private final Map<String, String> environmentIdsByRegion;
    private final Map<String, Map<String, InventoryEntry>> cachedInventories;

    public UdeployDeploymentLookupService(UdeployProperties properties, ObjectMapper objectMapper) {
        Objects.requireNonNull(properties, "properties");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.baseUri = sanitiseBaseUri(properties.getBaseUrl());
        this.basicAuthHeader = buildBasicAuthHeader(properties.getUsername(), properties.getPassword());
        this.environmentIdsByRegion = normaliseEnvironmentIds(properties.getEnvironmentIds());
        this.cachedInventories = new ConcurrentHashMap<>();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /**
     * Resolves the deployment requester (user name) for the given region and pipeline.
     * Any error that occurs while calling or parsing the UDeploy APIs results in an empty string.
     */
    public String resolveDeploymentRequester(String regionName, String pipelineName) {
        final String region;
        final String pipelineKey;
        try {
            region = normaliseRegion(regionName);
            pipelineKey = normalisePipelineKey(pipelineName);
        } catch (IllegalArgumentException ex) {
            LOGGER.warn("Cannot resolve deployment requester: {}", ex.getMessage());
            return "";
        }

        Map<String, InventoryEntry> inventory = obtainInventory(region);
        if (inventory.isEmpty()) {
            return "";
        }

        InventoryEntry entry = inventory.get(pipelineKey);
        if (entry == null) {
            LOGGER.info("Pipeline {} is not present in cached UDeploy inventory for region {}", pipelineName, region);
            return "";
        }

        return fetchRequesterUserName(entry.applicationProcessRequestId()).orElse("");
    }

    public void clearCache() {
        cachedInventories.clear();
        LOGGER.info("UDeploy inventory cache cleared for all regions");
    }

    public void clearCache(String regionName) {
        try {
            String region = normaliseRegion(regionName);
            cachedInventories.remove(region);
            LOGGER.info("UDeploy inventory cache cleared for region {}", region);
        } catch (IllegalArgumentException ignored) {
            LOGGER.warn("Attempted to clear cache for invalid region '{}'", regionName);
        }
    }

    private Map<String, InventoryEntry> obtainInventory(String regionKey) {
        Map<String, InventoryEntry> cached = cachedInventories.get(regionKey);
        if (cached != null) {
            return cached;
        }

        Map<String, InventoryEntry> loaded = loadAndIndexLatestInventory(regionKey);
        if (!loaded.isEmpty()) {
            cachedInventories.put(regionKey, loaded);
        }
        return loaded;
    }

    private Map<String, InventoryEntry> loadAndIndexLatestInventory(String regionKey) {
        String environmentId = environmentIdsByRegion.get(regionKey);
        if (environmentId == null || environmentId.isBlank()) {
            LOGGER.error("No environment id configured for region {}", regionKey);
            return Collections.emptyMap();
        }

        Optional<String> payload = executeGet(
                INVENTORY_PATH_TEMPLATE.formatted(environmentId.trim()),
                "latest inventory for region " + regionKey);

        if (payload.isEmpty()) {
            return Collections.emptyMap();
        }

        return parseInventory(payload.get());
    }

    private Optional<String> fetchRequesterUserName(String applicationProcessRequestId) {
        if (applicationProcessRequestId == null || applicationProcessRequestId.isBlank()) {
            LOGGER.warn("Empty application process request id supplied");
            return Optional.empty();
        }

        Optional<String> payload = executeGet(
                PROCESS_REQUEST_PATH_TEMPLATE.formatted(applicationProcessRequestId.trim()),
                "application process request " + applicationProcessRequestId);

        if (payload.isEmpty()) {
            return Optional.empty();
        }

        return parseUserName(payload.get());
    }

    private Optional<String> executeGet(String path, String context) {
        if (basicAuthHeader == null) {
            LOGGER.error("UDeploy credentials are not configured");
            return Optional.empty();
        }

        URI uri = baseUri.resolve(path);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .header("Accept", "application/json")
                .header("Authorization", basicAuthHeader)
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Optional.ofNullable(response.body());
            }
            LOGGER.error("UDeploy responded with status {} while fetching {}", response.statusCode(), context);
        } catch (IOException ex) {
            LOGGER.error("I/O error while fetching {}", context, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            LOGGER.error("Interrupted while fetching {}", context, ex);
        }
        return Optional.empty();
    }

    private Map<String, InventoryEntry> parseInventory(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (!root.isArray()) {
                LOGGER.error("Unexpected payload format for inventory response");
                return Collections.emptyMap();
            }

            Map<String, InventoryEntry> entries = new LinkedHashMap<>();
            for (JsonNode node : root) {
                String processId = readValue(node.get("applicationProcessRequestId"));
                String componentName = readValue(node.path("component").get("name"));
                if (processId == null || componentName == null) {
                    continue;
                }
                String pipelineKey = componentName.trim().toLowerCase(Locale.ROOT);
                entries.putIfAbsent(pipelineKey, new InventoryEntry(componentName.trim(), processId.trim()));
            }
            return Collections.unmodifiableMap(entries);
        } catch (IOException ex) {
            LOGGER.error("Failed to parse UDeploy inventory payload", ex);
            return Collections.emptyMap();
        }
    }

    private Optional<String> parseUserName(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            return Optional.ofNullable(readValue(root.get("userName")));
        } catch (IOException ex) {
            LOGGER.error("Failed to parse UDeploy application process payload", ex);
            return Optional.empty();
        }
    }

    private Map<String, String> normaliseEnvironmentIds(Map<String, String> environmentIds) {
        if (environmentIds == null || environmentIds.isEmpty()) {
            return Map.of();
        }
        return environmentIds.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null && !entry.getValue().isBlank())
                .collect(Collectors.toUnmodifiableMap(
                        entry -> normaliseRegion(entry.getKey()),
                        entry -> entry.getValue().trim()));
    }

    private URI sanitiseBaseUri(String baseUrl) {
        String value = (baseUrl == null ? "" : baseUrl.trim());
        if (value.isEmpty()) {
            throw new IllegalArgumentException("UDeploy base URL must not be empty");
        }
        if (!value.endsWith("/")) {
            value = value + "/";
        }
        return URI.create(value);
    }

    private String normaliseRegion(String region) {
        String value = region == null ? "" : region.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Region name must not be empty");
        }
        return value.toUpperCase(Locale.ROOT);
    }

    private String normalisePipelineKey(String pipelineName) {
        String value = pipelineName == null ? "" : pipelineName.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Pipeline name must not be empty");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private String buildBasicAuthHeader(String username, String password) {
        String user = username == null ? "" : username.trim();
        String pass = password == null ? "" : password.trim();
        if (user.isEmpty() || pass.isEmpty()) {
            return null;
        }
        String credentials = user + ":" + pass;
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    private String readValue(JsonNode node) {
        if (node == null || !node.isValueNode()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private record InventoryEntry(String componentName, String applicationProcessRequestId) {
    }
}