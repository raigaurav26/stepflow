package com.stepflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class StepFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(StepFlowApplication.class, args);
    }
}
