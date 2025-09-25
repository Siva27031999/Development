package com.siva.portal.integration.udeploy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "portal.udeploy")
public class UdeployProperties {

    private String baseUrl = "https://releasedeployment3.ti.group.net:8443/";
    private String username;
    private String password;
    private Map<String, String> environmentIds = new HashMap<>();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Map<String, String> getEnvironmentIds() {
        return environmentIds;
    }

    public void setEnvironmentIds(Map<String, String> environmentIds) {
        this.environmentIds = environmentIds;
    }
}