package com.meridian.substrate.app.blob;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Optional;

/**
 * S3-compatible {@link BlobStore} backed by MinIO. Active when
 * {@code substrate.blob-store.provider=minio}. Suppresses the
 * {@link UnconfiguredBlobStore} no-op via the {@code @ConditionalOnMissingBean}
 * guard on that class.
 *
 * <p>The default bucket is created lazily at startup if missing — keeps dev
 * setup to "run MinIO, set the property" without a manual bucket-create step.
 */
@Component
@ConditionalOnProperty(prefix = "substrate.blob-store", name = "provider", havingValue = "minio")
public class MinioBlobStore implements BlobStore {

    private static final Logger log = LoggerFactory.getLogger(MinioBlobStore.class);

    private final BlobStoreProperties properties;
    private final MinioClient client;

    public MinioBlobStore(BlobStoreProperties properties) {
        this.properties = properties;
        BlobStoreProperties.Minio m = properties.getMinio();
        if (m.getEndpoint() == null || m.getAccessKey() == null || m.getSecretKey() == null) {
            throw new IllegalStateException(
                    "substrate.blob-store.provider=minio but endpoint/access-key/secret-key are not all set");
        }
        this.client = MinioClient.builder()
                .endpoint(m.getEndpoint())
                .credentials(m.getAccessKey(), m.getSecretKey())
                .build();
    }

    @PostConstruct
    void ensureDefaultBucket() {
        try {
            String bucket = properties.getDefaultBucket();
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("[blob-store] Created default MinIO bucket '{}'", bucket);
            } else {
                log.info("[blob-store] Default MinIO bucket '{}' already exists", bucket);
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to verify/create default MinIO bucket — is the MinIO server reachable at "
                            + properties.getMinio().getEndpoint() + "?", e);
        }
    }

    @Override
    public String put(String bucket, String key, InputStream content, long contentLength, String contentType) {
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(content, contentLength, -1)
                    .contentType(contentType)
                    .build());
            return bucket + "/" + key;
        } catch (Exception e) {
            throw new IllegalStateException("MinIO put failed for " + bucket + "/" + key, e);
        }
    }

    @Override
    public Optional<BlobHandle> get(String bucket, String key) {
        try {
            StatObjectResponse stat = client.statObject(StatObjectArgs.builder()
                    .bucket(bucket).object(key).build());
            InputStream stream = client.getObject(GetObjectArgs.builder()
                    .bucket(bucket).object(key).build());
            return Optional.of(new BlobHandle(bucket, key, stat.contentType(), stat.size(), stream));
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return Optional.empty();
            }
            throw new IllegalStateException("MinIO get failed for " + bucket + "/" + key, e);
        } catch (Exception e) {
            throw new IllegalStateException("MinIO get failed for " + bucket + "/" + key, e);
        }
    }

    @Override
    public boolean delete(String bucket, String key) {
        if (!exists(bucket, key)) return false;
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
            return true;
        } catch (Exception e) {
            throw new IllegalStateException("MinIO delete failed for " + bucket + "/" + key, e);
        }
    }

    @Override
    public boolean exists(String bucket, String key) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return true;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return false;
            }
            throw new IllegalStateException("MinIO exists failed for " + bucket + "/" + key, e);
        } catch (Exception e) {
            throw new IllegalStateException("MinIO exists failed for " + bucket + "/" + key, e);
        }
    }
}
