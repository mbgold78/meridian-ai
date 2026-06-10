package com.meridian.substrate.app.branding;

/**
 * Response shape for {@code POST /api/v1/branding/assets}. {@code blobKey} is
 * the value the caller persists onto a {@code BrandingConfig} field
 * ({@code logoPrimaryBlobKey} etc.).
 */
public record BrandingAssetUploadResponse(
        String bucket,
        String blobKey,
        String contentType,
        long contentLength
) {}
