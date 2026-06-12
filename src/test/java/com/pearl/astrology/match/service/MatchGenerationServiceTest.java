package com.pearl.astrology.match.service;

import com.pearl.astrology.match.batch.CandidateFetchProcessor;
import com.pearl.astrology.match.batch.CompatibilityScoringProcessor;
import com.pearl.astrology.match.batch.UserCandidatePool;
import com.pearl.astrology.match.entity.DailyMatchQueue;
import com.pearl.astrology.match.entity.Match;
import com.pearl.astrology.match.entity.UserProfile;
import com.pearl.astrology.match.repository.DailyMatchQueueRepository;
import com.pearl.astrology.match.repository.MatchRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class MatchGenerationServiceTest {

    @Mock
    private CandidateFetchProcessor candidateFetchProcessor;

    @Mock
    private CompatibilityScoringProcessor compatibilityScoringProcessor;

    @Mock
    private MatchRepository matchRepository;

    @Mock
    private DailyMatchQueueRepository queueRepository;

    @InjectMocks
    private MatchGenerationService matchGenerationService;

    @Test
    public void testGenerateMatchesAsync_successfulGeneration() throws Exception {
        String userId = "user_abc";

        // Setup candidate pool
        UserProfile source = new UserProfile();
        source.setId(userId);
        UserProfile candidate = new UserProfile();
        candidate.setId("candidate_1");

        UserCandidatePool pool = UserCandidatePool.builder()
                .userId(userId)
                .sourceProfile(source)
                .candidates(List.of(candidate))
                .build();

        when(candidateFetchProcessor.process(any(DailyMatchQueue.class))).thenReturn(pool);

        // Setup scoring result
        Match match = Match.builder()
                .userId(userId)
                .candidateId("candidate_1")
                .score(85.0)
                .matchTimestamp(LocalDateTime.now())
                .viewed(false)
                .build();

        when(compatibilityScoringProcessor.process(pool)).thenReturn(com.pearl.astrology.match.batch.UserMatchResult.builder().userId(userId).matches(List.of(match)).build());

        // Queue entry exists
        when(queueRepository.existsByUserIdAndQueueDate(userId, LocalDate.now())).thenReturn(true);
        DailyMatchQueue queueEntry = DailyMatchQueue.builder()
                .userId(userId)
                .queueDate(LocalDate.now())
                .processed(false)
                .build();
        when(queueRepository.findByUserIdInAndQueueDateAndProcessedFalse(List.of(userId), LocalDate.now()))
                .thenReturn(List.of(queueEntry));

        // Execute
        matchGenerationService.generateMatchesAsync(userId);

        // Verify
        verify(matchRepository).deleteByUserId(userId);
        verify(matchRepository).saveAll(List.of(match));
        verify(queueRepository).saveAll(argThat(entries -> {
            List<DailyMatchQueue> list = (List<DailyMatchQueue>) entries;
            return list.size() == 1 && list.get(0).isProcessed();
        }));
    }

    @Test
    public void testGenerateMatchesAsync_noCandidatesFound() throws Exception {
        String userId = "user_no_profile";

        // CandidateFetchProcessor returns null (user not found or no prefs)
        when(candidateFetchProcessor.process(any(DailyMatchQueue.class))).thenReturn(null);

        // Execute
        matchGenerationService.generateMatchesAsync(userId);

        // Verify no matches saved and no queue updates
        verify(matchRepository, never()).deleteByUserId(any());
        verify(matchRepository, never()).saveAll(any());
        verify(queueRepository, never()).saveAll(any());
    }

    @Test
    public void testGenerateMatchesAsync_scoringReturnsEmpty() throws Exception {
        String userId = "user_low_score";

        UserProfile source = new UserProfile();
        source.setId(userId);
        UserCandidatePool pool = UserCandidatePool.builder()
                .userId(userId)
                .sourceProfile(source)
                .candidates(List.of(new UserProfile()))
                .build();

        when(candidateFetchProcessor.process(any(DailyMatchQueue.class))).thenReturn(pool);
        when(compatibilityScoringProcessor.process(pool)).thenReturn(com.pearl.astrology.match.batch.UserMatchResult.builder().userId(userId).matches(Collections.emptyList()).build());

        // Execute
        matchGenerationService.generateMatchesAsync(userId);

        // Verify no writes
        verify(matchRepository, never()).deleteByUserId(any());
        verify(matchRepository, never()).saveAll(any());
    }

    @Test
    public void testGenerateMatchesAsync_exceptionDoesNotPropagate() throws Exception {
        String userId = "user_error";

        // Simulate a MongoDB failure during candidate fetch
        when(candidateFetchProcessor.process(any(DailyMatchQueue.class)))
                .thenThrow(new RuntimeException("MongoDB connection timeout"));

        // Execute — should NOT throw
        matchGenerationService.generateMatchesAsync(userId);

        // Verify no writes attempted
        verify(matchRepository, never()).deleteByUserId(any());
        verify(matchRepository, never()).saveAll(any());
    }

    @Test
    public void testGenerateMatchesAsync_noQueueEntryStillSavesMatches() throws Exception {
        String userId = "user_new";

        UserProfile source = new UserProfile();
        source.setId(userId);
        UserProfile candidate = new UserProfile();
        candidate.setId("cand_1");

        UserCandidatePool pool = UserCandidatePool.builder()
                .userId(userId)
                .sourceProfile(source)
                .candidates(List.of(candidate))
                .build();

        when(candidateFetchProcessor.process(any(DailyMatchQueue.class))).thenReturn(pool);

        Match match = Match.builder()
                .userId(userId)
                .candidateId("cand_1")
                .score(72.0)
                .matchTimestamp(LocalDateTime.now())
                .viewed(false)
                .build();

        when(compatibilityScoringProcessor.process(pool)).thenReturn(com.pearl.astrology.match.batch.UserMatchResult.builder().userId(userId).matches(List.of(match)).build());

        // No queue entry exists yet
        when(queueRepository.existsByUserIdAndQueueDate(userId, LocalDate.now())).thenReturn(false);

        // Execute
        matchGenerationService.generateMatchesAsync(userId);

        // Matches should still be saved
        verify(matchRepository).deleteByUserId(userId);
        verify(matchRepository).saveAll(List.of(match));

        // Queue should NOT be updated since entry didn't exist
        verify(queueRepository, never()).findByUserIdInAndQueueDateAndProcessedFalse(any(), any());
        verify(queueRepository, never()).saveAll(any());
    }
}
