package com.evolution.backend.query;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.polyglot.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real pinned Pyright on first-party source, without application execution. */
class PythonBridgeNativeIT {
 @TempDir Path directory;
 @Test void builtinTypesUseCapturedNativeDeclarationsWithoutDroppingAnnotationCensus()throws Exception{
  var files=Map.of("worker/value.py","def identity(value: str) -> str:\n    return value\ndef use(value: str) -> str:\n    return identity(value)\n","worker/shadow.py","class str: pass\ndef local(value: str) -> str:\n    return value\n");var content=new StreamingContentStore(directory.resolve("cas"));var tables=new SemanticTables(content,4_000_000);var inventory=ContentDigest.sha256Utf8(files.toString());var context=EvidenceContext.forSnapshot(SnapshotIdentity.from(RepositoryIdentity.fromCanonicalCoordinate("urn:fixture:python-native-types"),inventory));var sdk=new AuthoredBridgeLanguages(directory.resolve("native"),content,tables,files);
  var nativeResult=sdk.capture("python","worker",files,context,inventory,ContentDigest.sha256Utf8("unqualified fixture policy"));var fields=new ArrayList<Map<String,Object>>();try(var rows=tables.open(nativeResult.capture().observations(),()->false)){SemanticTables.Row row;while((row=rows.next())!=null)if(row.key().startsWith("worker/value.py:"))fields.add(row.fields());}
  var types=fields.stream().filter(f->List.of("parameter-annotation","return-annotation").contains(f.get("role"))).toList();assertEquals(4,types.size());assertTrue(types.stream().allMatch(f->"RESOLVED".equals(f.get("status"))),"Native builtin annotation declarations must resolve");
  assertTrue(types.stream().allMatch(f->GraphJson.array(f.get("symbols")).stream().map(GraphJson::object).anyMatch(symbol->"str".equals(symbol.get("name"))&&"PLATFORM".equals(symbol.get("origin"))&&symbol.get("span")!=null)));
  assertEquals(0,fields.stream().filter(f->"UNRESOLVED".equals(f.get("status"))).count());
  var builtinUses=fields.stream().filter(f->GraphJson.array(f.getOrDefault("symbols",List.of())).stream().map(GraphJson::object).anyMatch(symbol->"str".equals(symbol.get("name")))).toList();
  assertEquals(4,builtinUses.stream().filter(f->"reference".equals(f.get("role"))).count());assertEquals(4,builtinUses.stream().filter(f->"callable-reference".equals(f.get("role"))&&"RESOLVED".equals(f.get("status"))).count());
  var builtinSpans=builtinUses.stream().map(f->f.get("span")).collect(java.util.stream.Collectors.toSet());
  assertEquals(4,fields.stream().filter(f->"value-access".equals(f.get("role"))&&builtinSpans.contains(f.get("span"))&&"NATIVE_BINDING_IS_NOT_STATE".equals(f.get("reason"))&&"NOT_APPLICABLE".equals(f.get("status"))).count());
  try(var index=SqliteSemanticCensusIndex.temporary(directory,4_000_000)){new LanguageBridgeImport(content,tables,4_000_000).read("python",nativeResult.request(),nativeResult.capture(),index,()->false);}
  try(var rows=tables.open(nativeResult.capture().observations(),()->false)){int shadowed=0;SemanticTables.Row row;while((row=rows.next())!=null)if(row.key().startsWith("worker/shadow.py:")&&List.of("parameter-annotation","return-annotation").contains(row.fields().get("role"))){shadowed++;assertEquals("RESOLVED",row.fields().get("status"));assertTrue(GraphJson.array(row.fields().get("symbols")).stream().map(GraphJson::object).anyMatch(symbol->"str".equals(symbol.get("name"))&&"PROJECT".equals(symbol.get("origin"))));}assertEquals(2,shadowed);}
  // Alter only this test's captured support copy, never the installed SDK.
  Path work=directory.resolve("native"),request=work.resolve("python-worker-main-1-request.json");var input=GraphJson.object(GraphJson.read(Files.readString(request),new GraphJson.Limits(4_000_000,64,4_000_000)));var builtin=GraphJson.array(input.get("files")).stream().map(GraphJson::object).filter(file->"resolutionOnly".equals(file.get("role"))).findFirst().orElseThrow();String changed=GraphJson.string(builtin.get("text"))+"\n# wrong SDK bytes\n";builtin.put("text",changed);builtin.put("sha256",ContentDigest.sha256Utf8(changed).value());Files.writeString(work.resolve("sources").resolve(GraphJson.string(builtin.get("path"))),changed);Path negative=work.resolve("wrong-sdk-request.json");Files.writeString(negative,com.evolution.analysis.contract.serialization.CanonicalJson.write(input));
  Path errors=work.resolve("wrong-sdk.stderr");var process=new ProcessBuilder(System.getProperty("bridge.python"),"-I","-B",Path.of("../analyzer-execution/src/main/resources/polyglot/python_engine.py").toAbsolutePath().toString(),"--request",negative.toString(),"--node",System.getProperty("bridge.node"),"--sdk",Path.of(System.getProperty("bridge.languages")).resolve("pyright").toString(),"--python",System.getProperty("bridge.python")).redirectError(errors.toFile()).redirectOutput(work.resolve("wrong-sdk.ndjson").toFile()).start();assertTrue(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));assertNotEquals(0,process.exitValue());assertTrue(Files.readString(errors).contains("PYRIGHT_STDLIB_BYTES_OR_SCOPE_MISMATCH"));
 }
}
