package com.meridian.substrate.engine.contract.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.repo.DocumentRegistryRepository;
import com.meridian.substrate.common.repo.EvidenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Soft-reference existence check for evidence refs (engine-spec contracts.md
 * §5.5): "soft references to existing Evidence/DocumentRegistry rows —
 * validated for existence, <b>warn-don't-fail</b> if missing (consistent
 * soft-ref posture)". Accepts the EVIDENCE-GROUNDING array shape: entries are
 * id strings or objects carrying an {@code id} field; unparseable entries are
 * warned about, never rejected.
 */
@Component
public class EvidenceRefValidator {

    private static final Logger log = LoggerFactory.getLogger(EvidenceRefValidator.class);

    private final EvidenceRepository evidenceRepository;
    private final DocumentRegistryRepository documentRegistryRepository;

    public EvidenceRefValidator(EvidenceRepository evidenceRepository,
                                DocumentRegistryRepository documentRegistryRepository) {
        this.evidenceRepository = evidenceRepository;
        this.documentRegistryRepository = documentRegistryRepository;
    }

    /** Logs a WARN per ref that resolves to neither table; never throws. */
    public void warnIfMissing(JsonNode evidenceRefs, String context) {
        if (evidenceRefs == null || !evidenceRefs.isArray()) {
            return;
        }
        for (JsonNode ref : evidenceRefs) {
            String raw = ref.isTextual() ? ref.asText()
                    : ref.hasNonNull("id") ? ref.get("id").asText() : null;
            UUID id = parse(raw);
            if (id == null) {
                log.warn("Evidence ref {} has no parseable id [{}] — recorded verbatim (soft-ref posture)",
                        ref, context);
                continue;
            }
            if (!evidenceRepository.existsById(id) && !documentRegistryRepository.existsById(id)) {
                log.warn("Evidence ref {} matches no Evidence/DocumentRegistry row [{}] —"
                        + " recorded anyway (warn-don't-fail, §5.5)", id, context);
            }
        }
    }

    private static UUID parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
