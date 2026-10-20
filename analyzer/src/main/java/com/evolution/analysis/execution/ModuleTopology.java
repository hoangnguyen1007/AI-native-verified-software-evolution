package com.evolution.analysis.execution;

import java.util.*;

/** Iterative SCC diagnostics. A component is not a permission to co-compile it. */
public final class ModuleTopology {
    public record Missing(String module,String dependency) {}
    public record Result(List<List<String>> components,List<List<String>> cycles,List<Missing> missing) {
        public Result {components=components.stream().map(List::copyOf).toList();cycles=cycles.stream().map(List::copyOf).toList();missing=List.copyOf(missing);}
    }
    public static Result analyze(Map<String,? extends Collection<String>> modules) {
        var adjacency=new TreeMap<String,List<String>>();var reverse=new TreeMap<String,List<String>>();var missing=new ArrayList<Missing>();
        modules.keySet().forEach(id->{ExecutionGraph.text(id);reverse.put(id,new ArrayList<>());});
        modules.forEach((id,dependencies)->{
            var targets=new TreeSet<String>();
            for(var dependency:dependencies){ExecutionGraph.text(dependency);if(!modules.containsKey(dependency))missing.add(new Missing(id,dependency));else targets.add(dependency);}
            adjacency.put(id,List.copyOf(targets));for(var target:targets)reverse.get(target).add(id);
        });
        record Frame(String id,Iterator<String> children){}
        var visited=new HashSet<String>();var order=new ArrayList<String>();
        for(var id:adjacency.keySet())if(visited.add(id)){
            var stack=new ArrayDeque<Frame>();stack.push(new Frame(id,adjacency.get(id).iterator()));
            while(!stack.isEmpty()){
                var frame=stack.peek();if(frame.children().hasNext()) {var next=frame.children().next();if(visited.add(next))stack.push(new Frame(next,adjacency.get(next).iterator()));}
                else order.add(stack.pop().id());
            }
        }
        Collections.reverse(order);visited.clear();var groups=new ArrayList<List<String>>();var cycles=new ArrayList<List<String>>();
        for(var id:order)if(visited.add(id)){
            var members=new TreeSet<String>();var stack=new ArrayDeque<String>();stack.push(id);
            while(!stack.isEmpty()){var next=stack.pop();members.add(next);for(var parent:reverse.get(next))if(visited.add(parent))stack.push(parent);}
            var group=List.copyOf(members);groups.add(group);if(group.size()>1||adjacency.get(id).contains(id))cycles.add(group);
        }
        Comparator<List<String>> comparator=Comparator.comparing(List::getFirst);groups.sort(comparator);cycles.sort(comparator);
        missing.sort(Comparator.comparing(Missing::module).thenComparing(Missing::dependency));
        return new Result(groups,cycles,missing.stream().distinct().toList());
    }
    private ModuleTopology(){}
}
