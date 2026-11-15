package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import java.util.*;

/** Versioned allowlist for comparison rows. Unknown provider payloads stay in the private archive. */
final class EvolutionDisclosure {
    private EvolutionDisclosure() {}

    private static final Set<String> FIELDS = Set.copyOf(Arrays.asList(("""
            identity id request graph snapshot repository context side scope kind family status disposition
            before after left right proof proofs subject basis authority trust tier confidence qualification
            origin canonicalName module path sourceSet language variant selected available applicable
            reason reasons reasonCode capability verdict allowedOperations withheldOperations operands operand
            evidence references evidenceReferences evidenceReference worldEvidence analysis
            document contentDigest digest artifact schema schemaVersion classification
            source target relationship relationshipKinds kinds sourceSpans sourceSpan location
            startLine endLine startColumn endColumn startOffset endOffset ordinal offset occurrenceCount
            antecedent support supportCount supportsTruncated supports occurrences multiplicity
            truth leftTruth rightTruth expectedTruth actualTruth feasibility feasible universeComplete identities state region
            nativeClassification commonClassification factKind encoding shape domainProof legalOrder order
            world trueWorlds falseWorlds unknownWorlds formula node operator children answer query limits
            transition continuity position from to parents complete provenance linkage earliestObserved
            declaredFrom declaredTo report reports rule ruleId ruleKey severity leftSeverity rightSeverity
            finding findingIdentity bundle bundleIdentity route vertices edges sources sourceCount
            projection population direction realization includeMust condition quantifier
            metric metrics definition definitionDigest observation value amount unit delta relativePercent
            exact numerator denominator denominatorChange rounding percentage confidence
            dimension dimensions root roots contributions score formula calibration model regionPolicy
            weight weightedScore penalty deduction count exactTotal total summary coverage
            evidenceTier replayTierChanged replayed valid reasoner witness witnessPolicyVersion worldReplay
            mode selection sourceSets nodeKinds conditional findings
            modelVersion semanticsVersion calibrationStatus penalties threshold baseline completeRules declaredRules
            completeMetrics requiredMetrics sufficient ruleResult reasonsAdded reasonsRemoved clean unknown
            semanticAbsenceProved sourcePartition targetPartition sourceContext targetContext
            input inputs gap gapIdentity mechanismCategory detectingProvider observationReferences
            acquisitionAttemptReferences affectedOutputs outcomes permissionDecision trustDecision
            selectedContext opposite dirty revision block present certain derived
            maxNodes maxClauses maxSteps maxDepth maxResults maxPaths maxVisitedVertices
            maxTraversedEdges maxAuxiliaryStates maxCells maxSolves workPolicy solver
            metadata capturedOperand publication size lineage projectedDocument projectedPath
            rightOnly leftOnly allowed withheld supportDigest locationDigest payloadDigest
            """).strip().split("\\s+")));
    // These containers can carry exact configuration values, producer text or arbitrary nested keys.
    private static final Set<String> SEALED = Set.of("assignment", "localChoices", "variable",
            "configuration", "parameters", "profile", "locator", "detail", "payload", "content",
            "obligation", "rootCause", "semanticPayload", "atom", "value");
    private static final Set<String> PATHS = Set.of("path", "projectedPath");
    private static final Set<String> HISTORY_PROOF_LINKS = Set.of("intervalProof", "previousAbsenceProof",
            "priorPresenceProof");

    static Map<String,Object> row(Map<String,Object> value) {
        var publicOperands = "spring.declared-yaml".equals(value.get("family")) ? declaration(value) : value;
        return GraphJson.object(project(publicOperands, "", 0));
    }

    private static Map<String,Object> declaration(Map<String,Object> row) {
        var copy = new TreeMap<>(row);
        // The private row identity binds the key. Remove private preimages before any public redaction digest is computed.
        copy.remove("identity");
        copy.remove("configurationKey");
        for (var side : List.of("left", "right")) {
            if (copy.get(side) instanceof Map<?,?> operand) {
                var publicOperand = new TreeMap<>(GraphJson.object(operand));
                publicOperand.remove("valueDigest");
                copy.put(side, publicOperand);
            }
        }
        return copy;
    }

    private static Object project(Object value, String field, int depth) {
        if (value == null) return null;
        if (depth >= 30 || SEALED.contains(field) && !numericValue(field,value)) return digest(value);
        if (HISTORY_PROOF_LINKS.contains(field))
            return value instanceof String id && id.matches("sha256:[0-9a-f]{64}") ? id : digest(value);
        if (value instanceof Map<?,?> map) {
            var out = new TreeMap<String,Object>();
            boolean filtered = false;
            for (var entry : map.entrySet()) {
                var key = (String)entry.getKey();
                if (FIELDS.contains(key) || SEALED.contains(key) || HISTORY_PROOF_LINKS.contains(key))
                    out.put(key, project(entry.getValue(), key, depth + 1));
                else filtered = true;
            }
            if (filtered) out.put("redactedOperand", digest(value));
            return out;
        }
        if (value instanceof List<?> values) return values.stream().map(child -> project(child, field, depth + 1)).toList();
        if (value instanceof String text) {
            if (field.equals("revision"))
                return text.matches("[0-9a-f]{40}|[0-9a-f]{64}") ? text : digest(text);
            if (PATHS.contains(field)) {
                try {
                    if (text.length() <= 512 && text.chars().noneMatch(Character::isISOControl))
                        return ContractChecks.modulePath(text);
                } catch (IllegalArgumentException ignored) { /* Nonportable source locator. */ }
                return digest(text);
            }
            // Only compact identifiers, enum values, digests and Java symbols enter the public view.
            if (text.length() > 512 || !text.matches("[A-Za-z0-9_.$:/@+<>()\\[\\],%#;=|~?-]+") ||
                    text.startsWith("/") || text.matches(".*(?:[A-Za-z]:/|://).*"))
                return digest(text);
        }
        return value;
    }

    private static boolean numericValue(String field, Object value) {
        if (!field.equals("value")) return false;
        if (value instanceof Number) return true;
        return value instanceof Map<?,?> metric && metric.keySet().equals(Set.of("amount","unit"))
                && metric.get("amount") instanceof Number && metric.get("unit") instanceof String unit
                && unit.matches("[a-z][a-z0-9-]{0,63}");
    }

    private static Map<String,Object> digest(Object value) {
        return Map.of("redacted", true, "operandDigest", CanonicalJson.sha256(value));
    }
}
