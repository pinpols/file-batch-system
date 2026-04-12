package com.example.batch.console.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * API 版本化支持：
 *
 * <ol>
 *   <li>/api/v1/console/** → /api/console/** 路径重写
 *   <li>Accept-Version / X-API-Version 头回显
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
public class ConsoleApiVersionConfiguration {

  private static final String CURRENT_VERSION = "1";
  private static final String VERSION_HEADER = "X-API-Version";
  private static final String ACCEPT_VERSION_HEADER = "Accept-Version";
  private static final String V1_PREFIX = "/api/v1/console/";
  private static final String UNVERSIONED_PREFIX = "/api/console/";

  @Bean
  FilterRegistrationBean<OncePerRequestFilter> apiVersionFilter() {
    FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>();
    registration.setFilter(
        new OncePerRequestFilter() {
          @Override
          protected void doFilterInternal(
              HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
              throws ServletException, IOException {
            response.setHeader(VERSION_HEADER, CURRENT_VERSION);
            String uri = request.getRequestURI();
            if (uri.startsWith(V1_PREFIX)) {
              String rewritten = UNVERSIONED_PREFIX + uri.substring(V1_PREFIX.length());
              HttpServletRequest wrapped =
                  new HttpServletRequestWrapper(request) {
                    @Override
                    public String getRequestURI() {
                      return rewritten;
                    }

                    @Override
                    public String getServletPath() {
                      return rewritten;
                    }
                  };
              filterChain.doFilter(wrapped, response);
              return;
            }
            filterChain.doFilter(request, response);
          }
        });
    registration.addUrlPatterns("/api/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }
}
