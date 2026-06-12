package com.pearl.astrology.match.batch;

import com.pearl.astrology.match.entity.DailyMatchQueue;
import com.pearl.astrology.match.entity.UserProfile;
import com.pearl.astrology.match.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Fetches candidate profiles from MongoDB using the source user's partnerPreference.
 * Replaces the mock implementation that returned IDs 1-100.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class
CandidateFetchProcessor implements ItemProcessor<DailyMatchQueue, UserCandidatePool> {

    private final UserProfileRepository userProfileRepository;

    @Value("${match.scoring.max-candidates-per-user:2000}")
    private int maxCandidates;

    @Override
    public UserCandidatePool process(DailyMatchQueue item) {
        log.info("Fetching candidates for user {}", item.getUserId());

        // 1. Load the source user's profile from MongoDB
        Optional<UserProfile> sourceOpt = userProfileRepository.findById(item.getUserId());
        if (sourceOpt.isEmpty()) {
            log.warn("User {} not found in MongoDB, skipping", item.getUserId());
            return null; // Returning null skips this item in Spring Batch
        }

        UserProfile source = sourceOpt.get();
        UserProfile.PartnerPreference pref = source.getPartnerPreference();

        if (pref == null) {
            log.warn("User {} has no partnerPreference set, skipping", item.getUserId());
            return null;
        }

        // 2. Determine the target gender (opposite of source)
        String targetGender = "Male".equalsIgnoreCase(source.getGender()) ? "Female" : "Male";

        // 3. Compute DOB range from ageRange preference
        //    e.g., ageRange { min: 24, max: 30 } → dob between (today - 30 years) and (today - 24 years)
        LocalDate today = LocalDate.now();
        LocalDate dobFrom = today.minusYears(pref.getAgeRange() != null ? pref.getAgeRange().getMax() : 40);
        LocalDate dobTo = today.minusYears(pref.getAgeRange() != null ? pref.getAgeRange().getMin() : 18);

        // 4. Get filter lists (default to broad if not set)
        List<String> religions = pref.getReligion() != null ? pref.getReligion() : Collections.emptyList();
        List<String> motherTongues = pref.getMotherTongue() != null ? pref.getMotherTongue() : Collections.emptyList();
        List<String> maritalStatuses = pref.getMaritalStatus() != null ? pref.getMaritalStatus() : Collections.emptyList();

        // 5. Query MongoDB for matching candidates (bounded by page request to prevent OOM)
        List<UserProfile> candidates = userProfileRepository.findCandidates(
                targetGender,
                religions,
                motherTongues,
                maritalStatuses,
                dobFrom,
                dobTo,
                item.getUserId(),
                PageRequest.of(0, maxCandidates)
        );

        log.info("Found {} candidates for user {} (gender={}, religions={}, tongues={})",
                candidates.size(), item.getUserId(), targetGender, religions, motherTongues);

        if (candidates.isEmpty()) {
            log.warn("No candidates found for user {}, fetching default matches", item.getUserId());
            candidates = userProfileRepository.findDefaultCandidates(targetGender, item.getUserId(), PageRequest.of(0, maxCandidates));
        }

        return UserCandidatePool.builder()
                .userId(item.getUserId())
                .sourceProfile(source)
                .candidates(candidates)
                .build();
    }
}
