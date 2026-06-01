package com.pearl.astrology.match.batch;

import com.pearl.astrology.match.entity.DailyMatchQueue;
import com.pearl.astrology.match.entity.Match;
import com.pearl.astrology.match.repository.DailyMatchQueueRepository;
import com.pearl.astrology.match.repository.MatchRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.Chunk;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class MatchMongoWriterTest {

    @Mock
    private MatchRepository matchRepository;

    @Mock
    private DailyMatchQueueRepository queueRepository;

    @InjectMocks
    private MatchMongoWriter matchMongoWriter;

    @Test
    public void testWriteEmptyOrNullChunk() {
        matchMongoWriter.write(null);
        matchMongoWriter.write(new Chunk<>());

        verifyNoInteractions(matchRepository);
        verifyNoInteractions(queueRepository);
    }

    @Test
    public void testWriteSuccessfully() {
        Match m1 = Match.builder().userId("user1").candidateId("cand1").score(85.5).build();
        Match m2 = Match.builder().userId("user1").candidateId("cand2").score(72.0).build();
        Match m3 = Match.builder().userId("user2").candidateId("cand3").score(90.0).build();

        List<Match> matchesUser1 = Arrays.asList(m1, m2);
        List<Match> matchesUser2 = Collections.singletonList(m3);

        Chunk<List<Match>> chunk = new Chunk<>(Arrays.asList(matchesUser1, matchesUser2));

        DailyMatchQueue q1 = DailyMatchQueue.builder().userId("user1").processed(false).build();
        DailyMatchQueue q2 = DailyMatchQueue.builder().userId("user2").processed(false).build();
        when(queueRepository.findByUserIdInAndQueueDateAndProcessedFalse(anyList(), eq(LocalDate.now())))
                .thenReturn(Arrays.asList(q1, q2));

        matchMongoWriter.write(chunk);

        // Verify deleteByUserIdIn is called for user1 and user2
        verify(matchRepository, times(1)).deleteByUserIdIn(argThat(list -> 
            list.containsAll(Arrays.asList("user1", "user2")) && list.size() == 2
        ));

        // Verify saveAll matches
        verify(matchRepository, times(1)).saveAll(argThat(iterable -> {
            List<Match> list = new ArrayList<>();
            iterable.forEach(list::add);
            return list.size() == 3 && list.containsAll(Arrays.asList(m1, m2, m3));
        }));

        // Verify daily match queue entries are marked processed and saved
        verify(queueRepository, times(1)).saveAll(argThat(iterable -> {
            List<DailyMatchQueue> list = new ArrayList<>();
            iterable.forEach(list::add);
            return list.size() == 2 && q1.isProcessed() && q2.isProcessed();
        }));
    }
}
