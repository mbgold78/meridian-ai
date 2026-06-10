package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.app.blob.BlobStore;
import com.meridian.substrate.app.blob.BlobStoreProperties;
import com.meridian.substrate.app.branding.BrandingAdminAuthorizer;
import com.meridian.substrate.app.branding.BrandingAssetUploadResponse;
import com.meridian.substrate.app.branding.BrandingConfigUpsertRequest;
import com.meridian.substrate.app.branding.service.BrandingConfigService;
import com.meridian.substrate.common.domain.BrandingConfig;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * BrandingConfig CRUD + branding asset upload.
 *
 * <p>Reads ({@code GET /api/v1/branding/configs/active?domainId=...}) are open
 * to any persona — every authenticated session needs the active branding to
 * render. Writes (create/update/activate) and asset uploads are gated by
 * {@link BrandingAdminAuthorizer} → require {@code X-Persona-Level: ADMIN}.
 *
 * <p>The asset endpoint accepts multipart uploads only; allowed content types
 * cover the asset surface defined on {@code BrandingConfig} (PNG/SVG/JPEG/ICO).
 * Returned {@code blobKey} is what the caller PATCHes onto the corresponding
 * {@code BrandingConfig} field — the upload itself does not mutate any
 * BrandingConfig row, by design (one round-trip per concern).
 */
@RestController
@RequestMapping("/api/v1/branding")
public class BrandingController {

    private static final Set<String> ALLOWED_ASSET_CONTENT_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "image/svg+xml",
            "image/webp",
            "image/x-icon",
            "image/vnd.microsoft.icon"
    );
    private static final long MAX_ASSET_BYTES = 5L * 1024 * 1024; // 5 MiB

    private final BrandingConfigService service;
    private final BrandingAdminAuthorizer authorizer;
    private final BlobStore blobStore;
    private final BlobStoreProperties blobStoreProperties;

    public BrandingController(BrandingConfigService service,
                              BrandingAdminAuthorizer authorizer,
                              BlobStore blobStore,
                              BlobStoreProperties blobStoreProperties) {
        this.service = service;
        this.authorizer = authorizer;
        this.blobStore = blobStore;
        this.blobStoreProperties = blobStoreProperties;
    }

    // ---- BrandingConfig CRUD ------------------------------------------------

    @GetMapping("/configs")
    public List<BrandingConfig> listByDomain(@RequestParam UUID domainId) {
        return service.listByDomain(domainId);
    }

    @GetMapping("/configs/{id}")
    public BrandingConfig get(@PathVariable UUID id) {
        return service.get(id);
    }

    /**
     * Resolve the active config for a domain — the boot-time call from the
     * frontend ThemeProvider. Returns the persisted ACTIVE row if present,
     * otherwise a transient default (not persisted as a side effect).
     */
    @GetMapping("/configs/active")
    public BrandingConfig getActive(@RequestParam UUID domainId) {
        return service.resolveActiveOrDefault(domainId);
    }

    @PostMapping("/configs")
    public ResponseEntity<BrandingConfig> create(@Valid @RequestBody BrandingConfigUpsertRequest req) {
        authorizer.requireAdmin();
        BrandingConfig saved = service.create(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PatchMapping("/configs/{id}")
    public BrandingConfig update(@PathVariable UUID id, @RequestBody BrandingConfigUpsertRequest req) {
        authorizer.requireAdmin();
        return service.update(id, req);
    }

    @PostMapping("/configs/{id}/activate")
    public BrandingConfig activate(@PathVariable UUID id) {
        authorizer.requireAdmin();
        return service.activate(id);
    }

    // ---- Asset upload -------------------------------------------------------

    @PostMapping(value = "/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BrandingAssetUploadResponse> uploadAsset(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "keyPrefix", defaultValue = "branding") String keyPrefix) throws IOException {

        authorizer.requireAdmin();

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Asset upload requires a non-empty 'file' part");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_ASSET_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException(
                    "Unsupported asset content type '" + contentType + "'. Allowed: "
                            + ALLOWED_ASSET_CONTENT_TYPES);
        }
        if (file.getSize() > MAX_ASSET_BYTES) {
            throw new IllegalArgumentException(
                    "Asset exceeds " + MAX_ASSET_BYTES + " bytes (received " + file.getSize() + ")");
        }

        String safePrefix = keyPrefix.replaceAll("[^a-zA-Z0-9._\\-/]", "_");
        String key = safePrefix + "/" + UUID.randomUUID() + extensionFor(contentType, file.getOriginalFilename());
        String bucket = blobStoreProperties.getDefaultBucket();

        blobStore.put(bucket, key, file.getInputStream(), file.getSize(), contentType);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new BrandingAssetUploadResponse(bucket, key, contentType, file.getSize()));
    }

    private String extensionFor(String contentType, String originalFilename) {
        if (originalFilename != null) {
            int dot = originalFilename.lastIndexOf('.');
            if (dot > 0 && dot < originalFilename.length() - 1) {
                String ext = originalFilename.substring(dot).toLowerCase();
                if (ext.matches("\\.[a-z0-9]{2,5}")) {
                    return ext;
                }
            }
        }
        return switch (contentType.toLowerCase()) {
            case "image/png"                                  -> ".png";
            case "image/jpeg"                                 -> ".jpg";
            case "image/svg+xml"                              -> ".svg";
            case "image/webp"                                 -> ".webp";
            case "image/x-icon", "image/vnd.microsoft.icon"   -> ".ico";
            default                                           -> "";
        };
    }
}
