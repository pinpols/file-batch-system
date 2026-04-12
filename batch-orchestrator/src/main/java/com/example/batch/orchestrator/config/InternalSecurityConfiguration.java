package com.example.batch.orchestrator.config;

import com.example.batch.common.config.BatchSecurityProperties;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 注册 {@link InternalAuthFilter} 到 {@code /internal/**} URL 模式。 */
@Configuration
public class InternalSecurityConfiguration {

    @Bean
    public FilterRegistrationBean<InternalAuthFilter> internalAuthFilter(
            BatchSecurityProperties securityProperties) {
        FilterRegistrationBean<InternalAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new InternalAuthFilter(securityProperties));
        registration.addUrlPatterns("/internal/*");
        registration.setOrder(1);
        return registration;
    }
}
