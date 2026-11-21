package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.ArchitectureQuery.StopReason;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

/** Evidence-scoped repair proposal. Compiler-checked syntax is a gate to patch output, not a verification verdict. */
public final class CapturedRepair {
    public static final String VERSION="architecture.repair-proposal:captured-v1";
    private static final VersionedIdentifier PROVIDER=new VersionedIdentifier("repair.captured","1");
    private static final VersionedIdentifier GAP_CATALOG=new VersionedIdentifier("repair.capability-gaps","1");
    private static final int MODEL_SOURCE_BYTES=32 * 1024;
    private final RepairChatModel model;

    public enum Strategy { RECIPE, AI }
    public enum Reason { DIAGNOSIS_WITHHELD, QUERY_LIMIT, SOURCE_UNAVAILABLE, RECIPE_NOT_APPLICABLE, AMBIGUOUS_RECIPE_TARGET,
        COMPILER_UNAVAILABLE, BASELINE_SYNTAX_REJECTED, CANDIDATE_SYNTAX_REJECTED, CANDIDATE_LIMIT,
        MODEL_NOT_CONFIGURED, MODEL_INPUT_LIMIT, MODEL_INPUT_MISMATCH, MODEL_UNAVAILABLE, INVALID_MODEL_OUTPUT, NO_EDIT }
    public record Source(String path, ContentDigest digest, List<ContentDigest> citations, Optional<RepairSource.Issue> reason) {}
    public record Offer(RepairChatModel.Provider provider, ContentDigest input, List<Source> files) {}
    public record Options(String version, List<JavaRepairRecipes.Spec> recipes,List<Source> sources,
                   List<CapabilityGapRecord> gaps,Optional<Offer> ai,Optional<Reason> aiUnavailable) {}
    public record Syntax(String path,ContentDigest source,String phase,JavaRepairSyntax.Check result) {}
    public record Edit(String path,ContentDigest before,ContentDigest after,ContentDigest byteEvidence,List<ContentDigest> citations) {}
    public record Candidate(FindingDiagnosis.Pin baseline,ContentDigest diagnosisInput,FindingDiagnosis.Proof affectedScope,
                     List<FindingDiagnosis.Gap> upstreamGaps,Strategy strategy,Optional<JavaRepairRecipes.Spec> recipe,
                     Optional<Offer> model,Optional<ContentDigest> modelResponse,List<Edit> edits,List<Syntax> syntax,
                     ContentDigest patchDigest,String patch,String verification) {}
    public record Proposal(ContentDigest identity,Candidate candidate) {}
    public record Outcome(String version,String status,Optional<Reason> reason,List<Source> sources,List<CapabilityGapRecord> gaps,
                   List<Syntax> syntax,Optional<Proposal> proposal) {}
    private record Inputs(List<RepairSource.File> files,List<Source> sources,List<CapabilityGapRecord> gaps) {}
    private record Request(Optional<Offer> offer,byte[] bytes,Optional<Reason> reason) {}
    private record Replacement(JavaRepairRecipes.Range range,String text) {}
    private record Changed(RepairSource.File source,String text) {}

    CapturedRepair(RepairChatModel model) { this.model=model; }

    Options options(FindingDiagnosis.Outcome diagnosis,KnowledgeGraph graph,Supplier<StopReason> stop) {
        var inputs=collect(diagnosis,graph,stop);
        var request=offer(diagnosis,graph,inputs,stop);
        return new Options(VERSION,JavaRepairRecipes.catalog(),inputs.sources(),inputs.gaps(),request.offer(),request.reason());
    }

    Outcome propose(FindingDiagnosis.Outcome diagnosis,KnowledgeGraph graph,Strategy strategy,
                    Optional<JavaRepairRecipes.Recipe> recipe,Optional<ContentDigest> modelInput,Supplier<StopReason> stop) {
        var inputs=collect(diagnosis,graph,stop);
        var checks=new ArrayList<Syntax>();
        if (stop.get()!=StopReason.NONE) return withheld(Reason.QUERY_LIMIT,inputs,checks);
        if (diagnosis.status()!=FindingDiagnosis.Status.CITATIONS_VALIDATED) return withheld(Reason.DIAGNOSIS_WITHHELD,inputs,checks);
        if (inputs.files().isEmpty()) return withheld(Reason.SOURCE_UNAVAILABLE,inputs,checks);
        var profile=JavaRepairSyntax.profile(graph,diagnosis.pin());
        var changes=new ArrayList<Changed>();
        Optional<Offer> modelOffer=Optional.empty();
        Optional<ContentDigest> modelResponse=Optional.empty();
        if (strategy==Strategy.RECIPE) {
            for (var file:inputs.files()) {
                if (stop.get()!=StopReason.NONE) return withheld(Reason.QUERY_LIMIT,inputs,checks);
                var parsed=JavaRepairSyntax.parse(file.text(),profile.forFile(file));
                checks.add(new Syntax(file.path(),file.digest(),"BASELINE",parsed.check()));
                try {
                    for (var nextText:JavaRepairRecipes.candidates(recipe.orElseThrow(),file,parsed))
                        changes.add(new Changed(file,nextText));
                } catch (StackOverflowError depth) {
                    return withheld(Reason.CANDIDATE_LIMIT,inputs,checks);
                }
            }
            if (changes.size()>1) return withheld(Reason.AMBIGUOUS_RECIPE_TARGET,inputs,checks);
            if (changes.isEmpty()) {
                var reason=checks.stream().anyMatch(c -> c.result().status()==JavaRepairSyntax.Status.UNAVAILABLE) ? Reason.COMPILER_UNAVAILABLE
                        : checks.stream().anyMatch(c -> c.result().status()==JavaRepairSyntax.Status.REJECTED) ? Reason.BASELINE_SYNTAX_REJECTED
                        : Reason.RECIPE_NOT_APPLICABLE;
                return withheld(reason,inputs,checks);
            }
        } else {
            var request=offer(diagnosis,graph,inputs,stop);
            if (request.reason().isPresent()) return withheld(request.reason().orElseThrow(),inputs,checks);
            modelOffer=request.offer();
            if (!modelOffer.map(Offer::input).equals(modelInput)) return withheld(Reason.MODEL_INPUT_MISMATCH,inputs,checks);
            try {
                var reply=model.propose(request.bytes(),stop);
                if (reply.edits().isEmpty()) return withheld(Reason.NO_EDIT,inputs,checks);
                changes.addAll(apply(reply.edits(),inputs.files()));
                modelResponse=Optional.of(reply.contentDigest());
            } catch (RepairChatModel.Rejected rejected) {
                return withheld(Reason.valueOf(rejected.reason.name()),inputs,checks);
            } catch (IllegalArgumentException invalid) {
                return withheld(Reason.INVALID_MODEL_OUTPUT,inputs,checks);
            }
        }

        var edits=new ArrayList<Edit>();
        Reason failed=null;
        for (var change:changes) {
            if (stop.get()!=StopReason.NONE) return withheld(Reason.QUERY_LIMIT,inputs,checks);
            var file=change.source();
            String next=change.text();
            var encoding=file.ascii() ? StandardCharsets.US_ASCII : StandardCharsets.UTF_8;
            if (next.isBlank() || next.indexOf(0)>=0 || !encoding.newEncoder().canEncode(next))
                return withheld(strategy==Strategy.AI ? Reason.INVALID_MODEL_OUTPUT : Reason.NO_EDIT,inputs,checks);
            byte[] bytes=next.getBytes(encoding);
            if (bytes.length>RepairSource.MAX_FILE_BYTES) return withheld(Reason.CANDIDATE_LIMIT,inputs,checks);
            var digest=ContentDigest.sha256(bytes);
            if (digest.equals(file.digest())) return withheld(Reason.NO_EDIT,inputs,checks);
            var checked=JavaRepairSyntax.parse(next,profile.forFile(file)).check();
            checks.add(new Syntax(file.path(),digest,"CANDIDATE",checked));
            if (checked.status()==JavaRepairSyntax.Status.REJECTED) failed=Reason.CANDIDATE_SYNTAX_REJECTED;
            else if (checked.status()==JavaRepairSyntax.Status.UNAVAILABLE && failed==null) failed=Reason.COMPILER_UNAVAILABLE;
            edits.add(new Edit(file.path(),file.digest(),digest,file.byteEvidence(),citationIds(file.citations())));
        }
        if (failed!=null) return withheld(failed,inputs,checks);
        if (stop.get()!=StopReason.NONE) return withheld(Reason.QUERY_LIMIT,inputs,checks);
        var patch=new StringBuilder();
        for (var change:changes) patch.append(RepairPatch.file(change.source().path(),change.source().text(),change.text()));
        String diff=patch.toString();
        var candidate=new Candidate(diagnosis.pin(),diagnosis.input().orElseThrow(),diagnosis.proof().orElseThrow(),diagnosis.gaps(),
                strategy,recipe.map(JavaRepairRecipes.Recipe::spec),modelOffer,modelResponse,List.copyOf(edits),List.copyOf(checks),
                ContentDigest.sha256Utf8(diff),diff,"UNVERIFIED");
        return new Outcome(VERSION,"PROPOSED",Optional.empty(),inputs.sources(),inputs.gaps(),List.of(),
                Optional.of(new Proposal(CanonicalJson.sha256(List.of(VERSION,candidate)),candidate)));
    }

    private static Inputs collect(FindingDiagnosis.Outcome diagnosis,KnowledgeGraph graph,Supplier<StopReason> stop) {
        var groups=new TreeMap<String,List<FindingDiagnosis.Citation>>();
        for (var c:diagnosis.citations()) groups.computeIfAbsent(c.path(),unused -> new ArrayList<>()).add(c);
        var files=new ArrayList<RepairSource.File>();
        var sources=new ArrayList<Source>();
        var gaps=new ArrayList<CapabilityGapRecord>();
        for (var row:groups.entrySet()) {
            var citations=row.getValue();
            Optional<RepairSource.Issue> issue=Optional.empty();
            try {
                if (stop.get()!=StopReason.NONE) throw new RepairSource.Unavailable(RepairSource.Issue.QUERY_LIMIT);
                files.add(RepairSource.open(graph,citations));
            }
            catch (RepairSource.Unavailable unavailable) {
                issue=Optional.of(unavailable.issue);
                gaps.add(gap(diagnosis,citations,unavailable.issue));
            }
            sources.add(new Source(row.getKey(),citations.getFirst().sourceDigest(),citationIds(citations),issue));
        }
        return new Inputs(List.copyOf(files),List.copyOf(sources),List.copyOf(gaps));
    }

    private Request offer(FindingDiagnosis.Outcome diagnosis,KnowledgeGraph graph,Inputs inputs,Supplier<StopReason> stop) {
        if (stop.get()!=StopReason.NONE) return unavailable(Reason.QUERY_LIMIT);
        if (diagnosis.status()!=FindingDiagnosis.Status.CITATIONS_VALIDATED) return unavailable(Reason.DIAGNOSIS_WITHHELD);
        if (model==null) return unavailable(Reason.MODEL_NOT_CONFIGURED);
        if (inputs.files().isEmpty()) return unavailable(Reason.SOURCE_UNAVAILABLE);
        var files=new ArrayList<Map<String,Object>>();
        int bytes=0;
        for (var file:inputs.files()) {
            bytes+=file.text().getBytes(StandardCharsets.UTF_8).length;
            if (bytes>MODEL_SOURCE_BYTES) return unavailable(Reason.MODEL_INPUT_LIMIT);
            var ranges=JavaRepairRecipes.citationRanges(file);
            var citationMaps=new ArrayList<Map<String,Object>>(file.citations().size());
            for (int i=0;i<file.citations().size();i++) {
                var c=file.citations().get(i);
                String excerpt="";
                if (i<ranges.size()) {
                    var r=ranges.get(i);
                    int start=Math.max(0,Math.min(r.start(),file.text().length()));
                    int end=Math.max(start,Math.min(r.end(),Math.min(file.text().length(),start+512)));
                    excerpt=file.text().substring(start,end);
                }
                citationMaps.add(Map.of("family",c.family(),"span",c.span(),"excerpt",excerpt));
            }
            files.add(Map.of("file",files.size()+1,"path",file.path(),"before",file.digest(),"source",file.text(),
                    "citations",List.copyOf(citationMaps)));
        }
        var proof=diagnosis.proof().orElseThrow();
        String ctx=diagnosis.pin().analysis().map(a -> a.value()).orElse(diagnosis.pin().context());
        String sourceName=entityLabel(graph,ctx,diagnosis.pin().context(),proof.source()), targetName=entityLabel(graph,ctx,diagnosis.pin().context(),proof.target());
        byte[] wire;
        try {
            wire=model.request(Map.of("rule",proof.rule(),"scope",proof.scope(),"classification",proof.classification(),
                    "relationships",proof.relationships(),"sourceEntity",proof.source(),"sourceName",sourceName,
                    "targetEntity",proof.target(),"targetName",targetName,"files",files),
                    inputs.files().stream().map(RepairSource.File::digest).toList());
        } catch (BoundedJson.TooLarge limit) {
            return unavailable(Reason.MODEL_INPUT_LIMIT);
        }
        var id=CanonicalJson.sha256(List.of(VERSION,model.provider(),diagnosis.pin(),diagnosis.input().orElseThrow(),ContentDigest.sha256(wire)));
        return new Request(Optional.of(new Offer(model.provider(),id,inputs.sources().stream().filter(s -> s.reason().isEmpty()).toList())),
                wire,Optional.empty());
    }

    static boolean authentic(Proposal proposal) {
        if (proposal==null || proposal.candidate()==null) return false;
        var candidate=proposal.candidate();
        return CanonicalJson.sha256(List.of(VERSION,candidate)).equals(proposal.identity())
                && ContentDigest.sha256Utf8(candidate.patch()).equals(candidate.patchDigest())
                && !candidate.edits().isEmpty();
    }

    private static String entityLabel(KnowledgeGraph graph,String primaryContext,String secondaryContext,String entityId) {
        var contexts=new LinkedHashSet<String>();
        if (primaryContext!=null && !primaryContext.isBlank()) contexts.add(primaryContext);
        if (secondaryContext!=null && !secondaryContext.isBlank()) contexts.add(secondaryContext);
        for (var context : contexts) {
            for (var node : graph.nodes(context)) {
                if (!node.identity().equals(entityId)) continue;
                for (var ref : node.evidence()) {
                    var ev=graph.evidence(ref).orElse(null);
                    if (ev==null || !ev.family().equals("java.declaration")) continue;
                    try {
                        var entity=GraphJson.object(GraphJson.object(GraphJson.read(ev.canonicalJson(),GraphJson.Limits.defaults())).get("entity"));
                        if (entity.get("qualifiedName") instanceof String q && !q.isBlank()) return q;
                        if (entity.get("canonicalName") instanceof String c && !c.isBlank()) return c;
                    } catch (RuntimeException ignored) {}
                }
            }
        }
        return entityId;
    }

    private static List<Changed> apply(List<RepairChatModel.Change> edits,List<RepairSource.File> files) {
        var groups=new TreeMap<Integer,List<Replacement>>();
        for (var edit:edits) {
            if (edit.file()<1 || edit.file()>files.size()) throw new IllegalArgumentException();
            var file=files.get(edit.file()-1);
            if (!file.digest().equals(edit.before())) throw new IllegalArgumentException();
            int start=file.text().indexOf(edit.oldText());
            if (start<0 || file.text().indexOf(edit.oldText(),start+1)>=0) throw new IllegalArgumentException();
            var range=new JavaRepairRecipes.Range(start,start+edit.oldText().length());
            groups.computeIfAbsent(edit.file(),unused -> new ArrayList<>()).add(new Replacement(range,edit.newText()));
        }
        boolean touchesEvidence=false;
        var result=new ArrayList<Changed>();
        for (var group:groups.entrySet()) {
            var file=files.get(group.getKey()-1);
            var replacements=group.getValue();
            var citations=JavaRepairRecipes.citationRanges(file);
            replacements.sort(Comparator.comparingInt(r -> r.range().start()));
            var candidate=new StringBuilder();
            int previousEnd=0;
            for (var edit:replacements) {
                var range=edit.range();
                if (range.start()<previousEnd) throw new IllegalArgumentException();
                touchesEvidence|=citations.stream().anyMatch(c -> JavaRepairRecipes.overlaps(range,c));
                candidate.append(file.text(),previousEnd,range.start()).append(edit.text());
                previousEnd=range.end();
            }
            result.add(new Changed(file,candidate.append(file.text(),previousEnd,file.text().length()).toString()));
        }
        if (!touchesEvidence) throw new IllegalArgumentException();
        return List.copyOf(result);
    }

    private static CapabilityGapRecord gap(FindingDiagnosis.Outcome diagnosis,List<FindingDiagnosis.Citation> citations,RepairSource.Issue issue) {
        var first=citations.getFirst();
        var subject=new EvidenceSubject(EvidenceSubject.Kind.DOCUMENT,first.span().document().value());
        var kind=switch (issue) {
            case SOURCE_BYTES_UNAVAILABLE, SOURCE_INTEGRITY_MISMATCH, SOURCE_LIMIT, QUERY_LIMIT -> EvidenceRequirement.Kind.REPOSITORY_CONTENT;
            case SOURCE_NOT_EDITABLE -> EvidenceRequirement.Kind.SOURCE_OWNERSHIP;
            case SOURCE_ENCODING_UNSUPPORTED -> EvidenceRequirement.Kind.DECODED_SOURCE;
            case COMPILER_UNSUPPORTED -> EvidenceRequirement.Kind.ALTERNATE_FRONTEND;
        };
        var requirement=new EvidenceRequirement(kind,"repair.checked-source",List.of(subject),
                EvidenceRequirement.AuthorizationClass.PASSIVE,List.of("Obtain editable source bytes and a supported syntax check for this citation."));
        var observation=ProviderObservationReference.create(PROVIDER,"repair.source-availability",
                diagnosis.input().orElseThrow(),CanonicalJson.sha256(List.of(first.path(),first.sourceDigest(),citationIds(citations),issue)));
        return CapabilityGapRecord.create(GAP_CATALOG,new EvidenceContext(diagnosis.pin().snapshot(),diagnosis.pin().analysis()),
                PROVIDER,"repair.source",issue.name(),subject,citations.stream().map(FindingDiagnosis.Citation::span).toList(),
                List.of(observation),List.of(requirement),List.of(),List.of(new AffectedOutput(AffectedOutput.Kind.POLICY,"repair.proposal")),
                List.of(),List.of(),List.of());
    }
    private static List<ContentDigest> citationIds(List<FindingDiagnosis.Citation> citations) {
        return citations.stream().map(FindingDiagnosis.Citation::identity).distinct().sorted().toList();
    }
    private static Request unavailable(Reason reason) { return new Request(Optional.empty(),new byte[0],Optional.of(reason)); }
    private static Outcome withheld(Reason reason,Inputs inputs,List<Syntax> checks) {
        return new Outcome(VERSION,"WITHHELD",Optional.of(reason),inputs.sources(),inputs.gaps(),List.copyOf(checks),Optional.empty());
    }
}
