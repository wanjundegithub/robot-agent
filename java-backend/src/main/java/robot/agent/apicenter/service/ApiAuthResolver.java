package robot.agent.apicenter.service;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import robot.agent.apicenter.model.ApiAuthType;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class ApiAuthResolver {

    public AuthAppliedRequest apply(String url, Map<String, String> explicitHeaders, ApiAuthConfigService.EffectiveAuth effectiveAuth) {
        ApiAuthConfigService.EffectiveAuth auth = effectiveAuth == null ? ApiAuthConfigService.EffectiveAuth.noAuth() : effectiveAuth;
        Map<String, String> headers = new LinkedHashMap<>();
        String resolvedUrl = url;
        if (auth.authType() == ApiAuthType.API_KEY) {
            String addTo = stringValue(auth.config().getOrDefault("addTo", "HEADER")).toUpperCase(Locale.ROOT);
            String key = stringValue(auth.config().get("key"));
            String value = stringValue(auth.config().get("value"));
            if ("QUERY".equals(addTo)) {
                resolvedUrl = appendQueryIfAbsent(resolvedUrl, key, value);
            } else {
                putIfPresent(headers, key, value);
            }
        } else if (auth.authType() == ApiAuthType.BEARER) {
            putIfPresent(headers, HttpHeaders.AUTHORIZATION, "Bearer " + stringValue(auth.config().get("token")));
        } else if (auth.authType() == ApiAuthType.BASIC) {
            String username = stringValue(auth.config().get("username"));
            String password = stringValue(auth.config().get("password"));
            String credential = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
            putIfPresent(headers, HttpHeaders.AUTHORIZATION, "Basic " + credential);
        }
        if (explicitHeaders != null) {
            explicitHeaders.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null) {
                    removeHeaderIgnoreCase(headers, key);
                    headers.put(key, value);
                }
            });
        }
        return new AuthAppliedRequest(resolvedUrl, headers, auth.authType(), auth.config(), auth.preview());
    }

    private String appendQueryIfAbsent(String url, String key, String value) {
        if (key == null || key.isBlank() || value == null) {
            return url;
        }
        String fragment = "";
        String withoutFragment = url;
        int fragmentIndex = url.indexOf('#');
        if (fragmentIndex >= 0) {
            fragment = url.substring(fragmentIndex);
            withoutFragment = url.substring(0, fragmentIndex);
        }
        int queryIndex = withoutFragment.indexOf('?');
        String rawQuery = queryIndex >= 0 ? withoutFragment.substring(queryIndex + 1) : null;
        if (containsQueryKey(rawQuery, key)) {
            return url;
        }
        String pair = encode(key) + "=" + encode(value);
        String separator = rawQuery == null || rawQuery.isBlank() ? "?" : "&";
        return withoutFragment + separator + pair + fragment;
    }

    private boolean containsQueryKey(String rawQuery, String key) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return false;
        }
        String encodedKey = encode(key);
        for (String part : rawQuery.split("&")) {
            int equals = part.indexOf('=');
            String currentKey = equals >= 0 ? part.substring(0, equals) : part;
            if (currentKey.equals(key) || currentKey.equals(encodedKey)) {
                return true;
            }
        }
        return false;
    }

    private void putIfPresent(Map<String, String> headers, String key, String value) {
        if (key != null && !key.isBlank() && value != null) {
            headers.put(key, value);
        }
    }

    private void removeHeaderIgnoreCase(Map<String, String> headers, String key) {
        String existingKey = null;
        for (String currentKey : headers.keySet()) {
            if (currentKey.equalsIgnoreCase(key)) {
                existingKey = currentKey;
                break;
            }
        }
        if (existingKey != null) {
            headers.remove(existingKey);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record AuthAppliedRequest(String url, Map<String, String> headers, ApiAuthType authType, Map<String, Object> config, String preview) {}
}
