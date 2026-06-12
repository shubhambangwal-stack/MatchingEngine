package com.pearl.astrology.match.batch;

import com.pearl.astrology.match.entity.Match;
import com.pearl.astrology.match.entity.UserProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Scores each candidate against the source user's profile using weighted dimensions:
 * Values (25%), Family (20%), Career (20%), Location (15%), Horoscope (20%)
 *
 * Replaces the mock Random-based scoring with real field-level comparison.
 */
@Component
@Slf4j
public class CompatibilityScoringProcessor implements ItemProcessor<UserCandidatePool, UserMatchResult> {

    @Override
    public UserMatchResult process(UserCandidatePool pool) {
        log.info("Scoring {} candidates for user {}", pool.getCandidates().size(), pool.getUserId());

        UserProfile source = pool.getSourceProfile();
        
        // Determine tier based on common industry standard premium tiers
        String tier = source.getSubscriptionTier();
        boolean isPremium = tier != null && (
                tier.equalsIgnoreCase("PREMIUM") || 
                tier.equalsIgnoreCase("GOLD") || 
                tier.equalsIgnoreCase("DIAMOND") || 
                tier.equalsIgnoreCase("ELITE")
        );

        // Industry standard limits and thresholds
        int limit = isPremium ? 500 : 10;
        double minScore = isPremium ? 40.0 : 50.0;

        List<Match> matches = pool.getCandidates().stream()
                .map(candidate -> scoreCandidate(source, candidate))
                .filter(match -> match.getScore() >= minScore)
                .sorted(Comparator.comparingDouble(Match::getScore).reversed())
                .limit(limit)
                .collect(Collectors.toList());

        return UserMatchResult.builder()
                .userId(source.getId())
                .matches(matches)
                .build();
    }

    private Match scoreCandidate(UserProfile source, UserProfile candidate) {
        double valuesScore = computeValuesScore(source, candidate);       // max 25
        double familyScore = computeFamilyScore(source, candidate);       // max 20
        double careerScore = computeCareerScore(source, candidate);       // max 20
        double locationScore = computeLocationScore(source, candidate);   // max 15
        double horoscopeScore = computeHoroscopeScore(source, candidate); // max 20

        double totalScore = valuesScore + familyScore + careerScore + locationScore + horoscopeScore;

        return Match.builder()
                .userId(source.getId())
                .candidateId(candidate.getId())
                .score(Math.round(totalScore * 100.0) / 100.0) // Round to 2 decimals
                .matchTimestamp(LocalDateTime.now())
                .viewed(false)
                .build();
    }

    // ──────────────────────────────────────────────────────────────
    // VALUES SCORE (25 points max)
    // Compares: values[], lifestyle.interests[], hobbies[]
    // ──────────────────────────────────────────────────────────────
    private double computeValuesScore(UserProfile source, UserProfile candidate) {
        double score = 0;

        // values[] overlap (max 10 points)
        score += overlapScore(source.getValues(), candidate.getValues(), 10);

        // hobbies[] overlap (max 8 points)
        score += overlapScore(source.getHobbies(), candidate.getHobbies(), 8);

        // lifestyle.interests[] overlap (max 7 points)
        List<String> sourceInterests = source.getLifestyle() != null ? source.getLifestyle().getInterests() : null;
        List<String> candidateInterests = candidate.getLifestyle() != null ? candidate.getLifestyle().getInterests() : null;
        score += overlapScore(sourceInterests, candidateInterests, 7);

        return score;
    }

    // ──────────────────────────────────────────────────────────────
    // FAMILY SCORE (20 points max)
    // Compares: familyStatus, familyType, familyValues
    // ──────────────────────────────────────────────────────────────
    private double computeFamilyScore(UserProfile source, UserProfile candidate) {
        double score = 0;

        // familyStatus match (max 7 points)
        if (equalsIgnoreNull(source.getFamilyStatus(), candidate.getFamilyStatus())) {
            score += 7;
        } else if (areSimilarFamilyStatus(source.getFamilyStatus(), candidate.getFamilyStatus())) {
            score += 4; // partial credit for adjacent tiers
        }

        // familyType match (max 6 points) — Nuclear/Joint/Joint Family
        if (equalsIgnoreNull(source.getFamilyType(), candidate.getFamilyType())) {
            score += 6;
        }

        // familyValues match (max 7 points) — Traditional/Moderate/Liberal
        if (equalsIgnoreNull(source.getFamilyValues(), candidate.getFamilyValues())) {
            score += 7;
        } else if (areAdjacentValues(source.getFamilyValues(), candidate.getFamilyValues())) {
            score += 4; // partial credit
        }

        return score;
    }

    // ──────────────────────────────────────────────────────────────
    // CAREER SCORE (20 points max)
    // Compares: profession, annualIncome, highestQualification
    // ──────────────────────────────────────────────────────────────
    private double computeCareerScore(UserProfile source, UserProfile candidate) {
        double score = 0;

        // Qualification level match (max 8 points)
        int sourceQual = qualificationLevel(source.getHighestQualification());
        int candidateQual = qualificationLevel(candidate.getHighestQualification());
        int qualDiff = Math.abs(sourceQual - candidateQual);
        if (qualDiff == 0) score += 8;
        else if (qualDiff == 1) score += 5;
        else if (qualDiff == 2) score += 2;

        // Income tier proximity (max 7 points)
        int sourceIncome = incomeTier(source.getAnnualIncome());
        int candidateIncome = incomeTier(candidate.getAnnualIncome());
        int incomeDiff = Math.abs(sourceIncome - candidateIncome);
        if (incomeDiff == 0) score += 7;
        else if (incomeDiff == 1) score += 5;
        else if (incomeDiff == 2) score += 2;

        // Same profession field bonus (max 5 points)
        if (equalsIgnoreNull(source.getProfession(), candidate.getProfession())) {
            score += 5;
        } else if (source.getProfession() != null && candidate.getProfession() != null
                && source.getWorkingWith() != null
                && source.getWorkingWith().equalsIgnoreCase(candidate.getWorkingWith())) {
            score += 3; // same sector (Private/Govt/Business)
        }

        return score;
    }

    // ──────────────────────────────────────────────────────────────
    // LOCATION SCORE (15 points max)
    // Uses horoscope.cityOfBirth as proxy for location
    // ──────────────────────────────────────────────────────────────
    private double computeLocationScore(UserProfile source, UserProfile candidate) {
        if (source.getHoroscope() == null || candidate.getHoroscope() == null) return 7.5; // neutral

        String sourceCity = source.getHoroscope().getCityOfBirth();
        String candidateCity = candidate.getHoroscope().getCityOfBirth();

        if (equalsIgnoreNull(sourceCity, candidateCity)) {
            return 15; // Same city
        }

        // Could add state/region matching here in the future
        return 5; // Different city, base score
    }

    // ──────────────────────────────────────────────────────────────
    // HOROSCOPE SCORE (20 points max)
    // Compares: manglik compatibility, gotra (must differ), birth details
    // ──────────────────────────────────────────────────────────────
    private double computeHoroscopeScore(UserProfile source, UserProfile candidate) {
        if (source.getHoroscope() == null || candidate.getHoroscope() == null) return 10; // neutral

        double score = 0;
        UserProfile.Horoscope srcH = source.getHoroscope();
        UserProfile.Horoscope canH = candidate.getHoroscope();

        // Manglik compatibility (max 10 points)
        // Both manglik or both non-manglik = full score; mixed = penalty
        if (equalsIgnoreNull(srcH.getManglik(), canH.getManglik())) {
            score += 10;
        } else {
            score += 2; // Manglik mismatch is a significant concern in Indian matrimony
        }

        // Gotra check (max 6 points) — must be DIFFERENT for compatibility
        if (srcH.getGotra() != null && canH.getGotra() != null) {
            if (!srcH.getGotra().equalsIgnoreCase(canH.getGotra())) {
                score += 6; // Different gotra = good
            }
            // Same gotra = 0 points (traditionally incompatible)
        } else {
            score += 3; // Unknown gotra = neutral
        }

        // Birth city similarity bonus (max 4 points)
        if (equalsIgnoreNull(srcH.getCityOfBirth(), canH.getCityOfBirth())) {
            score += 4;
        } else {
            score += 1;
        }

        return score;
    }

    // ──────────────────────────────────────────────────────────────
    // UTILITY METHODS
    // ──────────────────────────────────────────────────────────────

    /**
     * Computes overlap percentage between two lists and scales to maxPoints.
     */
    private double overlapScore(List<String> listA, List<String> listB, double maxPoints) {
        if (listA == null || listB == null || listA.isEmpty() || listB.isEmpty()) {
            return maxPoints * 0.3; // Some base score when data is missing
        }

        Set<String> setA = listA.stream().map(String::toLowerCase).collect(Collectors.toSet());
        Set<String> setB = listB.stream().map(String::toLowerCase).collect(Collectors.toSet());

        long common = setA.stream().filter(setB::contains).count();
        int total = Math.max(setA.size(), setB.size());

        return (common / (double) total) * maxPoints;
    }

    private boolean equalsIgnoreNull(String a, String b) {
        if (a == null || b == null) return false;
        return a.equalsIgnoreCase(b);
    }

    private boolean areSimilarFamilyStatus(String a, String b) {
        // Middle Class ↔ Upper Middle Class are adjacent
        List<String> tiers = List.of("lower class", "middle class", "upper middle class", "rich", "affluent");
        return areAdjacent(tiers, a, b);
    }

    private boolean areAdjacentValues(String a, String b) {
        // Traditional ↔ Moderate ↔ Liberal
        List<String> tiers = List.of("traditional", "moderate", "liberal");
        return areAdjacent(tiers, a, b);
    }

    private boolean areAdjacent(List<String> tiers, String a, String b) {
        if (a == null || b == null) return false;
        int idxA = -1, idxB = -1;
        for (int i = 0; i < tiers.size(); i++) {
            if (tiers.get(i).equalsIgnoreCase(a)) idxA = i;
            if (tiers.get(i).equalsIgnoreCase(b)) idxB = i;
        }
        return idxA >= 0 && idxB >= 0 && Math.abs(idxA - idxB) == 1;
    }

    private int qualificationLevel(String qualification) {
        if (qualification == null) return 0;
        return switch (qualification.toLowerCase()) {
            case "phd", "doctorate" -> 5;
            case "m.tech", "mba", "m.sc", "masters" -> 4;
            case "b.tech", "b.e", "bba", "b.sc", "bachelors" -> 3;
            case "diploma" -> 2;
            case "12th", "hsc" -> 1;
            default -> 2; // Unknown defaults to middle
        };
    }

    private int incomeTier(String income) {
        if (income == null) return 0;
        String lower = income.toLowerCase().replaceAll("\\s+", "");
        if (lower.contains("50") || lower.contains("above")) return 5;
        if (lower.contains("30") || lower.contains("40")) return 4;
        if (lower.contains("20") || lower.contains("25")) return 3;
        if (lower.contains("10") || lower.contains("15")) return 2;
        if (lower.contains("5") || lower.contains("below")) return 1;
        return 2; // default middle tier
    }
}
