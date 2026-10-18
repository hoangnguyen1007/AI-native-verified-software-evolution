package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import static com.evolution.analysis.acquisition.streaming.InventoryEntry.*;

/** Streaming central-directory reconciliation, including ZIP64. No whole central-directory array. */
final class ZipDirectory {
    record Summary(long entries){}
    static Summary index(StreamingContentStore content,SqliteInventoryStore inventory,ContentHandle archive,String census)throws IOException{
        if(archive.size()<22)throw new IOException("ARCHIVE_INVALID_ENVELOPE");long tailOffset=Math.max(0,archive.size()-65557);byte[] tail;
        try(var in=content.range(archive,tailOffset,archive.size()-tailOffset)){tail=in.readAllBytes();}
        var data=ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);int end=-1;
        for(int i=tail.length-22;i>=0;i--)if(data.getInt(i)==0x06054b50&&i+22+u16(data,i+20)==tail.length){end=i;break;}
        if(end<0||u16(data,end+4)!=0||u16(data,end+6)!=0)throw new IOException("ARCHIVE_INVALID_ENVELOPE");
        long count=u16(data,end+10),directorySize=u32(data,end+12),directoryOffset=u32(data,end+16),endOffset=tailOffset+end;
        if(u16(data,end+8)!=count)throw new IOException("ARCHIVE_MULTIDISK_DENIED");
        if(count==65535||directorySize==0xffffffffL||directoryOffset==0xffffffffL){
            if(endOffset<20)throw new IOException("ARCHIVE_ZIP64_INVALID");byte[] locator;try(var in=content.range(archive,endOffset-20,20)){locator=in.readAllBytes();}var l=ByteBuffer.wrap(locator).order(ByteOrder.LITTLE_ENDIAN);
            if(l.getInt(0)!=0x07064b50||l.getInt(4)!=0||l.getInt(16)!=1)throw new IOException("ARCHIVE_ZIP64_INVALID");long offset=l.getLong(8);
            if(offset<0||offset>endOffset-76)throw new IOException("ARCHIVE_ZIP64_INVALID");byte[] record;try(var in=content.range(archive,offset,56)){record=in.readAllBytes();}var z=ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN);
            if(z.getInt(0)!=0x06064b50||z.getLong(4)<44||z.getInt(16)!=0||z.getInt(20)!=0||z.getLong(24)!=z.getLong(32))throw new IOException("ARCHIVE_ZIP64_INVALID");
            count=z.getLong(32);directorySize=z.getLong(40);directoryOffset=z.getLong(48);endOffset=offset;
        }
        if(count<0||directoryOffset<0||directorySize<0||directoryOffset>endOffset||directorySize!=endOffset-directoryOffset)throw new IOException("ARCHIVE_DIRECTORY_INVALID");
        long observed=0;var batch=new ArrayList<SqliteInventoryStore.Observation>(256);
        try(var in=new DataInputStream(content.range(archive,directoryOffset,directorySize))){
            while(observed<count){int signature=Integer.reverseBytes(in.readInt());if(signature!=0x02014b50)throw new IOException("ARCHIVE_DIRECTORY_INVALID");byte[] fixed=in.readNBytes(42);if(fixed.length!=42)throw new EOFException();var f=ByteBuffer.wrap(fixed).order(ByteOrder.LITTLE_ENDIAN);
                int flags=u16(f,4),method=u16(f,6),nameLength=u16(f,24),extraLength=u16(f,26),commentLength=u16(f,28);long crc=u32(f,12),size=u32(f,20),external=u32(f,34);
                byte[] name=in.readNBytes(nameLength),extra=in.readNBytes(extraLength);if(name.length!=nameLength||extra.length!=extraLength)throw new EOFException();in.skipNBytes(commentLength);
                if((flags&1)!=0||method!=0&&method!=8)throw new IOException("ARCHIVE_METHOD_OR_ENCRYPTION_REQUIRED");
                if(size==0xffffffffL){var x=ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN);boolean found=false;while(x.remaining()>=4){int id=Short.toUnsignedInt(x.getShort()),length=Short.toUnsignedInt(x.getShort());if(length>x.remaining())throw new IOException("ARCHIVE_ZIP64_INVALID");if(id==1){if(length<8)throw new IOException("ARCHIVE_ZIP64_INVALID");size=x.getLong();found=true;break;}x.position(x.position()+length);}if(!found||size<0)throw new IOException("ARCHIVE_ZIP64_INVALID");}
                String encoding=(flags&2048)!=0?"UTF-8":"IBM437",decoded;try{decoded=Charset.forName(encoding).newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(name)).toString();}catch(CharacterCodingException invalid){throw new IOException("ARCHIVE_NAME_ENCODING_INVALID");}
                boolean directory=decoded.endsWith("/");if(directory){decoded=decoded.substring(0,decoded.length()-1);name=Arrays.copyOf(name,name.length-1);}
                if(decoded.indexOf('\\')>=0||decoded.matches("^[A-Za-z]:/.*"))throw new IOException("ARCHIVE_UNSAFE_PATH");
                LogicalPath path,lookup;try{path=LogicalPath.of(name);lookup=LogicalPath.utf8(decoded);}catch(IllegalArgumentException invalid){throw new IOException("ARCHIVE_UNSAFE_PATH");}
                if(inventory.observation(census,lookup).isPresent()||batch.stream().anyMatch(o->o.entry().path().equals(lookup)))throw new IOException("ARCHIVE_DUPLICATE_PATH");
                int unix=(int)(external>>>16);Kind kind=directory?Kind.DIRECTORY:(unix&0170000)==0120000?Kind.SYMBOLIC_LINK:Kind.FILE;String mode=kind==Kind.DIRECTORY?"040000":kind==Kind.SYMBOLIC_LINK?"120000":(unix&0111)!=0?"100755":"100644";
                var e=new InventoryEntry(lookup,kind,mode,classify(decoded),Disposition.PENDING,Optional.empty(),Optional.of(path.rawBase64()),Optional.of(encoding),List.of(),Optional.empty());
                batch.add(new SqliteInventoryStore.Observation(e,crc+":"+size+":"+method));observed++;if(batch.size()==256){inventory.entries(census,batch);batch.clear();}
            }if(in.read()!=-1)throw new IOException("ARCHIVE_DIRECTORY_INVALID");
        }finally{if(!batch.isEmpty())inventory.entries(census,batch);}
        return new Summary(observed);
    }
    private static int u16(ByteBuffer b,int offset){return Short.toUnsignedInt(b.getShort(offset));}
    private static long u32(ByteBuffer b,int offset){return Integer.toUnsignedLong(b.getInt(offset));}
}
