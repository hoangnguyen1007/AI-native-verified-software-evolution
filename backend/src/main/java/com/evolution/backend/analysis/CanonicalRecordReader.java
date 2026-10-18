package com.evolution.backend.analysis;

import com.evolution.analysis.contract.common.CanonicalIdentifier;
import java.lang.reflect.*;
import java.math.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;

/** Decode only an embedding-selected record schema; JSON never selects or loads a class.
 * Every public contract constructor runs, and callers verify canonical round-trip bytes. */
public final class CanonicalRecordReader {
    public static <T>T read(Object value,Class<T> type){return type.cast(readType(value,type,0));}
    private static Object readType(Object value,Type target,int depth){
        if(depth>128)throw new IllegalArgumentException("Record nesting exceeded");
        if(target instanceof ParameterizedType p){
            Type raw=p.getRawType();Type[] args=p.getActualTypeArguments();
            if(raw==Optional.class)return value==null?Optional.empty():Optional.of(readType(value,args[0],depth+1));
            if(raw==List.class||raw==Collection.class||raw==Set.class){if(!(value instanceof List<?> list))throw new IllegalArgumentException("Expected list");var values=list.stream().map(v->readType(v,args[0],depth+1)).toList();return raw==Set.class?Collections.unmodifiableSet(new LinkedHashSet<>(values)):values;}
            if(raw==Map.class||raw==SortedMap.class){if(!(value instanceof Map<?,?> map))throw new IllegalArgumentException("Expected map");var result=new LinkedHashMap<Object,Object>();map.forEach((k,v)->result.put(readType(k,args[0],depth+1),readType(v,args[1],depth+1)));return raw==SortedMap.class?Collections.unmodifiableSortedMap(new TreeMap<>(result)):Collections.unmodifiableMap(result);}
            throw new IllegalArgumentException("Unsupported parameterized schema");
        }
        if(!(target instanceof Class<?> type))throw new IllegalArgumentException("Unsupported record schema");
        if(type==Object.class)return value;
        if(value==null)throw new IllegalArgumentException("Unexpected null");
        if(type==String.class){if(value instanceof String)return value;throw new IllegalArgumentException("Expected string");}
        if(type==boolean.class||type==Boolean.class){if(value instanceof Boolean)return value;throw new IllegalArgumentException("Expected boolean");}
        if(type==BigDecimal.class)return new BigDecimal(value.toString());if(type==BigInteger.class)return new BigDecimal(value.toString()).toBigIntegerExact();
        if(type==int.class||type==Integer.class)return new BigDecimal(value.toString()).intValueExact();if(type==long.class||type==Long.class)return new BigDecimal(value.toString()).longValueExact();
        if(type==Instant.class)return Instant.parse((String)value);if(type==URI.class)return URI.create((String)value);
        if(!type.getName().startsWith("com.evolution."))throw new IllegalArgumentException("Record schema not allowed");
        if(type.isEnum())return enumValue(type,(String)value);
        if(type.isSealed()){
            if(!(value instanceof Map<?,?> map))throw new IllegalArgumentException("Expected sealed record");
            var candidates=Arrays.stream(type.getPermittedSubclasses()).filter(Class::isRecord).filter(c->fields(c).equals(map.keySet())).toList();
            if(candidates.size()!=1)throw new IllegalArgumentException("Ambiguous record schema");return readType(value,candidates.getFirst(),depth+1);
        }
        try{
            if(CanonicalIdentifier.class.isAssignableFrom(type))return type.getConstructor(String.class).newInstance(value);
            if(!type.isRecord()||!(value instanceof Map<?,?> map)||!fields(type).equals(map.keySet()))throw new IllegalArgumentException("Unexpected record fields");
            var components=type.getRecordComponents();Class<?>[] parameterTypes=new Class<?>[components.length];Object[] values=new Object[components.length];
            for(int i=0;i<components.length;i++){parameterTypes[i]=components[i].getType();values[i]=readType(map.get(components[i].getName()),components[i].getGenericType(),depth+1);}
            var constructor=type.getDeclaredConstructor(parameterTypes);if(!constructor.trySetAccessible())throw new IllegalArgumentException("Record inaccessible");return constructor.newInstance(values);
        }catch(ReflectiveOperationException invalid){throw new IllegalArgumentException("Record validation failed",invalid);}
    }
    private static Set<String> fields(Class<?> type){var fields=new HashSet<String>();for(var component:type.getRecordComponents())fields.add(component.getName());return fields;}
    @SuppressWarnings({"rawtypes","unchecked"})private static Object enumValue(Class<?> type,String value){return Enum.valueOf((Class)type,value);}
    private CanonicalRecordReader(){}
}
