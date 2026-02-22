# Project Documentation

## kafka connection
Kafka is an event streaming platform. It provides distributed connectivity between services and applications.

Connection parameters:
- Host: localhost
- Port: 9092
- Timeout: 30s

## kafka settings
- min.insync.replicas: 2
- log.retention.hours: 168
- num.partitions: 3

### broker configuration
Broker-specific settings for Kafka deployment.

Configure each broker with:
1. broker.id
2. zookeeper.connect
3. listeners

## API documentation
REST API endpoints for integration with finSense platform.

### authentication endpoints
POST /auth/login - User login
POST /auth/refresh - Refresh token

### data endpoints
GET /api/data/summary - Get summary data
POST /api/data/export - Export data

## Advanced Settings
Settings for production deployments & performance tuning.

### performance tuning
- Enable compression: true
- Buffer memory: 64MB
- Batch size: 16KB

### monitoring
Setup CloudWatch metrics & dashboards for production monitoring.
