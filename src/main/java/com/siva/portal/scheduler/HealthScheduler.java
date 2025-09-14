package com.siva.portal.scheduler;

import com.siva.portal.service.HealthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HealthScheduler {

    private HealthService healthService;

    public HealthScheduler(HealthService healthService) {
        this.healthService = healthService;
    }

    @Scheduled(fixedDelay = 300000) // Runs every 5 minutes
    public void run() {
        healthService.updateServiceStatusAndVersion();
    }
}
