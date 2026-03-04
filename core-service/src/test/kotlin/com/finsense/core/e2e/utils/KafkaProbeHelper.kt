package com.finsense.core.e2e.utils

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.Properties
import java.util.UUID

@Component
class KafkaProbeHelper(
    @Value("\${spring.kafka.bootstrap-servers}")
    private val bootstrapServers: String
) {
    fun consumeSingle(
        topic: String,
        timeout: Duration,
        predicate: (ConsumerRecord<String, String>) -> Boolean = { true }
    ): ConsumerRecord<String, String>? {
        val props = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, "probe-${UUID.randomUUID()}")
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        }

        KafkaConsumer<String, String>(props).use { consumer ->
            consumer.subscribe(listOf(topic))
            val deadline = System.currentTimeMillis() + timeout.toMillis()
            while (System.currentTimeMillis() < deadline) {
                val records = consumer.poll(Duration.ofMillis(300))
                for (record in records) {
                    if (predicate(record)) {
                        return record
                    }
                }
            }
        }

        return null
    }
}
