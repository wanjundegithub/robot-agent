package robot.agent.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import robot.agent.model.LlmModelRecord;
import robot.agent.model.LlmProviderConfig;
import robot.agent.repository.LlmModelRecordRepository;
import robot.agent.repository.LlmProviderConfigRepository;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class GeneratedModelDataCleanup implements ApplicationRunner {

    private static final String GENERATED_PROVIDER_CODE = "openai-compatible-prod";
    private static final Map<String, String> GENERATED_MODEL_NAMES = Map.of(
            "intent-router-v1", "意图路由模型",
            "knowledge-query-rewrite-v1", "知识库改写模型",
            "knowledge-answer-v1", "知识库回答模型",
            "general-chat-v1", "通用对话模型",
            "structured-extraction-v1", "结构化抽取模型"
    );
    private static final String GENERATED_PROVIDER_NAME = "Default OpenAI Compatible Provider";
    private static final String GENERATED_PROVIDER_TYPE = "openai";
    private static final String GENERATED_PROVIDER_BASE_URL = "https://api1.oai1.online/v1";

    private final LlmProviderConfigRepository providerRepository;
    private final LlmModelRecordRepository modelRecordRepository;

    public GeneratedModelDataCleanup(
            LlmProviderConfigRepository providerRepository,
            LlmModelRecordRepository modelRecordRepository
    ) {
        this.providerRepository = providerRepository;
        this.modelRecordRepository = modelRecordRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        removeGeneratedModelRecords();
        removeGeneratedProviderIfUnused();
    }

    private void removeGeneratedModelRecords() {
        List<LlmModelRecord> generatedRecords = modelRecordRepository.findByModelCodeIn(GENERATED_MODEL_NAMES.keySet()).stream()
                .filter(this::shouldRemoveModelRecord)
                .toList();
        if (!generatedRecords.isEmpty()) {
            modelRecordRepository.deleteAll(generatedRecords);
        }
    }

    private boolean shouldRemoveModelRecord(LlmModelRecord modelRecord) {
        if (modelRecord == null || !GENERATED_MODEL_NAMES.containsKey(modelRecord.getModelCode())) {
            return false;
        }
        if (isDoubaoModel(modelRecord)) {
            return false;
        }
        String generatedName = GENERATED_MODEL_NAMES.get(modelRecord.getModelCode());
        return equalsIgnoreCase(modelRecord.getCreatedBy(), "system")
                || equalsText(modelRecord.getModelName(), generatedName)
                || equalsText(modelRecord.getProviderCode(), GENERATED_PROVIDER_CODE);
    }

    private boolean isDoubaoModel(LlmModelRecord modelRecord) {
        return equalsIgnoreCase(modelRecord.getProvider(), "doubao")
                || equalsIgnoreCase(modelRecord.getProviderType(), "doubao")
                || containsIgnoreCase(modelRecord.getModelName(), "豆包")
                || containsIgnoreCase(modelRecord.getUpstreamModelCode(), "doubao");
    }

    private void removeGeneratedProviderIfUnused() {
        providerRepository.findByProviderCode(GENERATED_PROVIDER_CODE)
                .filter(this::isGeneratedProvider)
                .filter(provider -> modelRecordRepository.countByProviderCode(GENERATED_PROVIDER_CODE) == 0L)
                .ifPresent(providerRepository::delete);
    }

    private boolean isGeneratedProvider(LlmProviderConfig provider) {
        if (provider == null || !equalsText(provider.getProviderCode(), GENERATED_PROVIDER_CODE)) {
            return false;
        }
        if (equalsIgnoreCase(provider.getProviderType(), "doubao")) {
            return false;
        }
        return equalsText(provider.getProviderName(), GENERATED_PROVIDER_NAME)
                || equalsIgnoreCase(provider.getProviderType(), GENERATED_PROVIDER_TYPE)
                || equalsText(provider.getBaseUrl(), GENERATED_PROVIDER_BASE_URL)
                || equalsIgnoreCase(provider.getCreatedBy(), "system");
    }

    private boolean equalsText(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.trim().equals(right.trim());
    }

    private boolean equalsIgnoreCase(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }

    private boolean containsIgnoreCase(String value, String fragment) {
        if (value == null || fragment == null) {
            return false;
        }
        return value.toLowerCase(Locale.ROOT).contains(fragment.toLowerCase(Locale.ROOT));
    }
}
