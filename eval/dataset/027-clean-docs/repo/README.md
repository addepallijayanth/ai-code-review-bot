# Inventory Service

Keeps stock levels for the warehouse and publishes a `stock-changed` event whenever a level changes.

## Running locally

1. Start PostgreSQL and Kafka: `docker compose up -d`
2. Run the service: `./gradlew bootRun`
3. Open http://localhost:8080/actuator/health to check it is up.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/inventory` | Database connection |
| `KAFKA_BOOTSTRAP` | `localhost:9092` | Event broker |
