package com.debateai.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Stateless semantic similarity service based on TF-IDF vectors and cosine similarity.
 * The implementation is optimized for small document collections (3-10 documents).
 */
@Service
public class TextSimilarityService {

    private static final Pattern NON_ALPHANUMERIC_PATTERN = Pattern.compile("[^a-z0-9\\s]");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    /**
     * Builds TF-IDF vectors for the provided labeled documents, computes all pairwise cosine
     * similarities, and returns the average pairwise similarity.
     *
     * @param labeledDocuments map of label -> document text
     * @return pairwise similarities and average similarity
     */
    public SimilarityAnalysis analyzePairwiseSimilarity(Map<String, String> labeledDocuments) {
        if (labeledDocuments == null || labeledDocuments.size() < 2) {
            return new SimilarityAnalysis(List.of(), 0.0d);
        }

        Map<String, List<String>> tokenizedDocuments = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : labeledDocuments.entrySet()) {
            tokenizedDocuments.put(entry.getKey(), tokenize(entry.getValue()));
        }

        Map<String, Map<String, Double>> termFrequencyByLabel = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : tokenizedDocuments.entrySet()) {
            termFrequencyByLabel.put(entry.getKey(), computeTermFrequency(entry.getValue()));
        }

        Map<String, Double> inverseDocumentFrequency = computeInverseDocumentFrequency(tokenizedDocuments.values());
        Map<String, Map<String, Double>> tfIdfVectors = buildTfIdfVectors(termFrequencyByLabel, inverseDocumentFrequency);

        List<String> labels = new ArrayList<>(labeledDocuments.keySet());
        List<PairwiseSimilarity> pairwiseSimilarities = new ArrayList<>();
        double totalSimilarity = 0.0d;
        int pairCount = 0;

        for (int i = 0; i < labels.size(); i++) {
            for (int j = i + 1; j < labels.size(); j++) {
                String leftLabel = labels.get(i);
                String rightLabel = labels.get(j);
                double similarity = cosineSimilarity(
                        tfIdfVectors.getOrDefault(leftLabel, Map.of()),
                        tfIdfVectors.getOrDefault(rightLabel, Map.of())
                );
                pairwiseSimilarities.add(new PairwiseSimilarity(leftLabel, rightLabel, similarity));
                totalSimilarity += similarity;
                pairCount++;
            }
        }

        double averageSimilarity = pairCount == 0 ? 0.0d : totalSimilarity / pairCount;
        return new SimilarityAnalysis(List.copyOf(pairwiseSimilarities), clamp(averageSimilarity, 0.0d, 1.0d));
    }

    /**
     * Tokenizes text by lowercasing, removing punctuation, and filtering short tokens.
     *
     * @param text raw input text
     * @return cleaned tokens with minimum length of 3
     */
    public List<String> tokenize(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        String normalized = NON_ALPHANUMERIC_PATTERN.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ");
        String[] rawTokens = WHITESPACE_PATTERN.split(normalized.trim());
        List<String> tokens = new ArrayList<>(rawTokens.length);
        for (String token : rawTokens) {
            if (token.length() >= 3) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /**
     * Computes normalized term frequency for a single document.
     *
     * @param tokens cleaned tokens
     * @return TF map where each value is count(term)/tokenCount
     */
    public Map<String, Double> computeTermFrequency(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> counts = new HashMap<>();
        for (String token : tokens) {
            counts.merge(token, 1, Integer::sum);
        }

        double tokenCount = tokens.size();
        Map<String, Double> tf = new HashMap<>(counts.size());
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            tf.put(entry.getKey(), entry.getValue() / tokenCount);
        }
        return Map.copyOf(tf);
    }

    /**
     * Computes IDF with the formula: log(N / (1 + docCount)).
     *
     * @param tokenizedDocuments tokenized documents
     * @return IDF map for vocabulary terms
     */
    public Map<String, Double> computeInverseDocumentFrequency(Collection<List<String>> tokenizedDocuments) {
        if (tokenizedDocuments == null || tokenizedDocuments.isEmpty()) {
            return Map.of();
        }

        int documentCount = tokenizedDocuments.size();
        Map<String, Integer> documentFrequency = new HashMap<>();

        for (List<String> tokens : tokenizedDocuments) {
            Set<String> uniqueTerms = new HashSet<>(tokens);
            for (String term : uniqueTerms) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }

        Map<String, Double> idf = new HashMap<>(documentFrequency.size());
        for (Map.Entry<String, Integer> entry : documentFrequency.entrySet()) {
            double value = Math.log(documentCount / (1.0d + entry.getValue()));
            idf.put(entry.getKey(), value);
        }
        return Map.copyOf(idf);
    }

    /**
     * Computes cosine similarity between two sparse TF-IDF vectors.
     *
     * @param leftVector left TF-IDF vector
     * @param rightVector right TF-IDF vector
     * @return cosine similarity, clamped to [0, 1]
     */
    public double cosineSimilarity(Map<String, Double> leftVector, Map<String, Double> rightVector) {
        if (leftVector == null || rightVector == null || leftVector.isEmpty() || rightVector.isEmpty()) {
            return 0.0d;
        }

        Map<String, Double> smaller = leftVector.size() <= rightVector.size() ? leftVector : rightVector;
        Map<String, Double> larger = smaller == leftVector ? rightVector : leftVector;

        double dotProduct = 0.0d;
        for (Map.Entry<String, Double> entry : smaller.entrySet()) {
            double rightValue = larger.getOrDefault(entry.getKey(), 0.0d);
            dotProduct += entry.getValue() * rightValue;
        }

        double leftNorm = vectorNorm(leftVector);
        double rightNorm = vectorNorm(rightVector);
        if (leftNorm == 0.0d || rightNorm == 0.0d) {
            return 0.0d;
        }

        double cosine = dotProduct / (leftNorm * rightNorm);
        return clamp(cosine, 0.0d, 1.0d);
    }

    private Map<String, Map<String, Double>> buildTfIdfVectors(Map<String, Map<String, Double>> termFrequencyByLabel,
                                                                Map<String, Double> inverseDocumentFrequency) {
        Map<String, Map<String, Double>> vectors = new LinkedHashMap<>();

        for (Map.Entry<String, Map<String, Double>> entry : termFrequencyByLabel.entrySet()) {
            Map<String, Double> vector = new HashMap<>();
            for (Map.Entry<String, Double> tfEntry : entry.getValue().entrySet()) {
                double idf = inverseDocumentFrequency.getOrDefault(tfEntry.getKey(), 0.0d);
                double tfIdf = tfEntry.getValue() * idf;
                if (tfIdf != 0.0d) {
                    vector.put(tfEntry.getKey(), tfIdf);
                }
            }
            vectors.put(entry.getKey(), Map.copyOf(vector));
        }

        return Map.copyOf(vectors);
    }

    private double vectorNorm(Map<String, Double> vector) {
        double sumSquares = 0.0d;
        for (double value : vector.values()) {
            sumSquares += value * value;
        }
        return Math.sqrt(sumSquares);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public record PairwiseSimilarity(String leftLabel, String rightLabel, double similarity) {
    }

    public record SimilarityAnalysis(List<PairwiseSimilarity> pairwiseSimilarities, double averageSimilarity) {
        public double similarityBetween(String leftLabel, String rightLabel) {
            for (PairwiseSimilarity pairwise : pairwiseSimilarities) {
                boolean directMatch = pairwise.leftLabel().equals(leftLabel) && pairwise.rightLabel().equals(rightLabel);
                boolean reverseMatch = pairwise.leftLabel().equals(rightLabel) && pairwise.rightLabel().equals(leftLabel);
                if (directMatch || reverseMatch) {
                    return pairwise.similarity();
                }
            }
            return 0.0d;
        }
    }
}
