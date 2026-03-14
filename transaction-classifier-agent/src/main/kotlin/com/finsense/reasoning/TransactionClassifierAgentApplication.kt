package com.finsense.reasoning

import com.finsense.reasoning.config.AppProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(AppProperties::class)
class TransactionClassifierAgentApplication

fun main(args: Array<String>) {
    runApplication<TransactionClassifierAgentApplication>(*args)
}
