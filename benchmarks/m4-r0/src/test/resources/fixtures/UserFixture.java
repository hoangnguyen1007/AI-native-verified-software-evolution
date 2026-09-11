package fixtures;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
public class UserFixture {
    @Bean public Token user() { return new Token("user"); }
}
