package edu.demo.campusactivity;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityImageStoreTests {
    @TempDir Path directory;

    @Test
    void acceptsAndReencodesPngUnderUnpredictableName() throws Exception {
        ActivityImageStore store = new ActivityImageStore(directory.toString());
        BufferedImage image = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        ImageIO.write(image, "png", data);
        String url = store.save(new MockMultipartFile("file", "cover.png", "image/png", data.toByteArray()));
        assertTrue(url.matches("/images/[0-9a-f]{32}\\.png"));
        assertTrue(store.read(url.substring(8)).exists());
        assertEquals(404, assertThrows(ApiException.class, () -> store.read("../outside.png")).status.value());
    }

    @Test
    void rejectsNonImagesAndOversizedUploads() {
        ActivityImageStore store = new ActivityImageStore(directory.toString());
        assertEquals(400, assertThrows(ApiException.class, () -> store.save(
            new MockMultipartFile("file", "fake.png", "image/png", "not an image".getBytes()))).status.value());
        assertEquals(400, assertThrows(ApiException.class, () -> store.save(
            new MockMultipartFile("file", "huge.png", "image/png", new byte[3 * 1024 * 1024 + 1]))).status.value());
    }
}
