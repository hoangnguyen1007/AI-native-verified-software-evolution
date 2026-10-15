package com.evolution.analysis.query;

import java.util.*;
import com.evolution.analysis.query.ArchitectureQueryService.Prepared;
import static com.evolution.analysis.query.ArchitectureQuery.*;
import static com.evolution.analysis.query.ArchitectureQueryService.*;

/** Iterative Tarjan + bounded canonical-start simple-cycle enumeration, equivalent directed cycle set to Johnson.
 * No recursive traversal, all traversal/output/solver counters are request-global. */
final class BoundedGraphTraversal {
    private record Prefix(List<String> vertices,List<LogicalEdge> edges,QueryRegion region) {
        String last(){return vertices.getLast();}
        int units(){return vertices.size()+edges.size()+1;}
    }
    static List<Path> paths(Prepared p,String source,Optional<String> target,boolean shortest){
        var result=new ArrayList<Path>();var queue=new ArrayDeque<Prefix>();var emitted=new HashSet<String>();
        var start=p.catalog==null?QueryRegion.unknown():p.catalog.activate(source);
        queue.add(new Prefix(List.of(source),List.of(),start));long units=2;
        if(target.filter(source::equals).isPresent()){
            var check=start.check(p.budget,p.request.realization());if(check.truth()==Truth.SAT)result.add(new Path(List.of(source),List.of(),check.truth(),check.witness()));
            else if(check.truth()==Truth.UNKNOWN)qualify(p,source);else p.budget.rejected++;return result;
        }
        while(!queue.isEmpty()&&p.budget.running()){
            var prefix=queue.removeFirst();units-=prefix.units();
            for(var edge:p.adjacency.getOrDefault(prefix.last(),List.of())){
                if(!p.budget.step()){p.budget.frontier(prefix.last());break;}
                if(prefix.vertices().contains(edge.target))continue;
                if(prefix.vertices().size()>=p.request.limits().maxDepth()){
                    p.budget.frontier(edge.target);p.budget.halt(StopReason.DEPTH);break;
                }
                var next=prefix.region().combine(edge.region,true,p.budget);var check=next.check(p.budget,p.request.realization());
                if(check.truth()==Truth.UNSAT){p.budget.rejected++;continue;}
                var vertices=new ArrayList<>(prefix.vertices());vertices.add(edge.target);var edges=new ArrayList<>(prefix.edges());edges.add(edge);
                boolean terminal=target.map(edge.target::equals).orElse(true);
                if(terminal&&check.truth()==Truth.SAT&&(target.isPresent()||emitted.add(edge.target))){
                    result.add(new Path(vertices,edges.stream().map(e->e.view).toList(),Truth.SAT,check.witness()));
                    if(shortest)return result;
                    if(result.size()==p.request.limits().maxResults()){p.budget.halt(StopReason.OUTPUT);p.budget.frontier(edge.target);break;}
                }else if(terminal&&check.truth()==Truth.UNKNOWN)qualify(p,edge.target);
                if(target.filter(edge.target::equals).isPresent())continue;
                int nextUnits=vertices.size()+edges.size()+1;
                if(units+nextUnits>p.request.limits().maxAuxiliaryStates()-p.budget.auxiliary){
                    p.budget.frontier(edge.target);p.budget.halt(StopReason.MEMORY);break;
                }
                queue.addLast(new Prefix(List.copyOf(vertices),List.copyOf(edges),next));units+=nextUnits;
            }
        }
        if(p.budget.stop!=StopReason.NONE)queue.stream().limit(p.request.limits().maxResults()).forEach(q->p.budget.frontier(q.last()));
        return List.copyOf(result);
    }
    private static void qualify(Prepared p,String node){p.budget.unknown++;p.budget.frontier(node);p.budget.qualify("UNKNOWN_FEASIBILITY");}
    private static final class TarjanFrame {
        final String vertex,parent;final List<LogicalEdge> edges;int next;
        TarjanFrame(String vertex,String parent,List<LogicalEdge> edges){this.vertex=vertex;this.parent=parent;this.edges=edges;}
    }
    static List<Set<String>> components(Prepared p){
        var vertices=new TreeSet<String>();p.edges.forEach(e->{vertices.add(e.source);vertices.add(e.target);});
        p.entities.values().stream().filter(e->switch(p.request.scope()){
            case ENTITY->true;case SPRING_COMPONENT->e.kind().equals("BEAN_DEFINITION_CANDIDATE");default->e.kind().equals(p.request.scope().name());}).forEach(e->vertices.add(e.identity()));
        if(!p.budget.reserve(6L*vertices.size()))return List.of();
        var index=new HashMap<String,Integer>();var low=new HashMap<String,Integer>();var onStack=new HashSet<String>();
        var stack=new ArrayDeque<String>();var frames=new ArrayDeque<TarjanFrame>();var result=new ArrayList<Set<String>>();int counter=0;
        for(var root:vertices){
            if(!p.budget.running())break;if(index.containsKey(root))continue;
            frames.push(new TarjanFrame(root,null,p.adjacency.getOrDefault(root,List.of())));index.put(root,counter);low.put(root,counter++);stack.push(root);onStack.add(root);
            while(!frames.isEmpty()&&p.budget.scan()){
                var frame=frames.peek();
                if(frame.next<frame.edges.size()){
                    String next=frame.edges.get(frame.next++).target;
                    if(!index.containsKey(next)){
                        index.put(next,counter);low.put(next,counter++);stack.push(next);onStack.add(next);
                        frames.push(new TarjanFrame(next,frame.vertex,p.adjacency.getOrDefault(next,List.of())));
                    }else if(onStack.contains(next))low.put(frame.vertex,Math.min(low.get(frame.vertex),index.get(next)));
                }else{
                    frames.pop();if(frame.parent!=null)low.put(frame.parent,Math.min(low.get(frame.parent),low.get(frame.vertex)));
                    if(low.get(frame.vertex).equals(index.get(frame.vertex))){
                        var component=new TreeSet<String>();String vertex;
                        do {vertex=stack.pop();onStack.remove(vertex);component.add(vertex);}while(!vertex.equals(frame.vertex));
                        result.add(Collections.unmodifiableSet(component));
                    }
                }
            }
        }
        result.sort(Comparator.comparing(s->s.iterator().next()));p.budget.components=result.size();
        return result;
    }
    private static final class CycleFrame {
        final String vertex;final QueryRegion region;final List<LogicalEdge> edges;int next;
        CycleFrame(String vertex,QueryRegion region,List<LogicalEdge> edges){this.vertex=vertex;this.region=region;this.edges=edges;}
    }
    static List<Path> cycles(Prepared p){
        return cycles(p,false);
    }
    static List<Path> cycles(Prepared p,boolean includeUnknown){
        var components=components(p);var result=new ArrayList<Path>();
        p.budget.cyclic=(int)components.stream().filter(c->c.size()>1||p.adjacency.getOrDefault(c.iterator().next(),List.of()).stream().anyMatch(e->e.target.equals(e.source))).count();
        for(var component:components){
            if(!p.budget.running())break;
            boolean cyclic=component.size()>1||p.adjacency.getOrDefault(component.iterator().next(),List.of()).stream().anyMatch(e->e.target.equals(e.source));
            if(!cyclic)continue;
            for(var start:component){
                if(!p.budget.step())break;
                var frames=new ArrayDeque<CycleFrame>();var vertices=new ArrayList<String>();var edges=new ArrayList<LogicalEdge>();var visited=new HashSet<String>();
                vertices.add(start);visited.add(start);frames.push(new CycleFrame(start,p.catalog.activate(start),p.adjacency.getOrDefault(start,List.of())));
                while(!frames.isEmpty()&&p.budget.running()){
                    var frame=frames.peek();
                    if(frame.next==frame.edges.size()){
                        if(!p.budget.step())break;frames.pop();visited.remove(frame.vertex);vertices.removeLast();if(!edges.isEmpty())edges.removeLast();continue;
                    }
                    if(!p.budget.step())break;
                    var edge=frame.edges.get(frame.next++);if(!component.contains(edge.target)||edge.target.compareTo(start)<0)continue;
                    if(!edge.target.equals(start)&&visited.contains(edge.target))continue;
                    var region=frame.region.combine(edge.region,true,p.budget);var check=region.check(p.budget,p.request.realization());
                    if(check.truth()==Truth.UNSAT){p.budget.rejected++;continue;}
                    if(edge.target.equals(start)){
                        if(check.truth()==Truth.SAT||includeUnknown&&check.truth()==Truth.UNKNOWN){
                            if(check.truth()==Truth.UNKNOWN)qualify(p,start);
                            var cycleEdges=new ArrayList<>(edges);cycleEdges.add(edge);
                            result.add(new Path(List.copyOf(vertices),cycleEdges.stream().map(e->e.view).toList(),check.truth(),check.witness()));
                            if(result.size()==p.request.limits().maxResults()){p.budget.halt(StopReason.OUTPUT);p.budget.frontier(start);break;}
                        }else qualify(p,start);
                    }else{
                        if(vertices.size()==p.request.limits().maxDepth()){p.budget.halt(StopReason.DEPTH);p.budget.frontier(edge.target);break;}
                        if(!p.budget.step())break;vertices.add(edge.target);edges.add(edge);visited.add(edge.target);
                        frames.push(new CycleFrame(edge.target,region,p.adjacency.getOrDefault(edge.target,List.of())));
                    }
                }
                if(!p.budget.running())frames.stream().limit(p.request.limits().maxResults()).forEach(f->p.budget.frontier(f.vertex));
            }
        }
        return List.copyOf(result);
    }
    private BoundedGraphTraversal() {}
}
