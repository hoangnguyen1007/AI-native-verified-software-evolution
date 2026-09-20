package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.frontend.SourceInput;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.util.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import static com.evolution.analysis.ingestion.IngestionEvidence.Reason.*;

/** Passive repository configuration ingestion. The policy explicitly selects one application's locations. */
public final class ConfigDataIngestion {
    public static final VersionedIdentifier PROVIDER = new VersionedIdentifier("spring.config-data-ingestion", "m4u.1");
    public record Policy(List<String> locations, List<String> activeProfiles, Map<String,String> overrides,
                         int maxDocuments, int maxCharacters, int maxDepth, int maxProperties) {
        public Policy {
            locations = ContractChecks.distinctInOrder(locations, "config locations");
            locations.forEach(p -> { if (!p.equals(".")) ContractChecks.repositoryRelativePath(p,"config location"); });
            activeProfiles = ContractChecks.distinctInOrder(activeProfiles,"active profiles");
            overrides = Collections.unmodifiableMap(new TreeMap<>(overrides));
            if (maxDocuments < 1 || maxCharacters < 1 || maxDepth < 1 || maxDepth > 128 || maxProperties < 1)
                throw new IllegalArgumentException("Invalid configuration ingestion limits");
        }
        public static Policy defaults() { return new Policy(List.of("src/main/resources", "src/main/resources/config"),List.of(),Map.of(),256,1_000_000,64,50_000); }
    }
    public record Document(String path, int ordinal, Optional<String> profile, Map<String,String> properties,
                           ConditionEvidence evidence, boolean active) {
        public Document { properties = Collections.unmodifiableMap(new TreeMap<>(properties)); }
    }
    public record Result(ContentDigest inputIdentity, List<Document> documents, Map<String,String> properties,
                         List<String> activeProfiles, Optional<ConfigurationAssignment> assignment,
                         List<IngestionEvidence.Issue> issues, List<CapabilityGapRecord> gaps) {
        public Result {
            documents = List.copyOf(documents); properties = Collections.unmodifiableMap(new TreeMap<>(properties));
            activeProfiles = List.copyOf(activeProfiles); Objects.requireNonNull(assignment);
            issues = List.copyOf(issues); gaps = List.copyOf(gaps);
        }
        public ContentDigest identity() { return IngestionEvidence.digest(Map.of("input",inputIdentity,"documents",documents,
                "properties",properties,"profiles",activeProfiles,"assignment",assignment.map(ConfigurationAssignment::identity),"issues",issues,"gaps",gaps)); }
    }
    public Result ingest(RepositoryInputs inputs, Policy policy) {
        var identity = IngestionEvidence.digest(Map.of("provider",PROVIDER,"inputs",inputs.identity(),"policy",policy));
        var issues = new ArrayList<IngestionEvidence.Issue>();
        var parsed = new ArrayList<Document>();
        for (var file : inputs.snapshot().files()) {
            String path = file.path();
            if (location(path,policy) < 0 || !filename(path).matches("application(?:-.+)?\\.(properties|ya?ml)")) continue;
            var source = inputs.files().get(path);
            if (source == null) { issue(issues,INPUT_UNAVAILABLE,path,file.contentDigest()); continue; }
            if (source.text().length() > policy.maxCharacters()) { issue(issues,INPUT_LIMIT,path,file.contentDigest()); continue; }
            try {
                String decoded=path.endsWith(".properties")?new String(source.bytes(),StandardCharsets.ISO_8859_1):decodeYaml(source);
                if(decoded.length()>policy.maxCharacters())throw new Limit();
                List<Map<String,String>> rows = path.endsWith(".properties") ? properties(decoded,policy) : yaml(decoded,policy);
                if ((long) parsed.size()+rows.size() > policy.maxDocuments()) { issue(issues,INPUT_LIMIT,path,file.contentDigest()); continue; }
                String name = filename(path); int extension = name.lastIndexOf('.');
                Optional<String> profile = name.startsWith("application-") ? Optional.of(name.substring(12,extension)) : Optional.empty();
                for (int index=0; index<rows.size(); index++) {
                    var row=rows.get(index);
                    if(!validProfileLists(row))issue(issues,CONFIG_PROFILE_INVALID,path+"#"+index,file.contentDigest());
                    parsed.add(new Document(path,index,profile,row,
                        new ConditionEvidence.Source(source.document().identity(),file.contentDigest(),Optional.empty(),index),false));
                }
            } catch (Limit failure) { issue(issues,INPUT_LIMIT,path,file.contentDigest()); }
            catch (UnsupportedFormat failure) { issue(issues,CONFIG_FORMAT_UNSUPPORTED,path,file.contentDigest()); }
            catch (YAMLException | IllegalArgumentException failure) { issue(issues,MALFORMED_INPUT,path,file.contentDigest()); }
        }
        Comparator<Document> baseOrder = Comparator.comparingInt((Document d) -> location(d.path(),policy))
                .thenComparingInt(d -> d.path().endsWith(".properties") ? 1 : 0).thenComparing(Document::path).thenComparingInt(Document::ordinal);
        parsed.sort(baseOrder);
        // Both YAML suffixes in one location have no provider-independent precedence contract.
        Set<String> yamlNames = new HashSet<>();
        for (var d : parsed) if (!d.path().endsWith(".properties") && d.ordinal()==0
                && !yamlNames.add(d.path().replaceFirst("\\.ya?ml$","")))
            issue(issues,CONFIG_PRECEDENCE_UNKNOWN,d.path(),d.evidence().identity());
        var base = new TreeMap<String,String>();
        var unconditional=parsed.stream().filter(d -> d.profile().isEmpty() && !hasActivation(d.properties())).toList();
        unconditional.forEach(d -> mergeProfileLists(base,d.properties()));
        mergeProfileLists(base,policy.overrides());
        if(!validProfileLists(base))issue(issues,CONFIG_PROFILE_INVALID,"baseline-profile-list",identity);
        long includes=unconditional.stream().filter(d->d.properties().keySet().stream().anyMatch(k->k.equals("spring.profiles.include")||k.startsWith("spring.profiles.include["))).count();
        if(includes>1)issue(issues,CONFIG_PRECEDENCE_UNKNOWN,"multiple-profile-includes",identity);
        var profiles = new LinkedHashSet<String>();
        addProfiles(profiles,listValue(base,"spring.profiles.include"));
        addProfiles(profiles,policy.activeProfiles().isEmpty() ? listValue(base,"spring.profiles.active") : policy.activeProfiles());
        if (profiles.isEmpty()) {
            var defaults=listValue(base,"spring.profiles.default");
            addProfiles(profiles, defaults.equals(List.of("none")) ? List.of() : defaults.isEmpty() ? List.of("default") : defaults);
        }
        for (int iteration=0; iteration<policy.maxProperties(); iteration++) {
            int before=profiles.size();
            for (String p : List.copyOf(profiles)) addProfiles(profiles,listValue(base,"spring.profiles.group."+p));
            if (profiles.size()==before) break;
            if (profiles.size()>policy.maxProperties() || iteration+1==policy.maxProperties()) {issue(issues,INPUT_LIMIT,"profile-groups",identity);break;}
        }
        for (String p : profiles) if (!p.matches("[A-Za-z0-9][A-Za-z0-9._+@-]*") || p.contains("${"))
            issue(issues,CONFIG_PROFILE_INVALID,"profiles",identity);
        List<String> active = List.copyOf(profiles);
        // Config locations override earlier locations; within a location profiles override base,
        // and later active profiles override earlier ones, independently of file enumeration.
        parsed.sort(Comparator.comparingInt((Document d) -> location(d.path(),policy))
                .thenComparingInt(d -> d.profile().map(active::indexOf).map(i -> i+1).orElse(0)).thenComparing(baseOrder));
        Map<String,String> merged = new TreeMap<>(); var documents = new ArrayList<Document>();
        for (var d : parsed) {
            boolean enabled = d.profile().map(profiles::contains).orElse(true);
            try {
                var expressions=listValue(d.properties(),"spring.config.activate.on-profile");
                if (d.properties().keySet().stream().anyMatch(k -> k.startsWith("spring.config.activate.on-profile"))) {
                    if(expressions.isEmpty())throw new IllegalArgumentException();
                    enabled &= expressions.stream().anyMatch(e -> matches(e,profiles,policy.maxDepth(),identity));
                }
            }
            catch (IllegalArgumentException failure) { enabled=false; issue(issues,CONFIG_ACTIVATION_UNKNOWN,d.path()+"#"+d.ordinal(),d.evidence().identity()); }
            if (d.properties().containsKey("spring.config.activate.on-cloud-platform") || d.properties().containsKey("spring.profiles")) {
                enabled=false; issue(issues,CONFIG_ACTIVATION_UNKNOWN,d.path()+"#"+d.ordinal(),d.evidence().identity());
            }
            if ((d.profile().isPresent() || hasActivation(d.properties())) && d.properties().keySet().stream()
                    .anyMatch(k -> k.equals("spring.profiles.active") || k.equals("spring.profiles.default") || k.startsWith("spring.profiles.include") || k.startsWith("spring.profiles.group.")))
                issue(issues,CONFIG_PROFILE_INVALID,d.path()+"#"+d.ordinal(),d.evidence().identity());
            if (enabled) {
                merged.putAll(d.properties());
                for (String key : d.properties().keySet()) {
                    if (key.equals("spring.config.import")) issue(issues,CONFIG_IMPORT_REQUIRED,d.path()+"#"+d.ordinal(),d.evidence().identity());
                    if (Set.of("spring.config.location","spring.config.additional-location","spring.config.name").contains(key))
                        issue(issues,CONFIG_EXTERNAL_INPUT_REQUIRED,d.path()+"#"+d.ordinal(),d.evidence().identity());
                }
            }
            documents.add(new Document(d.path(),d.ordinal(),d.profile(),d.properties(),d.evidence(),enabled));
        }
        merged.putAll(policy.overrides());
        for (String key : merged.keySet()) {
            if (key.isBlank()) issue(issues,MALFORMED_INPUT,"blank-property-key",identity);
            if (key.equals("spring.config.import") || key.startsWith("spring.config.import["))
                issue(issues,CONFIG_IMPORT_REQUIRED,"config-import",identity);
            if (Set.of("spring.config.location","spring.config.additional-location","spring.config.name").contains(key))
                issue(issues,CONFIG_EXTERNAL_INPUT_REQUIRED,"config-location",identity);
        }
        var resolved = new TreeMap<String,String>();
        int remainingCharacters=policy.maxCharacters();
        for (String key : merged.keySet()) {
            try {
                String value=resolve(merged.get(key),merged,new LinkedHashSet<>(List.of(key)),policy.maxDepth(),remainingCharacters);
                if(key.length()+value.length()>remainingCharacters)throw new Limit();
                resolved.put(key,value);remainingCharacters-=key.length()+value.length();
            }
            catch(Limit failure){issue(issues,INPUT_LIMIT,"resolved-properties",identity);break;}
            catch (IllegalArgumentException failure) { issue(issues,CONFIG_PLACEHOLDER_UNRESOLVED,"property:"+key,identity); }
        }
        if (resolved.size()>policy.maxProperties()) issue(issues,INPUT_LIMIT,"merged-properties",identity);
        var baseline = new TreeMap<FiniteDomain.Variable,FiniteDomain.Value>();
        resolved.forEach((k,v) -> { if (!k.isBlank()) baseline.put(new FiniteDomain.Variable(FiniteDomain.Kind.PROPERTY,k),FiniteDomain.Value.exact(v)); });
        profiles.forEach(p -> { if (!p.isBlank()) baseline.put(new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE,p),FiniteDomain.Value.bool(true)); });
        var evidence = new ConditionEvidence.Derived(List.of(identity),PROVIDER,"repository-baseline");
        var sortedIssues = issues.stream().distinct().sorted(Comparator.comparing(IngestionEvidence.Issue::identity)).toList();
        return new Result(identity,documents,resolved,active,
                sortedIssues.isEmpty() ? Optional.of(new ConfigurationAssignment(baseline,evidence)) : Optional.empty(),
                sortedIssues,IngestionEvidence.gaps(inputs.snapshot().identity(),PROVIDER,identity,sortedIssues));
    }

    private static String decodeYaml(SourceInput source) {
        byte[] bytes=source.bytes();var bom=SourceInput.Bom.detect(bytes);
        var charset=switch(bom){case UTF16_BE->StandardCharsets.UTF_16BE;case UTF16_LE->StandardCharsets.UTF_16LE;default->StandardCharsets.UTF_8;};
        int skip=switch(bom){case UTF8->3;case UTF16_BE,UTF16_LE->2;default->0;};
        try{return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,skip,bytes.length-skip)).toString();}
        catch(java.nio.charset.CharacterCodingException failure){throw new IllegalArgumentException("Invalid YAML encoding");}
    }
    private static boolean validProfileLists(Map<String,String> values) {
        var bases=new TreeSet<String>();
        for(String key:values.keySet())if(key.startsWith("spring.profiles.")||key.startsWith("spring.config.activate.on-profile")) {
            int bracket=key.indexOf('[');if(bracket<0)continue;
            String base=key.substring(0,bracket);bases.add(base);
            if(!key.substring(bracket).matches("\\[(?:0|[1-9][0-9]*)\\]"))return false;
        }
        for(String base:bases) {
            if(values.containsKey(base))return false;
            long count=values.keySet().stream().filter(k->k.startsWith(base+"[")).count();
            for(int i=0;i<count;i++)if(!values.containsKey(base+"["+i+"]"))return false;
        }
        return true;
    }
    private static void mergeProfileLists(Map<String,String> target,Map<String,String> source) {
        var bases=new HashSet<String>();
        for(String key:source.keySet())if(key.startsWith("spring.profiles.")) {
            int bracket=key.indexOf('[');bases.add(bracket<0?key:key.substring(0,bracket));
        }
        target.keySet().removeIf(k->bases.stream().anyMatch(b->k.equals(b)||k.startsWith(b+"[")));
        target.putAll(source);
    }

    private static List<Map<String,String>> properties(String text, Policy policy) {
        var result = new ArrayList<Map<String,String>>();
        String[] lines=text.split("\\r\\n|\\n|\\r",-1);
        var part=new StringBuilder(); boolean continued=false;
        for (int i=0;i<lines.length;i++) {
            String line=lines[i];
            boolean separator=!continued && line.matches("[#!]---[ \\t\\f]*")
                    && (i==0 || !lines[i-1].stripLeading().startsWith(line.substring(0,1)))
                    && (i+1==lines.length || !lines[i+1].stripLeading().startsWith(line.substring(0,1)));
            if (separator) { addProperties(result,part.toString(),policy); part.setLength(0); }
            else { part.append(line).append('\n'); }
            boolean comment=!continued && (line.stripLeading().startsWith("#") || line.stripLeading().startsWith("!"));
            int slashes=0; for (int j=line.length()-1;j>=0 && line.charAt(j)=='\\';j--) slashes++;
            continued=!comment && (slashes%2==1);
        }
        addProperties(result,part.toString(),policy);
        return result;
    }
    private static void addProperties(List<Map<String,String>> result,String part,Policy policy) {
            if (result.size()>=policy.maxDocuments()) throw new Limit();
            Properties p = new Properties();
            try { p.load(new StringReader(part)); } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
            if (p.size()>policy.maxProperties()) throw new Limit();
            var values = new TreeMap<String,String>();
            p.forEach((k,v) -> {
                String key=((String)k).trim();
                // Boot's list shortcut needs escape-aware tokenization. Preserve an explicit gap
                // instead of treating it as an unrelated scalar property.
                if (key.endsWith("[]")) throw new UnsupportedFormat();
                if (values.putIfAbsent(key,(String)v)!=null) throw new IllegalArgumentException("Trimmed property key collision");
            });
            if (!values.isEmpty()) result.add(values);
    }
    private static List<Map<String,String>> yaml(String text, Policy policy) {
        var options = new LoaderOptions(); options.setAllowDuplicateKeys(false); options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(32); options.setNestingDepthLimit(policy.maxDepth()); options.setCodePointLimit(policy.maxCharacters());
        // SafeConstructor never instantiates target classes. Timestamp/binary/custom tags are rejected.
        var loader = new Yaml(new SafeConstructor(options)); var result = new ArrayList<Map<String,String>>();
        for (Object value : loader.loadAll(text)) {
            if (result.size()>=policy.maxDocuments()) throw new Limit();
            var flat = new TreeMap<String,String>();
            if (value != null && !(value instanceof Map<?,?>)) throw new IllegalArgumentException("YAML root must be a map");
            if (value != null) flatten("",value,flat,Collections.newSetFromMap(new IdentityHashMap<>()),policy,0);
            result.add(flat);
        }
        return result;
    }
    private static void flatten(String path,Object value,Map<String,String> out,Set<Object> ancestors,Policy policy,int depth) {
        if (depth>policy.maxDepth() || out.size()>=policy.maxProperties()) throw new Limit();
        if (value instanceof Map<?,?> map) {
            if (!ancestors.add(value)) throw new IllegalArgumentException("Cyclic YAML alias");
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || key.isBlank()) throw new IllegalArgumentException("Non-string YAML key");
                flatten(path.isEmpty()?key:path+(key.startsWith("[")?"":".")+key,entry.getValue(),out,ancestors,policy,depth+1);
            }
            ancestors.remove(value);
        } else if (value instanceof List<?> list) {
            if (!ancestors.add(value)) throw new IllegalArgumentException("Cyclic YAML alias");
            if (list.isEmpty() && out.putIfAbsent(path,"")!=null) throw new IllegalArgumentException("Flattened key collision");
            for (int i=0;i<list.size();i++) flatten(path+"["+i+"]",list.get(i),out,ancestors,policy,depth+1);
            ancestors.remove(value);
        } else {
            if (value!=null && !(value instanceof String || value instanceof Number || value instanceof Boolean))
                throw new IllegalArgumentException("Unsupported YAML scalar");
            if (out.putIfAbsent(path,value==null?"":value.toString())!=null) throw new IllegalArgumentException("Flattened key collision");
        }
    }
    private static String resolve(String value,Map<String,String> properties,Set<String> path,int budget,int maxChars) {
        if (budget<1) throw new IllegalArgumentException("Placeholder depth");
        StringBuilder out=new StringBuilder();
        for (int index=0;index<value.length();) {
            int start=value.indexOf("${",index);
            if (start<0) { out.append(value,index,value.length()); break; }
            out.append(value,index,start); int nesting=1,end=start+2;
            for (;end<value.length() && nesting>0;end++) {
                if (value.startsWith("${",end)) { nesting++;end++; }
                else if (value.charAt(end)=='}') nesting--;
            }
            if (nesting!=0) throw new IllegalArgumentException("Unclosed placeholder");
            String expression=resolve(value.substring(start+2,end-1),properties,path,budget-1,maxChars);
            int colon=expression.indexOf(':'); String key=colon<0?expression:expression.substring(0,colon);
            if (!path.add(key)) throw new IllegalArgumentException("Placeholder cycle");
            String replacement=properties.get(key);
            if (replacement==null && colon>=0) replacement=expression.substring(colon+1);
            if (replacement==null) throw new IllegalArgumentException("External placeholder required");
            out.append(resolve(replacement,properties,path,budget-1,maxChars)); path.remove(key); index=end;
            if (out.length()>maxChars) throw new Limit();
        }
        if (out.length()>maxChars) throw new Limit();
        return out.toString();
    }
    private static List<String> listValue(Map<String,String> values,String key) {
        if (values.containsKey(key)) return Arrays.stream(values.get(key).split(",",-1)).map(String::trim).filter(s -> !s.isEmpty()).toList();
        var list=new ArrayList<String>(); for (int i=0;values.containsKey(key+"["+i+"]");i++) list.add(values.get(key+"["+i+"]").trim()); return list;
    }
    private static void addProfiles(Set<String> profiles,List<String> additions) { profiles.addAll(additions); }
    private static boolean hasActivation(Map<String,String> values) { return values.keySet().stream().anyMatch(k -> k.startsWith("spring.config.activate.") || k.equals("spring.profiles")); }
    private static boolean matches(String expression,Set<String> profiles,int depth,ContentDigest identity) {
        var semantics=new ConditionExpression.Semantics(PROVIDER,identity);
        return evaluate(ProfileExpressionLowering.parse(expression,semantics,depth),profiles);
    }
    private static boolean evaluate(ConditionExpression expression,Set<String> profiles) {
        return switch (expression.operator()) {
            case PROFILE -> profiles.contains(((ConditionExpression.Profile)expression.operand()).name());
            case NOT -> !evaluate(expression.children().getFirst(),profiles);
            case ALL -> expression.children().stream().allMatch(e -> evaluate(e,profiles));
            case ANY -> expression.children().stream().anyMatch(e -> evaluate(e,profiles));
            default -> throw new IllegalArgumentException("Non-profile expression");
        };
    }
    private static int location(String path,Policy policy) {
        int slash=path.lastIndexOf('/'); String directory=slash<0?".":path.substring(0,slash); return policy.locations().indexOf(directory);
    }
    private static String filename(String path) { return path.substring(path.lastIndexOf('/')+1); }
    private static void issue(List<IngestionEvidence.Issue> issues,IngestionEvidence.Reason reason,String subject,ContentDigest input) {
        issues.add(new IngestionEvidence.Issue(reason,subject,List.of(input)));
    }
    private static final class Limit extends RuntimeException {}
    private static final class UnsupportedFormat extends RuntimeException {}
}
