package com.meridian.substrate.app.blob;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link LocalFilesystemBlobStore} — the no-Docker dev
 * fallback. Pure filesystem behavior, no Spring context.
 */
class LocalFilesystemBlobStoreTest {

    private static final String BUCKET = "substrate-dev";

    @TempDir
    Path tempDir;

    private LocalFilesystemBlobStore store;

    @BeforeEach
    void setUp() {
        BlobStoreProperties props = new BlobStoreProperties();
        props.setProvider("local");
        props.setDefaultBucket(BUCKET);
        props.getLocal().setBaseDir(tempDir.resolve("blob-store").toString());
        store = new LocalFilesystemBlobStore(props);
        store.ensureBaseDir();
    }

    @Test
    void putGetRoundTrip_preservesBytesAndContentType() throws Exception {
        byte[] bytes = "logo-bytes".getBytes(StandardCharsets.UTF_8);

        String uri = store.put(BUCKET, "branding/logo.png",
                new ByteArrayInputStream(bytes), bytes.length, "image/png");
        assertThat(uri).isEqualTo(BUCKET + "/branding/logo.png");

        Optional<BlobStore.BlobHandle> handle = store.get(BUCKET, "branding/logo.png");
        assertThat(handle).isPresent();
        assertThat(handle.get().contentType()).isEqualTo("image/png");
        assertThat(handle.get().contentLength()).isEqualTo(bytes.length);
        try (InputStream in = handle.get().content()) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        }
    }

    @Test
    void get_returnsEmptyForMissingKey() {
        assertThat(store.get(BUCKET, "branding/never-written.png")).isEmpty();
    }

    @Test
    void existsAndDelete_behaveLikeObjectStore() {
        byte[] bytes = {1, 2, 3};
        store.put(BUCKET, "branding/x.svg", new ByteArrayInputStream(bytes), bytes.length, "image/svg+xml");

        assertThat(store.exists(BUCKET, "branding/x.svg")).isTrue();
        assertThat(store.delete(BUCKET, "branding/x.svg"))
                .as("delete returns true when an object was removed")
                .isTrue();
        assertThat(store.exists(BUCKET, "branding/x.svg")).isFalse();
        assertThat(store.delete(BUCKET, "branding/x.svg"))
                .as("delete returns false when the object did not exist")
                .isFalse();
    }

    @Test
    void overwrite_replacesContentAndContentType() throws Exception {
        byte[] v1 = "v1".getBytes(StandardCharsets.UTF_8);
        byte[] v2 = "v2-longer".getBytes(StandardCharsets.UTF_8);

        store.put(BUCKET, "branding/asset", new ByteArrayInputStream(v1), v1.length, "image/png");
        store.put(BUCKET, "branding/asset", new ByteArrayInputStream(v2), v2.length, "image/webp");

        BlobStore.BlobHandle handle = store.get(BUCKET, "branding/asset").orElseThrow();
        assertThat(handle.contentType()).isEqualTo("image/webp");
        try (InputStream in = handle.content()) {
            assertThat(in.readAllBytes()).isEqualTo(v2);
        }
    }

    @Test
    void missingContentTypeMetadata_fallsBackToOctetStream() throws Exception {
        byte[] bytes = {9};
        store.put(BUCKET, "branding/no-meta.bin", new ByteArrayInputStream(bytes), bytes.length, null);

        BlobStore.BlobHandle handle = store.get(BUCKET, "branding/no-meta.bin").orElseThrow();
        assertThat(handle.contentType()).isEqualTo("application/octet-stream");
    }

    @Test
    void metadataSidecar_cannotCollideWithObjectKeys() throws Exception {
        // A real object whose key ends with .ctype must not be clobbered by
        // (or read as) another key's content-type metadata — the metadata tree
        // is parallel (.meta/), not sidecar-suffixed in the object tree.
        byte[] bytes = "i-am-an-object".getBytes(StandardCharsets.UTF_8);
        store.put(BUCKET, "branding/logo.png.ctype",
                new ByteArrayInputStream(bytes), bytes.length, "image/png");
        byte[] logo = {5, 5};
        store.put(BUCKET, "branding/logo.png", new ByteArrayInputStream(logo), logo.length, "image/png");

        try (InputStream in = store.get(BUCKET, "branding/logo.png.ctype").orElseThrow().content()) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        }
    }

    @Test
    void pathTraversalKeys_areRejected_andWriteNothingOutsideBase() throws Exception {
        Path canary = tempDir.resolve("escaped.txt");
        byte[] bytes = {1};

        assertThatThrownBy(() -> store.put(BUCKET, "../../escaped.txt",
                new ByteArrayInputStream(bytes), bytes.length, "image/png"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside its bucket");

        assertThat(Files.exists(canary))
                .as("a traversal key must not create files outside the bucket root")
                .isFalse();
        assertThatThrownBy(() -> store.get(BUCKET, "../secrets"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.exists(BUCKET, ".."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankKeysAndBadBuckets_areRejected() {
        byte[] bytes = {1};
        assertThatThrownBy(() -> store.put(BUCKET, "  ",
                new ByteArrayInputStream(bytes), bytes.length, "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.exists("../not-a-bucket", "key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid bucket name");
        assertThatThrownBy(() -> store.exists("UPPER", "key"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
