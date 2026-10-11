package in.gov.slate.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuditTrailInterceptor auditTrailInterceptor;

    public WebMvcConfig(AuditTrailInterceptor auditTrailInterceptor) {
        this.auditTrailInterceptor = auditTrailInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auditTrailInterceptor).addPathPatterns("/api/**");
    }
}
