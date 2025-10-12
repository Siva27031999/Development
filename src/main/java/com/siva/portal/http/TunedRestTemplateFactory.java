package com.siva.portal.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

// Apache HttpClient 4.x imports (commonly used with Spring Boot 2.x)
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

/**
 * Factory for building a resilient RestTemplate tuned for production usage.
 *
 * Features:
 * - Connection pooling with stale-connection validation and eviction
 * - Tunable connect/read/pool timeouts
 * - Optional basic logging interceptor
 * - Simple retry helper for transient I/O errors/timeouts
 */
public final class TunedRestTemplateFactory {

    private static final Logger log = LoggerFactory.getLogger(TunedRestTemplateFactory.class);

    private TunedRestTemplateFactory() {}

    public static RestTemplate createDefault() {
        return createCustom(
                Duration.ofSeconds(5),     // connect
                Duration.ofSeconds(30),    // read
                Duration.ofSeconds(2),     // connection-request (pool wait)
                200,                       // max total
                50,                        // max per route
                false,                     // bufferRequestBody (false helps large uploads)
                false,                     // enableBasicLogging
                Duration.ofSeconds(30)     // idle eviction
        );
    }

    public static RestTemplate createWithLogging() {
        return createCustom(
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                Duration.ofSeconds(2),
                200,
                50,
                false,
                true,
                Duration.ofSeconds(30)
        );
    }

    public static RestTemplate createCustom(
            Duration connectTimeout,
            Duration readTimeout,
            Duration connectionRequestTimeout,
            int maxTotal,
            int maxPerRoute,
            boolean bufferRequestBody,
            boolean enableBasicLogging,
            Duration idleConnectionEvict
    ) {
        // Connection manager with pooling and stale-connection validation
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(maxTotal);
        cm.setDefaultMaxPerRoute(maxPerRoute);
        cm.setValidateAfterInactivity((int) Duration.ofSeconds(5).toMillis());

        RequestConfig rc = RequestConfig.custom()
                .setConnectTimeout((int) connectTimeout.toMillis())
                .setSocketTimeout((int) readTimeout.toMillis())
                .setConnectionRequestTimeout((int) connectionRequestTimeout.toMillis())
                .setExpectContinueEnabled(false)
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(rc)
                .evictIdleConnections(idleConnectionEvict.toMillis(), TimeUnit.MILLISECONDS)
                .evictExpiredConnections()
                .disableAutomaticRetries() // retries handled explicitly
                .build();

        HttpComponentsClientHttpRequestFactory baseFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
        baseFactory.setConnectTimeout((int) connectTimeout.toMillis());
        baseFactory.setReadTimeout((int) readTimeout.toMillis());
        baseFactory.setBufferRequestBody(bufferRequestBody);

        ClientHttpRequestFactory effectiveFactory = enableBasicLogging
                ? new BufferingClientHttpRequestFactory(baseFactory)
                : baseFactory;

        RestTemplate rt = new RestTemplate(effectiveFactory);
        if (enableBasicLogging) {
            List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>(rt.getInterceptors());
            interceptors.add(new BasicTimingLoggingInterceptor());
            rt.setInterceptors(interceptors);
        }
        return rt;
    }

    /**
     * Simple retry wrapper for transient I/O failures (e.g., read timeouts).
     * Use with care for POST; prefer idempotency keys when possible.
     */
    public static <T> Optional<ResponseEntity<T>> exchangeWithRetry(
            RestTemplate rt,
            String url,
            HttpMethod method,
            HttpEntity<?> entity,
            Class<T> responseType,
            int maxAttempts,
            Duration initialBackoff,
            double backoffMultiplier,
            Object... uriVariables
    ) {
        int attempt = 0;
        Duration backoff = initialBackoff == null ? Duration.ofMillis(250) : initialBackoff;
        double multiplier = backoffMultiplier <= 0 ? 2.0 : backoffMultiplier;

        while (true) {
            attempt++;
            try {
                ResponseEntity<T> resp = rt.exchange(url, method, entity, responseType, uriVariables);
                return Optional.ofNullable(resp);
            } catch (ResourceAccessException ex) {
                if (attempt >= maxAttempts) {
                    log.warn("exchange attempt {} failed: {}", attempt, ex.getMessage());
                    return Optional.empty();
                }
                sleepQuietly(backoff);
                backoff = backoff.multipliedBy((long) Math.max(1, multiplier));
            } catch (RestClientException ex) {
                // Non-I/O client errors: do not retry by default
                log.warn("non-retryable RestTemplate error on attempt {}: {}", attempt, ex.getMessage());
                return Optional.empty();
            }
        }
    }

    private static void sleepQuietly(Duration d) {
        try {
            TimeUnit.MILLISECONDS.sleep(Math.max(1, d.toMillis()));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Minimal timing/status logging without dumping bodies.
     */
    private static class BasicTimingLoggingInterceptor implements ClientHttpRequestInterceptor {
        @Override
        public ClientHttpResponse intercept(org.springframework.http.HttpRequest request, byte[] body,
                                             ClientHttpRequestExecution execution) throws IOException {
            Instant start = Instant.now();
            try {
                ClientHttpResponse response = execution.execute(request, body);
                long ms = Duration.between(start, Instant.now()).toMillis();
                log.info("HTTP {} {} -> {} ({} ms)",
                        request.getMethod(),
                        request.getURI(),
                        safeStatus(response),
                        ms);
                return response;
            } catch (IOException ioe) {
                long ms = Duration.between(start, Instant.now()).toMillis();
                log.warn("HTTP {} {} failed after {} ms: {}",
                        request.getMethod(), request.getURI(), ms, ioe.getMessage());
                throw ioe;
            }
        }

        private String safeStatus(ClientHttpResponse response) {
            try {
                return response.getStatusCode().toString();
            } catch (Exception e) {
                return "(no-status)";
            }
        }
    }
}

