package com.meridian.substrate.app.infrastructure.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.TimeUnit;

/**
 * Registers web-layer interceptors and static-resource caching policy.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final PersonaInterceptor personaInterceptor;

    public WebConfig(PersonaInterceptor personaInterceptor) {
        this.personaInterceptor = personaInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(personaInterceptor)
                .addPathPatterns("/api/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // index.html: no-cache so browsers always fetch the latest version
        registry.addResourceHandler("/index.html")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache());
        // Hashed assets: cache for 1 year (content-addressed)
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).immutable());
    }
}
