package fixtures;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
@Configuration(proxyBeanMethods=false)
public class PropertyFixture {
    @Bean @ConditionalOnProperty(name="feature.enabled")
    public Token ordinary() { return new Token("ordinary"); }
    @Bean @ConditionalOnProperty(name="feature.enabled", havingValue="true")
    public Token explicitTrue() { return new Token("explicitTrue"); }
    @Bean @ConditionalOnProperty(name="feature.enabled", havingValue="true", matchIfMissing=true)
    public Token missingMatches() { return new Token("missingMatches"); }
}
