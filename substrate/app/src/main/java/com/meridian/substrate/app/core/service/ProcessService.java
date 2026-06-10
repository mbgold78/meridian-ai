package com.meridian.substrate.app.core.service;

import com.meridian.substrate.app.core.exception.EntityNotFoundException;
import com.meridian.substrate.common.domain.Process;
import com.meridian.substrate.common.domain.ProcessRole;
import com.meridian.substrate.common.domain.ProcessSystemLink;
import com.meridian.substrate.common.repo.ProcessRepository;
import com.meridian.substrate.common.repo.ProcessRoleRepository;
import com.meridian.substrate.common.repo.ProcessSystemLinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read-side service for {@link Process} entities and their associated
 * system links and roles.
 */
@Service
public class ProcessService {

    private static final Logger log = LoggerFactory.getLogger(ProcessService.class);

    private final ProcessRepository processRepository;
    private final ProcessSystemLinkRepository processSystemLinkRepository;
    private final ProcessRoleRepository processRoleRepository;

    public ProcessService(ProcessRepository processRepository,
                          ProcessSystemLinkRepository processSystemLinkRepository,
                          ProcessRoleRepository processRoleRepository) {
        this.processRepository = processRepository;
        this.processSystemLinkRepository = processSystemLinkRepository;
        this.processRoleRepository = processRoleRepository;
    }

    @Transactional(readOnly = true)
    public List<Process> getProcessesByDomain(UUID domainId) {
        log.debug("Fetching processes for domain [domainId={}]", domainId);
        return processRepository.findByDomainId(domainId);
    }

    @Transactional(readOnly = true)
    public Process getProcessById(UUID id) {
        log.debug("Fetching process [id={}]", id);
        return processRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Process", id));
    }

    @Transactional(readOnly = true)
    public List<ProcessSystemLink> getSystemLinksByProcess(UUID processId) {
        log.debug("Fetching system links for process [processId={}]", processId);
        return processSystemLinkRepository.findByProcessId(processId);
    }

    @Transactional(readOnly = true)
    public List<ProcessRole> getRolesByProcess(UUID processId) {
        log.debug("Fetching roles for process [processId={}]", processId);
        return processRoleRepository.findByProcessId(processId);
    }
}
