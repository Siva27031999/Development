package com.siva.portal.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServiceEndpoint {
    private String id;
    private String serviceName;
    private boolean enabled;
    private String hostName;
    private HealthCheck healthCheck;
    private VersionCheck versionCheck;
    private String serviceOwners;
    private String region;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HealthCheck {
        private String path;
        private String method;
        private JsonNode payload;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VersionCheck {
        private String path;
        private String method;
        private JsonNode payload;
    }
}
