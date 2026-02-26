package com.example.releve_bancaire.banking_entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class BankStatusConverter implements AttributeConverter<BankStatus, String> {

    @Override
    public String convertToDatabaseColumn(BankStatus attribute) {
        return attribute != null ? attribute.name() : null;
    }

    @Override
    public BankStatus convertToEntityAttribute(String dbData) {
        BankStatus mapped = BankStatus.fromExternalValue(dbData);
        return mapped != null ? mapped : BankStatus.PENDING;
    }
}
