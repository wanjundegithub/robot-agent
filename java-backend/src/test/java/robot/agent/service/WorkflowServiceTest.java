package robot.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import reactor.core.publisher.Mono;
import robot.agent.dto.request.CreateWorkflowVersionRequest;
import robot.agent.model.Workflow;
import robot.agent.model.WorkflowStatus;
import robot.agent.model.WorkflowVersion;
import robot.agent.model.WorkflowVersionStatus;
import robot.agent.repository.WorkflowRepository;
import robot.agent.repository.WorkflowVersionRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void validateWorkflowDefinitionRejectsInvalidV2GraphRules() {
        WorkflowService workflowService = newWorkflowService();
        String definition = """
                {
                  "schema_version": "workflow-designer/v2",
                  "main_graph_id": "main",
                  "graphs": {
                    "main": {
                      "graph_id": "main",
                      "graph_type": "main",
                      "entry_node_id": "start_main",
                      "nodes": {
                        "start_main": {"id": "start_main", "type": "start", "config": {"prompt": "开始"}},
                        "message_1": {"id": "message_1", "type": "message", "config": {"message_text": "处理中"}},
                        "sub_agent_1": {"id": "sub_agent_1", "type": "sub_agent", "config": {"prompt": "子代理", "subgraph_id": "missing_sub"}},
                        "tool_a": {"id": "tool_a", "type": "tool", "config": {"invoke_type": "capability", "group_id": "1", "group_snapshot_version": "v1", "capability_type": "API", "capability_code": "a"}},
                        "tool_b": {"id": "tool_b", "type": "tool", "config": {"invoke_type": "capability", "group_id": "1", "group_snapshot_version": "v1", "capability_type": "API", "capability_code": "b"}},
                        "end_main": {"id": "end_main", "type": "end", "config": {"prompt": "结束", "output_format": {}}}
                      },
                      "edges": [
                        {"id": "e1", "source": "start_main", "target": "message_1"},
                        {"id": "e2", "source": "message_1", "target": "tool_a"},
                        {"id": "e3", "source": "message_1", "target": "tool_b"},
                        {"id": "e4", "source": "tool_a", "target": "sub_agent_1"},
                        {"id": "e5", "source": "tool_b", "target": "end_main"},
                        {"id": "e6", "source": "sub_agent_1", "target": "end_main"}
                      ]
                    },
                    "sub_a": {
                      "graph_id": "sub_a",
                      "graph_type": "subflow",
                      "entry_node_id": "start_sub",
                      "nodes": {
                        "start_sub": {"id": "start_sub", "type": "start", "config": {"prompt": "子图开始"}},
                        "coordinator_bad": {"id": "coordinator_bad", "type": "coordinator", "config": {"prompt": "非法协调者"}},
                        "end_sub": {"id": "end_sub", "type": "end", "config": {"prompt": "子图结束", "output_format": {}}}
                      },
                      "edges": [
                        {"id": "s1", "source": "start_sub", "target": "coordinator_bad"},
                        {"id": "s2", "source": "coordinator_bad", "target": "end_sub"}
                      ]
                    }
                  },
                  "variables": {"global": [], "temporary": []}
                }
                """;

        List<Map<String, Object>> issues = workflowService.validateWorkflowDefinition(definition, "{}");

        assertThat(issues)
                .extracting(item -> item.get("message"))
                .contains(
                        "子流程中不允许出现 coordinator 节点",
                        "只有 coordinator 或 sub_agent 节点允许存在多条出边",
                        "sub_agent 节点的 subgraph_id 必须引用已存在的子图"
                );
    }

    @Test
    void validateWorkflowDefinitionRejectsGraphWithoutSingleStartAndEnd() {
        WorkflowService workflowService = newWorkflowService();
        String definition = """
                {
                  "schema_version": "workflow-designer/v2",
                  "main_graph_id": "main",
                  "graphs": {
                    "main": {
                      "graph_id": "main",
                      "graph_type": "main",
                      "entry_node_id": "start_a",
                      "nodes": {
                        "start_a": {"id": "start_a", "type": "start", "config": {"prompt": "开始A"}},
                        "start_b": {"id": "start_b", "type": "start", "config": {"prompt": "开始B"}},
                        "message_1": {"id": "message_1", "type": "message", "config": {"message_text": "处理中"}}
                      },
                      "edges": [
                        {"id": "e1", "source": "start_a", "target": "message_1"},
                        {"id": "e2", "source": "start_b", "target": "message_1"}
                      ]
                    }
                  }
                }
                """;

        List<Map<String, Object>> issues = workflowService.validateWorkflowDefinition(definition, "{}");

        assertThat(issues)
                .extracting(item -> item.get("message"))
                .contains(
                        "图 main 必须且只能有一个 start 节点",
                        "图 main 必须且只能有一个 end 节点"
                );
    }

    @Test
    void validateWorkflowDefinitionAllowsCoordinatorOnlyMainGraphWithSubflowLeafExit() {
        WorkflowService workflowService = newWorkflowService();
        String definition = """
                {
                  "schema_version": "workflow-designer/v2",
                  "main_graph_id": "main",
                  "graphs": {
                    "main": {
                      "graph_id": "main",
                      "graph_type": "main",
                      "entry_node_id": "coordinator_main",
                      "nodes": {
                        "coordinator_main": {"id": "coordinator_main", "type": "coordinator", "config": {"prompt": "路由到子代理"}},
                        "sub_agent_booking": {"id": "sub_agent_booking", "type": "sub_agent", "config": {"prompt": "进入订票子流程", "subgraph_id": "sub_booking"}}
                      },
                      "edges": [
                        {"id": "e1", "source": "coordinator_main", "target": "sub_agent_booking"}
                      ]
                    },
                    "sub_booking": {
                      "graph_id": "sub_booking",
                      "graph_type": "subflow",
                      "entry_node_id": "start_sub",
                      "nodes": {
                        "start_sub": {"id": "start_sub", "type": "start", "config": {"prompt": "开始子流程"}},
                        "message_sub": {"id": "message_sub", "type": "message", "config": {"message_text": "处理中"}},
                        "end_sub": {"id": "end_sub", "type": "end", "config": {"prompt": "结束子流程", "output_format": {}}}
                      },
                      "edges": [
                        {"id": "s1", "source": "start_sub", "target": "message_sub"},
                        {"id": "s2", "source": "message_sub", "target": "end_sub"}
                      ]
                    }
                  }
                }
                """;

        List<Map<String, Object>> issues = workflowService.validateWorkflowDefinition(definition, "{}");

        assertThat(issues)
                .extracting(item -> item.get("message"))
                .doesNotContain(
                        "图 main 必须且只能有一个 start 节点",
                        "图 main 必须且只能有一个 end 节点"
                );
    }

    @Test
    void validateWorkflowDefinitionChecksCapabilityToolFieldsInV2Graph() {
        WorkflowService workflowService = newWorkflowService();
        String definition = """
                {
                  "schema_version": "workflow-designer/v2",
                  "main_graph_id": "main",
                  "graphs": {
                    "main": {
                      "graph_id": "main",
                      "graph_type": "main",
                      "entry_node_id": "start_main",
                      "nodes": {
                        "start_main": {"id": "start_main", "type": "start", "config": {"prompt": "开始"}},
                        "tool_a": {"id": "tool_a", "type": "tool", "config": {"invoke_type": "capability", "group_id": "1", "group_snapshot_version": "v1", "capability_type": "API"}},
                        "end_main": {"id": "end_main", "type": "end", "config": {"prompt": "结束", "output_format": {}}}
                      },
                      "edges": [
                        {"id": "e1", "source": "start_main", "target": "tool_a"},
                        {"id": "e2", "source": "tool_a", "target": "end_main"}
                      ]
                    }
                  }
                }
                """;

        List<Map<String, Object>> issues = workflowService.validateWorkflowDefinition(definition, "{}");

        assertThat(issues)
                .extracting(item -> item.get("field"))
                .contains("config.capability_code");
    }

    @Test
    void validateWorkflowDefinitionAllowsPublishedCapabilityToolWithoutSnapshotFields() {
        WorkflowService workflowService = newWorkflowService();
        String definition = """
                {
                  "schema_version": "workflow-designer/v2",
                  "main_graph_id": "main",
                  "graphs": {
                    "main": {
                      "graph_id": "main",
                      "graph_type": "main",
                      "entry_node_id": "coordinator_main",
                      "nodes": {
                        "coordinator_main": {"id": "coordinator_main", "type": "coordinator", "config": {"prompt": "进入子流程"}},
                        "sub_agent_booking": {"id": "sub_agent_booking", "type": "sub_agent", "config": {"prompt": "运行子流程", "subgraph_id": "sub_booking"}}
                      },
                      "edges": [
                        {"id": "e1", "source": "coordinator_main", "target": "sub_agent_booking"}
                      ]
                    },
                    "sub_booking": {
                      "graph_id": "sub_booking",
                      "graph_type": "subflow",
                      "entry_node_id": "start_sub",
                      "nodes": {
                        "start_sub": {"id": "start_sub", "type": "start", "config": {"prompt": "开始子流程"}},
                        "tool_lookup": {"id": "tool_lookup", "type": "tool", "config": {"invoke_type": "capability", "group_id": "7", "capability_code": "search_flights", "payload_mapping": {}}},
                        "end_sub": {"id": "end_sub", "type": "end", "config": {"prompt": "结束子流程", "output_format": {}}}
                      },
                      "edges": [
                        {"id": "s1", "source": "start_sub", "target": "tool_lookup"},
                        {"id": "s2", "source": "tool_lookup", "target": "end_sub"}
                      ]
                    }
                  }
                }
                """;

        List<Map<String, Object>> issues = workflowService.validateWorkflowDefinition(definition, "{}");

        assertThat(issues)
                .extracting(item -> item.get("field"))
                .doesNotContain("config.group_snapshot_version", "config.capability_type");
    }

    @Test
    void buildRuntimeExecutionBundleNormalizesLegacyDefinitionToV2Snapshot() {
        WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
        WorkflowVersionRepository workflowVersionRepository = mock(WorkflowVersionRepository.class);
        AccessControlService accessControlService = mock(AccessControlService.class);
        AuditService auditService = mock(AuditService.class);
        PythonClient pythonClient = mock(PythonClient.class);
        ModelConfigService modelConfigService = mock(ModelConfigService.class);
        when(workflowVersionRepository.findByStatusOrderByCreatedAtDesc(WorkflowVersionStatus.PUBLISHED))
                .thenReturn(List.of());
        when(modelConfigService.resolveRoutingModelCode(ArgumentMatchers.anyCollection()))
                .thenReturn("intent-router-v1");
        when(modelConfigService.buildRuntimeBundle(ArgumentMatchers.anyCollection(), ArgumentMatchers.eq("intent-router-v1")))
                .thenReturn(new ModelConfigService.RuntimeModelBundle(List.of(), List.of()));

        WorkflowService workflowService = new WorkflowService(
                workflowRepository,
                workflowVersionRepository,
                objectMapper,
                accessControlService,
                auditService,
                pythonClient,
                modelConfigService
        );

        Map<String, Object> legacyDefinition = Map.of(
                "entry", "start",
                "nodes", Map.of(
                        "start", Map.of("id", "start", "type", "start", "config", Map.of("prompt", "开始")),
                        "route", Map.of("id", "route", "type", "coordinate", "config", Map.of("prompt", "路由")),
                        "legacy_sub", Map.of("id", "legacy_sub", "type", "subflow", "config", Map.of(
                                "prompt", "旧子流程",
                                "subflow_id", "child_graph"
                        )),
                        "end", Map.of("id", "end", "type", "end", "config", Map.of("prompt", "结束", "output_format", Map.of()))
                ),
                "transitions", Map.of(
                        "start", "route",
                        "route", "legacy_sub",
                        "legacy_sub", "end"
                )
        );

        WorkflowService.RuntimeExecutionBundle bundle = workflowService.buildRuntimeExecutionBundle(
                "legacy_flow",
                "1.0.0",
                legacyDefinition,
                Map.of(),
                Map.of("legacy", true)
        );

        assertThat(bundle.workflowDefinition()).containsEntry("schema_version", "workflow-designer/v2");
        assertThat(bundle.workflowDefinition()).containsEntry("main_graph_id", "main");
        @SuppressWarnings("unchecked")
        Map<String, Object> graphs = (Map<String, Object>) bundle.workflowDefinition().get("graphs");
        @SuppressWarnings("unchecked")
        Map<String, Object> main = (Map<String, Object>) graphs.get("main");
        @SuppressWarnings("unchecked")
        Map<String, Object> nodes = (Map<String, Object>) main.get("nodes");
        @SuppressWarnings("unchecked")
        Map<String, Object> route = (Map<String, Object>) nodes.get("route");
        @SuppressWarnings("unchecked")
        Map<String, Object> legacySub = (Map<String, Object>) nodes.get("legacy_sub");
        @SuppressWarnings("unchecked")
        Map<String, Object> legacySubConfig = (Map<String, Object>) legacySub.get("config");

        assertThat(route.get("type")).isEqualTo("coordinator");
        assertThat(legacySub.get("type")).isEqualTo("sub_agent");
        assertThat(legacySubConfig.get("subgraph_id")).isEqualTo("child_graph");
        assertThat(main).containsKey("edges");
        assertThat(bundle.workflowConfig()).containsEntry("legacy", true);
        assertThat(bundle.modelRecords()).isEmpty();
        assertThat(bundle.routingModelCode()).isEqualTo("intent-router-v1");
    }

    @Test
    void buildRuntimeExecutionBundlePreservesExplicitRuntimeModelKeys() {
        WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
        WorkflowVersionRepository workflowVersionRepository = mock(WorkflowVersionRepository.class);
        AccessControlService accessControlService = mock(AccessControlService.class);
        AuditService auditService = mock(AuditService.class);
        PythonClient pythonClient = mock(PythonClient.class);
        ModelConfigService modelConfigService = mock(ModelConfigService.class);
        when(workflowVersionRepository.findByStatusOrderByCreatedAtDesc(WorkflowVersionStatus.PUBLISHED))
                .thenReturn(List.of());
        when(modelConfigService.resolveRoutingModelCode(ArgumentMatchers.anyCollection()))
                .thenReturn("intent-router-v1");
        when(modelConfigService.buildRuntimeBundle(ArgumentMatchers.anyCollection(), ArgumentMatchers.eq("intent-router-v1")))
                .thenReturn(new ModelConfigService.RuntimeModelBundle(List.of(), List.of()));

        WorkflowService workflowService = new WorkflowService(
                workflowRepository,
                workflowVersionRepository,
                objectMapper,
                accessControlService,
                auditService,
                pythonClient,
                modelConfigService
        );

        Map<String, Object> legacyDefinition = Map.of(
                "entry", "start",
                "nodes", Map.of(
                        "start", Map.of("id", "start", "type", "start", "config", Map.of("prompt", "start")),
                        "chat", Map.of("id", "chat", "type", "sub_agent", "config", Map.of("model_code", "chat-main")),
                        "end", Map.of("id", "end", "type", "end", "config", Map.of("prompt", "done", "output_format", Map.of()))
                ),
                "transitions", Map.of(
                        "start", "chat",
                        "chat", "end"
                )
        );
        Map<String, Object> workflowConfig = Map.of(
                "routing_model_code", "router-main",
                "llm_defaults", Map.of("model_code", "default-chat")
        );

        WorkflowService.RuntimeExecutionBundle bundle = workflowService.buildRuntimeExecutionBundle(
                "legacy_flow",
                "1.0.0",
                legacyDefinition,
                Map.of(),
                workflowConfig
        );

        assertThat(bundle.workflowConfig()).containsEntry("routing_model_code", "router-main");
        @SuppressWarnings("unchecked")
        Map<String, Object> llmDefaults = (Map<String, Object>) bundle.workflowConfig().get("llm_defaults");
        assertThat(llmDefaults).containsEntry("model_code", "default-chat");

        @SuppressWarnings("unchecked")
        Map<String, Object> graphs = (Map<String, Object>) bundle.workflowDefinition().get("graphs");
        @SuppressWarnings("unchecked")
        Map<String, Object> main = (Map<String, Object>) graphs.get("main");
        @SuppressWarnings("unchecked")
        Map<String, Object> nodes = (Map<String, Object>) main.get("nodes");
        @SuppressWarnings("unchecked")
        Map<String, Object> chat = (Map<String, Object>) nodes.get("chat");
        @SuppressWarnings("unchecked")
        Map<String, Object> chatConfig = (Map<String, Object>) chat.get("config");
        assertThat(chatConfig).containsEntry("model_code", "chat-main");
    }

    @Test
    void validateWorkflowDefinitionSupportsLegacyCoordinateBranchingCompatibility() {
        WorkflowService workflowService = newWorkflowService();
        String definition = """
                {
                  "entry": "start",
                  "nodes": {
                    "start": {"id": "start", "type": "start", "config": {"prompt": "开始"}},
                    "route": {"id": "route", "type": "coordinate", "config": {"prompt": "路由"}},
                    "message_a": {"id": "message_a", "type": "message", "config": {"message_text": "A"}},
                    "message_b": {"id": "message_b", "type": "message", "config": {"message_text": "B"}},
                    "end": {"id": "end", "type": "end", "config": {"prompt": "结束", "output_format": {}}}
                  },
                  "transitions": {
                    "start": "route",
                    "route": {"a": "message_a", "b": "message_b"},
                    "message_a": "end",
                    "message_b": "end"
                  }
                }
                """;

        List<Map<String, Object>> issues = workflowService.validateWorkflowDefinition(definition, "{}");

        assertThat(issues)
                .extracting(item -> item.get("message"))
                .doesNotContain("只有 coordinator 或 sub_agent 节点允许存在多条出边");
    }

    @Test
    void validateWorkflowDefinitionRejectsUnsupportedSchemaVersion() {
        WorkflowService workflowService = newWorkflowService();
        String definition = """
                {
                  "schema_version": "workflow-designer/v3",
                  "main_graph_id": "main",
                  "graphs": {
                    "main": {
                      "graph_id": "main",
                      "graph_type": "main",
                      "entry_node_id": "start",
                      "nodes": {
                        "start": {"id": "start", "type": "start", "config": {"prompt": "开始"}},
                        "end": {"id": "end", "type": "end", "config": {"prompt": "结束", "output_format": {}}}
                      },
                      "edges": [{"id": "e1", "source": "start", "target": "end"}]
                    }
                  }
                }
                """;

        List<Map<String, Object>> issues = workflowService.validateWorkflowDefinition(definition, "{}");
        assertThat(issues).extracting(item -> item.get("field")).contains("schema_version");
    }

    @Test
    void validateWorkflowDefinitionRejectsInvalidMainGraphSemantics() {
        WorkflowService workflowService = newWorkflowService();
        String definition = """
                {
                  "schema_version": "workflow-designer/v2",
                  "main_graph_id": "sub_a",
                  "graphs": {
                    "main_a": {
                      "graph_id": "main_a",
                      "graph_type": "main",
                      "entry_node_id": "start",
                      "nodes": {
                        "start": {"id": "start", "type": "start", "config": {"prompt": "A开始"}},
                        "end": {"id": "end", "type": "end", "config": {"prompt": "A结束", "output_format": {}}}
                      },
                      "edges": [{"id": "e1", "source": "start", "target": "end"}]
                    },
                    "main_b": {
                      "graph_id": "main_b",
                      "graph_type": "main",
                      "entry_node_id": "start_b",
                      "nodes": {
                        "start_b": {"id": "start_b", "type": "start", "config": {"prompt": "B开始"}},
                        "end_b": {"id": "end_b", "type": "end", "config": {"prompt": "B结束", "output_format": {}}}
                      },
                      "edges": [{"id": "e2", "source": "start_b", "target": "end_b"}]
                    },
                    "sub_a": {
                      "graph_id": "sub_a",
                      "graph_type": "subflow",
                      "entry_node_id": "start_sub",
                      "nodes": {
                        "start_sub": {"id": "start_sub", "type": "start", "config": {"prompt": "子图开始"}},
                        "end_sub": {"id": "end_sub", "type": "end", "config": {"prompt": "子图结束", "output_format": {}}}
                      },
                      "edges": [{"id": "e3", "source": "start_sub", "target": "end_sub"}]
                    }
                  }
                }
                """;

        List<Map<String, Object>> issues = workflowService.validateWorkflowDefinition(definition, "{}");
        assertThat(issues)
                .extracting(item -> item.get("message"))
                .contains("工作流必须且只能有一个 main 图", "main_graph_id 指向的图必须是 main");
    }

    @Test
    void saveWorkflowDraftNormalizesDefinitionToV2BeforePersist() throws Exception {
        WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
        WorkflowVersionRepository workflowVersionRepository = mock(WorkflowVersionRepository.class);
        AccessControlService accessControlService = mock(AccessControlService.class);
        AuditService auditService = mock(AuditService.class);
        PythonClient pythonClient = mock(PythonClient.class);
        ModelConfigService modelConfigService = mock(ModelConfigService.class);

        Workflow existingWorkflow = new Workflow();
        existingWorkflow.setWorkflowCode("legacy_flow");
        existingWorkflow.setName("legacy_flow");
        existingWorkflow.setWorkspaceId(1L);
        existingWorkflow.setStatus(WorkflowStatus.DRAFT);
        when(workflowRepository.findByWorkflowCode("legacy_flow")).thenReturn(Optional.of(existingWorkflow));
        when(workflowVersionRepository.findByWorkflowCodeAndVersion("legacy_flow", "v1")).thenReturn(Optional.empty());
        when(workflowVersionRepository.save(any(WorkflowVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(workflowVersionRepository.findByStatusOrderByCreatedAtDesc(WorkflowVersionStatus.PUBLISHED)).thenReturn(List.of());
        when(modelConfigService.resolveRoutingModelCode(ArgumentMatchers.anyCollection())).thenReturn("intent-router-v1");
        when(modelConfigService.buildRuntimeBundle(ArgumentMatchers.anyCollection(), ArgumentMatchers.eq("intent-router-v1")))
                .thenReturn(new ModelConfigService.RuntimeModelBundle(List.of(), List.of()));

        WorkflowService workflowService = new WorkflowService(
                workflowRepository,
                workflowVersionRepository,
                objectMapper,
                accessControlService,
                auditService,
                pythonClient,
                modelConfigService
        );

        CreateWorkflowVersionRequest request = new CreateWorkflowVersionRequest();
        request.setVersion("v1");
        request.setDefinition("""
                {
                  "entry":"start",
                  "nodes":{
                    "start":{"id":"start","type":"start","config":{"prompt":"开始"}},
                    "route":{"id":"route","type":"coordinate","config":{"prompt":"路由"}},
                    "end":{"id":"end","type":"end","config":{"prompt":"结束","output_format":{}}}
                  },
                  "transitions":{"start":"route","route":"end"}
                }
                """);
        request.setEntryRule("{}");
        request.setConfig("{}");

        workflowService.saveWorkflowDraft("tester", "legacy_flow", request);

        ArgumentCaptor<WorkflowVersion> captor = ArgumentCaptor.forClass(WorkflowVersion.class);
        verify(workflowVersionRepository).save(captor.capture());
        Map<String, Object> persistedDefinition = objectMapper.readValue(captor.getValue().getDefinition(), Map.class);
        assertThat(persistedDefinition).containsEntry("schema_version", "workflow-designer/v2");
        assertThat(persistedDefinition).containsKey("graphs");
    }

    @Test
    void saveWorkflowDraftRejectsUnsupportedSchemaVersion() {
        WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
        WorkflowVersionRepository workflowVersionRepository = mock(WorkflowVersionRepository.class);
        AccessControlService accessControlService = mock(AccessControlService.class);
        AuditService auditService = mock(AuditService.class);
        PythonClient pythonClient = mock(PythonClient.class);
        ModelConfigService modelConfigService = mock(ModelConfigService.class);

        Workflow existingWorkflow = new Workflow();
        existingWorkflow.setWorkflowCode("bad_schema_flow");
        existingWorkflow.setName("bad_schema_flow");
        existingWorkflow.setWorkspaceId(1L);
        existingWorkflow.setStatus(WorkflowStatus.DRAFT);
        when(workflowRepository.findByWorkflowCode("bad_schema_flow")).thenReturn(Optional.of(existingWorkflow));
        when(workflowVersionRepository.findByStatusOrderByCreatedAtDesc(WorkflowVersionStatus.PUBLISHED)).thenReturn(List.of());

        WorkflowService workflowService = new WorkflowService(
                workflowRepository,
                workflowVersionRepository,
                objectMapper,
                accessControlService,
                auditService,
                pythonClient,
                modelConfigService
        );

        CreateWorkflowVersionRequest request = new CreateWorkflowVersionRequest();
        request.setVersion("v1");
        request.setDefinition("""
                {
                  "schema_version":"workflow-designer/v9",
                  "main_graph_id":"main",
                  "graphs":{}
                }
                """);

        assertThatThrownBy(() -> workflowService.saveWorkflowDraft("tester", "bad_schema_flow", request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Unsupported schema_version");
    }

    @Test
    void deleteWorkflowVersionRemovesVersionAndClearsCurrentVersionWhenNoPublishedFallbackExists() {
        WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
        WorkflowVersionRepository workflowVersionRepository = mock(WorkflowVersionRepository.class);
        AccessControlService accessControlService = mock(AccessControlService.class);
        AuditService auditService = mock(AuditService.class);
        PythonClient pythonClient = mock(PythonClient.class);
        ModelConfigService modelConfigService = mock(ModelConfigService.class);

        Workflow workflow = new Workflow();
        workflow.setWorkflowCode("demo_flow");
        workflow.setName("Demo Flow");
        workflow.setWorkspaceId(1L);
        workflow.setStatus(WorkflowStatus.PUBLISHED);
        workflow.setCurrentVersion("v1");

        WorkflowVersion version = new WorkflowVersion();
        version.setWorkflowCode("demo_flow");
        version.setVersion("v1");
        version.setStatus(WorkflowVersionStatus.PUBLISHED);

        when(workflowRepository.findByWorkflowCode("demo_flow")).thenReturn(Optional.of(workflow));
        when(workflowVersionRepository.findByWorkflowCodeAndVersion("demo_flow", "v1")).thenReturn(Optional.of(version));
        when(workflowVersionRepository.findByWorkflowCodeAndStatusNotOrderByCreatedAtDesc("demo_flow", WorkflowVersionStatus.ARCHIVED))
                .thenReturn(List.of(version));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(workflowVersionRepository.findByStatusOrderByCreatedAtDesc(WorkflowVersionStatus.PUBLISHED)).thenReturn(List.of());

        WorkflowService workflowService = new WorkflowService(
                workflowRepository,
                workflowVersionRepository,
                objectMapper,
                accessControlService,
                auditService,
                pythonClient,
                modelConfigService
        );

        workflowService.deleteWorkflowVersion("tester", "demo_flow", "v1");

        assertThat(workflow.getCurrentVersion()).isNull();
        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.DRAFT);
        verify(workflowVersionRepository).delete(version);
        verify(auditService).logAction(
                1L,
                "tester",
                "workflow.version.delete",
                "workflow_version",
                "demo_flow:v1",
                null,
                200
        );
    }

    @Test
    void deleteWorkflowSoftDeletesWorkflowClearsCurrentVersionAndRecordsAudit() {
        WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
        WorkflowVersionRepository workflowVersionRepository = mock(WorkflowVersionRepository.class);
        AccessControlService accessControlService = mock(AccessControlService.class);
        AuditService auditService = mock(AuditService.class);
        PythonClient pythonClient = mock(PythonClient.class);
        ModelConfigService modelConfigService = mock(ModelConfigService.class);

        Workflow workflow = new Workflow();
        workflow.setWorkflowCode("demo_flow");
        workflow.setName("Demo Flow");
        workflow.setWorkspaceId(1L);
        workflow.setStatus(WorkflowStatus.PUBLISHED);
        workflow.setCurrentVersion("v1");

        when(workflowRepository.findByWorkflowCode("demo_flow")).thenReturn(Optional.of(workflow));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowService workflowService = new WorkflowService(
                workflowRepository,
                workflowVersionRepository,
                objectMapper,
                accessControlService,
                auditService,
                pythonClient,
                modelConfigService
        );

        workflowService.deleteWorkflow("tester", "demo_flow");

        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.ARCHIVED);
        assertThat(workflow.getCurrentVersion()).isNull();
        verify(accessControlService).requireWorkflowAdminAction("tester", 1L, "demo_flow", "workflow.delete");
        verify(workflowRepository).save(workflow);
        verify(workflowVersionRepository, never()).delete(any(WorkflowVersion.class));
        verify(auditService).logAction(
                1L,
                "tester",
                "workflow.delete",
                "workflow_definition",
                "demo_flow",
                null,
                200
        );
    }

    @Test
    void deleteWorkflowRepairsLegacyStatusEnumBeforeSoftDelete() {
        WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
        WorkflowVersionRepository workflowVersionRepository = mock(WorkflowVersionRepository.class);
        AccessControlService accessControlService = mock(AccessControlService.class);
        AuditService auditService = mock(AuditService.class);
        PythonClient pythonClient = mock(PythonClient.class);
        ModelConfigService modelConfigService = mock(ModelConfigService.class);
        WorkflowSchemaRepairService workflowSchemaRepairService = mock(WorkflowSchemaRepairService.class);

        Workflow workflow = new Workflow();
        workflow.setWorkflowCode("demo_flow");
        workflow.setName("Demo Flow");
        workflow.setWorkspaceId(1L);
        workflow.setStatus(WorkflowStatus.PUBLISHED);
        workflow.setCurrentVersion("v1");

        when(workflowRepository.findByWorkflowCode("demo_flow")).thenReturn(Optional.of(workflow));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowService workflowService = new WorkflowService(
                workflowRepository,
                workflowVersionRepository,
                objectMapper,
                accessControlService,
                auditService,
                pythonClient,
                modelConfigService,
                workflowSchemaRepairService
        );

        workflowService.deleteWorkflow("tester", "demo_flow");

        InOrder inOrder = inOrder(workflowSchemaRepairService, workflowRepository);
        inOrder.verify(workflowSchemaRepairService).ensureArchivedWorkflowStatusSupported();
        inOrder.verify(workflowRepository).save(workflow);
        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.ARCHIVED);
    }

    @Test
    void getAllWorkflowsExcludesSoftDeletedWorkflows() {
        WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
        WorkflowVersionRepository workflowVersionRepository = mock(WorkflowVersionRepository.class);
        AccessControlService accessControlService = mock(AccessControlService.class);
        AuditService auditService = mock(AuditService.class);
        PythonClient pythonClient = mock(PythonClient.class);
        ModelConfigService modelConfigService = mock(ModelConfigService.class);

        Workflow active = new Workflow();
        active.setWorkflowCode("active_flow");
        active.setName("Active Flow");
        active.setWorkspaceId(1L);
        active.setStatus(WorkflowStatus.PUBLISHED);

        when(workflowRepository.findByStatusNotOrderByCreatedAtDesc(WorkflowStatus.ARCHIVED))
                .thenReturn(List.of(active));

        WorkflowService workflowService = new WorkflowService(
                workflowRepository,
                workflowVersionRepository,
                objectMapper,
                accessControlService,
                auditService,
                pythonClient,
                modelConfigService
        );

        assertThat(workflowService.getAllWorkflows())
                .extracting(response -> response.getWorkflowCode())
                .containsExactly("active_flow");
    }

    @Test
    void getPublishedWorkflowsDoesNotReturnSoftDeletedWorkflow() {
        WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
        WorkflowVersionRepository workflowVersionRepository = mock(WorkflowVersionRepository.class);
        AccessControlService accessControlService = mock(AccessControlService.class);
        AuditService auditService = mock(AuditService.class);
        PythonClient pythonClient = mock(PythonClient.class);
        ModelConfigService modelConfigService = mock(ModelConfigService.class);

        Workflow published = new Workflow();
        published.setWorkflowCode("published_flow");
        published.setName("Published Flow");
        published.setWorkspaceId(1L);
        published.setStatus(WorkflowStatus.PUBLISHED);
        published.setCreatedBy("demo-admin");

        Workflow deleted = new Workflow();
        deleted.setWorkflowCode("deleted_flow");
        deleted.setName("Deleted Flow");
        deleted.setWorkspaceId(1L);
        deleted.setStatus(WorkflowStatus.ARCHIVED);

        Workflow systemSeed = new Workflow();
        systemSeed.setWorkflowCode("flight_booking");
        systemSeed.setName("Flight Booking");
        systemSeed.setWorkspaceId(1L);
        systemSeed.setStatus(WorkflowStatus.PUBLISHED);
        systemSeed.setCreatedBy("system");

        Workflow demoGenerated = new Workflow();
        demoGenerated.setWorkflowCode("cap_workflow_20260425203345");
        demoGenerated.setName("Capability Workflow");
        demoGenerated.setDescription("Auto-created draft workflow");
        demoGenerated.setWorkspaceId(1L);
        demoGenerated.setStatus(WorkflowStatus.PUBLISHED);
        demoGenerated.setCreatedBy("demo-admin");

        Workflow newlySaved = new Workflow();
        newlySaved.setWorkflowCode("workflow_20260430120000");
        newlySaved.setName("真实业务工作流");
        newlySaved.setDescription("Auto-created draft workflow");
        newlySaved.setWorkspaceId(1L);
        newlySaved.setStatus(WorkflowStatus.PUBLISHED);
        newlySaved.setCreatedBy("demo-admin");

        when(workflowRepository.findByStatusOrderByCreatedAtDesc(WorkflowStatus.PUBLISHED))
                .thenReturn(List.of(published, deleted, systemSeed, demoGenerated, newlySaved));

        WorkflowService workflowService = new WorkflowService(
                workflowRepository,
                workflowVersionRepository,
                objectMapper,
                accessControlService,
                auditService,
                pythonClient,
                modelConfigService
        );

        assertThat(workflowService.getPublishedWorkflows())
                .extracting(response -> response.getWorkflowCode())
                .containsExactly("published_flow", "workflow_20260430120000");
    }

    @Test
    void buildRuntimeExecutionBundleForExplicitExecutionUsesIsolatedCatalog() {
        WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
        WorkflowVersionRepository workflowVersionRepository = mock(WorkflowVersionRepository.class);
        AccessControlService accessControlService = mock(AccessControlService.class);
        AuditService auditService = mock(AuditService.class);
        PythonClient pythonClient = mock(PythonClient.class);
        ModelConfigService modelConfigService = mock(ModelConfigService.class);

        WorkflowVersion selected = new WorkflowVersion();
        selected.setWorkflowCode("travel_assistant");
        selected.setVersion("v20260426");
        selected.setDefinition("""
                {"schema_version":"workflow-designer/v2","main_graph_id":"main","graphs":{"main":{"graph_id":"main","graph_type":"main","entry_node_id":"start","nodes":{"start":{"id":"start","type":"start","config":{"prompt":"开始"}},"end":{"id":"end","type":"end","config":{"prompt":"结束","output_format":{}}}},"edges":[{"id":"e1","source":"start","target":"end"}]}}}
                """);
        selected.setEntryRule("{}");
        selected.setConfig("{}");
        when(workflowVersionRepository.findByWorkflowCodeAndVersion("travel_assistant", "v20260426"))
                .thenReturn(Optional.of(selected));
        when(workflowVersionRepository.findByStatusOrderByCreatedAtDesc(WorkflowVersionStatus.PUBLISHED))
                .thenReturn(List.of());
        when(modelConfigService.resolveRoutingModelCode(ArgumentMatchers.anyCollection()))
                .thenReturn("intent-router-v1");
        when(modelConfigService.buildRuntimeBundle(ArgumentMatchers.anyCollection(), ArgumentMatchers.eq("intent-router-v1")))
                .thenReturn(new ModelConfigService.RuntimeModelBundle(List.of(), List.of()));

        WorkflowService workflowService = new WorkflowService(
                workflowRepository,
                workflowVersionRepository,
                objectMapper,
                accessControlService,
                auditService,
                pythonClient,
                modelConfigService
        );

        WorkflowService.RuntimeExecutionBundle bundle = workflowService.buildRuntimeExecutionBundleForExplicitExecution(
                "travel_assistant",
                "v20260426"
        );

        assertThat(bundle.workflowCatalog()).hasSize(1);
        assertThat(bundle.workflowCatalog()).containsKey("travel_assistant@v20260426");
    }

    @Test
    void routeMessageFallsBackWhenModelConfigUnavailable() {
        WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
        WorkflowVersionRepository workflowVersionRepository = mock(WorkflowVersionRepository.class);
        AccessControlService accessControlService = mock(AccessControlService.class);
        AuditService auditService = mock(AuditService.class);
        PythonClient pythonClient = mock(PythonClient.class);
        ModelConfigService modelConfigService = mock(ModelConfigService.class);

        Workflow workflow = new Workflow();
        workflow.setWorkflowCode("general_query");
        workflow.setStatus(WorkflowStatus.PUBLISHED);
        workflow.setCurrentVersion("1.0.0");

        WorkflowVersion version = new WorkflowVersion();
        version.setWorkflowCode("general_query");
        version.setVersion("1.0.0");
        version.setStatus(WorkflowVersionStatus.PUBLISHED);
        version.setDefinition("""
                {
                  "entry": "start",
                  "nodes": {
                    "start": {"id": "start", "type": "start", "config": {"prompt": "开始"}},
                    "end": {"id": "end", "type": "end", "config": {"prompt": "结束", "output_format": {}}}
                  },
                  "transitions": {
                    "start": "end",
                    "end": null
                  }
                }
                """);
        version.setEntryRule("""
                {
                  "intent_codes": ["general_query"],
                  "keywords": ["帮助", "查询"],
                  "priority": 100
                }
                """);

        when(workflowRepository.findByStatusOrderByCreatedAtDesc(WorkflowStatus.PUBLISHED))
                .thenReturn(List.of(workflow));
        when(workflowVersionRepository.findByWorkflowCodeAndVersion("general_query", "1.0.0"))
                .thenReturn(Optional.of(version));
        when(modelConfigService.resolveRoutingModelCode(ArgumentMatchers.anyCollection()))
                .thenReturn("intent-router-v1");
        when(modelConfigService.buildRuntimeBundle(ArgumentMatchers.anyCollection(), ArgumentMatchers.eq("intent-router-v1")))
                .thenReturn(new ModelConfigService.RuntimeModelBundle(List.of(), List.of()));

        WorkflowService workflowService = new WorkflowService(
                workflowRepository,
                workflowVersionRepository,
                objectMapper,
                accessControlService,
                auditService,
                pythonClient,
                modelConfigService
        );

        RoutingDecision decision = workflowService.routeMessage("hi", null);

        assertThat(decision.workflowCode()).isEqualTo("general_query");
        assertThat(decision.reason()).isEqualTo("model_fallback");
        assertThat(decision.confidence()).isGreaterThanOrEqualTo(0.55d);
    }

    private WorkflowService newWorkflowService() {
        WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
        WorkflowVersionRepository workflowVersionRepository = mock(WorkflowVersionRepository.class);
        AccessControlService accessControlService = mock(AccessControlService.class);
        AuditService auditService = mock(AuditService.class);
        PythonClient pythonClient = mock(PythonClient.class);
        ModelConfigService modelConfigService = mock(ModelConfigService.class);
        when(workflowVersionRepository.findByStatusOrderByCreatedAtDesc(WorkflowVersionStatus.PUBLISHED))
                .thenReturn(List.of());
        when(modelConfigService.resolveRoutingModelCode(ArgumentMatchers.anyCollection()))
                .thenReturn("intent-router-v1");
        when(modelConfigService.buildRuntimeBundle(ArgumentMatchers.anyCollection(), ArgumentMatchers.eq("intent-router-v1")))
                .thenReturn(new ModelConfigService.RuntimeModelBundle(List.of(), List.of()));
        when(pythonClient.classifyIntent(ArgumentMatchers.anyMap()))
                .thenReturn(Mono.just(Map.of(
                        "workflow_code", "default",
                        "intent_code", "default",
                        "confidence", 0.5d
                )));
        return new WorkflowService(
                workflowRepository,
                workflowVersionRepository,
                objectMapper,
                accessControlService,
                auditService,
                pythonClient,
                modelConfigService
        );
    }
}
