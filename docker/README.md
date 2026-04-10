# Phase 2 Middleware Stack

本目录提供 Phase 2 所需中间件的 Docker 编排:

- Redis 7
- PostgreSQL + pgvector
- OpenTelemetry Collector
- Jaeger
- Prometheus
- Grafana

## 启动

```powershell
docker compose -f docker-compose.phase2.yml up -d
```

## 访问

- Redis: `localhost:6379`
- pgvector(PostgreSQL): `localhost:5432`
- OTLP gRPC: `localhost:4317`
- OTLP HTTP: `localhost:4318`
- Jaeger: `http://localhost:16686`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3001` (`admin/admin`)

## 本地服务环境变量

启动 Java / Python 前建议设置:

```powershell
$env:ROBOT_REDIS_URL='redis://localhost:6379/0'
$env:ROBOT_VECTOR_DSN='postgresql://robot:robot@localhost:5432/robot_vector'
$env:ROBOT_OTEL_EXPORTER_ENDPOINT='http://localhost:4317'
```

Java 侧默认使用:

- OTLP traces: `http://localhost:4318/v1/traces`
- Prometheus: `/actuator/prometheus`

Python 侧默认使用:

- Redis: `redis://localhost:6379/0`
- pgvector: `postgresql://robot:robot@localhost:5432/robot_vector`
- OTLP exporter: `http://localhost:4317`
- Prometheus: `/metrics`
