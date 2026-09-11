package research;
import fixtures.*;
import java.util.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.FileSystemResource;

public class RuntimeProbe {
    static AnnotationConfigApplicationContext context() {
        var c = new AnnotationConfigApplicationContext();
        c.getEnvironment().getPropertySources().remove("systemProperties");
        c.getEnvironment().getPropertySources().remove("systemEnvironment");
        return c;
    }
    static void emit(String id, String actual) {
        System.out.println("{\"id\":\"" + id + "\",\"actual\":\"" + actual + "\"}");
    }
    static String tokens(AnnotationConfigApplicationContext c) {
        var names = new TreeSet<>(c.getBeansOfType(Token.class).keySet());
        return String.join(",", names);
    }
    static void order(String id, Class<?>... classes) {
        try (var c = context()) {
            c.register(classes); c.refresh(); emit(id, tokens(c));
        }
    }
    public static void main(String[] args) {
        String[] values = {null, "", "false", "FALSE", "true", "TRUE", "other", " false"};
        for (int i = 0; i < values.length; i++) {
            try (var c = context()) {
                if (values[i] != null)
                    c.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("explicit-fixture", Map.of("feature.enabled", values[i])));
                c.register(PropertyFixture.class); c.refresh(); emit("property-" + i, tokens(c));
            }
        }
        String[][] profiles = {{}, {"dev"}, {"prod"}, {"dev", "prod"}, {"qa"}};
        for (int i = 0; i < profiles.length; i++) {
            try (var c = context()) {
                c.getEnvironment().setActiveProfiles(profiles[i]);
                c.register(ProfileFixture.class); c.refresh(); emit("profile-" + i, tokens(c));
            }
        }
        order("order-ab", MissingAFixture.class, MissingBFixture.class);
        order("order-ba", MissingBFixture.class, MissingAFixture.class);
        order("order-user-a", UserFixture.class, MissingAFixture.class);
        order("order-a-user", MissingAFixture.class, UserFixture.class);
        for (boolean userFirst : new boolean[]{true, false}) {
            try (var c = context()) {
                c.register(userFirst ? new Class<?>[]{UserFixture.class, OnBeanFixture.class}
                                     : new Class<?>[]{OnBeanFixture.class, UserFixture.class});
                c.refresh(); emit("onbean-" + userFirst, "" + c.containsBean("present"));
            }
        }
        for (int count = 0; count <= 2; count++) {
            try (var c = context()) {
                for (int i = 0; i < count; i++) {
                    final int n = i;
                    c.registerBean("t" + i, Token.class, () -> new Token("t" + n));
                }
                c.register(SingleFixture.class); c.refresh();
                emit("single-" + count, "" + c.containsBean("single"));
            }
        }
        try (var c = context()) {
            c.registerBean("p", Token.class, () -> new Token("p"), bd -> bd.setPrimary(true));
            c.registerBean("q", Token.class, () -> new Token("q"));
            c.register(SingleFixture.class); c.refresh(); emit("single-primary", "" + c.containsBean("single"));
        }
        try (var c = context()) {
            c.register(DiFixture.class); c.refresh();
            emit("di-name-priority", c.getBean(DiFixture.Consumer.class).low.id);
        }
        try (var c = context()) {
            c.register(QualifierFixture.class); c.refresh();
            emit("di-qualifier-primary", c.getBean(QualifierFixture.Consumer.class).token.id);
        }
        try (var c = context()) {
            var reader = new XmlBeanDefinitionReader(c);
            reader.setValidationMode(XmlBeanDefinitionReader.VALIDATION_NONE);
            reader.setNamespaceAware(false);
            reader.loadBeanDefinitions(new FileSystemResource(args[0]));
            c.refresh();
            emit("xml-alias-ref", "" + (c.getBean(LegacyConsumer.class).getToken() == c.getBean("service")));
        }
        if (args[1].equals("6.2.0")) {
            try (var c = context()) {
                var bd = new RootBeanDefinition(Token.class);
                bd.getConstructorArgumentValues().addGenericArgumentValue("hidden");
                try { bd.getClass().getMethod("setDefaultCandidate", boolean.class).invoke(bd, false); }
                catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                c.registerBeanDefinition("hidden", bd);
                c.register(MissingAFixture.class); c.refresh(); emit("default-candidate", tokens(c));
            }
        }
    }
}
