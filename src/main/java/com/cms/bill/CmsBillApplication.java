package com.cms.bill;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CmsBillApplication {

    public static void main(String[] args) {
        SpringApplication.run(CmsBillApplication.class, args);
    }
}
