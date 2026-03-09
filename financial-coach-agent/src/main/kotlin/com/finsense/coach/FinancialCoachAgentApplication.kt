package com.finsense.coach

import com.finsense.coach.config.AppProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(AppProperties::class)
class FinancialCoachAgentApplication

fun main(args: Array<String>) {
    runApplication<FinancialCoachAgentApplication>(*args)
}
