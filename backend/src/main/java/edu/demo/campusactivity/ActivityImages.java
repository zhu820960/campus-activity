package edu.demo.campusactivity;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.IIOException;
import javax.imageio.stream.ImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Service
class ActivityImageStore {
    private static final long MAX_BYTES = 3L * 1024 * 1024;
    private final Path directory;

    ActivityImageStore(@Value("${app.images.directory:./uploads/images}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    String save(MultipartFile upload) {
        SafeImageCodec.Encoded image = SafeImageCodec.encode(upload, MAX_BYTES);
        try {
            Files.createDirectories(directory);
            String filename = UUID.randomUUID().toString().replace("-", "") + "." + image.extension();
            Path temporary = Files.createTempFile(directory, "image-", ".tmp");
            try {
                Files.write(temporary, image.bytes());
                Files.move(temporary, directory.resolve(filename), StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return "/images/" + filename;
        } catch (IOException error) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "图片保存失败");
        }
    }

    Resource read(String filename) {
        if (!filename.matches("[0-9a-f]{32}\\.(jpg|png)")) {
            throw new ApiException(HttpStatus.NOT_FOUND, "图片不存在");
        }
        Path file = directory.resolve(filename).normalize();
        if (!file.startsWith(directory) || !Files.isRegularFile(file)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "图片不存在");
        }
        return new FileSystemResource(file);
    }
}

final class SafeImageCodec {
    record Encoded(byte[] bytes, String extension) {}

    private SafeImageCodec() {}

    static Encoded encode(MultipartFile upload, long maxBytes) {
        if (upload == null || upload.isEmpty() || upload.getSize() > maxBytes) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "图片超出大小限制");
        }
        try {
            byte[] input = upload.getBytes();
            if (input.length > maxBytes) throw new ApiException(HttpStatus.BAD_REQUEST, "图片超出大小限制");
            try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
                if (stream == null) throw new ApiException(HttpStatus.BAD_REQUEST, "无法识别图片");
                Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw new ApiException(HttpStatus.BAD_REQUEST, "仅支持 JPEG 或 PNG 图片");
                ImageReader reader = readers.next();
                try {
                    reader.setInput(stream);
                    String format = reader.getFormatName().toLowerCase();
                    if (!format.equals("jpeg") && !format.equals("png")) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "仅支持 JPEG 或 PNG 图片");
                    }
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    if (width < 1 || height < 1 || width > 4096 || height > 4096
                        || (long) width * height > 12_000_000L) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "图片尺寸超出限制");
                    }
                    BufferedImage image = reader.read(0);
                    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                    if (!ImageIO.write(image, format, encoded) || encoded.size() > maxBytes) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "图片编码失败或超出大小限制");
                    }
                    return new Encoded(encoded.toByteArray(), format.equals("jpeg") ? "jpg" : "png");
                } finally {
                    reader.dispose();
                }
            }
        } catch (IIOException error) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "无法识别图片");
        } catch (IOException error) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "图片处理失败");
        }
    }
}

@RestController
@RequestMapping("/api/organizer/images")
class ActivityImageUploadController {
    record ImageView(String url) {}

    private final ActivityImageStore images;
    private final CurrentUser current;

    ActivityImageUploadController(ActivityImageStore images, CurrentUser current) {
        this.images = images;
        this.current = current;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ImageView upload(@RequestParam("file") MultipartFile file) {
        current.require(UserAccount.Role.ADMIN, UserAccount.Role.ORGANIZER);
        return new ImageView(images.save(file));
    }
}

@RestController
class ActivityImageReadController {
    private final ActivityImageStore images;
    private final ActivityRepository activities;
    private final CurrentUser current;

    ActivityImageReadController(ActivityImageStore images, ActivityRepository activities, CurrentUser current) {
        this.images = images;
        this.activities = activities;
        this.current = current;
    }

    @GetMapping("/images/{filename}")
    ResponseEntity<Resource> read(@PathVariable String filename) {
        UserAccount user = current.require(UserAccount.Role.ADMIN, UserAccount.Role.ORGANIZER);
        Resource resource = images.read(filename);
        String url = "/images/" + filename;
        if (!user.getRoles().contains(UserAccount.Role.ADMIN)
            && !activities.existsByCoverImageUrlAndOrganizerId(url, user.id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "图片不存在");
        }
        MediaType type = filename.endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type).header("Cache-Control", "private, max-age=300").body(resource);
    }
}

@RestController
@RequestMapping("/api/miniapp/images")
class MiniappActivityImageController {
    private final ActivityImageStore images;
    private final ActivityRepository activities;
    private final CurrentUser current;

    MiniappActivityImageController(ActivityImageStore images, ActivityRepository activities, CurrentUser current) {
        this.images = images;
        this.activities = activities;
        this.current = current;
    }

    @GetMapping("/{filename}")
    ResponseEntity<Resource> read(@PathVariable String filename) {
        current.require(UserAccount.Role.STUDENT);
        String url = "/images/" + filename;
        if (!activities.existsByCoverImageUrlAndStatus(url, Activity.Status.PUBLISHED)
            && !activities.existsByCoverImageUrlAndStatus(url, Activity.Status.CANCELLED)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "图片不存在");
        }
        Resource resource = images.read(filename);
        MediaType type = filename.endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type).header("Cache-Control", "private, max-age=300").body(resource);
    }
}

@RestController
@RequestMapping("/api/miniapp/organizer/images")
class MiniappOrganizerImageController {
    private final ActivityImageStore images;
    private final ActivityRepository activities;
    private final MiniappOrganizerController organizer;
    private final CurrentUser current;

    MiniappOrganizerImageController(ActivityImageStore images, ActivityRepository activities,
                                    MiniappOrganizerController organizer, CurrentUser current) {
        this.images = images;
        this.activities = activities;
        this.organizer = organizer;
        this.current = current;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ActivityImageUploadController.ImageView upload(@RequestParam("file") MultipartFile file) {
        organizer.requireOrganizer();
        return new ActivityImageUploadController.ImageView(images.save(file));
    }

    @GetMapping("/{filename}")
    ResponseEntity<Resource> read(@PathVariable String filename) {
        organizer.requireOrganizer();
        String url = "/images/" + filename;
        if (!activities.existsByCoverImageUrlAndOrganizerId(url, current.get().id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "图片不存在");
        }
        Resource resource = images.read(filename);
        MediaType type = filename.endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type).header("Cache-Control", "private, max-age=300").body(resource);
    }
}
