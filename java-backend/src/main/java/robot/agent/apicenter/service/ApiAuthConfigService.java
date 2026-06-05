package robot.agent.apicenter.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import robot.agent.apicenter.model.ApiAuthConfig;
import robot.agent.apicenter.model.ApiAuthMode;
import robot.agent.apicenter.model.ApiAuthScopeType;
import robot.agent.apicenter.model.ApiAuthType;
import robot.agent.apicenter.repository.ApiAuthConfigRepository;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
@Transactional
public class ApiAuthConfigService {

    private final ApiAuthConfigRepository repository;
    private final ApiAuthCryptoService cryptoService;
    private final ObjectMapper objectMapper;

    public ApiAuthConfigService(ApiAuthConfigRepository repository, ApiAuthCryptoService cryptoService, ObjectMapper objectMapper) {
        this.repository = repository;
        this.cryptoService = cryptoService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> saveAuthConfig(ApiAuthScopeType scopeType, Long scopeId, Map<String, Object> payload) {
        if (scopeId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "鉴权配置归属不能为空");
        }
        ApiAuthType authType = parseAuthType(payload == null ? null : payload.get("authType"));
        ApiAuthConfig config = repository.findByScopeTypeAndScopeId(scopeType, scopeId).orElseGet(ApiAuthConfig::new);
        Map<String, Object> existingConfig = config.getAuthType() == authType ? readConfig(config.getConfigCiphertext()) : Map.of();
        Map<String, Object> normalized = normalizeConfig(authType, payload, existingConfig);
        String preview = preview(authType, normalized);
        config.setScopeType(scopeType);
        config.setScopeId(scopeId);
        config.setAuthType(authType);
        config.setPreview(preview);
        config.setConfigCiphertext(authType == ApiAuthType.NO_AUTH ? null : cryptoService.encrypt(writeJson(normalized)));
        if (config.getCreatedAt() == null) {
            config.setCreatedAt(LocalDateTime.now());
        }
        config.setUpdatedAt(LocalDateTime.now());
        return toResponse(repository.save(config), false);
    }

    public void deleteAuthConfig(ApiAuthScopeType scopeType, Long scopeId) {
        if (scopeId != null) {
            repository.deleteByScopeTypeAndScopeId(scopeType, scopeId);
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getAuthConfig(ApiAuthScopeType scopeType, Long scopeId) {
        return repository.findByScopeTypeAndScopeId(scopeType, scopeId)
                .map(config -> toResponse(config, false))
                .orElseGet(this::defaultResponse);
    }

    @Transactional(readOnly = true)
    public EffectiveAuth resolveEffectiveAuth(Long groupId, Long apiId, String authModeValue) {
        ApiAuthMode authMode = parseAuthMode(authModeValue);
        if (authMode == ApiAuthMode.NONE) {
            return EffectiveAuth.noAuth();
        }
        if (authMode == ApiAuthMode.CUSTOM && apiId != null) {
            return repository.findByScopeTypeAndScopeId(ApiAuthScopeType.ITEM, apiId)
                    .map(this::toEffectiveAuth)
                    .orElse(EffectiveAuth.noAuth());
        }
        return repository.findByScopeTypeAndScopeId(ApiAuthScopeType.GROUP, groupId)
                .map(this::toEffectiveAuth)
                .orElse(EffectiveAuth.noAuth());
    }

    public EffectiveAuth resolveDraftEffectiveAuth(Long groupId, Long apiId, Map<String, Object> payload) {
        ApiAuthMode authMode = parseAuthMode(payload == null ? null : payload.get("authMode"));
        if (authMode == ApiAuthMode.NONE) {
            return EffectiveAuth.noAuth();
        }
        if (authMode == ApiAuthMode.CUSTOM) {
            Map<String, Object> authConfig = asMap(payload == null ? null : payload.get("authConfig"));
            ApiAuthConfig existing = apiId == null ? null : repository.findByScopeTypeAndScopeId(ApiAuthScopeType.ITEM, apiId).orElse(null);
            ApiAuthType existingAuthType = existing == null || existing.getAuthType() == null ? ApiAuthType.NO_AUTH : existing.getAuthType();
            ApiAuthType authType = authConfig.containsKey("authType") ? parseAuthType(authConfig.get("authType")) : existingAuthType;
            Map<String, Object> existingConfig = existing != null && existingAuthType == authType ? readConfig(existing.getConfigCiphertext()) : Map.of();
            if (authConfig.isEmpty() && existing != null) {
                return new EffectiveAuth(authType, existingConfig, firstNonBlank(existing.getPreview(), preview(authType, existingConfig)));
            }
            Map<String, Object> normalized = normalizeConfig(authType, authConfig, existingConfig);
            return new EffectiveAuth(authType, normalized, preview(authType, normalized));
        }
        return resolveEffectiveAuth(groupId, apiId, ApiAuthMode.INHERIT.name());
    }

    public Map<String, Object> responseForEffectiveAuth(EffectiveAuth auth) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authType", auth.authType().name());
        result.put("authPreview", auth.preview());
        result.put("configured", auth.authType() != ApiAuthType.NO_AUTH);
        return result;
    }

    public ApiAuthMode parseAuthMode(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return ApiAuthMode.INHERIT;
        }
        try {
            return ApiAuthMode.valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的鉴权策略: " + value);
        }
    }

    private EffectiveAuth toEffectiveAuth(ApiAuthConfig config) {
        ApiAuthType authType = config.getAuthType() == null ? ApiAuthType.NO_AUTH : config.getAuthType();
        if (authType == ApiAuthType.NO_AUTH) {
            return EffectiveAuth.noAuth();
        }
        return new EffectiveAuth(authType, readConfig(config.getConfigCiphertext()), firstNonBlank(config.getPreview(), preview(authType, readConfig(config.getConfigCiphertext()))));
    }

    private Map<String, Object> toResponse(ApiAuthConfig config, boolean includeConfig) {
        Map<String, Object> result = new LinkedHashMap<>();
        ApiAuthType authType = config.getAuthType() == null ? ApiAuthType.NO_AUTH : config.getAuthType();
        Map<String, Object> configMap = readConfig(config.getConfigCiphertext());
        result.put("authType", authType.name());
        result.put("preview", firstNonBlank(config.getPreview(), preview(authType, configMap)));
        result.put("authPreview", result.get("preview"));
        result.put("configured", authType != ApiAuthType.NO_AUTH);
        if (authType == ApiAuthType.API_KEY) {
            result.put("key", configMap.get("key"));
            result.put("addTo", configMap.getOrDefault("addTo", "HEADER"));
        }
        if (authType == ApiAuthType.DIGEST) {
            result.put("realm", configMap.get("realm"));
            result.put("algorithm", configMap.getOrDefault("algorithm", "MD5"));
            result.put("qop", configMap.getOrDefault("qop", "auth"));
        }
        if (includeConfig) {
            result.put("config", configMap);
        }
        return result;
    }

    private Map<String, Object> defaultResponse() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authType", ApiAuthType.NO_AUTH.name());
        result.put("preview", "No Auth");
        result.put("authPreview", "No Auth");
        result.put("configured", false);
        return result;
    }

    private ApiAuthType parseAuthType(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return ApiAuthType.NO_AUTH;
        }
        try {
            return ApiAuthType.valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的鉴权类型: " + value);
        }
    }

    private Map<String, Object> normalizeConfig(ApiAuthType authType, Map<String, Object> payload, Map<String, Object> existingConfig) {
        Map<String, Object> config = new LinkedHashMap<>();
        switch (authType) {
            case NO_AUTH -> {
                return config;
            }
            case API_KEY -> {
                config.put("key", required(payload, existingConfig, "key"));
                config.put("value", required(payload, existingConfig, "value"));
                String addTo = firstNonBlank(stringValue(payload.get("addTo")), firstNonBlank(stringValue(existingConfig.get("addTo")), "HEADER")).toUpperCase(Locale.ROOT);
                if (!"HEADER".equals(addTo) && !"QUERY".equals(addTo)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "API Key 添加位置必须是 HEADER 或 QUERY");
                }
                config.put("addTo", addTo);
            }
            case BEARER -> config.put("token", required(payload, existingConfig, "token"));
            case BASIC -> {
                config.put("username", required(payload, existingConfig, "username"));
                config.put("password", required(payload, existingConfig, "password"));
            }
            case DIGEST -> {
                config.put("username", required(payload, existingConfig, "username"));
                config.put("password", required(payload, existingConfig, "password"));
                putOptional(config, payload, existingConfig, "realm");
                putOptional(config, payload, existingConfig, "nonce");
                config.put("algorithm", firstNonBlank(stringValue(payload.get("algorithm")), firstNonBlank(stringValue(existingConfig.get("algorithm")), "MD5")));
                config.put("qop", firstNonBlank(stringValue(payload.get("qop")), firstNonBlank(stringValue(existingConfig.get("qop")), "auth")));
            }
        }
        return config;
    }

    private String preview(ApiAuthType authType, Map<String, Object> config) {
        return switch (authType) {
            case NO_AUTH -> "No Auth";
            case API_KEY -> "API Key " + String.valueOf(config.getOrDefault("addTo", "HEADER")).toLowerCase(Locale.ROOT) + ":" + config.get("key");
            case BEARER -> "Bearer " + mask(stringValue(config.get("token")));
            case BASIC -> "Basic " + mask(stringValue(config.get("username")));
            case DIGEST -> "Digest " + mask(stringValue(config.get("username")));
        };
    }

    private Map<String, Object> readConfig(String ciphertext) {
        String json = cryptoService.decrypt(ciphertext);
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "鉴权配置 JSON 格式不正确");
        }
    }

    private String writeJson(Map<String, Object> config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "鉴权配置 JSON 格式不正确");
        }
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            source.forEach((key, item) -> {
                if (key != null) {
                    result.put(String.valueOf(key), item);
                }
            });
            return result;
        }
        return new LinkedHashMap<>();
    }

    private void putOptional(Map<String, Object> target, Map<String, Object> source, Map<String, Object> existingConfig, String key) {
        String value = firstNonBlank(stringValue(source.get(key)), stringValue(existingConfig.get(key)));
        if (value != null && !value.isBlank()) {
            target.put(key, value.trim());
        }
    }

    private String required(Map<String, Object> payload, Map<String, Object> existingConfig, String key) {
        String value = payload == null ? null : firstNonBlank(stringValue(payload.get(key)), stringValue(existingConfig.get(key)));
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " 不能为空");
        }
        return value.trim();
    }

    private String mask(String value) {
        if (value == null || value.isBlank()) {
            return "••••";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 4) {
            return "••••";
        }
        return "••••" + trimmed.substring(trimmed.length() - 4);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    public record EffectiveAuth(ApiAuthType authType, Map<String, Object> config, String preview) {
        public static EffectiveAuth noAuth() {
            return new EffectiveAuth(ApiAuthType.NO_AUTH, Map.of(), "No Auth");
        }
    }
}
