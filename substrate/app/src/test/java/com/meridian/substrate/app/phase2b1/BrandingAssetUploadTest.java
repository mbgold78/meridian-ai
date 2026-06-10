package com.meridian.substrate.app.phase2b1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.app.blob.BlobStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for {@code POST /api/v1/branding/assets} — round-trips a
 * multipart upload through {@link InMemoryBlobStore} (test-only impl, marked
 * {@code @Primary} via {@code @TestConfiguration} so the production
 * {@code MinioBlobStore} / {@code LocalFilesystemBlobStore} / no-op fallback
 * are not consulted). Ported homepoc phase2b1 suite (substrate dev profile).
 *
 * <p>Three assertions kept seam-split per the homepoc test pattern:
 * <ul>
 *   <li>Upload without admin header is rejected with 403 — no bytes hit the store.</li>
 *   <li>Successful upload returns a {@code blobKey} the client can persist
 *       onto a {@link com.meridian.substrate.common.domain.BrandingConfig}
 *       field.</li>
 *   <li>The bytes are retrievable via the BlobStore by the returned key,
 *       proving the controller wrote what it claimed.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(BrandingAssetUploadTest.StubConfig.class)
class BrandingAssetUploadTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired InMemoryBlobStore blobStore;

    @Test
    void upload_rejectsNonAdmin_withoutWritingAnyBytes() throws Exception {
        int sizeBefore = blobStore.size();
        MockMultipartFile file = pngFile();

        mvc.perform(multipart("/api/v1/branding/assets").file(file))
                .andExpect(status().isForbidden());

        assertThat(blobStore.size())
                .as("a 403 response must not have written any bytes to the store")
                .isEqualTo(sizeBefore);
    }

    @Test
    void upload_returnsBlobKey_andStoredBytesRoundTripThroughBlobStore() throws Exception {
        MockMultipartFile file = pngFile();

        MvcResult result = mvc.perform(multipart("/api/v1/branding/assets")
                        .file(file)
                        .header("X-Persona-Level", "ADMIN"))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode data = json.readTree(result.getResponse().getContentAsByteArray()).path("data");
        String bucket = data.path("bucket").asText();
        String blobKey = data.path("blobKey").asText();
        String contentType = data.path("contentType").asText();
        long contentLength = data.path("contentLength").asLong();

        assertThat(bucket).isNotBlank();
        assertThat(blobKey).startsWith("branding/").endsWith(".png");
        assertThat(contentType).isEqualTo("image/png");
        assertThat(contentLength).isEqualTo(file.getBytes().length);

        Optional<BlobStore.BlobHandle> handle = blobStore.get(bucket, blobKey);
        assertThat(handle)
                .as("uploaded bytes must be retrievable from the BlobStore by the returned key")
                .isPresent();
        try (InputStream stored = handle.get().content()) {
            assertThat(stored.readAllBytes())
                    .as("retrieved bytes must equal the uploaded bytes")
                    .isEqualTo(file.getBytes());
        }
    }

    private MockMultipartFile pngFile() {
        // 8-byte PNG signature + arbitrary trailing bytes — enough to exercise
        // the multipart pipeline. The controller does not do header sniffing,
        // only content-type and size checks.
        byte[] bytes = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07
        };
        return new MockMultipartFile("file", "logo.png", "image/png", bytes);
    }

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        InMemoryBlobStore inMemoryBlobStore() {
            return new InMemoryBlobStore();
        }
    }

    /**
     * Test-only {@link BlobStore} keyed by {@code bucket + "/" + key}. Marked
     * {@code @Primary} via {@code @TestConfiguration} so it wins autowiring
     * over the production impls / no-op fallback. Captures bytes in memory so
     * the test can assert the controller actually wrote what it claimed to
     * write.
     */
    static class InMemoryBlobStore implements BlobStore {

        record Entry(String contentType, byte[] bytes) {}

        private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

        int size() { return store.size(); }

        @Override
        public String put(String bucket, String key, InputStream content, long contentLength, String contentType) {
            try {
                byte[] bytes = content.readAllBytes();
                store.put(bucket + "/" + key, new Entry(contentType, bytes));
                return bucket + "/" + key;
            } catch (IOException e) {
                throw new IllegalStateException("InMemoryBlobStore put failed", e);
            }
        }

        @Override
        public Optional<BlobHandle> get(String bucket, String key) {
            Entry e = store.get(bucket + "/" + key);
            if (e == null) return Optional.empty();
            return Optional.of(new BlobHandle(bucket, key, e.contentType(), e.bytes().length,
                    new ByteArrayInputStream(e.bytes())));
        }

        @Override
        public boolean delete(String bucket, String key) {
            return store.remove(bucket + "/" + key) != null;
        }

        @Override
        public boolean exists(String bucket, String key) {
            return store.containsKey(bucket + "/" + key);
        }
    }
}
