package com.xuexijiesuo.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Web 管理后台后端独立启动入口（8081），仅加载 web 包内组件 */
@SpringBootApplication
public class WebAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebAdminApplication.class, args);
    }
}
