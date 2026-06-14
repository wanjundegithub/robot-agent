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

    public Storage getStorage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage == null ? new Storage() : storage;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public void setEmbedding(Embedding embedding) {
        this.embedding = embedding == null ? new Embedding() : embedding;
    }

    public Retrieval getRetrieval() {
        return retrieval;
    }

    public void setRetrieval(Retrieval retrieval) {
        this.retrieval = retrieval == null ? new Retrieval() : retrieval;
    }

    public Route getRoute() {
        return route;
    }

    public void setRoute(Route route) {
        this.route = route == null ? new Route() : route;
    }

    public static class Storage {
        private String type = "minio";
        private Minio minio = new Minio();
        private int presignedUrlTtlSeconds = 300;
        private int maxFileSizeMb = 100;
        private List<String> allowedTypes = new ArrayList<>(List.of("txt", "pdf", "doc", "docx", "md"));

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public Minio getMinio() {
            return minio;
        }

        public void setMinio(Minio minio) {
            this.minio = minio == null ? new Minio() : minio;
        }

        public int getPresignedUrlTtlSeconds() {
            return presignedUrlTtlSeconds;
        }

        public void setPresignedUrlTtlSeconds(int presignedUrlTtlSeconds) {
            this.presignedUrlTtlSeconds = presignedUrlTtlSeconds;
        }

        public int getMaxFileSizeMb() {
            return maxFileSizeMb;
        }

        public void setMaxFileSizeMb(int maxFileSizeMb) {
            this.maxFileSizeMb = maxFileSizeMb;
        }

        public List<String> getAllowedTypes() {
            return allowedTypes;
        }

        public void setAllowedTypes(List<String> allowedTypes) {
            this.allowedTypes = allowedTypes == null ? new ArrayList<>() : allowedTypes;
        }
    }

    public static class Minio {
        private String endpoint = "http://localhost:9000";
        private String bucket = "robot-knowledge";
        private String accessKey = "robot";
        private String secretKey = "robot-knowledge-secret";
        private String region = "us-east-1";
        private boolean secure = false;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public boolean isSecure() {
            return secure;
        }

        public void setSecure(boolean secure) {
            this.secure = secure;
        }
    }

    public static class Embedding {
        private String defaultModelCode = "embedding-qwen3-8b";
        private String defaultUpstreamModel = "Qwen/Qwen3-Embedding-8B";
        private String providerCode = "modelscope-embedding";
        private String providerName = "ModelScope Embedding";
        private String providerType = "openai_compatible";
        private String baseUrl = "https://api-inference.modelscope.cn/v1";
        private String apiKeySecretRef = "env:MODELSCOPE_API_KEY";
        private String embeddingPath = "/embeddings";
        private String encodingFormat = "float";
        private boolean includeMessages = true;
        private boolean singleInputAsString = true;
        private int dimension = 4096;
        private int batchSize = 32;
        private int timeoutMs = 30000;

        public String getDefaultModelCode() {
            return defaultModelCode;
        }

        public void setDefaultModelCode(String defaultModelCode) {
            this.defaultModelCode = defaultModelCode;
        }

        public String getDefaultUpstreamModel() {
            return defaultUpstreamModel;
        }

        public void setDefaultUpstreamModel(String defaultUpstreamModel) {
            this.defaultUpstreamModel = defaultUpstreamModel;
        }

        public String getProviderCode() {
            return providerCode;
        }

        public void setProviderCode(String providerCode) {
            this.providerCode = providerCode;
        }

        public String getProviderName() {
            return providerName;
        }

        public void setProviderName(String providerName) {
            this.providerName = providerName;
        }

        public String getProviderType() {
            return providerType;
        }

        public void setProviderType(String providerType) {
            this.providerType = providerType;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKeySecretRef() {
            return apiKeySecretRef;
        }

        public void setApiKeySecretRef(String apiKeySecretRef) {
            this.apiKeySecretRef = apiKeySecretRef;
        }

        public String getEmbeddingPath() {
            return embeddingPath;
        }

        public void setEmbeddingPath(String embeddingPath) {
            this.embeddingPath = embeddingPath;
        }

        public String getEncodingFormat() {
            return encodingFormat;
        }

        public void setEncodingFormat(String encodingFormat) {
            this.encodingFormat = encodingFormat;
        }

        public boolean isIncludeMessages() {
            return includeMessages;
        }

        public void setIncludeMessages(boolean includeMessages) {
            this.includeMessages = includeMessages;
        }

        public boolean isSingleInputAsString() {
            return singleInputAsString;
        }

        public void setSingleInputAsString(boolean singleInputAsString) {
            this.singleInputAsString = singleInputAsString;
        }

        public int getDimension() {
            return dimension;
        }

        public void setDimension(int dimension) {
            this.dimension = dimension;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
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

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public double getScoreThreshold() {
            return scoreThreshold;
        }

        public void setScoreThreshold(double scoreThreshold) {
            this.scoreThreshold = scoreThreshold;
        }

        public double getVectorWeight() {
            return vectorWeight;
        }

        public void setVectorWeight(double vectorWeight) {
            this.vectorWeight = vectorWeight;
        }

        public double getKeywordWeight() {
            return keywordWeight;
        }

        public void setKeywordWeight(double keywordWeight) {
            this.keywordWeight = keywordWeight;
        }

        public double getMetadataBoost() {
            return metadataBoost;
        }

        public void setMetadataBoost(double metadataBoost) {
            this.metadataBoost = metadataBoost;
        }

        public int getVectorTopK() {
            return vectorTopK;
        }

        public void setVectorTopK(int vectorTopK) {
            this.vectorTopK = vectorTopK;
        }

        public int getKeywordTopK() {
            return keywordTopK;
        }

        public void setKeywordTopK(int keywordTopK) {
            this.keywordTopK = keywordTopK;
        }

        public int getQueryNgramMin() {
            return queryNgramMin;
        }

        public void setQueryNgramMin(int queryNgramMin) {
            this.queryNgramMin = queryNgramMin;
        }

        public int getQueryNgramMax() {
            return queryNgramMax;
        }

        public void setQueryNgramMax(int queryNgramMax) {
            this.queryNgramMax = queryNgramMax;
        }
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

        public double getIntentPrimaryThreshold() {
            return intentPrimaryThreshold;
        }

        public void setIntentPrimaryThreshold(double intentPrimaryThreshold) {
            this.intentPrimaryThreshold = intentPrimaryThreshold;
        }

        public double getKnowledgePrimaryThreshold() {
            return knowledgePrimaryThreshold;
        }

        public void setKnowledgePrimaryThreshold(double knowledgePrimaryThreshold) {
            this.knowledgePrimaryThreshold = knowledgePrimaryThreshold;
        }

        public double getIntentClarifyThreshold() {
            return intentClarifyThreshold;
        }

        public void setIntentClarifyThreshold(double intentClarifyThreshold) {
            this.intentClarifyThreshold = intentClarifyThreshold;
        }

        public double getKnowledgeClarifyThreshold() {
            return knowledgeClarifyThreshold;
        }

        public void setKnowledgeClarifyThreshold(double knowledgeClarifyThreshold) {
            this.knowledgeClarifyThreshold = knowledgeClarifyThreshold;
        }

        public int getIntentTimeoutMs() {
            return intentTimeoutMs;
        }

        public void setIntentTimeoutMs(int intentTimeoutMs) {
            this.intentTimeoutMs = intentTimeoutMs;
        }

        public int getKnowledgeTimeoutMs() {
            return knowledgeTimeoutMs;
        }

        public void setKnowledgeTimeoutMs(int knowledgeTimeoutMs) {
            this.knowledgeTimeoutMs = knowledgeTimeoutMs;
        }

        public int getMaxWaitMs() {
            return maxWaitMs;
        }

        public void setMaxWaitMs(int maxWaitMs) {
            this.maxWaitMs = maxWaitMs;
        }

        public boolean isCancelLateBranch() {
            return cancelLateBranch;
        }

        public void setCancelLateBranch(boolean cancelLateBranch) {
            this.cancelLateBranch = cancelLateBranch;
        }
    }
}
