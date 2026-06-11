package com.meridian.substrate.engine.contract.gateway;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Advisor-order pinning for the §4.2 PEP-2 aspect (engine-spec contracts.md
 * §4.2: the {@link GovernedActionAspect} "must run INSIDE the transaction:
 * order the aspect after the transaction interceptor so DENY rollback is
 * automatic").
 *
 * <p>Spring Boot's auto-configured {@code @EnableTransactionManagement} uses
 * the default {@code Ordered.LOWEST_PRECEDENCE} — the same order the aspect
 * would get, leaving the nesting UNDEFINED. This configuration re-declares
 * transaction management at {@link #TRANSACTION_ADVISOR_ORDER} (Boot's
 * {@code TransactionAutoConfiguration} backs off via
 * {@code @ConditionalOnMissingBean(AbstractTransactionManagementConfiguration)}),
 * so the chain is deterministically: transaction interceptor (outer) →
 * governed aspect → method body. {@code proxyTargetClass = true} preserves
 * Boot's default CGLIB proxying (services here have no interfaces).
 *
 * <p>Scope note: this configuration is engine-module and reaches the app
 * runtime via component scan. The ingestion module never sees it (ingestion
 * depends only on common — ADR D3 module rule) and keeps Boot's default
 * transaction auto-configuration.
 */
@Configuration
@EnableTransactionManagement(proxyTargetClass = true,
        order = GovernedEnforcementConfig.TRANSACTION_ADVISOR_ORDER)
public class GovernedEnforcementConfig {

    /**
     * Higher precedence (lower value) than
     * {@link GovernedActionAspect#GOVERNED_ASPECT_ORDER} ⇒ the transaction
     * advisor wraps OUTSIDE the governed aspect.
     */
    public static final int TRANSACTION_ADVISOR_ORDER = Ordered.LOWEST_PRECEDENCE - 100;
}
