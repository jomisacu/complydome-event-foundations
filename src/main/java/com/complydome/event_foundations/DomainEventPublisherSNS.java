package com.complydome.event_foundations;

import io.awspring.cloud.sns.core.SnsHeaders;
import io.awspring.cloud.sns.core.SnsTemplate;

import java.util.Map;

public class DomainEventPublisherSNS implements DomainEventPublisherInterface {
    private final SnsTemplate snsTemplate;
    private final String topicArn;

    public DomainEventPublisherSNS(SnsTemplate snsTemplate, String topicArn) {
        this.snsTemplate = snsTemplate;
        this.topicArn = topicArn;
    }

    @Override
    public void publish(DomainEventInterface event) {
        snsTemplate.convertAndSend(
                this.topicArn,
                event,
                Map.of(SnsHeaders.NOTIFICATION_SUBJECT_HEADER, event.getType())
        );
    }
}