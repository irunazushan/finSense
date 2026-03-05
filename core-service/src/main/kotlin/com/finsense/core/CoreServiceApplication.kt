package com.finsense.core

import com.finsense.core.config.AppProperties
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.core.env.Environment
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AppProperties::class)
class CoreServiceApplication {
    @PostConstruct
    fun logEnv() {
        println("===== KAFKA_BOOTSTRAP_SERVERS from env: ${System.getenv("KAFKA_BOOTSTRAP_SERVERS")}")
        // Also print the resolved property if you have a specific key
        println("===== Resolved bootstrap.servers: ${environment.getProperty("kafka.bootstrap-servers")}")
    }

    @Autowired
    lateinit var environment: Environment
}

fun main(args: Array<String>) {
    runApplication<CoreServiceApplication>(*args)
}
