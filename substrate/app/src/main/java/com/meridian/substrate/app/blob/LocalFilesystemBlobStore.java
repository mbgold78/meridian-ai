package com.meridian.substrate.app.blob;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Local-filesystem {@link BlobStore} — the dev fallback for the no-Docker dev
 * machine (port-map §4 constraint: this machine cannot run MinIO/compose).
 * Active when {@code substrate.blob-store.provider=local}.
 *
 * <p><b>Storage layout.</b> Object bytes land at
 * {@code {base-dir}/{bucket}/{key}}; the content type is recorded in a
 * parallel metadata tree at {@code {base-dir}/.meta/{bucket}/{key}.ctype}
 * (parallel tree rather than a sidecar next to the object so a metadata file
 * can never collide with a real object key).
 *
 * <p><b>base-dir default is {@code build/blob-store}</b> — inside the Gradle
 * build dir, wiped by {@code gradlew clean}. Deliberately NEVER a {@code data/}
 * directory: {@code data/} is gitignored repo-wide and reserved (port-map
 * global convention), and homepoc's {@code ./data} split-brain is a named
 * fixture seam (S16/S19).
 *
 * <p><b>Not a production store.</b> No durability guarantees, no concurrent
 * multi-writer safety beyond atomic-move semantics on put. Production is
 * MinIO/S3 behind the same interface (S11).
 */
@Component
@ConditionalOnProperty(prefix = "substrate.blob-store", name = "provider", havingValue = "local")
public class LocalFilesystemBlobStore implements BlobStore {

    private static final Logger log = LoggerFactory.getLogger(LocalFilesystemBlobStore.class);

    /** Conservative S3-style bucket-name shape; rejects path separators outright. */
    private static final Pattern BUCKET_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{1,62}");

    private static final String META_DIR = ".meta";
    private static final String CONTENT_TYPE_SUFFIX = ".ctype";
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final BlobStoreProperties properties;
    private final Path baseDir;

    public LocalFilesystemBlobStore(BlobStoreProperties properties) {
        this.properties = properties;
        String configured = properties.getLocal().getBaseDir();
        if (configured == null || configured.isBlank()) {
            configured = "build/blob-store";
        }
        this.baseDir = Path.of(configured).toAbsolutePath().normalize();
    }

    @PostConstruct
    void ensureBaseDir() {
        try {
            Files.createDirectories(baseDir.resolve(properties.getDefaultBucket()));
            log.info("[blob-store] Local filesystem blob store at '{}' (default bucket '{}')",
                    baseDir, properties.getDefaultBucket());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to create local blob-store base dir " + baseDir, e);
        }
    }

    @Override
    public String put(String bucket, String key, InputStream content, long contentLength, String contentType) {
        Path objectPath = resolveObjectPath(bucket, key);
        Path metaPath = resolveMetaPath(bucket, key);
        try {
            Files.createDirectories(objectPath.getParent());
            Files.createDirectories(metaPath.getParent());
            // Write to a temp file in the destination directory, then move into
            // place so a reader never observes a half-written object.
            Path tmp = Files.createTempFile(objectPath.getParent(), ".upload-", ".tmp");
            try {
                Files.copy(content, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, objectPath, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
            Files.writeString(metaPath,
                    contentType == null ? DEFAULT_CONTENT_TYPE : contentType,
                    StandardCharsets.UTF_8);
            return bucket + "/" + key;
        } catch (IOException e) {
            throw new IllegalStateException("Local blob put failed for " + bucket + "/" + key, e);
        }
    }

    @Override
    public Optional<BlobHandle> get(String bucket, String key) {
        Path objectPath = resolveObjectPath(bucket, key);
        if (!Files.isRegularFile(objectPath)) {
            return Optional.empty();
        }
        try {
            String contentType = readContentType(bucket, key);
            long size = Files.size(objectPath);
            InputStream stream = new BufferedInputStream(Files.newInputStream(objectPath));
            return Optional.of(new BlobHandle(bucket, key, contentType, size, stream));
        } catch (IOException e) {
            throw new IllegalStateException("Local blob get failed for " + bucket + "/" + key, e);
        }
    }

    @Override
    public boolean delete(String bucket, String key) {
        Path objectPath = resolveObjectPath(bucket, key);
        Path metaPath = resolveMetaPath(bucket, key);
        try {
            boolean removed = Files.deleteIfExists(objectPath);
            Files.deleteIfExists(metaPath);
            return removed;
        } catch (IOException e) {
            throw new IllegalStateException("Local blob delete failed for " + bucket + "/" + key, e);
        }
    }

    @Override
    public boolean exists(String bucket, String key) {
        return Files.isRegularFile(resolveObjectPath(bucket, key));
    }

    // ---- path resolution ----------------------------------------------------

    private String readContentType(String bucket, String key) throws IOException {
        Path metaPath = resolveMetaPath(bucket, key);
        if (!Files.isRegularFile(metaPath)) {
            return DEFAULT_CONTENT_TYPE;
        }
        String value = Files.readString(metaPath, StandardCharsets.UTF_8).trim();
        return value.isEmpty() ? DEFAULT_CONTENT_TYPE : value;
    }

    private Path resolveObjectPath(String bucket, String key) {
        Path bucketRoot = baseDir.resolve(validBucket(bucket));
        return confine(bucketRoot, key);
    }

    private Path resolveMetaPath(String bucket, String key) {
        Path metaRoot = baseDir.resolve(META_DIR).resolve(validBucket(bucket));
        return confine(metaRoot, key + CONTENT_TYPE_SUFFIX);
    }

    private String validBucket(String bucket) {
        if (bucket == null || !BUCKET_NAME.matcher(bucket).matches()) {
            throw new IllegalArgumentException("Invalid bucket name '" + bucket + "'");
        }
        return bucket;
    }

    /**
     * Resolve {@code key} under {@code root}, rejecting any key whose
     * normalized path escapes the root (e.g. {@code ../../etc/passwd}) or that
     * is empty/absolute. Keys are S3-style and may contain {@code /} segments.
     */
    private Path confine(Path root, String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Blob key must be non-blank");
        }
        Path candidate = root.resolve(key).normalize();
        if (!candidate.startsWith(root) || candidate.equals(root)) {
            throw new IllegalArgumentException(
                    "Blob key '" + key + "' resolves outside its bucket — rejected");
        }
        return candidate;
    }
}
