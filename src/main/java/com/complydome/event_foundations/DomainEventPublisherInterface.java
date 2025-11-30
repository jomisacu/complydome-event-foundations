package com.complydome.event_foundations;

public interface DomainEventPublisherInterface {
    void publish(DomainEventInterface event);
}
