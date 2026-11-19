package com.example.inboxdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class InboxDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(InboxDemoApplication.class, args);
    }
}
