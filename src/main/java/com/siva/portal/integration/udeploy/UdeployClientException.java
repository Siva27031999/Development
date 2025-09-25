package com.siva.portal.integration.udeploy;

/**
 * Runtime exception thrown when UDeploy calls or processing fail.
 */
public class UdeployClientException extends RuntimeException {

    public UdeployClientException(String message) {
        super(message);
    }

    public UdeployClientException(String message, Throwable cause) {
        super(message, cause);
    }
}