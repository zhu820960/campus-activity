package edu.demo.campusactivity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Service
class AvatarImageStore {
    private static final Logger log = LoggerFactory.getLogger(AvatarImageStore.class);
    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private final Path directory;

    AvatarImageStore(@Value("${app.avatars.directory:./uploads/avatars}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    String save(MultipartFile upload) {
        SafeImageCodec.Encoded image = SafeImageCodec.encode(upload, MAX_BYTES);
        try {
            Files.createDirectories(directory);
            String key = UUID.randomUUID().toString().replace("-", "") + "." + image.extension();
            Path temporary = Files.createTempFile(directory, "avatar-", ".tmp");
            try {
                Files.write(temporary, image.bytes());
                Files.move(temporary, directory.resolve(key), StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return key;
        } catch (IOException error) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "头像保存失败");
        }
    }

    Resource read(String key) {
        Path file = pathFor(key);
        if (!Files.isRegularFile(file)) throw new ApiException(HttpStatus.NOT_FOUND, "头像不存在");
        return new FileSystemResource(file);
    }

    void deleteQuietly(String key) {
        if (key == null) return;
        try {
            Files.deleteIfExists(pathFor(key));
        } catch (IOException error) {
            log.warn("无法清理旧头像文件");
        }
    }

    private Path pathFor(String key) {
        if (key == null || !key.matches("[0-9a-f]{32}\\.(jpg|png)")) {
            throw new ApiException(HttpStatus.NOT_FOUND, "头像不存在");
        }
        Path file = directory.resolve(key).normalize();
        if (!file.startsWith(directory)) throw new ApiException(HttpStatus.NOT_FOUND, "头像不存在");
        return file;
    }
}

@Service
class StudentAvatarService {
    private final CurrentUser current;
    private final UserRepository users;
    private final AvatarImageStore store;

    StudentAvatarService(CurrentUser current, UserRepository users, AvatarImageStore store) {
        this.current = current;
        this.users = users;
        this.store = store;
    }

    private UserAccount actor() {
        return current.miniappAttribution() != null ? current.require(UserAccount.Role.STUDENT)
            : current.require(UserAccount.Role.ADMIN, UserAccount.Role.ORGANIZER);
    }

    @Transactional
    MiniappAuthService.StudentView upload(MultipartFile file) {
        UserAccount student = actor();
        String oldKey = student.avatarKey;
        String newKey = store.save(file);
        cleanupAfterTransaction(oldKey, newKey);
        student.avatarKey = newKey;
        users.saveAndFlush(student);
        return MiniappAuthService.StudentView.of(student);
    }

    @Transactional
    void remove() {
        UserAccount student = actor();
        String oldKey = student.avatarKey;
        if (oldKey == null) return;
        student.avatarKey = null;
        users.saveAndFlush(student);
        cleanupAfterTransaction(oldKey, null);
    }

    @Transactional(readOnly = true)
    ResponseEntity<Resource> read() {
        UserAccount student = actor();
        String key = student.avatarKey;
        if (key == null) throw new ApiException(HttpStatus.NOT_FOUND, "头像不存在");
        Resource image = store.read(key);
        MediaType type = key.endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type).header("Cache-Control", "private, no-store").body(image);
    }

    private void cleanupAfterTransaction(String oldKey, String newKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) store.deleteQuietly(oldKey);
                else store.deleteQuietly(newKey);
            }
        });
    }
}

@RestController
@RequestMapping("/api/account/avatar")
class WebAvatarController {
    private final StudentAvatarService service;
    private final CurrentUser current;
    WebAvatarController(StudentAvatarService service, CurrentUser current) {
        this.service = service;
        this.current = current;
    }
    @GetMapping
    ResponseEntity<Resource> read() { return service.read(); }
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    AuthController.UserView upload(@RequestParam("file") MultipartFile file) {
        service.upload(file);
        return AuthController.UserView.of(current.get());
    }
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove() { service.remove(); }
}

@RestController
@RequestMapping("/api/miniapp/my/avatar")
class StudentAvatarController {
    private final StudentAvatarService service;

    StudentAvatarController(StudentAvatarService service) { this.service = service; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    MiniappAuthService.StudentView upload(@RequestParam("file") MultipartFile file) {
        return service.upload(file);
    }

    @GetMapping
    ResponseEntity<Resource> read() { return service.read(); }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove() { service.remove(); }
}
