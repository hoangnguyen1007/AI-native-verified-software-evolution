package com.evolution.analysis.spring;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.Diagnostic;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SourceDocument;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.evidence.EvidenceSubject;
import com.evolution.analysis.frontend.DeclarationRecord;
import com.evolution.analysis.frontend.SourceInput;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Closed, evidence-only raw-observation and semantic-obligation inventory for one M2 analysis. */
public record SpringMechanismInventory(
        ContentDigest identity,
        String schemaVersion,
        AnalysisIdentity analysis,
        VersionedIdentifier provider,
        VersionedIdentifier mechanismCatalog,
        String catalogRevision,
        ContentDigest frontendResultIdentity,
        SpringFrameworkEvidence frameworkEvidence,
        List<ResourceEvidence> resourceEvidence,
        List<AnnotationDeclarationEvidence> annotationDeclarations,
        List<AnnotationMetaEdge> annotationMetaEdges,
        List<AnnotationCycle> annotationCycles,
        List<RawObservation> rawObservations,
        List<SemanticObligation> obligations,
        List<ContentDigest> markerObservations,
        Coverage coverage,
        List<Problem> problems,
        List<String> limitations) {
    public static final String SCHEMA = "spring-mechanism-inventory-v2";
    public static final VersionedIdentifier PROVIDER =
            new VersionedIdentifier("spring.mechanism-scanner", "m4a.2");
    public static final List<String> LIMITATIONS = List.of(
            "M4A.2 inventories evidence and obligations only; it does not establish activation, registration, binding, runtime instantiation or truth regions.",
            "Annotation composition follows only exact resolved declaration edges; missing declaration bytes, ambiguous aliases, repeatable containers and cycles remain explicit graph evidence or gaps.",
            "The XML denominator contains each supplied document, start element, attribute and document-type declaration; comments, text and closing tags are not separate mechanism occurrences.",
            "Callable/type classifiers identify exact evidence-bound mechanism candidates; dynamic registry effects, container lookup results, generated members, factory products and Spring Data registration remain unresolved semantics.");

    public SpringMechanismInventory {
        ContractChecks.notNull(identity, "Spring inventory identity");
        if (!SCHEMA.equals(schemaVersion)) throw new IllegalArgumentException("Unsupported Spring inventory schema");
        ContractChecks.notNull(analysis, "Spring inventory analysis");
        ContractChecks.notNull(provider, "Spring inventory provider");
        if (!PROVIDER.equals(provider)) throw new IllegalArgumentException("Unsupported Spring inventory provider");
        if (!SpringMechanismCatalog.CATALOG.equals(mechanismCatalog)) {
            throw new IllegalArgumentException("Unsupported Spring mechanism catalog");
        }
        if (!SpringMechanismCatalog.REVISION.equals(catalogRevision)) {
            throw new IllegalArgumentException("Unsupported Spring mechanism catalog revision");
        }
        ContractChecks.notNull(frontendResultIdentity, "frontend result identity");
        ContractChecks.notNull(frameworkEvidence, "Spring framework evidence");
        resourceEvidence = ContractChecks.sortedDistinct(
                resourceEvidence, Comparator.naturalOrder(), "Spring resource evidence");
        if (resourceEvidence.stream().map(value -> value.document().identity()).distinct().count()
                != resourceEvidence.size()) {
            throw new IllegalArgumentException("Spring resource evidence contains duplicate documents");
        }
        annotationDeclarations = ContractChecks.sortedDistinct(annotationDeclarations,
                Comparator.naturalOrder(), "Spring annotation declarations");
        annotationMetaEdges = ContractChecks.sortedDistinct(annotationMetaEdges,
                Comparator.naturalOrder(), "Spring annotation meta edges");
        annotationCycles = ContractChecks.sortedDistinct(annotationCycles,
                Comparator.naturalOrder(), "Spring annotation cycles");
        rawObservations = ContractChecks.sortedDistinct(
                rawObservations, Comparator.naturalOrder(), "Spring raw observations");
        obligations = ContractChecks.sortedDistinct(
                obligations, Comparator.naturalOrder(), "Spring semantic obligations");
        markerObservations = ContractChecks.sortedDistinct(
                markerObservations, Comparator.naturalOrder(), "Spring marker observations");
        ContractChecks.notNull(coverage, "Spring inventory coverage");
        problems = ContractChecks.sortedDistinct(
                problems, Comparator.comparing(CanonicalJson::write), "Spring inventory problems");
        limitations = ContractChecks.sortedStrings(limitations, "Spring inventory limitations");
        validateClosure(resourceEvidence, annotationDeclarations, annotationMetaEdges, annotationCycles,
                rawObservations, obligations, markerObservations, coverage, problems);
        ContentDigest expected = derive(analysis, provider, mechanismCatalog, catalogRevision,
                frontendResultIdentity, frameworkEvidence, resourceEvidence, annotationDeclarations,
                annotationMetaEdges, annotationCycles, rawObservations, obligations, markerObservations,
                coverage, problems, limitations);
        if (!identity.equals(expected)) throw new IllegalArgumentException("Spring inventory identity does not match contents");
    }

    static SpringMechanismInventory create(AnalysisIdentity analysis, ContentDigest frontendResultIdentity,
            SpringFrameworkEvidence frameworkEvidence, Collection<ResourceEvidence> resourceEvidence,
            Collection<AnnotationDeclarationEvidence> annotationDeclarations,
            Collection<AnnotationMetaEdge> annotationMetaEdges, Collection<AnnotationCycle> annotationCycles,
            Collection<RawObservation> rawObservations,
            Collection<SemanticObligation> obligations, Collection<ContentDigest> markerObservations,
            Collection<Problem> problems) {
        List<RawObservation> raw = rawObservations.stream().sorted().toList();
        List<ResourceEvidence> resources = resourceEvidence.stream().sorted().toList();
        List<AnnotationDeclarationEvidence> declarations = annotationDeclarations.stream().sorted().toList();
        List<AnnotationMetaEdge> edges = annotationMetaEdges.stream().sorted().toList();
        List<AnnotationCycle> cycles = annotationCycles.stream().sorted().toList();
        List<SemanticObligation> semantic = obligations.stream().sorted().toList();
        List<ContentDigest> markers = markerObservations.stream().sorted().toList();
        List<Problem> sortedProblems = problems.stream()
                .sorted(Comparator.comparing(CanonicalJson::write)).toList();
        long unclassified = semantic.stream()
                .filter(value -> value.classification() == Classification.UNCLASSIFIED)
                .map(SemanticObligation::rawObservationIdentity).distinct().count();
        Coverage coverage = new Coverage(raw.size(), semantic.size(), markers.size(), unclassified,
                declarations.size(), edges.size(), cycles.size());
        List<String> limitations = LIMITATIONS.stream().sorted().toList();
        ContentDigest identity = derive(analysis, PROVIDER, SpringMechanismCatalog.CATALOG,
                SpringMechanismCatalog.REVISION, frontendResultIdentity, frameworkEvidence,
                resources, declarations, edges, cycles, raw, semantic, markers, coverage,
                sortedProblems, limitations);
        return new SpringMechanismInventory(identity, SCHEMA, analysis, PROVIDER,
                SpringMechanismCatalog.CATALOG, SpringMechanismCatalog.REVISION,
                frontendResultIdentity, frameworkEvidence, resources, declarations, edges, cycles,
                raw, semantic, markers, coverage, sortedProblems, limitations);
    }

    private static ContentDigest derive(AnalysisIdentity analysis, VersionedIdentifier provider,
            VersionedIdentifier catalog, String revision, ContentDigest frontendResultIdentity,
            SpringFrameworkEvidence frameworkEvidence, List<ResourceEvidence> resources,
            List<AnnotationDeclarationEvidence> annotationDeclarations,
            List<AnnotationMetaEdge> annotationMetaEdges, List<AnnotationCycle> annotationCycles,
            List<RawObservation> raw,
            List<SemanticObligation> obligations, List<ContentDigest> markers,
            Coverage coverage, List<Problem> problems, List<String> limitations) {
        return ContentDigest.sha256Utf8(CanonicalJson.write(Map.ofEntries(
                Map.entry("schema", SCHEMA), Map.entry("analysis", analysis),
                Map.entry("provider", provider), Map.entry("mechanismCatalog", catalog),
                Map.entry("catalogRevision", revision), Map.entry("frontendResult", frontendResultIdentity),
                Map.entry("frameworkEvidence", frameworkEvidence), Map.entry("resourceEvidence", resources),
                Map.entry("annotationDeclarations", annotationDeclarations),
                Map.entry("annotationMetaEdges", annotationMetaEdges),
                Map.entry("annotationCycles", annotationCycles),
                Map.entry("rawObservations", raw),
                Map.entry("obligations", obligations), Map.entry("markerObservations", markers),
                Map.entry("coverage", coverage), Map.entry("problems", problems),
                Map.entry("limitations", limitations))));
    }

    private static void validateClosure(List<ResourceEvidence> resources,
            List<AnnotationDeclarationEvidence> annotationDeclarations,
            List<AnnotationMetaEdge> annotationMetaEdges, List<AnnotationCycle> annotationCycles,
            List<RawObservation> raw, List<SemanticObligation> obligations,
            List<ContentDigest> markers, Coverage coverage, List<Problem> problems) {
        Map<ContentDigest, RawObservation> byIdentity = raw.stream()
                .collect(Collectors.toMap(RawObservation::identity, Function.identity()));
        Map<EntityIdentity, AnnotationDeclarationEvidence> annotationByIdentity = annotationDeclarations.stream()
                .collect(Collectors.toMap(AnnotationDeclarationEvidence::declaration, Function.identity()));
        Map<ContentDigest, AnnotationMetaEdge> edgeByIdentity = annotationMetaEdges.stream()
                .collect(Collectors.toMap(AnnotationMetaEdge::identity, Function.identity()));
        for (AnnotationDeclarationEvidence declaration : annotationDeclarations) {
            declaration.rawDeclarationObservationIdentity().ifPresent(reference -> {
                RawObservation observation = byIdentity.get(reference);
                if (observation == null || observation.kind() != RawKind.ANNOTATION_DECLARATION) {
                    throw new IllegalArgumentException("Annotation declaration references missing raw evidence");
                }
            });
            Set<ContentDigest> outgoing = annotationMetaEdges.stream()
                    .filter(edge -> edge.declaration().equals(declaration.declaration()))
                    .map(AnnotationMetaEdge::rawObservationIdentity).collect(Collectors.toSet());
            if (!outgoing.equals(Set.copyOf(declaration.metaAnnotationObservationIdentities()))) {
                throw new IllegalArgumentException("Annotation declaration and meta-edge evidence disagree");
            }
            for (AnnotationAttributeEvidence attribute : declaration.attributes()) {
                if (attribute.aliasObservationIdentities().stream().anyMatch(reference -> {
                    RawObservation observation = byIdentity.get(reference);
                    return observation == null || observation.kind() != RawKind.ANNOTATION_USE;
                })) throw new IllegalArgumentException("Annotation alias references missing raw evidence");
            }
        }
        for (AnnotationMetaEdge edge : annotationMetaEdges) {
            RawObservation observation = byIdentity.get(edge.rawObservationIdentity());
            if (!annotationByIdentity.containsKey(edge.declaration()) || observation == null
                    || observation.kind() != RawKind.ANNOTATION_USE) {
                throw new IllegalArgumentException("Annotation meta edge references missing evidence");
            }
        }
        for (AnnotationCycle cycle : annotationCycles) {
            if (!annotationByIdentity.keySet().containsAll(cycle.declarations())
                    || !edgeByIdentity.keySet().containsAll(cycle.edgeIdentities())) {
                throw new IllegalArgumentException("Annotation cycle references missing graph evidence");
            }
        }
        Set<SourceDocumentIdentity> suppliedResources = resources.stream()
                .map(value -> value.document().identity()).collect(Collectors.toSet());
        Set<SourceDocumentIdentity> observedResources = raw.stream()
                .filter(value -> switch (value.kind()) {
                    case XML_DOCUMENT, XML_ELEMENT, XML_ATTRIBUTE, XML_DOCUMENT_TYPE,
                            METADATA_DOCUMENT, METADATA_ENTRY -> true;
                    default -> false;
                }).map(RawObservation::document).collect(Collectors.toSet());
        if (!suppliedResources.equals(observedResources)) {
            throw new IllegalArgumentException("Every supplied Spring resource needs a raw-document denominator row");
        }
        Set<ContentDigest> markerSet = Set.copyOf(markers);
        Set<ContentDigest> obligationRows = obligations.stream()
                .map(SemanticObligation::rawObservationIdentity).collect(Collectors.toSet());
        if (!Collections.disjoint(markerSet, obligationRows)) {
            throw new IllegalArgumentException("A raw observation cannot be both marker and semantic obligation");
        }
        HashSet<ContentDigest> accounted = new HashSet<>(markerSet);
        accounted.addAll(obligationRows);
        if (!accounted.equals(byIdentity.keySet())) {
            throw new IllegalArgumentException("Every raw Spring observation must be accounted exactly as marker or obligation");
        }
        if (obligations.stream().anyMatch(value -> !byIdentity.containsKey(value.rawObservationIdentity()))) {
            throw new IllegalArgumentException("Spring obligation references an unknown raw observation");
        }
        for (SemanticObligation obligation : obligations) {
            SpringMechanismCatalog.require(obligation.primaryMechanism());
            boolean catchAll = obligation.primaryMechanism().equals("spring.mechanism.unclassified");
            if (catchAll != (obligation.classification() == Classification.UNCLASSIFIED)) {
                throw new IllegalArgumentException("Spring catch-all and obligation classification disagree");
            }
        }
        Set<ContentDigest> problemRows = problems.stream().flatMap(problem -> problem.rawObservationIdentity().stream())
                .collect(Collectors.toSet());
        if (!byIdentity.keySet().containsAll(problemRows)) {
            throw new IllegalArgumentException("Spring problem references an unknown raw observation");
        }
        Set<ContentDigest> unclassifiedRows = obligations.stream()
                .filter(value -> value.classification() == Classification.UNCLASSIFIED)
                .map(SemanticObligation::rawObservationIdentity).collect(Collectors.toSet());
        Set<ContentDigest> explainedUnclassifiedRows = problems.stream()
                .filter(problem -> problem.reason() == ProblemReason.UNCLASSIFIED_MECHANISM
                        || problem.reason() == ProblemReason.ANNOTATION_SEMANTICS_NOT_ADJUDICATED
                        || problem.reason() == ProblemReason.ANNOTATION_GRAPH_INCOMPLETE)
                .flatMap(problem -> problem.rawObservationIdentity().stream()).collect(Collectors.toSet());
        if (!explainedUnclassifiedRows.containsAll(unclassifiedRows)) {
            throw new IllegalArgumentException("Every unclassified observation needs a typed explanation");
        }
        Coverage expected = new Coverage(raw.size(), obligations.size(), markers.size(),
                unclassifiedRows.size(), annotationDeclarations.size(), annotationMetaEdges.size(),
                annotationCycles.size());
        if (!coverage.equals(expected)) throw new IllegalArgumentException("Spring inventory coverage does not reconcile");
    }

    public enum RawKind {
        ANNOTATION_USE, ANNOTATION_DECLARATION, XML_DOCUMENT, XML_ELEMENT, XML_ATTRIBUTE,
        XML_DOCUMENT_TYPE, METADATA_DOCUMENT, METADATA_ENTRY, JAVA_DECLARATION,
        JAVA_RELATIONSHIP, JAVA_TYPE_USE
    }
    public enum EvidenceState { VERIFIED, PARTIAL, UNRESOLVED, AMBIGUOUS, UNSUPPORTED, ERROR }
    public enum Classification { CLASSIFIED, UNCLASSIFIED }
    public enum ResourceKind { XML, METADATA }
    /** Accepted M4-R0 reason subset emitted by this slice; no scanner-private reason leaks into gaps. */
    public enum ProblemReason {
        UNCLASSIFIED_MECHANISM,
        ANNOTATION_SEMANTICS_NOT_ADJUDICATED,
        VERSION_FRAGMENT_NOT_VALIDATED,
        ANNOTATION_GRAPH_INCOMPLETE,
        CONSTRUCTOR_SET_INCOMPLETE,
        GENERATED_MEMBER_NOT_ACQUIRED,
        PARAMETER_EVIDENCE_INCOMPLETE,
        AGGREGATE_OR_PROVIDER_UNMODELED,
        FACTORY_PRODUCT_TYPE_UNKNOWN,
        DYNAMIC_REGISTRY_MUTATION,
        DYNAMIC_LOOKUP_TARGET,
        REPOSITORY_REGISTRATION_UNPROVED,
        ENTRYPOINT_OR_LIFECYCLE_UNMODELED
    }

    public record AnnotationAttributeEvidence(ContentDigest identity, EntityIdentity declaration,
            Optional<SourceSpan> span, ContentDigest declarationDigest, boolean defaultDeclared,
            List<ContentDigest> aliasObservationIdentities) implements Comparable<AnnotationAttributeEvidence> {
        public AnnotationAttributeEvidence {
            ContractChecks.notNull(identity, "annotation attribute identity");
            ContractChecks.notNull(declaration, "annotation attribute declaration");
            span = ContractChecks.notNull(span, "annotation attribute span");
            ContractChecks.notNull(declarationDigest, "annotation attribute declaration digest");
            aliasObservationIdentities = ContractChecks.sortedDistinct(aliasObservationIdentities,
                    Comparator.naturalOrder(), "annotation attribute aliases");
            ContentDigest expected = annotationAttributeIdentity(declaration, span, declarationDigest,
                    defaultDeclared, aliasObservationIdentities);
            if (!identity.equals(expected)) throw new IllegalArgumentException("Annotation attribute identity mismatch");
        }

        public static AnnotationAttributeEvidence create(DeclarationRecord declaration,
                boolean defaultDeclared, Collection<ContentDigest> aliases) {
            List<ContentDigest> sortedAliases = aliases.stream().sorted().toList();
            ContentDigest digest = ContentDigest.sha256Utf8(declaration.spelling());
            return new AnnotationAttributeEvidence(annotationAttributeIdentity(declaration.entity().identity(),
                    declaration.entity().declaration(), digest, defaultDeclared, sortedAliases),
                    declaration.entity().identity(), declaration.entity().declaration(), digest,
                    defaultDeclared, sortedAliases);
        }

        private static ContentDigest annotationAttributeIdentity(EntityIdentity declaration,
                Optional<SourceSpan> span, ContentDigest declarationDigest, boolean defaultDeclared,
                List<ContentDigest> aliases) {
            return ContentDigest.sha256Utf8(CanonicalJson.write(Map.of(
                    "schema", "spring-annotation-attribute-evidence-v1", "declaration", declaration,
                    "span", span, "declarationDigest", declarationDigest,
                    "defaultDeclared", defaultDeclared, "aliasObservations", aliases)));
        }

        @Override public int compareTo(AnnotationAttributeEvidence other) { return identity.compareTo(other.identity); }
    }

    public record AnnotationDeclarationEvidence(ContentDigest identity, EntityIdentity declaration,
            Optional<ContentDigest> rawDeclarationObservationIdentity, EvidenceState evidenceState,
            Optional<ContentDigest> declarationDigest, List<AnnotationAttributeEvidence> attributes,
            List<ContentDigest> metaAnnotationObservationIdentities,
            List<EntityIdentity> repeatableContainerTargets, boolean graphComplete)
            implements Comparable<AnnotationDeclarationEvidence> {
        public AnnotationDeclarationEvidence {
            ContractChecks.notNull(identity, "annotation declaration evidence identity");
            ContractChecks.notNull(declaration, "annotation declaration identity");
            rawDeclarationObservationIdentity = ContractChecks.notNull(rawDeclarationObservationIdentity,
                    "annotation declaration raw observation");
            ContractChecks.notNull(evidenceState, "annotation declaration evidence state");
            declarationDigest = ContractChecks.notNull(declarationDigest, "annotation declaration digest");
            attributes = ContractChecks.sortedDistinct(attributes, Comparator.naturalOrder(),
                    "annotation attributes");
            metaAnnotationObservationIdentities = ContractChecks.sortedDistinct(
                    metaAnnotationObservationIdentities, Comparator.naturalOrder(), "annotation meta observations");
            repeatableContainerTargets = ContractChecks.sortedDistinct(repeatableContainerTargets,
                    Comparator.naturalOrder(), "repeatable container targets");
            if (evidenceState == EvidenceState.VERIFIED
                    && (rawDeclarationObservationIdentity.isEmpty() || declarationDigest.isEmpty())) {
                throw new IllegalArgumentException("Verified annotation declaration needs exact source evidence");
            }
            ContentDigest expected = annotationDeclarationIdentity(declaration,
                    rawDeclarationObservationIdentity, evidenceState, declarationDigest, attributes,
                    metaAnnotationObservationIdentities, repeatableContainerTargets, graphComplete);
            if (!identity.equals(expected)) throw new IllegalArgumentException("Annotation declaration evidence identity mismatch");
        }

        public static AnnotationDeclarationEvidence create(EntityIdentity declaration,
                Optional<ContentDigest> rawDeclarationObservationIdentity, EvidenceState evidenceState,
                Optional<ContentDigest> declarationDigest, Collection<AnnotationAttributeEvidence> attributes,
                Collection<ContentDigest> metaAnnotations, Collection<EntityIdentity> repeatableContainers,
                boolean graphComplete) {
            List<AnnotationAttributeEvidence> sortedAttributes = attributes.stream().sorted().toList();
            List<ContentDigest> sortedMeta = metaAnnotations.stream().sorted().toList();
            List<EntityIdentity> sortedContainers = repeatableContainers.stream().sorted().toList();
            return new AnnotationDeclarationEvidence(annotationDeclarationIdentity(declaration,
                    rawDeclarationObservationIdentity, evidenceState, declarationDigest, sortedAttributes,
                    sortedMeta, sortedContainers, graphComplete), declaration,
                    rawDeclarationObservationIdentity, evidenceState, declarationDigest, sortedAttributes,
                    sortedMeta, sortedContainers, graphComplete);
        }

        private static ContentDigest annotationDeclarationIdentity(EntityIdentity declaration,
                Optional<ContentDigest> raw, EvidenceState state, Optional<ContentDigest> digest,
                List<AnnotationAttributeEvidence> attributes, List<ContentDigest> meta,
                List<EntityIdentity> containers, boolean complete) {
            return ContentDigest.sha256Utf8(CanonicalJson.write(Map.ofEntries(
                    Map.entry("schema", "spring-annotation-declaration-evidence-v1"),
                    Map.entry("declaration", declaration), Map.entry("rawObservation", raw),
                    Map.entry("evidenceState", state), Map.entry("declarationDigest", digest),
                    Map.entry("attributes", attributes), Map.entry("metaAnnotations", meta),
                    Map.entry("repeatableContainers", containers), Map.entry("graphComplete", complete))));
        }

        @Override public int compareTo(AnnotationDeclarationEvidence other) { return identity.compareTo(other.identity); }
    }

    public record AnnotationMetaEdge(ContentDigest identity, EntityIdentity declaration,
            Optional<EntityIdentity> target, String stableTargetReference,
            ContentDigest rawObservationIdentity, EvidenceState evidenceState)
            implements Comparable<AnnotationMetaEdge> {
        public AnnotationMetaEdge {
            ContractChecks.notNull(identity, "annotation meta edge identity");
            ContractChecks.notNull(declaration, "annotation meta edge declaration");
            target = ContractChecks.notNull(target, "annotation meta edge target");
            stableTargetReference = ContractChecks.text(stableTargetReference, "annotation meta edge reference");
            ContractChecks.notNull(rawObservationIdentity, "annotation meta edge raw observation");
            ContractChecks.notNull(evidenceState, "annotation meta edge evidence state");
            if (evidenceState == EvidenceState.VERIFIED && target.isEmpty()) {
                throw new IllegalArgumentException("Verified annotation meta edge requires one exact target");
            }
            ContentDigest expected = annotationMetaEdgeIdentity(declaration, target,
                    stableTargetReference, rawObservationIdentity, evidenceState);
            if (!identity.equals(expected)) throw new IllegalArgumentException("Annotation meta edge identity mismatch");
        }

        public static AnnotationMetaEdge create(EntityIdentity declaration, Optional<EntityIdentity> target,
                String stableTargetReference, ContentDigest rawObservationIdentity, EvidenceState state) {
            return new AnnotationMetaEdge(annotationMetaEdgeIdentity(declaration, target,
                    stableTargetReference, rawObservationIdentity, state), declaration, target,
                    stableTargetReference, rawObservationIdentity, state);
        }

        private static ContentDigest annotationMetaEdgeIdentity(EntityIdentity declaration,
                Optional<EntityIdentity> target, String reference, ContentDigest raw, EvidenceState state) {
            return ContentDigest.sha256Utf8(CanonicalJson.write(Map.of(
                    "schema", "spring-annotation-meta-edge-v1", "declaration", declaration,
                    "target", target, "stableTargetReference", reference,
                    "rawObservation", raw, "evidenceState", state)));
        }

        @Override public int compareTo(AnnotationMetaEdge other) { return identity.compareTo(other.identity); }
    }

    public record AnnotationCycle(ContentDigest identity, List<EntityIdentity> declarations,
            List<ContentDigest> edgeIdentities) implements Comparable<AnnotationCycle> {
        public AnnotationCycle {
            ContractChecks.notNull(identity, "annotation cycle identity");
            declarations = ContractChecks.sortedDistinct(declarations, Comparator.naturalOrder(),
                    "annotation cycle declarations");
            edgeIdentities = ContractChecks.sortedDistinct(edgeIdentities, Comparator.naturalOrder(),
                    "annotation cycle edges");
            if (declarations.isEmpty() || edgeIdentities.isEmpty()) {
                throw new IllegalArgumentException("Annotation cycle needs declarations and edges");
            }
            ContentDigest expected = annotationCycleIdentity(declarations, edgeIdentities);
            if (!identity.equals(expected)) throw new IllegalArgumentException("Annotation cycle identity mismatch");
        }

        public static AnnotationCycle create(Collection<EntityIdentity> declarations,
                Collection<ContentDigest> edges) {
            List<EntityIdentity> sortedDeclarations = declarations.stream().sorted().toList();
            List<ContentDigest> sortedEdges = edges.stream().sorted().toList();
            return new AnnotationCycle(annotationCycleIdentity(sortedDeclarations, sortedEdges),
                    sortedDeclarations, sortedEdges);
        }

        private static ContentDigest annotationCycleIdentity(List<EntityIdentity> declarations,
                List<ContentDigest> edges) {
            return ContentDigest.sha256Utf8(CanonicalJson.write(Map.of(
                    "schema", "spring-annotation-cycle-v1", "declarations", declarations,
                    "edges", edges)));
        }

        @Override public int compareTo(AnnotationCycle other) { return identity.compareTo(other.identity); }
    }

    /** Exact decoded resource input bound into the inventory identity before lexical scanning. */
    public record ResourceEvidence(ContentDigest identity, ResourceKind kind,
            SourceDocument document, SourceInput.Decoding decoding) implements Comparable<ResourceEvidence> {
        public ResourceEvidence {
            ContractChecks.notNull(identity, "Spring resource evidence identity");
            ContractChecks.notNull(kind, "Spring resource kind");
            ContractChecks.notNull(document, "Spring resource document");
            ContractChecks.notNull(decoding, "Spring resource decoding");
            ContentDigest expected = resourceIdentity(kind, document, decoding);
            if (!identity.equals(expected)) {
                throw new IllegalArgumentException("Spring resource evidence identity mismatch");
            }
        }

        public static ResourceEvidence from(ResourceKind kind, SourceInput input) {
            ContractChecks.notNull(input, "Spring resource input");
            return new ResourceEvidence(resourceIdentity(kind, input.document(), input.decoding()),
                    kind, input.document(), input.decoding());
        }

        private static ContentDigest resourceIdentity(
                ResourceKind kind, SourceDocument document, SourceInput.Decoding decoding) {
            return ContentDigest.sha256Utf8(CanonicalJson.write(Map.of(
                    "schema", "spring-resource-evidence-v1", "kind", kind,
                    "document", document, "decoding", decoding)));
        }

        @Override public int compareTo(ResourceEvidence other) { return identity.compareTo(other.identity); }
    }

    public record RawObservation(ContentDigest identity, RawKind kind, SourceDocumentIdentity document,
            Optional<SourceSpan> span, int ordinal, Optional<EntityIdentity> owner,
            Optional<EntityIdentity> resolvedTarget, EvidenceState evidenceState,
            String stableReference, String spelling, List<Diagnostic> diagnostics)
            implements Comparable<RawObservation> {
        public RawObservation {
            ContractChecks.notNull(identity, "raw observation identity");
            ContractChecks.notNull(kind, "raw observation kind");
            ContractChecks.notNull(document, "raw observation document");
            span = ContractChecks.notNull(span, "raw observation span");
            if (span.stream().anyMatch(value -> !value.document().equals(document))) {
                throw new IllegalArgumentException("Raw observation span belongs to another document");
            }
            if (ordinal < 0) throw new IllegalArgumentException("Raw observation ordinal must be non-negative");
            owner = ContractChecks.notNull(owner, "raw observation owner");
            resolvedTarget = ContractChecks.notNull(resolvedTarget, "raw observation target");
            ContractChecks.notNull(evidenceState, "raw observation evidence state");
            if (kind == RawKind.ANNOTATION_USE && evidenceState == EvidenceState.VERIFIED
                    && resolvedTarget.isEmpty()) {
                throw new IllegalArgumentException("Verified annotation evidence requires one target");
            }
            stableReference = ContractChecks.text(stableReference, "raw observation stable reference");
            spelling = ContractChecks.text(spelling, "raw observation spelling");
            diagnostics = ContractChecks.sortedDistinct(
                    diagnostics, Comparator.naturalOrder(), "raw observation diagnostics");
            ContentDigest expected = rawIdentity(kind, document, span, ordinal, owner,
                    resolvedTarget, evidenceState, stableReference, spelling, diagnostics);
            if (!identity.equals(expected)) throw new IllegalArgumentException("Raw observation identity mismatch");
        }

        public static RawObservation create(RawKind kind, SourceDocumentIdentity document,
                Optional<SourceSpan> span, int ordinal, Optional<EntityIdentity> owner,
                Optional<EntityIdentity> resolvedTarget, EvidenceState evidenceState,
                String stableReference, String spelling, List<Diagnostic> diagnostics) {
            List<Diagnostic> sortedDiagnostics = diagnostics.stream().sorted().toList();
            return new RawObservation(rawIdentity(kind, document, span, ordinal, owner, resolvedTarget,
                    evidenceState, stableReference, spelling, sortedDiagnostics), kind, document, span, ordinal,
                    owner, resolvedTarget, evidenceState, stableReference, spelling, sortedDiagnostics);
        }

        private static ContentDigest rawIdentity(RawKind kind, SourceDocumentIdentity document,
                Optional<SourceSpan> span, int ordinal, Optional<EntityIdentity> owner,
                Optional<EntityIdentity> target, EvidenceState state, String reference,
                String spelling, List<Diagnostic> diagnostics) {
            return ContentDigest.sha256Utf8(CanonicalJson.write(Map.ofEntries(
                    Map.entry("schema", "spring-raw-observation-v2"), Map.entry("kind", kind),
                    Map.entry("document", document), Map.entry("span", span), Map.entry("ordinal", ordinal),
                    Map.entry("owner", owner), Map.entry("resolvedTarget", target),
                    Map.entry("evidenceState", state), Map.entry("stableReference", reference),
                    Map.entry("spelling", spelling), Map.entry("diagnostics", diagnostics))));
        }

        @Override public int compareTo(RawObservation other) { return identity.compareTo(other.identity); }
    }

    public record SemanticObligation(ContentDigest identity, ContentDigest rawObservationIdentity,
            int roleOrdinal, String primaryMechanism, String role, List<String> secondaryTags,
            Classification classification) implements Comparable<SemanticObligation> {
        public SemanticObligation {
            ContractChecks.notNull(identity, "Spring obligation identity");
            ContractChecks.notNull(rawObservationIdentity, "Spring obligation raw observation");
            if (roleOrdinal < 0) throw new IllegalArgumentException("Spring obligation ordinal must be non-negative");
            primaryMechanism = ContractChecks.namespacedId(primaryMechanism, "primary Spring mechanism");
            role = ContractChecks.namespacedId(role, "Spring obligation role");
            secondaryTags = ContractChecks.sortedStrings(secondaryTags, "Spring obligation tags");
            ContractChecks.notNull(classification, "Spring obligation classification");
            ContentDigest expected = obligationIdentity(rawObservationIdentity, roleOrdinal,
                    primaryMechanism, role, secondaryTags, classification);
            if (!identity.equals(expected)) throw new IllegalArgumentException("Spring obligation identity mismatch");
        }

        public static SemanticObligation create(ContentDigest rawObservationIdentity, int roleOrdinal,
                String primaryMechanism, String role, Collection<String> secondaryTags,
                Classification classification) {
            List<String> tags = secondaryTags.stream().sorted().toList();
            return new SemanticObligation(obligationIdentity(rawObservationIdentity, roleOrdinal,
                    primaryMechanism, role, tags, classification), rawObservationIdentity,
                    roleOrdinal, primaryMechanism, role, tags, classification);
        }

        private static ContentDigest obligationIdentity(ContentDigest raw, int ordinal, String mechanism,
                String role, List<String> tags, Classification classification) {
            return ContentDigest.sha256Utf8(CanonicalJson.write(Map.of(
                    "schema", "spring-semantic-obligation-v1", "rawObservation", raw,
                    "roleOrdinal", ordinal, "primaryMechanism", mechanism, "role", role,
                    "secondaryTags", tags, "classification", classification)));
        }

        @Override public int compareTo(SemanticObligation other) { return identity.compareTo(other.identity); }
    }

    public record Coverage(long rawObservationCount, long semanticObligationCount,
            long markerObservationCount, long unclassifiedObservationCount,
            long annotationDeclarationCount, long annotationMetaEdgeCount,
            long annotationCycleCount) {
        public Coverage {
            if (rawObservationCount < 0 || semanticObligationCount < 0
                    || markerObservationCount < 0 || unclassifiedObservationCount < 0
                    || annotationDeclarationCount < 0 || annotationMetaEdgeCount < 0
                    || annotationCycleCount < 0
                    || markerObservationCount > rawObservationCount
                    || unclassifiedObservationCount > rawObservationCount) {
                throw new IllegalArgumentException("Invalid Spring inventory coverage");
            }
        }
    }

    public record Problem(ProblemReason reason, String mechanismCategory, EvidenceSubject subject,
            Optional<ContentDigest> rawObservationIdentity, List<SourceSpan> sourceSpans,
            List<Diagnostic> diagnostics, List<String> limitations) {
        public Problem {
            ContractChecks.notNull(reason, "Spring inventory problem reason");
            mechanismCategory = ContractChecks.namespacedId(mechanismCategory, "Spring problem category");
            ContractChecks.notNull(subject, "Spring problem subject");
            rawObservationIdentity = ContractChecks.notNull(rawObservationIdentity, "Spring problem raw observation");
            sourceSpans = ContractChecks.sortedDistinct(
                    sourceSpans, Comparator.naturalOrder(), "Spring problem spans");
            diagnostics = ContractChecks.sortedDistinct(
                    diagnostics, Comparator.naturalOrder(), "Spring problem diagnostics");
            limitations = ContractChecks.sortedStrings(limitations, "Spring problem limitations");
            if (limitations.isEmpty()) throw new IllegalArgumentException("Spring problem needs a bounded explanation");
        }
    }
}
