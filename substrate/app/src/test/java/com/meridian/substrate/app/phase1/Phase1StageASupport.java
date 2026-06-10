package com.meridian.substrate.app.phase1;

import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.Process;
import com.meridian.substrate.common.domain.SystemEntity;
import com.meridian.substrate.common.domain.enums.ManualProcessClassification;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.common.repo.ProcessRepository;
import com.meridian.substrate.common.repo.SystemRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

/**
 * Shared helpers for Phase 1 Stage A tests: seeds a domain + one system +
 * one process so link-style entities have targets. Kept in-package so
 * controller tests can pull this in without static glue.
 */
public abstract class Phase1StageASupport {

    @Autowired
    protected DomainRepository domainRepository;
    @Autowired
    protected SystemRepository systemRepository;
    @Autowired
    protected ProcessRepository processRepository;

    protected UUID createDomain() {
        Domain d = new Domain();
        d.setName("Test Domain " + UUID.randomUUID());
        d.setCreatedBy("test");
        return domainRepository.save(d).getId();
    }

    protected UUID createSystem(UUID domainId) {
        SystemEntity s = new SystemEntity();
        s.setDomainId(domainId);
        s.setName("Test System " + UUID.randomUUID());
        s.setCreatedBy("test");
        return systemRepository.save(s).getId();
    }

    protected UUID createProcess(UUID domainId) {
        Process p = new Process();
        p.setDomainId(domainId);
        p.setName("Test Process " + UUID.randomUUID());
        p.setDescription("Test description");
        p.setClassification(ManualProcessClassification.BRIDGE);
        p.setCreatedBy("test");
        return processRepository.save(p).getId();
    }
}
