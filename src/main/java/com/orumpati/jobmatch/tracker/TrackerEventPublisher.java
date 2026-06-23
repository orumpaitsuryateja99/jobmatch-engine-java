package com.orumpati.jobmatch.tracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/** Optional Kafka publisher for tracker lifecycle events. Disabled by default so
 * local non-Docker runs do not require a Kafka broker. */
@Service
public class TrackerEventPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String topic;

    public TrackerEventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper,
                                 @Value("${app.kafka.enabled:false}") boolean enabled,
                                 @Value("${app.kafka.tracker-topic:jobmatch.tracker.events}") String topic) {
        this.kafka = kafka;
        this.mapper = mapper;
        this.enabled = enabled;
        this.topic = topic;
    }

    public void publish(String eventType, Application app) {
        if (!enabled || app == null) return;
        try {
            Map<String, Object> payload = Map.of(
                    "eventType", eventType,
                    "occurredAt", Instant.now().toString(),
                    "application", app);
            kafka.send(topic, app.getId(), mapper.writeValueAsString(payload));
        } catch (Exception ignored) {
            // Kafka should never block the single-user tracker workflow.
        }
    }
}
