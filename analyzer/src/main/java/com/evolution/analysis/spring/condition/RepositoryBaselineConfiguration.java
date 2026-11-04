package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.VersionedIdentifier;
import java.util.*;

/** One named repository-input baseline, never a closed deployment configuration space.
 * Unknown environment, command-line properties and runtime profile changes remain explicit. */
public final class RepositoryBaselineConfiguration {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("spring.repository-baseline","m8.4-v1");
    public record Selected(ConfigurationSpace space,ConfigurationAssignment baseline) {}
    private RepositoryBaselineConfiguration() {}

    public static Selected select(SpringBuildContext build,ConfigDataIngestion.Result captured,
                                  List<ConditionOccurrence> conditions) {
        var evidence=new ConditionEvidence.Derived(List.of(captured.identity()),PROVIDER,"captured-repository-baseline-open-deployment");
        var variables=new TreeSet<FiniteDomain.Variable>();
        var pending=new ArrayDeque<ConditionExpression>();var seen=new HashSet<ConditionExpression.Identity>();
        conditions.forEach(c->pending.add(c.expression()));
        while(!pending.isEmpty()) {
            var expression=pending.removeFirst();if(!seen.add(expression.identity()))continue;
            if(expression.operand() instanceof ConditionExpression.Property property)
                property.keys().forEach(key->variables.add(new FiniteDomain.Variable(FiniteDomain.Kind.PROPERTY,key)));
            if(expression.operand() instanceof ConditionExpression.Profile profile)
                variables.add(new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE,profile.name()));
            pending.addAll(expression.children());
        }
        var values=new TreeMap<FiniteDomain.Variable,FiniteDomain.Value>();var domains=new ArrayList<FiniteDomain>();
        for(var variable:variables) {
            var value=variable.kind()==FiniteDomain.Kind.PROFILE?FiniteDomain.Value.bool(captured.activeProfiles().contains(variable.name())):
                    captured.properties().containsKey(variable.name())?FiniteDomain.Value.exact(captured.properties().get(variable.name())):FiniteDomain.Value.missing();
            values.put(variable,value);domains.add(new FiniteDomain(variable,List.of(value),evidence));
        }
        var documents=captured.documents().stream().map(d->new ConfigurationEnvelope.Document(d.evidence(),Optional.empty(),List.of(),
                ConfigurationEnvelope.Availability.AVAILABLE)).toList();
        var repository=new ConfigurationEnvelope(ConfigurationEnvelope.Layer.REPOSITORY,documents,List.of(),captured.identity());
        var unavailable=new ConfigurationEnvelope.Document(evidence,Optional.empty(),List.of(),ConfigurationEnvelope.Availability.UNOBSERVED_EXTERNAL);
        var deployment=new ConfigurationEnvelope(ConfigurationEnvelope.Layer.DEPLOYMENT,List.of(unavailable),List.of(),evidence.identity());
        var space=new ConfigurationSpace(build,repository,Optional.of(deployment),domains,List.of(),
                new ConfigurationSpace.PrecedencePolicy(ExogenousConditionEvaluator.PRECEDENCE,List.of(),evidence),
                new ConfigurationSpace.ProfilePolicy(ExogenousConditionEvaluator.PROFILES,List.of(),Map.of(),List.of(),evidence),PROVIDER,
                new ConfigurationSpace.FeasibilityPolicy(PROVIDER,ConfigurationSpace.Limits.conservative(),evidence));
        return new Selected(space,new ConfigurationAssignment(values,evidence));
    }
}
