package fixtures;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.*;
@Configuration(proxyBeanMethods=false)
public class DiFixture {
    @javax.annotation.Priority(1) @jakarta.annotation.Priority(1)
    public static class High extends Token { public High() { super("high"); } }
    public static class Low extends Token { public Low() { super("low"); } }
    public static class Consumer { @Autowired public Token low; }
    @Bean public High high() { return new High(); }
    @Bean public Low low() { return new Low(); }
    @Bean public Consumer consumer() { return new Consumer(); }
}
