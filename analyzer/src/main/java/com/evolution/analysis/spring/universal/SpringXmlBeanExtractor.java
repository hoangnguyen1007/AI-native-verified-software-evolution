package com.evolution.analysis.spring.universal;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.semantic.Diagnostic;
import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.evidence.AffectedOutput;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.evidence.EvidenceRequirement;
import com.evolution.analysis.evidence.EvidenceSubject;
import com.evolution.analysis.evidence.ProviderObservationReference;
import com.evolution.analysis.frontend.SourceInput;
import com.evolution.analysis.ingestion.IngestionEvidence;
import com.evolution.analysis.ingestion.RepositoryInputs;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.stream.Location;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Passive ({@code --build=off}) Spring XML {@code <bean>} extractor and {@code getBean()}
 * lookup projector using a hardened StAX parser with DTD and external entities strictly disabled
 * ({@code I1}).
 *
 * <p>Implements the declarative Spring XML extraction vocabulary (JackEE PLDI '20, Sec. 3.4–3.5)
 * for standard Spring XML XSD 3.x–6.x elements ({@code <bean>}, {@code <property>},
 * {@code <constructor-arg>}, {@code <qualifier>}, {@code <alias>}, {@code <import>},
 * {@code <authentication-provider>}, and {@code p:}/{@code c:} namespaces).
 */
public final class SpringXmlBeanExtractor {

    public static final VersionedIdentifier PROVIDER =
            new VersionedIdentifier("spring.xml-bean-extractor", "wp2.1-v1");
    public static final VersionedIdentifier GAP_CATALOG =
            new VersionedIdentifier("evidence.spring-xml-gaps", "wp2.1-v1");

    /** Pinned Shared Evidence Contract family for XML bean definitions. */
    public static final String EVIDENCE_FAMILY_XML_BEAN = "spring.xml-bean";
    /** Pinned Shared Evidence Contract family for {@code getBean()} lookups. */
    public static final String EVIDENCE_FAMILY_BEAN_LOOKUP = "spring.bean-lookup";
    /** Pinned Shared Evidence Contract edge type for resolved {@code getBean()} calls. */
    public static final String EDGE_RESOLVES_BEAN = "RESOLVES_BEAN";

    /** Pinned CapabilityGapRecord reason code for dynamic unresolved {@code getBean()} lookups. */
    public static final String GAP_DYNAMIC_BEAN_LOOKUP = "DYNAMIC_BEAN_LOOKUP_UNRESOLVED";
    public static final String GAP_XML_PARSE_FAILED = "SPRING_XML_PARSE_FAILED";
    public static final String GAP_XML_RESOURCE_LIMIT = "SPRING_XML_RESOURCE_LIMIT";

    private static final String SPRING_BEANS_NS_PREFIX = "http://www.springframework.org/schema/beans";
    private static final String SPRING_SECURITY_NS_PREFIX = "http://www.springframework.org/schema/security";
    private static final String SPRING_P_NS = "http://www.springframework.org/schema/p";
    private static final String SPRING_C_NS = "http://www.springframework.org/schema/c";

    public record Limits(
            int maxFiles,
            int maxBytesPerFile,
            int maxBeansPerFile,
            int maxTotalBeans,
            int maxDepth) {
        public Limits {
            if (maxFiles < 1 || maxBytesPerFile < 1 || maxBeansPerFile < 1
                    || maxTotalBeans < 1 || maxDepth < 2 || maxDepth > 256) {
                throw new IllegalArgumentException("Invalid Spring XML extraction limits");
            }
        }

        public static Limits defaults() {
            return new Limits(256, 2_097_152, 4_096, 32_768, 64);
        }
    }

    public record PropertyInjection(String name, String ref, String value) implements Comparable<PropertyInjection> {
        public PropertyInjection {
            name = safe(name);
            ref = safe(ref);
            value = safe(value);
        }

        public Map<String, Object> toEvidenceMap() {
            var map = new TreeMap<String, Object>();
            map.put("name", name);
            map.put("ref", ref);
            map.put("value", value);
            return Collections.unmodifiableMap(map);
        }

        @Override
        public int compareTo(PropertyInjection other) {
            int cmp = name.compareTo(other.name);
            if (cmp != 0) return cmp;
            cmp = ref.compareTo(other.ref);
            return cmp != 0 ? cmp : value.compareTo(other.value);
        }
    }

    public record ConstructorArg(
            int index,
            String name,
            String type,
            String ref,
            String value) implements Comparable<ConstructorArg> {
        public ConstructorArg {
            name = safe(name);
            type = safe(type);
            ref = safe(ref);
            value = safe(value);
        }

        public Map<String, Object> toEvidenceMap() {
            var map = new TreeMap<String, Object>();
            map.put("index", index);
            map.put("name", name);
            map.put("type", type);
            map.put("ref", ref);
            map.put("value", value);
            return Collections.unmodifiableMap(map);
        }

        @Override
        public int compareTo(ConstructorArg other) {
            int cmp = Integer.compare(index, other.index);
            if (cmp != 0) return cmp;
            cmp = name.compareTo(other.name);
            if (cmp != 0) return cmp;
            cmp = type.compareTo(other.type);
            if (cmp != 0) return cmp;
            cmp = ref.compareTo(other.ref);
            return cmp != 0 ? cmp : value.compareTo(other.value);
        }
    }

    public record XmlImport(String resource, String sourceFile, int line) implements Comparable<XmlImport> {
        public XmlImport {
            resource = safe(resource);
            sourceFile = normalizePath(sourceFile);
        }

        @Override
        public int compareTo(XmlImport other) {
            int cmp = sourceFile.compareTo(other.sourceFile);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(line, other.line);
            return cmp != 0 ? cmp : resource.compareTo(other.resource);
        }
    }

    public record AuthProviderRef(String ref, String sourceFile, int line) implements Comparable<AuthProviderRef> {
        public AuthProviderRef {
            ref = safe(ref);
            sourceFile = normalizePath(sourceFile);
        }

        @Override
        public int compareTo(AuthProviderRef other) {
            int cmp = sourceFile.compareTo(other.sourceFile);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(line, other.line);
            return cmp != 0 ? cmp : ref.compareTo(other.ref);
        }
    }

    /**
     * Extracted Spring XML {@code <bean>} definition conforming to the pinned
     * {@code "spring.xml-bean"} shared evidence contract.
     */
    public record XmlBeanDefinition(
            String beanId,
            List<String> aliases,
            String className,
            String scope,
            String initMethod,
            String destroyMethod,
            List<PropertyInjection> properties,
            List<ConstructorArg> constructorArgs,
            List<String> imports,
            List<String> authProviders,
            List<String> qualifiers,
            boolean primary,
            boolean lazyInit,
            String factoryBean,
            String factoryMethod,
            String sourceFile,
            int line,
            TrustTier trustTier) implements Comparable<XmlBeanDefinition> {
        public XmlBeanDefinition {
            beanId = safe(beanId);
            aliases = sortedDistinct(aliases);
            className = safe(className);
            scope = normalizeScope(scope);
            initMethod = safe(initMethod);
            destroyMethod = safe(destroyMethod);
            properties = properties == null ? List.of() : properties.stream().sorted().distinct().toList();
            constructorArgs = constructorArgs == null ? List.of() : List.copyOf(constructorArgs);
            imports = sortedDistinct(imports);
            authProviders = sortedDistinct(authProviders);
            qualifiers = sortedDistinct(qualifiers);
            factoryBean = safe(factoryBean);
            factoryMethod = safe(factoryMethod);
            sourceFile = normalizePath(sourceFile);
            trustTier = trustTier == null ? TrustTier.SOURCE_SYNTAX : trustTier;
        }

        public String evidenceFamily() {
            return EVIDENCE_FAMILY_XML_BEAN;
        }

        /** Canonical evidence payload matching the pinned {@code spring.xml-bean} contract. */
        public Map<String, Object> toEvidencePayload() {
            var map = new TreeMap<String, Object>();
            map.put("aliases", aliases);
            map.put("authProviders", authProviders);
            map.put("beanId", beanId);
            map.put("className", className);
            map.put("constructorArgs", constructorArgs.stream().map(ConstructorArg::toEvidenceMap).toList());
            map.put("destroyMethod", destroyMethod);
            map.put("factoryBean", factoryBean);
            map.put("factoryMethod", factoryMethod);
            map.put("imports", imports);
            map.put("initMethod", initMethod);
            map.put("lazyInit", lazyInit);
            map.put("line", line);
            map.put("primary", primary);
            map.put("properties", properties.stream().map(PropertyInjection::toEvidenceMap).toList());
            map.put("qualifiers", qualifiers);
            map.put("scope", scope);
            map.put("sourceFile", sourceFile);
            map.put("trustTier", trustTier.name());
            return Collections.unmodifiableMap(map);
        }

        @Override
        public int compareTo(XmlBeanDefinition other) {
            int cmp = sourceFile.compareTo(other.sourceFile);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(line, other.line);
            if (cmp != 0) return cmp;
            cmp = beanId.compareTo(other.beanId);
            if (cmp != 0) return cmp;
            return className.compareTo(other.className);
        }
    }

    /**
     * Resolved or dynamic {@code BeanFactory.getBean(...)} / {@code ApplicationContext.getBean(...)}
     * edge conforming to the pinned {@code "RESOLVES_BEAN"} / {@code "spring.bean-lookup"} contract.
     */
    public record BeanLookupEdge(
            String callerId,
            String beanName,
            String beanType,
            boolean dynamic,
            String resolvedBeanId,
            String sourceFile,
            int line,
            TrustTier trustTier) implements Comparable<BeanLookupEdge> {
        public BeanLookupEdge {
            callerId = safe(callerId);
            beanName = safe(beanName);
            beanType = safe(beanType);
            resolvedBeanId = safe(resolvedBeanId);
            sourceFile = normalizePath(sourceFile);
            trustTier = trustTier == null ? TrustTier.SOURCE_SYNTAX : trustTier;
        }

        public String edgeType() {
            return EDGE_RESOLVES_BEAN;
        }

        public String evidenceFamily() {
            return EVIDENCE_FAMILY_BEAN_LOOKUP;
        }

        /** Canonical evidence payload matching the pinned {@code spring.bean-lookup} contract. */
        public Map<String, Object> toEvidencePayload() {
            var map = new TreeMap<String, Object>();
            map.put("beanName", beanName);
            map.put("beanType", beanType);
            map.put("callerId", callerId);
            map.put("dynamic", dynamic);
            map.put("edgeType", EDGE_RESOLVES_BEAN);
            map.put("line", line);
            map.put("resolvedBeanId", resolvedBeanId);
            map.put("sourceFile", sourceFile);
            map.put("trustTier", trustTier.name());
            return Collections.unmodifiableMap(map);
        }

        @Override
        public int compareTo(BeanLookupEdge other) {
            int cmp = sourceFile.compareTo(other.sourceFile);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(line, other.line);
            if (cmp != 0) return cmp;
            cmp = callerId.compareTo(other.callerId);
            if (cmp != 0) return cmp;
            cmp = beanName.compareTo(other.beanName);
            if (cmp != 0) return cmp;
            cmp = beanType.compareTo(other.beanType);
            return cmp != 0 ? cmp : Boolean.compare(dynamic, other.dynamic);
        }
    }

    public record Result(
            ContentDigest identity,
            List<XmlBeanDefinition> beans,
            List<XmlImport> imports,
            List<AuthProviderRef> authProviders,
            List<BeanLookupEdge> beanLookups,
            List<CapabilityGapRecord> gaps) {
        private static final Result EMPTY = new Result(
                IngestionEvidence.digest(List.of(PROVIDER, "empty")),
                List.of(), List.of(), List.of(), List.of(), List.of());

        public Result {
            identity = Objects.requireNonNull(identity, "identity");
            beans = beans == null ? List.of() : beans.stream().sorted().distinct().toList();
            imports = imports == null ? List.of() : imports.stream().sorted().distinct().toList();
            authProviders = authProviders == null ? List.of() : authProviders.stream().sorted().distinct().toList();
            beanLookups = beanLookups == null ? List.of() : beanLookups.stream().sorted().distinct().toList();
            gaps = gaps == null ? List.of() : gaps.stream().sorted().distinct().toList();
        }

        public static Result empty() {
            return EMPTY;
        }

        public boolean isEmpty() {
            return beans.isEmpty() && imports.isEmpty() && authProviders.isEmpty()
                    && beanLookups.isEmpty() && gaps.isEmpty();
        }

        public Result withBeanLookups(List<BeanLookupEdge> extraLookups, List<CapabilityGapRecord> extraGaps) {
            var mergedLookups = new ArrayList<>(beanLookups);
            if (extraLookups != null) mergedLookups.addAll(extraLookups);
            var mergedGaps = new ArrayList<>(gaps);
            if (extraGaps != null) mergedGaps.addAll(extraGaps);
            var sortedLookups = mergedLookups.stream().sorted().distinct().toList();
            var sortedGaps = mergedGaps.stream().sorted().distinct().toList();
            ContentDigest digest = IngestionEvidence.digest(Map.of(
                    "provider", PROVIDER,
                    "beans", beans,
                    "imports", imports,
                    "authProviders", authProviders,
                    "beanLookups", sortedLookups,
                    "gaps", sortedGaps));
            return new Result(digest, beans, imports, authProviders, sortedLookups, sortedGaps);
        }
    }

    private SpringXmlBeanExtractor() {}

    public static Result extract(RepositoryInputs inputs) {
        return extract(inputs, Limits.defaults());
    }

    /**
     * Scans captured repository files for passive Spring XML bean configuration documents.
     * Non-Spring XML files (e.g. build manifests or logging configs) are safely skipped.
     */
    public static Result extract(RepositoryInputs inputs, Limits limits) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(limits, "limits");

        var xmlFiles = new ArrayList<String>();
        for (var file : inputs.snapshot().files()) {
            String path = normalizePath(file.path());
            if (isCandidateXmlPath(path)) {
                xmlFiles.add(path);
            }
        }
        Collections.sort(xmlFiles);
        if (xmlFiles.isEmpty()) {
            return Result.empty();
        }

        var allBeans = new ArrayList<XmlBeanDefinition>();
        var allImports = new ArrayList<XmlImport>();
        var allAuthProviders = new ArrayList<AuthProviderRef>();
        var allGaps = new ArrayList<CapabilityGapRecord>();
        int processedSpringFiles = 0;

        for (String path : xmlFiles) {
            SourceInput input = inputs.files().get(path);
            if (input == null) {
                continue;
            }
            if (input.bytes().length > limits.maxBytesPerFile()) {
                if (looksLikeSpringXmlHeader(new String(input.bytes(), 0,
                        Math.min(input.bytes().length, 4096), StandardCharsets.UTF_8))) {
                    allGaps.add(createXmlGap(inputs.snapshot().identity(), GAP_XML_RESOURCE_LIMIT,
                            path, 1, "Spring XML file exceeds maxBytesPerFile=" + limits.maxBytesPerFile()));
                }
                continue;
            }
            String text = new String(input.bytes(), StandardCharsets.UTF_8);
            if (!looksLikeSpringXmlHeader(text)) {
                continue;
            }
            if (processedSpringFiles >= limits.maxFiles()) {
                allGaps.add(createXmlGap(inputs.snapshot().identity(), GAP_XML_RESOURCE_LIMIT,
                        path, 1, "Exceeded maxFiles=" + limits.maxFiles()));
                break;
            }
            processedSpringFiles++;
            Result single = extractFromXml(inputs.snapshot().identity(), path, text, limits);
            for (var bean : single.beans()) {
                if (allBeans.size() >= limits.maxTotalBeans()) {
                    allGaps.add(createXmlGap(inputs.snapshot().identity(), GAP_XML_RESOURCE_LIMIT,
                            path, bean.line(), "Exceeded maxTotalBeans=" + limits.maxTotalBeans()));
                    break;
                }
                allBeans.add(bean);
            }
            allImports.addAll(single.imports());
            allAuthProviders.addAll(single.authProviders());
            allGaps.addAll(single.gaps());
        }

        if (allBeans.isEmpty() && allImports.isEmpty() && allAuthProviders.isEmpty() && allGaps.isEmpty()) {
            return Result.empty();
        }
        ContentDigest digest = IngestionEvidence.digest(Map.of(
                "provider", PROVIDER,
                "beans", allBeans.stream().sorted().distinct().toList(),
                "imports", allImports.stream().sorted().distinct().toList(),
                "authProviders", allAuthProviders.stream().sorted().distinct().toList(),
                "gaps", allGaps.stream().sorted().distinct().toList()));
        return new Result(digest, allBeans, allImports, allAuthProviders, List.of(), allGaps);
    }

    /**
     * Parses a single Spring XML document using a hardened StAX parser ({@code SUPPORT_DTD=false},
     * {@code IS_SUPPORTING_EXTERNAL_ENTITIES=false}). Rejects DTD/XXE payloads fail-closed.
     */
    public static Result extractFromXml(
            SnapshotIdentity snapshot,
            String sourceFile,
            String xmlContent,
            Limits limits) {
        Objects.requireNonNull(snapshot, "snapshot");
        String normPath = normalizePath(sourceFile);
        Objects.requireNonNull(limits, "limits");
        if (xmlContent == null || xmlContent.isBlank()) {
            return Result.empty();
        }
        if (xmlContent.getBytes(StandardCharsets.UTF_8).length > limits.maxBytesPerFile()) {
            var gap = createXmlGap(snapshot, GAP_XML_RESOURCE_LIMIT, normPath, 1,
                    "XML content exceeds maxBytesPerFile=" + limits.maxBytesPerFile());
            return buildSingleResult(List.of(), List.of(), List.of(), List.of(gap));
        }
        // Fail closed immediately on any DTD / ENTITY declaration (I1 XXE & Billion Laughs guard).
        if (containsDtdOrEntityDeclaration(xmlContent)) {
            var gap = createXmlGap(snapshot, GAP_XML_PARSE_FAILED, normPath, 1,
                    "DTD or external entity declarations are forbidden by passive StAX security policy (I1)");
            return buildSingleResult(List.of(), List.of(), List.of(), List.of(gap));
        }

        XMLInputFactory factory = createHardenedStaxFactory();
        var rawBeans = new ArrayList<MutableBean>();
        var fileImports = new ArrayList<XmlImport>();
        var fileAuthProviders = new ArrayList<AuthProviderRef>();
        var aliasesByTarget = new HashMap<String, List<String>>();
        var gaps = new ArrayList<CapabilityGapRecord>();

        XMLStreamReader reader = null;
        try {
            reader = factory.createXMLStreamReader(new StringReader(xmlContent));
            int depth = 0;
            boolean isSpringDoc = false;
            boolean rootSeen = false;
            var beanStack = new ArrayDeque<MutableBean>();

            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_DECLARATION
                        || event == XMLStreamConstants.ENTITY_REFERENCE) {
                    gaps.add(createXmlGap(snapshot, GAP_XML_PARSE_FAILED, normPath, lineOf(reader),
                            "Rejected DTD/entity event under hardened StAX policy (I1)"));
                    return buildSingleResult(List.of(), List.of(), List.of(), gaps);
                }

                if (event == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    if (depth > limits.maxDepth()) {
                        gaps.add(createXmlGap(snapshot, GAP_XML_RESOURCE_LIMIT, normPath, lineOf(reader),
                                "XML nesting depth exceeded maxDepth=" + limits.maxDepth()));
                        break;
                    }
                    String local = reader.getLocalName();
                    String ns = safe(reader.getNamespaceURI());
                    int line = lineOf(reader);

                    if (!rootSeen) {
                        rootSeen = true;
                        isSpringDoc = isSpringRootElement(local, ns, reader);
                        if (!isSpringDoc) {
                            return Result.empty();
                        }
                    }

                    if ("bean".equals(local)) {
                        if (rawBeans.size() >= limits.maxBeansPerFile()) {
                            gaps.add(createXmlGap(snapshot, GAP_XML_RESOURCE_LIMIT, normPath, line,
                                    "Exceeded maxBeansPerFile=" + limits.maxBeansPerFile()));
                            break;
                        }
                        var bean = parseBeanStart(reader, normPath, line);
                        if (!beanStack.isEmpty()) {
                            // Inner anonymous/named bean inside an enclosing bean's <property> or <constructor-arg>
                            MutableBean parent = beanStack.peek();
                            if (parent.pendingProperty != null
                                    && safe(parent.pendingProperty.ref).isEmpty()
                                    && !bean.beanId.isEmpty()) {
                                parent.pendingProperty.ref = bean.beanId;
                            } else if (parent.pendingCtorArg != null
                                    && safe(parent.pendingCtorArg.ref).isEmpty()
                                    && !bean.beanId.isEmpty()) {
                                parent.pendingCtorArg.ref = bean.beanId;
                            }
                        }
                        rawBeans.add(bean);
                        beanStack.push(bean);
                    } else if ("property".equals(local) && !beanStack.isEmpty()) {
                        beanStack.peek().pendingProperty = new PendingProperty(
                                attr(reader, "name"), attr(reader, "ref"), attr(reader, "value"));
                    } else if ("constructor-arg".equals(local) && !beanStack.isEmpty()) {
                        beanStack.peek().pendingCtorArg = new PendingCtorArg(
                                parseIntOrDefault(attr(reader, "index"), -1),
                                attr(reader, "name"),
                                attr(reader, "type"),
                                attr(reader, "ref"),
                                attr(reader, "value"));
                    } else if ("ref".equals(local) && !beanStack.isEmpty()) {
                        String refBean = firstNonBlank(attr(reader, "bean"), attr(reader, "local"), attr(reader, "parent"));
                        MutableBean cur = beanStack.peek();
                        if (cur.pendingProperty != null && safe(cur.pendingProperty.ref).isEmpty()) {
                            cur.pendingProperty.ref = refBean;
                        } else if (cur.pendingCtorArg != null && safe(cur.pendingCtorArg.ref).isEmpty()) {
                            cur.pendingCtorArg.ref = refBean;
                        }
                    } else if ("idref".equals(local) && !beanStack.isEmpty()) {
                        String refBean = firstNonBlank(attr(reader, "bean"), attr(reader, "local"));
                        MutableBean cur = beanStack.peek();
                        if (cur.pendingProperty != null && safe(cur.pendingProperty.value).isEmpty()) {
                            cur.pendingProperty.value = refBean;
                        } else if (cur.pendingCtorArg != null && safe(cur.pendingCtorArg.value).isEmpty()) {
                            cur.pendingCtorArg.value = refBean;
                        }
                    } else if ("value".equals(local) && !beanStack.isEmpty()) {
                        MutableBean cur = beanStack.peek();
                        if (cur.pendingProperty != null) {
                            cur.pendingProperty.valueBuf.setLength(0);
                            cur.pendingProperty.capturingValueText = true;
                        } else if (cur.pendingCtorArg != null) {
                            cur.pendingCtorArg.valueBuf.setLength(0);
                            cur.pendingCtorArg.capturingValueText = true;
                        }
                    } else if ("qualifier".equals(local) && !beanStack.isEmpty()) {
                        String qVal = firstNonBlank(attr(reader, "value"), attr(reader, "type"));
                        if (!qVal.isEmpty()) {
                            beanStack.peek().qualifiers.add(qVal);
                        }
                    } else if ("alias".equals(local)) {
                        String target = attr(reader, "name");
                        String alias = attr(reader, "alias");
                        if (!target.isEmpty() && !alias.isEmpty()) {
                            aliasesByTarget.computeIfAbsent(target, k -> new ArrayList<>()).add(alias);
                        }
                    } else if ("import".equals(local)) {
                        String resource = attr(reader, "resource");
                        if (!resource.isEmpty()) {
                            fileImports.add(new XmlImport(resource, normPath, line));
                        }
                    } else if ("authentication-provider".equals(local)) {
                        String ref = firstNonBlank(attr(reader, "ref"), attr(reader, "user-service-ref"));
                        if (!ref.isEmpty()) {
                            fileAuthProviders.add(new AuthProviderRef(ref, normPath, line));
                        }
                    }
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                    if (!beanStack.isEmpty()) {
                        MutableBean cur = beanStack.peek();
                        if (cur.pendingProperty != null && cur.pendingProperty.capturingValueText) {
                            cur.pendingProperty.valueBuf.append(reader.getText());
                        } else if (cur.pendingCtorArg != null && cur.pendingCtorArg.capturingValueText) {
                            cur.pendingCtorArg.valueBuf.append(reader.getText());
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    depth = Math.max(0, depth - 1);
                    String local = reader.getLocalName();
                    if ("value".equals(local) && !beanStack.isEmpty()) {
                        MutableBean cur = beanStack.peek();
                        if (cur.pendingProperty != null && cur.pendingProperty.capturingValueText) {
                            String captured = cur.pendingProperty.valueBuf.toString().trim();
                            if (safe(cur.pendingProperty.value).isEmpty()) {
                                cur.pendingProperty.value = captured;
                            }
                            cur.pendingProperty.capturingValueText = false;
                            cur.pendingProperty.valueBuf.setLength(0);
                        } else if (cur.pendingCtorArg != null && cur.pendingCtorArg.capturingValueText) {
                            String captured = cur.pendingCtorArg.valueBuf.toString().trim();
                            if (safe(cur.pendingCtorArg.value).isEmpty()) {
                                cur.pendingCtorArg.value = captured;
                            }
                            cur.pendingCtorArg.capturingValueText = false;
                            cur.pendingCtorArg.valueBuf.setLength(0);
                        }
                    } else if ("property".equals(local) && !beanStack.isEmpty()) {
                        MutableBean cur = beanStack.peek();
                        PendingProperty prop = cur.pendingProperty;
                        if (prop != null) {
                            if (!safe(prop.name).isEmpty()) {
                                cur.properties.add(new PropertyInjection(prop.name, prop.ref, prop.value));
                            }
                            cur.pendingProperty = null;
                        }
                    } else if ("constructor-arg".equals(local) && !beanStack.isEmpty()) {
                        MutableBean cur = beanStack.peek();
                        PendingCtorArg arg = cur.pendingCtorArg;
                        if (arg != null) {
                            int resolvedIdx = arg.index >= 0 ? arg.index : cur.constructorArgs.size();
                            cur.constructorArgs.add(new ConstructorArg(
                                    resolvedIdx, arg.name, arg.type, arg.ref, arg.value));
                            cur.pendingCtorArg = null;
                        }
                    } else if ("bean".equals(local) && !beanStack.isEmpty()) {
                        beanStack.pop();
                    }
                }
            }
        } catch (XMLStreamException | RuntimeException parseErr) {
            gaps.add(createXmlGap(snapshot, GAP_XML_PARSE_FAILED, normPath, lineOf(reader),
                    "Failed to parse Spring XML document: " + safeMsg(parseErr)));
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                }
            }
        }

        List<String> importPaths = fileImports.stream().map(XmlImport::resource).distinct().sorted().toList();
        List<String> authProviderRefs = fileAuthProviders.stream().map(AuthProviderRef::ref).distinct().sorted().toList();

        var finalizedBeans = new ArrayList<XmlBeanDefinition>(rawBeans.size());
        for (MutableBean mb : rawBeans) {
            var aliases = new ArrayList<>(mb.aliases);
            if (!mb.beanId.isEmpty()) {
                aliases.addAll(aliasesByTarget.getOrDefault(mb.beanId, List.of()));
            }
            aliases.removeIf(a -> a.equals(mb.beanId));
            finalizedBeans.add(new XmlBeanDefinition(
                    mb.beanId,
                    aliases,
                    mb.className,
                    mb.scope,
                    mb.initMethod,
                    mb.destroyMethod,
                    mb.properties,
                    mb.constructorArgs,
                    importPaths,
                    authProviderRefs,
                    mb.qualifiers,
                    mb.primary,
                    mb.lazyInit,
                    mb.factoryBean,
                    mb.factoryMethod,
                    mb.sourceFile,
                    mb.line,
                    TrustTier.SOURCE_SYNTAX));
        }

        return buildSingleResult(finalizedBeans, fileImports, fileAuthProviders, gaps);
    }

    /**
     * Projects folded {@code BeanFactory.getBean(...)} / {@code ApplicationContext.getBean(...)}
     * records captured by the Java frontend ({@code spring.bean-lookup} diagnostics) into typed
     * {@code RESOLVES_BEAN} edges and emits {@link CapabilityGapRecord}
     * ({@code "DYNAMIC_BEAN_LOOKUP_UNRESOLVED"}) for dynamic lookups ({@code I3}).
     */
    public static BeanLookupProjection extractBeanLookups(
            SpringSourceEvidence source,
            Collection<XmlBeanDefinition> xmlBeans,
            Map<String, String> javaBeansById,
            Map<String, List<String>> javaBeanIdsByType) {
        Objects.requireNonNull(source, "source");
        var snapshot = source.manifest().snapshot().identity();
        var xmlById = new HashMap<String, XmlBeanDefinition>();
        var xmlIdsByClass = new HashMap<String, List<String>>();
        if (xmlBeans != null) {
            for (var xb : xmlBeans) {
                if (!xb.beanId().isEmpty()) {
                    xmlById.putIfAbsent(xb.beanId(), xb);
                    for (String alias : xb.aliases()) {
                        xmlById.putIfAbsent(alias, xb);
                    }
                }
                if (!xb.className().isEmpty() && !xb.beanId().isEmpty()) {
                    xmlIdsByClass.computeIfAbsent(xb.className(), k -> new ArrayList<>()).add(xb.beanId());
                    String simple = simpleName(xb.className());
                    xmlIdsByClass.computeIfAbsent(simple, k -> new ArrayList<>()).add(xb.beanId());
                }
            }
        }

        var edges = new ArrayList<BeanLookupEdge>();
        var gaps = new ArrayList<CapabilityGapRecord>();
        for (Diagnostic diag : source.frontend().diagnostics()) {
            if (!EVIDENCE_FAMILY_BEAN_LOOKUP.equals(diag.code())) {
                continue;
            }
            var details = diag.details();
            String beanName = safe(details.get("beanName"));
            String beanType = safe(details.get("beanType"));
            boolean dynamic = Boolean.parseBoolean(details.getOrDefault("dynamic", "false"));
            String sourceFile = normalizePath(details.getOrDefault("sourceFile", ""));
            int line = parseIntOrDefault(details.get("line"), diag.span().map(s -> s.startLine()).orElse(1));
            String callerId = safe(details.get("caller"));
            String rawExpr = safe(details.get("rawExpression"));

            String resolvedId = "";
            if (!dynamic) {
                if (!beanName.isEmpty()) {
                    if (javaBeansById != null && javaBeansById.containsKey(beanName)) {
                        resolvedId = beanName;
                    } else if (xmlById.containsKey(beanName)) {
                        resolvedId = xmlById.get(beanName).beanId();
                    } else {
                        resolvedId = beanName;
                    }
                } else if (!beanType.isEmpty()) {
                    var matchedIds = new LinkedHashSet<String>();
                    if (javaBeanIdsByType != null) {
                        matchedIds.addAll(javaBeanIdsByType.getOrDefault(beanType, List.of()));
                        matchedIds.addAll(javaBeanIdsByType.getOrDefault(simpleName(beanType), List.of()));
                    }
                    matchedIds.addAll(xmlIdsByClass.getOrDefault(beanType, List.of()));
                    matchedIds.addAll(xmlIdsByClass.getOrDefault(simpleName(beanType), List.of()));
                    if (matchedIds.size() == 1) {
                        resolvedId = matchedIds.iterator().next();
                    } else if (!matchedIds.isEmpty()) {
                        resolvedId = matchedIds.stream().sorted().findFirst().orElse("");
                    } else {
                        resolvedId = beanType;
                    }
                }
            }

            edges.add(new BeanLookupEdge(
                    callerId,
                    beanName,
                    beanType,
                    dynamic,
                    resolvedId,
                    sourceFile,
                    line,
                    TrustTier.SOURCE_SYNTAX));

            if (dynamic) {
                gaps.add(createDynamicLookupGap(snapshot, sourceFile, line,
                        rawExpr.isEmpty() ? (callerId + ":getBean(" + beanName + "," + beanType + ")") : rawExpr));
            }
        }
        return new BeanLookupProjection(
                edges.stream().sorted().distinct().toList(),
                gaps.stream().sorted().distinct().toList());
    }

    public record BeanLookupProjection(
            List<BeanLookupEdge> edges,
            List<CapabilityGapRecord> gaps) {
        public BeanLookupProjection {
            edges = edges == null ? List.of() : List.copyOf(edges);
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
        }
    }

    /** Creates a deterministic {@link CapabilityGapRecord} for a dynamic {@code getBean()} lookup. */
    public static CapabilityGapRecord createDynamicLookupGap(
            SnapshotIdentity snapshot,
            String sourceFile,
            int line,
            String rawExpression) {
        Objects.requireNonNull(snapshot, "snapshot");
        String normFile = normalizePath(sourceFile);
        String expr = safe(rawExpression);
        ContentDigest obsDigest = IngestionEvidence.digest(Map.of(
                "reason", GAP_DYNAMIC_BEAN_LOOKUP,
                "sourceFile", normFile,
                "line", line,
                "expr", expr));
        var obs = ProviderObservationReference.create(
                PROVIDER, EVIDENCE_FAMILY_BEAN_LOOKUP, obsDigest, obsDigest);
        var subject = EvidenceSubject.observation(obs.identity());
        var req = new EvidenceRequirement(
                EvidenceRequirement.Kind.RUNTIME_OBSERVATION,
                "spring.bean-lookup.resolve-dynamic-key",
                List.of(subject),
                EvidenceRequirement.AuthorizationClass.RUNTIME_ACCESS,
                List.of("Supply a static constant bean name/type or runtime lookup trace for dynamic getBean() calls."));
        return CapabilityGapRecord.create(
                GAP_CATALOG,
                EvidenceContext.forSnapshot(snapshot),
                PROVIDER,
                "spring.bean-lookup",
                GAP_DYNAMIC_BEAN_LOOKUP,
                subject,
                List.of(),
                List.of(obs),
                List.of(req),
                List.of(),
                List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE, "spring.bean-lookup")),
                List.of(),
                List.of(),
                List.of("Dynamic BeanFactory/ApplicationContext.getBean() key at "
                        + normFile + ":" + line + " could not be statically constant-folded."));
    }

    static XMLInputFactory createHardenedStaxFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        setOptionalProperty(factory, XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, Boolean.FALSE);
        setOptionalProperty(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        setOptionalProperty(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    private static void setOptionalProperty(XMLInputFactory factory, String key, Object value) {
        try {
            factory.setProperty(key, value);
        } catch (IllegalArgumentException ignored) {
            // Some StAX implementations do not expose optional JAXP access properties when SUPPORT_DTD=false.
        }
    }

    private static boolean containsDtdOrEntityDeclaration(String xml) {
        String upper = xml.toUpperCase(Locale.ROOT);
        return upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY");
    }

    private static boolean isCandidateXmlPath(String path) {
        String low = path.toLowerCase(Locale.ROOT);
        if (!low.endsWith(".xml")) return false;
        String fileName = low.substring(low.lastIndexOf('/') + 1);
        // Fast reject standard non-Spring build/checkstyle/logging XML files before reading bytes.
        return !fileName.equals("pom.xml")
                && !fileName.equals("settings.xml")
                && !fileName.equals("checkstyle.xml")
                && !fileName.equals("spotbugs.xml")
                && !fileName.startsWith("logback")
                && !fileName.startsWith("log4j");
    }

    private static boolean looksLikeSpringXmlHeader(String text) {
        if (text == null || text.isEmpty()) return false;
        int limit = Math.min(text.length(), 8192);
        String head = text.substring(0, limit);
        return head.contains(SPRING_BEANS_NS_PREFIX)
                || head.contains(SPRING_SECURITY_NS_PREFIX)
                || head.contains("<beans")
                || head.contains(":beans")
                || (head.contains("<!DOCTYPE") && head.toLowerCase(Locale.ROOT).contains("spring-beans"));
    }

    private static boolean isSpringRootElement(String local, String ns, XMLStreamReader reader) {
        if (ns.startsWith(SPRING_BEANS_NS_PREFIX) || ns.startsWith(SPRING_SECURITY_NS_PREFIX)) {
            return true;
        }
        if ("beans".equals(local)) {
            int nsCount = reader.getNamespaceCount();
            if (nsCount == 0) {
                return true;
            }
            for (int i = 0; i < nsCount; i++) {
                String uri = safe(reader.getNamespaceURI(i));
                if (uri.startsWith("http://www.springframework.org/schema/")
                        || uri.startsWith("https://www.springframework.org/schema/")) {
                    return true;
                }
            }
            return true;
        }
        return false;
    }

    private static final class PendingProperty {
        final String name;
        String ref;
        String value;
        boolean capturingValueText;
        final StringBuilder valueBuf = new StringBuilder();

        PendingProperty(String name, String ref, String value) {
            this.name = name;
            this.ref = ref;
            this.value = value;
        }
    }

    private static final class PendingCtorArg {
        final int index;
        final String name;
        final String type;
        String ref;
        String value;
        boolean capturingValueText;
        final StringBuilder valueBuf = new StringBuilder();

        PendingCtorArg(int index, String name, String type, String ref, String value) {
            this.index = index;
            this.name = name;
            this.type = type;
            this.ref = ref;
            this.value = value;
        }
    }

    private static final class MutableBean {
        final String beanId;
        final List<String> aliases = new ArrayList<>();
        final String className;
        final String scope;
        final String initMethod;
        final String destroyMethod;
        final boolean primary;
        final boolean lazyInit;
        final String factoryBean;
        final String factoryMethod;
        final String sourceFile;
        final int line;
        final List<PropertyInjection> properties = new ArrayList<>();
        final List<ConstructorArg> constructorArgs = new ArrayList<>();
        final List<String> qualifiers = new ArrayList<>();
        PendingProperty pendingProperty;
        PendingCtorArg pendingCtorArg;

        MutableBean(String beanId, String className, String scope, String initMethod, String destroyMethod,
                boolean primary, boolean lazyInit, String factoryBean, String factoryMethod,
                String sourceFile, int line) {
            this.beanId = beanId;
            this.className = className;
            this.scope = scope;
            this.initMethod = initMethod;
            this.destroyMethod = destroyMethod;
            this.primary = primary;
            this.lazyInit = lazyInit;
            this.factoryBean = factoryBean;
            this.factoryMethod = factoryMethod;
            this.sourceFile = sourceFile;
            this.line = line;
        }
    }

    private static MutableBean parseBeanStart(XMLStreamReader reader, String sourceFile, int line) {
        String id = attr(reader, "id");
        String nameAttr = attr(reader, "name");
        String className = attr(reader, "class");
        String scope = attr(reader, "scope");
        if (scope.isEmpty()) {
            // Spring 1.x/2.x legacy singleton="false" attribute
            String singletonAttr = attr(reader, "singleton");
            if ("false".equalsIgnoreCase(singletonAttr)) {
                scope = "prototype";
            }
        }
        String initMethod = attr(reader, "init-method");
        String destroyMethod = attr(reader, "destroy-method");
        boolean primary = "true".equalsIgnoreCase(attr(reader, "primary"));
        boolean lazyInit = "true".equalsIgnoreCase(attr(reader, "lazy-init"));
        String factoryBean = attr(reader, "factory-bean");
        String factoryMethod = attr(reader, "factory-method");

        var parsedNames = new ArrayList<String>();
        if (!nameAttr.isEmpty()) {
            for (String tok : nameAttr.split("[,;\\s]+")) {
                if (!tok.isBlank()) parsedNames.add(tok.trim());
            }
        }
        String resolvedId = id;
        if (resolvedId.isEmpty() && !parsedNames.isEmpty()) {
            resolvedId = parsedNames.removeFirst();
        }
        if (resolvedId.isEmpty() && !className.isEmpty()) {
            resolvedId = className + "#" + line;
        }

        var bean = new MutableBean(resolvedId, className, normalizeScope(scope), initMethod, destroyMethod,
                primary, lazyInit, factoryBean, factoryMethod, sourceFile, line);
        bean.aliases.addAll(parsedNames);

        // Support Spring p: and c: XML shorthand namespace attributes
        int attrCount = reader.getAttributeCount();
        for (int i = 0; i < attrCount; i++) {
            String attrNs = safe(reader.getAttributeNamespace(i));
            String attrLocal = safe(reader.getAttributeLocalName(i));
            String attrVal = safe(reader.getAttributeValue(i));
            if (SPRING_P_NS.equals(attrNs)) {
                if (attrLocal.endsWith("-ref") && attrLocal.length() > 4) {
                    bean.properties.add(new PropertyInjection(
                            attrLocal.substring(0, attrLocal.length() - 4), attrVal, ""));
                } else if (!attrLocal.isEmpty()) {
                    bean.properties.add(new PropertyInjection(attrLocal, "", attrVal));
                }
            } else if (SPRING_C_NS.equals(attrNs)) {
                boolean isRef = attrLocal.endsWith("-ref") && attrLocal.length() > 4;
                String base = isRef ? attrLocal.substring(0, attrLocal.length() - 4) : attrLocal;
                int idx = base.startsWith("_") ? parseIntOrDefault(base.substring(1), bean.constructorArgs.size())
                        : bean.constructorArgs.size();
                String argName = base.startsWith("_") ? "" : base;
                bean.constructorArgs.add(new ConstructorArg(
                        idx, argName, "", isRef ? attrVal : "", isRef ? "" : attrVal));
            }
        }
        return bean;
    }

    private static Result buildSingleResult(
            List<XmlBeanDefinition> beans,
            List<XmlImport> imports,
            List<AuthProviderRef> authProviders,
            List<CapabilityGapRecord> gaps) {
        if (beans.isEmpty() && imports.isEmpty() && authProviders.isEmpty() && gaps.isEmpty()) {
            return Result.empty();
        }
        var sortedBeans = beans.stream().sorted().distinct().toList();
        var sortedImports = imports.stream().sorted().distinct().toList();
        var sortedAuth = authProviders.stream().sorted().distinct().toList();
        var sortedGaps = gaps.stream().sorted().distinct().toList();
        ContentDigest digest = IngestionEvidence.digest(Map.of(
                "provider", PROVIDER,
                "beans", sortedBeans,
                "imports", sortedImports,
                "authProviders", sortedAuth,
                "gaps", sortedGaps));
        return new Result(digest, sortedBeans, sortedImports, sortedAuth, List.of(), sortedGaps);
    }

    private static CapabilityGapRecord createXmlGap(
            SnapshotIdentity snapshot,
            String reasonCode,
            String sourceFile,
            int line,
            String detail) {
        String normFile = normalizePath(sourceFile);
        ContentDigest obsDigest = IngestionEvidence.digest(Map.of(
                "reason", reasonCode,
                "sourceFile", normFile,
                "line", line,
                "detail", detail));
        var obs = ProviderObservationReference.create(PROVIDER, EVIDENCE_FAMILY_XML_BEAN, obsDigest, obsDigest);
        var subject = EvidenceSubject.observation(obs.identity());
        var req = new EvidenceRequirement(
                EvidenceRequirement.Kind.REPOSITORY_CONTENT,
                "spring.xml-bean." + reasonCode.toLowerCase(Locale.ROOT).replace('_', '-'),
                List.of(subject),
                EvidenceRequirement.AuthorizationClass.PASSIVE,
                List.of("Supply valid, DTD-free Spring XML configuration within bounded parser limits."));
        return CapabilityGapRecord.create(
                GAP_CATALOG,
                EvidenceContext.forSnapshot(snapshot),
                PROVIDER,
                "spring.xml-bean",
                ContractChecks.token(reasonCode, "xml gap reason"),
                subject,
                List.of(),
                List.of(obs),
                List.of(req),
                List.of(),
                List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE, "spring.xml-bean")),
                List.of(),
                List.of(),
                List.of(detail));
    }

    public static String normalizeScope(String raw) {
        if (raw == null || raw.isBlank()) {
            return "singleton";
        }
        String trimmed = raw.trim();
        // Strip standard Spring constant references if passed from annotation text
        if (trimmed.endsWith(".SCOPE_SINGLETON") || "SCOPE_SINGLETON".equals(trimmed)) return "singleton";
        if (trimmed.endsWith(".SCOPE_PROTOTYPE") || "SCOPE_PROTOTYPE".equals(trimmed)) return "prototype";
        if (trimmed.endsWith(".SCOPE_REQUEST") || "SCOPE_REQUEST".equals(trimmed)) return "request";
        if (trimmed.endsWith(".SCOPE_SESSION") || "SCOPE_SESSION".equals(trimmed)) return "session";
        if (trimmed.endsWith(".SCOPE_APPLICATION") || "SCOPE_APPLICATION".equals(trimmed)) return "application";
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String attr(XMLStreamReader reader, String localName) {
        String direct = reader.getAttributeValue(null, localName);
        if (direct != null) return direct.trim();
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if (localName.equals(reader.getAttributeLocalName(i)) && safe(reader.getAttributeNamespace(i)).isEmpty()) {
                return safe(reader.getAttributeValue(i));
            }
        }
        return "";
    }

    private static int lineOf(XMLStreamReader reader) {
        if (reader == null) return 1;
        Location loc = reader.getLocation();
        return loc != null && loc.getLineNumber() > 0 ? loc.getLineNumber() : 1;
    }

    private static int parseIntOrDefault(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return "";
    }

    private static List<String> sortedDistinct(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).distinct().sorted().toList();
    }

    private static String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/').trim();
    }

    private static String simpleName(String fqn) {
        if (fqn == null) return "";
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private static String safe(String v) {
        return v == null ? "" : v.trim();
    }

    private static String safeMsg(Throwable t) {
        String msg = t.getMessage();
        return (msg == null || msg.isBlank()) ? t.getClass().getSimpleName() : msg;
    }
}
