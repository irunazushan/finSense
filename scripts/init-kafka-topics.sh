#!/bin/bash

# init-kafka-topics.sh
# Скрипт ожидает, пока Kafka станет доступной, и создаёт все необходимые топики.

BOOTSTRAP_SERVER="kafka:9092"
SLEEP_INTERVAL=5
MAX_ATTEMPTS=30

echo "Waiting for Kafka to be available at $BOOTSTRAP_SERVER..."

attempt=0
until kafka-topics --bootstrap-server "$BOOTSTRAP_SERVER" --list > /dev/null 2>&1; do
  attempt=$((attempt + 1))
  if [ $attempt -ge $MAX_ATTEMPTS ]; then
    echo "Kafka is not available after $MAX_ATTEMPTS attempts. Exiting."
    exit 1
  fi
  echo "Kafka not ready yet (attempt $attempt/$MAX_ATTEMPTS). Waiting $SLEEP_INTERVAL seconds..."
  sleep $SLEEP_INTERVAL
done

echo "Kafka is ready. Creating topics..."

# Функция создания топика с проверкой
create_topic() {
  local topic=$1
  local partitions=$2
  local retention_ms=$3

  echo "Creating topic: $topic"
  kafka-topics --create \
    --if-not-exists \
    --bootstrap-server "$BOOTSTRAP_SERVER" \
    --topic "$topic" \
    --partitions "$partitions" \
    --replication-factor 1 \
    --config "retention.ms=$retention_ms"
}

# Создаём все топики с заданными параметрами
create_topic "raw-transactions"             3 604800000   # 7 дней
create_topic "llm-classifier-requests"      2 259200000   # 3 дня
create_topic "llm-classifier-responses"     2 259200000   # 3 дня
create_topic "coach-requests"               2 604800000   # 7 дней
create_topic "coach-responses"              2 259200000   # 3 дня

echo "All topics created successfully."