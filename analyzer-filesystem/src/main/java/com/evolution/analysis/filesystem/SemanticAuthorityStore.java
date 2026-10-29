package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.EvidenceVerifier;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.polyglot.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Explicit operator-owned public-key/method registration and signed claims.
 * Never discovered inside analyzed repositories. No private keys or signer are
 * provisioned here; unknown methods/keys cannot acquire authority from outputs. */
public final class SemanticAuthorityStore {
    private final Path root;private final RepositoryContentStore content;private final SemanticTables tables;private final ContentDigest expectedPolicy;
    public SemanticAuthorityStore(Path root,RepositoryContentStore content,SemanticTables tables,ContentDigest expectedPolicy)throws IOException {this.root=directory(root);this.content=Objects.requireNonNull(content);this.tables=Objects.requireNonNull(tables);this.expectedPolicy=Objects.requireNonNull(expectedPolicy);state();}
    private record State(EvidenceVerifier verifier,Set<ContentDigest> revoked){}
    public ContentDigest policy()throws IOException{return state().verifier().policyIdentity();}
    /** Derive the current policy from the explicitly chosen operator directory,
     * never from a target-written report or saved qualification flag. */
    public static ContentDigest policy(Path root)throws IOException {
        return readState(directory(root)).verifier().policyIdentity();
    }
    private static Path directory(Path root)throws IOException {
        try{return StreamingContentStore.existingDirectory(root);}
        catch(IOException unavailable){throw new IOException("SEMANTIC_AUTHORITY_DIRECTORY",unavailable);}
    }
    public Set<ContentDigest> revocations()throws IOException{return state().revoked();}
    public static ContentDigest revisionKey(LanguageSemanticRequest request,SemanticTables.Table observations){return CanonicalJson.sha256(List.of("semantic-proof-lookup:m8.4-v1","revision",request.identity(),observations.identity()));}
    public static ContentDigest rootKey(SemanticContextCoordinator.Plan plan,SemanticRootCensus.Input input){return CanonicalJson.sha256(List.of("semantic-proof-lookup:m8.4-v1","root",plan.identity(),input.identity()));}
    public Map<String,EvidenceVerifier.VerifiedClaim> revisionClaims(LanguageSemanticRequest request,SemanticTables.Table observations)throws IOException{return claims(revisionKey(request,observations));}
    public Optional<EvidenceVerifier.VerifiedClaim> rootClaim(SemanticContextCoordinator.Plan plan,SemanticRootCensus.Input input)throws IOException {var claims=claims(rootKey(plan,input));if(!claims.keySet().stream().allMatch("root"::equals))throw new IOException("SEMANTIC_AUTHORITY_ROOT_ROLES");return Optional.ofNullable(claims.get("root"));}
    public SemanticPublicationValidation validation(SemanticContextCoordinator.CensusIndex indexes){return new SemanticPublicationValidation(content,tables,this::revisionClaims,this::rootClaim,indexes,()->unchecked(this::revocations),()->unchecked(this::policy));}
    private Map<String,EvidenceVerifier.VerifiedClaim> claims(ContentDigest key)throws IOException {
        try {
            var state=state();Path path=root.resolve("claims").resolve(key.value().substring(7)+".json");StreamingContentStore.safe(path);if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return Map.of();var bundle=read(path,65536);
            fields(bundle,"schema","claims");if(!"semantic-signed-claims:m8.4-v1".equals(bundle.get("schema")))throw new IOException("SEMANTIC_AUTHORITY_CLAIMS_SCHEMA");var result=new TreeMap<String,EvidenceVerifier.VerifiedClaim>();
            for(var entry:GraphJson.object(bundle.get("claims")).entrySet()){
                if(!entry.getKey().matches("census|observations|root|correspondence:sha256:[a-f0-9]{64}"))throw new IOException("SEMANTIC_AUTHORITY_CLAIM_ROLE");var claim=GraphJson.object(entry.getValue());fields(claim,"expected","receipt","controllerSignature","attestation","validatorSignature");var accepted=state.verifier().verifyAccepted(expected(claim.get("expected")),receipt(claim.get("receipt")),signature(claim.get("controllerSignature")),attestation(claim.get("attestation")),signature(claim.get("validatorSignature")),content);
                if(accepted.isEmpty())throw new IOException("SEMANTIC_AUTHORITY_SIGNATURE_OR_METHOD_REJECTED");result.put(entry.getKey(),accepted.orElseThrow());
            }
            // Keep revoked capabilities available for historical/reopened state;
            // consumers apply the current revocation set during every fold.
            return Collections.unmodifiableMap(result);
        }catch(IllegalArgumentException|ArithmeticException|ClassCastException|NullPointerException invalid){throw new IOException("SEMANTIC_AUTHORITY_CLAIMS_INVALID",invalid);}
    }
    private State state()throws IOException {
        var state=readState(root);if(!expectedPolicy.equals(state.verifier().policyIdentity()))throw new IOException("SEMANTIC_AUTHORITY_POLICY_MISMATCH");return state;
    }
    private static State readState(Path root)throws IOException {
        try {
            var config=read(root.resolve("authority.json"),1048576);fields(config,"schema","controllers","validators","methods","revoked");if(!"semantic-authority:m8.4-v1".equals(config.get("schema")))throw new IOException("SEMANTIC_AUTHORITY_SCHEMA");var methods=new ArrayList<EvidenceVerifier.Method>();
            for(var raw:GraphJson.array(config.get("methods"))){var method=GraphJson.object(raw);fields(method,"method","predicate","tool","requiredChecks");methods.add(new EvidenceVerifier.Method(version(method.get("method")),version(method.get("predicate")),digest(method.get("tool")),strings(method.get("requiredChecks"))));}
            var verifier=new EvidenceVerifier(keys(config.get("controllers")),keys(config.get("validators")),methods);var revoked=new TreeSet<ContentDigest>();for(var raw:GraphJson.array(config.get("revoked")))if(!revoked.add(digest(raw)))throw new IOException("SEMANTIC_AUTHORITY_DUPLICATE_REVOCATION");return new State(verifier,Collections.unmodifiableSet(revoked));
        }catch(GeneralSecurityException|IllegalArgumentException|ArithmeticException|ClassCastException invalid){throw new IOException("SEMANTIC_AUTHORITY_CONFIGURATION_INVALID",invalid);}
    }
    private static Map<String,PublicKey> keys(Object raw)throws GeneralSecurityException {var result=new TreeMap<String,PublicKey>();for(var entry:GraphJson.object(raw).entrySet())result.put(entry.getKey(),KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(GraphJson.string(entry.getValue())))));return result;}
    private static EvidenceVerifier.Expected expected(Object raw){var v=GraphJson.object(raw);fields(v,"launch","profile","fence","scope","inputClosure","claim","method","predicate","tool");return new EvidenceVerifier.Expected(digest(v.get("launch")),digest(v.get("profile")),number(v.get("fence")),digest(v.get("scope")),digest(v.get("inputClosure")),handle(v.get("claim")),version(v.get("method")),version(v.get("predicate")),digest(v.get("tool")));}
    private static EvidenceVerifier.Receipt receipt(Object raw){var v=GraphJson.object(raw);fields(v,"schema","attempt","fence","launch","profile","outputManifest","materials","termination");return new EvidenceVerifier.Receipt(text(v.get("schema")),text(v.get("attempt")),number(v.get("fence")),digest(v.get("launch")),digest(v.get("profile")),digest(v.get("outputManifest")),digest(v.get("materials")),digest(v.get("termination")));}
    private static EvidenceVerifier.Attestation attestation(Object raw){var v=GraphJson.object(raw);fields(v,"schema","receipt","claim","expectedScope","inputClosure","tool","method","predicate","passedChecks","denominatorClosed");if(!(v.get("denominatorClosed") instanceof Boolean closed))throw new IllegalArgumentException("BOOLEAN_REQUIRED");return new EvidenceVerifier.Attestation(text(v.get("schema")),digest(v.get("receipt")),digest(v.get("claim")),digest(v.get("expectedScope")),digest(v.get("inputClosure")),digest(v.get("tool")),version(v.get("method")),version(v.get("predicate")),strings(v.get("passedChecks")),closed);}
    private static EvidenceVerifier.SignatureEnvelope signature(Object raw){var v=GraphJson.object(raw);fields(v,"keyId","signatureBase64");return new EvidenceVerifier.SignatureEnvelope(text(v.get("keyId")),text(v.get("signatureBase64")));}
    private static VersionedIdentifier version(Object raw){var v=GraphJson.object(raw);fields(v,"id","version");return new VersionedIdentifier(text(v.get("id")),text(v.get("version")));}
    private static ContentHandle handle(Object raw){var v=GraphJson.object(raw);fields(v,"digest","size");return new ContentHandle(digest(v.get("digest")),number(v.get("size")));}
    private static ContentDigest digest(Object raw){return new ContentDigest(text(raw));}
    private static String text(Object raw){return GraphJson.string(raw);}
    private static List<String> strings(Object raw){return GraphJson.array(raw).stream().map(GraphJson::string).toList();}
    private static long number(Object raw){return ((java.math.BigDecimal)raw).longValueExact();}
    private static void fields(Map<String,Object> value,String... fields){if(!value.keySet().equals(Set.of(fields)))throw new IllegalArgumentException("SEMANTIC_AUTHORITY_RECORD_FIELDS");}
    private static Map<String,Object> read(Path path,int limit)throws IOException {StreamingContentStore.safe(path);if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>limit)throw new IOException("SEMANTIC_AUTHORITY_RECORD_UNAVAILABLE_OR_WORKER_REQUIRED");return GraphJson.object(GraphJson.read(Files.readString(path,StandardCharsets.UTF_8),new GraphJson.Limits(limit,64,limit)));}
    @FunctionalInterface private interface Read<T>{T get()throws IOException;}
    private static <T>T unchecked(Read<T> read){try{return read.get();}catch(IOException failure){throw new UncheckedIOException(failure);}}
}
