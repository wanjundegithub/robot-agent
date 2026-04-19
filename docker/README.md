# Phase 2 Middleware Stack

本目录提供 Phase 2 所需中间件的 Docker 编排:

- MySQL 8
- Redis 7
- PostgreSQL + pgvector

## 启动

```powershell
docker compose -f docker-compose.phase2.yml up -d
```

## 访问

- Redis: `localhost:6379`
- MySQL: `localhost:3306`
- pgvector(PostgreSQL): `localhost:5432`

## 本地服务环境变量

启动 Java / Python 前建议设置:

```powershell
$env:ROBOT_DB_URL='jdbc:mysql://localhost:3306/robot_agent?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true'
$env:ROBOT_DB_USERNAME='root'
$env:ROBOT_DB_PASSWORD='root'
$env:ROBOT_REDIS_URL='redis://localhost:6379/0'
$env:ROBOT_VECTOR_DSN='postgresql://robot:robot@localhost:5432/robot_vector'
$env:ROBOT_OTEL_ENABLED='false'
```

Java 侧默认使用:

- Prometheus: `/actuator/prometheus`

Python 侧默认使用:

- Redis: `redis://localhost:6379/0`
- pgvector: `postgresql://robot:robot@localhost:5432/robot_vector`
- Prometheus: `/metrics`
