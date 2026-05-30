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

/**
 * Listens for profile-completed events published by the Node.js backend
 * when a user finishes filling out their onboarding profile (partner preferences,
 * horoscope, etc.). Triggers real-time match generation immediately so that
 * matches are ready when the user lands on the dashboard.
 *
 * The daily batch job continues to run as a fallback/reconciliation pass.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProfileCompletedConsumer {

    private final DailyMatchQueueRepository queueRepository;
    private final ObjectMapper objectMapper;
    private final MatchGenerationService matchGenerationService;

    @KafkaListener(
            topics = "profile-completed",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeProfileCompleted(String message) {
        log.info("Received profile-completed event: {}", message);

        try {
            // Handle double-stringified JSON from Node.js REST proxy
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
                log.error("No valid ID found in profile-completed event payload: {}", payload);
                throw new IllegalArgumentException("No valid ID in profile-completed event: " + payload);
            }

            // Ensure the user is in today's queue (may already be there from user-created event)
            boolean exists = queueRepository.existsByUserIdAndQueueDate(userId, LocalDate.now());
            if (!exists) {
                DailyMatchQueue queueItem = DailyMatchQueue.builder()
                        .userId(userId)
                        .queueDate(LocalDate.now())
                        .processed(false)
                        .build();
                queueRepository.save(queueItem);
                log.info("User {} added to daily match queue via profile-completed event", userId);
            }

            // Trigger real-time match generation now that the profile is fully filled in.
            // Previous matches (from user-created) may have been skipped due to missing prefs;
            // this call will now generate them correctly.
            matchGenerationService.generateMatchesAsync(userId);
            log.info("Real-time match generation triggered for user {} after profile completion", userId);

        } catch (Exception e) {
            log.error("Failed to process profile-completed event: {}", message, e);
            throw new RuntimeException("Error processing profile-completed event, triggering retry/DLQ", e);
        }
    }
}
