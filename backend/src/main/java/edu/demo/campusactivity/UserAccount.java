package edu.demo.campusactivity;

import jakarta.persistence.Column;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "user_accounts")
public class UserAccount {
    public enum Role { STUDENT, ORGANIZER, ADMIN }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false, length = 80)
    public String name;
    @Column(unique = true, length = 160)
    public String email;
    @Column
    public String passwordHash;
    @Column(name = "student_number", unique = true, length = 14)
    public String studentNumber;
    @Column(name = "login_name", unique = true, length = 32)
    public String loginName;
    @Column(name = "avatar_key", length = 40)
    public String avatarKey;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    public Set<Role> roles = new HashSet<>();
    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    protected UserAccount() {}

    public UserAccount(String name, String email, String passwordHash, Role role) {
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
        this.roles.add(role);
    }

    static UserAccount selfRegisteredStudent(String name, String studentNumber) {
        UserAccount student = new UserAccount(name, null, null, Role.STUDENT);
        student.studentNumber = studentNumber;
        return student;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getStudentNumber() { return studentNumber; }
    public String getLoginName() { return loginName; }
    public String getAvatarKey() { return avatarKey; }
    public Set<Role> getRoles() { return roles; }

    void revokeWebCredentials() { this.passwordHash = null; }
}
