package com.meridian.substrate.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

// @EnableJpaAuditing is carried from homepoc per port-map §2.2. It is currently
// inert (BaseEntity uses Hibernate @CreationTimestamp/@UpdateTimestamp, not
// Spring Data auditing listeners) but stays for port fidelity.
@SpringBootApplication(scanBasePackages = "com.meridian.substrate")
@EntityScan(basePackages = "com.meridian.substrate")
@EnableJpaRepositories(basePackages = "com.meridian.substrate")
@EnableJpaAuditing
public class SubstrateApplication {

    public static void main(String[] args) {
        SpringApplication.run(SubstrateApplication.class, args);
    }
}
