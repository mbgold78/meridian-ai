package com.meridian.substrate.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.hibernate6.Hibernate6Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson ObjectMapper configuration with Java time support and Hibernate proxy handling.
 *
 * <p><b>Load-bearing for the wire contract.</b> Entities ARE the API wire format
 * (port-map §2.2): the {@link Hibernate6Module} keeps uninitialized lazy proxies
 * from exploding serialization, and works together with the {@code @JsonIgnore}
 * navigation-twin discipline on entities. ISO-8601 dates (never numeric timestamps)
 * are part of the contract the ported frontend depends on.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.registerModule(new Hibernate6Module());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
