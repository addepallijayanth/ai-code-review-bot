package com.example.reviewbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ReviewBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReviewBotApplication.class, args);
    }
}
