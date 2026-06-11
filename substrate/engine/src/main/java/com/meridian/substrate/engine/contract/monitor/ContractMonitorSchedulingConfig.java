package com.meridian.substrate.engine.contract.monitor;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * ShedLock wiring for the §5.2 monitor sweep (engine-spec contracts.md §5.2,
 * §10.3; §14.4 lease row). Component-scanned by the app module; the engine
 * {@code @DataJpaTest} slices never load it (no component scan there —
 * EngineContractJpaTestConfig precedent), so hand-constructed schedulers in
 * tests run un-proxied.
 *
 * <p>Only {@code @SchedulerLock}-annotated methods are leased — the cascade
 * engine's hourly sweeper is untouched. The lease table is the V5
 * {@code shedlock} DDL ({@code IF NOT EXISTS}, order-independent vs the V4
 * owner); {@code usingDbTime()} keys the lease on the database clock so
 * multi-node wall-clock skew cannot double-grant.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class ContractMonitorSchedulingConfig {

    @Bean
    @ConditionalOnMissingBean(LockProvider.class)
    public LockProvider contractSweepLockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
