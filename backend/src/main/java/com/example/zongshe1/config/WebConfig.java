package com.example.zongshe1.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 确保静态资源可以访问，包括HTML文件
        registry.addResourceHandler("/**")
                .addResourceLocations(
                        "classpath:/static/",
                        "classpath:/public/",
                        "classpath:/META-INF/resources/"
                )
                .resourceChain(false);

        // 明确配置HTML文件的访问
        registry.addResourceHandler("*.html")
                .addResourceLocations("classpath:/static/");

        // 配置CSS、JS等静态资源
        registry.addResourceHandler(
                        "*.css",
                        "*.js",
                        "*.png",
                        "*.jpg",
                        "*.jpeg",
                        "*.gif",
                        "*.ico",
                        "*.svg"
                )
                .addResourceLocations("classpath:/static/");
    }
}
