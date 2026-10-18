package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.io.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.zip.*;
import static com.evolution.analysis.acquisition.streaming.InventoryEntry.*;

/** Streaming ZIP intake without materializing any entry as a host path or invoking an archive program.
 * Unsafe/duplicate names withhold the root seal; the original archive remains immutable evidence. */
public final class ArchiveSnapshotAcquirer {
    private final StreamingContentStore content;private final SqliteInventoryStore inventory;
    public ArchiveSnapshotAcquirer(StreamingContentStore content,SqliteInventoryStore inventory){this.content=content;this.inventory=inventory;}
    public StreamingRepositoryAcquirer.Receipt acquire(ContentHandle archive,String revision,StreamingAcquisitionRequest request,BooleanSupplier cancelled)throws IOException{
        var job=inventory.create(revision,CanonicalJson.sha256(List.of("archive.zip:m8.4-v2",archive,request.identity())),Optional.of(archive));
        if(job.state()==JobJournal.State.COMPLETE)return inventory.verifySeal(revision,cancelled)?new StreamingRepositoryAcquirer.Receipt(revision,job.state(),inventory.manifest(revision),""):new StreamingRepositoryAcquirer.Receipt(revision,JobJournal.State.WAITING_FOR_INPUT,Optional.empty(),"SEALED_CONTENT_UNAVAILABLE");
        job=inventory.transition(revision,job.revision(),JobJournal.State.RUNNING,"",Optional.of(archive));
        try{
            if(!content.verify(archive))throw new IOException("ARCHIVE_CHECKSUM_INVALID");
            if(inventory.manifest(revision).isEmpty()){
                // A run-scoped entry census in SQLite detects duplicate names without a heap-sized set.
                String census=revision+".zip-census."+job.revision();
                var summary=ZipDirectory.index(content,inventory,archive,census);long observed=0;
                try(var raw=content.open(archive);var zip=new ZipInputStream(raw,java.nio.charset.Charset.forName("IBM437"))){
                    ZipEntry entry;while((entry=zip.getNextEntry())!=null){
                        if(cancelled.getAsBoolean())throw new CancellationException();String name=entry.getName();if(entry.isDirectory()&&name.endsWith("/"))name=name.substring(0,name.length()-1);
                        LogicalPath lookup;try{lookup=LogicalPath.utf8(name);}catch(IllegalArgumentException invalid){throw new IOException("ARCHIVE_UNSAFE_PATH");}
                        var declared=inventory.observation(census,lookup).orElseThrow(()->new IOException("ARCHIVE_LOCAL_DIRECTORY_MISMATCH"));var mark=declared.entry();LogicalPath path=new LogicalPath(mark.target().orElseThrow());
                        if(declared.stamp().startsWith("READ:"))throw new IOException("ARCHIVE_DUPLICATE_PATH");
                        Optional<ContentHandle> bytes=entry.isDirectory()?Optional.empty():Optional.of(content.put(zip,cancelled));
                        // ZipInputStream verifies CRC/declared size when the entry reaches EOF.
                        zip.closeEntry();if(!declared.stamp().equals(entry.getCrc()+":"+entry.getSize()+":"+entry.getMethod()))throw new IOException("ARCHIVE_LOCAL_DIRECTORY_MISMATCH");var status=request.excludes(name)?Disposition.EXCLUDED:Disposition.ACQUIRED;
                        Optional<String> target=Optional.empty(),problem=Optional.empty();if(mark.kind()==Kind.SYMBOLIC_LINK){if(bytes.orElseThrow().size()>1_048_576)problem=Optional.of("LINK_METADATA_OVERSIZE");else try(var in=content.open(bytes.orElseThrow())){
                            try{target=Optional.of(java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(in.readAllBytes())).toString());problem=Optional.of("LINK_CLOSURE_REQUIRED");}
                            catch(java.nio.charset.CharacterCodingException invalid){problem=Optional.of("LINK_TARGET_ENCODING_REQUIRED");}
                        }}
                        var actual=new InventoryEntry(path,mark.kind(),mark.mode(),mark.population(),status==Disposition.EXCLUDED?status:problem.isPresent()?Disposition.GAP:status,bytes,target,mark.encoding(),List.of(),problem);
                        var previous=inventory.observation(revision,path);if(previous.isPresent()&&!previous.orElseThrow().entry().equals(actual))throw new IOException("ARCHIVE_REPLAY_MISMATCH");
                        inventory.entries(revision,List.of(new SqliteInventoryStore.Observation(actual,archive.digest().value())));inventory.entries(census,List.of(new SqliteInventoryStore.Observation(mark,"READ:"+declared.stamp())));observed++;
                    }
                }
                if(observed!=summary.entries())throw new IOException("ARCHIVE_DENOMINATOR_OPEN");
                InventoryLinkResolver.resolve(inventory,revision);
            }
            job=inventory.transition(revision,job.revision(),JobJournal.State.RUNNING,"FRONTIER_CLOSED",Optional.of(archive));var manifest=inventory.seal(revision,request.repository(),request.identity());job=inventory.transition(revision,job.revision(),JobJournal.State.COMPLETE,"",Optional.of(archive));return new StreamingRepositoryAcquirer.Receipt(revision,job.state(),Optional.of(manifest),"");
        }catch(IOException|CancellationException invalid){String reason=invalid instanceof CancellationException?"CANCELLED_CHECKPOINT":invalid.getMessage();if(reason==null||!reason.matches("[A-Z_]+"))reason="ARCHIVE_INVALID_OR_INTERRUPTED";
            JobJournal.State state=reason.contains("DISK")?JobJournal.State.WAITING_FOR_RESOURCES:JobJournal.State.WAITING_FOR_INPUT;try{inventory.transition(revision,job.revision(),state,reason,Optional.of(archive));}catch(IOException cannotWrite){ }return new StreamingRepositoryAcquirer.Receipt(revision,state,Optional.empty(),reason);}
    }
}
