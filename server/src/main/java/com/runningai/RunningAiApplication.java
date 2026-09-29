package com.runningai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RunningAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(RunningAiApplication.class, args);
    }
}
