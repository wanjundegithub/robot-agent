package robot.agent.dto.request;

import java.util.ArrayList;
import java.util.List;

public class KnowledgeSearchRequest {
    private String query;
    private List<String> kbCodes = new ArrayList<>();
    private String retrievalMode = "hybrid";
    private Integer topK = 5;
    private Double scoreThreshold;
    private Boolean generateAnswer = true;

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public List<String> getKbCodes() { return kbCodes; }
    public void setKbCodes(List<String> kbCodes) { this.kbCodes = kbCodes == null ? new ArrayList<>() : kbCodes; }
    public String getRetrievalMode() { return retrievalMode; }
    public void setRetrievalMode(String retrievalMode) { this.retrievalMode = retrievalMode; }
    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }
    public Double getScoreThreshold() { return scoreThreshold; }
    public void setScoreThreshold(Double scoreThreshold) { this.scoreThreshold = scoreThreshold; }
    public Boolean getGenerateAnswer() { return generateAnswer; }
    public void setGenerateAnswer(Boolean generateAnswer) { this.generateAnswer = generateAnswer; }
}
