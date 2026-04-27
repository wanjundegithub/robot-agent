package robot.agent.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import robot.agent.model.CapabilityGroupSnapshot;
import robot.agent.model.CapabilityItem;
import robot.agent.model.CapabilityType;
import robot.agent.repository.CapabilityGroupRepository;
import robot.agent.repository.CapabilityGroupSnapshotRepository;
import robot.agent.repository.CapabilityItemRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityRuntimeResolverTest {

    @Test
    void resolveWorkflowDefinitionReplacesCapabilityToolWithPublishedApiConfig() {
        CapabilityItemRepository itemRepository = mock(CapabilityItemRepository.class);
        CapabilityGroupRepository groupRepository = mock(CapabilityGroupRepository.class);
        CapabilityGroupSnapshotRepository snapshotRepository = mock(CapabilityGroupSnapshotRepository.class);
        CapabilityService capabilityService = mock(CapabilityService.class);

        CapabilityItem item = new CapabilityItem();
        item.setGroupCode("payment_domain");
        item.setCapabilityCode("health_check");
        item.setCapabilityName("Health Check");
        item.setCapabilityType(CapabilityType.API);
        item.setPublishedVersion("v20260425205959");
        item.setDefinitionJson("""
                {
                  "url": "http://127.0.0.1:8080/actuator/health",
                  "method": "GET",
                  "headers": {
                    "Accept": "application/json"
                  }
                }
                """);

        when(capabilityService.resolveGroupCode(1L)).thenReturn("payment_domain");
        when(capabilityService.buildResolvedHeadersForCapability(1L, "health_check")).thenReturn(Map.of(
                "Accept", "application/json",
                "Authorization", "Bearer demo-token"
        ));
        when(itemRepository.findByGroupCodeAndCapabilityCode("payment_domain", "health_check"))
                .thenReturn(Optional.of(item));

        CapabilityRuntimeResolver resolver = new CapabilityRuntimeResolver(
                itemRepository,
                groupRepository,
                snapshotRepository,
                capabilityService
        );

        Map<String, Object> workflowDefinition = Map.of(
                "entry", "start",
                "nodes", Map.of(
                        "start", Map.of("id", "start", "type", "start", "config", Map.of("prompt", "Start")),
                        "call_health", Map.of(
                                "id", "call_health",
                                "type", "tool",
                                "config", Map.of(
                                        "invoke_type", "capability",
                                        "group_id", 1,
                                        "capability_code", "health_check",
                                        "payload_mapping", Map.of("userId", "execution.user_id")
                                )
                        )
                ),
                "transitions", Map.of("start", "call_health")
        );

        Map<String, Object> resolved = resolver.resolveWorkflowDefinition(workflowDefinition);
        @SuppressWarnings("unchecked")
        Map<String, Object> nodes = (Map<String, Object>) resolved.get("nodes");
        @SuppressWarnings("unchecked")
        Map<String, Object> toolNode = (Map<String, Object>) nodes.get("call_health");
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) toolNode.get("config");

        assertThat(config)
                .containsEntry("invoke_type", "api")
                .containsEntry("tool_code", "health_check")
                .containsEntry("group_id", 1L)
                .containsEntry("group_code", "payment_domain")
                .containsEntry("capability_code", "health_check")
                .containsEntry("capability_version", "v20260425205959")
                .containsEntry("url", "http://127.0.0.1:8080/actuator/health")
                .containsEntry("method", "GET");
        assertThat(config).doesNotContainKey("group_snapshot_version");
        assertThat(config).containsKey("payload_mapping");
        assertThat(config.get("headers")).isEqualTo(Map.of(
                "Accept", "application/json",
                "Authorization", "Bearer demo-token"
        ));
    }

    @Test
    void resolveWorkflowDefinitionRejectsUnpublishedCapability() {
        CapabilityItemRepository itemRepository = mock(CapabilityItemRepository.class);
        CapabilityGroupRepository groupRepository = mock(CapabilityGroupRepository.class);
        CapabilityGroupSnapshotRepository snapshotRepository = mock(CapabilityGroupSnapshotRepository.class);
        CapabilityService capabilityService = mock(CapabilityService.class);

        CapabilityItem item = new CapabilityItem();
        item.setGroupCode("payment_domain");
        item.setCapabilityCode("health_check");
        item.setCapabilityType(CapabilityType.API);
        item.setPublishedVersion(null);

        when(capabilityService.resolveGroupCode(1L)).thenReturn("payment_domain");
        when(itemRepository.findByGroupCodeAndCapabilityCode("payment_domain", "health_check"))
                .thenReturn(Optional.of(item));

        CapabilityRuntimeResolver resolver = new CapabilityRuntimeResolver(
                itemRepository,
                groupRepository,
                snapshotRepository,
                capabilityService
        );

        Map<String, Object> workflowDefinition = Map.of(
                "entry", "start",
                "nodes", Map.of(
                        "call_health", Map.of(
                                "id", "call_health",
                                "type", "tool",
                                "config", Map.of(
                                        "invoke_type", "capability",
                                        "group_id", 1,
                                        "capability_code", "health_check"
                                )
                        )
                )
        );

        assertThatThrownBy(() -> resolver.resolveWorkflowDefinition(workflowDefinition))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("尚未发布");
    }

    @Test
    void resolveWorkflowDefinitionTraversesGraphsAndResolvesCapabilityTools() {
        CapabilityItemRepository itemRepository = mock(CapabilityItemRepository.class);
        CapabilityGroupRepository groupRepository = mock(CapabilityGroupRepository.class);
        CapabilityGroupSnapshotRepository snapshotRepository = mock(CapabilityGroupSnapshotRepository.class);
        CapabilityService capabilityService = mock(CapabilityService.class);

        CapabilityItem mainItem = new CapabilityItem();
        mainItem.setGroupCode("travel_domain");
        mainItem.setCapabilityCode("search_flights");
        mainItem.setCapabilityName("Search Flights");
        mainItem.setCapabilityType(CapabilityType.API);
        mainItem.setPublishedVersion("v_main_1");
        mainItem.setDefinitionJson("""
                {
                  "url": "http://127.0.0.1:8080/api/flights/search",
                  "method": "POST"
                }
                """);

        CapabilityItem subItem = new CapabilityItem();
        subItem.setGroupCode("travel_domain");
        subItem.setCapabilityCode("book_flight");
        subItem.setCapabilityName("Book Flight");
        subItem.setCapabilityType(CapabilityType.API);
        subItem.setPublishedVersion("v_sub_1");
        subItem.setDefinitionJson("""
                {
                  "url": "http://127.0.0.1:8080/api/flights/book",
                  "method": "POST"
                }
                """);

        when(capabilityService.resolveGroupCode(1L)).thenReturn("travel_domain");
        when(capabilityService.buildResolvedHeadersForCapability(1L, "search_flights"))
                .thenReturn(Map.of("Authorization", "Bearer token-main"));
        when(capabilityService.buildResolvedHeadersForCapability(1L, "book_flight"))
                .thenReturn(Map.of("Authorization", "Bearer token-sub"));
        when(itemRepository.findByGroupCodeAndCapabilityCode("travel_domain", "search_flights"))
                .thenReturn(Optional.of(mainItem));
        when(itemRepository.findByGroupCodeAndCapabilityCode("travel_domain", "book_flight"))
                .thenReturn(Optional.of(subItem));

        CapabilityRuntimeResolver resolver = new CapabilityRuntimeResolver(
                itemRepository,
                groupRepository,
                snapshotRepository,
                capabilityService
        );

        Map<String, Object> workflowDefinition = Map.of(
                "schema_version", "workflow-designer/v2",
                "main_graph_id", "main",
                "graphs", Map.of(
                        "main", Map.of(
                                "graph_id", "main",
                                "graph_type", "main",
                                "entry_node_id", "start_main",
                                "nodes", Map.of(
                                        "start_main", Map.of("id", "start_main", "type", "start", "config", Map.of("prompt", "开始")),
                                        "tool_main", Map.of(
                                                "id", "tool_main",
                                                "type", "tool",
                                                "config", Map.of(
                                                        "invoke_type", "capability",
                                                        "group_id", 1,
                                                        "capability_code", "search_flights"
                                                )
                                        ),
                                        "sub_agent", Map.of(
                                                "id", "sub_agent",
                                                "type", "sub_agent",
                                                "config", Map.of("prompt", "子代理", "subgraph_id", "sub_booking")
                                        ),
                                        "end_main", Map.of("id", "end_main", "type", "end", "config", Map.of("prompt", "结束", "output_format", Map.of()))
                                ),
                                "edges", List.of(
                                        Map.of("id", "m1", "source", "start_main", "target", "tool_main"),
                                        Map.of("id", "m2", "source", "tool_main", "target", "sub_agent"),
                                        Map.of("id", "m3", "source", "sub_agent", "target", "end_main")
                                )
                        ),
                        "sub_booking", Map.of(
                                "graph_id", "sub_booking",
                                "graph_type", "subflow",
                                "entry_node_id", "start_sub",
                                "nodes", Map.of(
                                        "start_sub", Map.of("id", "start_sub", "type", "start", "config", Map.of("prompt", "子图开始")),
                                        "tool_sub", Map.of(
                                                "id", "tool_sub",
                                                "type", "tool",
                                                "config", Map.of(
                                                        "invoke_type", "capability",
                                                        "group_id", 1,
                                                        "capability_code", "book_flight"
                                                )
                                        ),
                                        "end_sub", Map.of("id", "end_sub", "type", "end", "config", Map.of("prompt", "子图结束", "output_format", Map.of()))
                                ),
                                "edges", List.of(
                                        Map.of("id", "s1", "source", "start_sub", "target", "tool_sub"),
                                        Map.of("id", "s2", "source", "tool_sub", "target", "end_sub")
                                )
                        )
                )
        );

        Map<String, Object> resolved = resolver.resolveWorkflowDefinition(workflowDefinition);
        @SuppressWarnings("unchecked")
        Map<String, Object> graphs = (Map<String, Object>) resolved.get("graphs");
        @SuppressWarnings("unchecked")
        Map<String, Object> mainGraph = (Map<String, Object>) graphs.get("main");
        @SuppressWarnings("unchecked")
        Map<String, Object> subGraph = (Map<String, Object>) graphs.get("sub_booking");
        @SuppressWarnings("unchecked")
        Map<String, Object> mainNodes = (Map<String, Object>) mainGraph.get("nodes");
        @SuppressWarnings("unchecked")
        Map<String, Object> subNodes = (Map<String, Object>) subGraph.get("nodes");
        @SuppressWarnings("unchecked")
        Map<String, Object> mainTool = (Map<String, Object>) mainNodes.get("tool_main");
        @SuppressWarnings("unchecked")
        Map<String, Object> subTool = (Map<String, Object>) subNodes.get("tool_sub");
        @SuppressWarnings("unchecked")
        Map<String, Object> mainConfig = (Map<String, Object>) mainTool.get("config");
        @SuppressWarnings("unchecked")
        Map<String, Object> subConfig = (Map<String, Object>) subTool.get("config");

        assertThat(mainConfig)
                .containsEntry("invoke_type", "api")
                .containsEntry("tool_code", "search_flights")
                .containsEntry("capability_version", "v_main_1")
                .containsEntry("url", "http://127.0.0.1:8080/api/flights/search");
        assertThat(mainConfig.get("headers")).isEqualTo(Map.of("Authorization", "Bearer token-main"));

        assertThat(subConfig)
                .containsEntry("invoke_type", "api")
                .containsEntry("tool_code", "book_flight")
                .containsEntry("capability_version", "v_sub_1")
                .containsEntry("url", "http://127.0.0.1:8080/api/flights/book");
        assertThat(subConfig.get("headers")).isEqualTo(Map.of("Authorization", "Bearer token-sub"));
    }

    @Test
    void resolveWorkflowDefinitionUsesSnapshotCapabilityVersion() {
        CapabilityItemRepository itemRepository = mock(CapabilityItemRepository.class);
        CapabilityGroupRepository groupRepository = mock(CapabilityGroupRepository.class);
        CapabilityGroupSnapshotRepository snapshotRepository = mock(CapabilityGroupSnapshotRepository.class);
        CapabilityService capabilityService = mock(CapabilityService.class);

        CapabilityGroupSnapshot snapshot = new CapabilityGroupSnapshot();
        snapshot.setGroupCode("travel_domain");
        snapshot.setSnapshotVersion("snapshot-20260426");
        snapshot.setSnapshotPayload("""
                {
                  "capabilities": [
                    {
                      "capabilityCode": "search_flights",
                      "capabilityType": "API",
                      "version": "cap-v1",
                      "definitionJson": "{\\"url\\":\\"http://127.0.0.1:8080/api/flights/search\\",\\"method\\":\\"POST\\"}"
                    }
                  ]
                }
                """);

        when(capabilityService.resolveGroupCode(1L)).thenReturn("travel_domain");
        when(capabilityService.buildResolvedHeadersForCapability(1L, "search_flights"))
                .thenReturn(Map.of("Authorization", "Bearer token-main"));
        when(snapshotRepository.findByGroupCodeAndSnapshotVersion("travel_domain", "snapshot-20260426"))
                .thenReturn(Optional.of(snapshot));

        CapabilityRuntimeResolver resolver = new CapabilityRuntimeResolver(
                itemRepository,
                groupRepository,
                snapshotRepository,
                capabilityService
        );

        Map<String, Object> workflowDefinition = Map.of(
                "entry", "start",
                "nodes", Map.of(
                        "call", Map.of(
                                "id", "call",
                                "type", "tool",
                                "config", Map.of(
                                        "invoke_type", "capability",
                                        "group_id", 1,
                                        "group_snapshot_version", "snapshot-20260426",
                                        "capability_code", "search_flights"
                                )
                        )
                )
        );

        Map<String, Object> resolved = resolver.resolveWorkflowDefinition(workflowDefinition);
        @SuppressWarnings("unchecked")
        Map<String, Object> nodes = (Map<String, Object>) resolved.get("nodes");
        @SuppressWarnings("unchecked")
        Map<String, Object> call = (Map<String, Object>) nodes.get("call");
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) call.get("config");

        assertThat(config).containsEntry("capability_version", "cap-v1");
        assertThat(config).containsEntry("group_snapshot_version", "snapshot-20260426");
    }

    @Test
    void resolveWorkflowDefinitionRejectsGroupIdAndGroupCodeMismatch() {
        CapabilityItemRepository itemRepository = mock(CapabilityItemRepository.class);
        CapabilityGroupRepository groupRepository = mock(CapabilityGroupRepository.class);
        CapabilityGroupSnapshotRepository snapshotRepository = mock(CapabilityGroupSnapshotRepository.class);
        CapabilityService capabilityService = mock(CapabilityService.class);

        when(capabilityService.resolveGroupCode(1L)).thenReturn("payment_domain");

        CapabilityRuntimeResolver resolver = new CapabilityRuntimeResolver(
                itemRepository,
                groupRepository,
                snapshotRepository,
                capabilityService
        );

        Map<String, Object> workflowDefinition = Map.of(
                "entry", "start",
                "nodes", Map.of(
                        "call", Map.of(
                                "id", "call",
                                "type", "tool",
                                "config", Map.of(
                                        "invoke_type", "capability",
                                        "group_id", 1,
                                        "group_code", "travel_domain",
                                        "capability_code", "search_flights"
                                )
                        )
                )
        );

        assertThatThrownBy(() -> resolver.resolveWorkflowDefinition(workflowDefinition))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("group_id 与 group_code 不一致");
    }
}
