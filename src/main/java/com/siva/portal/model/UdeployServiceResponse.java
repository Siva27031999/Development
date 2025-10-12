package com.siva.portal.model;

import lombok.Data;

import java.io.Serializable;
import java.util.Optional;

@Data
public class UdeployServiceResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    private Optional<String> serviceVersion = Optional.empty();
    private Optional<String> modifiedBy = Optional.empty();
}
