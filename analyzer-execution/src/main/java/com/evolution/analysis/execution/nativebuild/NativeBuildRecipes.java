package com.evolution.analysis.execution.nativebuild;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.readiness.ReadinessPlan;
import com.evolution.analysis.execution.ExecutionGraph;
import java.util.*;

/** Native command data for a guest executor. Repository paths are never host executables. */
public final class NativeBuildRecipes {
    public record Tool(String role,VersionedIdentifier provider,ContentHandle binary,String os,String arch) {
        public Tool {ExecutionGraph.text(role);Objects.requireNonNull(provider);Objects.requireNonNull(binary);ExecutionGraph.text(os);ExecutionGraph.text(arch);}
    }
    public record Command(ContentDigest tool,List<String> arguments,String rootDirectory,String phase) {
        public Command {Objects.requireNonNull(tool);arguments=List.copyOf(arguments);arguments.forEach(Objects::requireNonNull);ExecutionGraph.text(rootDirectory);ExecutionGraph.text(phase);}
    }
    public record Recipe(String schema,ContentDigest readiness,String sourceRecipe,List<String> contexts,List<Command> commands,
            Tool tool,ContentDigest inputClosure,ContentDigest networkPolicy,ContentDigest resourcePolicy,String executionScope) {
        public Recipe {
            if(!"native-build-recipe:m8.4-v1".equals(schema)||!"ISOLATED_ROOT".equals(executionScope))throw new IllegalArgumentException("INVALID_NATIVE_RECIPE");
            Objects.requireNonNull(readiness);ExecutionGraph.text(sourceRecipe);contexts=ExecutionGraph.strings(contexts);commands=List.copyOf(commands);Objects.requireNonNull(tool);Objects.requireNonNull(inputClosure);Objects.requireNonNull(networkPolicy);Objects.requireNonNull(resourcePolicy);
        }
        public ContentDigest identity(){return CanonicalJson.sha256(this);}
    }
    public record Lowering(Optional<Recipe> recipe,List<String> pendingReasons) {public Lowering {Objects.requireNonNull(recipe);pendingReasons=ExecutionGraph.strings(pendingReasons);if(recipe.isPresent()==!pendingReasons.isEmpty())throw new IllegalArgumentException("INVALID_LOWERING");}}
    /** taskMappings are operator/adapter-selected guest task names, never suggestions executed on the host. */
    public static Lowering lower(ReadinessPlan plan,ReadinessPlan.Recipe source,Tool tool,ContentDigest inputClosure,
            ContentDigest networkPolicy,ContentDigest resourcePolicy,Map<String,List<String>> taskMappings) {
        if(!plan.recipes().contains(source))throw new IllegalArgumentException("FOREIGN_RECIPE");
        var root=plan.roots().stream().filter(r->r.id().equals(source.root())).findFirst().orElseThrow(()->new IllegalArgumentException("UNKNOWN_ROOT"));
        var selected=plan.contexts().stream().filter(c->source.contexts().contains(c.id())).toList();
        if(selected.size()!=source.contexts().size()||!plan.selection().selected().containsAll(source.contexts()))throw new IllegalArgumentException("INVALID_CONTEXT_SCOPE");
        var args=new ArrayList<String>();var pending=new TreeSet<String>();
        if(source.family()==ReadinessPlan.Family.MAVEN){
            if(!tool.role().equals("maven"))throw new IllegalArgumentException("WRONG_TOOL");
            args.addAll(List.of("--batch-mode","--no-transfer-progress"));
            if(selected.stream().allMatch(c->c.variant().equals("baseline"))){
                if(selected.stream().anyMatch(c->!Set.of("main","test").contains(c.sourceSet())))pending.add("NATIVE_TASK_MAPPING_REQUIRED");
                args.add(selected.stream().anyMatch(c->c.sourceSet().equals("test"))?"test-compile":"compile");
            }else pending.add("NATIVE_CONTEXT_MAPPING_REQUIRED");
        }else if(source.family()==ReadinessPlan.Family.GRADLE){
            if(!tool.role().equals("gradle"))throw new IllegalArgumentException("WRONG_TOOL");
            args.addAll(List.of("--no-daemon","--console=plain"));var tasks=new LinkedHashSet<String>();
            for(var context:selected){var mapped=taskMappings.get(context.id());if(mapped==null||mapped.isEmpty())pending.add("NATIVE_TASK_MAPPING_REQUIRED");else for(String task:mapped){if(!task.matches(":?[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+)*"))throw new IllegalArgumentException("INVALID_NATIVE_TASK");tasks.add(task);}}
            args.addAll(tasks);
        }else pending.add("NATIVE_ADAPTER_REQUIRED");
        if(!pending.isEmpty())return new Lowering(Optional.empty(),List.copyOf(pending));
        return new Lowering(Optional.of(new Recipe("native-build-recipe:m8.4-v1",plan.identity(),source.id(),source.contexts(),List.of(new Command(tool.binary().digest(),args,root.directory(),"native-compile")),tool,inputClosure,networkPolicy,resourcePolicy,"ISOLATED_ROOT")),List.of());
    }
    private NativeBuildRecipes(){}
}
