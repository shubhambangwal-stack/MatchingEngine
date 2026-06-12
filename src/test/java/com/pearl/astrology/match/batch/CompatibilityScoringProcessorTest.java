package com.pearl.astrology.match.batch;

import com.pearl.astrology.match.entity.Match;
import com.pearl.astrology.match.entity.UserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CompatibilityScoringProcessorTest {

    private CompatibilityScoringProcessor processor;

    @BeforeEach
    public void setUp() {
        processor = new CompatibilityScoringProcessor();
    }

    @Test
    public void testScoringWithNullValuesDoesNotThrowException() {
        UserProfile source = new UserProfile();
        source.setId("user_1");
        source.setGender("Male");
        source.setSubscriptionTier("FREE");

        UserProfile candidate = new UserProfile();
        candidate.setId("candidate_1");
        candidate.setGender("Female");

        UserCandidatePool pool = UserCandidatePool.builder()
                .userId(source.getId())
                .sourceProfile(source)
                .candidates(Collections.singletonList(candidate))
                .build();

        List<Match> matches = processor.process(pool).getMatches();
        assertNotNull(matches);
        assertEquals(1, matches.size());
        
        Match m = matches.get(0);
        assertEquals("user_1", m.getUserId());
        assertEquals("candidate_1", m.getCandidateId());
        assertTrue(m.getScore() > 0);
    }

    @Test
    public void testPerfectMatchScoresHighly() {
        UserProfile source = createBaseProfile("user_1", "Male", "GOLD");
        UserProfile candidate = createBaseProfile("candidate_1", "Female", "FREE");

        source.setValues(Arrays.asList("Honesty", "Family", "Adventure"));
        candidate.setValues(Arrays.asList("Honesty", "Family", "Adventure"));

        source.setHobbies(Arrays.asList("Reading", "Traveling"));
        candidate.setHobbies(Arrays.asList("Reading", "Traveling"));

        source.setFamilyStatus("Middle Class");
        candidate.setFamilyStatus("Middle Class");

        source.setFamilyType("Nuclear");
        candidate.setFamilyType("Nuclear");

        source.setFamilyValues("Moderate");
        candidate.setFamilyValues("Moderate");

        source.setHighestQualification("B.Tech");
        candidate.setHighestQualification("B.Tech");

        source.setAnnualIncome("10-15 Lakhs");
        candidate.setAnnualIncome("10-15 Lakhs");

        source.setProfession("Software Engineer");
        candidate.setProfession("Software Engineer");

        UserProfile.Horoscope srcHoroscope = new UserProfile.Horoscope();
        srcHoroscope.setManglik("No");
        srcHoroscope.setGotra("Kashyap");
        srcHoroscope.setCityOfBirth("Delhi");
        source.setHoroscope(srcHoroscope);

        UserProfile.Horoscope canHoroscope = new UserProfile.Horoscope();
        canHoroscope.setManglik("No");
        canHoroscope.setGotra("Vats"); // Different gotra = compatible
        canHoroscope.setCityOfBirth("Delhi");
        candidate.setHoroscope(canHoroscope);

        UserCandidatePool pool = UserCandidatePool.builder()
                .userId(source.getId())
                .sourceProfile(source)
                .candidates(Collections.singletonList(candidate))
                .build();

        List<Match> matches = processor.process(pool).getMatches();
        assertNotNull(matches);
        assertEquals(1, matches.size());
        
        Match m = matches.get(0);
        assertTrue(m.getScore() >= 90.0, "Perfect compatibility should score near 100, got: " + m.getScore());
    }

    @Test
    public void testPremiumVsFreeTierThresholdsAndLimits() {
        UserProfile freeUser = createBaseProfile("free_user", "Male", "FREE");
        UserProfile premiumUser = createBaseProfile("premium_user", "Male", "GOLD");

        List<UserProfile> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UserProfile candidate = createBaseProfile("candidate_" + i, "Female", "FREE");
            candidates.add(candidate);
        }

        UserCandidatePool freePool = UserCandidatePool.builder()
                .userId(freeUser.getId())
                .sourceProfile(freeUser)
                .candidates(candidates)
                .build();

        List<Match> freeMatches = processor.process(freePool).getMatches();
        assertNotNull(freeMatches);
        assertEquals(10, freeMatches.size(), "Free user should be limited to 10 matches");

        UserCandidatePool premiumPool = UserCandidatePool.builder()
                .userId(premiumUser.getId())
                .sourceProfile(premiumUser)
                .candidates(candidates)
                .build();

        List<Match> premiumMatches = processor.process(premiumPool).getMatches();
        assertNotNull(premiumMatches);
        assertEquals(20, premiumMatches.size(), "Premium user should get all 20 matches");
    }

    private UserProfile createBaseProfile(String id, String gender, String tier) {
        UserProfile profile = new UserProfile();
        profile.setId(id);
        profile.setGender(gender);
        profile.setSubscriptionTier(tier);
        return profile;
    }
}
