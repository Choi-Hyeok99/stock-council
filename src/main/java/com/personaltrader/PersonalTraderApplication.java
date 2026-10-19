package com.personaltrader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PersonalTraderApplication {
    public static void main(String[] args) {
        SpringApplication.run(PersonalTraderApplication.class, args);
    }
}
