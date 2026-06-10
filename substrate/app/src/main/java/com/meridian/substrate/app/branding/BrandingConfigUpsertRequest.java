package com.meridian.substrate.app.branding;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.BrandingConfigStatus;

import java.util.UUID;

/**
 * Upsert payload for {@link com.meridian.substrate.common.domain.BrandingConfig}.
 * All optional fields default to {@code null} → service either keeps the
 * existing value (PATCH semantics) or, on create, leaves the column null.
 *
 * <p>Required on create: {@code domainId}, {@code appDisplayName},
 * {@code colorTokensLight}, {@code colorTokensDark}, {@code fontBody},
 * {@code fontHeading}, {@code fontMono}, {@code terminologyOverrides},
 * {@code status}.
 */
public record BrandingConfigUpsertRequest(
        UUID domainId,
        String appDisplayName,
        String tagline,

        JsonNode colorTokensLight,
        JsonNode colorTokensDark,

        String fontBody,
        String fontHeading,
        String fontMono,

        JsonNode spacingTokens,
        JsonNode radiusTokens,
        JsonNode shadowTokens,

        String logoPrimaryBlobKey,
        String logoMarkBlobKey,
        String logoLightBlobKey,
        String logoDarkBlobKey,
        String faviconBlobKey,
        String loginSplashBlobKey,

        JsonNode terminologyOverrides,

        String emailSenderAddress,
        String emailSenderName,
        String emailReplyTo,
        String emailHeaderHtml,
        String emailFooterHtml,
        String emailPrimaryColor,

        String legalFooterText,
        String privacyPolicyUrl,
        String termsOfServiceUrl,

        String customCssUrl,

        BrandingConfigStatus status
) {}
