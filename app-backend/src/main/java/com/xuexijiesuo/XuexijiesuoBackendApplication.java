package com.xuexijiesuo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // W5: 补单/对账定时任务（生产换 XXL-Job，见技术架构 §3.2）
public class XuexijiesuoBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(XuexijiesuoBackendApplication.class, args);
	}

}
