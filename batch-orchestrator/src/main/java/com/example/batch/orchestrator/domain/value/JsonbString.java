package com.example.batch.orchestrator.domain.value;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Objects;

public final class JsonbString {

    private final String value;

    @JsonCreator
    public JsonbString(String value) {
        this.value = value;
    }

    public static JsonbString of(String value) {
        return value == null ? null : new JsonbString(value);
    }

    @JsonRawValue
    @JsonValue
    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof JsonbString that)) {
            return false;
        }
        return Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
