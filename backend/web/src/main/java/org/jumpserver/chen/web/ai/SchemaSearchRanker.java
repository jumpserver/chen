package org.jumpserver.chen.web.ai;

import org.jumpserver.chen.framework.datasource.metadata.ColumnMetadata;
import org.jumpserver.chen.framework.datasource.metadata.ObjectRef;
import org.jumpserver.chen.framework.datasource.metadata.RelationMetadata;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** In-memory, metadata-only lexical ranking for the active schema. */
final class SchemaSearchRanker {
    private static final int MAX_QUERY_TERMS = 32;
    private static final int CONTEXT_TABLE_BOOST = 5;
    private static final int COVERAGE_BONUS = 6;
    private static final List<String> MATCH_SOURCES = List.of(
            "table_name", "table_comment", "column_name", "column_comment"
    );

    private SchemaSearchRanker() {
    }

    static boolean hasSearchTerms(String query) {
        return !queryTerms(query).isEmpty();
    }

    static List<RelationMetadata> selectColumnCandidates(
            List<RelationMetadata> relations, String query, String currentTable, int maximum
    ) {
        List<String> terms = queryTerms(query);
        List<ScoredRelation> scored = new ArrayList<>(relations.size());
        for (RelationMetadata relation : relations) {
            scored.add(new ScoredRelation(relation, relationScore(relation, terms, query, currentTable)));
        }
        List<RelationMetadata> selected = new ArrayList<>(scored.stream()
                .sorted(Comparator.comparingInt(ScoredRelation::score).reversed()
                        .thenComparing(item -> item.relation.ref().name(), String.CASE_INSENSITIVE_ORDER))
                .limit(maximum)
                .map(ScoredRelation::relation)
                .toList());
        RelationMetadata anchor = relations.stream()
                .filter(relation -> isCurrentTable(relation, currentTable)).findFirst().orElse(null);
        if (maximum > 0 && anchor != null && selected.stream().noneMatch(anchor::equals)) {
            selected.remove(selected.size() - 1);
            selected.add(anchor);
        }
        return List.copyOf(selected);
    }

    static RankedResults rank(
            List<RelationMetadata> relations,
            List<ColumnMetadata> columns,
            String query,
            String currentTable,
            int limit
    ) {
        List<String> terms = queryTerms(query);
        Map<ObjectRef, Candidate> byTable = new LinkedHashMap<>();
        for (RelationMetadata relation : relations) {
            Candidate candidate = new Candidate(relation, isCurrentTable(relation, currentTable));
            addRelationMatches(candidate, terms, query);
            byTable.put(relation.ref(), candidate);
        }
        for (ColumnMetadata column : columns) {
            Candidate candidate = byTable.get(column.owner());
            if (candidate == null) {
                continue;
            }
            for (String term : terms) {
                int nameScore = fieldScore(term, column.name(), 9, 7, 5);
                if (nameScore > 0) {
                    candidate.match(term, nameScore, "column_name");
                }
                int commentScore = fieldScore(term, column.comment(), 7, 6, 4);
                if (commentScore > 0) {
                    candidate.match(term, commentScore, "column_comment");
                }
            }
        }

        List<Candidate> ranked = byTable.values().stream()
                .filter(candidate -> candidate.score() > 0)
                .sorted(Comparator.comparingInt(Candidate::score).reversed()
                        .thenComparing(candidate -> candidate.relation.ref().name(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<Candidate> selected = new ArrayList<>(ranked.subList(0, Math.min(limit, ranked.size())));
        Candidate anchor = ranked.stream().filter(candidate -> candidate.currentTable).findFirst().orElse(null);
        if (limit > 1 && anchor != null && selected.stream().noneMatch(candidate -> candidate == anchor)) {
            selected.remove(selected.size() - 1);
            selected.add(anchor);
            selected.sort(Comparator.comparingInt(Candidate::score).reversed()
                    .thenComparing(candidate -> candidate.relation.ref().name(), String.CASE_INSENSITIVE_ORDER));
        }
        return new RankedResults(selected.stream().map(Candidate::serialize).toList(), ranked.size() > limit);
    }

    private static int relationScore(
            RelationMetadata relation, List<String> terms, String query, String currentTable
    ) {
        Candidate candidate = new Candidate(relation, isCurrentTable(relation, currentTable));
        addRelationMatches(candidate, terms, query);
        return candidate.score();
    }

    private static void addRelationMatches(Candidate candidate, List<String> terms, String query) {
        String exactQuery = normalize(query.trim());
        String table = candidate.relation.ref().name();
        String comment = candidate.relation.comment();
        if (normalize(table).equals(exactQuery)) {
            candidate.exactMatch(12, "table_name");
        } else if (comment != null && normalize(comment).equals(exactQuery)) {
            candidate.exactMatch(6, "table_comment");
        }
        for (String term : terms) {
            int nameScore = fieldScore(term, table, 18, 12, 8);
            if (nameScore > 0) {
                candidate.match(term, nameScore, "table_name");
            }
            int commentScore = fieldScore(term, comment, 12, 10, 7);
            if (commentScore > 0) {
                candidate.match(term, commentScore, "table_comment");
            }
        }
    }

    private static int fieldScore(String term, String value, int exact, int token, int contains) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        String normalized = normalize(value);
        if (normalized.equals(term)) {
            return exact;
        }
        if (fieldTokens(value).contains(term)) {
            return token;
        }
        return normalized.contains(term) ? contains : 0;
    }

    private static Set<String> fieldTokens(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : splitRuns(value)) {
            tokens.add(normalize(part));
        }
        return tokens;
    }

    /** Latin words split on separators/camel case; Han runs yield overlapping bigrams. */
    private static List<String> queryTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        for (String run : splitRuns(query)) {
            String normalized = normalize(run);
            if (normalized.isEmpty()) {
                continue;
            }
            if (Character.UnicodeScript.of(normalized.codePointAt(0)) == Character.UnicodeScript.HAN) {
                int[] points = normalized.codePoints().toArray();
                if (points.length == 1) {
                    terms.add(normalized);
                } else {
                    for (int index = 0; index < points.length - 1; index++) {
                        terms.add(new String(points, index, 2));
                    }
                }
            } else {
                terms.add(normalized);
            }
            if (terms.size() >= MAX_QUERY_TERMS) {
                break;
            }
        }
        return terms.stream().limit(MAX_QUERY_TERMS).toList();
    }

    private static List<String> splitRuns(String value) {
        String prepared = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ");
        List<String> runs = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        int previousKind = 0;
        for (int index = 0; index < prepared.length();) {
            int point = prepared.codePointAt(index);
            int kind = Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN
                    ? 2 : Character.isLetterOrDigit(point) ? 1 : 0;
            if (kind != previousKind && run.length() > 0) {
                runs.add(run.toString());
                run.setLength(0);
            }
            if (kind != 0) {
                run.appendCodePoint(point);
            }
            previousKind = kind;
            index += Character.charCount(point);
        }
        if (run.length() > 0) {
            runs.add(run.toString());
        }
        return runs;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }

    private static boolean isCurrentTable(RelationMetadata relation, String currentTable) {
        return currentTable != null && currentTable.equals(relation.ref().name());
    }

    record RankedResults(List<Map<String, Object>> candidates, boolean truncated) {
    }

    private record ScoredRelation(RelationMetadata relation, int score) {
    }

    private static final class Candidate {
        private final RelationMetadata relation;
        private final boolean currentTable;
        private final Map<String, Integer> matches = new LinkedHashMap<>();
        private final Set<String> matchedSources = new LinkedHashSet<>();
        private int exactQueryBonus;

        private Candidate(RelationMetadata relation, boolean currentTable) {
            this.relation = relation;
            this.currentTable = currentTable;
        }

        private void match(String term, int weight, String source) {
            matchedSources.add(source);
            matches.merge(term, weight, Math::max);
        }

        private void exactMatch(int weight, String source) {
            exactQueryBonus = weight;
            matchedSources.add(source);
        }

        private int score() {
            return matches.values().stream().mapToInt(Integer::intValue).sum()
                    + Math.max(0, matches.size() - 1) * COVERAGE_BONUS
                    + exactQueryBonus
                    + (currentTable ? CONTEXT_TABLE_BOOST : 0);
        }

        private Map<String, Object> serialize() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("table", relation.ref().name());
            result.put("score", score());
            result.put("matchedKeywords", matches.size());
            result.put("currentTable", currentTable);
            result.put("matchedOn", MATCH_SOURCES.stream().filter(matchedSources::contains).toList());
            return result;
        }
    }
}
