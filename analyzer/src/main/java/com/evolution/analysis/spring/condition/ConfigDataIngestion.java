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
    public static final VersionedIdentifier PROVIDER = new VersionedIdentifier("spring.config-data-ingestion", "m4uv2.2");
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
        var unconditional=new ImportClosure(inputs,policy,issues,identity,Set.of(),true)
                .expand(parsed.stream().filter(d -> d.profile().isEmpty() && !hasActivation(d.properties())).toList())
                .stream().filter(d -> d.profile().isEmpty() && !hasActivation(d.properties())).toList();
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
        for (var d : new ImportClosure(inputs,policy,issues,identity,profiles,false).expand(parsed)) {
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
                    if (Set.of("spring.config.location","spring.config.additional-location","spring.config.name").contains(key))
                        issue(issues,CONFIG_EXTERNAL_INPUT_REQUIRED,d.path()+"#"+d.ordinal(),d.evidence().identity());
                }
            }
            documents.add(new Document(d.path(),d.ordinal(),d.profile(),d.properties(),d.evidence(),enabled));
        }
        merged.putAll(policy.overrides());
        for (String key : merged.keySet()) {
            if (key.isBlank()) issue(issues,MALFORMED_INPUT,"blank-property-key",identity);
            if ((key.equals("spring.config.import") || key.startsWith("spring.config.import[")) && policy.overrides().containsKey(key))
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

    /** Reads only captured repository bytes. A relative import has no authority to read the host.
     * Expansion is repeated for the non-profile and selected-profile phases, each with bounded work. */
    private static final class ImportClosure {
        private final RepositoryInputs inputs;
        private final Policy policy;
        private final List<IngestionEvidence.Issue> issues;
        private final ContentDigest identity;
        private final Set<String> profiles;
        private final boolean beforeProfiles;
        private final Set<String> seen = new HashSet<>();
        private final Set<String> stack = new HashSet<>();
        private final Set<String> charged = new HashSet<>();
        private final Map<String,List<Document>> cache = new HashMap<>();
        private final List<Document> output = new ArrayList<>();
        private int characters;
        private int edges;

        ImportClosure(RepositoryInputs inputs,Policy policy,List<IngestionEvidence.Issue> issues,
                      ContentDigest identity,Set<String> profiles,boolean beforeProfiles) {
            this.inputs=inputs;this.policy=policy;this.issues=issues;this.identity=identity;
            this.profiles=profiles;this.beforeProfiles=beforeProfiles;
        }
        List<Document> expand(List<Document> roots) {
            // Boot discovers the highest-precedence contributor first. Import-once must
            // therefore retain the last declared import position, not the first low-priority one.
            for (var root:roots.reversed()) visit(root,0);
            return List.copyOf(output.reversed());
        }
        private void visit(Document document,int depth) {
            String key=document.path()+"#"+document.ordinal();
            if(stack.contains(key)) { problem(CONFIG_IMPORT_REQUIRED,document,"cyclic-import");return; }
            if(!seen.add(key))return;
            if(depth>policy.maxDepth() || seen.size()>policy.maxDocuments()) {
                problem(INPUT_LIMIT,document,"import-depth-or-documents");return;
            }
            var input=inputs.files().get(document.path());
            if(input!=null && charged.add(document.path())) {
                if((long)characters+input.bytes().length>policy.maxCharacters()) {
                    problem(INPUT_LIMIT,document,"import-bytes");return;
                }
                characters+=input.bytes().length;
            }
            if(!enabled(document)) {output.add(document);return;}
            stack.add(key);
            try {
                var properties=document.properties();
                if(properties.keySet().stream().anyMatch(k -> k.startsWith("spring.config.import["))) {
                    long count=properties.keySet().stream().filter(k -> k.startsWith("spring.config.import[")).count();
                    if(properties.containsKey("spring.config.import") || count!=listValue(properties,"spring.config.import").size()) {
                        problem(CONFIG_IMPORT_REQUIRED,document,"import-list");return;
                    }
                }
                for(String raw:listValue(properties,"spring.config.import").reversed()) {
                    if(++edges>policy.maxProperties()) {problem(INPUT_LIMIT,document,"import-edges");break;}
                    boolean optional=raw.startsWith("optional:");
                    String relative=optional?raw.substring(9):raw;
                    String path=relativePath(document.path(),relative);
                    if(path==null) {problem(CONFIG_IMPORT_REQUIRED,document,"external-or-unsupported-import");continue;}
                    if(!beforeProfiles) {
                        int dot=path.lastIndexOf('.');
                        for(String profile:List.copyOf(profiles).reversed()) {
                            if(!profile.matches("[A-Za-z0-9][A-Za-z0-9._+@-]*"))continue;
                            String variant=path.substring(0,dot)+"-"+profile+path.substring(dot);
                            read(variant,Optional.of(profile),true,document).reversed().forEach(d -> visit(d,depth+1));
                        }
                    }
                    read(path,Optional.empty(),optional,document).reversed().forEach(d -> visit(d,depth+1));
                }
            } finally {stack.remove(key);output.add(document);}
        }
        private boolean enabled(Document d) {
            if(beforeProfiles)return d.profile().isEmpty()&&!hasActivation(d.properties());
            if(d.profile().isPresent()&&!profiles.contains(d.profile().orElseThrow()))return false;
            if(d.properties().containsKey("spring.profiles")||d.properties().containsKey("spring.config.activate.on-cloud-platform"))return false;
            if(d.properties().keySet().stream().noneMatch(k -> k.startsWith("spring.config.activate.on-profile")))return true;
            try {return listValue(d.properties(),"spring.config.activate.on-profile").stream()
                    .anyMatch(e -> matches(e,profiles,policy.maxDepth(),identity));}
            catch(IllegalArgumentException invalid) {return false;}
        }
        private List<Document> read(String path,Optional<String> profile,boolean optional,Document importer) {
            if(cache.containsKey(path))return cache.get(path);
            var source=inputs.files().get(path);
            if(source==null) {
                boolean recorded=inputs.snapshot().files().stream().anyMatch(f -> f.path().equals(path));
                if(recorded||!optional)problem(recorded?INPUT_UNAVAILABLE:CONFIG_IMPORT_REQUIRED,importer,"missing-import");
                return List.of();
            }
            var rows=new ArrayList<Document>();
            try {
                if(source.bytes().length>policy.maxCharacters()-characters)throw new Limit();
                String decoded=path.endsWith(".properties")?new String(source.bytes(),StandardCharsets.ISO_8859_1):decodeYaml(source);
                var values=path.endsWith(".properties")?properties(decoded,policy):yaml(decoded,policy);
                for(int i=0;i<values.size();i++) {
                    if(!validProfileLists(values.get(i)))problem(CONFIG_PROFILE_INVALID,importer,"import-profile-list");
                    rows.add(new Document(path,i,profile,values.get(i),new ConditionEvidence.Source(
                            source.document().identity(),source.document().contentDigest(),Optional.empty(),i),false));
                }
            } catch(Limit exceeded) {problem(INPUT_LIMIT,importer,"import-bytes-or-parser");}
            catch(UnsupportedFormat unsupported) {problem(CONFIG_FORMAT_UNSUPPORTED,importer,"import-format");}
            catch(YAMLException|IllegalArgumentException malformed) {problem(MALFORMED_INPUT,importer,"malformed-import");}
            cache.put(path,List.copyOf(rows));return rows;
        }
        private String relativePath(String importer,String relative) {
            if(relative.isBlank()||relative.startsWith("/")||relative.contains(":")||relative.contains("\\")
                    ||relative.contains("${")||relative.contains("*")||!relative.matches(".*\\.(properties|ya?ml)"))return null;
            var parts=new ArrayDeque<String>();
            String directory=importer.contains("/")?importer.substring(0,importer.lastIndexOf('/')+1):"";
            for(String part:(directory+relative).split("/",-1)) {
                if(part.equals("..")) {if(parts.isEmpty())return null;parts.removeLast();}
                else if(!part.equals(".")&&!part.isEmpty())parts.addLast(part);
            }
            String path=String.join("/",parts);
            return policy.locations().stream().anyMatch(root -> root.equals(".")||path.startsWith(root+"/"))?path:null;
        }
        private void problem(IngestionEvidence.Reason reason,Document importer,String detail) {
            issue(issues,reason,importer.path()+"#"+importer.ordinal()+":"+detail,importer.evidence().identity());
        }
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
