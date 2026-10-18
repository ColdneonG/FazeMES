package cn.iocoder.yudao.module.mes.service.assistant;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;

/** Real lexical BM25 retrieval with Chinese bigrams; no embedding/vector claims or synthetic matches. */
@Component
public class MesAssistantRetriever {
    private static final Pattern WORDS = Pattern.compile("[\\p{IsHan}]+|[a-z0-9]+");
    public List<Map<String, Object>> rank(String query, List<Map<String, Object>> chunks, int limit) {
        Set<String> queryTerms = new HashSet<>(tokens(query));
        if (queryTerms.isEmpty()) return List.of();
        List<Integer> lengths = new ArrayList<>();
        Map<String, Integer> documentFrequency = new HashMap<>();
        double totalLength = 0;
        for (var chunk : chunks) {
            String searchable = chunk.get("title") + " " + chunk.get("heading") + " " + chunk.get("content");
            List<String> tokens = tokens(searchable);
            Set<String> matched = new HashSet<>();
            for (String token : tokens) if (queryTerms.contains(token)) matched.add(token);
            for (String term : matched) documentFrequency.merge(term, 1, Integer::sum);
            lengths.add(tokens.size()); totalLength += tokens.size();
        }
        double averageLength = chunks.isEmpty() ? 1 : Math.max(1, totalLength / chunks.size());
        Comparator<Map<String, Object>> bestFirst = Comparator.<Map<String, Object>>comparingDouble(row -> -((Number) row.get("score")).doubleValue())
                .thenComparingLong(row -> ((Number) row.get("chunkId")).longValue());
        PriorityQueue<Map<String, Object>> best = new PriorityQueue<>(bestFirst.reversed());
        for (int index = 0; index < chunks.size(); index++) {
            var chunk = chunks.get(index);
            Map<String, Integer> frequency = new HashMap<>();
            for (String token : tokens(chunk.get("title") + " " + chunk.get("heading") + " " + chunk.get("content")))
                if (queryTerms.contains(token)) frequency.merge(token, 1, Integer::sum);
            int length = lengths.get(index);
            double score = 0;
            for (String term : queryTerms) {
                int tf = frequency.getOrDefault(term, 0), df = documentFrequency.getOrDefault(term, 0);
                if (tf == 0) continue;
                double idf = Math.log(1 + (chunks.size() - df + .5) / (df + .5));
                score += idf * tf * 2.2 / (tf + 1.2 * (.25 + .75 * length / averageLength));
            }
            if (score > 0) {
                Map<String, Object> citation = new LinkedHashMap<>(chunks.get(index));
                citation.put("score", Math.round(score * 10000) / 10000.0);
                best.add(citation);
                if (best.size() > limit) best.poll();
            }
        }
        return best.stream().sorted(bestFirst).toList();
    }

    private static List<String> tokens(String text) {
        List<String> result = new ArrayList<>();
        var matcher = WORDS.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            if (Character.UnicodeScript.of(word.codePointAt(0)) == Character.UnicodeScript.HAN && word.length() > 1) {
                for (int index = 0; index < word.length() - 1; index++) result.add(word.substring(index, index + 2));
            } else result.add(word);
        }
        return result;
    }
}
