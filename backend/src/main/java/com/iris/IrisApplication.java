package com.iris;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties
public class IrisApplication {

    public static void main(String[] args) {
        SpringApplication.run(IrisApplication.class, args);
    }
}
