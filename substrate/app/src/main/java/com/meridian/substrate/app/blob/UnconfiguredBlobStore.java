package com.meridian.substrate.app.blob;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.InputStream;
import java.util.Optional;

/**
 * Default no-op {@link BlobStore} bean used when no concrete implementation is
 * configured. Every call throws {@link IllegalStateException} — this is
 * intentional: with {@code substrate.blob-store.provider} unset the app must
 * boot cleanly (safe-default rule), but any attempt to actually store a blob
 * is a configuration bug that should fail loudly, not write to a surprise
 * location.
 *
 * <p>Concrete impls: {@link MinioBlobStore} ({@code provider=minio}) and
 * {@link LocalFilesystemBlobStore} ({@code provider=local} — the no-Docker dev
 * fallback under {@code build/blob-store}).
 */
@Configuration
public class UnconfiguredBlobStore {

    @Bean
    @ConditionalOnMissingBean(BlobStore.class)
    public BlobStore blobStore() {
        return new BlobStore() {
            @Override
            public String put(String bucket, String key, InputStream content, long contentLength, String contentType) {
                throw unconfigured();
            }

            @Override
            public Optional<BlobHandle> get(String bucket, String key) {
                throw unconfigured();
            }

            @Override
            public boolean delete(String bucket, String key) {
                throw unconfigured();
            }

            @Override
            public boolean exists(String bucket, String key) {
                throw unconfigured();
            }

            private IllegalStateException unconfigured() {
                return new IllegalStateException(
                        "BlobStore is not configured. Set substrate.blob-store.provider=local "
                                + "(dev filesystem fallback under build/blob-store) or "
                                + "substrate.blob-store.provider=minio with endpoint/credentials.");
            }
        };
    }
}
