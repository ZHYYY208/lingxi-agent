package com.lingxi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LingxiBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(LingxiBackendApplication.class, args);
	}

}
