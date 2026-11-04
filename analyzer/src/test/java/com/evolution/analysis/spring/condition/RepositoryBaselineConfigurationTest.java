package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.ContentDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.spring.condition.ConfigurationSpaceTest.*;
import static com.evolution.analysis.spring.condition.ExogenousConditionEvaluatorTest.*;

class RepositoryBaselineConfigurationTest {
    @Test void baselineContainsReferencedRepositoryInputsButDoesNotCloseDeploymentOverrides(){
        var property=prop("feature.enabled","true",false);var profile=atom(new ConditionExpression.Profile("dev"));
        var captured=new ConfigDataIngestion.Result(ContentDigest.sha256Utf8("captured config"),List.of(),Map.of("feature.enabled","true","unrelated","private"),List.of("dev"),Optional.empty(),List.of(),List.of());
        var selected=RepositoryBaselineConfiguration.select(BUILD,captured,List.of(occurrence(property,0),occurrence(profile,1)));
        assertEquals(2,selected.space().domains().size());assertFalse(selected.baseline().baseline().containsKey(property("unrelated")));
        assertEquals(FiniteDomain.Value.exact("true"),selected.baseline().baseline().get(property("feature.enabled")));
        assertEquals(FiniteDomain.Value.bool(true),selected.baseline().baseline().get(new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE,"dev")));
        var result=ExogenousConditionEvaluator.evaluate(ConditionModel.create(selected.space(),List.of(occurrence(property,0),occurrence(profile,1))),SEM,selected.baseline(),ExogenousConditionEvaluator.Limits.conservative());
        assertTrue(result.rows().stream().allMatch(row->row.truth()==LogicalValue.UNKNOWN),"Uncaptured external overrides/profile changes cannot become repository-wide truth");
        assertFalse(result.capabilityGaps().isEmpty());
        assertEquals(selected.space().identity(),RepositoryBaselineConfiguration.select(BUILD,captured,List.of(occurrence(profile,1),occurrence(property,0))).space().identity());
    }
    @Test void noConditionVariablesStillDefinesOneBaselineWorld(){
        var captured=new ConfigDataIngestion.Result(ContentDigest.sha256Utf8("empty config"),List.of(),Map.of(),List.of(),Optional.empty(),List.of(),List.of());
        var selected=RepositoryBaselineConfiguration.select(BUILD,captured,List.of());
        assertTrue(selected.baseline().baseline().isEmpty());assertEquals(java.math.BigInteger.ONE,selected.space().cartesianSize().value());
    }
}
