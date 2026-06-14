# Knowledge Center Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the full-screen knowledge center with MinIO-backed original files, configurable embedding models, hybrid retrieval, explicit knowledge-space binding, and parallel intent/knowledge routing.

**Architecture:** Java Backend owns metadata, MinIO object writes, task orchestration, binding state, and user-facing APIs. Python AI owns parsing for `pdf/docx/txt/md`, chunking, embedding calls, pgvector writes, hybrid retrieval, and citation-grounded answer generation. Frontend adds a full-screen Knowledge Center panel using the same shell, colors, spacing, and panel style as the existing service robot console.

**Tech Stack:** Spring Boot 3, JPA, MySQL, MinIO Java SDK, Apache POI for legacy `.doc`, FastAPI, psycopg + pgvector, HTTP embedding adapters, React + Vite + Playwright.

---

## Scope Boundary

This plan implements the first production vertical slice from the approved spec:

- Text and common documents only: `txt`, `md`, `pdf`, `docx`, `doc`.
- No image OCR.
- Original files and long text objects in MinIO.
- Metadata and task state in MySQL.
- Chunks, keywords, search terms, vectors, and source metadata in PostgreSQL + pgvector.
- Default embedding profile: `embedding-bge-m3`, `bge-m3`, dimension `1024`.
- Explicit knowledge-space binding for sessions/workflows. No binding means no knowledge retrieval.
- Parallel intent recognition and knowledge retrieval with configurable thresholds.

## File Structure

### Middleware and Config

- Modify: `docker-compose.yml`
  Adds `minio` service, `minio-data` volume, MinIO environment variables, and Java backend MinIO env vars.
- Modify: `docker-compose.prod.yml`
  Adds production MinIO service and data volume path.
- Modify: `java-backend/src/main/resources/application.yml`
  Adds `robot.knowledge.storage`, `robot.knowledge.embedding`, `robot.knowledge.retrieval`, and `robot.knowledge.route`.
- Modify: `java-backend/pom.xml`
  Adds MinIO SDK and Apache POI dependencies.
- Modify: `python-ai/requirements.txt`
  Adds `pypdf` and `python-docx`.

### Java Backend

- Create: `java-backend/src/main/java/robot/agent/config/KnowledgeProperties.java`
  Typed configuration for storage, embedding, retrieval, and route thresholds.
- Create: `java-backend/src/main/java/robot/agent/service/knowledge/KnowledgeObjectStorage.java`
  Small interface for object storage operations.
- Create: `java-backend/src/main/java/robot/agent/service/knowledge/MinioKnowledgeObjectStorage.java`
  MinIO implementation, bucket creation, object upload, presigned URL generation.
- Create: `java-backend/src/main/java/robot/agent/service/knowledge/SafeObjectKeyFactory.java`
  Builds safe MinIO keys and sanitizes filenames.
- Create: `java-backend/src/main/java/robot/agent/service/knowledge/LegacyDocTextExtractor.java`
  Uses Apache POI HWPF for `.doc` text extraction.
- Create: `java-backend/src/main/java/robot/agent/model/KnowledgeTask.java`
  MySQL task record for ingestion stages and retry.
- Create: `java-backend/src/main/java/robot/agent/model/KnowledgeTaskStatus.java`
  Task lifecycle enum.
- Create: `java-backend/src/main/java/robot/agent/model/KnowledgeTaskStage.java`
  Ingestion stage enum.
- Create: `java-backend/src/main/java/robot/agent/repository/KnowledgeTaskRepository.java`
  JPA queries for task list, document tasks, and retry lookup.
- Modify: `java-backend/src/main/java/robot/agent/model/KnowledgeDocument.java`
  Adds source type, raw bucket/key/etag/content type, extracted object key, content hash, generated summary/keywords, and index version.
- Modify: `java-backend/src/main/java/robot/agent/model/KnowledgeDocumentStatus.java`
  Adds `READY` and `DELETED` while keeping existing states.
- Create: `java-backend/src/main/java/robot/agent/dto/request/CreateKnowledgeDocumentRequest.java`
  Text knowledge create request.
- Create: `java-backend/src/main/java/robot/agent/dto/response/KnowledgeDocumentResponse.java`
  Knowledge item list/detail response.
- Create: `java-backend/src/main/java/robot/agent/dto/response/KnowledgeTaskResponse.java`
  Task list/detail response.
- Create: `java-backend/src/main/java/robot/agent/dto/request/KnowledgeSearchRequest.java`
  Independent knowledge search request.
- Create: `java-backend/src/main/java/robot/agent/dto/response/KnowledgeSearchResponse.java`
  Search hits, summary answer, and citations.
- Create: `java-backend/src/main/java/robot/agent/service/knowledge/PythonKnowledgeClient.java`
  Java HTTP client to Python AI ingestion and search endpoints.
- Modify: `java-backend/src/main/java/robot/agent/service/KnowledgeService.java`
  Adds documents, tasks, retry, search, default embedding model, and task orchestration.
- Modify: `java-backend/src/main/java/robot/agent/controller/KnowledgeController.java`
  Adds document upload/text APIs, task APIs, search APIs, and presigned preview APIs.

### Python AI

- Create: `python-ai/src/core/embedding_runtime.py`
  Embedding API adapter that uses `model_code`, provider configs, model records, and dimension validation.
- Create: `python-ai/src/core/document_processing.py`
  Extracts text from MinIO-provided object bytes for `pdf/docx/txt/md`; receives Java-extracted text for `.doc`.
- Create: `python-ai/src/core/chunking.py`
  Deterministic chunking, token/search-term normalization, keyword extraction, and n-gram fallback.
- Create: `python-ai/src/core/knowledge_ingestion.py`
  Ingestion service that extracts text, chunks, embeds, writes pgvector rows, and returns task summary.
- Modify: `python-ai/src/core/settings.py`
  Adds vector dimension, retrieval weights, query n-gram range, and default embedding model code.
- Modify: `python-ai/src/core/knowledge_store.py`
  Upgrades pgvector schema to `knowledge_chunks`, adds vector + keyword + hybrid search, and index version filtering.
- Modify: `python-ai/src/api/models.py`
  Adds ingestion and search request/response models.
- Modify: `python-ai/src/api/main.py`
  Adds `/api/knowledge/ingest`, `/api/knowledge/search`, and `/api/knowledge/answer`.
- Modify: `python-ai/src/nodes/knowledge.py`
  Uses the upgraded search result shape and citation fields.

### Frontend

- Modify: `frontend/src/types/index.ts`
  Adds knowledge-space, document, task, search, citation, and binding types.
- Modify: `frontend/src/services/api.ts`
  Adds knowledge center API functions.
- Create: `frontend/src/components/KnowledgeCenterPanel.tsx`
  Full-screen knowledge center with plain left subnav, knowledge spaces, tasks, and search views.
- Modify: `frontend/src/App.tsx`
  Adds `knowledge` page key, nav tab, and page rendering.
- Modify: `frontend/src/index.css`
  Adds knowledge center layout styles consistent with existing console style.
- Create: `frontend/tests/e2e/knowledge-center.spec.ts`
  Verifies full-screen layout, style constraints, space list, add-space entry, add-knowledge entry, task page, and search page.

### Route Binding

- Create: `java-backend/src/main/java/robot/agent/model/KnowledgeBindingScope.java`
  Enum for `SESSION` and `WORKFLOW`.
- Create: `java-backend/src/main/java/robot/agent/model/KnowledgeBinding.java`
  Binding table for explicit knowledge-space bindings.
- Create: `java-backend/src/main/java/robot/agent/repository/KnowledgeBindingRepository.java`
  Queries active bindings by scope and target.
- Create: `java-backend/src/main/java/robot/agent/dto/request/UpdateKnowledgeBindingsRequest.java`
  Replaces a target's binding set.
- Create: `java-backend/src/main/java/robot/agent/dto/response/KnowledgeBindingResponse.java`
  Returns bound space ids and binding version.
- Create: `java-backend/src/main/java/robot/agent/service/knowledge/KnowledgeRouteDecisionService.java`
  Pure decision function for `INTENT`, `KNOWLEDGE`, `CLARIFY`, `FALLBACK`.
- Modify: `java-backend/src/main/java/robot/agent/service/WorkflowService.java`
  Loads binding context once, runs intent and knowledge branches concurrently, applies aggregation thresholds.

---

## Task 1: MinIO Middleware and Java Configuration

**Files:**
- Modify: `docker-compose.yml`
- Modify: `docker-compose.prod.yml`
- Modify: `java-backend/pom.xml`
- Modify: `java-backend/src/main/resources/application.yml`
- Create: `java-backend/src/main/java/robot/agent/config/KnowledgeProperties.java`
- Test: `java-backend/src/test/java/robot/agent/config/KnowledgePropertiesTest.java`
- Modify: `java-backend/src/test/java/robot/agent/config/LocalConfigurationTest.java`

- [ ] **Step 1: Write the failing Java property test**

Create `java-backend/src/test/java/robot/agent/config/KnowledgePropertiesTest.java`:

```java
package robot.agent.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgePropertiesTest {

    @Test
    void defaultsMatchKnowledgeCenterDesign() {
        KnowledgeProperties properties = new KnowledgeProperties();

        assertThat(properties.getStorage().getType()).isEqualTo("minio");
        assertThat(properties.getStorage().getMinio().getBucket()).isEqualTo("robot-knowledge");
        assertThat(properties.getStorage().getPresignedUrlTtlSeconds()).isEqualTo(300);
        assertThat(properties.getEmbedding().getDefaultModelCode()).isEqualTo("embedding-bge-m3");
        assertThat(properties.getEmbedding().getDefaultUpstreamModel()).isEqualTo("bge-m3");
        assertThat(properties.getEmbedding().getDimension()).isEqualTo(1024);
        assertThat(properties.getRetrieval().getVectorWeight()).isEqualTo(0.7d);
        assertThat(properties.getRetrieval().getKeywordWeight()).isEqualTo(0.3d);
        assertThat(properties.getRoute().getIntentPrimaryThreshold()).isEqualTo(0.75d);
        assertThat(properties.getRoute().getKnowledgePrimaryThreshold()).isEqualTo(0.65d);
    }
}
```

- [ ] **Step 2: Run the property test to verify it fails**

Run:

```powershell
mvn -pl java-backend -Dtest=KnowledgePropertiesTest test
```

Expected: FAIL because `KnowledgeProperties` does not exist.

- [ ] **Step 3: Implement typed knowledge configuration**

Create `java-backend/src/main/java/robot/agent/config/KnowledgeProperties.java`:

```java
package robot.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "robot.knowledge")
public class KnowledgeProperties {
    private Storage storage = new Storage();
    private Embedding embedding = new Embedding();
    private Retrieval retrieval = new Retrieval();
    private Route route = new Route();

    public Storage getStorage() { return storage; }
    public void setStorage(Storage storage) { this.storage = storage == null ? new Storage() : storage; }
    public Embedding getEmbedding() { return embedding; }
    public void setEmbedding(Embedding embedding) { this.embedding = embedding == null ? new Embedding() : embedding; }
    public Retrieval getRetrieval() { return retrieval; }
    public void setRetrieval(Retrieval retrieval) { this.retrieval = retrieval == null ? new Retrieval() : retrieval; }
    public Route getRoute() { return route; }
    public void setRoute(Route route) { this.route = route == null ? new Route() : route; }

    public static class Storage {
        private String type = "minio";
        private Minio minio = new Minio();
        private int presignedUrlTtlSeconds = 300;
        private int maxFileSizeMb = 100;
        private List<String> allowedTypes = new ArrayList<>(List.of("txt", "pdf", "doc", "docx", "md"));

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public Minio getMinio() { return minio; }
        public void setMinio(Minio minio) { this.minio = minio == null ? new Minio() : minio; }
        public int getPresignedUrlTtlSeconds() { return presignedUrlTtlSeconds; }
        public void setPresignedUrlTtlSeconds(int presignedUrlTtlSeconds) { this.presignedUrlTtlSeconds = presignedUrlTtlSeconds; }
        public int getMaxFileSizeMb() { return maxFileSizeMb; }
        public void setMaxFileSizeMb(int maxFileSizeMb) { this.maxFileSizeMb = maxFileSizeMb; }
        public List<String> getAllowedTypes() { return allowedTypes; }
        public void setAllowedTypes(List<String> allowedTypes) { this.allowedTypes = allowedTypes == null ? new ArrayList<>() : allowedTypes; }
    }

    public static class Minio {
        private String endpoint = "http://localhost:9000";
        private String bucket = "robot-knowledge";
        private String accessKey = "robot";
        private String secretKey = "robot-knowledge-secret";
        private String region = "us-east-1";
        private boolean secure = false;

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public boolean isSecure() { return secure; }
        public void setSecure(boolean secure) { this.secure = secure; }
    }

    public static class Embedding {
        private String defaultModelCode = "embedding-bge-m3";
        private String defaultUpstreamModel = "bge-m3";
        private int dimension = 1024;
        private int batchSize = 32;
        private int timeoutMs = 30000;

        public String getDefaultModelCode() { return defaultModelCode; }
        public void setDefaultModelCode(String defaultModelCode) { this.defaultModelCode = defaultModelCode; }
        public String getDefaultUpstreamModel() { return defaultUpstreamModel; }
        public void setDefaultUpstreamModel(String defaultUpstreamModel) { this.defaultUpstreamModel = defaultUpstreamModel; }
        public int getDimension() { return dimension; }
        public void setDimension(int dimension) { this.dimension = dimension; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    }

    public static class Retrieval {
        private String mode = "hybrid";
        private int topK = 5;
        private double scoreThreshold = 0.65d;
        private double vectorWeight = 0.7d;
        private double keywordWeight = 0.3d;
        private double metadataBoost = 0.05d;
        private int vectorTopK = 20;
        private int keywordTopK = 20;
        private int queryNgramMin = 2;
        private int queryNgramMax = 4;

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public int getTopK() { return topK; }
        public void setTopK(int topK) { this.topK = topK; }
        public double getScoreThreshold() { return scoreThreshold; }
        public void setScoreThreshold(double scoreThreshold) { this.scoreThreshold = scoreThreshold; }
        public double getVectorWeight() { return vectorWeight; }
        public void setVectorWeight(double vectorWeight) { this.vectorWeight = vectorWeight; }
        public double getKeywordWeight() { return keywordWeight; }
        public void setKeywordWeight(double keywordWeight) { this.keywordWeight = keywordWeight; }
        public double getMetadataBoost() { return metadataBoost; }
        public void setMetadataBoost(double metadataBoost) { this.metadataBoost = metadataBoost; }
        public int getVectorTopK() { return vectorTopK; }
        public void setVectorTopK(int vectorTopK) { this.vectorTopK = vectorTopK; }
        public int getKeywordTopK() { return keywordTopK; }
        public void setKeywordTopK(int keywordTopK) { this.keywordTopK = keywordTopK; }
        public int getQueryNgramMin() { return queryNgramMin; }
        public void setQueryNgramMin(int queryNgramMin) { this.queryNgramMin = queryNgramMin; }
        public int getQueryNgramMax() { return queryNgramMax; }
        public void setQueryNgramMax(int queryNgramMax) { this.queryNgramMax = queryNgramMax; }
    }

    public static class Route {
        private double intentPrimaryThreshold = 0.75d;
        private double knowledgePrimaryThreshold = 0.65d;
        private double intentClarifyThreshold = 0.55d;
        private double knowledgeClarifyThreshold = 0.55d;
        private int intentTimeoutMs = 1200;
        private int knowledgeTimeoutMs = 1800;
        private int maxWaitMs = 2000;
        private boolean cancelLateBranch = true;

        public double getIntentPrimaryThreshold() { return intentPrimaryThreshold; }
        public void setIntentPrimaryThreshold(double intentPrimaryThreshold) { this.intentPrimaryThreshold = intentPrimaryThreshold; }
        public double getKnowledgePrimaryThreshold() { return knowledgePrimaryThreshold; }
        public void setKnowledgePrimaryThreshold(double knowledgePrimaryThreshold) { this.knowledgePrimaryThreshold = knowledgePrimaryThreshold; }
        public double getIntentClarifyThreshold() { return intentClarifyThreshold; }
        public void setIntentClarifyThreshold(double intentClarifyThreshold) { this.intentClarifyThreshold = intentClarifyThreshold; }
        public double getKnowledgeClarifyThreshold() { return knowledgeClarifyThreshold; }
        public void setKnowledgeClarifyThreshold(double knowledgeClarifyThreshold) { this.knowledgeClarifyThreshold = knowledgeClarifyThreshold; }
        public int getIntentTimeoutMs() { return intentTimeoutMs; }
        public void setIntentTimeoutMs(int intentTimeoutMs) { this.intentTimeoutMs = intentTimeoutMs; }
        public int getKnowledgeTimeoutMs() { return knowledgeTimeoutMs; }
        public void setKnowledgeTimeoutMs(int knowledgeTimeoutMs) { this.knowledgeTimeoutMs = knowledgeTimeoutMs; }
        public int getMaxWaitMs() { return maxWaitMs; }
        public void setMaxWaitMs(int maxWaitMs) { this.maxWaitMs = maxWaitMs; }
        public boolean isCancelLateBranch() { return cancelLateBranch; }
        public void setCancelLateBranch(boolean cancelLateBranch) { this.cancelLateBranch = cancelLateBranch; }
    }
}
```

- [ ] **Step 4: Add application.yml values**

Add under `robot:` in `java-backend/src/main/resources/application.yml`:

```yaml
  knowledge:
    storage:
      type: minio
      minio:
        endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
        bucket: ${MINIO_BUCKET:robot-knowledge}
        access-key: ${MINIO_ACCESS_KEY:robot}
        secret-key: ${MINIO_SECRET_KEY:robot-knowledge-secret}
        region: ${MINIO_REGION:us-east-1}
        secure: ${MINIO_SECURE:false}
      presigned-url-ttl-seconds: 300
      max-file-size-mb: 100
      allowed-types:
        - txt
        - pdf
        - doc
        - docx
        - md
    embedding:
      default-model-code: embedding-bge-m3
      default-upstream-model: bge-m3
      dimension: 1024
      batch-size: 32
      timeout-ms: 30000
    retrieval:
      mode: hybrid
      top-k: 5
      score-threshold: 0.65
      vector-weight: 0.7
      keyword-weight: 0.3
      metadata-boost: 0.05
      vector-top-k: 20
      keyword-top-k: 20
      query-ngram-min: 2
      query-ngram-max: 4
    route:
      intent-primary-threshold: 0.75
      knowledge-primary-threshold: 0.65
      intent-clarify-threshold: 0.55
      knowledge-clarify-threshold: 0.55
      intent-timeout-ms: 1200
      knowledge-timeout-ms: 1800
      max-wait-ms: 2000
      cancel-late-branch: true
```

- [ ] **Step 5: Add Java dependencies**

Add these dependencies to `java-backend/pom.xml`:

```xml
<dependency>
    <groupId>io.minio</groupId>
    <artifactId>minio</artifactId>
    <version>8.5.17</version>
</dependency>
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi</artifactId>
    <version>5.2.5</version>
</dependency>
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-scratchpad</artifactId>
    <version>5.2.5</version>
</dependency>
```

- [ ] **Step 6: Add MinIO to docker compose**

In `docker-compose.yml`, add `MINIO_*` environment values to `java-backend` and add the `minio` service:

```yaml
      MINIO_ENDPOINT: http://minio:9000
      MINIO_BUCKET: ${MINIO_BUCKET:-robot-knowledge}
      MINIO_ACCESS_KEY: ${MINIO_ACCESS_KEY:-robot}
      MINIO_SECRET_KEY: ${MINIO_SECRET_KEY:-robot-knowledge-secret}
```

```yaml
  minio:
    image: ${MINIO_IMAGE:-minio/minio:latest}
    container_name: robot-agent-minio
    command: ["server", "/data", "--console-address", ":9001"]
    environment:
      MINIO_ROOT_USER: ${MINIO_ACCESS_KEY:-robot}
      MINIO_ROOT_PASSWORD: ${MINIO_SECRET_KEY:-robot-knowledge-secret}
    ports:
      - "${MINIO_BIND_ADDRESS:-127.0.0.1}:${MINIO_PORT:-9000}:9000"
      - "${MINIO_CONSOLE_BIND_ADDRESS:-127.0.0.1}:${MINIO_CONSOLE_PORT:-9001}:9001"
    volumes:
      - minio-data:/data
    healthcheck:
      test: ["CMD", "mc", "ready", "local"]
      interval: 5s
      timeout: 5s
      retries: 30
      start_period: 20s
    restart: unless-stopped
```

Add the volume:

```yaml
  minio-data:
```

In `docker-compose.prod.yml`, add:

```yaml
  minio:
    image: ${MINIO_IMAGE:-minio/minio:latest}
    container_name: robot-agent-minio
    command: ["server", "/data", "--console-address", ":9001"]
    environment:
      MINIO_ROOT_USER: ${MINIO_ACCESS_KEY:-robot}
      MINIO_ROOT_PASSWORD: ${MINIO_SECRET_KEY:-robot-knowledge-secret}
    ports:
      - "${MINIO_BIND_ADDRESS:-127.0.0.1}:${MINIO_PORT:-9000}:9000"
      - "${MINIO_CONSOLE_BIND_ADDRESS:-127.0.0.1}:${MINIO_CONSOLE_PORT:-9001}:9001"
    volumes:
      - /data/docker-data/minio/data:/data
    healthcheck:
      test: ["CMD", "mc", "ready", "local"]
      interval: 5s
      timeout: 5s
      retries: 30
      start_period: 20s
```

- [ ] **Step 7: Update local configuration test**

Append assertions to `java-backend/src/test/java/robot/agent/config/LocalConfigurationTest.java`:

```java
assertThat(applicationYaml).contains("knowledge:");
assertThat(applicationYaml).contains("storage:");
assertThat(applicationYaml).contains("minio:");
assertThat(dockerCompose).contains("robot-agent-minio");
assertThat(dockerCompose).contains("minio-data");
```

- [ ] **Step 8: Run tests and commit**

Run:

```powershell
mvn -pl java-backend -Dtest=KnowledgePropertiesTest,LocalConfigurationTest test
```

Expected: PASS.

Commit:

```powershell
git add docker-compose.yml docker-compose.prod.yml java-backend/pom.xml java-backend/src/main/resources/application.yml java-backend/src/main/java/robot/agent/config/KnowledgeProperties.java java-backend/src/test/java/robot/agent/config/KnowledgePropertiesTest.java java-backend/src/test/java/robot/agent/config/LocalConfigurationTest.java
git commit -m "feat: configure knowledge minio and retrieval settings"
```

## Task 2: Java MinIO Storage and Knowledge Document APIs

**Files:**
- Create: `java-backend/src/main/java/robot/agent/service/knowledge/KnowledgeObjectStorage.java`
- Create: `java-backend/src/main/java/robot/agent/service/knowledge/StoredKnowledgeObject.java`
- Create: `java-backend/src/main/java/robot/agent/service/knowledge/SafeObjectKeyFactory.java`
- Create: `java-backend/src/main/java/robot/agent/service/knowledge/MinioKnowledgeObjectStorage.java`
- Create: `java-backend/src/main/java/robot/agent/service/knowledge/LegacyDocTextExtractor.java`
- Modify: `java-backend/src/main/java/robot/agent/model/KnowledgeDocument.java`
- Modify: `java-backend/src/main/java/robot/agent/model/KnowledgeDocumentStatus.java`
- Modify: `java-backend/src/main/java/robot/agent/repository/KnowledgeDocumentRepository.java`
- Create: `java-backend/src/main/java/robot/agent/dto/request/CreateKnowledgeDocumentRequest.java`
- Create: `java-backend/src/main/java/robot/agent/dto/response/KnowledgeDocumentResponse.java`
- Modify: `java-backend/src/main/java/robot/agent/service/KnowledgeService.java`
- Modify: `java-backend/src/main/java/robot/agent/controller/KnowledgeController.java`
- Test: `java-backend/src/test/java/robot/agent/service/knowledge/SafeObjectKeyFactoryTest.java`
- Test: `java-backend/src/test/java/robot/agent/service/KnowledgeServiceDocumentTest.java`

- [ ] **Step 1: Write object-key tests**

Create `java-backend/src/test/java/robot/agent/service/knowledge/SafeObjectKeyFactoryTest.java`:

```java
package robot.agent.service.knowledge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SafeObjectKeyFactoryTest {

    @Test
    void buildsRawObjectKeyWithSanitizedFilename() {
        SafeObjectKeyFactory factory = new SafeObjectKeyFactory();

        String key = factory.rawObjectKey(1L, "kb_product", "doc_001", "../产品 手册.pdf");

        assertThat(key).isEqualTo("raw/1/kb_product/doc_001/产品_手册.pdf");
    }

    @Test
    void buildsExtractedTextObjectKey() {
        SafeObjectKeyFactory factory = new SafeObjectKeyFactory();

        String key = factory.extractedTextObjectKey(1L, "kb_product", "doc_001");

        assertThat(key).isEqualTo("extracted/1/kb_product/doc_001/content.json");
    }
}
```

- [ ] **Step 2: Run object-key test to verify it fails**

Run:

```powershell
mvn -pl java-backend -Dtest=SafeObjectKeyFactoryTest test
```

Expected: FAIL because `SafeObjectKeyFactory` does not exist.

- [ ] **Step 3: Implement object key and storage DTO**

Create `java-backend/src/main/java/robot/agent/service/knowledge/SafeObjectKeyFactory.java`:

```java
package robot.agent.service.knowledge;

import org.springframework.stereotype.Component;

@Component
public class SafeObjectKeyFactory {

    public String rawObjectKey(Long workspaceId, String kbCode, String docId, String originalFilename) {
        return "raw/%s/%s/%s/%s".formatted(workspaceId, cleanPathPart(kbCode), cleanPathPart(docId), sanitizeFilename(originalFilename));
    }

    public String longTextObjectKey(Long workspaceId, String kbCode, String docId) {
        return "raw/%s/%s/%s/content.txt".formatted(workspaceId, cleanPathPart(kbCode), cleanPathPart(docId));
    }

    public String extractedTextObjectKey(Long workspaceId, String kbCode, String docId) {
        return "extracted/%s/%s/%s/content.json".formatted(workspaceId, cleanPathPart(kbCode), cleanPathPart(docId));
    }

    private String sanitizeFilename(String filename) {
        String value = filename == null || filename.isBlank() ? "knowledge.txt" : filename.trim();
        value = value.replace("\\", "/");
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        value = value.replaceAll("[\\r\\n\\t]+", "_").replaceAll("[ ]+", "_");
        value = value.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}._-]", "_");
        return value.isBlank() ? "knowledge.txt" : value;
    }

    private String cleanPathPart(String value) {
        String normalized = value == null ? "" : value.trim();
        normalized = normalized.replaceAll("[^A-Za-z0-9_-]", "_");
        return normalized.isBlank() ? "default" : normalized;
    }
}
```

Create `java-backend/src/main/java/robot/agent/service/knowledge/StoredKnowledgeObject.java`:

```java
package robot.agent.service.knowledge;

public record StoredKnowledgeObject(
        String bucket,
        String objectKey,
        String etag,
        String contentType,
        long size
) {
}
```

Create `java-backend/src/main/java/robot/agent/service/knowledge/KnowledgeObjectStorage.java`:

```java
package robot.agent.service.knowledge;

import java.io.InputStream;
import java.net.URL;

public interface KnowledgeObjectStorage {
    StoredKnowledgeObject put(String objectKey, InputStream inputStream, long size, String contentType);

    URL presignedGetUrl(String objectKey);
}
```

- [ ] **Step 4: Implement MinIO storage**

Create `java-backend/src/main/java/robot/agent/service/knowledge/MinioKnowledgeObjectStorage.java`:

```java
package robot.agent.service.knowledge;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import org.springframework.stereotype.Service;
import robot.agent.config.KnowledgeProperties;

import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.TimeUnit;

@Service
public class MinioKnowledgeObjectStorage implements KnowledgeObjectStorage {
    private final KnowledgeProperties properties;
    private final MinioClient minioClient;

    public MinioKnowledgeObjectStorage(KnowledgeProperties properties) {
        this.properties = properties;
        KnowledgeProperties.Minio minio = properties.getStorage().getMinio();
        this.minioClient = MinioClient.builder()
                .endpoint(minio.getEndpoint())
                .credentials(minio.getAccessKey(), minio.getSecretKey())
                .region(minio.getRegion())
                .build();
    }

    @Override
    public StoredKnowledgeObject put(String objectKey, InputStream inputStream, long size, String contentType) {
        try {
            ensureBucket();
            String bucket = properties.getStorage().getMinio().getBucket();
            var result = minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(inputStream, size, -1)
                    .contentType(contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType)
                    .build());
            return new StoredKnowledgeObject(bucket, objectKey, result.etag(), contentType, size);
        } catch (Exception exc) {
            throw new IllegalStateException("Failed to write knowledge object to MinIO: " + objectKey, exc);
        }
    }

    @Override
    public URL presignedGetUrl(String objectKey) {
        try {
            String url = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(properties.getStorage().getMinio().getBucket())
                    .object(objectKey)
                    .expiry(properties.getStorage().getPresignedUrlTtlSeconds(), TimeUnit.SECONDS)
                    .build());
            return new URL(url);
        } catch (Exception exc) {
            throw new IllegalStateException("Failed to create MinIO presigned URL: " + objectKey, exc);
        }
    }

    private void ensureBucket() throws Exception {
        String bucket = properties.getStorage().getMinio().getBucket();
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }
}
```

- [ ] **Step 5: Extend document entity and repository**

Modify `KnowledgeDocumentStatus` to:

```java
package robot.agent.model;

public enum KnowledgeDocumentStatus {
    PENDING,
    PROCESSING,
    READY,
    PROCESSED,
    FAILED,
    DELETED
}
```

Add fields to `KnowledgeDocument`:

```java
@Column(name = "source_type", length = 32)
private String sourceType;

@Column(name = "raw_content", columnDefinition = "TEXT")
private String rawContent;

@Column(name = "raw_bucket", length = 128)
private String rawBucket;

@Column(name = "raw_object_key", length = 512)
private String rawObjectKey;

@Column(name = "raw_etag", length = 128)
private String rawEtag;

@Column(name = "raw_content_type", length = 128)
private String rawContentType;

@Column(name = "extracted_object_key", length = 512)
private String extractedObjectKey;

@Column(name = "content_hash", length = 128)
private String contentHash;

@Column(name = "generated_title", length = 256)
private String generatedTitle;

@Column(name = "generated_summary", columnDefinition = "TEXT")
private String generatedSummary;

@Column(name = "generated_keywords", columnDefinition = "TEXT")
private String generatedKeywords;

@Column(name = "index_version")
private Integer indexVersion;
```

Add these getters and setters below the existing `createdAt` accessor methods:

```java
public String getSourceType() {
    return sourceType;
}

public void setSourceType(String sourceType) {
    this.sourceType = sourceType;
}

public String getRawContent() {
    return rawContent;
}

public void setRawContent(String rawContent) {
    this.rawContent = rawContent;
}

public String getRawBucket() {
    return rawBucket;
}

public void setRawBucket(String rawBucket) {
    this.rawBucket = rawBucket;
}

public String getRawObjectKey() {
    return rawObjectKey;
}

public void setRawObjectKey(String rawObjectKey) {
    this.rawObjectKey = rawObjectKey;
}

public String getRawEtag() {
    return rawEtag;
}

public void setRawEtag(String rawEtag) {
    this.rawEtag = rawEtag;
}

public String getRawContentType() {
    return rawContentType;
}

public void setRawContentType(String rawContentType) {
    this.rawContentType = rawContentType;
}

public String getExtractedObjectKey() {
    return extractedObjectKey;
}

public void setExtractedObjectKey(String extractedObjectKey) {
    this.extractedObjectKey = extractedObjectKey;
}

public String getContentHash() {
    return contentHash;
}

public void setContentHash(String contentHash) {
    this.contentHash = contentHash;
}

public String getGeneratedTitle() {
    return generatedTitle;
}

public void setGeneratedTitle(String generatedTitle) {
    this.generatedTitle = generatedTitle;
}

public String getGeneratedSummary() {
    return generatedSummary;
}

public void setGeneratedSummary(String generatedSummary) {
    this.generatedSummary = generatedSummary;
}

public String getGeneratedKeywords() {
    return generatedKeywords;
}

public void setGeneratedKeywords(String generatedKeywords) {
    this.generatedKeywords = generatedKeywords;
}

public Integer getIndexVersion() {
    return indexVersion;
}

public void setIndexVersion(Integer indexVersion) {
    this.indexVersion = indexVersion;
}
```

Modify `KnowledgeDocumentRepository`:

```java
package robot.agent.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import robot.agent.model.KnowledgeDocument;

import java.util.List;
import java.util.Optional;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {
    List<KnowledgeDocument> findByKbCodeAndVersionOrderByCreatedAtDesc(String kbCode, String version);

    List<KnowledgeDocument> findByKbCodeOrderByCreatedAtDesc(String kbCode);

    Optional<KnowledgeDocument> findByDocId(String docId);
}
```

- [ ] **Step 6: Add document request and response DTOs**

Create `CreateKnowledgeDocumentRequest`:

```java
package robot.agent.dto.request;

public class CreateKnowledgeDocumentRequest {
    private String title;
    private String description;
    private String content;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
```

Create `KnowledgeDocumentResponse`:

```java
package robot.agent.dto.response;

import robot.agent.model.KnowledgeDocument;
import robot.agent.model.KnowledgeDocumentStatus;

import java.time.LocalDateTime;

public class KnowledgeDocumentResponse {
    private String docId;
    private String kbCode;
    private String filename;
    private Long fileSize;
    private String sourceType;
    private KnowledgeDocumentStatus status;
    private Integer chunkCount;
    private String errorMessage;
    private String generatedTitle;
    private String generatedSummary;
    private String generatedKeywords;
    private Integer indexVersion;
    private LocalDateTime uploadedAt;
    private LocalDateTime processedAt;

    public static KnowledgeDocumentResponse fromEntity(KnowledgeDocument entity) {
        KnowledgeDocumentResponse response = new KnowledgeDocumentResponse();
        response.setDocId(entity.getDocId());
        response.setKbCode(entity.getKbCode());
        response.setFilename(entity.getFilename());
        response.setFileSize(entity.getFileSize());
        response.setSourceType(entity.getSourceType());
        response.setStatus(entity.getStatus());
        response.setChunkCount(entity.getChunkCount());
        response.setErrorMessage(entity.getErrorMessage());
        response.setGeneratedTitle(entity.getGeneratedTitle());
        response.setGeneratedSummary(entity.getGeneratedSummary());
        response.setGeneratedKeywords(entity.getGeneratedKeywords());
        response.setIndexVersion(entity.getIndexVersion());
        response.setUploadedAt(entity.getUploadedAt());
        response.setProcessedAt(entity.getProcessedAt());
        return response;
    }

    public String getDocId() { return docId; }
    public void setDocId(String docId) { this.docId = docId; }
    public String getKbCode() { return kbCode; }
    public void setKbCode(String kbCode) { this.kbCode = kbCode; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public KnowledgeDocumentStatus getStatus() { return status; }
    public void setStatus(KnowledgeDocumentStatus status) { this.status = status; }
    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getGeneratedTitle() { return generatedTitle; }
    public void setGeneratedTitle(String generatedTitle) { this.generatedTitle = generatedTitle; }
    public String getGeneratedSummary() { return generatedSummary; }
    public void setGeneratedSummary(String generatedSummary) { this.generatedSummary = generatedSummary; }
    public String getGeneratedKeywords() { return generatedKeywords; }
    public void setGeneratedKeywords(String generatedKeywords) { this.generatedKeywords = generatedKeywords; }
    public Integer getIndexVersion() { return indexVersion; }
    public void setIndexVersion(Integer indexVersion) { this.indexVersion = indexVersion; }
    public LocalDateTime getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(LocalDateTime uploadedAt) { this.uploadedAt = uploadedAt; }
    public LocalDateTime getProcessedAt() { return processedAt; }
    public void setProcessedAt(LocalDateTime processedAt) { this.processedAt = processedAt; }
}
```

- [ ] **Step 7: Write service tests for text document creation**

Create `java-backend/src/test/java/robot/agent/service/KnowledgeServiceDocumentTest.java` with a Mockito test that verifies text knowledge creates a `KnowledgeDocument` with `sourceType=TEXT`, `status=PENDING`, short `rawContent`, and default `indexVersion=1`. Use the same constructor pattern as `ModelConfigServiceTest`.

Core assertion block:

```java
assertThat(savedDocument.getKbCode()).isEqualTo("kb_product");
assertThat(savedDocument.getSourceType()).isEqualTo("TEXT");
assertThat(savedDocument.getStatus()).isEqualTo(KnowledgeDocumentStatus.PENDING);
assertThat(savedDocument.getRawContent()).isEqualTo("保修期为一年");
assertThat(savedDocument.getIndexVersion()).isEqualTo(1);
```

- [ ] **Step 8: Add document APIs**

Add endpoints to `KnowledgeController`:

```java
@GetMapping("/{kbCode}/documents")
public ResponseEntity<List<KnowledgeDocumentResponse>> getKnowledgeDocuments(@PathVariable String kbCode) {
    return ResponseEntity.ok(knowledgeService.getKnowledgeDocuments(kbCode));
}

@PostMapping("/{kbCode}/documents/text")
public ResponseEntity<KnowledgeDocumentResponse> createTextKnowledgeDocument(
        @RequestHeader(value = "X-User-Id", required = false) String userId,
        @PathVariable String kbCode,
        @RequestBody CreateKnowledgeDocumentRequest request
) {
    return ResponseEntity.ok(knowledgeService.createTextKnowledgeDocument(userId, kbCode, request));
}

@PostMapping("/{kbCode}/documents/files")
public ResponseEntity<KnowledgeDocumentResponse> uploadKnowledgeDocument(
        @RequestHeader(value = "X-User-Id", required = false) String userId,
        @PathVariable String kbCode,
        @RequestParam("file") MultipartFile file
) {
    return ResponseEntity.ok(knowledgeService.uploadKnowledgeDocument(userId, kbCode, file));
}
```

- [ ] **Step 9: Implement service methods and commit**

Add these methods to `KnowledgeService`:

```java
public List<KnowledgeDocumentResponse> getKnowledgeDocuments(String kbCode) {
    return knowledgeDocumentRepository.findByKbCodeOrderByCreatedAtDesc(kbCode)
            .stream()
            .filter(document -> document.getStatus() != KnowledgeDocumentStatus.DELETED)
            .map(KnowledgeDocumentResponse::fromEntity)
            .collect(Collectors.toList());
}
```

Add these constructor dependencies to `KnowledgeService`:

```java
private final KnowledgeDocumentRepository knowledgeDocumentRepository;
private final KnowledgeObjectStorage knowledgeObjectStorage;
private final SafeObjectKeyFactory safeObjectKeyFactory;
private final KnowledgeProperties knowledgeProperties;
```

Add this text document method:

```java
public KnowledgeDocumentResponse createTextKnowledgeDocument(String userId, String kbCode, CreateKnowledgeDocumentRequest request) {
    KnowledgeBase knowledgeBase = knowledgeBaseRepository.findByKbCode(kbCode)
            .orElseThrow(() -> new RuntimeException("Knowledge base not found: " + kbCode));
    accessControlService.requireAnyRole(userId, knowledgeBase.getWorkspaceId(), Set.of("workflow_admin", "knowledge_admin"));

    String content = request.getContent() == null ? "" : request.getContent().trim();
    if (content.isBlank()) {
        throw new IllegalArgumentException("Knowledge text content must not be empty");
    }

    String docId = "doc_" + UUID.randomUUID().toString().replace("-", "");
    KnowledgeDocument document = new KnowledgeDocument();
    document.setKbCode(kbCode);
    document.setVersion(firstNonBlank(knowledgeBase.getCurrentVersion(), "1"));
    document.setDocId(docId);
    document.setFilename(firstNonBlank(request.getTitle(), "文本知识") + ".txt");
    document.setSourceType("TEXT");
    document.setRawContentType("text/plain; charset=utf-8");
    document.setStatus(KnowledgeDocumentStatus.PENDING);
    document.setUploadedAt(LocalDateTime.now());
    document.setIndexVersion(1);

    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    document.setFileSize((long) bytes.length);
    document.setContentHash(sha256Hex(bytes));
    if (bytes.length <= 64 * 1024) {
        document.setRawContent(content);
    } else {
        String objectKey = safeObjectKeyFactory.longTextObjectKey(knowledgeBase.getWorkspaceId(), kbCode, docId);
        StoredKnowledgeObject stored = knowledgeObjectStorage.put(objectKey, new ByteArrayInputStream(bytes), bytes.length, "text/plain; charset=utf-8");
        document.setRawBucket(stored.bucket());
        document.setRawObjectKey(stored.objectKey());
        document.setRawEtag(stored.etag());
    }

    KnowledgeDocument saved = knowledgeDocumentRepository.save(document);
    return KnowledgeDocumentResponse.fromEntity(saved);
}
```

Add this file upload method:

```java
public KnowledgeDocumentResponse uploadKnowledgeDocument(String userId, String kbCode, MultipartFile file) {
    KnowledgeBase knowledgeBase = knowledgeBaseRepository.findByKbCode(kbCode)
            .orElseThrow(() -> new RuntimeException("Knowledge base not found: " + kbCode));
    accessControlService.requireAnyRole(userId, knowledgeBase.getWorkspaceId(), Set.of("workflow_admin", "knowledge_admin"));

    if (file == null || file.isEmpty()) {
        throw new IllegalArgumentException("Knowledge file must not be empty");
    }
    String filename = firstNonBlank(file.getOriginalFilename(), "knowledge.txt");
    String extension = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
    if (!knowledgeProperties.getStorage().getAllowedTypes().contains(extension)) {
        throw new IllegalArgumentException("Unsupported knowledge file type: " + extension);
    }
    long maxBytes = knowledgeProperties.getStorage().getMaxFileSizeMb() * 1024L * 1024L;
    if (file.getSize() > maxBytes) {
        throw new IllegalArgumentException("Knowledge file exceeds max size");
    }

    String docId = "doc_" + UUID.randomUUID().toString().replace("-", "");
    String objectKey = safeObjectKeyFactory.rawObjectKey(knowledgeBase.getWorkspaceId(), kbCode, docId, filename);
    try (InputStream inputStream = file.getInputStream()) {
        StoredKnowledgeObject stored = knowledgeObjectStorage.put(objectKey, inputStream, file.getSize(), file.getContentType());
        KnowledgeDocument document = new KnowledgeDocument();
        document.setKbCode(kbCode);
        document.setVersion(firstNonBlank(knowledgeBase.getCurrentVersion(), "1"));
        document.setDocId(docId);
        document.setFilename(filename);
        document.setFileSize(file.getSize());
        document.setSourceType("FILE");
        document.setRawBucket(stored.bucket());
        document.setRawObjectKey(stored.objectKey());
        document.setRawEtag(stored.etag());
        document.setRawContentType(stored.contentType());
        document.setStatus(KnowledgeDocumentStatus.PENDING);
        document.setUploadedAt(LocalDateTime.now());
        document.setIndexVersion(1);
        return KnowledgeDocumentResponse.fromEntity(knowledgeDocumentRepository.save(document));
    } catch (IOException exc) {
        throw new IllegalStateException("Failed to read uploaded knowledge file", exc);
    }
}
```

Add helper methods:

```java
private String firstNonBlank(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
}

private String sha256Hex(byte[] bytes) {
    try {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder builder = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    } catch (NoSuchAlgorithmException exc) {
        throw new IllegalStateException("SHA-256 is not available", exc);
    }
}
```

Run:

```powershell
mvn -pl java-backend -Dtest=SafeObjectKeyFactoryTest,KnowledgeServiceDocumentTest test
```

Expected: PASS.

Commit:

```powershell
git add java-backend/src/main/java/robot/agent/service/knowledge java-backend/src/main/java/robot/agent/model/KnowledgeDocument.java java-backend/src/main/java/robot/agent/model/KnowledgeDocumentStatus.java java-backend/src/main/java/robot/agent/repository/KnowledgeDocumentRepository.java java-backend/src/main/java/robot/agent/dto/request/CreateKnowledgeDocumentRequest.java java-backend/src/main/java/robot/agent/dto/response/KnowledgeDocumentResponse.java java-backend/src/main/java/robot/agent/service/KnowledgeService.java java-backend/src/main/java/robot/agent/controller/KnowledgeController.java java-backend/src/test/java/robot/agent/service/knowledge/SafeObjectKeyFactoryTest.java java-backend/src/test/java/robot/agent/service/KnowledgeServiceDocumentTest.java
git commit -m "feat: store knowledge originals in minio"
```

## Task 3: Python Embedding Runtime and pgvector Hybrid Store

**Files:**
- Modify: `python-ai/requirements.txt`
- Modify: `python-ai/src/core/settings.py`
- Create: `python-ai/src/core/embedding_runtime.py`
- Create: `python-ai/src/core/chunking.py`
- Modify: `python-ai/src/core/knowledge_store.py`
- Test: `python-ai/tests/test_core/test_embedding_runtime.py`
- Test: `python-ai/tests/test_core/test_chunking.py`
- Test: `python-ai/tests/test_core/test_knowledge_store_sql.py`

- [ ] **Step 1: Write embedding runtime tests**

Create `python-ai/tests/test_core/test_embedding_runtime.py`:

```python
from unittest.mock import AsyncMock, Mock, patch

import pytest

from src.core.embedding_runtime import embed_texts_with_model


@pytest.mark.asyncio
async def test_embed_texts_with_openai_compatible_provider_posts_embeddings_payload():
    provider_configs = {
        "embedding-provider": {
            "provider_code": "embedding-provider",
            "provider_type": "openai_compatible",
            "base_url": "https://embedding.example.com/v1",
            "api_key_secret_ref": "test-secret",
            "extra_headers": {"__meta__": {"embedding_path": "/embeddings"}},
        }
    }
    model_records = {
        "embedding-bge-m3": {
            "model_code": "embedding-bge-m3",
            "provider_code": "embedding-provider",
            "upstream_model_code": "bge-m3",
            "default_options": {"embedding_dimension": 3, "timeout_sec": 10},
        }
    }
    with patch("src.core.embedding_runtime.httpx.AsyncClient") as mock_client:
        response = Mock()
        response.raise_for_status.return_value = None
        response.json.return_value = {"data": [{"embedding": [0.1, 0.2, 0.3]}]}
        instance = AsyncMock()
        instance.__aenter__.return_value = instance
        instance.__aexit__.return_value = None
        instance.post.return_value = response
        mock_client.return_value = instance

        vectors = await embed_texts_with_model(
            texts=["保修期为一年"],
            model_code="embedding-bge-m3",
            provider_configs=provider_configs,
            model_records=model_records,
            expected_dimension=3,
        )

    assert vectors == [[0.1, 0.2, 0.3]]
    assert instance.post.call_args.args[0] == "https://embedding.example.com/v1/embeddings"
    assert instance.post.call_args.kwargs["json"]["model"] == "bge-m3"
    assert instance.post.call_args.kwargs["json"]["input"] == ["保修期为一年"]
```

- [ ] **Step 2: Run embedding test to verify it fails**

Run:

```powershell
cd python-ai
pytest tests/test_core/test_embedding_runtime.py -q
```

Expected: FAIL because `embedding_runtime.py` does not exist.

- [ ] **Step 3: Implement embedding runtime**

Create `python-ai/src/core/embedding_runtime.py`:

```python
from __future__ import annotations

from typing import Any, Dict, List

import httpx

from src.core.model_runtime import ModelConfigError, _provider_meta, _resolve_secret, _join_url, resolve_model_record, resolve_provider


async def embed_texts_with_model(
    texts: List[str],
    model_code: str,
    provider_configs: Dict[str, Dict[str, Any]],
    model_records: Dict[str, Dict[str, Any]],
    expected_dimension: int,
) -> List[List[float]]:
    if not texts:
        return []
    model_record = resolve_model_record(model_records, model_code)
    provider = resolve_provider(provider_configs, str(model_record.get("provider_code")))
    upstream_model_code = str(model_record.get("upstream_model_code") or model_record.get("model_code") or "").strip()
    if not upstream_model_code:
        raise ModelConfigError("Embedding model upstream_model_code is required")

    base_url = str(provider.get("base_url", "")).rstrip("/")
    if not base_url:
        raise ModelConfigError("Embedding provider base_url is required")

    meta = _provider_meta(provider)
    request_url = _join_url(base_url, str(meta.get("embedding_path", "/embeddings")))
    headers = {"Content-Type": "application/json"}
    secret_ref = provider.get("api_key_secret_ref")
    if secret_ref:
        headers[str(meta.get("auth_header", "Authorization"))] = f"{str(meta.get('auth_scheme', 'Bearer')).strip()} {_resolve_secret(secret_ref)}".strip()

    timeout = float(_default_options(model_record).get("timeout_sec", 30))
    body = {"model": upstream_model_code, "input": texts}
    async with httpx.AsyncClient(timeout=timeout) as client:
        response = await client.post(request_url, headers=headers, json=body)
        response.raise_for_status()
        payload = response.json()

    vectors = _extract_vectors(payload)
    for vector in vectors:
        if len(vector) != expected_dimension:
            raise ModelConfigError(f"Embedding dimension mismatch: expected {expected_dimension}, got {len(vector)}")
    return vectors


def _default_options(model_record: Dict[str, Any]) -> Dict[str, Any]:
    options = model_record.get("default_options")
    return options if isinstance(options, dict) else {}


def _extract_vectors(payload: Dict[str, Any]) -> List[List[float]]:
    data = payload.get("data")
    if not isinstance(data, list):
        raise ModelConfigError("Embedding response missing data list")
    vectors: List[List[float]] = []
    for item in data:
        if not isinstance(item, dict) or not isinstance(item.get("embedding"), list):
            raise ModelConfigError("Embedding response item missing embedding")
        vectors.append([float(value) for value in item["embedding"]])
    return vectors
```

- [ ] **Step 4: Add chunking tests and implementation**

Create `python-ai/tests/test_core/test_chunking.py`:

```python
from src.core.chunking import build_chunks, normalize_search_terms


def test_build_chunks_splits_text_and_keeps_metadata():
    chunks = build_chunks("第一段介绍保修政策。\n\n第二段介绍退换货流程。", title="产品手册", max_chars=12)

    assert chunks[0]["chunk_index"] == 0
    assert chunks[0]["title"] == "产品手册"
    assert "保修" in chunks[0]["content"]
    assert chunks[0]["search_terms"]


def test_normalize_search_terms_uses_ngram_for_chinese():
    terms = normalize_search_terms("保修政策", ngram_min=2, ngram_max=3)

    assert "保修" in terms
    assert "政策" in terms
    assert "保修政" in terms
```

Create `python-ai/src/core/chunking.py`:

```python
from __future__ import annotations

import re
from hashlib import sha256
from typing import Any, Dict, List

_PUNCTUATION_RE = re.compile(r"[\s,，。！？!?；;：:、()\[\]{}<>《》\"'`~@#$%^&*_+=|\\/.-]+")
_CJK_RE = re.compile(r"[\u4e00-\u9fff]")


def build_chunks(text: str, title: str = "", max_chars: int = 800, overlap_chars: int = 120) -> List[Dict[str, Any]]:
    normalized = normalize_text(text)
    if not normalized:
        return []

    chunks: List[Dict[str, Any]] = []
    start = 0
    chunk_index = 0
    step = max(1, max_chars - max(0, overlap_chars))
    while start < len(normalized):
        end = min(len(normalized), start + max_chars)
        content = normalized[start:end].strip()
        if content:
            search_terms = normalize_search_terms(f"{title} {content}", 2, 4)
            chunks.append(
                {
                    "chunk_id": "",
                    "chunk_index": chunk_index,
                    "title": title,
                    "content": content,
                    "search_text": normalize_for_search(f"{title} {content}"),
                    "keywords": extract_keywords(f"{title} {content}"),
                    "search_terms": search_terms,
                    "content_hash": sha256(content.encode("utf-8")).hexdigest(),
                }
            )
            chunk_index += 1
        start += step
    return chunks


def normalize_text(text: str) -> str:
    lines = [line.strip() for line in (text or "").replace("\r\n", "\n").replace("\r", "\n").split("\n")]
    return "\n".join(line for line in lines if line)


def normalize_for_search(text: str) -> str:
    return _PUNCTUATION_RE.sub(" ", (text or "").lower()).strip()


def normalize_search_terms(text: str, ngram_min: int = 2, ngram_max: int = 4) -> List[str]:
    normalized = normalize_for_search(text)
    terms: set[str] = {token for token in normalized.split(" ") if token}
    cjk_text = "".join(char for char in normalized if _CJK_RE.match(char))
    for size in range(max(1, ngram_min), max(ngram_min, ngram_max) + 1):
        for index in range(0, max(0, len(cjk_text) - size + 1)):
            terms.add(cjk_text[index:index + size])
    return sorted(terms)


def extract_keywords(text: str, limit: int = 20) -> List[str]:
    terms = normalize_search_terms(text, 2, 4)
    scored = sorted(terms, key=lambda value: (-len(value), value))
    return scored[:limit]
```

- [ ] **Step 5: Upgrade settings**

Modify `python-ai/src/core/settings.py`:

```python
from pydantic_settings import BaseSettings, SettingsConfigDict


class RuntimeSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="ROBOT_", extra="ignore")

    redis_url: str = "redis://localhost:6379/0"
    redis_enabled: bool = True

    vector_dsn: str = "postgresql://robot:robot@localhost:5432/robot_vector"
    vector_enabled: bool = True
    vector_table: str = "knowledge_chunks"
    vector_dimension: int = 1024

    knowledge_embedding_default_model_code: str = "embedding-bge-m3"
    knowledge_retrieval_vector_weight: float = 0.7
    knowledge_retrieval_keyword_weight: float = 0.3
    knowledge_retrieval_metadata_boost: float = 0.05
    knowledge_retrieval_vector_top_k: int = 20
    knowledge_retrieval_keyword_top_k: int = 20
    knowledge_retrieval_query_ngram_min: int = 2
    knowledge_retrieval_query_ngram_max: int = 4


settings = RuntimeSettings()
```

- [ ] **Step 6: Upgrade pgvector table and hybrid search**

Modify `PgVectorKnowledgeStore.initialize()` so it creates:

```sql
CREATE TABLE IF NOT EXISTS knowledge_chunks (
    chunk_id TEXT PRIMARY KEY,
    kb_code TEXT NOT NULL,
    doc_id TEXT NOT NULL,
    index_version INT NOT NULL,
    chunk_index INT NOT NULL,
    title TEXT,
    content TEXT NOT NULL,
    search_text TEXT,
    keywords TEXT[],
    search_terms TEXT[],
    content_hash TEXT,
    embedding VECTOR(1024) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
)
```

Add indexes for `(kb_code, status, index_version)`, `(doc_id, index_version)`, GIN `metadata`, GIN `keywords`, GIN `search_terms`, and ivfflat vector cosine ops.

Add this method to `PgVectorKnowledgeStore`:

```python
def upsert_chunks(self, chunks: List[Dict[str, Any]]) -> int:
    if not chunks:
        return 0
    with self._connect() as connection:
        with connection.cursor() as cursor:
            for chunk in chunks:
                cursor.execute(
                    f"""
                    INSERT INTO {self._table_name} (
                        chunk_id, kb_code, doc_id, index_version, chunk_index, title,
                        content, search_text, keywords, search_terms, content_hash,
                        embedding, metadata, status, updated_at
                    )
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, 'ACTIVE', CURRENT_TIMESTAMP)
                    ON CONFLICT (chunk_id) DO UPDATE SET
                        title = EXCLUDED.title,
                        content = EXCLUDED.content,
                        search_text = EXCLUDED.search_text,
                        keywords = EXCLUDED.keywords,
                        search_terms = EXCLUDED.search_terms,
                        content_hash = EXCLUDED.content_hash,
                        embedding = EXCLUDED.embedding,
                        metadata = EXCLUDED.metadata,
                        status = 'ACTIVE',
                        updated_at = CURRENT_TIMESTAMP
                    """,
                    (
                        chunk["chunk_id"],
                        chunk["kb_code"],
                        chunk["doc_id"],
                        int(chunk["index_version"]),
                        int(chunk["chunk_index"]),
                        chunk.get("title"),
                        chunk["content"],
                        chunk.get("search_text"),
                        chunk.get("keywords", []),
                        chunk.get("search_terms", []),
                        chunk.get("content_hash"),
                        chunk["embedding"],
                        chunk.get("metadata", {}),
                    ),
                )
        connection.commit()
    return len(chunks)
```

Add this method to support multiple bound spaces:

```python
def search_many(
    self,
    kb_codes: List[str],
    query: str,
    retrieval_mode: str = "hybrid",
    top_k: int = 5,
    score_threshold: float = 0.0,
    embedding: List[float] | None = None,
) -> List[Dict[str, Any]]:
    if not kb_codes:
        return []
    vector_rows: List[Dict[str, Any]] = []
    keyword_rows: List[Dict[str, Any]] = []
    query_terms = normalize_search_terms(query, settings.knowledge_retrieval_query_ngram_min, settings.knowledge_retrieval_query_ngram_max)

    with self._connect() as connection:
        with connection.cursor() as cursor:
            if retrieval_mode in {"vector", "hybrid"}:
                query_embedding = embedding or self._embed_text(query)
                cursor.execute(
                    f"""
                    SELECT chunk_id, doc_id, kb_code, title, content, metadata,
                           CAST(1 - (embedding <=> %s) AS DOUBLE PRECISION) AS vector_score
                    FROM {self._table_name}
                    WHERE kb_code = ANY(%s) AND status = 'ACTIVE'
                    ORDER BY embedding <=> %s
                    LIMIT %s
                    """,
                    (query_embedding, kb_codes, query_embedding, settings.knowledge_retrieval_vector_top_k),
                )
                vector_rows = [_row_to_hit(row, "vector_score") for row in cursor.fetchall()]

            if retrieval_mode in {"keyword", "hybrid"}:
                cursor.execute(
                    f"""
                    SELECT chunk_id, doc_id, kb_code, title, content, metadata,
                           (
                             CASE WHEN search_terms && %s THEN 0.5 ELSE 0 END +
                             CASE WHEN keywords && %s THEN 0.3 ELSE 0 END +
                             CASE WHEN search_text ILIKE %s THEN 0.2 ELSE 0 END
                           ) AS keyword_score
                    FROM {self._table_name}
                    WHERE kb_code = ANY(%s)
                      AND status = 'ACTIVE'
                      AND (search_terms && %s OR keywords && %s OR search_text ILIKE %s)
                    ORDER BY keyword_score DESC, updated_at DESC
                    LIMIT %s
                    """,
                    (query_terms, query_terms, f"%{query}%", kb_codes, query_terms, query_terms, f"%{query}%", settings.knowledge_retrieval_keyword_top_k),
                )
                keyword_rows = [_row_to_hit(row, "keyword_score") for row in cursor.fetchall()]

    merged: Dict[str, Dict[str, Any]] = {}
    for item in vector_rows:
        merged[item["chunk_id"]] = {**item, "vector_score": item.get("vector_score", 0.0), "keyword_score": 0.0}
    for item in keyword_rows:
        current = merged.setdefault(item["chunk_id"], {**item, "vector_score": 0.0, "keyword_score": 0.0})
        current["keyword_score"] = item.get("keyword_score", 0.0)

    for item in merged.values():
        item["score"] = (
            float(item.get("vector_score", 0.0)) * settings.knowledge_retrieval_vector_weight
            + float(item.get("keyword_score", 0.0)) * settings.knowledge_retrieval_keyword_weight
            + settings.knowledge_retrieval_metadata_boost
        )
    return sorted(
        [item for item in merged.values() if float(item["score"]) >= score_threshold],
        key=lambda value: float(value["score"]),
        reverse=True,
    )[:top_k]
```

Add helper:

```python
def _row_to_hit(row: Any, score_key: str) -> Dict[str, Any]:
    return {
        "chunk_id": row[0],
        "doc_id": row[1],
        "kb_code": row[2],
        "title": row[3],
        "content": row[4],
        "metadata": row[5],
        score_key: float(row[6] or 0.0),
    }
```

- [ ] **Step 7: Run Python tests and commit**

Run:

```powershell
cd python-ai
pytest tests/test_core/test_embedding_runtime.py tests/test_core/test_chunking.py tests/test_nodes/test_knowledge.py -q
```

Expected: PASS.

Commit:

```powershell
git add python-ai/requirements.txt python-ai/src/core/settings.py python-ai/src/core/embedding_runtime.py python-ai/src/core/chunking.py python-ai/src/core/knowledge_store.py python-ai/tests/test_core/test_embedding_runtime.py python-ai/tests/test_core/test_chunking.py python-ai/tests/test_nodes/test_knowledge.py
git commit -m "feat: add embedding runtime and hybrid knowledge store"
```

## Task 4: Knowledge Ingestion Tasks and Python Processing API

**Files:**
- Create: `java-backend/src/main/java/robot/agent/model/KnowledgeTask.java`
- Create: `java-backend/src/main/java/robot/agent/model/KnowledgeTaskStatus.java`
- Create: `java-backend/src/main/java/robot/agent/model/KnowledgeTaskStage.java`
- Create: `java-backend/src/main/java/robot/agent/repository/KnowledgeTaskRepository.java`
- Create: `java-backend/src/main/java/robot/agent/dto/response/KnowledgeTaskResponse.java`
- Create: `java-backend/src/main/java/robot/agent/service/knowledge/PythonKnowledgeClient.java`
- Modify: `java-backend/src/main/java/robot/agent/service/KnowledgeService.java`
- Modify: `java-backend/src/main/java/robot/agent/controller/KnowledgeController.java`
- Create: `python-ai/src/core/document_processing.py`
- Create: `python-ai/src/core/knowledge_ingestion.py`
- Modify: `python-ai/src/api/models.py`
- Modify: `python-ai/src/api/main.py`
- Test: `python-ai/tests/test_core/test_knowledge_ingestion.py`
- Test: `java-backend/src/test/java/robot/agent/service/KnowledgeTaskServiceTest.java`

- [ ] **Step 1: Add Java task enums and entity**

Create `KnowledgeTaskStatus`:

```java
package robot.agent.model;

public enum KnowledgeTaskStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED
}
```

Create `KnowledgeTaskStage`:

```java
package robot.agent.model;

public enum KnowledgeTaskStage {
    RAW_SAVED,
    TEXT_EXTRACTED,
    CLEANED,
    CHUNKED,
    EMBEDDED,
    INDEXED
}
```

Create `java-backend/src/main/java/robot/agent/model/KnowledgeTask.java`:

```java
package robot.agent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "knowledge_task")
public class KnowledgeTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", length = 64, nullable = false, unique = true)
    private String taskId;

    @Column(name = "doc_id", length = 64, nullable = false)
    private String docId;

    @Column(name = "kb_code", length = 64, nullable = false)
    private String kbCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", length = 32, nullable = false)
    private KnowledgeTaskStage stage = KnowledgeTaskStage.RAW_SAVED;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private KnowledgeTaskStatus status = KnowledgeTaskStatus.QUEUED;

    @Column(name = "progress")
    private Integer progress = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "retry_count")
    private Integer retryCount = 0;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getDocId() { return docId; }
    public void setDocId(String docId) { this.docId = docId; }
    public String getKbCode() { return kbCode; }
    public void setKbCode(String kbCode) { this.kbCode = kbCode; }
    public KnowledgeTaskStage getStage() { return stage; }
    public void setStage(KnowledgeTaskStage stage) { this.stage = stage; }
    public KnowledgeTaskStatus getStatus() { return status; }
    public void setStatus(KnowledgeTaskStatus status) { this.status = status; }
    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
```

- [ ] **Step 2: Add task APIs**

Add controller endpoints:

```java
@GetMapping("/tasks/{taskId}")
public ResponseEntity<KnowledgeTaskResponse> getKnowledgeTask(@PathVariable String taskId) {
    return ResponseEntity.ok(knowledgeService.getKnowledgeTask(taskId));
}

@GetMapping("/documents/{docId}/tasks")
public ResponseEntity<List<KnowledgeTaskResponse>> getDocumentTasks(@PathVariable String docId) {
    return ResponseEntity.ok(knowledgeService.getDocumentTasks(docId));
}

@PostMapping("/tasks/{taskId}/retry")
public ResponseEntity<KnowledgeTaskResponse> retryKnowledgeTask(
        @RequestHeader(value = "X-User-Id", required = false) String userId,
        @PathVariable String taskId
) {
    return ResponseEntity.ok(knowledgeService.retryKnowledgeTask(userId, taskId));
}
```

- [ ] **Step 3: Add Python ingestion models**

Add to `python-ai/src/api/models.py`:

```python
class KnowledgeIngestRequest(BaseModel):
    task_id: str
    doc_id: str
    kb_code: str
    index_version: int = 1
    title: str = ""
    source_type: str
    filename: str = ""
    raw_content: Optional[str] = None
    raw_object_url: Optional[str] = None
    legacy_doc_text: Optional[str] = None
    embedding_model_code: str = "embedding-bge-m3"
    provider_configs: list[Dict[str, Any]] = Field(default_factory=list)
    model_records: list[Dict[str, Any]] = Field(default_factory=list)


class KnowledgeIngestResponse(BaseModel):
    task_id: str
    doc_id: str
    kb_code: str
    status: str
    chunk_count: int = 0
    generated_title: str = ""
    generated_summary: str = ""
    generated_keywords: list[str] = Field(default_factory=list)
    error_message: Optional[str] = None
```

- [ ] **Step 4: Implement Python document processing**

Create `document_processing.py`:

```python
from __future__ import annotations

from io import BytesIO
from pathlib import Path

from docx import Document
from pypdf import PdfReader


def extract_text(filename: str, content: bytes | None, raw_text: str | None = None, legacy_doc_text: str | None = None) -> str:
    suffix = Path(filename or "").suffix.lower()
    if raw_text:
        return raw_text
    if suffix == ".doc":
        return legacy_doc_text or ""
    if content is None:
        return ""
    if suffix in {".txt", ".md"}:
        return content.decode("utf-8", errors="replace")
    if suffix == ".pdf":
        reader = PdfReader(BytesIO(content))
        return "\n".join(page.extract_text() or "" for page in reader.pages)
    if suffix == ".docx":
        document = Document(BytesIO(content))
        return "\n".join(paragraph.text for paragraph in document.paragraphs)
    raise ValueError(f"Unsupported knowledge file type: {suffix}")
```

- [ ] **Step 5: Implement ingestion service and API**

Create `python-ai/src/core/knowledge_ingestion.py`:

```python
from __future__ import annotations

from typing import Any, Dict, List

import httpx

from src.api.models import KnowledgeIngestRequest, KnowledgeIngestResponse
from src.core.chunking import build_chunks
from src.core.document_processing import extract_text
from src.core.embedding_runtime import embed_texts_with_model
from src.core.knowledge_store import get_knowledge_store
from src.core.settings import settings


async def ingest_knowledge_document(
    request: KnowledgeIngestRequest,
    provider_configs: Dict[str, Dict[str, Any]],
    model_records: Dict[str, Dict[str, Any]],
) -> KnowledgeIngestResponse:
    try:
        content_bytes = await _download_bytes(request.raw_object_url) if request.raw_object_url else None
        text = extract_text(
            filename=request.filename,
            content=content_bytes,
            raw_text=request.raw_content,
            legacy_doc_text=request.legacy_doc_text,
        )
        chunks = build_chunks(text, title=request.title or request.filename)
        if not chunks:
            return KnowledgeIngestResponse(
                task_id=request.task_id,
                doc_id=request.doc_id,
                kb_code=request.kb_code,
                status="FAILED",
                error_message="No extractable text found",
            )

        embeddings: List[List[float]] = []
        for start in range(0, len(chunks), settings.knowledge_embedding_batch_size):
            batch = chunks[start:start + settings.knowledge_embedding_batch_size]
            embeddings.extend(
                await embed_texts_with_model(
                    texts=[chunk["content"] for chunk in batch],
                    model_code=request.embedding_model_code,
                    provider_configs=provider_configs,
                    model_records=model_records,
                    expected_dimension=settings.vector_dimension,
                )
            )

        rows = []
        for chunk, embedding in zip(chunks, embeddings):
            chunk_id = f"{request.doc_id}_{request.index_version}_{chunk['chunk_index']}"
            rows.append(
                {
                    **chunk,
                    "chunk_id": chunk_id,
                    "kb_code": request.kb_code,
                    "doc_id": request.doc_id,
                    "index_version": request.index_version,
                    "embedding": embedding,
                    "metadata": {
                        "filename": request.filename,
                        "task_id": request.task_id,
                        "source_type": request.source_type,
                    },
                }
            )

        get_knowledge_store().upsert_chunks(rows)
        keywords = sorted({keyword for row in rows for keyword in row.get("keywords", [])})[:20]
        return KnowledgeIngestResponse(
            task_id=request.task_id,
            doc_id=request.doc_id,
            kb_code=request.kb_code,
            status="SUCCEEDED",
            chunk_count=len(rows),
            generated_title=request.title or request.filename,
            generated_summary=rows[0]["content"][:200],
            generated_keywords=keywords,
        )
    except Exception as exc:
        return KnowledgeIngestResponse(
            task_id=request.task_id,
            doc_id=request.doc_id,
            kb_code=request.kb_code,
            status="FAILED",
            error_message=str(exc),
        )


async def _download_bytes(url: str) -> bytes:
    async with httpx.AsyncClient(timeout=30) as client:
        response = await client.get(url)
        response.raise_for_status()
        return response.content
```

Add route to `main.py`:

```python
@app.post("/api/knowledge/ingest", response_model=KnowledgeIngestResponse)
async def ingest_knowledge(request: KnowledgeIngestRequest):
    provider_configs = {str(item.get("provider_code")): item for item in request.provider_configs if item.get("provider_code")}
    model_records = {str(item.get("model_code")): item for item in request.model_records if item.get("model_code")}
    return await ingest_knowledge_document(request, provider_configs, model_records)
```

- [ ] **Step 6: Java orchestration**

Create `PythonKnowledgeClient.ingest(...)`:

```java
package robot.agent.service.knowledge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Service
public class PythonKnowledgeClient {
    private final WebClient webClient;

    public PythonKnowledgeClient(@Value("${python.base-url}") String pythonBaseUrl) {
        this.webClient = WebClient.builder().baseUrl(pythonBaseUrl).build();
    }

    public Map<String, Object> ingest(Map<String, Object> request) {
        return webClient.post()
                .uri("/api/knowledge/ingest")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .block();
    }

    public Map<String, Object> search(Map<String, Object> request) {
        return webClient.post()
                .uri("/api/knowledge/search")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .block();
    }
}
```

In `KnowledgeService` after creating a document:

- Create a `KnowledgeTask` with `QUEUED` and `RAW_SAVED`.
- For `.doc`, call `LegacyDocTextExtractor` and pass `legacy_doc_text`.
- Generate a MinIO presigned URL for file/long text objects.
- Build runtime provider/model bundle for the `knowledgeBase.embeddingModel` using `ModelConfigService`.
- Call Python ingestion asynchronously through a bounded executor or synchronously for the first vertical slice.
- On success, mark document `READY`, task `SUCCEEDED`, stage `INDEXED`, and set `chunkCount`, `generatedSummary`, `generatedKeywords`, `processedAt`.
- On failure, mark document `FAILED`, task `FAILED`, and save the error message.

- [ ] **Step 7: Run targeted tests and commit**

Run:

```powershell
cd python-ai
pytest tests/test_core/test_knowledge_ingestion.py -q
cd ..
mvn -pl java-backend -Dtest=KnowledgeTaskServiceTest test
```

Expected: PASS.

Commit:

```powershell
git add java-backend/src/main/java/robot/agent/model/KnowledgeTask.java java-backend/src/main/java/robot/agent/model/KnowledgeTaskStatus.java java-backend/src/main/java/robot/agent/model/KnowledgeTaskStage.java java-backend/src/main/java/robot/agent/repository/KnowledgeTaskRepository.java java-backend/src/main/java/robot/agent/dto/response/KnowledgeTaskResponse.java java-backend/src/main/java/robot/agent/service/knowledge/PythonKnowledgeClient.java java-backend/src/main/java/robot/agent/service/KnowledgeService.java java-backend/src/main/java/robot/agent/controller/KnowledgeController.java java-backend/src/test/java/robot/agent/service/KnowledgeTaskServiceTest.java python-ai/src/core/document_processing.py python-ai/src/core/knowledge_ingestion.py python-ai/src/api/models.py python-ai/src/api/main.py python-ai/tests/test_core/test_knowledge_ingestion.py
git commit -m "feat: process knowledge ingestion tasks"
```

## Task 5: Independent Knowledge Search and Citation Answering

**Files:**
- Create: `java-backend/src/main/java/robot/agent/dto/request/KnowledgeSearchRequest.java`
- Create: `java-backend/src/main/java/robot/agent/dto/response/KnowledgeSearchResponse.java`
- Modify: `java-backend/src/main/java/robot/agent/service/knowledge/PythonKnowledgeClient.java`
- Modify: `java-backend/src/main/java/robot/agent/service/KnowledgeService.java`
- Modify: `java-backend/src/main/java/robot/agent/controller/KnowledgeController.java`
- Modify: `python-ai/src/api/models.py`
- Modify: `python-ai/src/api/main.py`
- Test: `python-ai/tests/test_api/test_knowledge_search.py`
- Test: `java-backend/src/test/java/robot/agent/service/KnowledgeSearchServiceTest.java`

- [ ] **Step 1: Add search request/response models**

Java request:

```java
package robot.agent.dto.request;

import java.util.ArrayList;
import java.util.List;

public class KnowledgeSearchRequest {
    private String query;
    private List<String> kbCodes = new ArrayList<>();
    private String retrievalMode = "hybrid";
    private Integer topK = 5;
    private Boolean generateAnswer = true;

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public List<String> getKbCodes() { return kbCodes; }
    public void setKbCodes(List<String> kbCodes) { this.kbCodes = kbCodes == null ? new ArrayList<>() : kbCodes; }
    public String getRetrievalMode() { return retrievalMode; }
    public void setRetrievalMode(String retrievalMode) { this.retrievalMode = retrievalMode; }
    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }
    public Boolean getGenerateAnswer() { return generateAnswer; }
    public void setGenerateAnswer(Boolean generateAnswer) { this.generateAnswer = generateAnswer; }
}
```

Python request:

```python
class KnowledgeSearchRequest(BaseModel):
    query: str
    kb_codes: list[str] = Field(default_factory=list)
    retrieval_mode: str = "hybrid"
    top_k: int = 5
    score_threshold: float = 0.65
    embedding_model_code: str = "embedding-bge-m3"
    provider_configs: list[Dict[str, Any]] = Field(default_factory=list)
    model_records: list[Dict[str, Any]] = Field(default_factory=list)
    answer_model_code: Optional[str] = None
    generate_answer: bool = True
```

- [ ] **Step 2: Add Python search API**

Add `/api/knowledge/search` in `main.py`:

```python
@app.post("/api/knowledge/search")
async def search_knowledge(request: KnowledgeSearchRequest):
    provider_configs = {str(item.get("provider_code")): item for item in request.provider_configs if item.get("provider_code")}
    model_records = {str(item.get("model_code")): item for item in request.model_records if item.get("model_code")}
    documents = get_knowledge_store().search_many(
        kb_codes=request.kb_codes,
        query=request.query,
        retrieval_mode=request.retrieval_mode,
        top_k=request.top_k,
        score_threshold=request.score_threshold,
        embedding_model_code=request.embedding_model_code,
        provider_configs=provider_configs,
        model_records=model_records,
    )
    return {
        "query": request.query,
        "documents": documents,
        "answer": "",
        "citations": [{"chunkId": item.get("chunk_id"), "docId": item.get("doc_id"), "score": item.get("score")} for item in documents],
        "bestScore": max([float(item.get("score", 0.0)) for item in documents], default=0.0),
    }
```

- [ ] **Step 3: Add Java endpoint**

Add to `KnowledgeController`:

```java
@PostMapping("/search")
public ResponseEntity<KnowledgeSearchResponse> searchKnowledge(
        @RequestHeader(value = "X-User-Id", required = false) String userId,
        @RequestBody KnowledgeSearchRequest request
) {
    return ResponseEntity.ok(knowledgeService.searchKnowledge(userId, request));
}

@PostMapping("/{kbCode}/search")
public ResponseEntity<KnowledgeSearchResponse> searchSingleKnowledgeBase(
        @RequestHeader(value = "X-User-Id", required = false) String userId,
        @PathVariable String kbCode,
        @RequestBody KnowledgeSearchRequest request
) {
    request.setKbCodes(List.of(kbCode));
    return ResponseEntity.ok(knowledgeService.searchKnowledge(userId, request));
}
```

- [ ] **Step 4: Run tests and commit**

Run:

```powershell
cd python-ai
pytest tests/test_api/test_knowledge_search.py -q
cd ..
mvn -pl java-backend -Dtest=KnowledgeSearchServiceTest test
```

Expected: PASS.

Commit:

```powershell
git add java-backend/src/main/java/robot/agent/dto/request/KnowledgeSearchRequest.java java-backend/src/main/java/robot/agent/dto/response/KnowledgeSearchResponse.java java-backend/src/main/java/robot/agent/service/knowledge/PythonKnowledgeClient.java java-backend/src/main/java/robot/agent/service/KnowledgeService.java java-backend/src/main/java/robot/agent/controller/KnowledgeController.java java-backend/src/test/java/robot/agent/service/KnowledgeSearchServiceTest.java python-ai/src/api/models.py python-ai/src/api/main.py python-ai/tests/test_api/test_knowledge_search.py
git commit -m "feat: add independent knowledge search"
```

## Task 6: Explicit Binding and Parallel Route Aggregation

**Files:**
- Create: `java-backend/src/main/java/robot/agent/model/KnowledgeBindingScope.java`
- Create: `java-backend/src/main/java/robot/agent/model/KnowledgeBinding.java`
- Create: `java-backend/src/main/java/robot/agent/repository/KnowledgeBindingRepository.java`
- Create: `java-backend/src/main/java/robot/agent/dto/request/UpdateKnowledgeBindingsRequest.java`
- Create: `java-backend/src/main/java/robot/agent/dto/response/KnowledgeBindingResponse.java`
- Create: `java-backend/src/main/java/robot/agent/service/knowledge/KnowledgeRouteDecisionService.java`
- Modify: `java-backend/src/main/java/robot/agent/service/WorkflowService.java`
- Modify: `java-backend/src/main/java/robot/agent/controller/KnowledgeController.java`
- Test: `java-backend/src/test/java/robot/agent/service/knowledge/KnowledgeRouteDecisionServiceTest.java`

- [ ] **Step 1: Write route decision tests**

Create `KnowledgeRouteDecisionServiceTest.java`:

```java
package robot.agent.service.knowledge;

import org.junit.jupiter.api.Test;
import robot.agent.config.KnowledgeProperties;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeRouteDecisionServiceTest {

    private final KnowledgeRouteDecisionService service = new KnowledgeRouteDecisionService(new KnowledgeProperties());

    @Test
    void highIntentWins() {
        assertThat(service.decide(0.75, 0.99).finalRoute()).isEqualTo("INTENT");
    }

    @Test
    void strongKnowledgeWinsWhenIntentLow() {
        assertThat(service.decide(0.74, 0.65).finalRoute()).isEqualTo("KNOWLEDGE");
    }

    @Test
    void ambiguousBothSidesClarifies() {
        assertThat(service.decide(0.60, 0.60).finalRoute()).isEqualTo("CLARIFY");
    }

    @Test
    void bothLowFallsBack() {
        assertThat(service.decide(0.54, 0.54).finalRoute()).isEqualTo("FALLBACK");
    }
}
```

- [ ] **Step 2: Implement pure route decision service**

Create `KnowledgeRouteDecisionService.java`:

```java
package robot.agent.service.knowledge;

import org.springframework.stereotype.Service;
import robot.agent.config.KnowledgeProperties;

@Service
public class KnowledgeRouteDecisionService {
    private final KnowledgeProperties properties;

    public KnowledgeRouteDecisionService(KnowledgeProperties properties) {
        this.properties = properties;
    }

    public Decision decide(double intentConfidence, double knowledgeBestScore) {
        KnowledgeProperties.Route route = properties.getRoute();
        if (intentConfidence >= route.getIntentPrimaryThreshold()) {
            return new Decision("INTENT", "intent_primary");
        }
        if (intentConfidence < route.getIntentPrimaryThreshold() && knowledgeBestScore >= route.getKnowledgePrimaryThreshold()) {
            return new Decision("KNOWLEDGE", "knowledge_primary");
        }
        if (intentConfidence >= route.getIntentClarifyThreshold()
                && intentConfidence < route.getIntentPrimaryThreshold()
                && knowledgeBestScore >= route.getKnowledgeClarifyThreshold()
                && knowledgeBestScore < route.getKnowledgePrimaryThreshold()) {
            return new Decision("CLARIFY", "both_ambiguous");
        }
        if (intentConfidence < route.getIntentClarifyThreshold() && knowledgeBestScore < route.getKnowledgeClarifyThreshold()) {
            return new Decision("FALLBACK", "both_low");
        }
        if (intentConfidence >= route.getIntentClarifyThreshold()) {
            return new Decision("CLARIFY", "intent_ambiguous");
        }
        if (knowledgeBestScore >= route.getKnowledgeClarifyThreshold()) {
            return new Decision("CLARIFY", "knowledge_ambiguous");
        }
        return new Decision("FALLBACK", "boundary_fallback");
    }

    public record Decision(String finalRoute, String routeReason) {
    }
}
```

- [ ] **Step 3: Add binding model and APIs**

Create `KnowledgeBinding` with `scope`, `targetId`, `workspaceId`, `kbCode`, `enabled`, `bindingVersion`, `createdAt`, `updatedAt`.

Add APIs:

```http
GET /api/knowledge-bindings?scope=SESSION&targetId={sessionId}
PUT /api/knowledge-bindings
```

`PUT` replaces enabled binding rows for the scope/target pair and increments `bindingVersion`.

- [ ] **Step 4: Integrate WorkflowService**

In `WorkflowService`, load binding context once when creating a route/execute decision:

- Resolve `boundKnowledgeSpaceIds`.
- If empty, set knowledge result to `{searched:false,bestScore:0}` and skip Python knowledge search.
- If non-empty, run current intent logic and knowledge search in separate `CompletableFuture`s.
- Apply `KnowledgeRouteDecisionService`.
- Preserve existing workflow path for `INTENT`.
- For `KNOWLEDGE`, return a knowledge answer response without starting a workflow execution.
- For `CLARIFY`, use clarification question from intent or knowledge ambiguity.
- For `FALLBACK`, use existing fallback wording.

- [ ] **Step 5: Run tests and commit**

Run:

```powershell
mvn -pl java-backend -Dtest=KnowledgeRouteDecisionServiceTest,WorkflowServiceTest test
```

Expected: PASS.

Commit:

```powershell
git add java-backend/src/main/java/robot/agent/model/KnowledgeBindingScope.java java-backend/src/main/java/robot/agent/model/KnowledgeBinding.java java-backend/src/main/java/robot/agent/repository/KnowledgeBindingRepository.java java-backend/src/main/java/robot/agent/dto/request/UpdateKnowledgeBindingsRequest.java java-backend/src/main/java/robot/agent/dto/response/KnowledgeBindingResponse.java java-backend/src/main/java/robot/agent/service/knowledge/KnowledgeRouteDecisionService.java java-backend/src/main/java/robot/agent/service/WorkflowService.java java-backend/src/main/java/robot/agent/controller/KnowledgeController.java java-backend/src/test/java/robot/agent/service/knowledge/KnowledgeRouteDecisionServiceTest.java
git commit -m "feat: route between intent and bound knowledge"
```

## Task 7: Full-Screen Knowledge Center Frontend

**Files:**
- Modify: `frontend/src/types/index.ts`
- Modify: `frontend/src/services/api.ts`
- Create: `frontend/src/components/KnowledgeCenterPanel.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/index.css`
- Create: `frontend/tests/e2e/knowledge-center.spec.ts`

- [ ] **Step 1: Write Playwright layout test**

Create `frontend/tests/e2e/knowledge-center.spec.ts`:

```ts
import { expect, test } from '@playwright/test'

test.beforeEach(async ({ page }) => {
  await page.route('**/api/workflows/published', async (route) => route.fulfill({ json: [] }))
  await page.route('**/api/sessions**', async (route) => route.fulfill({ json: [] }))
  await page.route('**/api/knowledge-bases', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        json: [
          {
            id: 1,
            workspaceId: 1,
            kbCode: 'kb_product',
            name: '产品知识',
            description: '产品说明与售后政策',
            embeddingModel: 'embedding-bge-m3',
            status: 'ACTIVE',
            createdAt: '2026-06-14T00:00:00',
          },
        ],
      })
      return
    }
    await route.fulfill({ json: { id: 2, workspaceId: 1, kbCode: 'kb_new', name: '新知识空间', status: 'ACTIVE' } })
  })
  await page.route('**/api/knowledge-bases/kb_product/documents', async (route) => route.fulfill({ json: [] }))
  await page.route('**/api/knowledge/search', async (route) => route.fulfill({
    json: {
      query: '保修期',
      documents: [{ chunkId: 'chunk_1', docId: 'doc_1', kbCode: 'kb_product', title: '产品手册', content: '保修期为一年', score: 0.92 }],
      answer: '根据产品手册，保修期为一年。',
      citations: [{ chunkId: 'chunk_1', docId: 'doc_1', score: 0.92 }],
      bestScore: 0.92,
    },
  }))
})

test('knowledge center uses full screen layout and required entries', async ({ page }) => {
  await page.goto('/#knowledge')
  const panel = page.getByTestId('knowledge-center-panel')
  await expect(panel).toBeVisible()
  const box = await panel.boundingBox()
  const viewport = page.viewportSize()
  expect(box?.width).toBeGreaterThan((viewport?.width ?? 0) * 0.9)
  expect(box?.height).toBeGreaterThan((viewport?.height ?? 0) * 0.75)
  await expect(page.getByTestId('knowledge-space-create')).toBeVisible()
  await expect(page.getByTestId('knowledge-space-list')).toContainText('产品知识')
  await expect(page.getByTestId('knowledge-subnav-spaces')).toHaveText('知识空间')
  await expect(page.getByTestId('knowledge-subnav-tasks')).toHaveText('采集任务')
  await expect(page.getByTestId('knowledge-subnav-search')).toHaveText('知识检索')
})
```

- [ ] **Step 2: Run E2E test to verify it fails**

Run:

```powershell
cd frontend
npm run test:e2e -- knowledge-center.spec.ts
```

Expected: FAIL because `#knowledge` page and test ids do not exist.

- [ ] **Step 3: Add frontend types and API functions**

Add `KnowledgeSpace`, `KnowledgeDocument`, `KnowledgeTask`, `KnowledgeSearchResult` interfaces to `frontend/src/types/index.ts`.

Add API functions to `frontend/src/services/api.ts`:

```ts
export async function getKnowledgeSpaces(): Promise<KnowledgeSpace[]> {
  const response = await apiFetch(`${API_BASE_URL}/knowledge-bases`)
  if (!response.ok) await parseApiError(response)
  return await response.json()
}

export async function createKnowledgeSpace(payload: { kbCode: string; name: string; description?: string; embeddingModel?: string }, currentUserId: string): Promise<KnowledgeSpace> {
  const response = await apiFetch(`${API_BASE_URL}/knowledge-bases`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-User-Id': currentUserId || ADMIN_USER_ID },
    body: JSON.stringify(payload),
  })
  if (!response.ok) await parseApiError(response)
  return await response.json()
}

export async function getKnowledgeDocuments(kbCode: string): Promise<KnowledgeDocument[]> {
  const response = await apiFetch(`${API_BASE_URL}/knowledge-bases/${encodeURIComponent(kbCode)}/documents`)
  if (!response.ok) await parseApiError(response)
  return await response.json()
}

export async function searchKnowledge(payload: { query: string; kbCodes: string[]; topK?: number; generateAnswer?: boolean }): Promise<KnowledgeSearchResult> {
  const response = await apiFetch(`${API_BASE_URL}/knowledge/search`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  if (!response.ok) await parseApiError(response)
  return await response.json()
}
```

- [ ] **Step 4: Implement KnowledgeCenterPanel**

Create `KnowledgeCenterPanel.tsx` with:

- Root `data-testid="knowledge-center-panel"`.
- Plain text subnav buttons:
  - `data-testid="knowledge-subnav-spaces"` with text `知识空间`
  - `data-testid="knowledge-subnav-tasks"` with text `采集任务`
  - `data-testid="knowledge-subnav-search"` with text `知识检索`
- Spaces page:
  - `data-testid="knowledge-space-create"` button `+ 新增知识空间`
  - `data-testid="knowledge-space-list"`
  - rows with knowledge name, code, document count, embedding model, status.
- Detail page:
  - `data-testid="knowledge-document-create"` button `+ 新增知识`
  - document list.
- Tasks page:
  - filters and task list.
- Search page:
  - search input, knowledge-space selector, topK selector, result list, answer and citation panel.

Use existing style vocabulary: `.panel-card`, `text-slate-*`, white translucent panels, dark primary buttons, blue link text. Do not put the whole page in a narrow card.

- [ ] **Step 5: Wire App navigation**

Modify `PageKey`:

```ts
type PageKey = 'chat' | 'workflow' | 'execution' | 'models' | 'api-center' | 'knowledge'
```

Add hash sync:

```ts
if (value === 'workflow' || value === 'execution' || value === 'models' || value === 'chat' || value === 'api-center' || value === 'knowledge') {
  setActivePage(value)
  return
}
```

Add nav tab:

```tsx
<button className={`nav-tab ${activePage === 'knowledge' ? 'active' : ''}`} onClick={() => navigateToPage('knowledge')}>
  知识库
</button>
```

Add render branch:

```tsx
if (activePage === 'knowledge') {
  return <KnowledgeCenterPanel currentUserId={currentUserId} />
}
```

- [ ] **Step 6: Add CSS and run frontend checks**

Add `.knowledge-center-panel`, `.knowledge-center-layout`, `.knowledge-center-subnav`, `.knowledge-center-main`, `.knowledge-space-list`, `.knowledge-search-results` to `frontend/src/index.css`. Keep the page full height:

```css
.knowledge-center-panel {
  display: flex;
  min-height: calc(100vh - 112px);
  width: 100%;
}

.knowledge-center-layout {
  display: grid;
  grid-template-columns: 180px minmax(0, 1fr);
  gap: 18px;
  width: 100%;
}

.knowledge-center-subnav {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 0;
}

.knowledge-center-subnav button {
  border: 0;
  background: transparent;
  color: #475569;
  padding: 10px 12px;
  text-align: left;
  font-size: 14px;
  font-weight: 600;
}

.knowledge-center-subnav button.active {
  color: #0f172a;
}

.knowledge-center-main {
  min-width: 0;
  min-height: 0;
}
```

Run:

```powershell
cd frontend
npm run build
npm run test:e2e -- knowledge-center.spec.ts
```

Expected: PASS.

Commit:

```powershell
git add frontend/src/types/index.ts frontend/src/services/api.ts frontend/src/components/KnowledgeCenterPanel.tsx frontend/src/App.tsx frontend/src/index.css frontend/tests/e2e/knowledge-center.spec.ts
git commit -m "feat: add full screen knowledge center"
```

## Task 8: End-to-End Verification and Documentation Sync

**Files:**
- Modify: `docs/superpowers/specs/2026-06-14-knowledge-center-design.md` only if implementation reveals a concrete mismatch.
- Create: `docs/superpowers/verification/2026-06-14-knowledge-center-verification.md`

- [ ] **Step 1: Run backend verification**

Run:

```powershell
mvn -pl java-backend test
```

Expected: PASS.

- [ ] **Step 2: Run Python verification**

Run:

```powershell
cd python-ai
pytest -q
```

Expected: PASS.

- [ ] **Step 3: Run frontend verification**

Run:

```powershell
cd frontend
npm run build
npm run test:e2e -- knowledge-center.spec.ts
```

Expected: PASS.

- [ ] **Step 4: Run compose config check**

Run:

```powershell
docker compose config
```

Expected: command exits with code `0` and includes services `mysql`, `redis`, `pgvector`, and `minio`.

- [ ] **Step 5: Write verification note**

Create `docs/superpowers/verification/2026-06-14-knowledge-center-verification.md`:

```markdown
# Knowledge Center Verification

Date: 2026-06-14

## Commands

- `mvn -pl java-backend test`
- `cd python-ai && pytest -q`
- `cd frontend && npm run build`
- `cd frontend && npm run test:e2e -- knowledge-center.spec.ts`
- `docker compose config`

## Result

All commands completed successfully.

## Coverage

- MinIO storage configuration
- Knowledge document metadata and task lifecycle
- Python ingestion and hybrid retrieval
- Parallel route decision thresholds
- Full-screen knowledge center UI
```

- [ ] **Step 6: Commit verification**

Run:

```powershell
git add docs/superpowers/verification/2026-06-14-knowledge-center-verification.md docs/superpowers/specs/2026-06-14-knowledge-center-design.md
git commit -m "docs: verify knowledge center implementation"
```

## Plan Self-Review Checklist

- Spec coverage:
  - MinIO original storage: Task 1, Task 2.
  - Embedding model and configuration: Task 1, Task 3.
  - MySQL metadata and task state: Task 2, Task 4.
  - pgvector chunk/vector/keyword storage: Task 3.
  - Hybrid vector + keyword search: Task 3, Task 5.
  - Independent knowledge search page/API: Task 5, Task 7.
  - Explicit binding and no-binding short circuit: Task 6.
  - Parallel intent/knowledge route aggregation: Task 6.
  - Full-screen UI matching console style: Task 7.
  - No OCR: enforced by supported file handlers in Task 4.
- Type consistency:
  - Java uses camelCase DTO fields and existing response conventions.
  - Python API uses snake_case Pydantic fields.
  - Frontend maps backend camelCase responses.
- Verification:
  - Java unit tests, Python tests, frontend build/E2E, and Docker compose config are included.

## Execution Options

1. Subagent-Driven (recommended): dispatch a fresh subagent per task and review after each task.
2. Inline Execution: execute tasks in this session with `superpowers:executing-plans` checkpoints.
