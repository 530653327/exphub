package com.exphub;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.exphub.mapper")
public class ExpHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExpHubApplication.class, args);
    }
}
