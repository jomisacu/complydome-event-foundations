package com.complydome.event_foundations;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type",
        visible = true
)
public interface DomainEventInterface {
    String getType();

    Instant getOccurredOn();

    String getEventId();
}
