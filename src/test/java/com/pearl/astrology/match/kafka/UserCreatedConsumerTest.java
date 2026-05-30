package com.pearl.astrology.match.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pearl.astrology.match.entity.DailyMatchQueue;
import com.pearl.astrology.match.repository.DailyMatchQueueRepository;
import com.pearl.astrology.match.service.MatchGenerationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class UserCreatedConsumerTest {

    @Mock
    private DailyMatchQueueRepository queueRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private MatchGenerationService matchGenerationService;

    @InjectMocks
    private UserCreatedConsumer consumer;

    @Test
    public void testConsumeUserCreatedSuccessfully() {
        String rawMessage = "{\"_id\":\"user_abc\",\"gender\":\"Male\"}";
        when(queueRepository.existsByUserIdAndQueueDate("user_abc", LocalDate.now())).thenReturn(false);

        consumer.consumeUserCreated(rawMessage);

        verify(queueRepository, times(1)).save(argThat(item -> 
            "user_abc".equals(item.getUserId()) && !item.isProcessed()
        ));
        // Verify real-time match generation was triggered
        verify(matchGenerationService, times(1)).generateMatchesAsync("user_abc");
    }

    @Test
    public void testConsumeDoubleStringifiedJsonSuccessfully() {
        String rawMessage = "\"{\\\"_id\\\":\\\"user_abc\\\",\\\"gender\\\":\\\"Male\\\"}\"";
        when(queueRepository.existsByUserIdAndQueueDate("user_abc", LocalDate.now())).thenReturn(false);

        consumer.consumeUserCreated(rawMessage);

        verify(queueRepository, times(1)).save(argThat(item -> 
            "user_abc".equals(item.getUserId()) && !item.isProcessed()
        ));
        verify(matchGenerationService, times(1)).generateMatchesAsync("user_abc");
    }

    @Test
    public void testConsumeDuplicateDoesNotSaveButStillTriggersMatchGeneration() {
        String rawMessage = "{\"_id\":\"user_abc\",\"gender\":\"Male\"}";
        when(queueRepository.existsByUserIdAndQueueDate("user_abc", LocalDate.now())).thenReturn(true);

        consumer.consumeUserCreated(rawMessage);

        verify(queueRepository, never()).save(any(DailyMatchQueue.class));
        // Even if already queued, match generation should still be triggered
        // (handles the case where user-created fires again after a profile update)
        verify(matchGenerationService, times(1)).generateMatchesAsync("user_abc");
    }

    @Test
    public void testConsumeInvalidJsonThrowsException() {
        String rawMessage = "invalid-json-content";

        assertThrows(RuntimeException.class, () -> {
            consumer.consumeUserCreated(rawMessage);
        });

        verify(queueRepository, never()).save(any(DailyMatchQueue.class));
        verify(matchGenerationService, never()).generateMatchesAsync(any());
    }

    @Test
    public void testConsumeMissingIdThrowsException() {
        String rawMessage = "{\"gender\":\"Male\"}";

        assertThrows(RuntimeException.class, () -> {
            consumer.consumeUserCreated(rawMessage);
        });

        verify(queueRepository, never()).save(any(DailyMatchQueue.class));
        verify(matchGenerationService, never()).generateMatchesAsync(any());
    }
}
