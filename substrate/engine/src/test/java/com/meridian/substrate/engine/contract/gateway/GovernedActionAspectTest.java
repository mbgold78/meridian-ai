package com.meridian.substrate.engine.contract.gateway;

import com.meridian.substrate.common.domain.enums.PrincipalType;
import com.meridian.substrate.engine.contract.ContractProperties;
import com.meridian.substrate.engine.contract.gateway.GovernedContextResolver.GovernedContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * §4.2 PEP-2 aspect unit lane (engine-spec contracts.md §4.2; ADR-002 D2/D5):
 * principal resolution order (explicit scope &gt; transport source &gt;
 * SERVICE fallback), action construction from the explicit per-service
 * resolver, deny propagation (the body must never run after an enforce-mode
 * DENY), and the documented unresolved-context posture (D5 shape without a
 * row: HUMAN proceeds; AGENT/SERVICE deny in enforce, proceed in shadow).
 *
 * <p>Proxying via {@link AspectJProxyFactory} — the same AspectJ-annotation
 * machinery Spring AOP uses at runtime, no Spring context. The
 * inside-the-transaction ORDERING half of §4.2 is integration-proven by
 * {@code PostgresContractGatewayDenyRollbackIT} (rollback semantics need a
 * real transaction manager).
 */
class GovernedActionAspectTest {

    static final UUID DOMAIN_ID = UUID.randomUUID();

    /** Minimal governed service: one resolvable verb, one unresolvable. */
    public static class SampleService {

        boolean invoked;

        @Governed(actionType = "sample.write")
        public String write(UUID id) {
            invoked = true;
            return "written";
        }

        @Governed(actionType = "sample.unresolvable")
        public String orphan() {
            invoked = true;
            return "orphaned";
        }

        public String ungovernedPlain() {
            invoked = true;
            return "plain";
        }
    }

    ContractGateway gateway;
    ContractProperties properties;
    GovernedPrincipalSource personaSource;
    Optional<Principal> sourcePrincipal;
    SampleService target;
    SampleService proxy;

    @BeforeEach
    void setUp() {
        gateway = mock(ContractGateway.class);
        properties = new ContractProperties();           // shadow default, like §1
        sourcePrincipal = Optional.empty();
        personaSource = () -> sourcePrincipal;

        GovernedContextResolver resolver = GovernedContextResolver.of(SampleService.class,
                (actionType, method, args) -> "write".equals(method.getName())
                        ? new GovernedContext(DOMAIN_ID,
                                new TargetRef("SAMPLE", String.valueOf(args[0])), null)
                        : GovernedContext.unresolved());

        GovernedActionAspect aspect = new GovernedActionAspect(
                gateway, properties, List.of(resolver), List.of(personaSource));
        target = new SampleService();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        proxy = factory.getProxy();
    }

    // ------------------------------------------------------ action construction

    @Test
    void evaluatesTheResolvedActionBeforeTheMethodBodyRuns() {
        UUID id = UUID.randomUUID();
        assertThat(proxy.write(id)).isEqualTo("written");

        ArgumentCaptor<GovernedAction> captor = ArgumentCaptor.forClass(GovernedAction.class);
        verify(gateway).evaluate(captor.capture());
        GovernedAction action = captor.getValue();
        assertThat(action.actionType()).isEqualTo("sample.write");
        assertThat(action.domainId()).isEqualTo(DOMAIN_ID);
        assertThat(action.target()).isEqualTo(new TargetRef("SAMPLE", id.toString()));
        assertThat(action.dispatchId()).as("PEP 3 is explicit, never aspect-built").isNull();
    }

    @Test
    void ungovernedMethodsNeverTouchTheGateway() {
        assertThat(proxy.ungovernedPlain()).isEqualTo("plain");
        verifyNoInteractions(gateway);
    }

    // -------------------------------------------------- principal resolution

    @Test
    void principalDefaultsToServiceForEngineInternalCallers() {
        proxy.write(UUID.randomUUID());
        ArgumentCaptor<GovernedAction> captor = ArgumentCaptor.forClass(GovernedAction.class);
        verify(gateway).evaluate(captor.capture());
        assertThat(captor.getValue().principal())
                .isEqualTo(Principal.service());          // fail-closed conservative default
    }

    @Test
    void transportSourceSuppliesTheHumanPrincipal() {
        sourcePrincipal = Optional.of(Principal.human("operating-partner"));
        proxy.write(UUID.randomUUID());
        ArgumentCaptor<GovernedAction> captor = ArgumentCaptor.forClass(GovernedAction.class);
        verify(gateway).evaluate(captor.capture());
        assertThat(captor.getValue().principal().type()).isEqualTo(PrincipalType.HUMAN);
        assertThat(captor.getValue().principal().id()).isEqualTo("operating-partner");
    }

    @Test
    void explicitScopeWinsOverTransportSources() {
        sourcePrincipal = Optional.of(Principal.human("operating-partner"));
        GovernedPrincipalScope.callAs(Principal.agent("mcp-agent-1"),
                () -> proxy.write(UUID.randomUUID()));
        ArgumentCaptor<GovernedAction> captor = ArgumentCaptor.forClass(GovernedAction.class);
        verify(gateway).evaluate(captor.capture());
        assertThat(captor.getValue().principal())
                .as("MCP AGENT scope must never be misattributed as HUMAN")
                .isEqualTo(Principal.agent("mcp-agent-1"));
    }

    @Test
    void principalScopeRestoresThePreviousPrincipalOnExit() {
        GovernedPrincipalScope.callAs(Principal.agent("outer"), () -> {
            GovernedPrincipalScope.callAs(Principal.agent("inner"), () -> {
                assertThat(GovernedPrincipalScope.current())
                        .contains(Principal.agent("inner"));
                return null;
            });
            assertThat(GovernedPrincipalScope.current()).contains(Principal.agent("outer"));
            return null;
        });
        assertThat(GovernedPrincipalScope.current()).isEmpty();
    }

    // ------------------------------------------------------- deny propagation

    @Test
    void enforceModeDenyPropagatesAndTheBodyNeverRuns() {
        ContractDeniedException denial = new ContractDeniedException(
                new Decision.Deny(List.of(), List.of("demo-gateway-guards"),
                        ContractGateway.REASON_DENY_GUARD_MATCHED, UUID.randomUUID()),
                "denied");
        doThrow(denial).when(gateway).evaluate(any());

        assertThatThrownBy(() -> proxy.write(UUID.randomUUID())).isSameAs(denial);
        assertThat(target.invoked)
                .as("a denied governed mutation must never execute")
                .isFalse();
    }

    // ------------------------------------------- unresolved-context posture (D5)

    @Test
    void unresolvableContext_humanProceedsWithoutEvaluation() {
        sourcePrincipal = Optional.of(Principal.human("operating-partner"));
        properties.getEnforcement().setMode("enforce");
        assertThat(proxy.orphan()).isEqualTo("orphaned");  // fail-open for humans (D5)
        verifyNoInteractions(gateway);
    }

    @Test
    void unresolvableContext_agentIsDeniedInEnforceMode() {
        properties.getEnforcement().setMode("enforce");
        assertThatThrownBy(() ->
                GovernedPrincipalScope.callAs(Principal.agent("mcp-agent-1"), proxy::orphan))
                .isInstanceOf(ContractDeniedException.class)
                .satisfies(thrown -> {
                    Decision.Deny deny = ((ContractDeniedException) thrown).getDeny();
                    assertThat(deny.reason()).isEqualTo(ContractGateway.REASON_GATEWAY_ERROR);
                    assertThat(deny.decisionId())
                            .as("no domain ⇒ no decision row could be written")
                            .isNull();
                });
        assertThat(target.invoked).isFalse();
        verifyNoInteractions(gateway);
    }

    @Test
    void unresolvableContext_agentProceedsInShadowMode() {
        properties.getEnforcement().setMode("shadow");     // shadow never blocks (ADR D4)
        String result = GovernedPrincipalScope.callAs(
                Principal.agent("mcp-agent-1"), proxy::orphan);
        assertThat(result).isEqualTo("orphaned");
        verifyNoInteractions(gateway);
    }

    @Test
    void missingResolverIsTheUnresolvedPosture() {
        GovernedActionAspect aspect = new GovernedActionAspect(
                gateway, properties, List.of(), List.of()); // no resolver registered
        AspectJProxyFactory factory = new AspectJProxyFactory(new SampleService());
        factory.addAspect(aspect);
        SampleService unresolved = factory.getProxy();

        properties.getEnforcement().setMode("enforce");
        assertThatThrownBy(() -> GovernedPrincipalScope.callAs(
                Principal.agent("mcp-agent-1"), () -> unresolved.write(UUID.randomUUID())))
                .isInstanceOf(ContractDeniedException.class);
        verifyNoInteractions(gateway);
    }
}
