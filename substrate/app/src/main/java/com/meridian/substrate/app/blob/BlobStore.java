package com.meridian.substrate.app.blob;

import java.io.InputStream;
import java.util.Optional;

/**
 * S3-compatible blob storage abstraction. MinIO in compose/dev-with-Docker,
 * local filesystem on the no-Docker dev machine, S3/Azure Blob in prod
 * (production object-store decision open — port-map S11). First consumer:
 * branding asset uploads ({@code POST /api/v1/branding/assets}).
 */
public interface BlobStore {

    /**
     * Store bytes at {@code key} in {@code bucket}. Returns the canonical URI
     * the caller should persist for later retrieval.
     */
    String put(String bucket, String key, InputStream content, long contentLength, String contentType);

    /**
     * Fetch bytes previously stored at {@code bucket}/{@code key}.
     */
    Optional<BlobHandle> get(String bucket, String key);

    /**
     * Delete the object at {@code bucket}/{@code key}. Returns true if an
     * object was removed, false if it did not exist.
     */
    boolean delete(String bucket, String key);

    /**
     * Lightweight existence check without fetching content.
     */
    boolean exists(String bucket, String key);

    record BlobHandle(
            String bucket,
            String key,
            String contentType,
            long contentLength,
            InputStream content
    ) {}
}
