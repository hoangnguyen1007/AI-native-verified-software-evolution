package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.policy.PolicyEvaluation;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;
import java.util.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;

/** A reviewer can inspect or export this candidate. Application and verification belong to an isolated worker. */
public final class RepairProposal {
    public static final String VERSION = "architecture.repair-proposal:demo-v1";

    // Operator-independent registration of the authored fixture's only editable file. The graph
    // has source descriptors but predates embedded captured bytes, so hash-check these exact bytes.
    private static final SnapshotIdentity SNAPSHOT = new SnapshotIdentity("snapshot:sha256:ac54918ce56f2f3d8561e7cb7556200759cebb8527d30beb1f7e46c69050659e");
    private static final Map<ContentDigest,String> POLICY_VERSIONS = Map.of(
            new ContentDigest("sha256:67ca6fce9a53127603f0906e73b7b2a33db089d24a2a3df13deeb6f2215e8232"),"1",
            new ContentDigest("sha256:8f301531495bb8b68637768aac4e5d4e6883aca2cd919001f2849436268a3a58"),"2",
            new ContentDigest("sha256:2fe739e3cb106a0f95aeaf536d2a277571189ade7c7ba7421e3a7668a91a7c03"),"2");
    private static final ContentDigest ORIGINAL = new ContentDigest("sha256:10026b4edd69da18adc2587c77db51b149a6ac597d1758681736a291e6ce3eaa");
    private static final String PATH = "src/main/java/app/Client.java";
    static final String SOURCE = "package app; @org.springframework.stereotype.Component @org.springframework.context.annotation.Profile(\"dev\") class Client { public Client(Store store){} }";
    private static final VersionedIdentifier ENGINE = new VersionedIdentifier("openrewrite.java","8.87.7");
    private static final RecipeSpec RECIPE = new RecipeSpec(DemoRepairRecipe.class.getName(),"1");

    enum Status { PROPOSED, WITHHELD }
    enum Reason { DIAGNOSIS_WITHHELD, FIXTURE_NOT_REGISTERED, SOURCE_MISMATCH, TRANSFORMATION_UNAVAILABLE, QUERY_LIMIT }
    enum Verification { UNVERIFIED }
    enum ExpectedFindingChange { REMOVED }

    record Plan(String summary,List<String> steps,List<String> verificationChecks,ExpectedFindingChange expectedFindingChange) {}
    record Edit(String path,ContentDigest before,ContentDigest after) {}
    record RecipeSpec(String name,String version) {}
    record Candidate(FindingDiagnosis.Pin baseline,ContentDigest diagnosisInput,FindingDiagnosis.Mode diagnosisMode,
                     ContentDigest policySource,FindingDiagnosis.Proof affectedScope,FindingDiagnosis.Citation citation,
                     VersionedIdentifier engine,RecipeSpec recipe,Plan plan,Edit edit,
                     ContentDigest patchDigest,String patch,Verification verification) {}
    record Proposal(ContentDigest identity,Candidate candidate) {}
    record Outcome(String version,Status status,Optional<Reason> reason,Optional<Proposal> proposal) {}

    Outcome prepare(FindingDiagnosis.Outcome diagnosis, PolicyEvaluation.Finding finding,
                    java.util.function.Supplier<StopReason> stop) {
        if (diagnosis.status() != FindingDiagnosis.Status.CITATIONS_VALIDATED) return withheld(Reason.DIAGNOSIS_WITHHELD);
        var proof = diagnosis.proof().orElseThrow();
        var policySource = finding.bundle().policySource();
        if (!diagnosis.pin().snapshot().equals(SNAPSHOT) || !proof.rule().id().equals("client.no-store")
                || !proof.rule().version().equals(POLICY_VERSIONS.get(policySource)) || proof.scope() != Scope.SPRING_COMPONENT
                || proof.classification() != Classification.MAY || !proof.relationships().equals(List.of("spring.selected-binding")))
            return withheld(Reason.FIXTURE_NOT_REGISTERED);

        var citation = diagnosis.citations().stream().filter(c -> c.path().equals(PATH) && c.sourceDigest().equals(ORIGINAL)
                && c.span().startLine()==1 && c.span().endLine()==1 && c.span().startColumn()==140 && c.span().endColumn()==151).findFirst();
        if (citation.isEmpty() || !ContentDigest.sha256Utf8(SOURCE).equals(ORIGINAL)) return withheld(Reason.SOURCE_MISMATCH);
        if (stop.get() != StopReason.NONE) return withheld(Reason.QUERY_LIMIT);

        String after;
        try { after = transform(); }
        catch (RuntimeException | LinkageError failure) { return withheld(Reason.TRANSFORMATION_UNAVAILABLE); }
        if (stop.get() != StopReason.NONE) return withheld(Reason.QUERY_LIMIT);
        String expected = SOURCE.substring(0,139) + SOURCE.substring(150);
        if (!after.equals(expected)) return withheld(Reason.TRANSFORMATION_UNAVAILABLE);

        String patch = "diff --git a/" + PATH + " b/" + PATH + "\n--- a/" + PATH + "\n+++ b/" + PATH
                + "\n@@ -1 +1 @@\n-" + SOURCE + "\n\\ No newline at end of file\n+" + after + "\n\\ No newline at end of file\n";
        var plan = new Plan("Remove the unused Store injection from the profiled Client.",
                List.of("Remove Store store from the empty Client constructor.", "Preserve the dev profile and the Store bean."),
                List.of("Verify the baseline source digest before applying the patch in an isolated workspace.",
                        "Check that Client starts with dev enabled and remains inactive with dev disabled.",
                        "Reanalyze the candidate and compare the same policy and modeled configurations."),ExpectedFindingChange.REMOVED);
        var edit = new Edit(PATH,ORIGINAL,ContentDigest.sha256Utf8(after));
        var candidate = new Candidate(diagnosis.pin(),diagnosis.input().orElseThrow(),diagnosis.mode(),policySource,proof,citation.orElseThrow(),
                ENGINE,RECIPE,plan,edit,ContentDigest.sha256Utf8(patch),patch,Verification.UNVERIFIED);
        var identity = CanonicalJson.sha256(List.of(VERSION,candidate));
        return new Outcome(VERSION,Status.PROPOSED,Optional.empty(),Optional.of(new Proposal(identity,candidate)));
    }

    private static String transform() {
        var context = new InMemoryExecutionContext(error -> { throw new IllegalStateException("OpenRewrite transformation failed",error); });
        var parsed = JavaParser.fromJavaVersion().build().parse(context,SOURCE).toList();
        if (parsed.size()!=1 || !(parsed.getFirst() instanceof J.CompilationUnit unit) || !unit.printAll().equals(SOURCE))
            throw new IllegalStateException("OpenRewrite source round trip failed");
        var changes = new DemoRepairRecipe().run(new InMemoryLargeSourceSet(List.of(unit)),context).getChangeset().getAllResults();
        if (changes.size()!=1 || changes.getFirst().getBefore()!=unit || changes.getFirst().getAfter()==null)
            throw new IllegalStateException("Expected one changed source file");
        return changes.getFirst().getAfter().printAll();
    }

    private static Outcome withheld(Reason reason) {
        return new Outcome(VERSION,Status.WITHHELD,Optional.of(reason),Optional.empty());
    }
}
