package com.meridian.substrate.app.demo;

import com.meridian.substrate.common.repo.BrandingConfigRepository;
import com.meridian.substrate.common.repo.DesignDecisionRepository;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.common.repo.EvidenceRepository;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.common.repo.OrganizationMemberRepository;
import com.meridian.substrate.common.repo.ProcessRepository;
import com.meridian.substrate.common.repo.SystemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C10 acceptance, disabled half (port-map §7): with the default
 * {@code substrate.demo.seed=false} (the key is NOT overridden here — this
 * pins the safe default, port-map §5 S1) boot leaves the database empty.
 *
 * <p>Uses a private H2 database so data created by other cached test contexts
 * on the shared dev-profile DB cannot leak into the emptiness assertions.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:demoseed_disabled;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("dev")
class DemoSeedDisabledTest {

    @Autowired DomainRepository domainRepository;
    @Autowired SystemRepository systemRepository;
    @Autowired ProcessRepository processRepository;
    @Autowired OrganizationMemberRepository organizationMemberRepository;
    @Autowired FindingRepository findingRepository;
    @Autowired DesignDecisionRepository designDecisionRepository;
    @Autowired EvidenceRepository evidenceRepository;
    @Autowired BrandingConfigRepository brandingConfigRepository;

    @Test
    void seedDisabledByDefaultLeavesDatabaseEmpty() {
        assertThat(domainRepository.count()).isZero();
        assertThat(systemRepository.count()).isZero();
        assertThat(processRepository.count()).isZero();
        assertThat(organizationMemberRepository.count()).isZero();
        assertThat(findingRepository.count()).isZero();
        assertThat(designDecisionRepository.count()).isZero();
        assertThat(evidenceRepository.count()).isZero();
        assertThat(brandingConfigRepository.count()).isZero();
    }
}
