package com.siva.portal.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServiceDetails {
    private String id;
    private String region;
    private String serviceName;
    private String serviceStatus;
    private String currentVersion;
    private String previousVersion;
    private boolean notify;
    private LocalDateTime captureTime;

    public ServiceDetails() {
    }

    public ServiceDetails(String region, String serviceName, String serviceStatus) {
        this.region = region;
        this.serviceName = serviceName;
        this.serviceStatus = serviceStatus;
        this.captureTime = ZonedDateTime.now(ZoneId.of("IST")).toLocalDateTime();
        this.id = generateId(region, serviceName);
    }

    private String generateId(String region, String serviceName) {
        return region + "_" + serviceName;
    }

    private void updateId() {
        this.id = generateId(this.region, this.serviceName);
    }

    public void setRegion(String region) {
        this.region = region;
        updateId();
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
        updateId();
    }
}
