package robot.agent.dto.response;

import java.util.ArrayList;
import java.util.List;

public class KnowledgeSearchStreamEvent {
    private String type;
    private String query;
    private List<String> kbCodes = new ArrayList<>();
    private Integer firstFrameDeadlineMs;
    private Long elapsedMs;
    private KnowledgeSearchResponse result;
    private String message;
    private String content;
    private Integer deltaIndex;

    public static KnowledgeSearchStreamEvent started(String query, List<String> kbCodes, int firstFrameDeadlineMs, String content) {
        KnowledgeSearchStreamEvent event = new KnowledgeSearchStreamEvent();
        event.setType("started");
        event.setQuery(query);
        event.setKbCodes(kbCodes);
        event.setFirstFrameDeadlineMs(firstFrameDeadlineMs);
        event.setElapsedMs(0L);
        event.setContent(content);
        return event;
    }

    public static KnowledgeSearchStreamEvent delta(String content, int deltaIndex, long elapsedMs) {
        KnowledgeSearchStreamEvent event = new KnowledgeSearchStreamEvent();
        event.setType("delta");
        event.setContent(content);
        event.setDeltaIndex(deltaIndex);
        event.setElapsedMs(elapsedMs);
        return event;
    }

    public static KnowledgeSearchStreamEvent completed(KnowledgeSearchResponse result, long elapsedMs) {
        KnowledgeSearchStreamEvent event = new KnowledgeSearchStreamEvent();
        event.setType("completed");
        event.setQuery(result == null ? null : result.getQuery());
        event.setResult(result);
        event.setElapsedMs(elapsedMs);
        return event;
    }

    public static KnowledgeSearchStreamEvent failed(String message, long elapsedMs) {
        KnowledgeSearchStreamEvent event = new KnowledgeSearchStreamEvent();
        event.setType("failed");
        event.setMessage(message);
        event.setElapsedMs(elapsedMs);
        return event;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public List<String> getKbCodes() { return kbCodes; }
    public void setKbCodes(List<String> kbCodes) { this.kbCodes = kbCodes == null ? new ArrayList<>() : kbCodes; }
    public Integer getFirstFrameDeadlineMs() { return firstFrameDeadlineMs; }
    public void setFirstFrameDeadlineMs(Integer firstFrameDeadlineMs) { this.firstFrameDeadlineMs = firstFrameDeadlineMs; }
    public Long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(Long elapsedMs) { this.elapsedMs = elapsedMs; }
    public KnowledgeSearchResponse getResult() { return result; }
    public void setResult(KnowledgeSearchResponse result) { this.result = result; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content == null ? "" : content; }
    public Integer getDeltaIndex() { return deltaIndex; }
    public void setDeltaIndex(Integer deltaIndex) { this.deltaIndex = deltaIndex; }
}
