package com.example.releve_bancaire.releve_bancaire.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * DTO pour la modification en lot de transactions
 */
@Data
public class BulkUpdateRequest {
    private List<Long> ids;
    private Map<String, Object> updates;
}
