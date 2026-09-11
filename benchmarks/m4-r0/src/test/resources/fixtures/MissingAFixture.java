package fixtures;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
@Configuration(proxyBeanMethods=false)
public class MissingAFixture {
    @Bean @ConditionalOnMissingBean(Token.class)
    public Token a() { return new Token("a"); }
}
