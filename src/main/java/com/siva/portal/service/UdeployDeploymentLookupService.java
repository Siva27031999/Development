package com.siva.portal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.siva.portal.model.UdeployProperties;
import com.siva.portal.model.UdeployServiceResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Facade for the UDeploy APIs used by the portal.
 *
 * It caches the expensive inventory lookup per region and resolves the deployment requester
 * (user name) for a given pipeline by chaining inventory and application process request calls.
 */
@Service
@Slf4j
public class UdeployDeploymentLookupService {

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
     * Resolves deployment details for the given region and pipeline.
     * On any error (network, parsing, missing data), returns a response
     * with empty fields rather than throwing.
     */
    public UdeployServiceResponse resolveDeploymentRequester(String regionName, String pipelineName) {
        final String region;
        final String pipelineKey;
        try {
            region = normaliseRegion(regionName);
            pipelineKey = normalisePipelineKey(pipelineName);
        } catch (IllegalArgumentException ex) {
            log.warn("Cannot resolve deployment requester: {}", ex.getMessage());
            return new UdeployServiceResponse();
        }

        Map<String, InventoryEntry> inventory = obtainInventory(region);
        if (inventory.isEmpty()) {
            return new UdeployServiceResponse();
        }

        InventoryEntry entry = inventory.entrySet().stream()
                .filter(e -> e.getKey().toLowerCase(Locale.ROOT).contains(pipelineKey.toLowerCase(Locale.ROOT)))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        if (entry == null) {
            log.info("Pipeline {} is not present in cached UDeploy inventory for region {}", pipelineName, region);
            return new UdeployServiceResponse();
        }

        return fetchUdeployDetails(entry.applicationProcessRequestId(), pipelineName);
    }

    public void clearCache() {
        cachedInventories.clear();
        log.info("UDeploy inventory cache cleared for all regions");
    }

    public void clearCache(String regionName) {
        try {
            String region = normaliseRegion(regionName);
            cachedInventories.remove(region);
            log.info("UDeploy inventory cache cleared for region {}", region);
        } catch (IllegalArgumentException ignored) {
            log.warn("Attempted to clear cache for invalid region '{}'", regionName);
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
            log.error("No environment id configured for region {}", regionKey);
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

    private UdeployServiceResponse fetchUdeployDetails(String applicationProcessRequestId, String pipelineName) {
        UdeployServiceResponse response = new UdeployServiceResponse();
        if (applicationProcessRequestId == null || applicationProcessRequestId.isBlank()) {
            log.warn("Empty application process request id supplied");
            return response;
        }
        Optional<String> payload = executeGet(
                PROCESS_REQUEST_PATH_TEMPLATE.formatted(applicationProcessRequestId.trim()),
                "application process request " + applicationProcessRequestId);

        if (payload.isEmpty()) {
            return response;
        }
        try {
            findVersionDetails(response, objectMapper.readTree(payload.get()), pipelineName);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse UDeploy application process payload", e);
            // Return empty response on parse failure
            return response;
        }
        return response;
    }

    private Optional<String> executeGet(String path, String context) {
        if (basicAuthHeader == null) {
            log.error("UDeploy credentials are not configured");
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
            log.error("UDeploy responded with status {} while fetching {}", response.statusCode(), context);
        } catch (IOException ex) {
            log.error("I/O error while fetching {}", context, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while fetching {}", context, ex);
        }
        return Optional.empty();
    }

    private Map<String, InventoryEntry> parseInventory(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (!root.isArray()) {
                log.error("Unexpected payload format for inventory response");
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
            log.error("Failed to parse UDeploy inventory payload", ex);
            return Collections.emptyMap();
        }
    }

    private void parseUserName(UdeployServiceResponse udeployServiceResponse, JsonNode root) {
        try {
            String value = readValue(root.get("userName"));
            if (value == null) {
                // Fallbacks to improve resilience without changing existing behavior
                value = firstNonNull(
                        readValue(root.get("requestedBy")),
                        readValue(root.get("user")),
                        readValue(root.get("modifiedBy")),
                        readValue(root.get("lastModifiedBy"))
                );
            }
            udeployServiceResponse.setModifiedBy(Optional.ofNullable(value));
        } catch (Exception ex) {
            log.error("Failed to parse UDeploy application process payload", ex);
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

    public void findVersionDetails(UdeployServiceResponse udeployServiceResponse, JsonNode root, String pipelineName) {
        if (root == null || root.isNull()) {
            return;
        }
        parseVersionName(udeployServiceResponse, root, pipelineName);
        parseUserName(udeployServiceResponse, root);
    }

    private boolean parseVersionName(UdeployServiceResponse udeployServiceResponse, JsonNode currentNode, String pipelineName) {
        if (currentNode == null || currentNode.isNull()) {
            return false;
        }

        if (currentNode.isObject() && currentNode.has("version")) {
            JsonNode versionNode = currentNode.get("version");
            if (versionNode.isObject() && versionNode.has("description") && versionNode.has("name")) {
                String description = readValue(versionNode.get("description"));
                String versionName = readValue(versionNode.get("name"));

                if (description != null && pipelineName != null) {
                    String d = description.toLowerCase(Locale.ROOT);
                    String p = pipelineName.toLowerCase(Locale.ROOT);
                    if (d.contains(p)) {
                        udeployServiceResponse.setServiceVersion(Optional.ofNullable(versionName));
                        return true;
                    }
                }
            }
        }

        if (currentNode.isObject()) {
            Iterator<String> fieldNames = currentNode.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                if (parseVersionName(udeployServiceResponse, currentNode.get(fieldName), pipelineName)) {
                    return true;
                }
            }
        } else if (currentNode.isArray()) {
            for (JsonNode element : currentNode) {
                if (parseVersionName(udeployServiceResponse, element, pipelineName)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static String firstNonNull(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
