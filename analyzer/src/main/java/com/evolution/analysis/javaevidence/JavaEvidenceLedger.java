package com.evolution.analysis.javaevidence;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.acquisition.streaming.ContentHandle;
import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.*;

public final class JavaEvidenceLedger {
    public enum State {PENDING,NARROWED,SATISFIED,NOT_APPLICABLE,CONFLICTED,REOPENED}
    public record Current(State state,List<ContentDigest> answers,List<ContentDigest> proofs,List<ContentDigest> blockers,List<ContentDigest> conflicts){
        public Current{Objects.requireNonNull(state);answers=ordered(answers);proofs=ordered(proofs);blockers=ordered(blockers);conflicts=ordered(conflicts);}
    }
    public static final class View {
        private final Map<ContentDigest,Current> rows;private final Map<ContentDigest,ProviderConflictRecord> conflicts;
        private View(Map<ContentDigest,Current> rows,Map<ContentDigest,ProviderConflictRecord> conflicts){this.rows=Collections.unmodifiableMap(new TreeMap<>(rows));this.conflicts=Collections.unmodifiableMap(new TreeMap<>(conflicts));}
        public Map<ContentDigest,Current> rows(){return rows;}
        public Map<ContentDigest,ProviderConflictRecord> conflicts(){return conflicts;}
        public long active(){return rows.values().stream().filter(r->r.state()!=State.SATISFIED&&r.state()!=State.NOT_APPLICABLE).count();}
        public ContentDigest identity(){var stable=new TreeMap<String,Object>();rows.forEach((key,value)->stable.put(key.value(),value));return CanonicalJson.sha256(Map.of("schema","java-closure-current-view:m8.4-v1","rows",stable));}
        public Map<State,Long> counts(){var counts=new EnumMap<State,Long>(State.class);for(State state:State.values())counts.put(state,rows.values().stream().filter(row->row.state()==state).count());return Collections.unmodifiableMap(counts);}
    }
    public record GapDisposition(CapabilityGapRecord gap,List<ContentDigest> requirements,boolean active){public GapDisposition{requirements=List.copyOf(requirements);}}
    public static final class Published {
        private final JavaEvidenceRecord ledger,projection;private final View current;
        private final JavaEvidenceTables tables;
        private final ContentHandle census;
        private final Map<ContentDigest,JavaEvidenceAuthority.Verified> proofs;
        private final Set<ContentDigest> revoked,closedProducers;
        private Published(JavaEvidenceRecord ledger,JavaEvidenceRecord projection,View current,JavaEvidenceTables tables,ContentHandle census,
                Map<ContentDigest,JavaEvidenceAuthority.Verified> proofs,Set<ContentDigest> revoked,Set<ContentDigest> closedProducers){
            this.ledger=ledger;this.projection=projection;this.current=current;this.tables=tables;this.census=census;
            this.proofs=Map.copyOf(proofs);this.revoked=Set.copyOf(revoked);this.closedProducers=Set.copyOf(closedProducers);
        }
        public JavaEvidenceRecord ledger(){return ledger;}public JavaEvidenceRecord projection(){return projection;}public View current(){return current;}
        /** Revalidate immutable inputs at use; a saved current row is not a live capability. */
        public View revalidate(ContentDigest policy,Set<ContentDigest> newlyRevoked)throws IOException{
            tables.requireContent(census);
            tables.requireContent(new ContentHandle(ledger.identity(),ledger.json().getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
            tables.requireContent(new ContentHandle(projection.identity(),projection.json().getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
            var requirements=new ArrayList<JavaEvidenceRecord>();var decisions=new ArrayList<JavaEvidenceRecord>();
            tables.read(JavaEvidenceTables.Table.from(ledger.fields().get("requirements")),requirements::add,()->false);
            tables.read(JavaEvidenceTables.Table.from(ledger.fields().get("decisions")),decisions::add,()->false);
            tables.read(JavaEvidenceTables.Table.from(ledger.fields().get("bindings")),row->{},()->false);
            tables.read(JavaEvidenceTables.Table.from(projection.fields().get("current")),row->{},()->false);
            var allRevoked=new HashSet<>(revoked);allRevoked.addAll(newlyRevoked);
            allRevoked.addAll(unavailableOccurrenceProofs(requirements,decisions,tables));
            boolean settled=current.rows().values().stream().allMatch(row->!active(row.state()));
            if(settled&&projection.digest("policy").equals(policy)&&allRevoked.equals(revoked)){
                boolean unchanged=true;
                var batch=new JavaEvidenceAuthority.CurrentBatch();
                for(var proof:proofs.values())try{if(!batch.current(proof)){unchanged=false;break;}}catch(IOException missing){unchanged=false;break;}
                // The immutable proof/producer census and all referenced bytes are unchanged.
                // Reuse the already folded answer; never cache verification across uses.
                if(unchanged)return current;
            }
            return new JavaEvidenceLedger().fold(requirements,decisions,proofs,allRevoked,closedProducers,Optional.of(policy));
        }
        public List<JavaEvidenceRecord> requirements()throws IOException{
            var rows=new ArrayList<JavaEvidenceRecord>();
            var table=JavaEvidenceTables.Table.from(ledger.fields().get("requirements"));Optional<JavaEvidenceTables.Cursor> cursor=Optional.empty();
            while(true){var page=tables.scan(table,cursor,256,rows::add,()->false);if(page.state()==JavaEvidenceTables.ScanState.END)break;
                if(page.state()==JavaEvidenceTables.ScanState.CANCELLED)throw new IOException("JAVA_EVIDENCE_CANCELLED_WITH_FRONTIER");cursor=Optional.of(page.cursor());}
            return List.copyOf(rows);
        }
        public List<com.evolution.analysis.graph.GraphEvidence> evidence()throws IOException{
            var result=new ArrayList<com.evolution.analysis.graph.GraphEvidence>();
            for(String name:List.of("requirements","decisions","bindings"))tables.read(JavaEvidenceTables.Table.from(ledger.fields().get(name)),
                    row->result.add(com.evolution.analysis.graph.GraphEvidence.capture("java.evidence.gap-closure-"+name,row.fields())),()->false);
            for(var proof:proofs.values())result.add(com.evolution.analysis.graph.GraphEvidence.capture("java.evidence.proof",proof.record().fields()));
            return List.copyOf(result);
        }
    }
    public static List<GapDisposition> gapDispositions(Collection<CapabilityGapRecord> history,List<JavaEvidenceRecord> requirements,View current){
        var bindings=new HashMap<String,Set<ContentDigest>>();
        for(var requirement:requirements)for(Object gap:GraphJson.array(requirement.fields().get("gapRefs")))bindings.computeIfAbsent(GraphJson.string(gap),key->new TreeSet<>()).add(requirementKey(requirement));
        return history.stream().distinct().sorted().map(gap->{var keys=bindings.getOrDefault(gap.gapIdentity().value(),Set.of());boolean unmet=keys.isEmpty()||keys.stream().anyMatch(key->!current.rows().containsKey(key)||active(current.rows().get(key).state()));return new GapDisposition(gap,List.copyOf(keys),unmet);}).toList();
    }
    /** Persist full causal history separately from stable current rows. A changed census starts a new ledger. */
    public Published publish(Map<String,Object> scope,ContentHandle census,ContentDigest trustPolicy,List<JavaEvidenceRecord> requirements,List<JavaEvidenceRecord> decisions,List<JavaEvidenceRecord> bindings,
            Optional<JavaEvidenceRecord> previous,Map<ContentDigest,JavaEvidenceAuthority.Verified> proofs,Set<ContentDigest> revoked,Set<ContentDigest> closedProducers,JavaEvidenceTables tables,BooleanSupplier cancelled)throws IOException{
        tables.requireContent(census);for(var requirement:requirements)JavaGeneratedOverlay.sameScope(scope,requirement);
        var effectiveRevocations=new HashSet<>(revoked);effectiveRevocations.addAll(unavailableOccurrenceProofs(requirements,decisions,tables));
        var current=fold(requirements,decisions,proofs,effectiveRevocations,closedProducers,Optional.of(trustPolicy));
        for(var conflict:current.conflicts().values())tables.putCanonical(conflict,cancelled);
        var required=new ArrayList<>(requirements);required.sort(JavaEvidenceTables::compare);var history=decisions.stream().distinct().sorted(Comparator.comparing(JavaEvidenceRecord::identity)).toList();var audit=bindings.stream().distinct().sorted(Comparator.comparing(JavaEvidenceRecord::identity)).toList();
        var requirementTable=tables.write("java-closure-requirement",required.iterator(),cancelled);var decisionTable=tables.write("java-closure-decision",history.iterator(),cancelled);var bindingTable=tables.write("java-evidence-binding",audit.iterator(),cancelled);
        if(previous.isPresent()){
            var old=previous.orElseThrow();kind(old,"java-closure-ledger");JavaGeneratedOverlay.sameScope(scope,old);if(!old.digest("census").equals(census.digest()))throw invalid("CENSUS_CHANGED");
            tables.requireContent(new ContentHandle(old.identity(),old.json().getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
            var oldRequirements=new HashSet<ContentDigest>();tables.read(JavaEvidenceTables.Table.from(old.fields().get("requirements")),r->oldRequirements.add(r.identity()),cancelled);
            if(!oldRequirements.equals(new HashSet<>(requirements.stream().map(JavaEvidenceRecord::identity).toList())))throw invalid("REQUIREMENT_CENSUS_CHANGED");
            var ids=new HashSet<>(history.stream().map(JavaEvidenceRecord::identity).toList());tables.read(JavaEvidenceTables.Table.from(old.fields().get("decisions")),r->{if(!ids.contains(r.identity()))throw invalid("HISTORY_DELETION");},cancelled);
            var audits=new HashSet<>(audit.stream().map(JavaEvidenceRecord::identity).toList());tables.read(JavaEvidenceTables.Table.from(old.fields().get("bindings")),r->{if(!audits.contains(r.identity()))throw invalid("AUDIT_DELETION");},cancelled);
        }
        var codec=new JavaEvidenceCodec();var ledger=new LinkedHashMap<String,Object>();ledger.put("schema","java-closure-ledger:m8.4-v1");ledger.put("scope",scope);ledger.put("census",census.digest());ledger.put("requirements",requirementTable.wire());ledger.put("decisions",decisionTable.wire());ledger.put("bindings",bindingTable.wire());ledger.put("previous",previous.map(JavaEvidenceRecord::identity).orElse(null));
        var rows=new ArrayList<JavaEvidenceRecord>();
        for(var entry:current.rows().entrySet()){var row=entry.getValue();rows.add(codec.create(Map.of("schema","java-closure-current-row:m8.4-v1","requirement",entry.getKey(),"state",row.state(),"answers",row.answers(),"proofContents",row.proofs(),"blockers",row.blockers(),"conflicts",row.conflicts())));}
        var currentTable=tables.write("java-closure-current-row",rows.iterator(),cancelled);var counts=new TreeMap<String,Long>();counts.put("total",(long)current.rows().size());counts.put("active",current.active());current.counts().forEach((state,count)->counts.put(state==State.NOT_APPLICABLE?"notApplicable":state.name().toLowerCase(Locale.ROOT),count));
        // Bind the current captured trust policy; differing policies cannot be silently coalesced.
        var projection=codec.create(Map.of("schema","java-closure-projection:m8.4-v1","scope",scope,"census",census.digest(),"validator",new com.evolution.analysis.contract.common.VersionedIdentifier("evidence.java-closure-reconciler","m8.4-step4-v1"),"policy",trustPolicy,"current",currentTable.wire(),"denominator",closedProducers.containsAll(current.rows().keySet())?"CLOSED":"OPEN","counts",counts));
        var ledgerRecord=codec.create(ledger);tables.put(ledgerRecord,cancelled);tables.put(projection,cancelled);return new Published(ledgerRecord,projection,current,tables,census,proofs,revoked,closedProducers);
    }
    public static ContentDigest requirementKey(JavaEvidenceRecord requirement){
        kind(requirement,"java-closure-requirement");var fields=new TreeMap<String,Object>();
        fields.put("schema","java-requirement-key:m8.4-v1");
        for(String field:List.of("scope","question","subject","category","predicate"))fields.put(field,requirement.fields().get(field));
        return CanonicalJson.sha256(fields);
    }
    private static Set<ContentDigest> unavailableOccurrenceProofs(List<JavaEvidenceRecord> requirements,List<JavaEvidenceRecord> decisions,JavaEvidenceTables tables)throws IOException{
        var unavailable=new HashSet<ContentDigest>();var checked=new HashMap<ContentHandle,Boolean>();
        for(var requirement:requirements)for(Object value:GraphJson.array(requirement.fields().get("occurrences"))){
            if(Thread.currentThread().isInterrupted())throw new IOException("JAVA_EVIDENCE_CHECK_CANCELLED");
            var handle=JavaEvidenceRecord.handle(value);Boolean valid=checked.get(handle);
            if(valid==null){try{tables.requireContent(handle);valid=true;}catch(IOException missing){if(Thread.currentThread().isInterrupted())throw missing;valid=false;}checked.put(handle,valid);}
            if(!valid)unavailable.add(requirementKey(requirement));
        }
        var revoked=new HashSet<ContentDigest>();for(var decision:decisions)if(unavailable.contains(decision.digest("requirement")))revoked.addAll(refs(decision,"proofs"));return Set.copyOf(revoked);
    }
    /** closedProducers is the independently reconciled producer census, never provider-emitted rows. */
    public View fold(List<JavaEvidenceRecord> requirements,List<JavaEvidenceRecord> decisions,Map<ContentDigest,JavaEvidenceAuthority.Verified> proofs,Set<ContentDigest> revoked,Set<ContentDigest> closedProducers){
        var policies=proofs.values().stream().map(p->p.record().digest("policy")).distinct().toList();if(policies.size()>1)throw invalid("CURRENT_TRUST_POLICY_REQUIRED");
        return fold(requirements,decisions,proofs,revoked,closedProducers,policies.stream().findFirst());
    }
    private View fold(List<JavaEvidenceRecord> requirements,List<JavaEvidenceRecord> decisions,Map<ContentDigest,JavaEvidenceAuthority.Verified> proofs,Set<ContentDigest> revoked,Set<ContentDigest> closedProducers,Optional<ContentDigest> trustPolicy){
        var byRequirement=new TreeMap<ContentDigest,JavaEvidenceRecord>();
        for(var requirement:requirements)if(byRequirement.putIfAbsent(requirementKey(requirement),requirement)!=null)throw invalid("DUPLICATE_REQUIREMENT");
        if(!byRequirement.keySet().containsAll(closedProducers))throw invalid("FOREIGN_PRODUCER_CENSUS");
        var byDecision=new TreeMap<ContentDigest,JavaEvidenceRecord>();var perRequirement=new HashMap<ContentDigest,List<JavaEvidenceRecord>>();
        for(var decision:decisions){kind(decision,"java-closure-decision");if(!byRequirement.containsKey(decision.digest("requirement")))throw invalid("FOREIGN_REQUIREMENT");
            if(byDecision.putIfAbsent(decision.identity(),decision)==null)perRequirement.computeIfAbsent(decision.digest("requirement"),k->new ArrayList<>()).add(decision);
        }
        for(var decision:byDecision.values())for(var previous:refs(decision,"supersedes")){
            var old=byDecision.get(previous);if(old==null||!old.digest("requirement").equals(decision.digest("requirement")))throw invalid("INVALID_SUPERSESSION");
        }
        order(byDecision,key->refs(byDecision.get(key),"supersedes"));
        var supersededDecisions=new HashSet<ContentDigest>();
        byDecision.values().forEach(decision->supersededDecisions.addAll(refs(decision,"supersedes")));
        var result=new TreeMap<ContentDigest,Current>();var conflictRecords=new TreeMap<ContentDigest,ProviderConflictRecord>();
        var batch=new JavaEvidenceAuthority.CurrentBatch();
        for(var key:order(byRequirement,id->refs(byRequirement.get(id),"prerequisites"))){
            var requirement=byRequirement.get(key);var history=perRequirement.getOrDefault(key,List.of());var superseded=new HashSet<ContentDigest>();
            history.forEach(d->superseded.addAll(refs(d,"supersedes")));
            var heads=history.stream().filter(d->!superseded.contains(d.identity())).toList();
            var blockers=refs(requirement,"prerequisites").stream().filter(id->active(result.get(id).state())).toList();
            var answers=new TreeSet<ContentDigest>();var accepted=new TreeSet<ContentDigest>();var conflicts=new TreeSet<ContentDigest>();
            boolean invalidated=false,incomplete=heads.isEmpty()||!closedProducers.contains(key),narrowed=false,notApplicable=false,satisfied=false;
            for(var head:heads){
                State state=State.valueOf(head.text("state"));conflicts.addAll(refs(head,"conflicts"));
                invalidated|=state==State.REOPENED;
                if(state==State.NARROWED)for(var proofId:refs(head,"proofs")){
                    var token=proofs.get(proofId);if(token==null||!proofId.equals(token.identity())||revoked.contains(proofId)||!matches(requirement,token.record())||trustPolicy.filter(p->!p.equals(token.record().digest("policy"))).isPresent())continue;
                    try{narrowed|=batch.current(token);}catch(IOException unavailable){/* Missing evidence cannot narrow. */}
                }
                if(state!=State.SATISFIED&&state!=State.NOT_APPLICABLE){
                    boolean noAnswer=state==State.PENDING&&refs(head,"proofs").isEmpty()&&refs(head,"conflicts").isEmpty()&&Set.of("PRODUCER_REJECTED","PRODUCER_UNAVAILABLE","PRODUCER_FAILED","PRODUCER_DENIED").contains(head.fields().get("reason"));
                    if(!noAnswer)incomplete=true;continue;
                }
                var proofIds=refs(head,"proofs");boolean valid=!proofIds.isEmpty();
                for(var proofId:proofIds){
                    if(revoked.contains(proofId)){valid=false;invalidated=true;continue;}
                    var token=proofs.get(proofId);
                    if(token==null||!proofId.equals(token.identity())||!matches(requirement,token.record())){valid=false;continue;}
                    if(trustPolicy.isPresent()&&!token.record().digest("policy").equals(trustPolicy.orElseThrow())){valid=false;invalidated=true;continue;}
                    try{if(!batch.current(token)){valid=false;invalidated=true;continue;}}catch(java.io.IOException unavailable){valid=false;invalidated=true;continue;}
                    if(state==State.NOT_APPLICABLE&&!token.record().text("question").equals("java.generator-not-applicable")){valid=false;continue;}
                    accepted.add(proofId);answers.add(token.answer());
                }
                var prerequisiteIds=refs(requirement,"prerequisites");
                for(var p:refs(head,"prerequisites")){
                    var prior=byDecision.get(p);if(prior==null||!prerequisiteIds.contains(prior.digest("requirement"))||supersededDecisions.contains(p))valid=false;
                }
                // Every prerequisite needs a cited terminal decision as well as current closure.
                for(var p:prerequisiteIds)if(refs(head,"prerequisites").stream().map(byDecision::get).filter(Objects::nonNull).noneMatch(d->d.digest("requirement").equals(p)&&Set.of("SATISFIED","NOT_APPLICABLE").contains(d.text("state"))))valid=false;
                incomplete|=!valid;notApplicable|=valid&&state==State.NOT_APPLICABLE;satisfied|=valid&&state==State.SATISFIED;
            }
            // The decision author cannot hide already discovered, valid contrary observations.
            // A validator must explicitly cover such an answer before it can be suppressed.
            var referenced=List.copyOf(accepted);
            for(var entry:new TreeMap<>(proofs).entrySet()){
                var token=entry.getValue();if(!entry.getKey().equals(token.identity())||revoked.contains(token.identity())||!matches(requirement,token.record())||trustPolicy.filter(p->!p.equals(token.record().digest("policy"))).isPresent())continue;
                boolean current;try{current=batch.current(token);}catch(IOException missing){current=false;}if(!current)continue;
                boolean adjudicated=referenced.stream().map(proofs::get).filter(Objects::nonNull).anyMatch(p->!p.answer().equals(token.answer())&&p.adjudicationInputs().contains(token.answer()));
                if(!adjudicated){accepted.add(token.identity());answers.add(token.answer());}
            }
            if(answers.size()>1){
                var alternatives=accepted.stream().map(proofs::get).filter(Objects::nonNull).toList();var left=alternatives.getFirst();var right=alternatives.stream().filter(p->!p.answer().equals(left.answer())).findFirst().orElseThrow();
                var conflict=ProviderConflictRecord.create(EvidenceContext.forSnapshot(new SnapshotIdentity(GraphJson.string(requirement.object("scope").get("baseSnapshot")))),observation(left),observation(right),List.of("java.native-answer"),ProviderConflictRecord.AdjudicationStatus.UNRESOLVED,Optional.empty(),List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE,"java.build-closure")),List.of(),List.of("Contradictory validated answers remain active until explicit adjudication."));
                var identity=CanonicalJson.sha256(conflict);conflicts.add(identity);conflictRecords.put(identity,conflict);
            }
            State state=!conflicts.isEmpty()?State.CONFLICTED:invalidated?State.REOPENED:!blockers.isEmpty()||incomplete||!satisfied&&!notApplicable?narrowed?State.NARROWED:State.PENDING:notApplicable?State.NOT_APPLICABLE:State.SATISFIED;
            // Every suppressed conflicting answer/record needs explicit validator evidence.
            var contrary=new TreeSet<ContentDigest>();
            for(var old:history)if(superseded.contains(old.identity())){
                contrary.addAll(refs(old,"conflicts"));
                for(var oldProof:refs(old,"proofs")){var token=proofs.get(oldProof);if(token==null)contrary.add(oldProof);else if(!answers.isEmpty()&&!answers.contains(token.answer()))contrary.add(token.answer());}
            }
            if(!contrary.isEmpty()){
                boolean adjudicated=heads.stream().flatMap(d->refs(d,"proofs").stream()).map(proofs::get).filter(Objects::nonNull)
                    .anyMatch(p->accepted.contains(p.identity())&&p.adjudicationInputs().containsAll(contrary));
                if(!adjudicated){state=State.CONFLICTED;conflicts.addAll(contrary);}
            }
            if(state==State.PENDING&&!blockers.isEmpty()&&history.stream().anyMatch(d->Set.of("SATISFIED","NOT_APPLICABLE").contains(d.text("state"))))state=State.REOPENED;
            result.put(key,new Current(state,List.copyOf(answers),List.copyOf(accepted),blockers,List.copyOf(conflicts)));
        }
        return new View(result,conflictRecords);
    }
    private static ProviderObservationReference observation(JavaEvidenceAuthority.Verified proof){var provider=proof.record().object("validator");return ProviderObservationReference.create(new VersionedIdentifier(GraphJson.string(provider.get("id")),GraphJson.string(provider.get("version"))),"java.validated-answer",proof.identity(),proof.answer());}
    private static boolean matches(JavaEvidenceRecord req,JavaEvidenceRecord proof){return req.fields().get("scope").equals(proof.fields().get("scope"))&&req.text("question").equals(proof.text("question"))&&req.text("subject").equals(proof.text("subject"))&&req.fields().get("predicate").equals(proof.fields().get("predicate"));}
    private static boolean active(State state){return state!=State.SATISFIED&&state!=State.NOT_APPLICABLE;}
    static List<ContentDigest> refs(JavaEvidenceRecord record,String key){return GraphJson.array(record.fields().get(key)).stream().map(v->new ContentDigest(GraphJson.string(v))).toList();}
    static void kind(JavaEvidenceRecord record,String kind){if(!record.kind().equals(kind))throw invalid("RECORD_KIND");}
    private static IllegalArgumentException invalid(String reason){return new IllegalArgumentException("JAVA_CLOSURE_"+reason);}
    private static List<ContentDigest> ordered(List<ContentDigest> values){return values.stream().distinct().sorted().toList();}
    private static List<ContentDigest> order(Map<ContentDigest,JavaEvidenceRecord> rows,java.util.function.Function<ContentDigest,List<ContentDigest>> prerequisites){
        var pending=new TreeSet<>(rows.keySet());var result=new ArrayList<ContentDigest>();var completed=new HashSet<ContentDigest>();
        for(var key:pending)if(!rows.keySet().containsAll(prerequisites.apply(key)))throw invalid("DANGLING_REFERENCE");
        while(!pending.isEmpty()){
            var ready=pending.stream().filter(key->completed.containsAll(prerequisites.apply(key))).findFirst();
            if(ready.isEmpty())throw invalid("CAUSAL_CYCLE");var key=ready.orElseThrow();pending.remove(key);result.add(key);completed.add(key);
        }return result;
    }
}
