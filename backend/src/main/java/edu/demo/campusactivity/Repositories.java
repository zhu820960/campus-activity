package edu.demo.campusactivity;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UserRepository extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByEmailIgnoreCase(String email);
    Optional<UserAccount> findByStudentNumber(String studentNumber);
    Optional<UserAccount> findByLoginNameIgnoreCase(String loginName);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserAccount u where u.id = :id")
    Optional<UserAccount> lockUserById(@Param("id") Long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserAccount u where lower(u.email) = lower(:email)")
    Optional<UserAccount> lockByEmailIgnoreCase(@Param("email") String email);
    List<UserAccount> findAllByOrderByCreatedAtDesc();
}

interface ActivityRepository extends JpaRepository<Activity, Long> {
    boolean existsByCoverImageUrlAndStatus(String coverImageUrl, Activity.Status status);
    boolean existsByCoverImageUrlAndOrganizerId(String coverImageUrl, Long organizerId);
    long countByOrganizerId(Long organizerId);
    List<Activity> findAllByOrderByCreatedAtDesc();
    List<Activity> findByStatusOrderByStartsAtAsc(Activity.Status status);
    List<Activity> findByOrganizerIdOrderByCreatedAtDesc(Long organizerId);
    List<Activity> findByOrganizerIdOrderByCreatedAtDesc(Long organizerId, Pageable pageable);
    List<Activity> findByStatusOrderByCreatedAtAsc(Activity.Status status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Activity a where a.id = :id")
    Optional<Activity> lockById(@Param("id") Long id);
}

interface RegistrationRepository extends JpaRepository<Registration, Long>,
    org.springframework.data.jpa.repository.JpaSpecificationExecutor<Registration> {
    Optional<Registration> findByActivityIdAndStudentId(Long activityId, Long studentId);
    List<Registration> findByStudentIdOrderByRegisteredAtDesc(Long studentId);
    List<Registration> findByStudentIdAndIdentityIdOrderByRegisteredAtDesc(Long studentId, Long identityId);
    List<Registration> findByStudentIdInOrderByRegisteredAtDesc(java.util.Collection<Long> studentIds);
    List<Registration> findByActivityIdInAndStudentId(java.util.Collection<Long> activityIds, Long studentId);
    List<Registration> findByActivityIdOrderByRegisteredAtAsc(Long activityId);
    @EntityGraph(attributePaths = {"student"})
    Page<Registration> findByActivityIdAndStatus(Long activityId, Registration.Status status, Pageable pageable);
    @Query("select r.registeredAt from Registration r where r.status = :status and r.registeredAt >= :start and r.registeredAt < :end")
    List<java.time.Instant> activeRegistrationTimes(@Param("status") Registration.Status status,
        @Param("start") java.time.Instant start, @Param("end") java.time.Instant end);
}

interface CheckinRepository extends JpaRepository<Checkin, Long> {
    boolean existsByActivityIdAndStudentId(Long activityId, Long studentId);
    Optional<Checkin> findByActivityIdAndStudentId(Long activityId, Long studentId);
    List<Checkin> findByActivityId(Long activityId);
    @Query("select c from Checkin c, Registration r where r.id in :registrationIds "
        + "and c.activity = r.activity and c.student = r.student")
    List<Checkin> findForRegistrations(@Param("registrationIds") List<Long> registrationIds);
    long countByActivityId(Long activityId);

    @Query("select c.activity.id, count(c) from Checkin c group by c.activity.id")
    List<Object[]> countByActivityGrouped();
    @Query("select c.checkedAt from Checkin c where c.checkedAt >= :start and c.checkedAt < :end")
    List<java.time.Instant> checkinTimes(@Param("start") java.time.Instant start,
        @Param("end") java.time.Instant end);
}

interface ActivityAuditRepository extends JpaRepository<ActivityAudit, Long>,
    org.springframework.data.jpa.repository.JpaSpecificationExecutor<ActivityAudit> {
    List<ActivityAudit> findByActivityIdOrderByIdDesc(Long activityId);
    List<ActivityAudit> findTop100ByOrderByIdDesc();
}

interface UserSecurityAuditRepository extends JpaRepository<UserSecurityAudit, Long>,
    org.springframework.data.jpa.repository.JpaSpecificationExecutor<UserSecurityAudit> {
    List<UserSecurityAudit> findTop100ByOrderByIdDesc();
}
