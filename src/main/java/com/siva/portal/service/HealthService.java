package com.siva.portal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.siva.portal.model.ServiceDetails;
import com.siva.portal.model.ServiceEndpoint;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class HealthService {

    private RestTemplate restTemplate;
    //private ServiceEndpointRepository endpointRepository;

    private final Map<String, ServiceDetails> serviceDetailsMap = new HashMap<>();

    public HealthService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public void updateServiceStatusAndVersion() {
        //endpointRepository.getServiceEndpoint();
        List<ServiceEndpoint> serviceEndpoints = null;
        serviceEndpoints.parallelStream().forEach(endpoint -> {
            try {
                if(endpoint.isEnabled()) {
                    ServiceDetails serviceDetails = checkAndStoreServiceHealth(endpoint);
                    if(serviceDetails.getServiceStatus().equalsIgnoreCase("UP"))
                        checkAndStoreServiceVersion(endpoint, serviceDetails);
                    addServiceDetails(serviceDetails);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private ServiceDetails checkAndStoreServiceHealth(ServiceEndpoint endpoint) {
        String key = endpoint.getRegion() + "_" + endpoint.getServiceName();
        String healthCheckUrl = endpoint.getHostName() + endpoint.getHealthCheck().getPath();
        String serviceStatus = fetchServiceHealth(healthCheckUrl, endpoint.getHealthCheck().getMethod(), endpoint.getHealthCheck().getPayload(), endpoint.getServiceName(), endpoint.getRegion());

        ServiceDetails serviceDetails = serviceDetailsMap.getOrDefault(key, new ServiceDetails());
        serviceDetails.setRegion(endpoint.getRegion());
        serviceDetails.setServiceName(endpoint.getServiceName());
        serviceDetails.setServiceStatus(serviceStatus);
        serviceDetails.setCaptureTime(java.time.ZonedDateTime.now(java.time.ZoneId.of("IST")).toLocalDateTime());

        return serviceDetails;
    }

    private void checkAndStoreServiceVersion(ServiceEndpoint endpoint, ServiceDetails serviceDetails) {
        if(isVersionCheckApplicable(endpoint, serviceDetails)) {
            String versionCheckUrl = endpoint.getHostName() + endpoint.getVersionCheck().getPath();
            String currentVersion = resolveCurrentVersion(serviceDetails);
            String actualVersion = resolveActualVersion(versionCheckUrl, currentVersion);

            serviceDetails.setCurrentVersion(actualVersion);
            updatePreviousVersion(serviceDetails, currentVersion, actualVersion);
        }
    }

    private  boolean isVersionCheckApplicable(ServiceEndpoint endpoint, ServiceDetails serviceDetails) {
        return serviceDetails != null && endpoint.getVersionCheck() != null && StringUtils.isNotEmpty(endpoint.getVersionCheck().getPath());
    }

    private String resolveCurrentVersion(ServiceDetails serviceDetails) {
        String currentVersion = serviceDetails.getCurrentVersion();
        return currentVersion != null && !currentVersion.isEmpty() ? currentVersion : "";
    }

    private String resolveActualVersion(String versionCheckUrl, String currentVersion) {
        String actualVersion = fetchServiceVersion(versionCheckUrl);
        return "ERROR".equalsIgnoreCase(actualVersion) && StringUtils.isNotEmpty(currentVersion) ? currentVersion : actualVersion;
    }

    private void updatePreviousVersion(ServiceDetails serviceDetails, String currentVersion, String actualVersion) {
        if(StringUtils.isEmpty(serviceDetails.getPreviousVersion())) {
            serviceDetails.setPreviousVersion(currentVersion);
        } else {
            String existingPreviousVersion = serviceDetails.getPreviousVersion();
            if(StringUtils.isEmpty(existingPreviousVersion)) {
                serviceDetails.setPreviousVersion(currentVersion);
            } else if(actualVersion != null && !actualVersion.equals(existingPreviousVersion)) {
                serviceDetails.setNotify(true);
            }
        }
    }

    private String fetchServiceHealth(String url, String method, JsonNode payload, String serviceName, String region) {
        ResponseEntity<String> response = null;
        try {
            HttpEntity<String> entity = createHttpEntity(method, payload);
            response = restTemplate.exchange(url, HttpMethod.valueOf(method), entity, String.class);
            return response.getStatusCode().is2xxSuccessful() ? "UP" : "DOWN";
        } catch (Exception e) {
            if("DOCGEN".equalsIgnoreCase(serviceName) && StringUtils.containsIgnoreCase(e.getMessage(), "ERR_WHILE_STORE_IMAGE")) {
                checkDocgenError(region, "DOWN");
                return "UP";
            }
            return "DOWN";
        } finally {
             if("DOCGEN".equalsIgnoreCase(serviceName) && response!=null && response.getStatusCode().is2xxSuccessful()) {
                 checkDocgenError(region, "UP");
             }
        }
    }

    private String fetchServiceVersion(String url) {
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            return response.getBody();
        } catch (Exception e) {
            return "ERROR";
        }
    }

    private void checkDocgenError(String region, String serviceStatus) {
        ServiceDetails imagingDetails = new ServiceDetails();
        imagingDetails.setRegion(region);
        imagingDetails.setServiceName("IMAGING");
        imagingDetails.setServiceStatus(serviceStatus);
        imagingDetails.setCaptureTime(java.time.ZonedDateTime.now(java.time.ZoneId.of("IST")).toLocalDateTime());
        addServiceDetails(imagingDetails);
    }

    private HttpEntity<String> createHttpEntity(String method, JsonNode payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if("POST".equalsIgnoreCase(method) && payload != null) {
            return new HttpEntity<>(payload.toPrettyString(), headers);
        }
        return new HttpEntity<>(headers);
    }

    private void addServiceDetails(ServiceDetails serviceDetails) {
        serviceDetailsMap.put(serviceDetails.getId(), serviceDetails);
    }

    public List<ServiceDetails> getServiceDetails() {
        return List.copyOf(serviceDetailsMap.values());
    }

    public void updateServiceField(String region, String serviceName, String fieldName, Object newValue) {
        String key = region + "_" + serviceName;
        ServiceDetails serviceDetails = serviceDetailsMap.get(key);

        if(serviceDetails != null) {
            switch (fieldName.toLowerCase()) {
                case "servicestatus":
                    serviceDetails.setServiceStatus((String) newValue);
                    break;
                case "currentversion":
                    serviceDetails.setCurrentVersion((String) newValue);
                    break;
                case "previousversion":
                    serviceDetails.setPreviousVersion((String) newValue);
                    break;
                case "notify":
                    serviceDetails.setNotify((Boolean) newValue);
                    break;
                default:
                    throw new IllegalArgumentException("Invalid field name: " + serviceDetails);
            }
            serviceDetailsMap.put(key, serviceDetails);
        } else {
            throw new IllegalArgumentException("Service not found for region: " + region + " and serviceName: " + serviceName);
        }
    }
}
