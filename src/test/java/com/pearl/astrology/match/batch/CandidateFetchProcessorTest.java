package com.pearl.astrology.match.batch;

import com.pearl.astrology.match.entity.DailyMatchQueue;
import com.pearl.astrology.match.entity.UserProfile;
import com.pearl.astrology.match.repository.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class CandidateFetchProcessorTest {

    @Mock
    private UserProfileRepository userProfileRepository;

    @InjectMocks
    private CandidateFetchProcessor processor;

    @BeforeEach
    public void setUp() {
        ReflectionTestUtils.setField(processor, "maxCandidates", 2000);
    }

    @Test
    public void testProcessReturnsNullIfUserNotFound() {
        DailyMatchQueue queueItem = new DailyMatchQueue();
        queueItem.setUserId("non_existent_id");

        when(userProfileRepository.findById("non_existent_id")).thenReturn(Optional.empty());

        UserCandidatePool pool = processor.process(queueItem);

        assertNull(pool);
        verify(userProfileRepository, never()).findCandidates(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    public void testProcessFetchesDefaultCandidatesIfNoPartnerPreference() {
        DailyMatchQueue queueItem = new DailyMatchQueue();
        queueItem.setUserId("user_id");

        UserProfile profile = new UserProfile();
        profile.setId("user_id");
        profile.setGender("Male");
        profile.setPartnerPreference(null);

        when(userProfileRepository.findById("user_id")).thenReturn(Optional.of(profile));

        UserProfile candidate1 = new UserProfile();
        candidate1.setId("candidate_1");

        when(userProfileRepository.findDefaultCandidates(
                eq("Female"),
                eq("user_id"),
                eq(PageRequest.of(0, 2000))
        )).thenReturn(Collections.singletonList(candidate1));

        UserCandidatePool pool = processor.process(queueItem);

        assertNotNull(pool);
        assertEquals("user_id", pool.getUserId());
        assertEquals(profile, pool.getSourceProfile());
        assertEquals(1, pool.getCandidates().size());
        assertEquals("candidate_1", pool.getCandidates().get(0).getId());
    }

    @Test
    public void testProcessCalculatesDatesAndFetchesCorrectly() {
        DailyMatchQueue queueItem = new DailyMatchQueue();
        queueItem.setUserId("user_id");

        UserProfile profile = new UserProfile();
        profile.setId("user_id");
        profile.setGender("Male");

        UserProfile.PartnerPreference pref = new UserProfile.PartnerPreference();
        UserProfile.AgeRange ageRange = new UserProfile.AgeRange();
        ageRange.setMin(25);
        ageRange.setMax(35);
        pref.setAgeRange(ageRange);
        pref.setReligion(Arrays.asList("Hindu", "Sikh"));
        pref.setMotherTongue(Collections.singletonList("Hindi"));
        pref.setMaritalStatus(Collections.singletonList("Never Married"));
        profile.setPartnerPreference(pref);

        when(userProfileRepository.findById("user_id")).thenReturn(Optional.of(profile));

        UserProfile candidate1 = new UserProfile();
        candidate1.setId("candidate_1");
        candidate1.setGender("Female");

        when(userProfileRepository.findCandidates(
                eq("Female"),
                eq(Arrays.asList("Hindu", "Sikh")),
                eq(Collections.singletonList("Hindi")),
                eq(Collections.singletonList("Never Married")),
                any(LocalDate.class),
                any(LocalDate.class),
                eq("user_id"),
                eq(PageRequest.of(0, 2000))
        )).thenReturn(Collections.singletonList(candidate1));

        UserCandidatePool pool = processor.process(queueItem);

        assertNotNull(pool);
        assertEquals("user_id", pool.getUserId());
        assertEquals(profile, pool.getSourceProfile());
        assertEquals(1, pool.getCandidates().size());
        assertEquals("candidate_1", pool.getCandidates().get(0).getId());
    }
}
