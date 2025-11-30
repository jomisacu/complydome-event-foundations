package com.complydome.event_foundations;

import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

public class DomainEventConsumerSQS {
    private static final Logger log = LoggerFactory.getLogger(DomainEventConsumerSQS.class);
    private final ApplicationEventPublisher eventPublisher;

    public DomainEventConsumerSQS(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @SqsListener("${app.events.queue-name}")
    public void consume(DomainEventInterface event) {
        log.info(">>> Evento recibido de SQS: {} | ID: {}", event.getType(), event.getEventId());

        // Spring busca métodos con la anotación @EventListener
        // y que su parámetro coincida con la clase concreta del evento
        eventPublisher.publishEvent(event);
    }
}
