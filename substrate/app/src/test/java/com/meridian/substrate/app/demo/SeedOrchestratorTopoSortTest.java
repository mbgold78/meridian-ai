package com.meridian.substrate.app.demo;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SeedOrchestrator#topoSort(List)} — dependency-honoring
 * order, cycle detection, and unknown-dependency hard failure (all boot-time
 * errors by design: a mis-declared seeder graph must never half-seed).
 */
class SeedOrchestratorTopoSortTest {

    // --- fixture seeders -------------------------------------------------

    abstract static class NoOpSeeder implements Seeder {
        @Override public void seedBulk(SeedContext ctx) { }
        @Override public void seedSamplesForDomain(SeedContext ctx, UUID domainId) { }
    }

    static class A extends NoOpSeeder {
        @Override public List<Class<? extends Seeder>> dependsOn() { return List.of(); }
    }

    static class B extends NoOpSeeder {
        @Override public List<Class<? extends Seeder>> dependsOn() { return List.of(A.class); }
    }

    static class C extends NoOpSeeder {
        @Override public List<Class<? extends Seeder>> dependsOn() { return List.of(A.class, B.class); }
    }

    static class CycleX extends NoOpSeeder {
        @Override public List<Class<? extends Seeder>> dependsOn() { return List.of(CycleY.class); }
    }

    static class CycleY extends NoOpSeeder {
        @Override public List<Class<? extends Seeder>> dependsOn() { return List.of(CycleX.class); }
    }

    static class Orphan extends NoOpSeeder {
        // Depends on a seeder class that is not registered as a bean.
        @Override public List<Class<? extends Seeder>> dependsOn() { return List.of(B.class); }
    }

    // --- tests ------------------------------------------------------------

    @Test
    void sortsDependenciesBeforeDependents_regardlessOfRegistrationOrder() {
        List<Seeder> ordered = SeedOrchestrator.topoSort(List.of(new C(), new B(), new A()));

        List<Class<?>> classes = ordered.stream().<Class<?>>map(Object::getClass).toList();
        assertThat(classes.indexOf(A.class)).isLessThan(classes.indexOf(B.class));
        assertThat(classes.indexOf(B.class)).isLessThan(classes.indexOf(C.class));
        assertThat(ordered).hasSize(3);
    }

    @Test
    void preservesRegistrationOrderWhenDependenciesPermit() {
        A first = new A();
        // Two independent roots: stable insertion order expected.
        Seeder second = new NoOpSeeder() {
            @Override public List<Class<? extends Seeder>> dependsOn() { return List.of(); }
        };
        List<Seeder> ordered = SeedOrchestrator.topoSort(List.of(first, second));
        assertThat(ordered).containsExactly(first, second);
    }

    @Test
    void cycleIsAHardError() {
        assertThatThrownBy(() -> SeedOrchestrator.topoSort(List.of(new CycleX(), new CycleY())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cycle detected");
    }

    @Test
    void unknownDependencyIsAHardError() {
        assertThatThrownBy(() -> SeedOrchestrator.topoSort(List.of(new Orphan())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no such seeder bean is registered");
    }
}
