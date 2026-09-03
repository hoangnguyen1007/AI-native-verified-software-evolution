package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.EntityIdentity;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.frontend.*;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Labels were authored before adapter output, independently reviewed, and copied byte-for-byte. */
class FrozenFixtureTest {
    @Test void accountsForAllFrozenDeclarationsAndCallsWithoutUnexpectedFacts() throws Exception {
        var manifest = JsonParser.parseString(resource("/m2/fixture-expectations.json")).getAsJsonObject();
        int declarationsChecked = 0, callsChecked = 0;
        for (var raw : manifest.getAsJsonArray("fixtures")) {
            var fixture = raw.getAsJsonObject();
            String id = fixture.get("id").getAsString(), path = fixture.get("sourcePath").getAsString();
            String source = resource("/m2/fixtures/" + id + "/" + path);
            assertEquals("sha256:" + fixture.get("sourceSha256").getAsString(), ContentDigest.sha256Utf8(source).value());
            var request = TestInputs.request(Map.of(path, source), List.of());
            var result = new JavaParserFrontend().analyze(request);
            var document = request.sources().getFirst().document().identity();
            var labels = new HashMap<String, JsonObject>();
            fixture.getAsJsonArray("typeDeclarations").forEach(d -> labels.put(d.getAsJsonObject().get("id").getAsString(), d.getAsJsonObject()));
            fixture.getAsJsonArray("callableDeclarations").forEach(d -> labels.put(d.getAsJsonObject().get("id").getAsString(), d.getAsJsonObject()));
            var entities = new HashMap<String, Entity>();
            assertEquals(labels.size(),result.declarations().stream().filter(d -> d.derivation().kind()==DerivationKind.DIRECT).map(DeclarationRecord::entity)
                    .filter(e -> e.origin() == EntityOrigin.PROJECT && Set.of(EntityKind.TYPE,EntityKind.METHOD,EntityKind.CONSTRUCTOR).contains(e.kind())).count(),id + ": unexpected declaration");
            for (var entry : labels.entrySet()) {
                String key = canonical(entry.getKey(), labels, document.value());
                var matched = result.declarations().stream().map(DeclarationRecord::entity).filter(e -> e.canonicalName().equals(key)).toList();
                assertEquals(1, matched.size(), entry.getKey() + "\n" + CanonicalJson.write(result));
                var entity = matched.getFirst(); entities.put(entry.getKey(), entity);
                assertEquals(EntityOrigin.PROJECT, entity.origin());
                assertEquals(EntityKind.valueOf(entry.getValue().get("kind").getAsString()), entity.kind());
                assertEquals(span(entry.getValue().getAsJsonObject("span"), document), entity.declaration().orElseThrow());
                declarationsChecked++;
            }
            var byIdentity = new HashMap<EntityIdentity, Entity>(); result.declarations().forEach(d -> byIdentity.put(d.entity().identity(), d.entity()));
            var actualCalls = result.occurrences().stream().filter(o -> Set.of("java.calls", "java.constructor-calls").contains(o.relationship().kind().value())).toList();
            assertEquals(fixture.getAsJsonArray("occurrences").size(), actualCalls.size(), id + ": unexpected/omitted invocation");
            for (var occurrence : fixture.getAsJsonArray("occurrences")) {
                var label = occurrence.getAsJsonObject();
                String labelId = label.get("id").getAsString();
                var expectedSpan = span(label.getAsJsonObject("span"), document);
                String category = label.get("category").getAsString();
                var matches = actualCalls.stream().filter(o -> o.span().equals(expectedSpan) && o.relationship().kind().value().equals(category)).toList();
                assertEquals(1, matches.size(), labelId);
                var actual = matches.getFirst();
                assertEquals(entities.get(label.get("callerDeclaration").getAsString()).identity(), actual.relationship().source(), labelId);
                assertEquals(SemanticStatus.valueOf(label.get("status").getAsString()), actual.status(), labelId + "\n" + CanonicalJson.write(actual));
                assertEquals(0, actual.ordinal());
                assertEquals(label.getAsJsonObject("span").get("sourceText").getAsString(), new OriginalSource(document, source).slice(actual.span()));
                if (actual.status() == SemanticStatus.RESOLVED) {
                    var target = byIdentity.get(((RelationshipTarget.Resolved)actual.relationship().target()).target());
                    var expectedTarget = label.getAsJsonObject("target");
                    if (expectedTarget.has("declaration")) assertEquals(entities.get(expectedTarget.get("declaration").getAsString()), target, labelId);
                    else {
                        assertEquals(expectedTarget.get("canonicalName").getAsString(), target.canonicalName(), labelId);
                        assertEquals(EntityOrigin.valueOf(expectedTarget.get("origin").getAsString()), target.origin());
                        assertTrue(target.declaration().isEmpty());
                    }
                } else {
                    assertInstanceOf(RelationshipTarget.Unresolved.class, actual.relationship().target());
                    assertFalse(actual.uncertainties().isEmpty());
                }
                callsChecked++;
            }
            assertTrue(result.declarations().stream().noneMatch(d -> d.entity().canonicalName().contains("\"work\"")), "no fabricated missing target");
            assertEquals(CanonicalJson.write(result), CanonicalJson.write(new JavaParserFrontend().analyze(request)));
            Path output = Path.of("target/m2-evidence", id + ".json"); Files.createDirectories(output.getParent());
            Files.writeString(output, CanonicalJson.write(result), StandardCharsets.UTF_8);
            Files.writeString(output.resolveSibling(id + "-manifest.json"), CanonicalJson.write(request.manifest()), StandardCharsets.UTF_8);
        }
        assertEquals(27, declarationsChecked); assertEquals(23, callsChecked);
    }
    private static String resource(String path) throws Exception {
        try (var stream = Objects.requireNonNull(FrozenFixtureTest.class.getResourceAsStream(path))) { return new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
    }
    private static SourceSpan span(JsonObject s, com.evolution.analysis.contract.identity.SourceDocumentIdentity document) {
        return new SourceSpan(document, s.get("startLine").getAsInt(), s.get("startColumn").getAsInt(), s.get("endLine").getAsInt(), s.get("endColumn").getAsInt());
    }
    private static String canonical(String id, Map<String, JsonObject> labels, String document) {
        var label = labels.get(id);
        if (label.has("canonicalName") && !label.get("canonicalName").isJsonNull()) return label.get("canonicalName").getAsString();
        Object tuple;
        if (label.has("localToCallable")) tuple = List.of("local-type", tuple(canonical(label.get("localToCallable").getAsString(), labels, document)), document, label.getAsJsonObject("span").get("startOffsetUtf16").getAsInt(), "local", label.get("name").getAsString());
        else if (label.has("memberOfType")) tuple = List.of("member-type", tuple(canonical(label.get("memberOfType").getAsString(), labels, document)), label.get("name").getAsString());
        else {
            var parameters = new ArrayList<Object>();
            label.getAsJsonArray("parameterErasure").forEach(p -> { String name = p.getAsString(); int index = name.lastIndexOf('.'); parameters.add(List.of("declared", List.of("type", List.of(name.substring(0, index).split("\\.")), name.substring(index + 1)))); });
            var owner = tuple(canonical(label.get("ownerType").getAsString(), labels, document));
            tuple = label.get("kind").getAsString().equals("CONSTRUCTOR") ? List.of("constructor", owner, parameters) : List.of("method", owner, label.get("name").getAsString(), parameters);
        }
        return "java:v1:" + new Gson().toJson(tuple);
    }
    private static Object tuple(String canonical) { return JsonParser.parseString(canonical.substring("java:v1:".length())); }
}
