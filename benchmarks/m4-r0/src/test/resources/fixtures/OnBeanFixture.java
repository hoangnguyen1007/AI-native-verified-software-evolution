package fixtures;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
@Configuration(proxyBeanMethods=false)
public class OnBeanFixture {
    @Bean @ConditionalOnBean(Token.class)
    public String present() { return "present"; }
}
