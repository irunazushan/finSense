# Kafka Topics

| Топик | Продюсер | Консьюмер |
|-------|----------|----------|
| `raw-transactions` | Generator | Core |

## Docker Compose

```yaml
# фрагмент docker-compose.infrastructure.yml
zookeeper:
  image: confluentinc/cp-zookeeper:7.5.0
  environment:
    ZOOKEEPER_CLIENT_PORT: 2181
```

## Next Section

Some content here
