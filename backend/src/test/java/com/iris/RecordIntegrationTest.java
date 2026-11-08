package com.iris;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iris.repository.AuditEventRepository;
import com.iris.repository.ImageRecordRepository;
import com.iris.repository.VerificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "iris.storage.upload-dir=target/test-storage")
class RecordIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ImageRecordRepository recordRepository;

    @Autowired
    private VerificationRepository verificationRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @BeforeEach
    void cleanDatabase() {
        verificationRepository.deleteAll();
        recordRepository.deleteAll();
        auditEventRepository.deleteAll();
    }

    @Test
    @DisplayName("Registering an image and re-uploading the signed file matches through LSB")
    void signedImageMatchesExactly() throws Exception {
        byte[] image = samplePng(512, 384, 5);

        JsonNode record = register(image, "sample.png");
        long id = record.get("id").asLong();
        assertEquals(512, record.get("width").asInt());
        assertTrue(record.get("lsbEmbedded").asBoolean());
        assertTrue(record.get("robustEmbedded").asBoolean());

        byte[] signed = download(record.get("signedUrl").asText());
        JsonNode verification = verify(signed, "signed.png");

        assertTrue(verification.get("matched").asBoolean());
        assertEquals("LSB", verification.get("method").asText());
        assertTrue(verification.get("exactMatch").asBoolean());
        assertEquals(id, verification.get("record").get("id").asLong());
    }

    @Test
    @DisplayName("A tone-shifted JPEG re-encode still recovers the record through the robust layer")
    void tamperedJpegMatchesThroughRobustLayer() throws Exception {
        byte[] image = samplePng(512, 384, 9);
        JsonNode record = register(image, "sample.png");
        long id = record.get("id").asLong();

        byte[] signed = download(record.get("signedUrl").asText());
        byte[] tampered = tamper(signed);

        JsonNode verification = verify(tampered, "tampered.jpg");

        assertTrue(verification.get("matched").asBoolean(), "Tampered image should still match");
        assertEquals(id, verification.get("record").get("id").asLong());
        String method = verification.get("method").asText();
        assertTrue(method.equals("SPREAD") || method.equals("PHASH"), "Unexpected method: " + method);
        assertFalse(verification.get("exactMatch").asBoolean());
    }

    @Test
    @DisplayName("An unrelated image does not match any record")
    void unrelatedImageDoesNotMatch() throws Exception {
        register(samplePng(512, 384, 3), "sample.png");

        BufferedImage noise = new BufferedImage(512, 384, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < noise.getHeight(); y++) {
            for (int x = 0; x < noise.getWidth(); x++) {
                noise.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(noise, "png", out);

        JsonNode verification = verify(out.toByteArray(), "noise.png");
        assertFalse(verification.get("matched").asBoolean());
        assertEquals("NONE", verification.get("method").asText());
    }

    @Test
    @DisplayName("Deleting a record removes it and its files")
    void deleteRemovesRecord() throws Exception {
        JsonNode record = register(samplePng(384, 256, 21), "delete-me.png");
        long id = record.get("id").asLong();

        mockMvc.perform(delete("/api/records/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/records/" + id))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Non-image uploads are rejected with a safe error")
    void invalidFileRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "not an image".getBytes());

        mockMvc.perform(multipart("/api/records").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_FORMAT"));
    }

    private JsonNode register(byte[] image, String filename) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, "image/png", image);
        String body = mockMvc.perform(multipart("/api/records").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.originalUrl").isString())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode verify(byte[] image, String filename) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, "image/png", image);
        String body = mockMvc.perform(multipart("/api/verify").file(file))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }

    private byte[] download(String url) throws Exception {
        return mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
    }

    private byte[] tamper(byte[] signedBytes) throws Exception {
        BufferedImage signed = ImageIO.read(new ByteArrayInputStream(signedBytes));
        BufferedImage adjusted = new BufferedImage(signed.getWidth(), signed.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < signed.getHeight(); y++) {
            for (int x = 0; x < signed.getWidth(); x++) {
                int rgb = signed.getRGB(x, y);
                int r = clamp((int) (((rgb >> 16) & 0xFF) * 1.15 + 10));
                int g = clamp((int) (((rgb >> 8) & 0xFF) * 1.15 + 10));
                int b = clamp((int) ((rgb & 0xFF) * 1.15 + 10));
                adjusted.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(adjusted, "jpg", out);
        return out.toByteArray();
    }

    private byte[] samplePng(int width, int height, long seed) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = (int) (30 + 200.0 * x / width);
                int g = (int) (50 + 150.0 * y / height);
                int b = (int) (160 - 80.0 * x / width);
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        Random random = new Random(seed);
        for (int i = 0; i < 3000; i++) {
            int x = random.nextInt(width);
            int y = random.nextInt(height);
            int v = random.nextBoolean() ? 0 : 255;
            image.setRGB(x, y, (v << 16) | (v << 8) | v);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private int clamp(int value) {
        return Math.min(255, Math.max(0, value));
    }
}
