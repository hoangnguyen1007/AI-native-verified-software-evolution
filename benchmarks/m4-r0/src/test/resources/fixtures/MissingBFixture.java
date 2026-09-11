package fixtures;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
@Configuration(proxyBeanMethods=false)
public class MissingBFixture {
    @Bean @ConditionalOnMissingBean(Token.class)
    public Token b() { return new Token("b"); }
}
