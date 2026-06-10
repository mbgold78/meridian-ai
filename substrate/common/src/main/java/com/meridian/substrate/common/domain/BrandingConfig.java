package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.BrandingConfigStatus;
import jakarta.persistence.*;

import java.util.UUID;

/**
 * Per-Domain branding and terminology configuration. The single source of
 * truth for white-label rendering — colors, typography, logos, terminology
 * overrides, email-template chrome, legal links.
 *
 * <p>Architectural contract (P15 + P16):
 * <ul>
 *   <li><b>One ACTIVE config per Domain.</b> Enforced service-side by
 *       {@code BrandingConfigService.demoteCurrentActive(...)}. The
 *       structurally correct DB-level partial unique index
 *       ({@code WHERE status = 'ACTIVE'}) is rejected by H2 in this build;
 *       see {@code docs/phase2b1-observations.md} for the Postgres-only
 *       follow-up. DRAFT configs may coexist; the {@code ThemeProvider}
 *       only resolves the ACTIVE row at boot.</li>
 *   <li><b>Color tokens are authoritative as JSON maps.</b> Light and dark
 *       variants ship as separate token maps; the frontend maps these into
 *       CSS custom properties on {@code <html>}. The schema does not encode
 *       the token names — that is the {@code tokens.css} contract on the
 *       frontend.</li>
 *   <li><b>Asset references are blob keys, not URLs.</b> The {@link
 *       com.meridian.substrate.app.infrastructure.storage.BlobStore} owns
 *       resolution; the controller serves assets through a signed-URL or
 *       streaming endpoint downstream of these keys.</li>
 *   <li><b>Email branding fields are present but not consumed in 2B-1.</b>
 *       The Phase 3 Human reader work consumes {@code emailSenderAddress},
 *       {@code emailHeaderHtml}, etc. The schema is complete now to avoid
 *       a migration when that work lands.</li>
 * </ul>
 */
@Entity
@Table(name = "branding_configs", indexes = {
        @Index(name = "idx_branding_configs_domain_id", columnList = "domain_id"),
        @Index(name = "idx_branding_configs_status", columnList = "status")
})
public class BrandingConfig extends BaseEntity {

    @Column(name = "domain_id", nullable = false)
    private UUID domainId;

    // ---- Identity ----------------------------------------------------------

    @Column(name = "app_display_name", nullable = false, length = 255)
    private String appDisplayName;

    @Column(name = "tagline", length = 512)
    private String tagline;

    // ---- Color tokens (JSON) ----------------------------------------------

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "color_tokens_light", columnDefinition = "TEXT", nullable = false)
    private JsonNode colorTokensLight;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "color_tokens_dark", columnDefinition = "TEXT", nullable = false)
    private JsonNode colorTokensDark;

    // ---- Typography --------------------------------------------------------

    @Column(name = "font_body", nullable = false, length = 255)
    private String fontBody;

    @Column(name = "font_heading", nullable = false, length = 255)
    private String fontHeading;

    @Column(name = "font_mono", nullable = false, length = 255)
    private String fontMono;

    // ---- Spacing / radius / shadow (optional overrides) -------------------

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "spacing_tokens", columnDefinition = "TEXT")
    private JsonNode spacingTokens;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "radius_tokens", columnDefinition = "TEXT")
    private JsonNode radiusTokens;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "shadow_tokens", columnDefinition = "TEXT")
    private JsonNode shadowTokens;

    // ---- Asset blob keys ---------------------------------------------------

    @Column(name = "logo_primary_blob_key", length = 255)
    private String logoPrimaryBlobKey;

    @Column(name = "logo_mark_blob_key", length = 255)
    private String logoMarkBlobKey;

    @Column(name = "logo_light_blob_key", length = 255)
    private String logoLightBlobKey;

    @Column(name = "logo_dark_blob_key", length = 255)
    private String logoDarkBlobKey;

    @Column(name = "favicon_blob_key", length = 255)
    private String faviconBlobKey;

    @Column(name = "login_splash_blob_key", length = 255)
    private String loginSplashBlobKey;

    // ---- Terminology (JSON map: key -> {singular, plural}) ----------------

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "terminology_overrides", columnDefinition = "TEXT", nullable = false)
    private JsonNode terminologyOverrides;

    // ---- Email branding (defined, NOT consumed in 2B-1) -------------------

    @Column(name = "email_sender_address", length = 255)
    private String emailSenderAddress;

    @Column(name = "email_sender_name", length = 255)
    private String emailSenderName;

    @Column(name = "email_reply_to", length = 255)
    private String emailReplyTo;

    @Column(name = "email_header_html", columnDefinition = "TEXT")
    private String emailHeaderHtml;

    @Column(name = "email_footer_html", columnDefinition = "TEXT")
    private String emailFooterHtml;

    @Column(name = "email_primary_color", length = 32)
    private String emailPrimaryColor;

    // ---- Legal -------------------------------------------------------------

    @Column(name = "legal_footer_text", columnDefinition = "TEXT")
    private String legalFooterText;

    @Column(name = "privacy_policy_url", length = 512)
    private String privacyPolicyUrl;

    @Column(name = "terms_of_service_url", length = 512)
    private String termsOfServiceUrl;

    // ---- Advanced ----------------------------------------------------------

    @Column(name = "custom_css_url", length = 512)
    private String customCssUrl;

    // ---- Status ------------------------------------------------------------

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private BrandingConfigStatus status = BrandingConfigStatus.DRAFT;

    // ---- accessors ---------------------------------------------------------

    public UUID getDomainId() { return domainId; }
    public void setDomainId(UUID domainId) { this.domainId = domainId; }

    public String getAppDisplayName() { return appDisplayName; }
    public void setAppDisplayName(String appDisplayName) { this.appDisplayName = appDisplayName; }

    public String getTagline() { return tagline; }
    public void setTagline(String tagline) { this.tagline = tagline; }

    public JsonNode getColorTokensLight() { return colorTokensLight; }
    public void setColorTokensLight(JsonNode colorTokensLight) { this.colorTokensLight = colorTokensLight; }

    public JsonNode getColorTokensDark() { return colorTokensDark; }
    public void setColorTokensDark(JsonNode colorTokensDark) { this.colorTokensDark = colorTokensDark; }

    public String getFontBody() { return fontBody; }
    public void setFontBody(String fontBody) { this.fontBody = fontBody; }

    public String getFontHeading() { return fontHeading; }
    public void setFontHeading(String fontHeading) { this.fontHeading = fontHeading; }

    public String getFontMono() { return fontMono; }
    public void setFontMono(String fontMono) { this.fontMono = fontMono; }

    public JsonNode getSpacingTokens() { return spacingTokens; }
    public void setSpacingTokens(JsonNode spacingTokens) { this.spacingTokens = spacingTokens; }

    public JsonNode getRadiusTokens() { return radiusTokens; }
    public void setRadiusTokens(JsonNode radiusTokens) { this.radiusTokens = radiusTokens; }

    public JsonNode getShadowTokens() { return shadowTokens; }
    public void setShadowTokens(JsonNode shadowTokens) { this.shadowTokens = shadowTokens; }

    public String getLogoPrimaryBlobKey() { return logoPrimaryBlobKey; }
    public void setLogoPrimaryBlobKey(String logoPrimaryBlobKey) { this.logoPrimaryBlobKey = logoPrimaryBlobKey; }

    public String getLogoMarkBlobKey() { return logoMarkBlobKey; }
    public void setLogoMarkBlobKey(String logoMarkBlobKey) { this.logoMarkBlobKey = logoMarkBlobKey; }

    public String getLogoLightBlobKey() { return logoLightBlobKey; }
    public void setLogoLightBlobKey(String logoLightBlobKey) { this.logoLightBlobKey = logoLightBlobKey; }

    public String getLogoDarkBlobKey() { return logoDarkBlobKey; }
    public void setLogoDarkBlobKey(String logoDarkBlobKey) { this.logoDarkBlobKey = logoDarkBlobKey; }

    public String getFaviconBlobKey() { return faviconBlobKey; }
    public void setFaviconBlobKey(String faviconBlobKey) { this.faviconBlobKey = faviconBlobKey; }

    public String getLoginSplashBlobKey() { return loginSplashBlobKey; }
    public void setLoginSplashBlobKey(String loginSplashBlobKey) { this.loginSplashBlobKey = loginSplashBlobKey; }

    public JsonNode getTerminologyOverrides() { return terminologyOverrides; }
    public void setTerminologyOverrides(JsonNode terminologyOverrides) { this.terminologyOverrides = terminologyOverrides; }

    public String getEmailSenderAddress() { return emailSenderAddress; }
    public void setEmailSenderAddress(String emailSenderAddress) { this.emailSenderAddress = emailSenderAddress; }

    public String getEmailSenderName() { return emailSenderName; }
    public void setEmailSenderName(String emailSenderName) { this.emailSenderName = emailSenderName; }

    public String getEmailReplyTo() { return emailReplyTo; }
    public void setEmailReplyTo(String emailReplyTo) { this.emailReplyTo = emailReplyTo; }

    public String getEmailHeaderHtml() { return emailHeaderHtml; }
    public void setEmailHeaderHtml(String emailHeaderHtml) { this.emailHeaderHtml = emailHeaderHtml; }

    public String getEmailFooterHtml() { return emailFooterHtml; }
    public void setEmailFooterHtml(String emailFooterHtml) { this.emailFooterHtml = emailFooterHtml; }

    public String getEmailPrimaryColor() { return emailPrimaryColor; }
    public void setEmailPrimaryColor(String emailPrimaryColor) { this.emailPrimaryColor = emailPrimaryColor; }

    public String getLegalFooterText() { return legalFooterText; }
    public void setLegalFooterText(String legalFooterText) { this.legalFooterText = legalFooterText; }

    public String getPrivacyPolicyUrl() { return privacyPolicyUrl; }
    public void setPrivacyPolicyUrl(String privacyPolicyUrl) { this.privacyPolicyUrl = privacyPolicyUrl; }

    public String getTermsOfServiceUrl() { return termsOfServiceUrl; }
    public void setTermsOfServiceUrl(String termsOfServiceUrl) { this.termsOfServiceUrl = termsOfServiceUrl; }

    public String getCustomCssUrl() { return customCssUrl; }
    public void setCustomCssUrl(String customCssUrl) { this.customCssUrl = customCssUrl; }

    public BrandingConfigStatus getStatus() { return status; }
    public void setStatus(BrandingConfigStatus status) { this.status = status; }
}
