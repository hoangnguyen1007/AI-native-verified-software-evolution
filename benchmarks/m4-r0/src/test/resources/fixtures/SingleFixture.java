package fixtures;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
@Configuration(proxyBeanMethods=false)
public class SingleFixture {
    @Bean @ConditionalOnSingleCandidate(Token.class)
    public String single() { return "single"; }
}
