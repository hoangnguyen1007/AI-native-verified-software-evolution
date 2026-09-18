import java.util.*;
import jakarta.annotation.Priority;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.support.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.CommonAnnotationBeanPostProcessor;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

/** Trusted authored oracle harness, compiled only by the explicit integration test. */
public class Spring620Probe {
    interface Named { String name(); }
    public static class Token implements Named {
        final String name; public Token(String name) { this.name = name; }
        public String name() { return name; }
    }
    @Priority(1) public static class High extends Token { public High(String name) { super(name); } }
    @Priority(10) public static class Low extends Token { public Low(String name) { super(name); } }
    interface Store<T> extends Named {}
    public static class RawStore extends Token implements Store { public RawStore(String name) { super(name); } }
    public static class StringStore extends Token implements Store<String> { public StringStore(String name) { super(name); } }
    public static class Fields {
        Token token; Token b; Optional<Token> optional; List<Token> list;
        @Qualifier("b") Token qualified;
        Store<String> generic;
    }
    public static class ExplicitResource { @Resource(name="b") Token value; }
    public static class DefaultResource { @Resource Token missing; }
    public static class WrongResource { @Resource(name="b") Runnable value; }

    static DefaultListableBeanFactory factory(boolean[] flags, int[] types, boolean comparator) {
        var factory = new DefaultListableBeanFactory();
        factory.setAutowireCandidateResolver(new QualifierAnnotationAutowireCandidateResolver());
        if (comparator) factory.setDependencyComparator(AnnotationAwareOrderComparator.INSTANCE);
        Class<?>[] classes = {Token.class, High.class, Low.class, RawStore.class, StringStore.class};
        for (int i=0; i<2; i++) {
            final String name = i == 0 ? "a" : "b"; final int type = types[i];
            var definition = new RootBeanDefinition(classes[type]);
            definition.setInstanceSupplier(() -> switch (type) {
                case 1 -> new High(name); case 2 -> new Low(name); case 3 -> new RawStore(name);
                case 4 -> new StringStore(name); default -> new Token(name);
            });
            definition.setPrimary(flags[i]); definition.setFallback(flags[2+i]);
            definition.setAutowireCandidate(flags[4+i]); definition.setDefaultCandidate(flags[6+i]);
            factory.registerBeanDefinition(name, definition);
        }
        return factory;
    }
    public static String observe(String field, boolean[] flags, int[] types, boolean comparator, boolean required) throws Exception {
        var factory = factory(flags, types, comparator);
        try {
            Object result;
            if (field.startsWith("resource")) {
                var processor = new CommonAnnotationBeanPostProcessor(); processor.setBeanFactory(factory);
                if (field.equals("resourceExplicit")) {
                    var consumer = new ExplicitResource(); processor.postProcessProperties(null, consumer, "consumer"); result = consumer.value;
                } else if (field.equals("resourceDefault")) {
                    var consumer = new DefaultResource(); processor.postProcessProperties(null, consumer, "consumer"); result = consumer.missing;
                } else {
                    var consumer = new WrongResource(); processor.postProcessProperties(null, consumer, "consumer"); result = consumer.value;
                }
            } else result = factory.resolveDependency(new DependencyDescriptor(Fields.class.getDeclaredField(field), required), null);
            if (result instanceof Optional<?> optional) result = optional.orElse(null);
            if (result == null) return "ABSENT_OPTIONAL";
            if (result instanceof Collection<?> collection) return "AGGREGATE:" + collection.stream()
                    .map(value -> ((Named)value).name()).collect(java.util.stream.Collectors.joining(","));
            return "SELECTED:" + ((Named)result).name();
        } catch (Exception error) {
            Throwable cursor = error;
            while (cursor != null) {
                String type = cursor.getClass().getSimpleName();
                if (type.equals("NoUniqueBeanDefinitionException")) return "AMBIGUOUS";
                if (type.equals("BeanNotOfRequiredTypeException")) return "ERROR";
                if (type.equals("NoSuchBeanDefinitionException")) return "UNSATISFIED";
                cursor = cursor.getCause();
            }
            throw error;
        } finally { factory.destroySingletons(); }
    }
}
