package com.evolution.cli;

import java.io.*;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Structural diagnostics only. Exception messages, paths, SQL and source text
 * may contain secrets and are never copied into CLI output. */
final class FailureDiagnostics {
    private static final Set<String> OPERATIONS=Set.of("GRAPH_PARTITION_STORAGE_UNAVAILABLE","GRAPH_PROJECTION_STORAGE_UNAVAILABLE","GRAPH_PARTITION_AUDIT_UNAVAILABLE","GRAPH_PARTITION_AUDIT_WRITE","GRAPH_PARTITION_AUDIT_READ","GRAPH_PARTITION_AUDIT_CLOSE","ANALYSIS_SPOOL_FAILED","ANALYSIS_SPOOL_CLOSE","ANALYSIS_SPOOL_HASH_MISMATCH","CAS_CORRUPT","GRAPH_PARTITION_CONTENT_CORRUPT","GRAPH_PARTITION_DIGEST_MISMATCH","GRAPH_SNAPSHOT_CONFLICT","WAITING_FOR_DISK","GRAPH_PROJECTION_DISK_PRESSURE","GRAPH_PROJECTION_PAYLOAD_HASH_MISMATCH","GRAPH_PROJECTION_PAYLOAD_TRUNCATED","GRAPH_PROJECTION_PAYLOAD_LOCATOR_INVALID","GRAPH_PROJECTION_PAYLOAD_MISSING");
    private record Item(Throwable failure,String relation) {}
    static String classification(Throwable failure) {
        var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());var pending=new ArrayDeque<Throwable>();pending.add(failure);
        String result=null;boolean sqlite=false;
        while(!pending.isEmpty()&&seen.size()<32) {
            var value=pending.removeFirst();if(!seen.add(value))continue;
            sqlite|=value.getClass().getName().startsWith("org.sqlite.")||value.getMessage()!=null&&OPERATIONS.contains(value.getMessage());
            if(value instanceof CancellationException||value instanceof InterruptedException)return "CANCELLED";
            if(value instanceof OutOfMemoryError)return "MEMORY_LIMIT";
            if(value instanceof AccessDeniedException)result="STORAGE_PERMISSION_DENIED";
            else if(value instanceof NoSuchFileException)result="STORAGE_NOT_FOUND";
            else if(value instanceof SQLException sql) {
                result=sqlite?switch(sql.getErrorCode()&255){case 3,8,23->"STORAGE_PERMISSION_DENIED";case 5,6->"STORAGE_BUSY";case 7->"MEMORY_LIMIT";case 13->"STORAGE_FULL";case 11,26->"STORAGE_CORRUPT";case 14->"STORAGE_OPEN_FAILED";default->result==null?"STORAGE_IO_FAILED":result;}:result==null?"STORAGE_IO_FAILED":result;
                if(sql.getNextException()!=null)pending.addLast(sql.getNextException());
            } else if(value instanceof FileSystemException fs&&full(fs.getReason()))result="STORAGE_FULL";
            else if(value instanceof IOException&&Set.of("WAITING_FOR_DISK","GRAPH_PROJECTION_DISK_PRESSURE").contains(Objects.toString(value.getMessage(),"")))result="STORAGE_FULL";
            else if(value instanceof IOException&&Set.of("GRAPH_PROJECTION_PAYLOAD_HASH_MISMATCH","GRAPH_PROJECTION_PAYLOAD_TRUNCATED","GRAPH_PROJECTION_PAYLOAD_LOCATOR_INVALID","GRAPH_PROJECTION_PAYLOAD_MISSING").contains(Objects.toString(value.getMessage(),"")))result="STORAGE_CORRUPT";
            else if(value instanceof IOException&&result==null)result="STORAGE_IO_FAILED";
            if(value.getCause()!=null)pending.addLast(value.getCause());
        }
        return result;
    }
    private static boolean full(String reason) {if(reason==null)return false;String text=reason.toLowerCase(Locale.ROOT);return text.contains("no space left on device")||text.contains("not enough space on the disk")||text.contains("disk quota exceeded");}
    static Map<String,Object> describe(Throwable failure,String stage,boolean frames) {
        var chain=new ArrayList<Map<String,Object>>();var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        var pending=new ArrayDeque<Item>();pending.add(new Item(failure,"failure"));
        while(!pending.isEmpty()&&chain.size()<16) {
            var item=pending.removeFirst();var value=item.failure();if(!seen.add(value))continue;
            var row=new TreeMap<String,Object>();row.put("relation",item.relation());row.put("type",value.getClass().getName());
            if(value.getMessage()!=null&&OPERATIONS.contains(value.getMessage()))row.put("operation",value.getMessage());
            if(value instanceof SQLException sql){row.put("sqlCode",sql.getErrorCode());if(sql.getSQLState()!=null&&sql.getSQLState().matches("[A-Z0-9]{5}"))row.put("sqlState",sql.getSQLState());if(sql.getNextException()!=null)pending.addLast(new Item(sql.getNextException(),"nextSqlException"));}
            if(frames){var trace=new ArrayList<String>();for(var frame:value.getStackTrace())if(frame.getClassName().startsWith("com.evolution.")||frame.getClassName().startsWith("org.sqlite.")||frame.getClassName().startsWith("java.nio.")){trace.add(frame.getClassName()+"."+frame.getMethodName()+":"+frame.getLineNumber());if(trace.size()==32)break;}row.put("frames",trace);}
            chain.add(row);
            if(value.getCause()!=null)pending.addLast(new Item(value.getCause(),"cause"));
            for(var suppressed:value.getSuppressed()){if(pending.size()>=32)break;pending.addLast(new Item(suppressed,"suppressed"));}
        }
        return Map.of("stage",stage!=null&&stage.matches("[a-z-]{1,40}")?stage:"command","chain",chain,"truncated",!pending.isEmpty());
    }
    static void debug(PrintWriter output,Map<String,Object> diagnostic) {
        output.println("[DEBUG] stage="+diagnostic.get("stage"));
        for(Object raw:(List<?>)diagnostic.get("chain")) {var row=(Map<?,?>)raw;output.println("  "+row.get("relation")+"="+row.get("type")+(row.containsKey("sqlCode")?" sqlCode="+row.get("sqlCode"):""));
            if(row.get("frames") instanceof List<?> frames)for(Object frame:frames)output.println("    "+frame);
        }
    }
    private FailureDiagnostics() {}
}
