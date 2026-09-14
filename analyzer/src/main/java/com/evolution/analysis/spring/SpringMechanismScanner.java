package com.evolution.analysis.spring;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.evidence.EvidenceSubject;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.spring.SpringMechanismInventory.*;
import java.io.ByteArrayInputStream;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.stream.*;

/** Pure passive M4A.2 scanner over supplied M2 and decoded resource evidence. */
public final class SpringMechanismScanner {
    private static final RelationshipKind ANNOTATED_WITH = new RelationshipKind("java.annotated-with");
    private static final String XML_FAMILY = "spring.registration.xml";
    private static final String UNCLASSIFIED = "spring.mechanism.unclassified";
    private static final String BEANS_NAMESPACE = "http://www.springframework.org/schema/beans";
    private static final Set<String> XML_MARKER_NAMESPACES = Set.of(
            "http://www.w3.org/2001/XMLSchema-instance",
            "http://www.w3.org/XML/1998/namespace");
    private static final Set<String> BEANS_ELEMENTS = Set.of(
            "alias", "arg-type", "array", "bean", "beans", "constructor-arg", "description",
            "entry", "idref", "import", "key", "list", "lookup-method", "map", "meta", "null",
            "prop", "property", "props", "qualifier", "ref", "replaced-method", "set", "value");
    private static final Set<String> SPRING_DATA_TYPES = Set.of(
            "org.springframework.data.repository.Repository",
            "org.springframework.data.repository.CrudRepository",
            "org.springframework.data.repository.ListCrudRepository",
            "org.springframework.data.repository.PagingAndSortingRepository",
            "org.springframework.data.repository.ListPagingAndSortingRepository",
            "org.springframework.data.repository.reactive.ReactiveCrudRepository",
            "org.springframework.data.repository.reactive.ReactiveSortingRepository",
            "org.springframework.data.repository.kotlin.CoroutineCrudRepository",
            "org.springframework.data.jpa.repository.JpaRepository",
            "org.springframework.data.mongodb.repository.MongoRepository",
            "org.springframework.data.mongodb.repository.ReactiveMongoRepository");
    private static final Set<String> PROGRAMMATIC_SPI_TYPES = Set.of(
            "org.springframework.beans.factory.config.BeanFactoryPostProcessor",
            "org.springframework.beans.factory.config.BeanPostProcessor",
            "org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor",
            "org.springframework.context.ApplicationContextInitializer",
            "org.springframework.context.annotation.ImportBeanDefinitionRegistrar",
            "org.springframework.context.annotation.ImportSelector",
            "org.springframework.context.annotation.DeferredImportSelector");
    private static final Set<String> FRAMEWORK_ENTRYPOINT_TYPES = Set.of(
            "org.springframework.beans.factory.DisposableBean",
            "org.springframework.beans.factory.InitializingBean",
            "org.springframework.beans.factory.SmartInitializingSingleton",
            "org.springframework.boot.ApplicationRunner",
            "org.springframework.boot.CommandLineRunner",
            "org.springframework.boot.web.servlet.ServletContextInitializer",
            "org.springframework.context.ApplicationListener",
            "org.springframework.context.Lifecycle",
            "org.springframework.context.SmartLifecycle");
    private static final Set<String> AGGREGATE_JDK_TYPES = Set.of(
            "java.util.Collection", "java.util.List", "java.util.Map", "java.util.Optional",
            "java.util.Set");
    private static final Set<String> AGGREGATE_DEPENDENCY_TYPES = Set.of(
            "jakarta.inject.Provider", "javax.inject.Provider",
            "org.springframework.beans.factory.ObjectFactory",
            "org.springframework.beans.factory.ObjectProvider");
    private static final Set<String> PROGRAMMATIC_CALL_OWNER_TYPES = Set.of(
            "org.springframework.context.support.GenericApplicationContext",
            "org.springframework.context.annotation.AnnotationConfigApplicationContext",
            "org.springframework.beans.factory.support.BeanDefinitionRegistry",
            "org.springframework.beans.factory.support.DefaultListableBeanFactory",
            "org.springframework.beans.factory.config.SingletonBeanRegistry",
            "org.springframework.beans.factory.support.BeanDefinitionReader",
            "org.springframework.context.annotation.AnnotatedBeanDefinitionReader",
            "org.springframework.context.annotation.ClassPathBeanDefinitionScanner");
    private static final Set<String> CONTAINER_LOOKUP_CALL_OWNER_TYPES = Set.of(
            "org.springframework.beans.factory.BeanFactory",
            "org.springframework.beans.factory.ListableBeanFactory",
            "org.springframework.beans.factory.ObjectFactory",
            "org.springframework.beans.factory.ObjectProvider",
            "org.springframework.beans.factory.config.ConfigurableListableBeanFactory",
            "org.springframework.beans.factory.support.AbstractBeanFactory",
            "org.springframework.beans.factory.support.DefaultListableBeanFactory",
            "org.springframework.context.ApplicationContext",
            "org.springframework.context.ConfigurableApplicationContext",
            "org.springframework.context.support.AbstractApplicationContext",
            "org.springframework.web.context.WebApplicationContext");

    private SpringMechanismScanner() {}

    public static SpringMechanismInventory scan(SpringMechanismScanRequest request) {
        ContractChecks.notNull(request, "Spring mechanism scan request");
        Accumulator output = new Accumulator(request.frontendResult());
        scanAnnotations(request.frontendResult(), output);
        buildAnnotationGraph(request.frontendResult(), request.frameworkEvidence(), output);
        classifyAnnotations(request.frameworkEvidence(), output);
        scanJavaMechanisms(request.frontendResult(), request.frameworkEvidence(), output);
        request.xmlInputs().forEach(input -> scanXml(input, output));
        request.metadataInputs().forEach(input -> scanMetadata(input, output));
        validateFramework(request.frameworkEvidence(), output);
        List<ResourceEvidence> resources = java.util.stream.Stream.concat(
                request.xmlInputs().stream().map(input -> ResourceEvidence.from(ResourceKind.XML, input)),
                request.metadataInputs().stream().map(input -> ResourceEvidence.from(ResourceKind.METADATA, input)))
                .sorted().toList();
        return SpringMechanismInventory.create(request.frontendResult().analysis(),
                ContentDigest.sha256Utf8(CanonicalJson.write(request.frontendResult())),
                request.frameworkEvidence(), resources, output.annotationDeclarations,
                output.annotationMetaEdges, output.annotationCycles, output.raw,
                output.obligations, output.markers, output.problems);
    }

    private static void scanAnnotations(FrontendResult frontend, Accumulator output) {
        Map<EntityIdentity, DeclarationRecord> declarations = frontend.declarations().stream()
                .collect(java.util.stream.Collectors.toMap(value -> value.entity().identity(), value -> value));
        Map<OccurrenceIdentity, RelationshipOccurrence> occurrences = frontend.occurrences().stream()
                .collect(java.util.stream.Collectors.toMap(RelationshipOccurrence::identity, value -> value));
        Map<SourceSpan, List<ObservationRecord>> observationsBySpan = new TreeMap<>();
        frontend.observations().stream().filter(value -> value.category().equals(ANNOTATED_WITH)).forEach(value -> {
            if (value.span().isPresent()) {
                observationsBySpan.computeIfAbsent(value.span().orElseThrow(), ignored -> new ArrayList<>()).add(value);
            }
        });
        Set<ObservationRecord> consumed = new HashSet<>();
        ArrayList<AnnotationCandidate> candidates = new ArrayList<>();

        for (AnnotationUseRecord annotation : frontend.annotations()) {
            List<ObservationRecord> matches = observationsBySpan.getOrDefault(annotation.span(), List.of());
            consumed.addAll(matches);
            if (matches.size() == 1) {
                candidates.add(candidate(annotation, matches.getFirst(), occurrences, declarations));
            } else {
                candidates.add(new AnnotationCandidate(annotation.span().document(), Optional.of(annotation.span()),
                        Optional.of(annotation.owner()), Optional.empty(), EvidenceState.ERROR,
                        annotation.canonicalName(), annotation.spelling(), List.of(),
                        "The M2 annotation-use row did not have exactly one matching annotated-with observation."));
            }
        }
        frontend.observations().stream().filter(value -> value.category().equals(ANNOTATED_WITH))
                .filter(value -> !consumed.contains(value)).forEach(observation -> candidates.add(
                        candidate(observation, occurrences, declarations)));

        candidates.sort(Comparator.comparing((AnnotationCandidate value) -> value.document().value())
                .thenComparing(value -> value.span().map(CanonicalJson::write).orElse(""))
                .thenComparing(AnnotationCandidate::spelling)
                .thenComparing(AnnotationCandidate::stableReference));
        Map<SourceDocumentIdentity, Integer> ordinals = new HashMap<>();
        for (AnnotationCandidate candidate : candidates) {
            int ordinal = ordinals.merge(candidate.document(), 1, Integer::sum) - 1;
            RawObservation raw = RawObservation.create(RawKind.ANNOTATION_USE, candidate.document(),
                    candidate.span(), ordinal, candidate.owner(), candidate.resolvedTarget(),
                    candidate.state(), candidate.stableReference(), candidate.spelling(), candidate.diagnostics());
            output.raw.add(raw);
            output.annotationCandidates.put(raw.identity(), candidate);
        }

        int declarationOrdinal = 0;
        for (DeclarationRecord declaration : frontend.declarations()) {
            if (!isSourceAnnotationDeclaration(declaration)) continue;
            RawObservation raw = RawObservation.create(RawKind.ANNOTATION_DECLARATION,
                    declaration.entity().declaration().orElseThrow().document(), declaration.entity().declaration(),
                    declarationOrdinal++, Optional.of(declaration.entity().identity()),
                    Optional.of(declaration.entity().identity()), map(declaration.status()),
                    declaration.entity().canonicalName(), declaration.spelling(), declaration.diagnostics());
            output.raw.add(raw);
            output.markers.add(raw.identity());
            output.annotationDeclarationRows.put(declaration.entity().identity(), raw.identity());
        }
    }

    private static void buildAnnotationGraph(FrontendResult frontend,
            SpringFrameworkEvidence frameworkEvidence, Accumulator output) {
        Map<EntityIdentity, EntityIdentity> containers = declarationContainers(frontend);
        Map<EntityIdentity, DeclarationRecord> sourceAnnotations = output.declarations.values().stream()
                .filter(SpringMechanismScanner::isSourceAnnotationDeclaration)
                .collect(java.util.stream.Collectors.toMap(value -> value.entity().identity(), value -> value,
                        (left, right) -> left, TreeMap::new));

        for (Map.Entry<ContentDigest, AnnotationCandidate> entry : output.annotationCandidates.entrySet()) {
            AnnotationCandidate candidate = entry.getValue();
            if (candidate.owner().isEmpty() || !sourceAnnotations.containsKey(candidate.owner().orElseThrow())) continue;
            output.annotationMetaEdges.add(AnnotationMetaEdge.create(candidate.owner().orElseThrow(),
                    candidate.resolvedTarget(), candidate.stableReference(), entry.getKey(), candidate.state()));
        }

        List<AnnotationCycle> cycles = annotationCycles(sourceAnnotations.keySet(), output.annotationMetaEdges);
        output.annotationCycles.addAll(cycles);
        Set<EntityIdentity> cyclicDeclarations = cycles.stream()
                .flatMap(value -> value.declarations().stream()).collect(java.util.stream.Collectors.toSet());

        TreeMap<EntityIdentity, AnnotationDeclarationParts> partsByDeclaration = new TreeMap<>();
        for (DeclarationRecord declaration : sourceAnnotations.values()) {
            EntityIdentity declarationIdentity = declaration.entity().identity();
            List<AnnotationMetaEdge> outgoing = output.annotationMetaEdges.stream()
                    .filter(edge -> edge.declaration().equals(declarationIdentity)).toList();
            List<AnnotationAttributeEvidence> attributes = output.declarations.values().stream()
                    .filter(value -> value.entity().kind() == EntityKind.METHOD)
                    .filter(value -> containers.get(value.entity().identity()) != null
                            && containers.get(value.entity().identity()).equals(declarationIdentity))
                    .map(value -> AnnotationAttributeEvidence.create(value,
                            hasJavaKeyword(value.spelling(), "default"), aliasRows(value.entity().identity(),
                                    frameworkEvidence, output)))
                    .sorted().toList();
            List<EntityIdentity> repeatableTargets = repeatableTargets(declarationIdentity,
                    frontend.types(), frameworkEvidence, output);
            boolean hasRepeatable = outgoing.stream().anyMatch(edge -> edge.target().stream().anyMatch(target ->
                    isExactType(target, "java.lang.annotation.Repeatable", frameworkEvidence,
                            output.declarations, EntityOrigin.JDK)));
            boolean locallyComplete = declaration.status() == SemanticStatus.RESOLVED
                    && outgoing.stream().allMatch(edge -> edge.evidenceState() == EvidenceState.VERIFIED
                            && edge.target().isPresent()
                            && (sourceAnnotations.containsKey(edge.target().orElseThrow())
                                    || trustedAnnotationEdge(edge.target().orElseThrow(),
                                            frameworkEvidence, output)))
                    && attributes.stream().allMatch(attribute ->
                            attribute.aliasObservationIdentities().size() <= 1
                                    && attribute.aliasObservationIdentities().stream()
                                            .allMatch(identity -> raw(output, identity).evidenceState()
                                                    == EvidenceState.VERIFIED))
                    && (!hasRepeatable || repeatableTargets.size() == 1)
                    && !cyclicDeclarations.contains(declarationIdentity);
            partsByDeclaration.put(declarationIdentity, new AnnotationDeclarationParts(
                    declaration, outgoing, attributes, repeatableTargets, locallyComplete));
        }

        TreeSet<EntityIdentity> incompleteDeclarations = new TreeSet<>(cyclicDeclarations);
        partsByDeclaration.forEach((identity, parts) -> {
            if (!parts.locallyComplete()) incompleteDeclarations.add(identity);
        });
        boolean changed;
        do {
            changed = false;
            for (Map.Entry<EntityIdentity, AnnotationDeclarationParts> entry : partsByDeclaration.entrySet()) {
                if (incompleteDeclarations.contains(entry.getKey())) continue;
                boolean reachesIncompleteSource = entry.getValue().outgoing().stream()
                        .flatMap(edge -> edge.target().stream())
                        .anyMatch(incompleteDeclarations::contains);
                if (reachesIncompleteSource) changed |= incompleteDeclarations.add(entry.getKey());
            }
        } while (changed);

        for (Map.Entry<EntityIdentity, AnnotationDeclarationParts> entry : partsByDeclaration.entrySet()) {
            EntityIdentity declarationIdentity = entry.getKey();
            AnnotationDeclarationParts parts = entry.getValue();
            Optional<ContentDigest> raw = Optional.ofNullable(output.annotationDeclarationRows.get(declarationIdentity));
            AnnotationDeclarationEvidence evidence = AnnotationDeclarationEvidence.create(declarationIdentity,
                    raw, EvidenceState.VERIFIED,
                    Optional.of(ContentDigest.sha256Utf8(parts.declaration().spelling())),
                    parts.attributes(), parts.outgoing().stream()
                            .map(AnnotationMetaEdge::rawObservationIdentity).toList(),
                    parts.repeatableTargets(), !incompleteDeclarations.contains(declarationIdentity));
            output.annotationDeclarations.add(evidence);
            output.annotationDeclarationsByEntity.put(declarationIdentity, evidence);
            if (incompleteDeclarations.contains(declarationIdentity)) {
                output.incompleteAnnotationDeclarations.add(declarationIdentity);
            }
        }

        for (Map.Entry<ContentDigest, AnnotationCandidate> entry : output.annotationCandidates.entrySet()) {
            AnnotationCandidate candidate = entry.getValue();
            if (candidate.resolvedTarget().isEmpty()) continue;
            EntityIdentity target = candidate.resolvedTarget().orElseThrow();
            if (output.annotationDeclarationsByEntity.containsKey(target)) continue;
            DeclarationRecord declaration = output.declarations.get(target);
            if (declaration == null || knownAnnotationRole(target, frameworkEvidence, output)
                    || declaration.entity().origin() == EntityOrigin.JDK) continue;
            AnnotationDeclarationEvidence missing = AnnotationDeclarationEvidence.create(target,
                    Optional.empty(), EvidenceState.UNRESOLVED, Optional.empty(), List.of(), List.of(),
                    List.of(), false);
            output.annotationDeclarations.add(missing);
            output.annotationDeclarationsByEntity.put(target, missing);
            output.incompleteAnnotationDeclarations.add(target);
        }

        for (AnnotationCycle cycle : cycles) {
            ContentDigest raw = output.annotationMetaEdges.stream()
                    .filter(edge -> cycle.edgeIdentities().contains(edge.identity()))
                    .map(AnnotationMetaEdge::rawObservationIdentity).sorted().findFirst().orElseThrow();
            RawObservation observation = output.raw.stream().filter(value -> value.identity().equals(raw))
                    .findFirst().orElseThrow();
            output.problems.add(problem(ProblemReason.ANNOTATION_GRAPH_INCOMPLETE,
                    "spring.bean.stereotype.composed",
                    new EvidenceSubject(EvidenceSubject.Kind.ENTITY, cycle.declarations().getFirst().value()),
                    Optional.of(raw), observation.span().stream().toList(), observation.diagnostics(),
                    "The exact meta-annotation graph contains a cycle; the cycle and all participating edges are retained."));
        }
    }

    private static Map<EntityIdentity, EntityIdentity> declarationContainers(FrontendResult frontend) {
        TreeMap<EntityIdentity, EntityIdentity> result = new TreeMap<>();
        java.util.stream.Stream.concat(frontend.occurrences().stream().map(RelationshipOccurrence::relationship),
                        frontend.derivedRelationships().stream().map(DerivedRelationshipRecord::relationship))
                .filter(value -> value.kind().value().equals("java.declares"))
                .filter(value -> value.target() instanceof RelationshipTarget.Resolved)
                .forEach(value -> {
                    EntityIdentity child = ((RelationshipTarget.Resolved) value.target()).target();
                    EntityIdentity prior = result.putIfAbsent(child, value.source());
                    if (prior != null && !prior.equals(value.source())) result.remove(child);
                });
        return Map.copyOf(result);
    }

    private static boolean isSourceAnnotationDeclaration(DeclarationRecord declaration) {
        return declaration.entity().kind() == EntityKind.TYPE
                && declaration.entity().declaration().isPresent()
                && sourceTypeKind(declaration.spelling()) == SourceTypeKind.ANNOTATION;
    }

    private static boolean isInstantiableSourceType(DeclarationRecord declaration) {
        return switch (sourceTypeKind(declaration.spelling())) {
            case CLASS, RECORD, ENUM -> true;
            case ANNOTATION, INTERFACE, UNKNOWN -> false;
        };
    }

    private static SourceTypeKind sourceTypeKind(String source) {
        List<String> tokens = javaTokens(source);
        int parentheses = 0;
        int brackets = 0;
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (token.equals("(")) { parentheses++; continue; }
            if (token.equals(")")) { parentheses = Math.max(0, parentheses - 1); continue; }
            if (token.equals("[")) { brackets++; continue; }
            if (token.equals("]")) { brackets = Math.max(0, brackets - 1); continue; }
            if (parentheses != 0 || brackets != 0) continue;
            if (token.equals("{")) return SourceTypeKind.UNKNOWN;
            if (token.equals("@") && index + 1 < tokens.size()
                    && tokens.get(index + 1).equals("interface")) return SourceTypeKind.ANNOTATION;
            SourceTypeKind kind = switch (token) {
                case "class" -> SourceTypeKind.CLASS;
                case "interface" -> SourceTypeKind.INTERFACE;
                case "record" -> SourceTypeKind.RECORD;
                case "enum" -> SourceTypeKind.ENUM;
                default -> SourceTypeKind.UNKNOWN;
            };
            if (kind != SourceTypeKind.UNKNOWN
                    && (index == 0 || !tokens.get(index - 1).equals("."))) return kind;
        }
        return SourceTypeKind.UNKNOWN;
    }

    private static List<ContentDigest> aliasRows(EntityIdentity attribute,
            SpringFrameworkEvidence frameworkEvidence, Accumulator output) {
        return output.annotationCandidates.entrySet().stream()
                .filter(entry -> entry.getValue().owner().equals(Optional.of(attribute)))
                .filter(entry -> entry.getValue().resolvedTarget().stream().anyMatch(target ->
                        isExactType(target, "org.springframework.core.annotation.AliasFor", frameworkEvidence,
                                output.declarations, EntityOrigin.DEPENDENCY)))
                .map(Map.Entry::getKey).sorted().toList();
    }

    private static List<EntityIdentity> repeatableTargets(EntityIdentity declaration,
            List<TypeUseRecord> types, SpringFrameworkEvidence frameworkEvidence, Accumulator output) {
        List<RawObservation> repeatableRows = output.annotationCandidates.entrySet().stream()
                .filter(entry -> entry.getValue().owner().equals(Optional.of(declaration)))
                .filter(entry -> entry.getValue().resolvedTarget().stream().anyMatch(target ->
                        isExactType(target, "java.lang.annotation.Repeatable", frameworkEvidence,
                                output.declarations, EntityOrigin.JDK)))
                .map(Map.Entry::getKey)
                .map(identity -> output.raw.stream().filter(value -> value.identity().equals(identity))
                        .findFirst().orElseThrow()).toList();
        TreeSet<EntityIdentity> result = new TreeSet<>();
        for (RawObservation repeatable : repeatableRows) {
            types.stream().filter(type -> type.owner().equals(Optional.of(declaration)))
                    .filter(type -> repeatable.span().stream().anyMatch(value -> contains(value, type.span())))
                    .flatMap(type -> type.type().referencedEntities().stream())
                    .filter(target -> !isExactType(target, "java.lang.annotation.Repeatable", frameworkEvidence,
                            output.declarations, EntityOrigin.JDK)).forEach(result::add);
        }
        return List.copyOf(result);
    }

    private static boolean contains(SourceSpan outer, SourceSpan inner) {
        if (!outer.document().equals(inner.document())) return false;
        return comparePosition(outer.startLine(), outer.startColumn(), inner.startLine(), inner.startColumn()) <= 0
                && comparePosition(inner.endLine(), inner.endColumn(), outer.endLine(), outer.endColumn()) <= 0;
    }

    private static int comparePosition(int leftLine, int leftColumn, int rightLine, int rightColumn) {
        int line = Integer.compare(leftLine, rightLine);
        return line != 0 ? line : Integer.compare(leftColumn, rightColumn);
    }

    private static boolean trustedAnnotationEdge(EntityIdentity target,
            SpringFrameworkEvidence frameworkEvidence, Accumulator output) {
        DeclarationRecord declaration = output.declarations.get(target);
        if (declaration == null) return false;
        if (declaration.entity().origin() == EntityOrigin.PROJECT) {
            return output.annotationDeclarationsByEntity.containsKey(target)
                    || isSourceAnnotationDeclaration(declaration);
        }
        if (declaration.entity().origin() == EntityOrigin.JDK) return true;
        return declaration.entity().origin() == EntityOrigin.DEPENDENCY
                && frameworkEvidence.containsEntityScope(declaration.entity().stableScope())
                && !SpringMechanismCatalog.entriesForAnnotationKey(
                        declaration.entity().canonicalName()).isEmpty();
    }

    private static boolean knownAnnotationRole(EntityIdentity target,
            SpringFrameworkEvidence frameworkEvidence, Accumulator output) {
        DeclarationRecord declaration = output.declarations.get(target);
        if (declaration == null) return false;
        String key = declaration.entity().canonicalName();
        return SpringMechanismCatalog.isNonSpringMarker(key)
                && declaration.entity().origin() == EntityOrigin.JDK
                || !SpringMechanismCatalog.entriesForAnnotationKey(key).isEmpty()
                && declaration.entity().origin() == EntityOrigin.DEPENDENCY
                && frameworkEvidence.containsEntityScope(declaration.entity().stableScope());
    }

    private static List<SpringMechanismCatalog.Entry> composedAnnotationFamilies(EntityIdentity start,
            Optional<EntityIdentity> useOwner, Map<EntityIdentity, DeclarationRecord> declarations,
            SpringFrameworkEvidence frameworkEvidence, Accumulator output) {
        TreeMap<EntityIdentity, List<AnnotationMetaEdge>> adjacency = new TreeMap<>();
        output.annotationMetaEdges.forEach(edge -> adjacency.computeIfAbsent(edge.declaration(),
                ignored -> new ArrayList<>()).add(edge));
        adjacency.values().forEach(edges -> edges.sort(Comparator.naturalOrder()));
        TreeSet<EntityIdentity> visited = new TreeSet<>();
        ArrayDeque<EntityIdentity> queue = new ArrayDeque<>();
        queue.add(start);
        TreeMap<String, SpringMechanismCatalog.Entry> families = new TreeMap<>();
        while (!queue.isEmpty()) {
            EntityIdentity current = queue.removeFirst();
            if (!visited.add(current)) continue;
            for (AnnotationMetaEdge edge : adjacency.getOrDefault(current, List.of())) {
                if (edge.evidenceState() != EvidenceState.VERIFIED || edge.target().isEmpty()) continue;
                EntityIdentity target = edge.target().orElseThrow();
                DeclarationRecord declaration = declarations.get(target);
                if (declaration == null) continue;
                List<SpringMechanismCatalog.Entry> entries = SpringMechanismCatalog.entriesForAnnotationKey(
                        declaration.entity().canonicalName());
                if (!entries.isEmpty() && declaration.entity().origin() == EntityOrigin.DEPENDENCY
                        && frameworkEvidence.containsEntityScope(declaration.entity().stableScope())) {
                    SpringMechanismCatalog.Entry selected = selectAnnotationFamily(entries, useOwner, declarations);
                    if (selected != null && !selected.id().equals("spring.bean.stereotype.composed")) {
                        families.put(selected.id(), selected);
                    }
                }
                if (output.annotationDeclarationsByEntity.containsKey(target)
                        || adjacency.containsKey(target)) queue.addLast(target);
            }
        }
        return List.copyOf(families.values());
    }

    private static List<AnnotationCycle> annotationCycles(Set<EntityIdentity> declarations,
            List<AnnotationMetaEdge> edges) {
        TreeMap<EntityIdentity, List<AnnotationMetaEdge>> adjacency = new TreeMap<>();
        declarations.forEach(value -> adjacency.put(value, new ArrayList<>()));
        edges.stream().filter(edge -> edge.target().stream().anyMatch(declarations::contains))
                .forEach(edge -> adjacency.get(edge.declaration()).add(edge));
        adjacency.values().forEach(values -> values.sort(Comparator.naturalOrder()));
        return new AnnotationTarjan(adjacency).cycles();
    }

    private static boolean hasJavaKeyword(String source, String keyword) {
        return javaTokens(source).contains(keyword);
    }

    private static List<String> javaTokens(String source) {
        ArrayList<String> tokens = new ArrayList<>();
        for (int index = 0; index < source.length();) {
            char value = source.charAt(index);
            if (Character.isWhitespace(value)) { index++; continue; }
            if (value == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                index += 2;
                while (index < source.length() && source.charAt(index) != '\n' && source.charAt(index) != '\r') index++;
                continue;
            }
            if (value == '/' && index + 1 < source.length() && source.charAt(index + 1) == '*') {
                int end = source.indexOf("*/", index + 2);
                index = end < 0 ? source.length() : end + 2;
                continue;
            }
            if (value == '\'' || value == '"') {
                char quote = value;
                index++;
                while (index < source.length()) {
                    char current = source.charAt(index++);
                    if (current == '\\' && index < source.length()) index++;
                    else if (current == quote) break;
                }
                continue;
            }
            if (Character.isJavaIdentifierStart(value)) {
                int end = index + 1;
                while (end < source.length() && Character.isJavaIdentifierPart(source.charAt(end))) end++;
                tokens.add(source.substring(index, end));
                index = end;
            } else {
                tokens.add(String.valueOf(value));
                index++;
            }
        }
        return List.copyOf(tokens);
    }

    private static void classifyAnnotations(SpringFrameworkEvidence frameworkEvidence, Accumulator output) {
        for (Map.Entry<ContentDigest, AnnotationCandidate> entry : output.annotationCandidates.entrySet()) {
            RawObservation raw = output.raw.stream().filter(value -> value.identity().equals(entry.getKey()))
                    .findFirst().orElseThrow();
            classifyAnnotation(raw, entry.getValue(), output.declarations, frameworkEvidence, output);
        }
    }

    private static AnnotationCandidate candidate(AnnotationUseRecord annotation, ObservationRecord observation,
            Map<OccurrenceIdentity, RelationshipOccurrence> occurrences,
            Map<EntityIdentity, DeclarationRecord> declarations) {
        Optional<EntityIdentity> target = resolvedTarget(observation, occurrences);
        String reference = target.map(declarations::get).map(value -> value.entity().canonicalName())
                .orElse(observation.reference());
        return new AnnotationCandidate(annotation.span().document(), Optional.of(annotation.span()),
                Optional.of(annotation.owner()), target, map(observation.attribution()), reference,
                annotation.spelling(), observation.diagnostics(), "");
    }

    private static AnnotationCandidate candidate(ObservationRecord observation,
            Map<OccurrenceIdentity, RelationshipOccurrence> occurrences,
            Map<EntityIdentity, DeclarationRecord> declarations) {
        Optional<RelationshipOccurrence> occurrence = observation.mappedOccurrence().map(occurrences::get);
        Optional<EntityIdentity> owner = occurrence.map(value -> value.relationship().source());
        Optional<EntityIdentity> target = resolvedTarget(observation, occurrences);
        String reference = target.map(declarations::get).map(value -> value.entity().canonicalName())
                .orElse(observation.reference());
        return new AnnotationCandidate(observation.document(), observation.span(), owner, target,
                map(observation.attribution()), reference, observation.reference(), observation.diagnostics(),
                "The annotation observation had no corresponding M2 annotation-use row or owner evidence.");
    }

    private static Optional<EntityIdentity> resolvedTarget(ObservationRecord observation,
            Map<OccurrenceIdentity, RelationshipOccurrence> occurrences) {
        return observation.mappedOccurrence().map(occurrences::get).map(RelationshipOccurrence::relationship)
                .map(SemanticRelationship::target).filter(RelationshipTarget.Resolved.class::isInstance)
                .map(RelationshipTarget.Resolved.class::cast).map(RelationshipTarget.Resolved::target);
    }

    private static void classifyAnnotation(RawObservation raw, AnnotationCandidate candidate,
            Map<EntityIdentity, DeclarationRecord> declarations,
            SpringFrameworkEvidence frameworkEvidence, Accumulator output) {
        if (raw.evidenceState() != EvidenceState.VERIFIED || raw.resolvedTarget().isEmpty()) {
            ProblemReason reason = candidate.problem().isEmpty()
                    ? ProblemReason.UNCLASSIFIED_MECHANISM
                    : ProblemReason.ANNOTATION_SEMANTICS_NOT_ADJUDICATED;
            addUnclassified(raw, reason,
                    candidate.problem().isEmpty()
                            ? "The exact annotation type was not resolved by the supplied M2 evidence."
                            : candidate.problem(), output);
            return;
        }
        DeclarationRecord target = declarations.get(raw.resolvedTarget().orElseThrow());
        if (target == null) {
            addUnclassified(raw, ProblemReason.UNCLASSIFIED_MECHANISM,
                    "The resolved annotation target is absent from the supplied declaration ledger.", output);
            return;
        }
        String annotationKey = target.entity().canonicalName();
        if (SpringMechanismCatalog.isNonSpringMarker(annotationKey)
                && target.entity().origin() == EntityOrigin.JDK) {
            output.markers.add(raw.identity());
            return;
        }
        List<SpringMechanismCatalog.Entry> entries = SpringMechanismCatalog.entriesForAnnotationKey(annotationKey);
        if (!entries.isEmpty() && (target.entity().origin() != EntityOrigin.DEPENDENCY
                || !frameworkEvidence.containsEntityScope(target.entity().stableScope()))) {
            addUnclassified(raw, ProblemReason.ANNOTATION_SEMANTICS_NOT_ADJUDICATED,
                    "The resolved FQN is not bound to an exact artifact in the supplied M3 classpath manifest.",
                    output);
            return;
        }
        SpringMechanismCatalog.Entry selected = selectAnnotationFamily(entries, raw.owner(), declarations);
        if (selected == null && entries.isEmpty()) {
            List<SpringMechanismCatalog.Entry> composed = composedAnnotationFamilies(
                    raw.resolvedTarget().orElseThrow(), raw.owner(), declarations, frameworkEvidence, output);
            if (!composed.isEmpty()) {
                int ordinal = 0;
                for (SpringMechanismCatalog.Entry family : composed) {
                    String mechanism = family.id().equals("spring.bean.stereotype.direct")
                            ? "spring.bean.stereotype.composed" : family.id();
                    output.obligations.add(SemanticObligation.create(raw.identity(), ordinal++, mechanism,
                            mechanism + ".composed-annotation", List.of(
                                    "spring.annotation.composed", "spring.raw.annotation-use"),
                            Classification.CLASSIFIED));
                }
                if (output.incompleteAnnotationDeclarations.contains(raw.resolvedTarget().orElseThrow())) {
                    output.problems.add(problem(ProblemReason.ANNOTATION_GRAPH_INCOMPLETE,
                            "spring.bean.stereotype.composed", observationSubject(raw),
                            Optional.of(raw.identity()), raw.span().stream().toList(), raw.diagnostics(),
                            "The composed annotation family is detected, but its declaration graph contains a cycle or incomplete edge."));
                }
                return;
            }
        }
        if (selected == null) {
            ProblemReason reason = entries.isEmpty()
                    && output.annotationDeclarationsByEntity.containsKey(raw.resolvedTarget().orElseThrow())
                    ? ProblemReason.ANNOTATION_GRAPH_INCOMPLETE
                    : ProblemReason.ANNOTATION_SEMANTICS_NOT_ADJUDICATED;
            addUnclassified(raw, reason,
                    "The exact annotation declaration graph/use role is incomplete or not adjudicated by spring-mechanisms:v2.", output);
            return;
        }
        List<String> tags = SpringMechanismCatalog.isBootAnnotationKey(annotationKey)
                ? List.of("spring.raw.annotation-use", "spring.source.boot-annotation")
                : List.of("spring.raw.annotation-use");
        output.obligations.add(SemanticObligation.create(raw.identity(), 0, selected.id(),
                selected.id() + ".annotation", tags, Classification.CLASSIFIED));
    }

    private static SpringMechanismCatalog.Entry selectAnnotationFamily(
            List<SpringMechanismCatalog.Entry> entries, Optional<EntityIdentity> owner,
            Map<EntityIdentity, DeclarationRecord> declarations) {
        if (entries.size() == 1) return entries.getFirst();
        if (entries.isEmpty() || owner.isEmpty() || !entries.stream().allMatch(value ->
                value.id().equals("spring.injection.constructor.explicit")
                        || value.id().equals("spring.injection.field")
                        || value.id().equals("spring.injection.method"))) return null;
        DeclarationRecord declaration = declarations.get(owner.orElseThrow());
        if (declaration == null) return null;
        String family = switch (declaration.entity().kind()) {
            case CONSTRUCTOR -> "spring.injection.constructor.explicit";
            case FIELD -> "spring.injection.field";
            case METHOD -> "spring.injection.method";
            default -> "";
        };
        return entries.stream().filter(value -> value.id().equals(family)).findFirst().orElse(null);
    }

    private static void addUnclassified(RawObservation raw, ProblemReason reason,
            String limitation, Accumulator output) {
        output.obligations.add(SemanticObligation.create(raw.identity(), 0, UNCLASSIFIED,
                "spring.mechanism.unclassified.annotation", List.of("spring.raw.annotation-use"),
                Classification.UNCLASSIFIED));
        output.problems.add(problem(reason, UNCLASSIFIED,
                new EvidenceSubject(EvidenceSubject.Kind.OBSERVATION, raw.identity().value()),
                Optional.of(raw.identity()), raw.span().stream().toList(), raw.diagnostics(), limitation));
    }

    private static void scanJavaMechanisms(FrontendResult frontend,
            SpringFrameworkEvidence frameworkEvidence, Accumulator output) {
        scanTypeMechanisms(frontend, frameworkEvidence, output);
        scanCallableMechanisms(frontend, frameworkEvidence, output);
        scanInjectionDeclarations(frontend, frameworkEvidence, output);
        scanAggregateTypes(frontend, frameworkEvidence, output);
        for (SemanticObligation obligation : output.obligations.stream().toList()) {
            ProblemReason reason = switch (obligation.primaryMechanism()) {
                case "spring.injection.constructor.generated" -> ProblemReason.GENERATED_MEMBER_NOT_ACQUIRED;
                case "spring.registration.spring-data" -> ProblemReason.REPOSITORY_REGISTRATION_UNPROVED;
                default -> null;
            };
            if (reason == null) continue;
            RawObservation raw = raw(output, obligation.rawObservationIdentity());
            addMechanismProblem(raw, obligation.primaryMechanism(), reason,
                    reason == ProblemReason.GENERATED_MEMBER_NOT_ACQUIRED
                            ? "The generator annotation is detected, but no constructor/member is synthesized without exact generator version, configuration and generated-source or bytecode evidence."
                            : "The Spring Data marker is detected, but enablement, scan filters, store binding, exclusions and factory/proxy evidence are not established.",
                    output);
        }
    }

    private static void scanTypeMechanisms(FrontendResult frontend,
            SpringFrameworkEvidence frameworkEvidence, Accumulator output) {
        List<RelationshipOccurrence> occurrences = frontend.occurrences().stream()
                .filter(value -> Set.of("java.extends", "java.implements")
                        .contains(value.relationship().kind().value()))
                .filter(value -> value.relationship().target() instanceof RelationshipTarget.Resolved)
                .toList();
        TreeMap<EntityIdentity, List<EntityIdentity>> adjacency = new TreeMap<>();
        java.util.stream.Stream.concat(occurrences.stream().map(RelationshipOccurrence::relationship),
                        frontend.derivedRelationships().stream().map(DerivedRelationshipRecord::relationship))
                .filter(value -> Set.of("java.extends", "java.implements").contains(value.kind().value()))
                .filter(value -> value.target() instanceof RelationshipTarget.Resolved)
                .forEach(value -> adjacency.computeIfAbsent(value.source(), ignored -> new ArrayList<>())
                        .add(((RelationshipTarget.Resolved) value.target()).target()));
        adjacency.values().forEach(values -> values.sort(Comparator.naturalOrder()));

        for (RelationshipOccurrence occurrence : occurrences) {
            EntityIdentity target = ((RelationshipTarget.Resolved) occurrence.relationship().target()).target();
            List<TypeMechanism> mechanisms = reachableTypeMechanisms(target, adjacency,
                    frameworkEvidence, output.declarations);
            Optional<TypeMechanism> recognized = recognizedTypeMechanism(target, output.declarations);
            if (mechanisms.isEmpty() && recognized.isEmpty()) continue;
            RawObservation raw = relationshipRaw(frontend, occurrence, output.declarations);
            output.raw.add(raw);
            if (mechanisms.isEmpty()) {
                addUnclassifiedJava(raw,
                        "The type has a registered Spring mechanism FQN but is not bound to an exact supplied dependency artifact.",
                        output);
                continue;
            }
            int ordinal = 0;
            for (TypeMechanism mechanism : mechanisms) {
                output.obligations.add(SemanticObligation.create(raw.identity(), ordinal++,
                        mechanism.family(), mechanism.family() + ".type-hierarchy",
                        List.of("spring.raw.type-relationship"), Classification.CLASSIFIED));
                addMechanismProblem(raw, mechanism.family(), mechanism.reason(), mechanism.limitation(), output);
            }
        }
    }

    private static List<TypeMechanism> reachableTypeMechanisms(EntityIdentity start,
            Map<EntityIdentity, List<EntityIdentity>> adjacency, SpringFrameworkEvidence frameworkEvidence,
            Map<EntityIdentity, DeclarationRecord> declarations) {
        TreeMap<String, TypeMechanism> matches = new TreeMap<>();
        TreeSet<EntityIdentity> visited = new TreeSet<>();
        ArrayDeque<EntityIdentity> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            EntityIdentity current = queue.removeFirst();
            if (!visited.add(current)) continue;
            recognizedTypeMechanism(current, declarations).filter(value ->
                    isExactDependencyType(current, frameworkEvidence, declarations))
                    .ifPresent(value -> matches.put(value.family(), value));
            adjacency.getOrDefault(current, List.of()).forEach(queue::addLast);
        }
        return List.copyOf(matches.values());
    }

    private static Optional<TypeMechanism> recognizedTypeMechanism(EntityIdentity identity,
            Map<EntityIdentity, DeclarationRecord> declarations) {
        DeclarationRecord declaration = declarations.get(identity);
        if (declaration == null || declaration.entity().kind() != EntityKind.TYPE) return Optional.empty();
        String name = declaration.entity().canonicalName();
        if (matchesAnyType(name, Set.of("org.springframework.beans.factory.FactoryBean"))) {
            return Optional.of(new TypeMechanism("spring.registration.factory",
                    ProblemReason.FACTORY_PRODUCT_TYPE_UNKNOWN,
                    "FactoryBean participation is detected, but its product type, names and registration region require bytecode/configuration evidence."));
        }
        if (matchesAnyType(name, SPRING_DATA_TYPES)) {
            return Optional.of(new TypeMechanism("spring.registration.spring-data",
                    ProblemReason.REPOSITORY_REGISTRATION_UNPROVED,
                    "Repository ancestry is detected, but enablement, scan/store/exclusion and factory/proxy evidence remain required."));
        }
        if (matchesAnyType(name, PROGRAMMATIC_SPI_TYPES)) {
            return Optional.of(new TypeMechanism("spring.registration.programmatic",
                    ProblemReason.DYNAMIC_REGISTRY_MUTATION,
                    "A registry/factory extension SPI is implemented; its programmatic mutations are not executed or assumed inert."));
        }
        if (matchesAnyType(name, FRAMEWORK_ENTRYPOINT_TYPES)) {
            return Optional.of(new TypeMechanism("spring.entrypoint.framework",
                    ProblemReason.ENTRYPOINT_OR_LIFECYCLE_UNMODELED,
                    "A framework callback SPI is implemented; callback registration, activation and lifecycle order remain unresolved."));
        }
        return Optional.empty();
    }

    private static void scanCallableMechanisms(FrontendResult frontend,
            SpringFrameworkEvidence frameworkEvidence, Accumulator output) {
        for (RelationshipOccurrence occurrence : frontend.occurrences()) {
            if (!occurrence.relationship().kind().value().equals("java.calls")
                    || !(occurrence.relationship().target() instanceof RelationshipTarget.Resolved resolved)) continue;
            DeclarationRecord target = output.declarations.get(resolved.target());
            if (target == null || target.entity().kind() != EntityKind.METHOD) continue;
            Optional<CallableMechanism> recognized = recognizedCallable(target);
            if (recognized.isEmpty()) continue;
            RawObservation raw = relationshipRaw(frontend, occurrence, output.declarations);
            output.raw.add(raw);
            if (!isExactDependencyType(target.entity().identity(), frameworkEvidence, output.declarations)) {
                addUnclassifiedJava(raw,
                        "The callable has a registered Spring API signature but its declaration is not bound to an exact supplied dependency artifact.",
                        output);
                continue;
            }
            CallableMechanism mechanism = recognized.orElseThrow();
            output.obligations.add(SemanticObligation.create(raw.identity(), 0, mechanism.family(),
                    mechanism.family() + ".api-call", List.of("spring.raw.call"), Classification.CLASSIFIED));
            addMechanismProblem(raw, mechanism.family(), mechanism.reason(), mechanism.limitation(), output);
        }
    }

    private static Optional<CallableMechanism> recognizedCallable(DeclarationRecord target) {
        String canonical = target.entity().canonicalName();
        if (memberOfAnyMethod(canonical, PROGRAMMATIC_CALL_OWNER_TYPES,
                Set.of("loadBeanDefinitions", "register", "registerAlias", "registerBean",
                        "registerBeanDefinition", "registerSingleton", "removeAlias",
                        "removeBeanDefinition", "scan"))) {
            return Optional.of(new CallableMechanism("spring.registration.programmatic",
                    ProblemReason.DYNAMIC_REGISTRY_MUTATION,
                    "The exact registration API call is retained, but suppliers, processors and resulting definition state require configuration/build/runtime evidence."));
        }
        if (memberOfAnyMethod(canonical, CONTAINER_LOOKUP_CALL_OWNER_TYPES,
                Set.of("getBean", "getBeanNamesForType", "getBeanProvider", "getBeansOfType",
                        "getBeansWithAnnotation", "getIfAvailable", "getIfUnique", "getObject",
                        "orderedStream", "stream"))) {
            return Optional.of(new CallableMechanism("spring.lookup.container",
                    ProblemReason.DYNAMIC_LOOKUP_TARGET,
                    "The exact container/provider lookup call is retained, but its runtime target set and configuration region are not statically fabricated."));
        }
        return Optional.empty();
    }

    private static void scanInjectionDeclarations(FrontendResult frontend,
            SpringFrameworkEvidence frameworkEvidence, Accumulator output) {
        Map<EntityIdentity, EntityIdentity> containers = declarationContainers(frontend);
        Map<EntityIdentity, List<EntityIdentity>> parameters = callableParameters(frontend);
        Set<EntityIdentity> beanMethods = annotationOwnersForType(
                "org.springframework.context.annotation.Bean", frameworkEvidence, output);
        Set<EntityIdentity> injectedCallables = output.obligations.stream()
                .filter(value -> Set.of("spring.injection.constructor.explicit", "spring.injection.method",
                        "spring.injection.resource")
                        .contains(value.primaryMechanism()))
                .map(value -> raw(output, value.rawObservationIdentity()).owner()).flatMap(Optional::stream)
                .filter(identity -> {
                    DeclarationRecord declaration = output.declarations.get(identity);
                    return declaration != null && (declaration.entity().kind() == EntityKind.CONSTRUCTOR
                            || declaration.entity().kind() == EntityKind.METHOD);
                })
                .collect(java.util.stream.Collectors.toSet());

        for (EntityIdentity beanMethod : beanMethods) {
            for (EntityIdentity parameter : parameters.getOrDefault(beanMethod, List.of())) {
                DeclarationRecord declaration = output.declarations.get(parameter);
                if (declaration == null) {
                    output.problems.add(problem(ProblemReason.PARAMETER_EVIDENCE_INCOMPLETE,
                            "spring.injection.bean-parameter",
                            new EvidenceSubject(EvidenceSubject.Kind.ENTITY, parameter.value()), Optional.empty(),
                            List.of(), List.of(), "A @Bean parameter identity lacks declaration evidence."));
                    continue;
                }
                RawObservation raw = declarationRaw(declaration, Optional.of(beanMethod),
                        "spring.bean-parameter:" + declaration.entity().canonicalName(), output);
                output.obligations.add(SemanticObligation.create(raw.identity(), 0,
                        "spring.injection.bean-parameter", "spring.injection.bean-parameter.declaration",
                        List.of("spring.raw.java-declaration"), Classification.CLASSIFIED));
                output.injectionTypeOwners.add(parameter);
                if (!hasCompleteParameterType(frontend, parameter)) {
                    output.problems.add(problem(ProblemReason.PARAMETER_EVIDENCE_INCOMPLETE,
                            "spring.injection.bean-parameter",
                            new EvidenceSubject(EvidenceSubject.Kind.ENTITY, parameter.value()),
                            Optional.of(raw.identity()), raw.span().stream().toList(), raw.diagnostics(),
                            "The @Bean parameter declaration is retained, but its written type is missing or unresolved."));
                }
            }
        }
        for (EntityIdentity callable : injectedCallables) {
            output.injectionTypeOwners.addAll(parameters.getOrDefault(callable, List.of()));
        }
        output.obligations.stream()
                .filter(value -> Set.of("spring.injection.field", "spring.injection.resource")
                        .contains(value.primaryMechanism()))
                .map(value -> raw(output, value.rawObservationIdentity()).owner()).flatMap(Optional::stream)
                .filter(identity -> {
                    DeclarationRecord declaration = output.declarations.get(identity);
                    return declaration != null && declaration.entity().kind() == EntityKind.FIELD;
                })
                .forEach(output.injectionTypeOwners::add);

        Set<EntityIdentity> beanTypes = output.obligations.stream()
                .filter(value -> Set.of("spring.bean.stereotype.direct", "spring.bean.stereotype.composed",
                        "spring.bean.factory-method", "spring.registration.auto-configuration")
                        .contains(value.primaryMechanism()))
                .map(value -> raw(output, value.rawObservationIdentity()).owner()).flatMap(Optional::stream)
                .filter(identity -> {
                    DeclarationRecord declaration = output.declarations.get(identity);
                    return declaration != null && declaration.entity().kind() == EntityKind.TYPE
                            && isInstantiableSourceType(declaration);
                }).collect(java.util.stream.Collectors.toSet());
        Set<EntityIdentity> generatedTypes = output.obligations.stream()
                .filter(value -> value.primaryMechanism().equals("spring.injection.constructor.generated"))
                .map(value -> raw(output, value.rawObservationIdentity()).owner()).flatMap(Optional::stream)
                .collect(java.util.stream.Collectors.toSet());

        for (EntityIdentity beanType : new TreeSet<>(beanTypes)) {
            List<DeclarationRecord> constructors = output.declarations.values().stream()
                    .filter(value -> value.entity().kind() == EntityKind.CONSTRUCTOR)
                    .filter(value -> beanType.equals(containers.get(value.entity().identity())))
                    .sorted().toList();
            boolean explicit = constructors.stream().anyMatch(value -> injectedCallables.contains(value.entity().identity()));
            boolean exactConstructorSet = constructors.stream().allMatch(value ->
                    value.status() == SemanticStatus.RESOLVED && value.entity().declaration().isPresent());
            if (constructors.size() == 1 && exactConstructorSet
                    && !explicit && !generatedTypes.contains(beanType)) {
                DeclarationRecord constructor = constructors.getFirst();
                RawObservation raw = declarationRaw(constructor, Optional.of(beanType),
                        "spring.implicit-constructor:" + constructor.entity().canonicalName(), output);
                output.obligations.add(SemanticObligation.create(raw.identity(), 0,
                        "spring.injection.constructor.implicit",
                        "spring.injection.constructor.implicit.declaration",
                        List.of("spring.raw.java-declaration"), Classification.CLASSIFIED));
                output.injectionTypeOwners.addAll(parameters.getOrDefault(constructor.entity().identity(), List.of()));
            } else if (!explicit && (constructors.isEmpty() || !exactConstructorSet
                    || generatedTypes.contains(beanType))) {
                output.problems.add(problem(generatedTypes.contains(beanType)
                                ? ProblemReason.GENERATED_MEMBER_NOT_ACQUIRED
                                : ProblemReason.CONSTRUCTOR_SET_INCOMPLETE,
                        generatedTypes.contains(beanType) ? "spring.injection.constructor.generated"
                                : "spring.injection.constructor.implicit",
                        new EvidenceSubject(EvidenceSubject.Kind.ENTITY, beanType.value()), Optional.empty(),
                        output.declarations.get(beanType).entity().declaration().stream().toList(), List.of(),
                        "The exact constructor set is incomplete; no implicit injection point is invented."));
            }
        }
    }

    private static boolean hasCompleteParameterType(FrontendResult frontend, EntityIdentity parameter) {
        List<TypeUseRecord> parameterTypes = frontend.types().stream()
                .filter(value -> value.owner().equals(Optional.of(parameter)))
                .filter(value -> value.role().value().equals("java.parameter-type"))
                .toList();
        return parameterTypes.size() == 1
                && parameterTypes.getFirst().type().status() == SemanticStatus.RESOLVED;
    }

    private static Map<EntityIdentity, List<EntityIdentity>> callableParameters(FrontendResult frontend) {
        TreeMap<EntityIdentity, List<EntityIdentity>> result = new TreeMap<>();
        java.util.stream.Stream.concat(frontend.occurrences().stream().map(RelationshipOccurrence::relationship),
                        frontend.derivedRelationships().stream().map(DerivedRelationshipRecord::relationship))
                .filter(value -> value.kind().value().equals("java.has-parameter"))
                .filter(value -> value.target() instanceof RelationshipTarget.Resolved)
                .forEach(value -> result.computeIfAbsent(value.source(), ignored -> new ArrayList<>())
                        .add(((RelationshipTarget.Resolved) value.target()).target()));
        result.values().forEach(values -> values.sort(Comparator.naturalOrder()));
        return Collections.unmodifiableMap(result);
    }

    private static Set<EntityIdentity> annotationOwnersForType(String canonicalType,
            SpringFrameworkEvidence frameworkEvidence, Accumulator output) {
        return output.annotationCandidates.values().stream()
                .filter(value -> value.state() == EvidenceState.VERIFIED)
                .filter(value -> value.resolvedTarget().stream().anyMatch(target ->
                        isExactType(target, canonicalType, frameworkEvidence, output.declarations,
                                EntityOrigin.DEPENDENCY)))
                .flatMap(value -> value.owner().stream()).collect(java.util.stream.Collectors.toSet());
    }

    private static void scanAggregateTypes(FrontendResult frontend,
            SpringFrameworkEvidence frameworkEvidence, Accumulator output) {
        int ordinal = 0;
        for (TypeUseRecord type : frontend.types()) {
            if (type.owner().isEmpty() || !output.injectionTypeOwners.contains(type.owner().orElseThrow())) continue;
            AggregateMatch match = aggregateMatch(type.type(), frameworkEvidence, output.declarations);
            if (match == AggregateMatch.NONE) continue;
            RawObservation raw = RawObservation.create(RawKind.JAVA_TYPE_USE, type.span().document(),
                    Optional.of(type.span()), ordinal++, type.owner(), rootTypeTarget(type.type()),
                    map(type.type().status()), "spring.aggregate-type:" + type.type().spelling(),
                    type.type().spelling(), List.of());
            output.raw.add(raw);
            if (match == AggregateMatch.UNTRUSTED) {
                addUnclassifiedJava(raw,
                        "The aggregate/provider spelling matches a registered shape but its type identity is not exact trusted evidence.",
                        output);
                continue;
            }
            output.obligations.add(SemanticObligation.create(raw.identity(), 0,
                    "spring.injection.aggregate", "spring.injection.aggregate.type-shape",
                    List.of("spring.raw.type-use"), Classification.CLASSIFIED));
            addMechanismProblem(raw, "spring.injection.aggregate",
                    ProblemReason.AGGREGATE_OR_PROVIDER_UNMODELED,
                    "The exact aggregate/provider shape is retained, but candidate expansion, ordering, laziness and optionality require later binding semantics.",
                    output);
        }
    }

    private static AggregateMatch aggregateMatch(JavaType type, SpringFrameworkEvidence frameworkEvidence,
            Map<EntityIdentity, DeclarationRecord> declarations) {
        if (type.kind() == JavaType.Kind.ARRAY) return AggregateMatch.EXACT;
        if (type.kind() != JavaType.Kind.DECLARED || type.target().isEmpty()) return AggregateMatch.NONE;
        EntityIdentity target = type.target().orElseThrow();
        DeclarationRecord declaration = declarations.get(target);
        if (declaration == null) return AggregateMatch.NONE;
        String canonical = declaration.entity().canonicalName();
        if (matchesAnyType(canonical, AGGREGATE_JDK_TYPES)) {
            return declaration.entity().origin() == EntityOrigin.JDK
                    ? AggregateMatch.EXACT : AggregateMatch.UNTRUSTED;
        }
        if (matchesAnyType(canonical, AGGREGATE_DEPENDENCY_TYPES)) {
            return declaration.entity().origin() == EntityOrigin.DEPENDENCY
                    && frameworkEvidence.containsEntityScope(declaration.entity().stableScope())
                    ? AggregateMatch.EXACT : AggregateMatch.UNTRUSTED;
        }
        return AggregateMatch.NONE;
    }

    private static Optional<EntityIdentity> rootTypeTarget(JavaType type) {
        if (type.target().isPresent()) return type.target();
        return type.kind() == JavaType.Kind.ARRAY && !type.components().isEmpty()
                ? rootTypeTarget(type.components().getFirst()) : Optional.empty();
    }

    private static RawObservation declarationRaw(DeclarationRecord declaration,
            Optional<EntityIdentity> owner, String reference, Accumulator output) {
        RawObservation prior = output.javaDeclarationRows.get(declaration.entity().identity());
        if (prior != null) return prior;
        Optional<SourceSpan> span = declaration.entity().declaration();
        SourceDocumentIdentity document = span.map(SourceSpan::document)
                .or(() -> owner.map(output.declarations::get).flatMap(value -> value.entity().declaration())
                        .map(SourceSpan::document))
                .orElseThrow(() -> new IllegalArgumentException("Java declaration evidence lacks a source document"));
        RawObservation raw = RawObservation.create(RawKind.JAVA_DECLARATION, document, span,
                output.javaDeclarationRows.size(), owner, Optional.of(declaration.entity().identity()),
                map(declaration.status()), reference, declaration.spelling(), declaration.diagnostics());
        output.raw.add(raw);
        output.javaDeclarationRows.put(declaration.entity().identity(), raw);
        return raw;
    }

    private static RawObservation relationshipRaw(FrontendResult frontend,
            RelationshipOccurrence occurrence, Map<EntityIdentity, DeclarationRecord> declarations) {
        ObservationRecord observation = frontend.observations().stream()
                .filter(value -> value.mappedOccurrence().equals(Optional.of(occurrence.identity())))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "M2 relationship occurrence lacks its authoritative observation row"));
        Optional<EntityIdentity> target = occurrence.relationship().target() instanceof RelationshipTarget.Resolved resolved
                ? Optional.of(resolved.target()) : Optional.empty();
        String reference = target.map(declarations::get).filter(Objects::nonNull)
                .map(value -> value.entity().canonicalName()).orElse(observation.reference());
        return RawObservation.create(RawKind.JAVA_RELATIONSHIP, occurrence.span().document(),
                Optional.of(occurrence.span()), occurrence.ordinal(),
                Optional.of(occurrence.relationship().source()), target, map(occurrence.status()),
                reference, observation.reference(), observation.diagnostics());
    }

    private static void addMechanismProblem(RawObservation raw, String family, ProblemReason reason,
            String limitation, Accumulator output) {
        Problem candidate = problem(reason, family, observationSubject(raw), Optional.of(raw.identity()),
                raw.span().stream().toList(), raw.diagnostics(), limitation);
        if (!output.problems.contains(candidate)) output.problems.add(candidate);
    }

    private static void addUnclassifiedJava(RawObservation raw, String limitation, Accumulator output) {
        output.obligations.add(SemanticObligation.create(raw.identity(), 0, UNCLASSIFIED,
                "spring.mechanism.unclassified.java", List.of("spring.raw.java"),
                Classification.UNCLASSIFIED));
        output.problems.add(problem(ProblemReason.UNCLASSIFIED_MECHANISM, UNCLASSIFIED,
                observationSubject(raw), Optional.of(raw.identity()), raw.span().stream().toList(),
                raw.diagnostics(), limitation));
    }

    private static RawObservation raw(Accumulator output, ContentDigest identity) {
        return output.raw.stream().filter(value -> value.identity().equals(identity))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Missing Spring raw observation"));
    }

    private static boolean isExactDependencyType(EntityIdentity identity,
            SpringFrameworkEvidence frameworkEvidence, Map<EntityIdentity, DeclarationRecord> declarations) {
        DeclarationRecord declaration = declarations.get(identity);
        return declaration != null && declaration.entity().origin() == EntityOrigin.DEPENDENCY
                && frameworkEvidence.containsEntityScope(declaration.entity().stableScope());
    }

    private static boolean isExactType(EntityIdentity identity, String canonicalType,
            SpringFrameworkEvidence frameworkEvidence, Map<EntityIdentity, DeclarationRecord> declarations,
            EntityOrigin expectedOrigin) {
        DeclarationRecord declaration = declarations.get(identity);
        if (declaration == null || declaration.entity().origin() != expectedOrigin
                || !declaration.entity().canonicalName().equals(annotationKey(canonicalType))) return false;
        return expectedOrigin != EntityOrigin.DEPENDENCY
                || frameworkEvidence.containsEntityScope(declaration.entity().stableScope());
    }

    private static boolean matchesAnyType(String canonicalName, Set<String> types) {
        return types.stream().map(SpringMechanismScanner::annotationKey).anyMatch(canonicalName::equals);
    }

    private static boolean memberOfAnyMethod(String canonicalMember, Set<String> ownerTypes,
            Set<String> methods) {
        return ownerTypes.stream().anyMatch(owner -> methods.stream()
                .anyMatch(method -> methodOf(canonicalMember, owner, method)));
    }

    private static boolean methodOf(String canonicalMember, String ownerType, String method) {
        String type = annotationKey(ownerType);
        String tuple = type.substring("java:v1:".length());
        return canonicalMember.startsWith("java:v1:[\"method\"," + tuple + ",\"" + method + "\",");
    }

    private static String annotationKey(String canonicalType) {
        int separator = canonicalType.lastIndexOf('.');
        return JavaSymbolName.topLevelType(canonicalType.substring(0, separator),
                canonicalType.substring(separator + 1)).canonicalName();
    }

    private enum AggregateMatch { NONE, EXACT, UNTRUSTED }
    private enum SourceTypeKind { ANNOTATION, CLASS, INTERFACE, RECORD, ENUM, UNKNOWN }
    private record TypeMechanism(String family, ProblemReason reason, String limitation) {}
    private record CallableMechanism(String family, ProblemReason reason, String limitation) {}

    private static void scanXml(SourceInput input, Accumulator output) {
        List<XmlStartTag> tags;
        List<XmlToken> doctypes;
        List<XmlEvent> events;
        try {
            XmlLexicalInventory lexical = lexXml(input.text());
            tags = lexical.tags();
            doctypes = lexical.doctypes();
            events = parseXml(withoutDoctypes(input.text(), doctypes), tags);
            if (events.size() != tags.size()) throw new XMLStreamException("XML lexical/parser start-tag counts differ");
        } catch (RuntimeException | XMLStreamException failure) {
            RawObservation document = resourceDocument(input, RawKind.XML_DOCUMENT, 0,
                    "spring.xml.invalid-document", "<xml-document>", EvidenceState.ERROR);
            output.raw.add(document);
            output.obligations.add(SemanticObligation.create(document.identity(), 0, UNCLASSIFIED,
                    "spring.mechanism.unclassified.xml", List.of("spring.raw.xml-document"),
                    Classification.UNCLASSIFIED));
            output.problems.add(problem(ProblemReason.UNCLASSIFIED_MECHANISM, UNCLASSIFIED,
                    observationSubject(document), Optional.of(document.identity()), document.span().stream().toList(),
                    List.of(), "The supplied XML document could not be safely inventoried as a Spring document."));
            output.problems.add(problem(ProblemReason.UNCLASSIFIED_MECHANISM, XML_FAMILY,
                    observationSubject(document), Optional.of(document.identity()), document.span().stream().toList(),
                    List.of(), "Secure XML parsing failed; no inner XML occurrence is claimed complete."));
            return;
        }

        boolean legacyBeans = !events.isEmpty() && events.getFirst().namespace().isEmpty()
                && events.getFirst().localName().equals("beans");
        boolean beansDocument = !events.isEmpty()
                && (legacyBeans || events.getFirst().namespace().equals(BEANS_NAMESPACE));
        RawObservation document = resourceDocument(input, RawKind.XML_DOCUMENT, 0,
                beansDocument ? "spring.xml.beans-document" : "spring.xml.unknown-document",
                "<xml-document>", EvidenceState.VERIFIED);
        output.raw.add(document);
        classifyXml(document, beansDocument, false, "The XML root is not a recognized Spring beans document.", output);

        int ordinal = 1;
        for (XmlToken doctype : doctypes) {
            RawObservation raw = xmlRaw(input, RawKind.XML_DOCUMENT_TYPE, doctype, ordinal++,
                    "spring.xml.document-type", EvidenceState.VERIFIED);
            output.raw.add(raw);
            classifyXml(raw, beansDocument, false, "The document type belongs to an unrecognized XML document.", output);
            if (containsExternalIdentifier(doctype.spelling())) {
                output.problems.add(problem(ProblemReason.UNCLASSIFIED_MECHANISM, XML_FAMILY,
                        observationSubject(raw), Optional.of(raw.identity()), raw.span().stream().toList(), List.of(),
                        "External XML resources are never fetched; exact local DTD/schema evidence is required."));
            }
        }
        for (int index = 0; index < tags.size(); index++) {
            XmlStartTag tag = tags.get(index);
            XmlEvent event = events.get(index);
            boolean recognizedElement = beansDocument && BEANS_ELEMENTS.contains(event.localName())
                    && (legacyBeans && event.namespace().isEmpty() || event.namespace().equals(BEANS_NAMESPACE));
            RawObservation element = xmlRaw(input, RawKind.XML_ELEMENT, tag.element(), ordinal++,
                    qName(event.namespace(), event.localName()), EvidenceState.VERIFIED);
            output.raw.add(element);
            classifyXml(element, recognizedElement, false,
                    "The XML element namespace is not covered by the bounded beans XML family.", output);
            for (XmlToken attribute : tag.attributes()) {
                XmlAttribute resolved = resolveAttribute(attribute, event);
                boolean marker = XML_MARKER_NAMESPACES.contains(resolved.namespace())
                        || resolved.namespaceDeclaration() && resolved.namespace().equals(BEANS_NAMESPACE);
                boolean recognizedAttribute = recognizedElement && !resolved.namespaceDeclaration()
                        && (resolved.namespace().isEmpty() || resolved.namespace().equals(BEANS_NAMESPACE)
                                || XML_MARKER_NAMESPACES.contains(resolved.namespace()));
                RawObservation raw = xmlRaw(input, RawKind.XML_ATTRIBUTE, attribute, ordinal++,
                        resolved.stableReference(), EvidenceState.VERIFIED);
                output.raw.add(raw);
                classifyXml(raw, recognizedAttribute, marker,
                        "The XML attribute or namespace declaration is not covered by the bounded beans XML family.", output);
            }
        }
    }

    private static void classifyXml(RawObservation raw, boolean recognized, boolean marker,
            String limitation, Accumulator output) {
        if (marker) {
            output.markers.add(raw.identity());
        } else if (recognized) {
            output.obligations.add(SemanticObligation.create(raw.identity(), 0, XML_FAMILY,
                    XML_FAMILY + "." + raw.kind().name().toLowerCase(Locale.ROOT).replace('_', '-'),
                    List.of("spring.raw.xml"), Classification.CLASSIFIED));
        } else {
            output.obligations.add(SemanticObligation.create(raw.identity(), 0, UNCLASSIFIED,
                    "spring.mechanism.unclassified.xml", List.of("spring.raw.xml"),
                    Classification.UNCLASSIFIED));
            output.problems.add(problem(ProblemReason.UNCLASSIFIED_MECHANISM, UNCLASSIFIED,
                    observationSubject(raw), Optional.of(raw.identity()), raw.span().stream().toList(),
                    raw.diagnostics(), limitation));
        }
    }

    private static RawObservation resourceDocument(SourceInput input, RawKind kind, int ordinal,
            String reference, String spelling, EvidenceState state) {
        return RawObservation.create(kind, input.document().identity(), wholeDocumentSpan(input), ordinal,
                Optional.empty(), Optional.empty(), state, reference, spelling, List.of());
    }

    private static RawObservation xmlRaw(SourceInput input, RawKind kind, XmlToken token, int ordinal,
            String reference, EvidenceState state) {
        return RawObservation.create(kind, input.document().identity(),
                Optional.of(span(input.document().identity(), input.text(), token.start(), token.end())),
                ordinal, Optional.empty(), Optional.empty(), state, reference, token.spelling(), List.of());
    }

    private static void scanMetadata(SourceInput input, Accumulator output) {
        boolean factories = input.document().path().endsWith("META-INF/spring.factories");
        boolean imports = input.document().path().endsWith(
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        boolean recognizedDocument = factories || imports;
        RawObservation document = resourceDocument(input, RawKind.METADATA_DOCUMENT, 0,
                recognizedDocument ? "spring.metadata.auto-configuration" : "spring.metadata.unclassified",
                "<metadata-document>", EvidenceState.VERIFIED);
        output.raw.add(document);
        classifyMetadata(document, recognizedDocument, output,
                "The metadata resource path is not a registered auto-configuration input.");

        int ordinal = 1;
        List<MetadataEntry> entries = factories
                ? propertyEntries(input.text()) : physicalEntries(input.text());
        for (MetadataEntry entry : entries) {
            String key = factories ? propertyKey(entry.logicalValue()) : entry.logicalValue();
            boolean recognized = imports
                    || key.equals("org.springframework.boot.autoconfigure.EnableAutoConfiguration");
            RawObservation raw = RawObservation.create(RawKind.METADATA_ENTRY,
                    input.document().identity(), Optional.of(span(
                            input.document().identity(), input.text(), entry.start(), entry.end())),
                    ordinal++, Optional.empty(), Optional.empty(), EvidenceState.VERIFIED,
                    key, input.text().substring(entry.start(), entry.end()), List.of());
            output.raw.add(raw);
            classifyMetadata(raw, recognized, output,
                    "The metadata entry is an unadjudicated extension SPI or malformed auto-configuration row.");
        }
    }

    private static List<MetadataEntry> physicalEntries(String text) {
        ArrayList<MetadataEntry> entries = new ArrayList<>();
        for (PhysicalLine line : physicalLines(text)) {
            String stripped = line.content().strip();
            if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("!")) continue;
            int start = line.start() + line.content().indexOf(stripped);
            entries.add(new MetadataEntry(start, start + stripped.length(), stripped));
        }
        return List.copyOf(entries);
    }

    /** Java-properties logical rows, preserving one contiguous source span across continuations. */
    private static List<MetadataEntry> propertyEntries(String text) {
        List<PhysicalLine> lines = physicalLines(text);
        ArrayList<MetadataEntry> entries = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            PhysicalLine first = lines.get(index);
            String leadingTrimmed = first.content().stripLeading();
            if (leadingTrimmed.isBlank()
                    || leadingTrimmed.startsWith("#") || leadingTrimmed.startsWith("!")) continue;
            int start = first.start() + first.content().indexOf(leadingTrimmed);
            int end = first.start() + first.content().length();
            StringBuilder logical = new StringBuilder(leadingTrimmed);
            while (hasOddTrailingBackslashes(logical) && index + 1 < lines.size()) {
                logical.setLength(logical.length() - 1);
                PhysicalLine continued = lines.get(++index);
                String continuation = continued.content().stripLeading();
                logical.append(continuation);
                end = continued.start() + continued.content().length();
            }
            entries.add(new MetadataEntry(start, end, logical.toString()));
        }
        return List.copyOf(entries);
    }

    private static List<PhysicalLine> physicalLines(String text) {
        ArrayList<PhysicalLine> lines = new ArrayList<>();
        int offset = 0;
        while (offset < text.length()) {
            int end = offset;
            while (end < text.length() && text.charAt(end) != '\n' && text.charAt(end) != '\r') end++;
            lines.add(new PhysicalLine(offset, text.substring(offset, end)));
            if (end < text.length() && text.charAt(end) == '\r') end++;
            if (end < text.length() && text.charAt(end) == '\n') end++;
            offset = end;
        }
        return List.copyOf(lines);
    }

    private static boolean hasOddTrailingBackslashes(CharSequence value) {
        int count = 0;
        for (int index = value.length() - 1; index >= 0 && value.charAt(index) == '\\'; index--) count++;
        return count % 2 == 1;
    }

    private static void classifyMetadata(RawObservation raw, boolean recognized,
            Accumulator output, String limitation) {
        if (recognized) {
            output.obligations.add(SemanticObligation.create(raw.identity(), 0,
                    "spring.registration.auto-configuration",
                    "spring.registration.auto-configuration.metadata", List.of("spring.raw.metadata"),
                    Classification.CLASSIFIED));
        } else {
            output.obligations.add(SemanticObligation.create(raw.identity(), 0, UNCLASSIFIED,
                    "spring.mechanism.unclassified.metadata", List.of("spring.raw.metadata"),
                    Classification.UNCLASSIFIED));
            output.problems.add(problem(ProblemReason.UNCLASSIFIED_MECHANISM, UNCLASSIFIED,
                    observationSubject(raw), Optional.of(raw.identity()), raw.span().stream().toList(),
                    List.of(), limitation));
        }
    }

    private static String propertyKey(String value) {
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!escaped && (character == '=' || character == ':' || Character.isWhitespace(character))) {
                return value.substring(0, index).strip();
            }
            if (character == '\\' && !escaped) escaped = true;
            else escaped = false;
        }
        return value.strip();
    }

    private static void validateFramework(SpringFrameworkEvidence evidence, Accumulator output) {
        boolean hasClassifiedSpringInput = output.obligations.stream()
                .anyMatch(value -> !value.primaryMechanism().equals(UNCLASSIFIED));
        if (!hasClassifiedSpringInput) return;
        if (!evidence.completeClasspath() || evidence.classpathManifestIdentity().isEmpty()) {
            output.problems.add(frameworkProblem(ProblemReason.VERSION_FRAGMENT_NOT_VALIDATED, evidence,
                    "The complete content-addressed M3 classpath manifest is required to certify a Spring version fragment."));
            return;
        }
        boolean hasBootSpecificInput = output.obligations.stream().anyMatch(value ->
                value.primaryMechanism().equals("spring.registration.auto-configuration")
                        || value.secondaryTags().contains("spring.source.boot-annotation"));
        Set<String> frameworkVersions = versions(evidence, "org.springframework");
        Set<String> bootVersions = versions(evidence, "org.springframework.boot");
        if (frameworkVersions.size() > 1 || bootVersions.size() > 1) {
            output.problems.add(frameworkProblem(ProblemReason.VERSION_FRAGMENT_NOT_VALIDATED, evidence,
                    "Split or mixed Spring versions require an explicit accepted matrix entry."));
            return;
        }
        if (frameworkVersions.isEmpty()) {
            output.problems.add(frameworkProblem(ProblemReason.VERSION_FRAGMENT_NOT_VALIDATED, evidence,
                    "No exact Spring Framework artifact is present in the supplied M3 classpath evidence."));
            return;
        }
        String framework = frameworkVersions.iterator().next();
        String expectedBoot = switch (framework) {
            case "5.3.31" -> "2.7.18";
            case "6.1.14" -> "3.3.5";
            case "6.2.0" -> "3.4.0";
            default -> null;
        };
        if (expectedBoot == null) {
            output.problems.add(frameworkProblem(ProblemReason.VERSION_FRAGMENT_NOT_VALIDATED, evidence,
                    "The exact Spring Framework patch is outside the three accepted executable R0 tuples."));
            return;
        }
        if (hasBootSpecificInput && bootVersions.isEmpty()) {
            output.problems.add(frameworkProblem(ProblemReason.VERSION_FRAGMENT_NOT_VALIDATED, evidence,
                    "Spring Boot evidence is required for the observed Boot-specific mechanism."));
            return;
        }
        if (!bootVersions.isEmpty() && !bootVersions.equals(Set.of(expectedBoot))) {
            output.problems.add(frameworkProblem(ProblemReason.VERSION_FRAGMENT_NOT_VALIDATED, evidence,
                    "The Spring Boot patch does not match the accepted Spring Framework tuple."));
            return;
        }
        Map<String, String> expected = expectedArtifacts(framework, expectedBoot);
        Map<String, String> actual = evidence.artifacts().stream()
                .filter(value -> value.classpathLogicalName().equals(value.coordinate() + "@jar"))
                .collect(java.util.stream.Collectors.toMap(SpringFrameworkEvidence.Artifact::coordinate,
                        value -> value.contentDigest().value(), (left, right) -> left, TreeMap::new));
        boolean frameworkPinsMatch = expected.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("org.springframework:"))
                .allMatch(entry -> entry.getValue().equals(actual.get(entry.getKey())));
        boolean bootPinsMatch = bootVersions.isEmpty() || expected.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("org.springframework.boot:"))
                .allMatch(entry -> entry.getValue().equals(actual.get(entry.getKey())));
        if (!frameworkPinsMatch || !bootPinsMatch) {
            output.problems.add(frameworkProblem(ProblemReason.VERSION_FRAGMENT_NOT_VALIDATED, evidence,
                    "The version label is known, but the accepted exact artifact set/digests are incomplete or different."));
        }
    }

    private static Set<String> versions(SpringFrameworkEvidence evidence, String exactGroup) {
        return evidence.artifacts().stream().map(SpringFrameworkEvidence.Artifact::coordinate)
                .map(value -> value.split(":", -1)).filter(parts -> parts[0].equals(exactGroup))
                .map(parts -> parts[2]).collect(java.util.stream.Collectors.toCollection(TreeSet::new));
    }

    private static Problem frameworkProblem(ProblemReason reason, SpringFrameworkEvidence evidence,
            String limitation) {
        return problem(reason, UNCLASSIFIED,
                new EvidenceSubject(EvidenceSubject.Kind.CATEGORY,
                        "spring.framework-evidence:" + evidence.identity().value()), Optional.empty(),
                List.of(), List.of(), limitation);
    }

    static Map<String, String> expectedArtifacts(String framework, String boot) {
        return switch (framework) {
            case "5.3.31" -> pins(framework, boot,
                    "3f0c666f317abaa845fc3a24fba219b1f469716bf309cccd755eecb8fee20430",
                    "a8d6d99003d0a28049cba4273afbcfc64e1107ee3c33f67935853e9711544aa7",
                    "38def055d1e22b5514b1cb19cef4474e5c1b0d2127c483e7d014bde87c4a4cf3",
                    "7013ed3da15a8d4be797f5c310f9aa1b196b97f2313bc41e60ef3f5627224fe9",
                    "e027f122b8a4e3030339068220bed02d1c9d397eb5897f1e33ba2f63b22591ac",
                    "eee0df6a25a9c56d228ea86272546aa5a0656caf2f14e7b375417b066abbc0db",
                    "530f4e0fdfeb3a0e2b3a369d15cdea38fbdc1696f8b030c35a6ad65c27524950",
                    "1c4e0aadcb662b6149b536a2cf288003ffefe81a6cc69846e9f14976529a1b08");
            case "6.1.14" -> pins(framework, boot,
                    "15596939a6dbcca5812dee1ab9a8b36f69536bfd80672798927d5f1ac0d384e5",
                    "6cad84b2a35a33a85a313a19445a43e5432b68e4f0bbf6c2bfc4a885a93dd727",
                    "da76b53f6a20f09b38052a300435f3245780d30deb46ec5dd75314cda06fd365",
                    "e15a1179fc9642ffed13ca55e2863e2da524ccd1083b7c6f1b5cfd5733f3b2c5",
                    "69ed6b052397a929a0e4c452f63f2c9a3d22e046d8b80d878190205ddb259518",
                    "9975c462bacee7a0c1aa79e55aa4faed02bd28dc78d1252404da87f5f7fb4cb1",
                    "6a4a5c8a5b58c209705881e487b49445679ab69c858623fef700f634e24eb9c2",
                    "4e9d0d98414d92cc1844f84b88d58a0c968fde51e7333be48dffe424bab77844");
            case "6.2.0" -> pins(framework, boot,
                    "fe84f18bdefb3ad4f993357d31e61613bb55f348d7ea69ec1ca968b3240b3b08",
                    "33adf77b49236f2966c9cec7eb5a1aea8ade81d7ada80c36d91bbf89d3298e15",
                    "a0d2de9df7902edc5f6ae55e2936327bdeac177dbc5392906b8d36f3691fe121",
                    "3b82629da38717dd616a70ec1055f9a8e208d58132071a6314a32159f8bce162",
                    "37cfc1fe8ca22bb789c20aac6b90e09f2b0ea73085f3271ae61cf12cdb7e8876",
                    "2b59288f030ce77a89ec88aa426f32eb9a309056f3646dd5157f9f31f0204fd7",
                    "dea6332c2fa49ce2c724c78e3e57e41417dab50c82f1ccb5bfb22cc993290609",
                    "525aa88505399c6ccfddfe295b313d55a259ed5e8d5890a3568485c074f98414");
            default -> Map.of();
        };
    }

    private static Map<String, String> pins(String framework, String boot, String aop, String beans,
            String context, String core, String expression, String jcl, String bootCore, String autoConfigure) {
        return Map.of(
                "org.springframework:spring-aop:" + framework, "sha256:" + aop,
                "org.springframework:spring-beans:" + framework, "sha256:" + beans,
                "org.springframework:spring-context:" + framework, "sha256:" + context,
                "org.springframework:spring-core:" + framework, "sha256:" + core,
                "org.springframework:spring-expression:" + framework, "sha256:" + expression,
                "org.springframework:spring-jcl:" + framework, "sha256:" + jcl,
                "org.springframework.boot:spring-boot:" + boot, "sha256:" + bootCore,
                "org.springframework.boot:spring-boot-autoconfigure:" + boot, "sha256:" + autoConfigure);
    }

    private static Problem problem(ProblemReason reason, String category, EvidenceSubject subject,
            Optional<ContentDigest> raw, List<SourceSpan> spans, List<Diagnostic> diagnostics,
            String limitation) {
        return new Problem(reason, category, subject, raw, spans, diagnostics, List.of(limitation));
    }

    private static EvidenceSubject observationSubject(RawObservation raw) {
        return new EvidenceSubject(EvidenceSubject.Kind.OBSERVATION, raw.identity().value());
    }

    private static EvidenceState map(SemanticStatus status) {
        return switch (status) {
            case RESOLVED -> EvidenceState.VERIFIED;
            case UNRESOLVED -> EvidenceState.UNRESOLVED;
            case AMBIGUOUS -> EvidenceState.AMBIGUOUS;
            case UNSUPPORTED -> EvidenceState.UNSUPPORTED;
            case ERROR -> EvidenceState.ERROR;
            case PARTIAL, CONDITIONAL -> EvidenceState.PARTIAL;
        };
    }

    private static Optional<SourceSpan> wholeDocumentSpan(SourceInput input) {
        if (input.text().isEmpty()) return Optional.empty();
        return Optional.of(span(input.document().identity(), input.text(), 0, input.text().length()));
    }

    private static SourceSpan span(SourceDocumentIdentity document, String text, int start, int end) {
        if (start < 0 || end <= start || end > text.length()) throw new IllegalArgumentException("Invalid source offsets");
        int[] from = position(text, start);
        int[] to = position(text, end);
        return new SourceSpan(document, from[0], from[1], to[0], to[1]);
    }

    private static int[] position(String text, int offset) {
        int line = 1;
        int column = 1;
        for (int index = 0; index < offset; index++) {
            char value = text.charAt(index);
            if (value == '\r') {
                if (index + 1 < offset && text.charAt(index + 1) == '\n') index++;
                line++;
                column = 1;
            } else if (value == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new int[] {line, column};
    }

    private static XmlLexicalInventory lexXml(String text) throws XMLStreamException {
        ArrayList<XmlStartTag> tags = new ArrayList<>();
        ArrayList<XmlToken> doctypes = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            int open = text.indexOf('<', index);
            if (open < 0) break;
            if (text.startsWith("<!--", open)) { index = requiredEnd(text, open + 4, "-->"); continue; }
            if (text.startsWith("<![CDATA[", open)) { index = requiredEnd(text, open + 9, "]]>"); continue; }
            if (text.startsWith("<?", open)) { index = requiredEnd(text, open + 2, "?>"); continue; }
            if (startsWithIgnoreCase(text, open, "<!DOCTYPE")) {
                int end = declarationEnd(text, open + 9);
                doctypes.add(new XmlToken(text.substring(open, end), open, end));
                index = end;
                continue;
            }
            if (text.startsWith("<!", open)) { index = declarationEnd(text, open + 2); continue; }
            if (text.startsWith("</", open)) { index = requiredEnd(text, open + 2, ">"); continue; }
            int cursor = open + 1;
            int nameStart = cursor;
            cursor = xmlNameEnd(text, cursor);
            if (cursor == nameStart) throw new XMLStreamException("Missing XML element name");
            XmlToken element = new XmlToken(text.substring(nameStart, cursor), nameStart, cursor);
            ArrayList<XmlToken> attributes = new ArrayList<>();
            while (true) {
                cursor = skipWhitespace(text, cursor);
                if (cursor >= text.length()) throw new XMLStreamException("Unclosed XML start tag");
                if (text.charAt(cursor) == '>') { cursor++; break; }
                if (text.charAt(cursor) == '/' && cursor + 1 < text.length() && text.charAt(cursor + 1) == '>') {
                    cursor += 2; break;
                }
                int attributeStart = cursor;
                int nameEnd = xmlNameEnd(text, cursor);
                if (nameEnd == cursor) throw new XMLStreamException("Invalid XML attribute name");
                cursor = skipWhitespace(text, nameEnd);
                if (cursor >= text.length() || text.charAt(cursor) != '=') {
                    throw new XMLStreamException("XML attribute lacks equals");
                }
                cursor = skipWhitespace(text, cursor + 1);
                if (cursor >= text.length() || text.charAt(cursor) != '\'' && text.charAt(cursor) != '"') {
                    throw new XMLStreamException("XML attribute value must be quoted");
                }
                char quote = text.charAt(cursor++);
                int closing = text.indexOf(quote, cursor);
                if (closing < 0) throw new XMLStreamException("Unclosed XML attribute value");
                cursor = closing + 1;
                attributes.add(new XmlToken(text.substring(attributeStart, cursor), attributeStart, cursor));
            }
            tags.add(new XmlStartTag(element, List.copyOf(attributes)));
            index = cursor;
        }
        return new XmlLexicalInventory(List.copyOf(tags), List.copyOf(doctypes));
    }

    private static List<XmlEvent> parseXml(String text, List<XmlStartTag> tags) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        try { factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, ""); } catch (IllegalArgumentException ignored) {}
        try { factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, ""); } catch (IllegalArgumentException ignored) {}
        factory.setXMLResolver((publicID, systemID, baseURI, namespace) -> new ByteArrayInputStream(new byte[0]));
        XMLStreamReader reader = factory.createXMLStreamReader(new java.io.StringReader(text));
        ArrayList<XmlEvent> events = new ArrayList<>();
        int tagIndex = 0;
        try {
            while (reader.hasNext()) {
                if (reader.next() != XMLStreamConstants.START_ELEMENT) continue;
                if (tagIndex >= tags.size()) throw new XMLStreamException("XML parser produced an unexpected start tag");
                XmlStartTag lexical = tags.get(tagIndex++);
                if (!lexical.element().spelling().equals(lexicalQName(reader.getPrefix(), reader.getLocalName()))) {
                    throw new XMLStreamException("XML lexical/parser element names differ");
                }
                ArrayList<XmlAttribute> attributes = new ArrayList<>();
                for (XmlToken token : lexical.attributes()) {
                    String rawName = attributeName(token.spelling());
                    if (rawName.equals("xmlns") || rawName.startsWith("xmlns:")) {
                        String prefix = rawName.equals("xmlns") ? "" : rawName.substring(6);
                        String uri = Optional.ofNullable(reader.getNamespaceURI(prefix)).orElse("");
                        attributes.add(new XmlAttribute(token, uri, rawName, true));
                    } else {
                        String prefix = rawName.contains(":") ? rawName.substring(0, rawName.indexOf(':')) : "";
                        String local = rawName.contains(":") ? rawName.substring(rawName.indexOf(':') + 1) : rawName;
                        String namespace = "";
                        boolean found = false;
                        for (int attributeIndex = 0; attributeIndex < reader.getAttributeCount(); attributeIndex++) {
                            QName name = reader.getAttributeName(attributeIndex);
                            if (name.getLocalPart().equals(local)
                                    && Optional.ofNullable(name.getPrefix()).orElse("").equals(prefix)) {
                                namespace = Optional.ofNullable(name.getNamespaceURI()).orElse("");
                                found = true;
                                break;
                            }
                        }
                        if (!found) throw new XMLStreamException("XML lexical/parser attributes differ");
                        attributes.add(new XmlAttribute(token, namespace, qName(namespace, local), false));
                    }
                }
                events.add(new XmlEvent(Optional.ofNullable(reader.getNamespaceURI()).orElse(""),
                        reader.getLocalName(), List.copyOf(attributes)));
            }
        } finally {
            reader.close();
        }
        if (tagIndex != tags.size()) throw new XMLStreamException("XML lexical/parser start-tag counts differ");
        return List.copyOf(events);
    }

    private static String withoutDoctypes(String text, List<XmlToken> doctypes) {
        if (doctypes.isEmpty()) return text;
        char[] sanitized = text.toCharArray();
        for (XmlToken doctype : doctypes) {
            for (int index = doctype.start(); index < doctype.end(); index++) {
                if (sanitized[index] != '\r' && sanitized[index] != '\n') sanitized[index] = ' ';
            }
        }
        return new String(sanitized);
    }

    private static XmlAttribute resolveAttribute(XmlToken token, XmlEvent event) {
        return event.attributes().stream().filter(value -> value.token().equals(token)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("XML attribute evidence is missing"));
    }

    private static int requiredEnd(String text, int from, String delimiter) throws XMLStreamException {
        int found = text.indexOf(delimiter, from);
        if (found < 0) throw new XMLStreamException("Unclosed XML lexical section");
        return found + delimiter.length();
    }

    private static int declarationEnd(String text, int from) throws XMLStreamException {
        int brackets = 0;
        char quote = 0;
        for (int index = from; index < text.length(); index++) {
            char value = text.charAt(index);
            if (quote != 0) {
                if (value == quote) quote = 0;
            } else if (value == '\'' || value == '"') quote = value;
            else if (value == '[') brackets++;
            else if (value == ']') brackets--;
            else if (value == '>' && brackets == 0) return index + 1;
        }
        throw new XMLStreamException("Unclosed XML declaration");
    }

    private static int xmlNameEnd(String text, int from) {
        int index = from;
        while (index < text.length()) {
            char value = text.charAt(index);
            if (Character.isLetterOrDigit(value) || value == '_' || value == ':' || value == '-'
                    || value == '.' || value >= 0x80) index++;
            else break;
        }
        return index;
    }

    private static int skipWhitespace(String text, int from) {
        int index = from;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
        return index;
    }

    private static boolean startsWithIgnoreCase(String text, int offset, String value) {
        return offset + value.length() <= text.length()
                && text.regionMatches(true, offset, value, 0, value.length());
    }

    private static boolean containsExternalIdentifier(String doctype) {
        String upper = doctype.toUpperCase(Locale.ROOT);
        return upper.matches("(?s).*\\b(?:SYSTEM|PUBLIC)\\b.*");
    }

    private static String attributeName(String spelling) {
        int end = 0;
        while (end < spelling.length() && !Character.isWhitespace(spelling.charAt(end))
                && spelling.charAt(end) != '=') end++;
        return spelling.substring(0, end);
    }

    private static String qName(String namespace, String local) {
        return namespace == null || namespace.isEmpty() ? local : "{" + namespace + "}" + local;
    }

    private static String lexicalQName(String prefix, String local) {
        return prefix == null || prefix.isEmpty() ? local : prefix + ":" + local;
    }

    private static final class Accumulator {
        final Map<EntityIdentity, DeclarationRecord> declarations;
        final List<RawObservation> raw = new ArrayList<>();
        final List<SemanticObligation> obligations = new ArrayList<>();
        final List<ContentDigest> markers = new ArrayList<>();
        final List<Problem> problems = new ArrayList<>();
        final Map<ContentDigest, AnnotationCandidate> annotationCandidates = new TreeMap<>();
        final Map<EntityIdentity, ContentDigest> annotationDeclarationRows = new TreeMap<>();
        final List<AnnotationDeclarationEvidence> annotationDeclarations = new ArrayList<>();
        final Map<EntityIdentity, AnnotationDeclarationEvidence> annotationDeclarationsByEntity = new TreeMap<>();
        final List<AnnotationMetaEdge> annotationMetaEdges = new ArrayList<>();
        final List<AnnotationCycle> annotationCycles = new ArrayList<>();
        final Set<EntityIdentity> incompleteAnnotationDeclarations = new TreeSet<>();
        final Map<EntityIdentity, RawObservation> javaDeclarationRows = new TreeMap<>();
        final Set<EntityIdentity> injectionTypeOwners = new TreeSet<>();
        Accumulator(FrontendResult frontend) {
            declarations = frontend.declarations().stream()
                    .collect(java.util.stream.Collectors.toMap(value -> value.entity().identity(), value -> value));
        }
    }

    private static final class AnnotationTarjan {
        private final Map<EntityIdentity, List<AnnotationMetaEdge>> adjacency;
        private final Map<EntityIdentity, Integer> indexes = new TreeMap<>();
        private final Map<EntityIdentity, Integer> lowLinks = new TreeMap<>();
        private final ArrayDeque<EntityIdentity> stack = new ArrayDeque<>();
        private final Set<EntityIdentity> onStack = new HashSet<>();
        private final List<AnnotationCycle> result = new ArrayList<>();
        private int nextIndex;

        AnnotationTarjan(Map<EntityIdentity, List<AnnotationMetaEdge>> adjacency) {
            this.adjacency = adjacency;
        }

        List<AnnotationCycle> cycles() {
            adjacency.keySet().stream().sorted().forEach(node -> {
                if (!indexes.containsKey(node)) visit(node);
            });
            return result.stream().sorted().toList();
        }

        private void visit(EntityIdentity node) {
            indexes.put(node, nextIndex);
            lowLinks.put(node, nextIndex++);
            stack.push(node);
            onStack.add(node);
            for (AnnotationMetaEdge edge : adjacency.getOrDefault(node, List.of())) {
                if (edge.target().isEmpty() || !adjacency.containsKey(edge.target().orElseThrow())) continue;
                EntityIdentity target = edge.target().orElseThrow();
                if (!indexes.containsKey(target)) {
                    visit(target);
                    lowLinks.put(node, Math.min(lowLinks.get(node), lowLinks.get(target)));
                } else if (onStack.contains(target)) {
                    lowLinks.put(node, Math.min(lowLinks.get(node), indexes.get(target)));
                }
            }
            if (!lowLinks.get(node).equals(indexes.get(node))) return;
            TreeSet<EntityIdentity> component = new TreeSet<>();
            EntityIdentity current;
            do {
                current = stack.pop();
                onStack.remove(current);
                component.add(current);
            } while (!current.equals(node));
            boolean selfCycle = component.size() == 1 && adjacency.getOrDefault(node, List.of()).stream()
                    .anyMatch(edge -> edge.target().equals(Optional.of(node)));
            if (component.size() < 2 && !selfCycle) return;
            List<ContentDigest> edges = component.stream()
                    .flatMap(value -> adjacency.getOrDefault(value, List.of()).stream())
                    .filter(edge -> edge.target().stream().anyMatch(component::contains))
                    .map(AnnotationMetaEdge::identity).sorted().toList();
            result.add(AnnotationCycle.create(component, edges));
        }
    }

    private record AnnotationCandidate(SourceDocumentIdentity document, Optional<SourceSpan> span,
            Optional<EntityIdentity> owner, Optional<EntityIdentity> resolvedTarget, EvidenceState state,
            String stableReference, String spelling, List<Diagnostic> diagnostics, String problem) {}
    private record AnnotationDeclarationParts(DeclarationRecord declaration,
            List<AnnotationMetaEdge> outgoing, List<AnnotationAttributeEvidence> attributes,
            List<EntityIdentity> repeatableTargets, boolean locallyComplete) {}
    private record XmlToken(String spelling, int start, int end) {}
    private record XmlStartTag(XmlToken element, List<XmlToken> attributes) {}
    private record XmlLexicalInventory(List<XmlStartTag> tags, List<XmlToken> doctypes) {}
    private record XmlEvent(String namespace, String localName, List<XmlAttribute> attributes) {}
    private record XmlAttribute(XmlToken token, String namespace, String stableReference,
            boolean namespaceDeclaration) {}
    private record PhysicalLine(int start, String content) {}
    private record MetadataEntry(int start, int end, String logicalValue) {}
}
