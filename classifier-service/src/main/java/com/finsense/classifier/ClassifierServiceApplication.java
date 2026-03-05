package com.finsense.classifier;

import com.finsense.classifier.config.ClassificationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ClassificationProperties.class)
public class ClassifierServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClassifierServiceApplication.class, args);
    }
}
