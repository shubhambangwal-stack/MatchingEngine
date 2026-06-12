package com.pearl.astrology.match.repository;

import com.pearl.astrology.match.entity.UserProfile;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import java.time.LocalDate;
import java.util.List;

/**
 * Read-only access to the users collection managed by the Node.js backend.
 */
public interface UserProfileRepository extends MongoRepository<UserProfile, String> {

    /**
     * Find active, non-blocked candidate profiles matching partner preferences.
     * Filters by: opposite gender, religion list, motherTongue list, marital status,
     * and date-of-birth within the age range.
     */
    @Query("{ " +
           "'isActive': true, " +
           "'isBlocked': false, " +
           "'profileCompleted': true, " +
           "'gender': ?0, " +
           "'religion': { $in: ?1 }, " +
           "'motherTongue': { $in: ?2 }, " +
           "'maritalStatus': { $in: ?3 }, " +
           "'dob': { $gte: ?4, $lte: ?5 }, " +
           "'_id': { $ne: ?6 } " +
           "}")
    List<UserProfile> findCandidates(
            String gender,
            List<String> religions,
            List<String> motherTongues,
            List<String> maritalStatuses,
            LocalDate dobFrom,
            LocalDate dobTo,
            String excludeUserId,
            org.springframework.data.domain.Pageable pageable
    );

    @Query("{ " +
           "'isActive': true, " +
           "'isBlocked': false, " +
           "'profileCompleted': true, " +
           "'gender': ?0, " +
           "'_id': { $ne: ?1 } " +
           "}")
    List<UserProfile> findDefaultCandidates(
            String gender,
            String excludeUserId,
            org.springframework.data.domain.Pageable pageable
    );
}
