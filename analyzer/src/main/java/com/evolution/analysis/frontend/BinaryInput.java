package com.evolution.analysis.frontend;

import com.evolution.analysis.contract.analysis.ClasspathEntry;
import com.evolution.analysis.contract.analysis.ClasspathEntryKind;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.contract.common.ContentDigest;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import com.evolution.analysis.javaevidence.*;
import com.evolution.analysis.acquisition.streaming.RepositoryContentStore;
import java.io.IOException;

/** Supplied JAR handle, not part of stable identity. Never an ambient classpath. */
public record BinaryInput(ClasspathEntry entry, Path path, Optional<ModuleIdentity> reactorModule, Optional<NativeOrigin> nativeOrigin) {
    public static final class NativeResolutionCensus {
        private final JavaEvidenceRecord manifest;private final JavaEvidenceAuthority.Verified context;private final RepositoryContentStore content;private final java.util.Set<ContentDigest> slots;private final boolean jpms,reactorSources;
        private NativeResolutionCensus(JavaEvidenceRecord manifest,JavaEvidenceAuthority.Verified context,RepositoryContentStore content,java.util.Set<ContentDigest> slots,boolean jpms,boolean reactorSources){this.manifest=manifest;this.context=context;this.content=content;this.slots=java.util.Set.copyOf(slots);this.jpms=jpms;this.reactorSources=reactorSources;}
        public boolean jpms(){return jpms;}public boolean reactorSources(){return reactorSources;}
    }
    public static NativeResolutionCensus nativeCensus(JavaEvidenceRecord manifest,JavaEvidenceAuthority.Verified context,RepositoryContentStore content,int workerSlots,java.util.function.BooleanSupplier cancelled)throws IOException{
        if(!manifest.kind().equals("java-resolution-manifest")||!manifest.text("status").equals("EXACT")||!context.current()||!context.identity().equals(manifest.digest("contextProof"))||!context.record().text("question").equals("java.resolution-context")||!context.record().text("subject").equals(manifest.object("scope").get("context"))||!context.record().fields().get("scope").equals(manifest.fields().get("scope"))||!context.answer().equals(JavaResolutionPlanner.answer(manifest)))throw new IOException("JAVA_NATIVE_CONTEXT_PROOF_MISMATCH");
        var table=JavaEvidenceTables.Table.from(manifest.fields().get("slots"));if(table.rows()>workerSlots)throw new IOException("JAVA_NATIVE_CONTEXT_WORKER_REQUIRED");var ids=new java.util.HashSet<ContentDigest>();boolean[] unsupported={false,false};
        new JavaEvidenceTables(content,2_000_000).read(table,row->{if(!row.fields().get("scope").equals(manifest.fields().get("scope")))throw new IOException("JAVA_NATIVE_SLOT_SCOPE_MISMATCH");if(row.text("space").equals("MODULE_PATH"))unsupported[0]=true;if(row.text("space").equals("CLASS_PATH")){if(row.number("ordinal")!=ids.size())throw new IOException("JAVA_NATIVE_SLOT_ORDER_INVALID");ids.add(row.identity());if(row.text("kind").equals("REACTOR_SOURCE"))unsupported[1]=true;}},cancelled);
        return new NativeResolutionCensus(manifest,context,content,ids,unsupported[0],unsupported[1]);
    }
    public static final class NativeOriginProof {
        private final ContentDigest resolution;private final JavaEvidenceAuthority.Verified shape,context;private final ClasspathEntry original;private final ContentDigest slot;
        private final RepositoryContentStore content;private final java.util.List<com.evolution.analysis.acquisition.streaming.ContentHandle> references;private final ClasspathEntry solver;private final long ordinal,classpathSize;
        private final JavaEvidenceRecord nativeSlot,nativeManifest;
        private NativeOriginProof(ContentDigest resolution,JavaEvidenceAuthority.Verified shape,JavaEvidenceAuthority.Verified context,ClasspathEntry original,ContentDigest slot,RepositoryContentStore content,java.util.List<com.evolution.analysis.acquisition.streaming.ContentHandle> references,ClasspathEntry solver,long ordinal,long classpathSize,JavaEvidenceRecord nativeSlot,JavaEvidenceRecord nativeManifest){this.resolution=resolution;this.shape=shape;this.context=context;this.original=original;this.slot=slot;this.content=content;this.references=java.util.List.copyOf(references);this.solver=solver;this.ordinal=ordinal;this.classpathSize=classpathSize;this.nativeSlot=nativeSlot;this.nativeManifest=nativeManifest;}
        boolean matches(FrontendPlan plan,ModuleIdentity module,com.evolution.analysis.contract.source.SourceClassification sourceSet,java.util.Map<String,String> options,int position,int count,java.util.Map<RepositoryContentStore,java.util.Set<com.evolution.analysis.acquisition.streaming.ContentHandle>> checkedContent,java.util.Set<JavaEvidenceAuthority.Verified> checkedProofs){
            var scope=context.record().object("scope");
            try{var checked=checkedContent.computeIfAbsent(content,key->new java.util.HashSet<>());for(var reference:references)if(!checked.contains(reference)){if(!content.verify(reference))return false;checked.add(reference);}for(var proof:java.util.List.of(shape,context))if(!checkedProofs.contains(proof)){if(!proof.current())return false;checkedProofs.add(proof);}return ordinal==position&&classpathSize==count&&resolution.equals(plan.classpathManifest())&&module.value().equals(scope.get("module"))&&Objects.equals(options.get("java.native-context"),scope.get("context"))&&Objects.equals(options.getOrDefault("ingestion.source-set-name",sourceSet.name().toLowerCase(java.util.Locale.ROOT)),scope.get("sourceSet"));}catch(IOException unavailable){return false;}
        }
        public Object canonicalForm(){return java.util.Map.of("resolution",resolution,"shape",shape.identity(),"context",context.identity());}
        boolean correspondenceMatches(JavaSourceCorrespondence.Verified binding){return context.record().object("scope").equals(binding.scope())&&binding.policy().filter(context.record().digest("policy")::equals).isPresent();}
        public java.util.List<com.evolution.analysis.graph.GraphEvidence> evidence(){return java.util.List.of(com.evolution.analysis.graph.GraphEvidence.capture("java.evidence.resolution-slot",nativeSlot.fields()),com.evolution.analysis.graph.GraphEvidence.capture("java.evidence.resolution",nativeManifest.fields()),com.evolution.analysis.graph.GraphEvidence.capture("java.evidence.proof",shape.record().fields()),com.evolution.analysis.graph.GraphEvidence.capture("java.evidence.proof",context.record().fields()));}
    }
    public record NativeOrigin(ClasspathEntry original,ContentDigest slot,Optional<NativeOriginProof> proof){
        public NativeOrigin(ClasspathEntry original,ContentDigest slot){this(original,slot,Optional.empty());}
        public NativeOrigin{Objects.requireNonNull(original);Objects.requireNonNull(slot);Objects.requireNonNull(proof);if(proof.isPresent()&&(!proof.orElseThrow().original.equals(original)||!proof.orElseThrow().slot.equals(slot)))throw new FrontendInputException("frontend.native-origin-proof","Native proof belongs to another artifact or slot");}
        public Object canonicalForm(){return java.util.Map.of("original",original,"slot",slot,"proof",proof.map(NativeOriginProof::canonicalForm));}
    }
    public BinaryInput(ClasspathEntry entry,Path path,Optional<ModuleIdentity> reactorModule){this(entry,path,reactorModule,Optional.empty());}
    public BinaryInput(ClasspathEntry entry, Path path) {
        this(entry, path, Optional.empty());
    }

    public BinaryInput {
        Objects.requireNonNull(entry);
        path = Objects.requireNonNull(path).toAbsolutePath().normalize();
        reactorModule = Objects.requireNonNull(reactorModule);
        nativeOrigin=Objects.requireNonNull(nativeOrigin);
        if(nativeOrigin.isPresent()&&nativeOrigin.orElseThrow().original().kind()!=entry.kind())throw new FrontendInputException("frontend.binary-origin-kind","Native and solver origin kinds differ");
        if(nativeOrigin.isPresent()&&nativeOrigin.orElseThrow().proof().isPresent()&&!nativeOrigin.orElseThrow().proof().orElseThrow().solver.equals(entry))throw new FrontendInputException("frontend.native-origin-proof","Native proof belongs to another solver view");
        if (entry.kind() != ClasspathEntryKind.DEPENDENCY
                && entry.kind() != ClasspathEntryKind.MODULE_OUTPUT) {
            throw new FrontendInputException("frontend.binary-kind", "Binary input must be a dependency or reactor output JAR");
        }
        if ((entry.kind() == ClasspathEntryKind.MODULE_OUTPUT) != reactorModule.isPresent()) {
            throw new FrontendInputException("frontend.binary-origin", "Only reactor output JARs carry a module identity");
        }
    }
    /** Native coordinates identify declarations; the solver entry identifies ordered adapter bytes. */
    public ClasspathEntry originEntry(){return nativeOrigin.map(NativeOrigin::original).orElse(entry);}
    /** Issue an origin capability only from protected binary/context evidence and the exact slot table. */
    public BinaryInput withValidatedOrigin(JavaEvidenceRecord slot,JavaEvidenceAuthority.Verified shape,NativeResolutionCensus census)throws IOException{
        var manifest=census.manifest;var context=census.context;var content=census.content;if(census.jpms||census.reactorSources)throw new IOException("JAVA_NATIVE_CONTEXT_PROVIDER_REQUIRED");
        var origin=nativeOrigin.orElseThrow(()->new IOException("JAVA_NATIVE_ORIGIN_MAPPING_REQUIRED"));var scope=manifest.object("scope");
        var expectedKind=slot.text("origin").equals("PROJECT")?ClasspathEntryKind.MODULE_OUTPUT:ClasspathEntryKind.DEPENDENCY;
        if(slot.text("origin").equals("JDK")||entry.kind()!=expectedKind||!reactorModule.map(ModuleIdentity::value).equals(Optional.ofNullable(slot.fields().get("module")).map(com.evolution.analysis.graph.GraphJson::string)))throw new IOException("JAVA_NATIVE_ORIGIN_PROOF_MISMATCH");
        if(!slot.text("space").equals("CLASS_PATH")||!slot.text("disposition").equals("ACCEPTED")||!shape.record().digest("policy").equals(context.record().digest("policy"))||!context.record().text("subject").equals(scope.get("context")))throw new IOException("JAVA_NATIVE_ORIGIN_PROOF_MISMATCH");
        if(!origin.slot().equals(slot.identity())||!census.slots.contains(slot.identity())||!origin.original().contentDigest().equals(JavaEvidenceRecord.handle(slot.fields().get("original")).digest())||!origin.original().logicalName().equals(slot.text("logicalName"))||!slot.fields().get("scope").equals(scope)||!shape.current()||!shape.identity().equals(slot.digest("proof"))||!shape.record().text("question").equals("java.binary-shape")||!shape.record().text("subject").equals(origin.original().contentDigest().value())||!shape.record().fields().get("scope").equals(scope))throw new IOException("JAVA_NATIVE_ORIGIN_PROOF_MISMATCH");
        ContentDigest view=slot.fields().get("selectedView")!=null?JavaEvidenceRecord.handle(slot.fields().get("selectedView")).digest():slot.text("kind").equals("JAR")?origin.original().contentDigest():null;
        if(view==null||!view.equals(entry.contentDigest()))throw new IOException("JAVA_DERIVED_VIEW_BINDING_REQUIRED");
        var references=new java.util.ArrayList<com.evolution.analysis.acquisition.streaming.ContentHandle>();references.add(JavaEvidenceRecord.handle(slot.fields().get("original")));references.add(JavaEvidenceTables.Table.from(manifest.fields().get("slots")).content());references.add(JavaEvidenceTables.Table.from(manifest.fields().get("sourceMembership")).content());if(slot.fields().get("selectedView")!=null)references.add(JavaEvidenceRecord.handle(slot.fields().get("selectedView")));for(Object file:com.evolution.analysis.graph.GraphJson.array(manifest.fields().get("argfiles")))references.add(JavaEvidenceRecord.handle(file));
        return new BinaryInput(entry,path,reactorModule,Optional.of(new NativeOrigin(origin.original(),origin.slot(),Optional.of(new NativeOriginProof(manifest.identity(),shape,context,origin.original(),origin.slot(),content,references,entry,slot.number("ordinal"),census.slots.size(),slot,manifest)))));
    }
}
