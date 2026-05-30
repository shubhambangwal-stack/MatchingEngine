package com.pearl.astrology.match.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pearl.astrology.match.entity.DailyMatchQueue;
import com.pearl.astrology.match.repository.DailyMatchQueueRepository;
import com.pearl.astrology.match.service.MatchGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserCreatedConsumer {

    private final DailyMatchQueueRepository queueRepository;
    private final ObjectMapper objectMapper;
    private final MatchGenerationService matchGenerationService;

    @KafkaListener(
            topics = "user-created",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeUserCreated(String message) {
        log.info("Received user-created event: {}", message);

        try {
            // Handle double-stringified JSON from Node.js REST proxy
            // Node.js: JSON.stringify(obj) → '{"_id":"..."}' (a string)
            // REST proxy wraps again → '"{"_id":"..."}"' (string-in-string)
            String json = message;
            while (json.startsWith("\"") && json.endsWith("\"")) {
                json = objectMapper.readValue(json, String.class);
            }
            Map<String, Object> payload = objectMapper.readValue(json, Map.class);

            // Priority: _id (Node.js), userId (Legacy), id (Generic)
            String userId = null;
            if (payload.containsKey("_id"))
                userId = payload.get("_id").toString();
            else if (payload.containsKey("userId"))
                userId = payload.get("userId").toString();
            else if (payload.containsKey("id"))
                userId = payload.get("id").toString();

            if (userId == null) {
                log.error("No valid ID found in user-created event payload: {}", payload);
                throw new IllegalArgumentException("No valid ID found in user-created event payload: " + payload);
            }

            // Avoid duplicate entries for the same day
            boolean exists = queueRepository.existsByUserIdAndQueueDate(userId, LocalDate.now());

            if (!exists) {
                DailyMatchQueue queueItem = DailyMatchQueue.builder()
                        .userId(userId)
                        .queueDate(LocalDate.now())
                        .processed(false)
                        .build();

                queueRepository.save(queueItem);
                log.info("User {} added to daily match queue", userId);
            } else {
                log.warn("User {} already in queue for today", userId);
            }

            // Trigger real-time match generation asynchronously.
            // This runs in a separate thread pool so the Kafka consumer
            // thread is not blocked. Failure here is non-fatal — the
            // daily batch job acts as a fallback.
            matchGenerationService.generateMatchesAsync(userId);
            log.info("Real-time match generation triggered for user {}", userId);

        } catch (Exception e) {
            log.error("Failed to process user-created event: {}", message, e);
            throw new RuntimeException("Error processing user-created event, triggering retry/DLQ", e);
        }
    }
}

