package com.meridian.substrate.engine.contract.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.repo.ContractBreachRepository;
import com.meridian.substrate.common.repo.ContractCommitmentRepository;
import com.meridian.substrate.common.repo.ContractRepository;
import com.meridian.substrate.engine.contract.gateway.GovernedContextResolver.GovernedContext;
import com.meridian.substrate.engine.contract.monitor.AttestationService;
import com.meridian.substrate.engine.contract.monitor.BreachService;
import com.meridian.substrate.engine.contract.monitor.MetricSampleService;
import com.meridian.substrate.engine.contract.registry.ContractRegistryService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * The contract engine's explicit per-service {@link GovernedContextResolver}
 * beans (engine-spec contracts.md §4.2 PEP 2 — "explicit per-service resolver
 * beans; no SpEL magic"). Domain resolution follows the entity chain the
 * method itself walks (breach → contract, commitment → contract), inside the
 * governed transaction; a missing entity yields
 * {@link GovernedContext#unresolved()} — the method body would 404 on it
 * anyway, and the aspect's documented posture covers the gap.
 *
 * <p>Target entity-type vocabulary feeds the §4.3 guard narrowing and the
 * §4.4 {@code target.*} dot-paths: {@code CONTRACT},
 * {@code CONTRACT_COMMITMENT}, {@code CONTRACT_BREACH}. Payload summaries are
 * deliberately SMALL (§4.1) — only fields a guard predicate could
 * meaningfully reference.
 */
@Configuration
public class ContractGovernedResolvers {

    /** §4.3/§4.4 target vocabulary. */
    public static final String TARGET_CONTRACT = "CONTRACT";
    public static final String TARGET_COMMITMENT = "CONTRACT_COMMITMENT";
    public static final String TARGET_BREACH = "CONTRACT_BREACH";

    @Bean
    public GovernedContextResolver contractRegistryGovernedResolver(
            ContractRepository contracts, ObjectMapper objectMapper) {
        return GovernedContextResolver.of(ContractRegistryService.class,
                (actionType, method, args) -> switch (method.getName()) {
                    case "create" -> {
                        var cmd = (ContractRegistryService.CreateContractCommand) args[0];
                        ObjectNode payload = objectMapper.createObjectNode();
                        payload.put("contractKey", cmd.contractKey());
                        payload.put("contractType", cmd.type());
                        yield new GovernedContext(cmd.domainId(), null, payload);
                    }
                    case "patch" -> contractContext(contracts, (UUID) args[0], null);
                    case "respond" -> {
                        var cmd = (ContractRegistryService.RespondCommand) args[1];
                        ObjectNode payload = objectMapper.createObjectNode();
                        if (cmd.partyId() != null) {
                            payload.put("partyId", cmd.partyId().toString());
                        }
                        if (cmd.response() != null) {
                            payload.put("response", cmd.response().name());
                        }
                        yield contractContext(contracts, (UUID) args[0], payload);
                    }
                    case "invokeDecline" -> {
                        var cmd = (ContractRegistryService.InvokeDeclineCommand) args[1];
                        ObjectNode payload = objectMapper.createObjectNode();
                        if (cmd.declineTriggerId() != null) {
                            payload.put("declineTriggerId", cmd.declineTriggerId().toString());
                        }
                        yield contractContext(contracts, (UUID) args[0], payload);
                    }
                    default -> GovernedContext.unresolved();
                });
    }

    @Bean
    public GovernedContextResolver attestationGovernedResolver(
            ContractCommitmentRepository commitments, ContractRepository contracts,
            ObjectMapper objectMapper) {
        return GovernedContextResolver.of(AttestationService.class,
                (actionType, method, args) -> switch (method.getName()) {
                    case "attest" -> {
                        var cmd = (AttestationService.AttestCommand) args[0];
                        ObjectNode payload = objectMapper.createObjectNode();
                        payload.put("periodKey", cmd.periodKey());
                        if (cmd.claim() != null) {
                            payload.put("complianceClaim", cmd.claim().name());
                        }
                        yield commitmentContext(commitments, contracts, cmd.commitmentId(), payload);
                    }
                    case "recordProgress" ->
                            commitmentContext(commitments, contracts, (UUID) args[0], null);
                    default -> GovernedContext.unresolved();
                });
    }

    @Bean
    public GovernedContextResolver metricSampleGovernedResolver(
            ContractRepository contracts, ObjectMapper objectMapper) {
        return GovernedContextResolver.of(MetricSampleService.class,
                (actionType, method, args) -> {
                    if (!"report".equals(method.getName())) {
                        return GovernedContext.unresolved();
                    }
                    var cmd = (MetricSampleService.ReportMetricCommand) args[0];
                    ObjectNode payload = objectMapper.createObjectNode();
                    payload.put("metricKey", cmd.metricKey());
                    payload.put("value", cmd.value());
                    payload.put("periodKey", cmd.periodKey());
                    return contractContext(contracts, cmd.contractId(), payload);
                });
    }

    @Bean
    public GovernedContextResolver breachGovernedResolver(
            ContractBreachRepository breaches, ContractRepository contracts) {
        return GovernedContextResolver.of(BreachService.class,
                (actionType, method, args) -> {
                    // acknowledge / remediate / waive all lead with the breach id.
                    if (!(args.length > 0 && args[0] instanceof UUID breachId)) {
                        return GovernedContext.unresolved();
                    }
                    return breaches.findById(breachId)
                            .flatMap(breach -> contracts.findById(breach.getContractId()))
                            .map(contract -> new GovernedContext(contract.getDomainId(),
                                    new TargetRef(TARGET_BREACH, breachId.toString()), null))
                            .orElseGet(GovernedContext::unresolved);
                });
    }

    // ------------------------------------------------------------------ helpers

    private static GovernedContext contractContext(ContractRepository contracts,
                                                   UUID contractId, ObjectNode payload) {
        if (contractId == null) {
            return GovernedContext.unresolved();
        }
        return contracts.findById(contractId)
                .map(contract -> new GovernedContext(contract.getDomainId(),
                        new TargetRef(TARGET_CONTRACT, contractId.toString()), payload))
                .orElseGet(GovernedContext::unresolved);
    }

    private static GovernedContext commitmentContext(ContractCommitmentRepository commitments,
                                                     ContractRepository contracts,
                                                     UUID commitmentId, ObjectNode payload) {
        if (commitmentId == null) {
            return GovernedContext.unresolved();
        }
        return commitments.findById(commitmentId)
                .flatMap(commitment -> contracts.findById(commitment.getContractId()))
                .map((Contract contract) -> new GovernedContext(contract.getDomainId(),
                        new TargetRef(TARGET_COMMITMENT, commitmentId.toString()), payload))
                .orElseGet(GovernedContext::unresolved);
    }
}
