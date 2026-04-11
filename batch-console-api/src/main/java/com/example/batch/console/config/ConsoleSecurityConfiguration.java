package com.example.batch.console.config;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.constants.CommonErrorMessages;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.console.support.ConsoleAuthenticationFilter;
import com.example.batch.console.support.ConsoleRateLimitFilter;
import com.example.batch.console.support.ConsoleSecurityHeadersWriter;
import com.example.batch.console.support.ConsoleSecurityResponseWriter;
import com.example.batch.console.support.SlidingWindowRateLimiter;

import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class ConsoleSecurityConfiguration {

    private final ConsoleSecurityProperties properties;
    private final BatchSecurityProperties batchSecurityProperties;

    @Bean
    public ConsoleRateLimitFilter consoleRateLimitFilter(
            SlidingWindowRateLimiter rateLimiter,
            ConsoleRateLimitProperties rateLimitProperties,
            ConsoleSecurityResponseWriter responseWriter) {
        return new ConsoleRateLimitFilter(rateLimiter, rateLimitProperties, responseWriter);
    }

    @Bean
    public SecurityFilterChain consoleSecurityFilterChain(
            HttpSecurity http,
            ConsoleAuthenticationFilter consoleAuthenticationFilter,
            ConsoleRateLimitFilter consoleRateLimitFilter,
            ConsoleSecurityResponseWriter responseWriter,
            ConsoleSecurityHeadersWriter securityHeadersWriter)
            throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers.addHeaderWriter(securityHeadersWriter))
                .httpBasic(Customizer.withDefaults())
                .exceptionHandling(
                        exceptionHandling ->
                                exceptionHandling
                                        .authenticationEntryPoint(
                                                authenticationEntryPoint(responseWriter))
                                        .accessDeniedHandler(accessDeniedHandler(responseWriter)))
                .authorizeHttpRequests(
                        authorize ->
                                authorize
                                        .requestMatchers(
                                                "/actuator/health",
                                                "/actuator/info",
                                                "/actuator/prometheus")
                                        .permitAll()
                                        .requestMatchers(
                                                "/api/console/auth/login",
                                                "/console-login.html",
                                                "/favicon.ico")
                                        .permitAll()
                                        .requestMatchers(
                                                "/api/v3/api-docs/**",
                                                "/api/swagger-ui/**",
                                                "/api/swagger-ui.html")
                                        .permitAll()
                                        .requestMatchers("/actuator/loggers/**")
                                        .hasAuthority("ROLE_ADMIN")
                                        .anyRequest()
                                        .authenticated())
                .addFilterBefore(consoleRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(consoleAuthenticationFilter, ConsoleRateLimitFilter.class)
                .build();
    }

    private AuthenticationEntryPoint authenticationEntryPoint(
            ConsoleSecurityResponseWriter responseWriter) {
        return (request, response, authException) ->
                responseWriter.write(
                        response,
                        HttpStatus.UNAUTHORIZED,
                        ResultCode.UNAUTHORIZED,
                        CommonErrorMessages.AUTHENTICATION_REQUIRED);
    }

    private AccessDeniedHandler accessDeniedHandler(ConsoleSecurityResponseWriter responseWriter) {
        return (request, response, accessDeniedException) ->
                responseWriter.write(
                        response,
                        HttpStatus.FORBIDDEN,
                        ResultCode.FORBIDDEN,
                        CommonErrorMessages.ACCESS_DENIED);
    }
}
