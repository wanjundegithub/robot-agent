package robot.agent.apicenter.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ApiUrlTemplateResolver {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)\\}");

    public List<String> extractVariables(String url) {
        if (url == null || url.isBlank()) {
            return List.of();
        }
        Set<String> variables = new LinkedHashSet<>();
        Matcher matcher = VARIABLE_PATTERN.matcher(url);
        while (matcher.find()) {
            variables.add(matcher.group(1));
        }
        return new ArrayList<>(variables);
    }

    public String resolve(String url, Map<String, ?> values) {
        if (url == null || url.isBlank()) {
            return url;
        }
        Matcher matcher = VARIABLE_PATTERN.matcher(url);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String variableName = matcher.group(1);
            Object value = values == null ? null : values.get(variableName);
            if (value == null) {
                throw new IllegalArgumentException("缺少 URL 变量: " + variableName);
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}
