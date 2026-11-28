package com.scenic.digitalhuman;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 开启定时任务调度（第十二周 A3）：配合 SchedulerConfig 的 4 线程池跑三个 cron 任务 */
@EnableScheduling
@SpringBootApplication
public class DigitalHumanApplication {

    public static void main(String[] args) {
        SpringApplication.run(DigitalHumanApplication.class, args);
    }
}
