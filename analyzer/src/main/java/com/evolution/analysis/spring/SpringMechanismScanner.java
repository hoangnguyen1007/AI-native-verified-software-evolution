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

/** Pure passive M4A.1 scanner over supplied M2 and decoded resource evidence. */
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

    private SpringMechanismScanner() {}

    public static SpringMechanismInventory scan(SpringMechanismScanRequest request) {
        ContractChecks.notNull(request, "Spring mechanism scan request");
        Accumulator output = new Accumulator(request.frontendResult());
        scanAnnotations(request.frontendResult(), request.frameworkEvidence(), output);
        request.xmlInputs().forEach(input -> scanXml(input, output));
        request.metadataInputs().forEach(input -> scanMetadata(input, output));
        validateFramework(request.frameworkEvidence(), output);
        List<ResourceEvidence> resources = java.util.stream.Stream.concat(
                request.xmlInputs().stream().map(input -> ResourceEvidence.from(ResourceKind.XML, input)),
                request.metadataInputs().stream().map(input -> ResourceEvidence.from(ResourceKind.METADATA, input)))
                .sorted().toList();
        return SpringMechanismInventory.create(request.frontendResult().analysis(),
                ContentDigest.sha256Utf8(CanonicalJson.write(request.frontendResult())),
                request.frameworkEvidence(), resources, output.raw, output.obligations,
                output.markers, output.problems);
    }

    private static void scanAnnotations(FrontendResult frontend,
            SpringFrameworkEvidence frameworkEvidence, Accumulator output) {
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
            classifyAnnotation(raw, candidate, declarations, frameworkEvidence, output);
        }

        int declarationOrdinal = 0;
        for (DeclarationRecord declaration : frontend.declarations()) {
            if (declaration.entity().kind() != EntityKind.TYPE
                    || !declaration.spelling().contains("@interface")
                    || declaration.entity().declaration().isEmpty()) continue;
            RawObservation raw = RawObservation.create(RawKind.ANNOTATION_DECLARATION,
                    declaration.entity().declaration().orElseThrow().document(), declaration.entity().declaration(),
                    declarationOrdinal++, Optional.of(declaration.entity().identity()),
                    Optional.of(declaration.entity().identity()), map(declaration.status()),
                    declaration.entity().canonicalName(), declaration.spelling(), declaration.diagnostics());
            output.raw.add(raw);
            output.markers.add(raw.identity());
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
        if (selected == null) {
            addUnclassified(raw, ProblemReason.ANNOTATION_SEMANTICS_NOT_ADJUDICATED,
                    "The exact annotation declaration/use role is not adjudicated by spring-mechanisms:v2.", output);
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

    private static Map<String, String> expectedArtifacts(String framework, String boot) {
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
        Accumulator(FrontendResult frontend) {
            declarations = frontend.declarations().stream()
                    .collect(java.util.stream.Collectors.toMap(value -> value.entity().identity(), value -> value));
        }
    }

    private record AnnotationCandidate(SourceDocumentIdentity document, Optional<SourceSpan> span,
            Optional<EntityIdentity> owner, Optional<EntityIdentity> resolvedTarget, EvidenceState state,
            String stableReference, String spelling, List<Diagnostic> diagnostics, String problem) {}
    private record XmlToken(String spelling, int start, int end) {}
    private record XmlStartTag(XmlToken element, List<XmlToken> attributes) {}
    private record XmlLexicalInventory(List<XmlStartTag> tags, List<XmlToken> doctypes) {}
    private record XmlEvent(String namespace, String localName, List<XmlAttribute> attributes) {}
    private record XmlAttribute(XmlToken token, String namespace, String stableReference,
            boolean namespaceDeclaration) {}
    private record PhysicalLine(int start, String content) {}
    private record MetadataEntry(int start, int end, String logicalValue) {}
}
