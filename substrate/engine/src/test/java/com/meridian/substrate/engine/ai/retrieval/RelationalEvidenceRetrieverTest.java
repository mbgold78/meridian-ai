package com.meridian.substrate.engine.ai.retrieval;

import com.meridian.substrate.common.domain.Evidence;
import com.meridian.substrate.common.domain.SystemEntity;
import com.meridian.substrate.common.repo.CanonicalEntityRepository;
import com.meridian.substrate.common.repo.DesignDecisionRepository;
import com.meridian.substrate.common.repo.EntityOverlapRepository;
import com.meridian.substrate.common.repo.EvidenceRepository;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.common.repo.IntegrationRepository;
import com.meridian.substrate.common.repo.OrganizationMemberRepository;
import com.meridian.substrate.common.repo.ProcessRepository;
import com.meridian.substrate.common.repo.SystemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RelationalEvidenceRetriever} — a live code path in
 * substrate (homepoc dead-code mirage §2.3): the EVIDENCE branch backs
 * AssistiveModeService's grounding/citation pool.
 */
class RelationalEvidenceRetrieverTest {

    private SystemRepository systemRepository;
    private CanonicalEntityRepository canonicalEntityRepository;
    private FindingRepository findingRepository;
    private IntegrationRepository integrationRepository;
    private EntityOverlapRepository entityOverlapRepository;
    private OrganizationMemberRepository organizationMemberRepository;
    private ProcessRepository processRepository;
    private DesignDecisionRepository designDecisionRepository;
    private EvidenceRepository evidenceRepository;
    private RelationalEvidenceRetriever retriever;

    @BeforeEach
    void setUp() {
        systemRepository = mock(SystemRepository.class);
        canonicalEntityRepository = mock(CanonicalEntityRepository.class);
        findingRepository = mock(FindingRepository.class);
        integrationRepository = mock(IntegrationRepository.class);
        entityOverlapRepository = mock(EntityOverlapRepository.class);
        organizationMemberRepository = mock(OrganizationMemberRepository.class);
        processRepository = mock(ProcessRepository.class);
        designDecisionRepository = mock(DesignDecisionRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);
        retriever = new RelationalEvidenceRetriever(
                systemRepository, canonicalEntityRepository, findingRepository,
                integrationRepository, entityOverlapRepository, organizationMemberRepository,
                processRepository, designDecisionRepository, evidenceRepository);
    }

    @Test
    void evidenceBranch_returnsEvidenceRowUuidsAsSourceIds() {
        UUID domainId = UUID.randomUUID();
        Evidence evidence = new Evidence();
        setId(evidence, UUID.randomUUID());
        evidence.setDomainId(domainId);
        evidence.setEntityId(UUID.randomUUID());
        evidence.setEntityType("capability");
        evidence.setLabel("Policy doc extract");
        when(evidenceRepository.findByDomainId(domainId)).thenReturn(List.of(evidence));

        List<EvidenceChunk> chunks = retriever.retrieve(new EvidenceQuery(
                EvidenceQueryType.EVIDENCE, Map.of("domainId", domainId), null));

        assertThat(chunks).hasSize(1);
        EvidenceChunk chunk = chunks.get(0);
        assertThat(chunk.source().type()).isEqualTo(EvidenceSourceType.EVIDENCE_RECORD);
        assertThat(chunk.source().sourceId()).isEqualTo(evidence.getId().toString());
        assertThat(chunk.source().displayName()).isEqualTo("Policy doc extract");
        assertThat(chunk.content()).contains("Policy doc extract").contains("capability");
        assertThat(chunk.relevanceScore()).isEqualTo(1.0);

        verify(evidenceRepository).findByDomainId(domainId);
        verifyNoInteractions(systemRepository);
    }

    @Test
    void domainIdFilter_acceptsStringForm() {
        UUID domainId = UUID.randomUUID();
        when(evidenceRepository.findByDomainId(domainId)).thenReturn(List.of());

        retriever.retrieve(new EvidenceQuery(
                EvidenceQueryType.EVIDENCE, Map.of("domainId", domainId.toString()), null));

        verify(evidenceRepository).findByDomainId(domainId);
    }

    @Test
    void invalidDomainIdString_fallsBackToFindAll() {
        when(evidenceRepository.findAll()).thenReturn(List.of());

        Map<String, Object> filters = new HashMap<>();
        filters.put("domainId", "not-a-uuid");
        retriever.retrieve(new EvidenceQuery(EvidenceQueryType.EVIDENCE, filters, null));

        verify(evidenceRepository).findAll();
    }

    @Test
    void systemsBranch_formatsChunksAndAppliesLimit() {
        SystemEntity a = systemNamed("Alpha");
        SystemEntity b = systemNamed("Beta");
        when(systemRepository.findAll()).thenReturn(List.of(a, b));

        List<EvidenceChunk> chunks = retriever.retrieve(new EvidenceQuery(
                EvidenceQueryType.SYSTEMS, null, 1));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).startsWith("System: Alpha");
        assertThat(chunks.get(0).source().type()).isEqualTo(EvidenceSourceType.SYSTEM);
    }

    @Test
    void everyQueryType_dispatchesWithoutError() {
        when(systemRepository.findAll()).thenReturn(List.of());
        when(canonicalEntityRepository.findAll()).thenReturn(List.of());
        when(findingRepository.findAll()).thenReturn(List.of());
        when(integrationRepository.findAll()).thenReturn(List.of());
        when(entityOverlapRepository.findAll()).thenReturn(List.of());
        when(organizationMemberRepository.findAll()).thenReturn(List.of());
        when(processRepository.findAll()).thenReturn(List.of());
        when(designDecisionRepository.findAll()).thenReturn(List.of());
        when(evidenceRepository.findAll()).thenReturn(List.of());

        for (EvidenceQueryType type : EvidenceQueryType.values()) {
            assertThat(retriever.retrieve(new EvidenceQuery(type, null, null)))
                    .as("query type %s must dispatch cleanly", type)
                    .isEmpty();
        }
    }

    private SystemEntity systemNamed(String name) {
        SystemEntity s = new SystemEntity();
        setId(s, UUID.randomUUID());
        s.setName(name);
        return s;
    }

    /** BaseEntity.id is DB-generated; reflect it in for unit-test fixtures. */
    private static void setId(Object entity, UUID id) {
        try {
            Class<?> type = entity.getClass();
            Field field = null;
            while (type != null && field == null) {
                try {
                    field = type.getDeclaredField("id");
                } catch (NoSuchFieldException e) {
                    type = type.getSuperclass();
                }
            }
            if (field == null) {
                throw new IllegalStateException("no id field on " + entity.getClass());
            }
            field.setAccessible(true);
            field.set(entity, id);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
