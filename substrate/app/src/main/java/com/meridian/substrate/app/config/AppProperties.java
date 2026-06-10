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

        public boolean isSeed() { return seed; }
        public void setSeed(boolean seed) { this.seed = seed; }
    }
}
