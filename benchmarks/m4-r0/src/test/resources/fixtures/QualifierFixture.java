package fixtures;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.*;
@Configuration(proxyBeanMethods=false)
public class QualifierFixture {
    public static class Consumer { @Autowired @Qualifier("chosen") public Token token; }
    @Bean @Primary public Token primary() { return new Token("primary"); }
    @Bean @Qualifier("chosen") public Token chosen() { return new Token("chosen"); }
    @Bean public Consumer consumer() { return new Consumer(); }
}
