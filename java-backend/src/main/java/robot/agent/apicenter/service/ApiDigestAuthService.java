package robot.agent.apicenter.service;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class ApiDigestAuthService {

    private final SecureRandom secureRandom = new SecureRandom();

    public String buildAuthorization(String challenge, String method, URI uri, Map<String, Object> config) {
        Map<String, String> challengeValues = parseChallenge(challenge);
        String username = stringValue(config.get("username"));
        String password = stringValue(config.get("password"));
        String realm = firstNonBlank(challengeValues.get("realm"), stringValue(config.get("realm")));
        String nonce = firstNonBlank(challengeValues.get("nonce"), stringValue(config.get("nonce")));
        String qop = chooseQop(firstNonBlank(challengeValues.get("qop"), stringValue(config.get("qop"))));
        String algorithm = firstNonBlank(challengeValues.get("algorithm"), stringValue(config.getOrDefault("algorithm", "MD5"))).toUpperCase(Locale.ROOT);
        String opaque = challengeValues.get("opaque");
        String digestUri = digestUri(uri);
        String nc = "00000001";
        String cnonce = cnonce();
        String ha1 = md5(username + ":" + realm + ":" + password);
        String ha2 = md5(method.toUpperCase(Locale.ROOT) + ":" + digestUri);
        String response = qop == null || qop.isBlank()
                ? md5(ha1 + ":" + nonce + ":" + ha2)
                : md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);
        StringBuilder header = new StringBuilder("Digest ");
        appendQuoted(header, "username", username);
        appendQuoted(header, "realm", realm);
        appendQuoted(header, "nonce", nonce);
        appendQuoted(header, "uri", digestUri);
        appendQuoted(header, "response", response);
        if (algorithm != null && !algorithm.isBlank()) {
            appendToken(header, "algorithm", algorithm);
        }
        if (opaque != null && !opaque.isBlank()) {
            appendQuoted(header, "opaque", opaque);
        }
        if (qop != null && !qop.isBlank()) {
            appendToken(header, "qop", qop);
            appendToken(header, "nc", nc);
            appendQuoted(header, "cnonce", cnonce);
        }
        return header.toString();
    }

    public boolean isDigestChallenge(String header) {
        return header != null && header.trim().toLowerCase(Locale.ROOT).startsWith("digest ");
    }

    private Map<String, String> parseChallenge(String challenge) {
        String value = challenge == null ? "" : challenge.trim();
        if (value.toLowerCase(Locale.ROOT).startsWith("digest ")) {
            value = value.substring(7).trim();
        }
        Map<String, String> result = new LinkedHashMap<>();
        StringBuilder token = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '"') {
                quoted = !quoted;
            }
            if (current == ',' && !quoted) {
                putChallengePart(result, token.toString());
                token.setLength(0);
            } else {
                token.append(current);
            }
        }
        putChallengePart(result, token.toString());
        return result;
    }

    private void putChallengePart(Map<String, String> result, String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        int equals = part.indexOf('=');
        if (equals <= 0) {
            return;
        }
        String key = part.substring(0, equals).trim();
        String value = part.substring(equals + 1).trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        result.put(key, value);
    }

    private String digestUri(URI uri) {
        String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
        return uri.getRawQuery() == null || uri.getRawQuery().isBlank() ? path : path + "?" + uri.getRawQuery();
    }

    private String chooseQop(String qop) {
        if (qop == null || qop.isBlank()) {
            return "auth";
        }
        for (String part : qop.split(",")) {
            if ("auth".equalsIgnoreCase(part.trim())) {
                return "auth";
            }
        }
        return qop.trim();
    }

    private void appendQuoted(StringBuilder builder, String key, String value) {
        appendSeparator(builder);
        builder.append(key).append("=\"").append(value == null ? "" : value).append('"');
    }

    private void appendToken(StringBuilder builder, String key, String value) {
        appendSeparator(builder);
        builder.append(key).append('=').append(value == null ? "" : value);
    }

    private void appendSeparator(StringBuilder builder) {
        if (builder.length() > "Digest ".length()) {
            builder.append(", ");
        }
    }

    private String md5(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Digest 鉴权计算失败", exception);
        }
    }

    private String cnonce() {
        byte[] bytes = new byte[8];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }
}
