package com.evolution.analysis.spring;

import java.util.*;

/** Authored taxonomy control for denominator tests, not a source-extraction oracle. */
public final class BindingInventoryFixtures {
    private BindingInventoryFixtures() {}
    public static SpringMechanismInventory withInjectionObligation(SpringMechanismInventory inventory) {
        var obligations = new ArrayList<>(inventory.obligations());
        obligations.add(SpringMechanismInventory.SemanticObligation.create(inventory.rawObservations().getFirst().identity(), 1,
                "spring.injection.field", "spring.injection.field.authored-control", List.of(), SpringMechanismInventory.Classification.CLASSIFIED));
        return SpringMechanismInventory.create(inventory.analysis(), inventory.frontendResultIdentity(), inventory.frameworkEvidence(),
                inventory.resourceEvidence(), inventory.annotationDeclarations(), inventory.annotationMetaEdges(), inventory.annotationCycles(),
                inventory.rawObservations(), obligations, inventory.markerObservations(), inventory.problems());
    }
}
