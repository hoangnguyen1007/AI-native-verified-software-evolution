package com.evolution.analysis.policy;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.query.ArchitectureQuery.Scope;
import java.io.StringReader;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.*;
import static com.evolution.analysis.policy.ArchitecturePolicy.*;

/** Strict external-policy compiler. */
public final class PolicyCompiler {
    public static final VersionedIdentifier VERSION = new VersionedIdentifier("policy.compiler", "m6.1-v1");
    public static final VersionedIdentifier SYNTAX = new VersionedIdentifier("policy.yaml-parser", "snakeyaml-2.6-m6.1-v1");
    public record Limits(int maxBytes, int maxRules, int maxNodes) {
        public Limits { if (maxBytes < 1 || maxBytes > 1_048_576 || maxRules < 1 || maxRules > 256 || maxNodes < 1 || maxNodes > 20_000)
            throw new IllegalArgumentException("Invalid policy compiler limits"); }
        public static Limits defaults() { return new Limits(262_144, 256, 20_000); }
    }
    /** 1-based code-point coordinates, end exclusive; path is a schema path without input key values. */
    public record Location(int line, int column, int endLine, int endColumn) {}
    public record Problem(String code, String path, Optional<Location> location) {}
    public record Result(ContentDigest source, Limits limits, AssessmentStatus status, Optional<ArchitecturePolicy> policy,
                         Map<String,Location> locations, List<Problem> problems, List<CapabilityGapRecord> gaps) {
        public Result {
            Objects.requireNonNull(source);Objects.requireNonNull(limits);Objects.requireNonNull(status);Objects.requireNonNull(policy);
            locations = Collections.unmodifiableMap(new TreeMap<>(locations)); problems = List.copyOf(problems); gaps = List.copyOf(gaps);
            if(status!=AssessmentStatus.COMPLETE&&status!=AssessmentStatus.WITHHELD
                    ||(status==AssessmentStatus.COMPLETE)!=(policy.isPresent()&&problems.isEmpty()&&gaps.isEmpty())
                    ||status==AssessmentStatus.WITHHELD&&(policy.isPresent()||problems.isEmpty()||gaps.isEmpty()))
                throw new IllegalArgumentException("Invalid policy compilation state");
        }
        public ContentDigest identity() { return ContentDigest.sha256Utf8(CanonicalJson.write(List.of(VERSION, SYNTAX, this))); }
    }
    public Result compile(byte[] bytes, EvidenceContext context, Limits limits) {
        Objects.requireNonNull(bytes); Objects.requireNonNull(context); Objects.requireNonNull(limits);
        byte[] input = bytes.clone(); var source = ContentDigest.sha256(input); var parser = new Parser(limits);
        ArchitecturePolicy policy = null;
        try {
            if (input.length > limits.maxBytes()) throw new Invalid("INPUT_LIMIT", "$", null);
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(input)).toString();
            var options = new LoaderOptions(); options.setAllowDuplicateKeys(false); options.setAllowRecursiveKeys(false);
            options.setMaxAliasesForCollections(0); options.setNestingDepthLimit(24); options.setCodePointLimit(limits.maxBytes());
            // Compose nodes only: no target class construction, YAML merge or polymorphic deserialization.
            Node root = new Yaml(new SafeConstructor(options)).compose(new StringReader(text));
            if (root == null) throw new Invalid("EMPTY_POLICY", "$", null);
            parser.scan(root, "$", 0); policy = parser.policy(root);
        } catch (CharacterCodingException invalid) { parser.problem("INVALID_UTF8", "$", null);
        } catch (Invalid invalid) { parser.problem(invalid.code, invalid.path, invalid.node);
        } catch (YAMLException invalid) { parser.problem("INVALID_YAML", "$", null); }
        var gaps = parser.problems.stream().map(p -> PolicyGaps.create(context, VERSION, source, p.code(), List.of(limits, p))).toList();
        return new Result(source, limits, parser.problems.isEmpty() ? AssessmentStatus.COMPLETE : AssessmentStatus.WITHHELD,
                parser.problems.isEmpty() ? Optional.ofNullable(policy) : Optional.empty(), parser.locations, parser.problems, gaps);
    }
    private static final class Invalid extends RuntimeException {
        final String code, path; final Node node;
        Invalid(String code, String path, Node node) { this.code=code; this.path=path; this.node=node; }
    }
    private static final class Parser {
        final Limits limits; int nodes;
        final Map<String,Location> locations = new TreeMap<>(); final List<Problem> problems = new ArrayList<>();
        Parser(Limits limits) { this.limits=limits; }
        void problem(String code, String path, Node node) { problems.add(new Problem(code, path, Optional.ofNullable(node).map(Parser::location))); }
        static Location location(Node node) { var a=node.getStartMark(); var b=node.getEndMark();
            return new Location(a.getLine()+1,a.getColumn()+1,b.getLine()+1,b.getColumn()+1); }
        void scan(Node node, String path, int depth) {
            if (++nodes > limits.maxNodes() || depth > 24) throw new Invalid("AST_LIMIT", path, node);
            if (node.getAnchor()!=null) throw new Invalid("YAML_ANCHOR_UNSUPPORTED", path, node);
            if (node instanceof MappingNode map && Tag.MAP.equals(node.getTag())) {
                var keys = new HashSet<String>(); int index=0;
                for (var tuple : map.getValue()) {
                    String key = scalar(tuple.getKeyNode(), path);
                    if (!keys.add(key)) throw new Invalid("DUPLICATE_KEY", path, tuple.getKeyNode());
                    // Do not publish unknown input keys as diagnostic paths (they can contain secrets).
                    scan(tuple.getKeyNode(), path+".#key"+index, depth+1); scan(tuple.getValueNode(), path+".#value"+index++, depth+1);
                }
            } else if (node instanceof SequenceNode seq && Tag.SEQ.equals(node.getTag())) {
                for (int i=0;i<seq.getValue().size();i++) scan(seq.getValue().get(i),path+"["+i+"]",depth+1);
            } else if (!(node instanceof ScalarNode) || !Tag.STR.equals(node.getTag())) throw new Invalid("UNSUPPORTED_YAML_TYPE", path, node);
            else scalar(node,path);
        }
        ArchitecturePolicy policy(Node node) {
            var map = map(node,"$",Set.of("schema","id","version","rules"));
            if (!SCHEMA.equals(scalar(required(map,"schema","$"),"$.schema"))) throw new Invalid("UNSUPPORTED_SCHEMA","$.schema",map.get("schema"));
            String id = scalar(required(map,"id","$"),"$.id"), version = scalar(required(map,"version","$"),"$.version");
            var sequence = sequence(required(map,"rules","$"),"$.rules");
            if (sequence.isEmpty() || sequence.size()>limits.maxRules()) throw new Invalid("RULE_COUNT_LIMIT","$.rules",map.get("rules"));
            var rules = new ArrayList<Rule>(); var ids=new HashSet<String>();
            for (int i=0;i<sequence.size();i++) {
                String path="$.rules["+i+"]"; Node value=sequence.get(i);
                try { var rule=rule(value,path); if (!ids.add(rule.header().id())) throw new Invalid("DUPLICATE_RULE_ID",path,value); rules.add(rule);
                } catch (Invalid invalid) { problem(invalid.code,invalid.path,invalid.node);
                } catch (IllegalArgumentException invalid) { problem("INVALID_RULE",path,value); }
            }
            try { return problems.isEmpty()?new ArchitecturePolicy(id,version,rules):null;
            } catch (IllegalArgumentException invalid) { throw new Invalid("INVALID_POLICY","$",node); }
        }
        Rule rule(Node node,String path) {
            var map=map(node,path,Set.of("id","version","kind","severity","scope","relationships","from","to","allowed","layers","within"));
            var kind=enumeration(Kind.class,required(map,"kind",path),path+".kind");
            var expected=new HashSet<>(Set.of("id","version","kind","severity","scope","relationships"));
            expected.addAll(switch(kind) {case FORBIDDEN_DEPENDENCY -> Set.of("from","to"); case BOUNDARY -> Set.of("from","allowed");
                case LAYER_BOUNDARY -> Set.of("layers","allowed"); case ACYCLIC -> Set.of("within");});
            if (!map.keySet().equals(expected)) throw new Invalid("RULE_FIELDS",path,node);
            var header=new Header(scalar(map.get("id"),path+".id"),scalar(map.get("version"),path+".version"),
                    enumeration(Severity.class,map.get("severity"),path+".severity"),enumeration(Scope.class,map.get("scope"),path+".scope"),
                    strings(map.get("relationships"),path+".relationships",false));
            return switch(kind) {
                case FORBIDDEN_DEPENDENCY -> new Forbidden(header,selector(map.get("from"),path+".from"),selector(map.get("to"),path+".to"));
                case BOUNDARY -> new Boundary(header,selector(map.get("from"),path+".from"),selector(map.get("allowed"),path+".allowed"));
                case ACYCLIC -> new Cycles(header,selector(map.get("within"),path+".within"));
                case LAYER_BOUNDARY -> {
                    var layers=new ArrayList<Layer>();var values=sequence(map.get("layers"),path+".layers");
                    for (int i=0;i<values.size();i++) {String p=path+".layers["+i+"]";var l=map(values.get(i),p,Set.of("name","select"));
                        layers.add(new Layer(scalar(required(l,"name",p),p+".name"),selector(required(l,"select",p),p+".select")));}
                    // Sequence avoids arbitrary mapping keys and makes duplicate source rows explicit.
                    var allowed=new TreeMap<String,Set<String>>();var rows=sequence(map.get("allowed"),path+".allowed");
                    for(int i=0;i<rows.size();i++) {String p=path+".allowed["+i+"]";var row=map(rows.get(i),p,Set.of("from","to"));
                        var name=scalar(required(row,"from",p),p+".from");
                        if(allowed.putIfAbsent(name,strings(required(row,"to",p),p+".to",true))!=null) throw new Invalid("DUPLICATE_LAYER_SOURCE",p,rows.get(i));}
                    yield new Layers(header,layers,allowed);
                }
            };
        }
        Selector selector(Node node,String path) {
            var m=map(node,path,Set.of("packages","names","modules","origins","sourceSets","identities"));
            return new Selector(optionalStrings(m,"packages",path),optionalStrings(m,"names",path),optionalStrings(m,"modules",path),
                    optionalStrings(m,"origins",path),optionalStrings(m,"sourceSets",path),optionalStrings(m,"identities",path));
        }
        Set<String> optionalStrings(Map<String,Node> map,String key,String path) { return map.containsKey(key)?strings(map.get(key),path+"."+key,false):Set.of(); }
        Set<String> strings(Node node,String path,boolean emptyAllowed) {
            var values=sequence(node,path); if ((!emptyAllowed&&values.isEmpty())||values.size()>128) throw new Invalid("INVALID_STRING_LIST",path,node);
            var result=new TreeSet<String>(); for(int i=0;i<values.size();i++)if(!result.add(scalar(values.get(i),path+"["+i+"]"))) throw new Invalid("DUPLICATE_VALUE",path,node);
            return result;
        }
        String scalar(Node node,String path) {
            if (!(node instanceof ScalarNode s) || !Tag.STR.equals(node.getTag())) throw new Invalid("EXPECTED_STRING",path,node);
            if (s.getValue().length()>512 || s.getValue().indexOf('\0')>=0) throw new Invalid("SCALAR_LIMIT",path,node);
            try { CanonicalJson.write(s.getValue()); } catch (IllegalArgumentException invalid) { throw new Invalid("INVALID_UNICODE",path,node); }
            locations.put(path,location(node)); return s.getValue();
        }
        List<Node> sequence(Node node,String path) {
            if (!(node instanceof SequenceNode s) || !Tag.SEQ.equals(node.getTag())) throw new Invalid("EXPECTED_SEQUENCE",path,node);
            locations.put(path,location(node)); return s.getValue();
        }
        Map<String,Node> map(Node node,String path,Set<String> allowed) {
            if (!(node instanceof MappingNode m) || !Tag.MAP.equals(node.getTag())) throw new Invalid("EXPECTED_MAPPING",path,node);
            locations.put(path,location(node)); var result=new LinkedHashMap<String,Node>();
            for (var t:m.getValue()) {String key=scalar(t.getKeyNode(),path+".#key");
                if (!allowed.contains(key)) throw new Invalid("UNKNOWN_FIELD",path,t.getKeyNode());
                result.put(key,t.getValueNode());}
            return result;
        }
        Node required(Map<String,Node> m,String key,String path) {var node=m.get(key);if(node==null)throw new Invalid("MISSING_FIELD",path+"."+key,null);return node;}
        <E extends Enum<E>> E enumeration(Class<E> type,Node node,String path) {
            try { return Enum.valueOf(type,scalar(node,path)); } catch (IllegalArgumentException invalid) { throw new Invalid("UNKNOWN_ENUM",path,node); }
        }
    }

    // ─── WP2.2: Bounded Declarative RSL-Style Metadata Policy Compiler & Pack Activation ───

    public static final String METADATA_CONSISTENCY_PACK_ID = MetadataConsistencyRulePack.PACK_ID;
    public static final VersionedIdentifier METADATA_POLICY_VERSION =
            new VersionedIdentifier("policy.metadata-rsl", "wp2.2-v1");

    /** Closed finite SKG / Spring / XML metadata domains available to bounded RSL rules (I2: guaranteed termination). */
    public enum MetadataDomain {
        XML_BEANS, XML_PROPERTIES, XML_CONSTRUCTOR_ARGS, XML_IMPORTS,
        SPRING_BEANS, LIFECYCLE_METHODS, VALUE_PLACEHOLDERS, CONDITIONAL_PROPERTIES,
        CONFIG_PROPERTIES_BEANS, BEAN_LOOKUPS, FEIGN_ENDPOINTS, CONTROLLER_ENDPOINTS,
        TYPES, METHODS, FIELDS, ANNOTATIONS, CONFIG_KEYS
    }

    /** Deterministic non-scripting comparison operators (zero regex DoS, zero arbitrary code execution). */
    public enum PredicateOp {
        EQUALS, NOT_EQUALS, CONTAINS, STARTS_WITH, ENDS_WITH, MATCHES_NORMALIZED, IN, PRESENT, ABSENT, IS_TRUE, IS_FALSE
    }

    /** Bounded declarative assertion operators for RSL-style metadata consistency rules. */
    public enum AssertOp {
        EXISTS, NOT_EXISTS, PREDICATE,
        CLASS_RESOLVABLE, METHOD_EXISTS, PROPERTY_EXISTS, CONFIG_KEY_BOUND, BEAN_TRUTH_SATISFIED, ENDPOINT_MATCHED
    }

    public record MetadataPredicate(
            String field,
            PredicateOp op,
            Optional<String> value,
            Optional<String> rhsField,
            Set<String> values
    ) {
        public MetadataPredicate {
            field = validateFieldRef(field, "predicate field");
            Objects.requireNonNull(op, "op");
            value = value == null ? Optional.empty() : value;
            rhsField = rhsField == null ? Optional.empty() : rhsField.map(r -> validateFieldRef(r, "rhsField"));
            values = Collections.unmodifiableSet(new TreeSet<>(values == null ? Set.of() : values));
            if (values.size() > 128) throw new IllegalArgumentException("Predicate values limit exceeded");
            if (op == PredicateOp.IN && values.isEmpty()) {
                throw new IllegalArgumentException("IN predicate requires non-empty values");
            }
            if (Set.of(PredicateOp.EQUALS, PredicateOp.NOT_EQUALS, PredicateOp.CONTAINS,
                    PredicateOp.STARTS_WITH, PredicateOp.ENDS_WITH, PredicateOp.MATCHES_NORMALIZED).contains(op)
                    && value.isEmpty() && rhsField.isEmpty()) {
                throw new IllegalArgumentException(op + " predicate requires value or rhsField");
            }
        }
    }

    public record ForEachClause(String variable, MetadataDomain domain) {
        public ForEachClause {
            variable = validateVarName(variable, "forEach variable");
            Objects.requireNonNull(domain, "domain");
        }
    }

    public record ExistsClause(String variable, MetadataDomain domain, List<MetadataPredicate> where) {
        public ExistsClause {
            variable = validateVarName(variable, "exists variable");
            Objects.requireNonNull(domain, "domain");
            where = List.copyOf(Objects.requireNonNull(where, "where"));
            if (where.isEmpty() || where.size() > 32) {
                throw new IllegalArgumentException("exists clause requires 1..32 where predicates");
            }
        }
    }

    public record AssertClause(
            AssertOp op,
            Optional<ExistsClause> exists,
            Optional<MetadataPredicate> predicate,
            Optional<String> targetField,
            String message
    ) {
        public AssertClause {
            Objects.requireNonNull(op, "op");
            exists = exists == null ? Optional.empty() : exists;
            predicate = predicate == null ? Optional.empty() : predicate;
            targetField = targetField == null ? Optional.empty() : targetField.map(f -> validateFieldRef(f, "targetField"));
            message = Objects.requireNonNull(message, "message").strip();
            if (message.isEmpty() || message.length() > 512) {
                throw new IllegalArgumentException("assert message must be 1..512 characters");
            }
            if ((op == AssertOp.EXISTS || op == AssertOp.NOT_EXISTS) && exists.isEmpty()) {
                throw new IllegalArgumentException(op + " assertion requires exists clause");
            }
            if (op == AssertOp.PREDICATE && predicate.isEmpty()) {
                throw new IllegalArgumentException("PREDICATE assertion requires predicate");
            }
        }
    }

    public record MetadataRule(
            String id,
            String version,
            Severity severity,
            ForEachClause forEach,
            List<MetadataPredicate> where,
            AssertClause assertion
    ) {
        public MetadataRule {
            id = ContractChecks.namespacedId(id, "metadata rule id");
            version = ContractChecks.token(version == null || version.isBlank() ? "1" : version, "metadata rule version");
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(forEach, "forEach");
            where = List.copyOf(where == null ? List.of() : where);
            if (where.size() > 32) throw new IllegalArgumentException("where clause supports at most 32 predicates");
            Objects.requireNonNull(assertion, "assertion");
            // Validate all bound variable references are in scope (static safety, no free variables)
            var outerVars = Set.of(forEach.variable());
            for (var p : where) validateBoundVars(p, outerVars);
            assertion.predicate().ifPresent(p -> validateBoundVars(p, outerVars));
            assertion.targetField().ifPresent(f -> validateVarInScope(f, outerVars));
            assertion.exists().ifPresent(ex -> {
                if (ex.variable().equals(forEach.variable())) {
                    throw new IllegalArgumentException("exists variable cannot shadow forEach variable");
                }
                var innerVars = Set.of(forEach.variable(), ex.variable());
                for (var p : ex.where()) validateBoundVars(p, innerVars);
            });
        }

        public ContentDigest identity() {
            return ContentDigest.sha256Utf8(CanonicalJson.write(List.of(METADATA_POLICY_VERSION, this)));
        }
    }

    public record MetadataPackActivation(String packId, Set<String> includeRules, Set<String> excludeRules) {
        public MetadataPackActivation {
            packId = ContractChecks.token(packId, "packId");
            if (!METADATA_CONSISTENCY_PACK_ID.equals(packId)) {
                throw new IllegalArgumentException("Unknown metadata policy pack: " + packId);
            }
            includeRules = Collections.unmodifiableSet(new TreeSet<>(includeRules == null ? Set.of() : includeRules));
            excludeRules = Collections.unmodifiableSet(new TreeSet<>(excludeRules == null ? Set.of() : excludeRules));
            if (!MetadataConsistencyRulePack.ALL_RULE_IDS.containsAll(includeRules)
                    || !MetadataConsistencyRulePack.ALL_RULE_IDS.containsAll(excludeRules)) {
                throw new IllegalArgumentException("Unknown rule id in metadata-consistency-pack selection");
            }
        }
    }

    public record MetadataPolicy(
            String id,
            String version,
            List<MetadataPackActivation> packs,
            List<MetadataRule> rules
    ) {
        public MetadataPolicy {
            id = ContractChecks.namespacedId(id, "metadata policy id");
            version = ContractChecks.token(version, "metadata policy version");
            packs = ContractChecks.sortedDistinct(packs == null ? List.of() : packs,
                    Comparator.comparing(MetadataPackActivation::packId), "metadata packs");
            rules = ContractChecks.sortedDistinct(rules == null ? List.of() : rules,
                    Comparator.comparing(MetadataRule::id), "metadata rules");
            if (packs.isEmpty() && rules.isEmpty()) {
                throw new IllegalArgumentException("Metadata policy requires at least one pack activation or metadata rule");
            }
            if (rules.size() > 256) {
                throw new IllegalArgumentException("Metadata policy supports at most 256 custom rules");
            }
        }

        public ContentDigest identity() {
            return ContentDigest.sha256Utf8(CanonicalJson.write(List.of(METADATA_POLICY_VERSION, this)));
        }
    }

    public record MetadataCompilationResult(
            ContentDigest source,
            Limits limits,
            AssessmentStatus status,
            Optional<MetadataPolicy> policy,
            Map<String, Location> locations,
            List<Problem> problems,
            List<CapabilityGapRecord> gaps
    ) {
        public MetadataCompilationResult {
            Objects.requireNonNull(source); Objects.requireNonNull(limits);
            Objects.requireNonNull(status); Objects.requireNonNull(policy);
            locations = Collections.unmodifiableMap(new TreeMap<>(locations));
            problems = List.copyOf(problems);
            gaps = List.copyOf(gaps);
            if (status != AssessmentStatus.COMPLETE && status != AssessmentStatus.WITHHELD
                    || (status == AssessmentStatus.COMPLETE) != (policy.isPresent() && problems.isEmpty() && gaps.isEmpty())
                    || status == AssessmentStatus.WITHHELD && (policy.isPresent() || problems.isEmpty() || gaps.isEmpty())) {
                throw new IllegalArgumentException("Invalid metadata policy compilation state");
            }
        }

        public ContentDigest identity() {
            return ContentDigest.sha256Utf8(CanonicalJson.write(List.of(METADATA_POLICY_VERSION, SYNTAX, this)));
        }
    }

    /** Activates a built-in metadata consistency pack directly with exact deterministic identity. */
    public MetadataCompilationResult compileBuiltInMetadataPack(String packId, EvidenceContext context) {
        Objects.requireNonNull(packId, "packId");
        Objects.requireNonNull(context, "context");
        String yaml = "schema: architecture-policy:v1\nid: builtin." + packId + "\nversion: \"1\"\npacks:\n  - " + packId + "\n";
        return compileMetadataPolicy(yaml.getBytes(StandardCharsets.UTF_8), context, Limits.defaults());
    }

    /**
     * Compiles bounded declarative RSL-style metadata rules ({@code forEach}, {@code where}, {@code exists}, {@code assert})
     * and built-in pack activations ({@code metadata-consistency-pack}) from {@code architecture-policy:v1} YAML.
     */
    public MetadataCompilationResult compileMetadataPolicy(byte[] bytes, EvidenceContext context, Limits limits) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(limits, "limits");
        byte[] input = bytes.clone();
        var source = ContentDigest.sha256(input);
        var parser = new MetadataParser(limits);
        MetadataPolicy policy = null;
        try {
            if (input.length > limits.maxBytes()) throw new Invalid("INPUT_LIMIT", "$", null);
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(input)).toString();
            var options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setAllowRecursiveKeys(false);
            options.setMaxAliasesForCollections(0);
            options.setNestingDepthLimit(24);
            options.setCodePointLimit(limits.maxBytes());
            Node root = new Yaml(new SafeConstructor(options)).compose(new StringReader(text));
            if (root == null) throw new Invalid("EMPTY_POLICY", "$", null);
            parser.scan(root, "$", 0);
            policy = parser.parseMetadataPolicy(root);
        } catch (CharacterCodingException invalid) {
            parser.problem("INVALID_UTF8", "$", null);
        } catch (Invalid invalid) {
            parser.problem(invalid.code, invalid.path, invalid.node);
        } catch (YAMLException invalid) {
            parser.problem("INVALID_YAML", "$", null);
        }
        var gaps = parser.problems.stream()
                .map(p -> PolicyGaps.create(context, METADATA_POLICY_VERSION, source, p.code(), List.of(limits, p)))
                .toList();
        return new MetadataCompilationResult(
                source, limits,
                parser.problems.isEmpty() ? AssessmentStatus.COMPLETE : AssessmentStatus.WITHHELD,
                parser.problems.isEmpty() ? Optional.ofNullable(policy) : Optional.empty(),
                parser.locations, parser.problems, gaps);
    }

    private static String validateVarName(String v, String label) {
        if (v == null || !v.matches("[a-zA-Z_][a-zA-Z0-9_]{0,31}")) {
            throw new IllegalArgumentException("Invalid " + label + ": " + v);
        }
        return v;
    }

    private static String validateFieldRef(String ref, String label) {
        if (ref == null || !ref.matches("[a-zA-Z_][a-zA-Z0-9_]{0,31}\\.[a-zA-Z_][a-zA-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Invalid " + label + " (expected var.field): " + ref);
        }
        return ref;
    }

    private static void validateBoundVars(MetadataPredicate p, Set<String> scope) {
        validateVarInScope(p.field(), scope);
        p.rhsField().ifPresent(r -> validateVarInScope(r, scope));
    }

    private static void validateVarInScope(String fieldRef, Set<String> scope) {
        String var = fieldRef.substring(0, fieldRef.indexOf('.'));
        if (!scope.contains(var)) {
            throw new IllegalArgumentException("Unbound variable '" + var + "' in field reference '" + fieldRef + "'");
        }
    }

    private static final class MetadataParser {
        final Limits limits;
        int nodes;
        final Map<String, Location> locations = new TreeMap<>();
        final List<Problem> problems = new ArrayList<>();

        MetadataParser(Limits limits) {
            this.limits = limits;
        }

        void problem(String code, String path, Node node) {
            problems.add(new Problem(code, path, Optional.ofNullable(node).map(Parser::location)));
        }

        void scan(Node node, String path, int depth) {
            if (++nodes > limits.maxNodes() || depth > 24) throw new Invalid("AST_LIMIT", path, node);
            if (node.getAnchor() != null) throw new Invalid("YAML_ANCHOR_UNSUPPORTED", path, node);
            if (node instanceof MappingNode map && Tag.MAP.equals(node.getTag())) {
                var keys = new HashSet<String>();
                int index = 0;
                for (var tuple : map.getValue()) {
                    String key = scalar(tuple.getKeyNode(), path);
                    if (!keys.add(key)) throw new Invalid("DUPLICATE_KEY", path, tuple.getKeyNode());
                    scan(tuple.getKeyNode(), path + ".#key" + index, depth + 1);
                    scan(tuple.getValueNode(), path + ".#value" + index++, depth + 1);
                }
            } else if (node instanceof SequenceNode seq && Tag.SEQ.equals(node.getTag())) {
                for (int i = 0; i < seq.getValue().size(); i++) {
                    scan(seq.getValue().get(i), path + "[" + i + "]", depth + 1);
                }
            } else if (node instanceof ScalarNode sc
                    && (Tag.STR.equals(sc.getTag()) || Tag.BOOL.equals(sc.getTag()))) {
                scalar(node, path);
            } else {
                throw new Invalid("UNSUPPORTED_YAML_TYPE", path, node);
            }
        }

        MetadataPolicy parseMetadataPolicy(Node root) {
            var map = map(root, "$", Set.of("schema", "id", "version", "packs",
                    METADATA_CONSISTENCY_PACK_ID, "metadataRules", "rules"));
            if (!SCHEMA.equals(scalar(required(map, "schema", "$"), "$.schema"))) {
                throw new Invalid("UNSUPPORTED_SCHEMA", "$.schema", map.get("schema"));
            }
            String id = scalar(required(map, "id", "$"), "$.id");
            String version = scalar(required(map, "version", "$"), "$.version");

            var packsById = new TreeMap<String, MetadataPackActivation>();
            if (map.containsKey("packs")) {
                var packSeq = sequence(map.get("packs"), "$.packs");
                for (int i = 0; i < packSeq.size(); i++) {
                    String pPath = "$.packs[" + i + "]";
                    Node pNode = packSeq.get(i);
                    try {
                        String packId = scalar(pNode, pPath);
                        var act = new MetadataPackActivation(packId, Set.of(), Set.of());
                        if (packsById.putIfAbsent(act.packId(), act) != null) {
                            throw new Invalid("DUPLICATE_PACK_ID", pPath, pNode);
                        }
                    } catch (Invalid inv) {
                        problem(inv.code, inv.path, inv.node);
                    } catch (IllegalArgumentException inv) {
                        problem("UNKNOWN_PACK", pPath, pNode);
                    }
                }
            }
            if (map.containsKey(METADATA_CONSISTENCY_PACK_ID)) {
                String pPath = "$." + METADATA_CONSISTENCY_PACK_ID;
                Node pNode = map.get(METADATA_CONSISTENCY_PACK_ID);
                try {
                    var pm = map(pNode, pPath, Set.of("enabled", "includeRules", "excludeRules"));
                    boolean enabled = !pm.containsKey("enabled") || Boolean.parseBoolean(scalar(pm.get("enabled"), pPath + ".enabled"));
                    if (enabled) {
                        Set<String> inc = pm.containsKey("includeRules")
                                ? stringSet(pm.get("includeRules"), pPath + ".includeRules", true) : Set.of();
                        Set<String> exc = pm.containsKey("excludeRules")
                                ? stringSet(pm.get("excludeRules"), pPath + ".excludeRules", true) : Set.of();
                        var act = new MetadataPackActivation(METADATA_CONSISTENCY_PACK_ID, inc, exc);
                        if (packsById.putIfAbsent(act.packId(), act) != null) {
                            throw new Invalid("DUPLICATE_PACK_ID", pPath, pNode);
                        }
                    }
                } catch (Invalid inv) {
                    problem(inv.code, inv.path, inv.node);
                } catch (IllegalArgumentException inv) {
                    problem("INVALID_PACK_CONFIG", pPath, pNode);
                }
            }

            var rules = new ArrayList<MetadataRule>();
            if (map.containsKey("metadataRules")) {
                var ruleSeq = sequence(map.get("metadataRules"), "$.metadataRules");
                if (ruleSeq.size() > limits.maxRules()) {
                    throw new Invalid("RULE_COUNT_LIMIT", "$.metadataRules", map.get("metadataRules"));
                }
                var seenIds = new HashSet<String>();
                for (int i = 0; i < ruleSeq.size(); i++) {
                    String rPath = "$.metadataRules[" + i + "]";
                    Node rNode = ruleSeq.get(i);
                    try {
                        var rule = parseRule(rNode, rPath);
                        if (!seenIds.add(rule.id())) {
                            throw new Invalid("DUPLICATE_RULE_ID", rPath, rNode);
                        }
                        rules.add(rule);
                    } catch (Invalid inv) {
                        problem(inv.code, inv.path, inv.node);
                    } catch (IllegalArgumentException inv) {
                        problem("INVALID_METADATA_RULE", rPath, rNode);
                    }
                }
            }

            if (problems.isEmpty() && packsById.isEmpty() && rules.isEmpty()) {
                throw new Invalid("EMPTY_METADATA_POLICY", "$", root);
            }
            try {
                return problems.isEmpty()
                        ? new MetadataPolicy(id, version, List.copyOf(packsById.values()), rules)
                        : null;
            } catch (IllegalArgumentException invalid) {
                throw new Invalid("INVALID_POLICY", "$", root);
            }
        }

        private MetadataRule parseRule(Node node, String path) {
            var m = map(node, path, Set.of("id", "version", "severity", "forEach", "where", "assert"));
            String id = scalar(required(m, "id", path), path + ".id");
            String version = m.containsKey("version") ? scalar(m.get("version"), path + ".version") : "1";
            Severity sev = enumeration(Severity.class, required(m, "severity", path), path + ".severity");
            ForEachClause forEach = parseForEach(required(m, "forEach", path), path + ".forEach");
            List<MetadataPredicate> where = m.containsKey("where")
                    ? parsePredicates(m.get("where"), path + ".where") : List.of();
            AssertClause assertion = parseAssert(required(m, "assert", path), path + ".assert");
            return new MetadataRule(id, version, sev, forEach, where, assertion);
        }

        private ForEachClause parseForEach(Node node, String path) {
            var m = map(node, path, Set.of("var", "in"));
            String varName = scalar(required(m, "var", path), path + ".var");
            MetadataDomain domain = enumeration(MetadataDomain.class, required(m, "in", path), path + ".in");
            return new ForEachClause(varName, domain);
        }

        private ExistsClause parseExists(Node node, String path) {
            var m = map(node, path, Set.of("var", "in", "where"));
            String varName = scalar(required(m, "var", path), path + ".var");
            MetadataDomain domain = enumeration(MetadataDomain.class, required(m, "in", path), path + ".in");
            var where = parsePredicates(required(m, "where", path), path + ".where");
            return new ExistsClause(varName, domain, where);
        }

        private AssertClause parseAssert(Node node, String path) {
            var m = map(node, path, Set.of("op", "exists", "notExists", "predicate", "field", "value", "rhsField", "values", "targetField", "message"));
            String message = scalar(required(m, "message", path), path + ".message");
            if (m.containsKey("exists")) {
                if (m.containsKey("notExists") || m.containsKey("predicate")) {
                    throw new Invalid("AMBIGUOUS_ASSERT", path, node);
                }
                return new AssertClause(AssertOp.EXISTS, Optional.of(parseExists(m.get("exists"), path + ".exists")),
                        Optional.empty(), Optional.empty(), message);
            }
            if (m.containsKey("notExists")) {
                if (m.containsKey("predicate")) {
                    throw new Invalid("AMBIGUOUS_ASSERT", path, node);
                }
                return new AssertClause(AssertOp.NOT_EXISTS, Optional.of(parseExists(m.get("notExists"), path + ".notExists")),
                        Optional.empty(), Optional.empty(), message);
            }
            if (m.containsKey("predicate")) {
                return new AssertClause(AssertOp.PREDICATE, Optional.empty(),
                        Optional.of(parsePredicate(m.get("predicate"), path + ".predicate")), Optional.empty(), message);
            }
            if (m.containsKey("op")) {
                String rawOp = scalar(m.get("op"), path + ".op");
                // Allow inline predicate shorthand when field is present
                if (m.containsKey("field")) {
                    PredicateOp pop;
                    try { pop = PredicateOp.valueOf(rawOp); }
                    catch (IllegalArgumentException e) { throw new Invalid("UNKNOWN_ENUM", path + ".op", m.get("op")); }
                    Optional<String> val = m.containsKey("value") ? Optional.of(scalar(m.get("value"), path + ".value")) : Optional.empty();
                    Optional<String> rhs = m.containsKey("rhsField") ? Optional.of(scalar(m.get("rhsField"), path + ".rhsField")) : Optional.empty();
                    Set<String> vals = m.containsKey("values") ? stringSet(m.get("values"), path + ".values", false) : Set.of();
                    var pred = new MetadataPredicate(scalar(m.get("field"), path + ".field"), pop, val, rhs, vals);
                    return new AssertClause(AssertOp.PREDICATE, Optional.empty(), Optional.of(pred), Optional.empty(), message);
                }
                AssertOp aop;
                try { aop = AssertOp.valueOf(rawOp); }
                catch (IllegalArgumentException e) { throw new Invalid("UNKNOWN_ENUM", path + ".op", m.get("op")); }
                Optional<String> targetField = m.containsKey("targetField")
                        ? Optional.of(scalar(m.get("targetField"), path + ".targetField")) : Optional.empty();
                return new AssertClause(aop, Optional.empty(), Optional.empty(), targetField, message);
            }
            throw new Invalid("MISSING_ASSERT_BODY", path, node);
        }

        private List<MetadataPredicate> parsePredicates(Node node, String path) {
            var seq = sequence(node, path);
            if (seq.size() > 32) throw new Invalid("PREDICATE_COUNT_LIMIT", path, node);
            var out = new ArrayList<MetadataPredicate>(seq.size());
            for (int i = 0; i < seq.size(); i++) {
                out.add(parsePredicate(seq.get(i), path + "[" + i + "]"));
            }
            return out;
        }

        private MetadataPredicate parsePredicate(Node node, String path) {
            var m = map(node, path, Set.of("field", "op", "value", "rhsField", "values"));
            String field = scalar(required(m, "field", path), path + ".field");
            PredicateOp op = enumeration(PredicateOp.class, required(m, "op", path), path + ".op");
            Optional<String> val = m.containsKey("value") ? Optional.of(scalar(m.get("value"), path + ".value")) : Optional.empty();
            Optional<String> rhs = m.containsKey("rhsField") ? Optional.of(scalar(m.get("rhsField"), path + ".rhsField")) : Optional.empty();
            Set<String> vals = m.containsKey("values") ? stringSet(m.get("values"), path + ".values", false) : Set.of();
            return new MetadataPredicate(field, op, val, rhs, vals);
        }

        private Set<String> stringSet(Node node, String path, boolean emptyAllowed) {
            var values = sequence(node, path);
            if ((!emptyAllowed && values.isEmpty()) || values.size() > 128) {
                throw new Invalid("INVALID_STRING_LIST", path, node);
            }
            var result = new TreeSet<String>();
            for (int i = 0; i < values.size(); i++) {
                if (!result.add(scalar(values.get(i), path + "[" + i + "]"))) {
                    throw new Invalid("DUPLICATE_VALUE", path, node);
                }
            }
            return result;
        }

        private String scalar(Node node, String path) {
            if (!(node instanceof ScalarNode s) || (!Tag.STR.equals(s.getTag()) && !Tag.BOOL.equals(s.getTag()))) {
                throw new Invalid("EXPECTED_STRING", path, node);
            }
            if (s.getValue().length() > 512 || s.getValue().indexOf('\0') >= 0) {
                throw new Invalid("SCALAR_LIMIT", path, node);
            }
            try { CanonicalJson.write(s.getValue()); } catch (IllegalArgumentException invalid) {
                throw new Invalid("INVALID_UNICODE", path, node);
            }
            locations.put(path, Parser.location(node));
            return s.getValue();
        }

        private List<Node> sequence(Node node, String path) {
            if (!(node instanceof SequenceNode s) || !Tag.SEQ.equals(node.getTag())) {
                throw new Invalid("EXPECTED_SEQUENCE", path, node);
            }
            locations.put(path, Parser.location(node));
            return s.getValue();
        }

        private Map<String, Node> map(Node node, String path, Set<String> allowed) {
            if (!(node instanceof MappingNode m) || !Tag.MAP.equals(node.getTag())) {
                throw new Invalid("EXPECTED_MAPPING", path, node);
            }
            locations.put(path, Parser.location(node));
            var result = new LinkedHashMap<String, Node>();
            for (var t : m.getValue()) {
                String key = scalar(t.getKeyNode(), path + ".#key");
                if (!allowed.contains(key)) throw new Invalid("UNKNOWN_FIELD", path, t.getKeyNode());
                result.put(key, t.getValueNode());
            }
            return result;
        }

        private Node required(Map<String, Node> m, String key, String path) {
            var node = m.get(key);
            if (node == null) throw new Invalid("MISSING_FIELD", path + "." + key, null);
            return node;
        }

        private <E extends Enum<E>> E enumeration(Class<E> type, Node node, String path) {
            try { return Enum.valueOf(type, scalar(node, path)); }
            catch (IllegalArgumentException invalid) { throw new Invalid("UNKNOWN_ENUM", path, node); }
        }
    }
}
