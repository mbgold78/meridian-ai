package com.meridian.substrate.app.architecture;

import com.meridian.substrate.engine.agent.AgentCriteriaGateService;
import com.meridian.substrate.engine.contract.gateway.TinyDslEvaluator;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.transaction.event.TransactionalEventListener;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * Agent-engine architecture pins (engine-spec agents.md; test plan #11/#14),
 * CI-provable from bytecode alone:
 *
 * <ol>
 *   <li><b>No second DSL (§9.1; ADR-003 D9):</b> {@code engine.agent}
 *       contains no predicate-DSL implementation of its own — STRUCTURED
 *       criteria gates REUSE the contracts {@link TinyDslEvaluator} verbatim.
 *       Two pins: no agent class is named like a DSL/evaluator, and the gate
 *       service actually depends on the shared evaluator (non-vacuity).</li>
 *   <li><b>No {@code @TransactionalEventListener} in {@code engine.agent}
 *       (§13.4; ADR-001 D10):</b> outbox rows are written IN the verb
 *       transaction — never after-commit listeners.</li>
 * </ol>
 */
@AnalyzeClasses(packages = "com.meridian.substrate.engine.agent",
        importOptions = ImportOption.DoNotIncludeTests.class)
class AgentEngineArchitectureTest {

    /** §9.1: un-compilable prose ships UNSTRUCTURED — never a home-grown evaluator. */
    @ArchTest
    static final ArchRule noSecondDslImplementation = noClasses()
            .that().resideInAPackage("com.meridian.substrate.engine.agent..")
            .should().haveSimpleNameContaining("Dsl")
            .orShould().haveSimpleNameContaining("Predicate")
            .orShould().haveSimpleNameContaining("Evaluator")
            .because("engine-spec agents.md §9.1 / ADR-003 D9: the criteria-gate predicate"
                    + " language is the contracts TinyDsl, reused VERBATIM — engine.agent must"
                    + " never grow a second evaluator (one DSL, one leaf-resolution semantic)");

    /** Non-vacuity: the gate service really rides the SHARED evaluator. */
    @ArchTest
    static final ArchRule gateServiceReusesTheContractsTinyDsl = classes()
            .that().haveFullyQualifiedName(AgentCriteriaGateService.class.getName())
            .should().dependOnClassesThat().haveFullyQualifiedName(TinyDslEvaluator.class.getName())
            .because("the no-second-DSL rule would be vacuously green if gate evaluation"
                    + " quietly stopped using the shared evaluator");

    /** §13.4 / ADR-001 D10: same-transaction outbox — no after-commit listeners. */
    @ArchTest
    static final ArchRule noTransactionalEventListeners = noMethods()
            .that().areDeclaredInClassesThat()
            .resideInAPackage("com.meridian.substrate.engine.agent..")
            .should().beAnnotatedWith(TransactionalEventListener.class)
            .because("engine-spec agents.md §13.4: agent_notifications rows are written in the"
                    + " SAME transaction as the triggering event (ADR-001 D10) — an"
                    + " @TransactionalEventListener would silently break the outbox guarantee");
}
