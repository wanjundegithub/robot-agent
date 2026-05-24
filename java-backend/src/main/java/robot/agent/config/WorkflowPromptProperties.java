package robot.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "robot.workflow.prompts")
public class WorkflowPromptProperties {

    private Map<String, String> system = defaultSystemPrompts();

    public Map<String, String> getSystem() {
        return system;
    }

    public void setSystem(Map<String, String> system) {
        Map<String, String> merged = defaultSystemPrompts();
        if (system != null) {
            system.forEach((key, value) -> {
                if (key != null && value != null && !value.isBlank()) {
                    merged.put(key, value.trim());
                }
            });
        }
        this.system = merged;
    }

    public Map<String, Object> asWorkflowConfigSystemPrompts() {
        return new LinkedHashMap<>(system);
    }

    private Map<String, String> defaultSystemPrompts() {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("workflow_control", "你是工作流执行路由器，只能根据流程定义输出结构化 JSON。禁止生成用户可见业务话术，禁止补充、推断或要求流程中未声明的槽位、变量、步骤。如果流程定义中没有声明输入变量，则不得要求用户提供任何业务字段。除 fallback 场景外，message / welcome_message 必须为空或省略。fallback 话术只能说明无法处理或请求用户重新表达，不得包含具体业务办理步骤、槽位名或成功承诺。");
        defaults.put("intent_routing", "You are an intent router. Return JSON only. You must choose workflow_code only from provided candidate_workflows. If no candidate is reliable or the requested service is not available, return matched=false. When matched=false, generate clarification_question as the user-facing fallback message in Chinese; do not leave it empty. 禁止生成候选流程以外的业务流程、槽位、办理步骤或成功承诺；兜底话术只能说明无法处理或请求用户重新表达。");
        defaults.put("workflow_welcome", "你是工作流欢迎语决策引擎，只返回 JSON。只能基于 workflow_summary、session_context 和已配置 opening_messages 判断是否需要欢迎。不得推断、补充或要求流程中未声明的槽位、变量、步骤；不得生成办理成功承诺。如果没有可复用的配置话术或无法确认欢迎语符合流程定义，应返回 should_greet=false。");
        return defaults;
    }
}
