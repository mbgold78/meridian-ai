package com.meridian.substrate.app.branding.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.app.branding.BrandingConfigUpsertRequest;
import com.meridian.substrate.app.branding.BrandingDefaults;
import com.meridian.substrate.app.core.exception.EntityNotFoundException;
import com.meridian.substrate.common.domain.BrandingConfig;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.enums.BrandingConfigStatus;
import com.meridian.substrate.common.repo.BrandingConfigRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CRUD + lookup for {@link BrandingConfig}. The service enforces the
 * "one ACTIVE per Domain" invariant by demoting any prior ACTIVE row in the
 * same domain to DRAFT before promoting a new one — see {@link #activate(UUID)}
 * and the {@code create}/{@code update} paths below.
 *
 * <p><b>Concurrency — two-level pessimistic locking in
 * {@link #demoteCurrentActive(UUID, UUID)}:</b>
 * <ol>
 *   <li><b>Domain-row lock (outer).</b> {@code PESSIMISTIC_WRITE} on the
 *       {@code Domain} row, acquired first. The Domain row is stable across
 *       the activate transaction (its state does not change), so locking it
 *       serializes all activate operations per-domain regardless of
 *       {@code BrandingConfig} state transitions happening inside the
 *       transaction. This closes the interleaving where the
 *       predicate-matching ACTIVE row gets demoted mid-flight and the second
 *       writer's locked-row query returns empty on unblock.</li>
 *   <li><b>BrandingConfig ACTIVE-row lock (inner).</b>
 *       {@code findFirstByDomainIdAndStatusForUpdate} acquires a
 *       {@code PESSIMISTIC_WRITE} row lock on the current ACTIVE row (if any).
 *       Redundant with the Domain-row lock for the specific interleaving
 *       above, but kept as defense-in-depth against other interleavings
 *       (two writers racing to demote the same row before either commits).</li>
 * </ol>
 *
 * <p>Order matters: the Domain-row lock MUST be acquired before the
 * BrandingConfig lookup, otherwise the second writer can read stale state
 * before blocking. On Postgres the partial unique index
 * {@code uq_branding_configs_active_per_domain}
 * (db/migration/postgresql/V2_1) backstops the invariant at the DB level;
 * H2 rejects partial indexes, so on the dev profile the invariant stays
 * service-enforced (homepoc {@code docs/phase2b1-observations.md} has the
 * full interleaving analysis).
 */
@Service
public class BrandingConfigService {

    private static final Logger log = LoggerFactory.getLogger(BrandingConfigService.class);

    private final BrandingConfigRepository repository;
    private final ObjectMapper mapper;

    @PersistenceContext
    private EntityManager entityManager;

    public BrandingConfigService(BrandingConfigRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public BrandingConfig get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("BrandingConfig", id));
    }

    @Transactional(readOnly = true)
    public List<BrandingConfig> listByDomain(UUID domainId) {
        return repository.findByDomainId(domainId);
    }

    @Transactional(readOnly = true)
    public Optional<BrandingConfig> findActiveForDomain(UUID domainId) {
        return repository.findFirstByDomainIdAndStatus(domainId, BrandingConfigStatus.ACTIVE);
    }

    /**
     * Resolve the config that should be applied at app boot for a domain. If
     * no ACTIVE config exists, returns a transient default — never persists
     * one as a side effect of a read. The demo-pack seeder is the only path
     * that persists a default.
     */
    @Transactional(readOnly = true)
    public BrandingConfig resolveActiveOrDefault(UUID domainId) {
        return findActiveForDomain(domainId)
                .orElseGet(() -> BrandingDefaults.newDefaultFor(domainId, mapper));
    }

    @Transactional
    public BrandingConfig create(BrandingConfigUpsertRequest req) {
        BrandingConfig c = new BrandingConfig();
        c.setDomainId(req.domainId());
        applyRequiredFieldsOnCreate(c, req);
        applyOptionalFields(c, req);
        c.setStatus(req.status() == null ? BrandingConfigStatus.DRAFT : req.status());
        c.setCreatedBy("api");

        if (c.getStatus() == BrandingConfigStatus.ACTIVE) {
            demoteCurrentActive(req.domainId(), null);
        }

        try {
            BrandingConfig saved = repository.save(c);
            log.info("Created BrandingConfig [id={}, domainId={}, status={}]",
                    saved.getId(), saved.getDomainId(), saved.getStatus());
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException(
                    "Failed to persist BrandingConfig — likely a duplicate ACTIVE config for domain "
                            + req.domainId(), e);
        }
    }

    @Transactional
    public BrandingConfig update(UUID id, BrandingConfigUpsertRequest req) {
        BrandingConfig c = get(id);
        applyOptionalFields(c, req);

        // Apply non-null required-on-create fields opportunistically (PATCH semantics).
        if (req.appDisplayName() != null) c.setAppDisplayName(req.appDisplayName());
        if (req.colorTokensLight() != null) c.setColorTokensLight(req.colorTokensLight());
        if (req.colorTokensDark() != null) c.setColorTokensDark(req.colorTokensDark());
        if (req.fontBody() != null) c.setFontBody(req.fontBody());
        if (req.fontHeading() != null) c.setFontHeading(req.fontHeading());
        if (req.fontMono() != null) c.setFontMono(req.fontMono());
        if (req.terminologyOverrides() != null) c.setTerminologyOverrides(req.terminologyOverrides());

        if (req.status() != null && req.status() != c.getStatus()) {
            if (req.status() == BrandingConfigStatus.ACTIVE) {
                demoteCurrentActive(c.getDomainId(), c.getId());
            }
            c.setStatus(req.status());
        }

        return repository.save(c);
    }

    /**
     * Promote a DRAFT to ACTIVE, demoting any existing ACTIVE row in the
     * same domain to DRAFT atomically.
     */
    @Transactional
    public BrandingConfig activate(UUID id) {
        BrandingConfig c = get(id);
        if (c.getStatus() == BrandingConfigStatus.ACTIVE) {
            return c;
        }
        demoteCurrentActive(c.getDomainId(), c.getId());
        c.setStatus(BrandingConfigStatus.ACTIVE);
        return repository.save(c);
    }

    private void demoteCurrentActive(UUID domainId, UUID excludeId) {
        // 1. Outer lock: PESSIMISTIC_WRITE on the Domain row. MUST be first —
        //    this serializes all concurrent activates for this domain
        //    regardless of what happens to BrandingConfig rows inside the
        //    transaction. Without this, two concurrent activates could each
        //    lock the current ACTIVE BrandingConfig, demote it, promote
        //    different targets, and both commit — two ACTIVE rows result.
        //    See class Javadoc.
        entityManager.find(Domain.class, domainId, LockModeType.PESSIMISTIC_WRITE);

        // 2. Inner lock: PESSIMISTIC_WRITE on the current ACTIVE BrandingConfig
        //    row (if any). Redundant with the Domain lock for the primary
        //    race, defense-in-depth against other interleavings.
        repository.findFirstByDomainIdAndStatusForUpdate(domainId, BrandingConfigStatus.ACTIVE)
                .filter(existing -> excludeId == null || !existing.getId().equals(excludeId))
                .ifPresent(existing -> {
                    existing.setStatus(BrandingConfigStatus.DRAFT);
                    repository.save(existing);
                    log.info("Demoted prior ACTIVE BrandingConfig [id={}, domainId={}] to DRAFT",
                            existing.getId(), existing.getDomainId());
                });
    }

    private void applyRequiredFieldsOnCreate(BrandingConfig c, BrandingConfigUpsertRequest req) {
        if (req.appDisplayName() == null
                || req.colorTokensLight() == null
                || req.colorTokensDark() == null
                || req.fontBody() == null
                || req.fontHeading() == null
                || req.fontMono() == null
                || req.terminologyOverrides() == null) {
            throw new IllegalArgumentException(
                    "BrandingConfig create requires appDisplayName, color tokens (light + dark), "
                            + "all three font families, and terminologyOverrides");
        }
        c.setAppDisplayName(req.appDisplayName());
        c.setColorTokensLight(req.colorTokensLight());
        c.setColorTokensDark(req.colorTokensDark());
        c.setFontBody(req.fontBody());
        c.setFontHeading(req.fontHeading());
        c.setFontMono(req.fontMono());
        c.setTerminologyOverrides(req.terminologyOverrides());
    }

    private void applyOptionalFields(BrandingConfig c, BrandingConfigUpsertRequest req) {
        if (req.tagline() != null) c.setTagline(req.tagline());
        if (req.spacingTokens() != null) c.setSpacingTokens(req.spacingTokens());
        if (req.radiusTokens() != null) c.setRadiusTokens(req.radiusTokens());
        if (req.shadowTokens() != null) c.setShadowTokens(req.shadowTokens());
        if (req.logoPrimaryBlobKey() != null) c.setLogoPrimaryBlobKey(req.logoPrimaryBlobKey());
        if (req.logoMarkBlobKey() != null) c.setLogoMarkBlobKey(req.logoMarkBlobKey());
        if (req.logoLightBlobKey() != null) c.setLogoLightBlobKey(req.logoLightBlobKey());
        if (req.logoDarkBlobKey() != null) c.setLogoDarkBlobKey(req.logoDarkBlobKey());
        if (req.faviconBlobKey() != null) c.setFaviconBlobKey(req.faviconBlobKey());
        if (req.loginSplashBlobKey() != null) c.setLoginSplashBlobKey(req.loginSplashBlobKey());
        if (req.emailSenderAddress() != null) c.setEmailSenderAddress(req.emailSenderAddress());
        if (req.emailSenderName() != null) c.setEmailSenderName(req.emailSenderName());
        if (req.emailReplyTo() != null) c.setEmailReplyTo(req.emailReplyTo());
        if (req.emailHeaderHtml() != null) c.setEmailHeaderHtml(req.emailHeaderHtml());
        if (req.emailFooterHtml() != null) c.setEmailFooterHtml(req.emailFooterHtml());
        if (req.emailPrimaryColor() != null) c.setEmailPrimaryColor(req.emailPrimaryColor());
        if (req.legalFooterText() != null) c.setLegalFooterText(req.legalFooterText());
        if (req.privacyPolicyUrl() != null) c.setPrivacyPolicyUrl(req.privacyPolicyUrl());
        if (req.termsOfServiceUrl() != null) c.setTermsOfServiceUrl(req.termsOfServiceUrl());
        if (req.customCssUrl() != null) c.setCustomCssUrl(req.customCssUrl());
    }
}
