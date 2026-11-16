import java.util.Arrays;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** Runtime oracle for the sealed three-file Spring fixture, executed inside the demo VM. */
public final class ProfileCheck {
    public static void main(String[] args) throws Exception {
        boolean dev = switch (args[0]) { case "on" -> true; case "off" -> false; default -> throw new IllegalArgumentException(); };
        boolean expectsStoreInjection = switch (args[1]) {
            case "baseline", "control" -> true;
            case "candidate" -> false;
            default -> throw new IllegalArgumentException();
        };
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(dev ? new String[]{"dev"} : new String[0]);
            context.register(Class.forName("app.App"));
            context.refresh();
            require(Arrays.equals(context.getEnvironment().getActiveProfiles(),dev ? new String[]{"dev"} : new String[0]));
            require(context.getBean("store").getClass().getName().equals("app.Store"));
            require(context.containsBean("client")==dev);
            if (dev) {
                var client = context.getBean("client");
                require(client.getClass().getName().equals("app.Client"));
                var constructors = client.getClass().getDeclaredConstructors();
                require(constructors.length==1);
                var parameters = constructors[0].getParameterTypes();
                require(parameters.length==(expectsStoreInjection ? 1 : 0));
                if (expectsStoreInjection) require(parameters[0].getName().equals("app.Store"));
                require(Arrays.asList(context.getBeanFactory().getDependenciesForBean("client")).contains("store")==expectsStoreInjection);
                System.out.println("EVO_DEPENDENCY "+args[1]+" "+expectsStoreInjection);
            }
        }
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalStateException("SPRING_PROFILE_ASSERTION_FAILED");
    }
}
