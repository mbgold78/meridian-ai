package com.meridian.substrate.app.blob;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the {@link BlobStore} backing implementation
 * ({@code substrate.blob-store.*}, renamed from homepoc's
 * {@code meridian.blob-store.*} per port-map §3).
 *
 * <p>Provider selection:
 * <ul>
 *   <li>{@code minio} — {@link MinioBlobStore} (compose / Docker-capable dev;
 *       requires endpoint + credentials).</li>
 *   <li>{@code local} — {@link LocalFilesystemBlobStore} (the no-Docker dev
 *       fallback; writes under {@code build/blob-store} by default — NEVER a
 *       {@code data/} directory, which is gitignored repo-wide).</li>
 *   <li>unset — {@link UnconfiguredBlobStore} no-op (boots fine, throws on
 *       first use). Safe default so a missing key never breaks boot.</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "substrate.blob-store")
public class BlobStoreProperties {

    /** Provider id: {@code minio} | {@code local} | unset (no-op). */
    private String provider;

    /** Default bucket created on startup if missing — used by branding asset uploads. */
    private String defaultBucket = "substrate-dev";

    private final Minio minio = new Minio();

    private final Local local = new Local();

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getDefaultBucket() { return defaultBucket; }
    public void setDefaultBucket(String defaultBucket) { this.defaultBucket = defaultBucket; }

    public Minio getMinio() { return minio; }

    public Local getLocal() { return local; }

    public static class Minio {
        private String endpoint;
        private String accessKey;
        private String secretKey;

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String accessKey) { this.accessKey = accessKey; }

        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    }

    public static class Local {
        /**
         * Root directory for the local-filesystem dev fallback. Defaults to a
         * path under the Gradle {@code build/} dir so blobs are wiped by
         * {@code gradlew clean} and never land in a gitignored {@code data/}
         * directory (port-map global convention).
         */
        private String baseDir = "build/blob-store";

        public String getBaseDir() { return baseDir; }
        public void setBaseDir(String baseDir) { this.baseDir = baseDir; }
    }
}
