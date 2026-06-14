package robot.agent.dto.response;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class KnowledgeSearchResponse {
    private String query;
    private List<DocumentHit> documents = new ArrayList<>();
    private String answer = "";
    private List<Citation> citations = new ArrayList<>();
    private Double bestScore = 0.0d;

    public static KnowledgeSearchResponse fromMap(Map<String, Object> value) {
        KnowledgeSearchResponse response = new KnowledgeSearchResponse();
        response.setQuery(stringValue(value.get("query")));
        response.setAnswer(stringValue(value.get("answer")));
        response.setBestScore(doubleValue(value.get("bestScore")));
        response.setDocuments(listOfMaps(value.get("documents")).stream().map(DocumentHit::fromMap).toList());
        response.setCitations(listOfMaps(value.get("citations")).stream().map(Citation::fromMap).toList());
        return response;
    }

    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(KnowledgeSearchResponse::mapOfObjects)
                .toList();
    }

    private static Map<String, Object> mapOfObjects(Object value) {
        Map<?, ?> source = (Map<?, ?>) value;
        java.util.LinkedHashMap<String, Object> target = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                target.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return target;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? 0.0d : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException exc) {
            return 0.0d;
        }
    }

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public List<DocumentHit> getDocuments() { return documents; }
    public void setDocuments(List<DocumentHit> documents) { this.documents = documents == null ? new ArrayList<>() : documents; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer == null ? "" : answer; }
    public List<Citation> getCitations() { return citations; }
    public void setCitations(List<Citation> citations) { this.citations = citations == null ? new ArrayList<>() : citations; }
    public Double getBestScore() { return bestScore; }
    public void setBestScore(Double bestScore) { this.bestScore = bestScore == null ? 0.0d : bestScore; }

    public static class DocumentHit {
        private String chunkId;
        private String docId;
        private String kbCode;
        private String title;
        private String content;
        private Double score = 0.0d;

        public static DocumentHit fromMap(Map<String, Object> value) {
            DocumentHit hit = new DocumentHit();
            hit.setChunkId(stringValue(value.getOrDefault("chunk_id", value.get("chunkId"))));
            hit.setDocId(stringValue(value.getOrDefault("doc_id", value.get("docId"))));
            hit.setKbCode(stringValue(value.getOrDefault("kb_code", value.get("kbCode"))));
            hit.setTitle(stringValue(value.get("title")));
            hit.setContent(stringValue(value.get("content")));
            hit.setScore(doubleValue(value.get("score")));
            return hit;
        }

        public String getChunkId() { return chunkId; }
        public void setChunkId(String chunkId) { this.chunkId = chunkId; }
        public String getDocId() { return docId; }
        public void setDocId(String docId) { this.docId = docId; }
        public String getKbCode() { return kbCode; }
        public void setKbCode(String kbCode) { this.kbCode = kbCode; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public Double getScore() { return score; }
        public void setScore(Double score) { this.score = score == null ? 0.0d : score; }
    }

    public static class Citation {
        private String chunkId;
        private String docId;
        private Double score = 0.0d;

        public static Citation fromMap(Map<String, Object> value) {
            Citation citation = new Citation();
            citation.setChunkId(stringValue(value.getOrDefault("chunkId", value.get("chunk_id"))));
            citation.setDocId(stringValue(value.getOrDefault("docId", value.get("doc_id"))));
            citation.setScore(doubleValue(value.get("score")));
            return citation;
        }

        public String getChunkId() { return chunkId; }
        public void setChunkId(String chunkId) { this.chunkId = chunkId; }
        public String getDocId() { return docId; }
        public void setDocId(String docId) { this.docId = docId; }
        public Double getScore() { return score; }
        public void setScore(Double score) { this.score = score == null ? 0.0d : score; }
    }
}
