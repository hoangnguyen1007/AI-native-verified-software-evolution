package fixtures;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
public class ProfileFixture {
    @Bean @Profile("dev & !prod")
    public Token devOnly() { return new Token("devOnly"); }
    @Bean @Profile({"dev", "prod"})
    public Token either() { return new Token("either"); }
    @Bean @Profile("default")
    public Token defaultProfile() { return new Token("defaultProfile"); }
}
