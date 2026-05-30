package com.pearl.astrology.match.service;

import com.pearl.astrology.match.batch.CandidateFetchProcessor;
import com.pearl.astrology.match.batch.CompatibilityScoringProcessor;
import com.pearl.astrology.match.entity.DailyMatchQueue;
import com.pearl.astrology.match.entity.Match;
import com.pearl.astrology.match.batch.UserCandidatePool;
import com.pearl.astrology.match.repository.DailyMatchQueueRepository;
import com.pearl.astrology.match.repository.MatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * Provides real-time matchmaking for a single user by orchestrating the same
 * processors used in the Spring Batch job.
 *
 * Called immediately after a user registration (user-created) or profile
 * completion (profile-completed) Kafka event is received, so that matches
 * are ready by the time the user opens the dashboard.
 *
 * Failure here is non-fatal: we log the error and let the daily batch job act
 * as a fallback so that the Kafka consumer is never blocked.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MatchGenerationService {

    private final CandidateFetchProcessor candidateFetchProcessor;
    private final CompatibilityScoringProcessor compatibilityScoringProcessor;
    private final MatchRepository matchRepository;
    private final DailyMatchQueueRepository queueRepository;

    /**
     * Generates and persists matches for the given user asynchronously.
     * Runs in a separate thread so that the Kafka consumer thread is never
     * blocked and can immediately acknowledge the message.
     *
     * @param userId the MongoDB ObjectId string of the newly registered user
     */
    @Async("matchGenerationExecutor")
    public void generateMatchesAsync(String userId) {
        log.info("[RealTime] Starting match generation for user {}", userId);

        try {
            // 1. Build a synthetic DailyMatchQueue entry so we can reuse the existing processor
            DailyMatchQueue queueItem = DailyMatchQueue.builder()
                    .userId(userId)
                    .queueDate(LocalDate.now())
                    .processed(false)
                    .build();

            // 2. Fetch candidates using the existing processor (returns null if no profile/prefs)
            UserCandidatePool pool = candidateFetchProcessor.process(queueItem);
            if (pool == null) {
                log.warn("[RealTime] No candidates found for user {} - skipping match generation. " +
                         "Profile may be incomplete; daily batch job will retry.", userId);
                return;
            }

            // 3. Score candidates using the existing scoring processor
            List<Match> matches = compatibilityScoringProcessor.process(pool);
            if (matches == null || matches.isEmpty()) {
                log.warn("[RealTime] Scoring produced no matches for user {}. " +
                         "Daily batch job will retry.", userId);
                return;
            }

            // 4. Replace old matches and persist new ones atomically
            matchRepository.deleteByUserId(userId);
            matchRepository.saveAll(matches);

            // 5. Mark the queue entry as processed so the daily batch skips this user
            boolean alreadyQueued = queueRepository.existsByUserIdAndQueueDate(userId, LocalDate.now());
            if (alreadyQueued) {
                List<DailyMatchQueue> entries = queueRepository
                        .findByUserIdInAndQueueDateAndProcessedFalse(List.of(userId), LocalDate.now());
                entries.forEach(e -> e.setProcessed(true));
                queueRepository.saveAll(entries);
            }

            log.info("[RealTime] Successfully generated {} matches for user {}", matches.size(), userId);

        } catch (Exception e) {
            // Non-fatal: log and let the daily batch job act as a fallback
            log.error("[RealTime] Failed to generate real-time matches for user {}. " +
                      "Daily batch job will pick this user up as fallback.", userId, e);
        }
    }
}
