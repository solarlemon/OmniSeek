package com.example.omniseek;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OmniSeekApplication {

    public static void main(String[] args) {
        SpringApplication.run(OmniSeekApplication.class, args);
    }

}
