package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import java.io.*;
import java.util.*;

/** Passive class-file member correspondence. A constant-pool reference is not
 * promoted to CALLS, dispatch certainty or source-level declaration precision. */
public final class JvmBridgeDescriptors {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("architecture.jvm-descriptors","m8.4-v1");
    public record Member(String owner,String name,String descriptor,String kind,int flags) implements Comparable<Member>{
        public int compareTo(Member other){int n=owner.compareTo(other.owner);if(n==0)n=name.compareTo(other.name);if(n==0)n=descriptor.compareTo(other.descriptor);if(n==0)n=kind.compareTo(other.kind);return n==0?Integer.compare(flags,other.flags):n;}
    }
    public record ClassView(VersionedIdentifier provider,ContentHandle binary,String owner,Optional<String> sourceFile,int major,List<Member> declarations,List<Member> references){public ClassView{declarations=List.copyOf(declarations);references=List.copyOf(references);}}
    private record Pair(int first,int second){}
    public static ClassView read(ContentHandle handle,RepositoryContentStore content,int workerBytes)throws IOException{
        if(handle.size()>workerBytes||workerBytes<1)throw new IOException("BRIDGE_JVM_CLASS_WORKER_REQUIRED");if(!content.verify(handle))throw new IOException("BRIDGE_JVM_CLASS_UNAVAILABLE");byte[] bytes;
        try(var stream=content.open(handle)){bytes=stream.readNBytes(workerBytes);if(bytes.length!=handle.size()||stream.read()!=-1||!ContentDigest.sha256(bytes).equals(handle.digest()))throw new IOException("BRIDGE_JVM_CLASS_BYTES_CHANGED");}
        try(var input=new DataInputStream(new ByteArrayInputStream(bytes))){
            if(input.readInt()!=0xcafebabe)throw new IOException("BRIDGE_JVM_CLASS_MAGIC");input.readUnsignedShort();int major=input.readUnsignedShort();int size=input.readUnsignedShort();if(size<1)throw new IOException("BRIDGE_JVM_CONSTANT_POOL");var pool=new Object[size];var tags=new int[size];
            for(int i=1;i<size;i++){int tag=input.readUnsignedByte();tags[i]=tag;switch(tag){
                case 1->pool[i]=input.readUTF();case 3,4->input.skipNBytes(4);case 5,6->{input.skipNBytes(8);if(++i>=size)throw new IOException("BRIDGE_JVM_CONSTANT_POOL");}
                case 7,8,16,19,20->pool[i]=input.readUnsignedShort();case 9,10,11,12,17,18->pool[i]=new Pair(input.readUnsignedShort(),input.readUnsignedShort());case 15->input.skipNBytes(3);default->throw new IOException("BRIDGE_JVM_CONSTANT_TAG_UNSUPPORTED");
            }}
            input.readUnsignedShort();String owner=className(pool,tags,input.readUnsignedShort());int parent=input.readUnsignedShort();if(parent!=0)className(pool,tags,parent);int interfaces=input.readUnsignedShort();for(int i=0;i<interfaces;i++)className(pool,tags,input.readUnsignedShort());
            var declarations=new TreeSet<Member>();for(String kind:List.of("FIELD","METHOD")){int count=input.readUnsignedShort();for(int i=0;i<count;i++){int flags=input.readUnsignedShort();String name=utf(pool,tags,input.readUnsignedShort()),descriptor=utf(pool,tags,input.readUnsignedShort());declarations.add(new Member(owner,name,descriptor,kind,flags));attributes(input,pool,tags,false);}}
            var source=attributes(input,pool,tags,true);if(input.read()!=-1)throw new IOException("BRIDGE_JVM_CLASS_TRAILING_BYTES");
            var references=new TreeSet<Member>();for(int i=1;i<size;i++)if(tags[i]==9||tags[i]==10||tags[i]==11){var reference=(Pair)pool[i];String target=className(pool,tags,reference.first());if(reference.second()<1||reference.second()>=size||tags[reference.second()]!=12)throw new IOException("BRIDGE_JVM_NAME_AND_TYPE");var signature=(Pair)pool[reference.second()];references.add(new Member(target,utf(pool,tags,signature.first()),utf(pool,tags,signature.second()),tags[i]==9?"FIELD":"METHOD",0));}
            return new ClassView(PROVIDER,handle,owner,source,major,List.copyOf(declarations),List.copyOf(references));
        }catch(EOFException|UTFDataFormatException truncated){throw new IOException("BRIDGE_JVM_CLASS_MALFORMED",truncated);}catch(ClassCastException|IndexOutOfBoundsException invalid){throw new IOException("BRIDGE_JVM_CLASS_MALFORMED",invalid);}
    }
    public static boolean matches(Member reference,Member declaration){return reference.owner().equals(declaration.owner())&&reference.name().equals(declaration.name())&&reference.descriptor().equals(declaration.descriptor())&&reference.kind().equals(declaration.kind());}
    private static Optional<String> attributes(DataInputStream input,Object[] pool,int[] tags,boolean sourceAllowed)throws IOException{
        int count=input.readUnsignedShort();String source=null;for(int i=0;i<count;i++){String name=utf(pool,tags,input.readUnsignedShort());long size=Integer.toUnsignedLong(input.readInt());if(sourceAllowed&&name.equals("SourceFile")){if(source!=null||size!=2)throw new IOException("BRIDGE_JVM_SOURCE_FILE_ATTRIBUTE");source=utf(pool,tags,input.readUnsignedShort());}else input.skipNBytes(size);}return Optional.ofNullable(source);
    }
    private static String className(Object[] pool,int[] tags,int index)throws IOException{if(index<1||index>=pool.length||tags[index]!=7)throw new IOException("BRIDGE_JVM_CLASS_INDEX");return utf(pool,tags,(Integer)pool[index]);}
    private static String utf(Object[] pool,int[] tags,int index)throws IOException{if(index<1||index>=pool.length||tags[index]!=1)throw new IOException("BRIDGE_JVM_UTF_INDEX");return (String)pool[index];}
    private JvmBridgeDescriptors(){}
}
