package edu.demo.campusactivity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Entity
@Table(name = "announcements")
class Announcement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @Column(nullable = false, length = 160)
    String title;
    @Column(nullable = false, length = 3000)
    String body;
    @Column(nullable = false)
    Instant publishedAt = Instant.now();
    @Column(nullable = false)
    boolean active = true;

    protected Announcement() {}

    Announcement(String title, String body) {
        this.title = title;
        this.body = body;
    }
}

interface AnnouncementRepository extends JpaRepository<Announcement, Long> {
    List<Announcement> findByActiveTrueOrderByPublishedAtDesc();
    List<Announcement> findAllByOrderByPublishedAtDesc();
}

record AnnouncementView(Long id, String title, String body, Instant publishedAt, boolean active) {
    static AnnouncementView of(Announcement a) {
        return new AnnouncementView(a.id, a.title, a.body, a.publishedAt, a.active);
    }
}

@RestController
@RequestMapping("/api/announcements")
class AnnouncementController {
    private final AnnouncementRepository announcements;
    private final CurrentUser current;

    AnnouncementController(AnnouncementRepository announcements, CurrentUser current) {
        this.announcements = announcements;
        this.current = current;
    }

    @GetMapping
    @Transactional(readOnly = true)
    List<AnnouncementView> published() {
        current.require(UserAccount.Role.ORGANIZER, UserAccount.Role.ADMIN);
        return announcements.findByActiveTrueOrderByPublishedAtDesc().stream()
            .map(AnnouncementView::of).toList();
    }
}

@RestController
@RequestMapping("/api/admin/announcements")
class AdminAnnouncementController {
    record Input(@NotBlank @Size(max = 160) String title,
                 @NotBlank @Size(max = 3000) String body) {}

    private final AnnouncementRepository announcements;
    private final CurrentUser current;

    AdminAnnouncementController(AnnouncementRepository announcements, CurrentUser current) {
        this.announcements = announcements;
        this.current = current;
    }

    @GetMapping
    @Transactional(readOnly = true)
    List<AnnouncementView> all() {
        current.require(UserAccount.Role.ADMIN);
        return announcements.findAllByOrderByPublishedAtDesc().stream()
            .map(AnnouncementView::of).toList();
    }

    @PostMapping
    @Transactional
    AnnouncementView publish(@Valid @RequestBody Input input) {
        current.require(UserAccount.Role.ADMIN);
        return AnnouncementView.of(announcements.save(new Announcement(input.title().trim(), input.body().trim())));
    }

    @DeleteMapping("/{id}")
    @Transactional
    void withdraw(@PathVariable Long id) {
        current.require(UserAccount.Role.ADMIN);
        Announcement announcement = find(id);
        announcement.active = false;
    }

    @PutMapping("/{id}")
    @Transactional
    AnnouncementView edit(@PathVariable Long id, @Valid @RequestBody Input input) {
        current.require(UserAccount.Role.ADMIN);
        Announcement announcement = find(id);
        announcement.title = input.title().trim();
        announcement.body = input.body().trim();
        return AnnouncementView.of(announcement);
    }

    @PostMapping("/{id}/publish")
    @Transactional
    AnnouncementView republish(@PathVariable Long id) {
        current.require(UserAccount.Role.ADMIN);
        Announcement announcement = find(id);
        if (announcement.active) {
            throw new ApiException(HttpStatus.CONFLICT, "公告已经发布");
        }
        announcement.active = true;
        announcement.publishedAt = Instant.now();
        return AnnouncementView.of(announcement);
    }

    private Announcement find(Long id) {
        return announcements.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "公告不存在"));
    }
}
