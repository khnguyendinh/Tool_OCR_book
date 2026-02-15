package com.ocrbook;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class OcrBookApplication {

    public static void main(String[] args) {
        SpringApplication.run(OcrBookApplication.class, args);
    }
}
