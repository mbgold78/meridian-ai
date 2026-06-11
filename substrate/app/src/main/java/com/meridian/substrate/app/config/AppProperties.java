package com.meridian.substrate.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Application-level configuration properties (prefix {@code substrate}).
 *
 * <p>All fields carry safe Java defaults so the application boots with no
 * {@code substrate.data-dir} / {@code substrate.uploads-dir} /
 * {@code substrate.staging-dir} / {@code substrate.demo.seed} keys present.
 *
 * <p>Relaxed-binding note (carried forward from the homepoc port): the bound key
 * for {@code dataDir} is {@code substrate.data-dir} (or {@code substrate.dataDir}),
 * NOT {@code substrate.data.dir} — homepoc's {@code meridian.data.dir} property
 * style never actually bound and only worked because the values matched the code
 * defaults. Use the kebab-case keys.
 */
@Component
@ConfigurationProperties(prefix = "substrate")
public class AppProperties {

    private String dataDir = "./data";
    private String uploadsDir = "./uploads";
    private String stagingDir = "./staging";
    private Demo demo = new Demo();

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }
    public String getUploadsDir() { return uploadsDir; }
    public void setUploadsDir(String uploadsDir) { this.uploadsDir = uploadsDir; }
    public String getStagingDir() { return stagingDir; }
    public void setStagingDir(String stagingDir) { this.stagingDir = stagingDir; }
    public Demo getDemo() { return demo; }
    public void setDemo(Demo demo) { this.demo = demo; }

    public static class Demo {
        private boolean seed = false;
        private Contracts contracts = new Contracts();

        public boolean isSeed() { return seed; }
        public void setSeed(boolean seed) { this.seed = seed; }
        public Contracts getContracts() { return contracts; }
        public void setContracts(Contracts contracts) { this.contracts = contracts; }
    }

    /**
     * {@code substrate.demo.contracts.*} — DemoPack-only contract levers
     * (engine-spec contracts.md §1/§14.2). {@code activate=true} additionally
     * records ACCEPTED responses for every required party of the
     * {@code co-tribal-knowledge-depopulation} vector, flipping it ACTIVE for
     * monitor demos (the 3b demo lever). {@code guards=true} seeds the §13
     * row-3c SYNTHETIC guard-bearing contract ({@code demo-gateway-guards})
     * and activates it — both FIXTURE contracts compile to ZERO guards by
     * spec §16, so gateway demos and the 3c acceptance need this deliberately
     * synthetic vector. Effective only when {@code substrate.demo.seed=true}
     * (the seeder never runs otherwise).
     */
    public static class Contracts {
        private boolean activate = false;
        private boolean guards = false;

        public boolean isActivate() { return activate; }
        public void setActivate(boolean activate) { this.activate = activate; }
        public boolean isGuards() { return guards; }
        public void setGuards(boolean guards) { this.guards = guards; }
    }
}
