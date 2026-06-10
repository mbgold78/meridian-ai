package com.meridian.substrate.app.core.service;

import com.meridian.substrate.app.api.v1.request.ReaderRegistrationCreateRequest;
import com.meridian.substrate.app.core.exception.EntityNotFoundException;
import com.meridian.substrate.common.domain.ReaderRegistration;
import com.meridian.substrate.common.domain.enums.ReaderKind;
import com.meridian.substrate.common.domain.enums.ReaderStatus;
import com.meridian.substrate.common.repo.ReaderRegistrationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * CRUD for ReaderRegistrations plus a Structured-reader helper that runs
 * {@link ProbeService} over the registered URI to fingerprint it on create.
 * Phase 1 scope: registration persists; the probe result is stored in metadata
 * for diagnostic purposes only. Actual reading (Evidence-record writes) is
 * Phase 5 work.
 */
@Service
public class ReaderRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(ReaderRegistrationService.class);

    private final ReaderRegistrationRepository repository;
    private final ProbeService probeService;

    public ReaderRegistrationService(ReaderRegistrationRepository repository,
                                     ProbeService probeService) {
        this.repository = repository;
        this.probeService = probeService;
    }

    @Transactional(readOnly = true)
    public List<ReaderRegistration> listByDomain(UUID domainId) {
        return repository.findByDomainId(domainId);
    }

    @Transactional(readOnly = true)
    public ReaderRegistration get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("ReaderRegistration", id));
    }

    @Transactional
    public ReaderRegistration create(ReaderRegistrationCreateRequest req) {
        ReaderRegistration r = new ReaderRegistration();
        r.setDomainId(req.domainId());
        r.setKind(req.kind());
        r.setName(req.name());
        r.setUri(req.uri());
        r.setAuthContext(req.authContext());
        r.setStatus(req.status() == null ? ReaderStatus.ACTIVE : req.status());
        r.setRegisteredAt(Instant.now());
        r.setCreatedBy("api");
        ReaderRegistration saved = repository.save(r);
        log.debug("Registered reader [id={}, kind={}, name={}]",
                saved.getId(), saved.getKind(), saved.getName());
        return saved;
    }

    @Transactional
    public ReaderRegistration markRead(UUID id, String fingerprintHash) {
        ReaderRegistration r = get(id);
        r.setLastReadAt(Instant.now());
        r.setLastFingerprintHash(fingerprintHash);
        return repository.save(r);
    }

    @Transactional
    public void delete(UUID id) {
        repository.deleteById(id);
    }

    /**
     * Helper for the Structured reader flow (Phase 5 will wire this into the
     * ingestion retrofit). Runs ProbeService over the registered URI and
     * returns the probe result for the caller to persist or inspect.
     */
    public ProbeService.ProbeResult probe(ReaderRegistration registration) {
        if (registration.getKind() != ReaderKind.STRUCTURED) {
            throw new IllegalArgumentException(
                    "ProbeService is only applicable to STRUCTURED readers; got " + registration.getKind());
        }
        if (registration.getUri() == null || registration.getUri().isBlank()) {
            throw new IllegalArgumentException("Structured reader has no URI to probe");
        }
        return probeService.probe(registration.getUri());
    }
}
