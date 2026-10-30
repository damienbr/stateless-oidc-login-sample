package com.example.voltahome.demo;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * DEMO ONLY. The SPA is one static page (static/index.html), served at "/" by Spring Boot. The error routes the itsme
 * flows redirect to (app.login-error-url, app.confirmation-error-url) serve the same page, which reads the code.
 */
@Configuration
class DemoWebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/login-error").setViewName("forward:/index.html");
        registry.addViewController("/confirmation-error").setViewName("forward:/index.html");
    }
}
