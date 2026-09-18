package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.spring.binding.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.spring.condition.InjectionBindingsTest.*;
import static com.evolution.analysis.spring.condition.LogicalValue.*;
import static com.evolution.analysis.spring.binding.InjectionBindingPlan.*;

/** Explicit, offline oracle IT. JARs must be acquired by the existing R0 fetcher first.
 * No production dependency on Spring, and no analyzed repository code is executed. */
class SpringBindingOracleIT {
    @TempDir Path output;
    private static final Map<String, String> PINS = Map.of(
            "spring-core-6.2.0.jar", "3b82629da38717dd616a70ec1055f9a8e208d58132071a6314a32159f8bce162",
            "spring-jcl-6.2.0.jar", "2b59288f030ce77a89ec88aa426f32eb9a309056f3646dd5157f9f31f0204fd7",
            "spring-beans-6.2.0.jar", "33adf77b49236f2966c9cec7eb5a1aea8ade81d7ada80c36d91bbf89d3298e15",
            "spring-aop-6.2.0.jar", "fe84f18bdefb3ad4f993357d31e61613bb55f348d7ea69ec1ca968b3240b3b08",
            "spring-expression-6.2.0.jar", "37cfc1fe8ca22bb789c20aac6b90e09f2b0ea73085f3271ae61cf12cdb7e8876",
            "spring-context-6.2.0.jar", "a0d2de9df7902edc5f6ae55e2936327bdeac177dbc5392906b8d36f3691fe121",
            "jakarta.annotation-api-2.1.1.jar", "5f65fdaf424eee2b55e1d882ba9bb376be93fb09b37b808be6e22e8851c909fe");
    record Case(String key, String field, boolean[] flags, int[] types, boolean comparator, boolean required) {}
    static boolean[] flags(boolean pa, boolean pb, boolean fa, boolean fb, boolean aa, boolean ab, boolean da, boolean db) {
        return new boolean[]{pa,pb,fa,fb,aa,ab,da,db};
    }
    @Test void normalizedEngineConformsToPinnedSpringContainer() throws Exception {
        var jars = Path.of("..", "benchmarks", "m4-r0", ".cache", "jars");
        var paths = new ArrayList<Path>();
        for (var pin : new TreeMap<>(PINS).entrySet()) {
            var path = jars.resolve(pin.getKey());
            assertTrue(Files.isRegularFile(path), "Acquire R0 locked artifacts before running the oracle IT: " + pin.getKey());
            assertEquals("sha256:" + pin.getValue(), ContentDigest.sha256(Files.readAllBytes(path)).value()); paths.add(path);
        }
        String classpath = String.join(java.io.File.pathSeparator, paths.stream().map(Path::toString).toList());
        var source = Path.of("src/test/resources/spring-binding-oracle/Spring620Probe.java");
        var compiler = ToolProvider.getSystemJavaCompiler(); assertNotNull(compiler);
        assertEquals(0, compiler.run(null, null, null, "--release", "21", "-proc:none", "-classpath", classpath,
                "-d", output.toString(), source.toString()));
        var urls = new ArrayList<java.net.URL>(); urls.add(output.toUri().toURL());
        for (var path : paths) urls.add(path.toUri().toURL());
        boolean[] plain = flags(false,false,false,false,true,true,true,true);
        var cases = List.of(
                new Case("name-before-priority", "b", plain, new int[]{1,0}, true, true),
                new Case("primary-before-name", "b", flags(true,false,false,false,true,true,true,true), new int[]{0,1}, true,true),
                new Case("unique-non-fallback", "b", flags(false,false,false,true,true,true,true,true), new int[]{0,1}, true,true),
                new Case("primary-fallback", "token", flags(false,true,false,true,true,true,true,true), new int[]{0,0}, true,true),
                new Case("two-primaries", "b", flags(true,true,false,false,true,true,true,true), new int[]{1,2}, true,true),
                new Case("priority", "token", plain, new int[]{2,1}, true,true),
                new Case("priority-tie", "token", plain, new int[]{1,1}, true,true),
                new Case("no-comparator", "token", plain, new int[]{1,2}, false,true),
                new Case("optional-ambiguous", "optional", plain, new int[]{0,0}, true,false),
                new Case("nonrequired-ambiguous", "token", plain, new int[]{0,0}, true,false),
                new Case("optional-empty", "optional", flags(false,false,false,false,false,false,true,true), new int[]{0,0}, true,true),
                new Case("required-empty", "token", flags(false,false,false,false,false,false,true,true), new int[]{0,0}, true,true),
                new Case("qualifier-excludes-primary", "qualified", flags(true,false,false,false,true,true,true,true), new int[]{0,0}, true,true),
                new Case("qualified-nondefault", "qualified", flags(false,false,false,false,true,true,true,false), new int[]{0,0}, true,true),
                new Case("autowire-false", "qualified", flags(false,false,false,false,true,false,true,true), new int[]{0,0}, true,true),
                new Case("all-fallbacks", "token", flags(false,false,true,true,true,true,true,true), new int[]{1,2}, true,true),
                new Case("aggregate-ignores-primary", "list", flags(true,false,false,true,true,true,true,true), new int[]{0,0}, true,true),
                new Case("aggregate-priority-order", "list", plain, new int[]{2,1}, true,true),
                new Case("empty-required-list", "list", flags(false,false,false,false,false,false,true,true), new int[]{0,0}, true,true),
                new Case("strict-before-raw-primary", "generic", flags(false,true,false,false,true,true,true,true), new int[]{4,3}, true,true),
                new Case("resource-bypasses-flags", "resourceExplicit", flags(true,false,false,false,true,false,true,false), new int[]{0,0}, true,true),
                new Case("resource-default-type-fallback", "resourceDefault", flags(true,false,false,false,true,true,true,true), new int[]{0,0}, true,true),
                new Case("resource-wrong-type", "resourceWrong", plain, new int[]{0,0}, true,true));
        var observations = new TreeMap<String,String>();
        try (var loader = new URLClassLoader(urls.toArray(java.net.URL[]::new), ClassLoader.getPlatformClassLoader())) {
            var method = loader.loadClass("Spring620Probe").getMethod("observe", String.class, boolean[].class, int[].class, boolean.class, boolean.class);
            for (var c : cases) {
                String actual = (String) method.invoke(null, c.field(), c.flags(), c.types(), c.comparator(), c.required());
                assertEquals(actual, normalized(c), c.key()); observations.put(c.key(), actual);
            }
        }
        Files.createDirectories(Path.of("target/m4c3-work"));
        Files.writeString(Path.of("target/m4c3-work/oracle-observations.json"), com.evolution.analysis.contract.serialization.CanonicalJson.write(observations));
        assertEquals(23, observations.size());
    }
    private static String normalized(Case c) throws Exception {
        var f = setup("a", "b"); boolean resource = c.field().startsWith("resource");
        var shape = c.field().equals("optional") ? Shape.OPTIONAL : c.field().equals("list") ? Shape.LIST : Shape.SINGLE;
        var required = c.required() ? Required.REQUIRED : Required.OPTIONAL;
        var name = Name.of(resource ? c.field().equals("resourceDefault") ? "missing" : "b" : c.field());
        var base = dependency(f, shape, name, required);
        var d = change(base, shape, resource ? Mode.RESOURCE : Mode.AUTOWIRE, required, name,
                c.field().equals("qualified") ? Name.of("b") : Name.absent(), c.field().equals("qualified") ? TRUE : FALSE,
                true, resource && c.field().equals("resourceDefault"), resource, false, Normalization.COMPLETE, Optional.empty());
        List<BindingEvidence.Definition> defs = new ArrayList<>(); List<BindingEvidence.Match> matches = new ArrayList<>();
        for (int i=0; i<2; i++) {
            String n = i == 0 ? "a" : "b";
            Integer priority = switch (c.types()[i]) { case 1 -> Integer.valueOf(1); case 2 -> Integer.valueOf(10); default -> null; };
            var def = definition(f,n,c.flags()[i],c.flags()[2+i],priority);
            defs.add(new BindingEvidence.Definition(def.candidate(), c.flags()[4+i] ? TRUE : FALSE, c.flags()[6+i] ? TRUE : FALSE,
                    def.primary(), def.fallback(), def.factoryOwner(), def.runtimeKind(), def.priority(), def.priority(), FALSE, def.evidence()));
            boolean raw = !c.field().equals("resourceWrong") && shape != Shape.LIST;
            matches.add(match(f,d,n,BindingEvidence.Lane.DIRECT,raw ? TRUE : FALSE,
                    c.field().equals("generic") && c.types()[i] == 3 ? FALSE : TRUE, TRUE,
                    c.field().equals("qualified") && i == 0 ? FALSE : TRUE));
            if (shape == Shape.LIST) matches.add(match(f,d,n,BindingEvidence.Lane.ELEMENT,TRUE,TRUE,TRUE,TRUE));
        }
        var p = bindingPlan(f,List.of(d),defs,matches); var env=p.environment();
        p=with(p,new Environment(env.descriptorsComplete(),env.noResolvableDependencies(),env.noPostRegistrationMutation(),
                c.comparator() ? ComparatorPolicy.SPRING_ORDER : ComparatorPolicy.NONE,env.candidateOrder(),env.candidateOrderComplete(),env.evidence()),p.limits());
        var row=resolve(f,p).rows().getFirst();
        return row.outcome().name() + (row.selected().isEmpty() ? "" : ":" + String.join(",",row.selected().stream().map(InjectionBindings.Target::beanName).toList()));
    }
}
