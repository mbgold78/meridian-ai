package com.meridian.substrate.app.architecture;

import com.meridian.substrate.common.governance.UngovernedModule;
import com.meridian.substrate.common.repo.AgentStatusEventRepository;
import com.meridian.substrate.common.repo.CascadeLevelActionRepository;
import com.meridian.substrate.common.repo.AiCurationEventRepository;
import com.meridian.substrate.common.repo.ContractEnforcementDecisionRepository;
import com.meridian.substrate.common.repo.ContractEventRepository;
import com.meridian.substrate.common.repo.ContractMonitorRunRepository;
import com.meridian.substrate.common.repo.ContractPartyResponseRepository;
import com.meridian.substrate.engine.contract.gateway.Governed;
import com.meridian.substrate.engine.contract.gateway.Ungoverned;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.core.importer.Locations;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.LocationProvider;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.CrudRepository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The §14.6 ArchUnit suite (engine-spec contracts.md §4.2/§10.9/§14.6;
 * ADR-002 D2/D3) — runs in CI from 3c day one and is deliberately
 * H2/Docker/Spring-independent (pure bytecode analysis):
 *
 * <ol>
 *   <li><b>Governed-coverage rule (§4.2):</b> every public
 *       {@code @Transactional(readOnly=false)} method in the governed
 *       packages must carry {@code @Governed} or an auditable
 *       {@code @Ungoverned(reason=…)} — "no route around" is CI-proven, not
 *       hoped. A companion rule bans class-level mutating {@code @Transactional}
 *       in those packages so the method-level gate cannot be bypassed, and a
 *       non-vacuity check pins that the rule actually sees the PEP surface.</li>
 *   <li><b>Append-only repo rule (§10.9):</b> the four append-only contract
 *       repositories expose no delete/update surface — not declared, not
 *       inherited ({@code Repository}, never {@code CrudRepository}), no
 *       {@code @Modifying}.</li>
 *   <li><b>Module rules:</b> engine never imports app; ingestion never
 *       imports engine or app; common imports none of them. (The ingestion
 *       module is on this TEST classpath precisely so these can be proven
 *       from one suite — its compile graph stays common-only, which is what
 *       the rule asserts.)</li>
 *   <li><b>ADR D3 exemption pin:</b> {@code IngestionApplication} carries
 *       {@code @UngovernedModule} with the D3 reason string.</li>
 * </ol>
 */
@AnalyzeClasses(locations = GovernedArchitectureTest.AllSubstrateModules.class,
        importOptions = ImportOption.DoNotIncludeTests.class)
class GovernedArchitectureTest {

    static final String ROOT_PACKAGE = "com.meridian.substrate";

    /**
     * Classpath packages (common/engine/app) PLUS the ingestion module's
     * compiled classes by filesystem location — ingestion is a separate
     * bootable app (own {@code application.yml}, {@code @EnableJpaRepositories})
     * and must NOT join the app test classpath; the Gradle test task hands
     * its classes dir over as a system property and orders
     * {@code :ingestion:compileJava} first.
     */
    public static class AllSubstrateModules implements LocationProvider {

        static final String INGESTION_CLASSES_PROPERTY = "substrate.archunit.ingestion-classes";

        @Override
        public Set<Location> get(Class<?> testClass) {
            Set<Location> locations = new HashSet<>(Locations.ofPackage(ROOT_PACKAGE));
            locations.add(Location.of(ingestionClassesDir()));
            return locations;
        }

        private static Path ingestionClassesDir() {
            String configured = System.getProperty(INGESTION_CLASSES_PROPERTY);
            Path dir = configured != null
                    ? Paths.get(configured)
                    // IDE fallback: module-relative Gradle layout
                    : Paths.get("..", "ingestion", "build", "classes", "java", "main")
                            .toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) {
                throw new IllegalStateException("ingestion classes dir not found at " + dir
                        + " — run :ingestion:compileJava (the Gradle test task wires this)");
            }
            return dir;
        }
    }

    /**
     * §4.2: "every public method in the governed packages
     * ({@code com.meridian.substrate.app.core.service..},
     * {@code com.meridian.substrate.engine..} service classes)". Maintained
     * as modules grow — additions here widen the gate, never narrow it.
     */
    static final String[] GOVERNED_PACKAGES = {
            "com.meridian.substrate.app.core.service..",
            "com.meridian.substrate.engine..",
    };

    /**
     * §10.9 append-only repositories (no update/delete code path may exist) +
     * the cascade action log (engine-spec cascade.md §12 test #6 ArchUnit
     * half — "assert via architecture test if ArchUnit is present"; D1 landed
     * before this suite existed and the backfill was owed) + the agent audit
     * log (engine-spec agents.md §5.5 / invariant I4; test plan #6 ArchUnit
     * half) + the diagnostic curation audit (engine-spec diagnostic.md §3.2 /
     * test plan #15: "repositories expose no update/delete methods for
     * AiCurationEvent; ArchUnit pins this").
     */
    static final Class<?>[] APPEND_ONLY_REPOSITORIES = {
            CascadeLevelActionRepository.class,
            ContractEventRepository.class,
            ContractMonitorRunRepository.class,
            ContractPartyResponseRepository.class,
            ContractEnforcementDecisionRepository.class,
            AgentStatusEventRepository.class,
            AiCurationEventRepository.class,
    };

    // ------------------------------------------------- 1. governed coverage

    private static final DescribedPredicate<JavaMethod> MUTATING_TRANSACTIONAL =
            new DescribedPredicate<>("annotated with @Transactional without readOnly=true") {
                @Override
                public boolean test(JavaMethod method) {
                    return method.tryGetAnnotationOfType(Transactional.class)
                            .map(tx -> !tx.readOnly())
                            .orElse(false);
                }
            };

    @ArchTest
    static final ArchRule governed_coverage_over_mutating_methods = methods()
            .that().arePublic()
            .and().areDeclaredInClassesThat().resideInAnyPackage(GOVERNED_PACKAGES)
            .and(MUTATING_TRANSACTIONAL)
            .should().beAnnotatedWith(Governed.class)
            .orShould().beAnnotatedWith(Ungoverned.class)
            .because("every mutating service command in the governed packages is a PEP"
                    + " (@Governed) or an auditable, reasoned exemption (@Ungoverned) —"
                    + " engine-spec contracts.md §4.2; ADR-002 D2");

    /** Class-level mutating @Transactional would hide methods from the rule above. */
    @ArchTest
    static final ArchRule no_class_level_mutating_transactional_in_governed_packages = classes()
            .that().resideInAnyPackage(GOVERNED_PACKAGES)
            .should(new ArchCondition<>("not declare class-level @Transactional(readOnly=false)") {
                @Override
                public void check(JavaClass clazz, ConditionEvents events) {
                    clazz.tryGetAnnotationOfType(Transactional.class).ifPresent(tx -> {
                        if (!tx.readOnly()) {
                            events.add(SimpleConditionEvent.violated(clazz, clazz.getName()
                                    + " declares class-level @Transactional(readOnly=false) —"
                                    + " declare it per method so the §4.2 governed-coverage"
                                    + " rule can see every mutating command"));
                        }
                    });
                }
            })
            .because("the governed-coverage gate keys on method-level @Transactional (§4.2)");

    /**
     * Non-vacuity pin: the coverage rule must actually SEE the PEP surface.
     * If a refactor moved the services out of the governed packages, the
     * rules above would pass vacuously — this fails instead.
     */
    @ArchTest
    static void governed_coverage_is_not_vacuous(JavaClasses classes) {
        long governed = 0;
        long ungoverned = 0;
        for (JavaClass clazz : classes) {
            for (JavaMethod method : clazz.getMethods()) {
                if (method.isAnnotatedWith(Governed.class)) {
                    governed++;
                }
                if (method.isAnnotatedWith(Ungoverned.class)) {
                    ungoverned++;
                }
            }
        }
        assertThat(governed)
                .as("@Governed PEP methods visible to the suite (27 at 3c; grows with modules)")
                .isGreaterThanOrEqualTo(27);
        assertThat(ungoverned)
                .as("@Ungoverned auditable exemptions visible to the suite (9 at 3c)")
                .isGreaterThanOrEqualTo(9);
    }

    // ---------------------------------------------- 2. append-only repo rule

    @ArchTest
    static final ArchRule append_only_repositories_declare_no_mutators = noMethods()
            .that().areDeclaredInClassesThat().belongToAnyOf(APPEND_ONLY_REPOSITORIES)
            .should().haveNameMatching("(delete|remove|update).*")
            .because("cascade_level_actions / contract_events / contract_monitor_runs /"
                    + " contract_party_responses / contract_enforcement_decisions /"
                    + " agent_status_events / ai_curation_events are append-only — no"
                    + " update/delete repo method may exist (engine-spec contracts.md"
                    + " §2.2/§10.9; cascade.md test #6; agents.md I4; diagnostic.md §3.2)");

    @ArchTest
    static final ArchRule append_only_repositories_have_no_modifying_queries = noMethods()
            .that().areDeclaredInClassesThat().belongToAnyOf(APPEND_ONLY_REPOSITORIES)
            .should().beAnnotatedWith(Modifying.class)
            .because("@Modifying JPQL would be an update/delete path in disguise (§10.9)");

    @ArchTest
    static final ArchRule append_only_repositories_never_extend_crud_repository = noClasses()
            .that().belongToAnyOf(APPEND_ONLY_REPOSITORIES)
            .should().beAssignableTo(CrudRepository.class)
            .because("CrudRepository/JpaRepository inherit deleteById/deleteAll — the"
                    + " append-only surfaces extend plain Repository so no delete exists"
                    + " anywhere in the type system (§10.9)");

    // ----------------------------------------------------- 3. module rules

    @ArchTest
    static final ArchRule engine_never_imports_app = noClasses()
            .that().resideInAPackage("com.meridian.substrate.engine..")
            .should().dependOnClassesThat().resideInAPackage("com.meridian.substrate.app..")
            .because("dependency rule common ← engine ← app (engine-spec contracts.md header)");

    @ArchTest
    static final ArchRule ingestion_never_imports_engine_or_app = noClasses()
            .that().resideInAPackage("com.meridian.substrate.ingestion..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.meridian.substrate.engine..", "com.meridian.substrate.app..")
            .because("ingestion depends only on common (ADR-002 D3 module rule) — its"
                    + " gateway exemption is structural, not just declared");

    @ArchTest
    static final ArchRule common_imports_no_other_module = noClasses()
            .that().resideInAPackage("com.meridian.substrate.common..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.meridian.substrate.engine..", "com.meridian.substrate.app..",
                    "com.meridian.substrate.ingestion..")
            .because("common is the shared foundation (dependency rule common ← engine ← app)");

    // ------------------------------------------------ 4. ADR D3 exemption pin

    /**
     * Pinned verbatim against {@code IngestionApplication.UNGOVERNED_REASON}
     * (referenced by name — ingestion is import-only here, see
     * {@link AllSubstrateModules}); equality means any drift in the declared
     * exemption is a reviewed change, not silent.
     */
    static final String ADR_D3_REASON =
            "Ingestion bulk-commit writes operator-curated, pre-activation engagement data"
                    + " without transiting app/engine services; declared exempt from the contract"
                    + " enforcement gateway in v1 (ADR-002 D3). MUST be re-evaluated for gateway"
                    + " coverage before Phase 4 service-mode ingest ships.";

    @ArchTest
    static void ingestion_application_carries_the_adr_d3_ungoverned_module_marker(
            JavaClasses classes) {
        JavaClass ingestionApp =
                classes.get("com.meridian.substrate.ingestion.IngestionApplication");
        assertThat(ingestionApp.isAnnotatedWith(UngovernedModule.class))
                .as("@UngovernedModule on IngestionApplication (§4.2/§14.6; ADR-002 D3)")
                .isTrue();
        UngovernedModule marker = ingestionApp.getAnnotationOfType(UngovernedModule.class);
        assertThat(marker.reason())
                .as("the ADR D3 reason string, verbatim")
                .isEqualTo(ADR_D3_REASON)
                .contains("ADR-002 D3")
                .contains("Phase 4 service-mode ingest");
    }
}
