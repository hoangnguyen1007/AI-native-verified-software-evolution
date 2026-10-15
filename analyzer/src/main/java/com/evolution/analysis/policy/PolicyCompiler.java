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
}
