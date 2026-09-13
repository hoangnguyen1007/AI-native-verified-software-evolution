package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;

/** Explicit normalized input, never an implicit read of host environment or a Boot Config Data loader. */
public record ConfigurationEnvelope(Layer layer, List<Document> documents, List<DeclaredSource> declaredSources,
                                    ContentDigest acquisitionPolicyIdentity) {
    public enum Layer { REPOSITORY, DEPLOYMENT }
    public enum Availability { AVAILABLE, MISSING_OPTIONAL, MISSING_REQUIRED, FAILED_READ, DENIED_READ,
        UNRECOGNIZED_LOADER, UNOBSERVED_EXTERNAL }
    public enum SourceKind { DOCUMENT, ENVIRONMENT_DESCRIPTOR, SYSTEM_PROPERTY_DESCRIPTOR, COMMAND_LINE_DESCRIPTOR,
        TEST_DESCRIPTOR, CUSTOM_DESCRIPTOR }

    public record Document(ConditionEvidence evidence, Optional<ConditionExpression.Identity> activation,
                           List<ContentDigest> importAncestry, Availability availability) {
        public Document {
            Objects.requireNonNull(evidence); Objects.requireNonNull(activation); Objects.requireNonNull(availability);
            importAncestry = List.copyOf(importAncestry); // ordered ancestry; a repeated import is not silently erased
        }
    }
    public record DeclaredSource(String key, SourceKind kind, ConditionEvidence evidence,
                                 Optional<ConditionExpression.Identity> activation,
                                 List<ContentDigest> importAncestry, Availability availability,
                                 Map<FiniteDomain.Variable, List<FiniteDomain.Value>> values,
                                 VersionedIdentifier conversionPolicy) {
        public DeclaredSource {
            key = ConditionIdentitySupport.name(key); Objects.requireNonNull(kind); Objects.requireNonNull(evidence);
            Objects.requireNonNull(activation); Objects.requireNonNull(availability); Objects.requireNonNull(conversionPolicy);
            importAncestry = List.copyOf(importAncestry);
            Map<FiniteDomain.Variable, List<FiniteDomain.Value>> copy = new TreeMap<>();
            values.forEach((variable, domain) -> copy.put(variable, new FiniteDomain(variable, domain, evidence).values()));
            values = Collections.unmodifiableMap(copy);
        }
        public ContentDigest identity() { return ConditionIdentitySupport.digest(canonicalForm()); }
        public Object canonicalForm() {
            // Canonical JSON object keys are strings; typed variables are explicit rows instead.
            return Map.of("key", key, "kind", kind, "evidence", evidence, "activation", activation,
                    "importAncestry", importAncestry, "availability", availability,
                    "values", values.entrySet().stream().map(entry -> Map.of("variable", entry.getKey(), "values", entry.getValue())).toList(),
                    "conversionPolicy", conversionPolicy);
        }
    }
    public ConfigurationEnvelope {
        Objects.requireNonNull(layer); Objects.requireNonNull(acquisitionPolicyIdentity);
        documents = ContractChecks.distinctInOrder(documents, "envelope documents");
        declaredSources = List.copyOf(declaredSources);
        if (declaredSources.stream().map(DeclaredSource::key).distinct().count() != declaredSources.size()) {
            throw new IllegalArgumentException("Envelope source keys must be unique within a layer");
        }
    }
    public Identity identity() { return new Identity(ConditionIdentitySupport.derive("spring-envelope", canonicalForm())); }
    public Map<String, Object> canonicalForm() {
        return Map.of("layer", layer, "documents", documents,
                "declaredSources", declaredSources.stream().map(DeclaredSource::canonicalForm).toList(),
                "acquisitionPolicyIdentity", acquisitionPolicyIdentity);
    }
    public record Identity(String value) implements CanonicalIdentifier, Comparable<Identity> {
        public Identity { value = ConditionIdentitySupport.require(value, "spring-envelope"); }
        @Override public int compareTo(Identity other) { return value.compareTo(other.value); }
    }
}
