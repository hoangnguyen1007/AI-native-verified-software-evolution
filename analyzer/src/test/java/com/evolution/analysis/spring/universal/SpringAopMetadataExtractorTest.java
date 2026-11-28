package com.evolution.analysis.spring.universal;

import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.semantic.TrustTier;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpringAopMetadataExtractorTest {

    private static final SnapshotIdentity SNAPSHOT =
            new SnapshotIdentity("snapshot:sha256:0000000000000000000000000000000000000000000000000000000000000011");

    @Test
    void matchesExecutionAnnotationWithinNamedPointcutsAndOrdersDeterministicallyPerJasmineAlg2() {
        var pointcuts = List.of(
                new SpringAopMetadataExtractor.PointcutDefinition(
                        "com.acme.aop.AuditAspect", "serviceLayer",
                        "within(com.acme.service..*)", "AuditAspect.java", 10),
                new SpringAopMetadataExtractor.PointcutDefinition(
                        "com.acme.aop.AuditAspect", "publicMethods",
                        "execution(public * com.acme.service..*Service.*(..))", "AuditAspect.java", 13));

        // Intentionally shuffled advice order to verify Jasmine Alg. 2 deterministic ordering:
        // (@Order, aspectClassName, advicePrecedence: AROUND -> BEFORE -> AFTER_RETURNING -> AFTER_THROWING -> AFTER)
        var advices = List.of(
                new SpringAopMetadataExtractor.AdviceDeclaration(
                        "com.acme.aop.AuditAspect", "afterLog", SpringAopMetadataExtractor.AdviceKind.AFTER,
                        10, "serviceLayer() && publicMethods()", "AuditAspect.java", 20, TrustTier.SOURCE_SYNTAX),
                new SpringAopMetadataExtractor.AdviceDeclaration(
                        "com.acme.aop.AuditAspect", "aroundTrace", SpringAopMetadataExtractor.AdviceKind.AROUND,
                        10, "serviceLayer() && !@annotation(com.acme.NoTrace)", "AuditAspect.java", 25, TrustTier.SOURCE_SYNTAX),
                new SpringAopMetadataExtractor.AdviceDeclaration(
                        "com.acme.aop.AuditAspect", "beforeAudit", SpringAopMetadataExtractor.AdviceKind.BEFORE,
                        10, "publicMethods()", "AuditAspect.java", 30, TrustTier.SOURCE_SYNTAX),
                new SpringAopMetadataExtractor.AdviceDeclaration(
                        "com.acme.aop.AuditAspect", "afterReturningAudit", SpringAopMetadataExtractor.AdviceKind.AFTER_RETURNING,
                        10, "publicMethods()", "AuditAspect.java", 35, TrustTier.SOURCE_SYNTAX),
                new SpringAopMetadataExtractor.AdviceDeclaration(
                        "com.acme.aop.AuditAspect", "afterThrowingAudit", SpringAopMetadataExtractor.AdviceKind.AFTER_THROWING,
                        10, "publicMethods()", "AuditAspect.java", 40, TrustTier.SOURCE_SYNTAX),
                new SpringAopMetadataExtractor.AdviceDeclaration(
                        "com.acme.aop.SecurityAspect", "checkAuth", SpringAopMetadataExtractor.AdviceKind.BEFORE,
                        1, "@annotation(com.acme.RequiresRole)", "SecurityAspect.java", 12, TrustTier.SOURCE_SYNTAX));

        var targets = List.of(
                new SpringAopMetadataExtractor.TargetMethodDescriptor(
                        "com.acme.service.OrderService", "placeOrder", "void", List.of("java.lang.String"),
                        Set.of("public"), Set.of("org.springframework.stereotype.Service"),
                        Set.of("com.acme.RequiresRole"), Set.of("com.acme.service.OrderService"),
                        "OrderService.java", 15),
                new SpringAopMetadataExtractor.TargetMethodDescriptor(
                        "com.acme.service.OrderService", "healthPing", "boolean", List.of(),
                        Set.of("public"), Set.of("org.springframework.stereotype.Service"),
                        Set.of("com.acme.NoTrace"), Set.of("com.acme.service.OrderService"),
                        "OrderService.java", 28),
                new SpringAopMetadataExtractor.TargetMethodDescriptor(
                        "com.acme.helper.StringHelper", "format", "java.lang.String", List.of("java.lang.String"),
                        Set.of("public"), Set.of(), Set.of(), Set.of("com.acme.helper.StringHelper"),
                        "StringHelper.java", 9));

        var forward = SpringAopMetadataExtractor.match(SNAPSHOT, advices, pointcuts, targets);
        var reversedAdvices = new ArrayList<>(advices);
        Collections.reverse(reversedAdvices);
        var replay = SpringAopMetadataExtractor.match(SNAPSHOT, reversedAdvices, pointcuts, targets);

        assertEquals(forward.identity(), replay.identity(), "I2: AOP matching digest must be input-order invariant");
        assertTrue(forward.gaps().isEmpty(), () -> "Unexpected gaps: " + forward.gaps());

        var placeOrderChain = forward.advicesForMethod("com.acme.service.OrderService", "placeOrder");
        assertEquals(6, placeOrderChain.size());
        // Order 1 (SecurityAspect) comes first, then Order 10 (AuditAspect) in AROUND -> BEFORE -> AFTER_RETURNING -> AFTER_THROWING -> AFTER order!
        assertEquals("com.acme.aop.SecurityAspect", placeOrderChain.get(0).aspectClass());
        assertEquals(SpringAopMetadataExtractor.AdviceKind.BEFORE, placeOrderChain.get(0).adviceKind());
        assertEquals(List.of(
                SpringAopMetadataExtractor.AdviceKind.AROUND,
                SpringAopMetadataExtractor.AdviceKind.BEFORE,
                SpringAopMetadataExtractor.AdviceKind.AFTER_RETURNING,
                SpringAopMetadataExtractor.AdviceKind.AFTER_THROWING,
                SpringAopMetadataExtractor.AdviceKind.AFTER),
                placeOrderChain.subList(1, 6).stream().map(SpringAopMetadataExtractor.AdviceEdge::adviceKind).toList());

        // Negated !@annotation(com.acme.NoTrace) must exclude healthPing from aroundTrace
        var healthChain = forward.advicesForMethod("com.acme.service.OrderService", "healthPing");
        assertTrue(healthChain.stream().noneMatch(e -> e.adviceMethod().equals("aroundTrace")));
        assertEquals(4, healthChain.size());

        // Non-matching helper class must not be proxied
        assertTrue(forward.isClassProxied("com.acme.service.OrderService"));
        assertFalse(forward.isClassProxied("com.acme.helper.StringHelper"));

        // Verify pinned shared evidence contract fields
        var firstPayload = placeOrderChain.getFirst().toEvidencePayload();
        for (String key : List.of("aspectClass", "adviceKind", "order", "pointcut", "targetClass", "targetMethod")) {
            assertTrue(firstPayload.containsKey(key), "Missing pinned spring.aop-advice field: " + key);
        }
        assertEquals("ADVISES_METHOD", placeOrderChain.getFirst().edgeType());
        assertEquals("PROXIED_BY_ASPECT", forward.proxiedByAspectEdges().getFirst().edgeType());
    }

    @Test
    void dynamicAndComplexPointcutsEmitAopPointcutDynamicCapabilityGaps() {
        var advices = List.of(
                new SpringAopMetadataExtractor.AdviceDeclaration(
                        "com.acme.aop.DynamicAspect", "withArgs", SpringAopMetadataExtractor.AdviceKind.BEFORE,
                        5, "execution(* com.acme..*(..)) && args(userId, ..)", "DynamicAspect.java", 14, TrustTier.SOURCE_SYNTAX),
                new SpringAopMetadataExtractor.AdviceDeclaration(
                        "com.acme.aop.DynamicAspect", "withDynamicBean", SpringAopMetadataExtractor.AdviceKind.AROUND,
                        5, "bean(${dynamic.target})", "DynamicAspect.java", 20, TrustTier.SOURCE_SYNTAX),
                new SpringAopMetadataExtractor.AdviceDeclaration(
                        "com.acme.aop.DynamicAspect", "missingNamedRef", SpringAopMetadataExtractor.AdviceKind.AFTER,
                        5, "unknownPointcut()", "DynamicAspect.java", 26, TrustTier.SOURCE_SYNTAX));

        var result = SpringAopMetadataExtractor.match(SNAPSHOT, advices, List.of(), List.of());
        assertEquals(3, result.gaps().size());
        assertTrue(result.gaps().stream().allMatch(g -> SpringAopMetadataExtractor.GAP_AOP_POINTCUT_DYNAMIC.equals(g.reasonCode())));
    }

    @Test
    void staticProxyTrapDetectorFlagsSelfInvocationBypassAndCglibPrivateFinalTrapsWithoutFalsePositives() {
        var aopResult = SpringAopMetadataExtractor.match(
                SNAPSHOT,
                List.of(new SpringAopMetadataExtractor.AdviceDeclaration(
                        "com.acme.aop.AuditAspect", "audit", SpringAopMetadataExtractor.AdviceKind.BEFORE,
                        1, "execution(* com.acme.OrderService.audited*(..))", "AuditAspect.java", 10, TrustTier.SOURCE_SYNTAX)),
                List.of(),
                List.of(
                        new SpringAopMetadataExtractor.TargetMethodDescriptor(
                                "com.acme.OrderService", "auditedStep", "void", List.of(),
                                Set.of("public"), Set.of("org.springframework.stereotype.Service"),
                                Set.of(), Set.of("com.acme.OrderService"), "OrderService.java", 22),
                        new SpringAopMetadataExtractor.TargetMethodDescriptor(
                                "com.acme.OrderService", "auditedFinal", "void", List.of(),
                                Set.of("public", "final"), Set.of("org.springframework.stereotype.Service"),
                                Set.of(), Set.of("com.acme.OrderService"), "OrderService.java", 30)));

        var orderService = new SpringProxyTrapDetector.BeanClassInfo(
                "com.acme.OrderService",
                Set.of("public"),
                Set.of("org.springframework.stereotype.Service"),
                true,
                List.of(
                        new SpringProxyTrapDetector.MethodInfo("com.acme.OrderService", "submit",
                                Set.of("public"), Set.of(), "OrderService.java", 10),
                        new SpringProxyTrapDetector.MethodInfo("com.acme.OrderService", "saveTx",
                                Set.of("public"), Set.of("org.springframework.transaction.annotation.Transactional"), "OrderService.java", 16),
                        new SpringProxyTrapDetector.MethodInfo("com.acme.OrderService", "auditedStep",
                                Set.of("public"), Set.of(), "OrderService.java", 22),
                        new SpringProxyTrapDetector.MethodInfo("com.acme.OrderService", "auditedFinal",
                                Set.of("public", "final"), Set.of(), "OrderService.java", 30),
                        new SpringProxyTrapDetector.MethodInfo("com.acme.OrderService", "privateTx",
                                Set.of("private"), Set.of("org.springframework.transaction.annotation.Transactional"), "OrderService.java", 36),
                        new SpringProxyTrapDetector.MethodInfo("com.acme.OrderService", "finalEndpoint",
                                Set.of("public", "final"), Set.of("org.springframework.web.bind.annotation.GetMapping"), "OrderService.java", 42),
                        new SpringProxyTrapDetector.MethodInfo("com.acme.OrderService", "plainHelper",
                                Set.of("private"), Set.of(), "OrderService.java", 48)),
                "OrderService.java", 5);

        var calls = List.of(
                // 1. Self-invocation bypass on @Transactional
                new SpringProxyTrapDetector.IntraClassCall("com.acme.OrderService", "submit", "saveTx", true, "OrderService.java", 12),
                // 2. Self-invocation bypass on ADVISES_METHOD target
                new SpringProxyTrapDetector.IntraClassCall("com.acme.OrderService", "submit", "auditedStep", true, "OrderService.java", 13),
                // 3. Call via self-injected proxy field -> NOT a bypass!
                new SpringProxyTrapDetector.IntraClassCall("com.acme.OrderService", "submit", "saveTx", false, "OrderService.java", 14),
                // 4. Plain private helper call -> NOT a bypass!
                new SpringProxyTrapDetector.IntraClassCall("com.acme.OrderService", "submit", "plainHelper", true, "OrderService.java", 15));

        var traps = SpringProxyTrapDetector.detect(SNAPSHOT, List.of(orderService), calls, aopResult);

        assertEquals(2, traps.selfInvocationFindings().size(), () -> "self=" + traps.selfInvocationFindings());
        assertTrue(traps.selfInvocationFindings().stream().anyMatch(f -> f.targetMethod().equals("saveTx")));
        assertTrue(traps.selfInvocationFindings().stream().anyMatch(f -> f.targetMethod().equals("auditedStep")));

        // CGLIB traps: auditedFinal (final + ADVISES_METHOD), privateTx (private + @Transactional), finalEndpoint (final + @GetMapping on proxied bean)
        assertEquals(3, traps.cglibPrivateFinalFindings().size(), () -> "cglib=" + traps.cglibPrivateFinalFindings());
        assertTrue(traps.cglibPrivateFinalFindings().stream().anyMatch(f -> f.targetMethod().equals("auditedFinal")));
        assertTrue(traps.cglibPrivateFinalFindings().stream().anyMatch(f -> f.targetMethod().equals("privateTx")));
        assertTrue(traps.cglibPrivateFinalFindings().stream().anyMatch(f -> f.targetMethod().equals("finalEndpoint")));
        assertTrue(traps.cglibPrivateFinalFindings().stream().noneMatch(f -> f.targetMethod().equals("plainHelper")));
    }
}
