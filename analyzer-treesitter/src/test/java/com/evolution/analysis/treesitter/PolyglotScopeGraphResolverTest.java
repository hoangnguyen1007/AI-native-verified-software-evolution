package com.evolution.analysis.treesitter;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.treesitter.PolyglotScopeGraphResolver.EdgeKind;
import com.evolution.analysis.treesitter.PolyglotScopeGraphResolver.ResolvedEdge;
import com.evolution.analysis.treesitter.PolyglotScopeGraphResolver.ScopeGraph;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolyglotScopeGraphResolverTest {

    private static Map<String, byte[]> utf8Files(Map<String, String> textByPath) {
        var out = new LinkedHashMap<String, byte[]>();
        for (var e : textByPath.entrySet()) {
            out.put(e.getKey(), e.getValue().getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }

    @Test
    void pythonRelativeImportsInitReExportsMultiInheritanceAndCalls() throws Exception {
        var files = new LinkedHashMap<String, String>();
        files.put("pyproject.toml", """
                [project]
                name = "acme-core"
                [tool.setuptools.packages.find]
                where = ["src"]
                """);
        files.put("src/acme/__init__.py", """
                from .models import BaseEntity, AuditMixin
                from .service import calculate_total as compute_sum
                """);
        files.put("src/acme/models/__init__.py", """
                from .base import BaseEntity
                from .audit import AuditMixin
                """);
        files.put("src/acme/models/base.py", """
                class BaseEntity:
                    def validate(self):
                        return True
                """);
        files.put("src/acme/models/audit.py", """
                class AuditMixin:
                    def record_audit(self):
                        return "ok"
                """);
        files.put("src/acme/service.py", """
                def calculate_total(amount):
                    return amount * 2
                """);
        files.put("src/acme/app/handler.py", """
                from ..models import BaseEntity, AuditMixin
                from acme import compute_sum

                class Invoice(BaseEntity, AuditMixin):
                    def finalize(self):
                        self.validate()
                        self.record_audit()
                        return compute_sum(42)
                """);

        var frontend = new TreeSitterSourceFrontend();
        ScopeGraph graph = frontend.analyzeWorkspace(utf8Files(files), () -> false);

        // Passive pyproject.toml + __init__.py manifest discovery
        assertEquals(1, graph.manifests().pythonProjects().size());
        assertEquals("acme-core", graph.manifests().pythonProjects().getFirst().projectName());
        assertTrue(graph.manifests().pythonInitPackages().contains("src/acme"));
        assertTrue(graph.manifests().pythonInitPackages().contains("src/acme/models"));

        // Phase 1 + Phase 2a export table resolved through __init__.py barrels
        assertEquals("src/acme/models/base.py",
                graph.exportTable().lookup("src/acme/models/__init__.py", "BaseEntity").orElseThrow().modulePath());
        assertEquals("src/acme/service.py",
                graph.exportTable().lookup("src/acme/__init__.py", "compute_sum").orElseThrow().modulePath());

        // Cross-file IMPORTS edges through relative + __init__.py re-exports
        assertTrue(graph.hasEdge(EdgeKind.IMPORTS, "src/acme/app/handler.py",
                "src/acme/models/base.py", "BaseEntity"), () -> "Missing BaseEntity import: " + graph.edges());
        assertTrue(graph.hasEdge(EdgeKind.IMPORTS, "src/acme/app/handler.py",
                "src/acme/models/audit.py", "AuditMixin"), () -> "Missing AuditMixin import: " + graph.edges());
        assertTrue(graph.hasEdge(EdgeKind.IMPORTS, "src/acme/app/handler.py",
                "src/acme/service.py", "calculate_total"), () -> "Missing aliased re-export import: " + graph.edges());

        // Multi-inheritance INHERITS edges
        assertTrue(graph.hasEdge(EdgeKind.INHERITS, "src/acme/app/handler.py",
                "src/acme/models/base.py", "BaseEntity"), () -> "Missing BaseEntity INHERITS: " + graph.edges());
        assertTrue(graph.hasEdge(EdgeKind.INHERITS, "src/acme/app/handler.py",
                "src/acme/models/audit.py", "AuditMixin"), () -> "Missing AuditMixin INHERITS: " + graph.edges());

        // Cross-file CALLS_SYMBOL edges (inherited methods + aliased re-exported function)
        assertTrue(graph.hasEdge(EdgeKind.CALLS_SYMBOL, "src/acme/app/handler.py",
                "src/acme/models/base.py", "BaseEntity.validate"), () -> "Missing inherited validate call: " + graph.edges());
        assertTrue(graph.hasEdge(EdgeKind.CALLS_SYMBOL, "src/acme/app/handler.py",
                "src/acme/models/audit.py", "AuditMixin.record_audit"), () -> "Missing inherited audit call: " + graph.edges());
        assertTrue(graph.hasEdge(EdgeKind.CALLS_SYMBOL, "src/acme/app/handler.py",
                "src/acme/service.py", "calculate_total"), () -> "Missing compute_sum call: " + graph.edges());

        // Every edge tagged with POLYGLOT_IMPORT_SCOPED and non-COMPILER trust tier (I4)
        assertFalse(graph.edges().isEmpty());
        for (ResolvedEdge edge : graph.edges()) {
            assertEquals(PolyglotScopeGraphResolver.PROVENANCE_POLYGLOT_IMPORT_SCOPED, edge.provenance());
            assertEquals(TrustTier.SOURCE_SYNTAX, edge.trustTier());
            assertNotEquals(TrustTier.COMPILER, edge.trustTier());
        }
        assertTrue(graph.capabilityGaps().isEmpty(), () -> "Unexpected gaps: " + graph.capabilityGaps());
    }

    @Test
    void typescriptTsconfigAliasBarrelReExportExtendsImplementsAndCalls() throws Exception {
        var files = new LinkedHashMap<String, String>();
        files.put("tsconfig.json", """
                {
                  // JSONC comment & trailing comma must parse cleanly
                  "compilerOptions": {
                    "baseUrl": ".",
                    "paths": {
                      "@core/*": ["src/core/*"],
                    },
                  },
                }
                """);
        files.put("package.json", """
                {
                  "name": "@acme/web",
                  "version": "1.0.0"
                }
                """);
        files.put("src/core/base.ts", """
                export interface Auditable {
                  audit(): string;
                }
                export class BaseWorker {
                  start(): void {}
                }
                """);
        files.put("src/core/format.ts", """
                export function formatLabel(raw: string): string {
                  return raw.trim();
                }
                """);
        files.put("src/core/index.ts", """
                export { BaseWorker, Auditable } from './base';
                export { formatLabel as normalizeLabel } from './format';
                """);
        files.put("src/features/runner.ts", """
                import { BaseWorker, Auditable, normalizeLabel } from '@core/index';

                export class JobRunner extends BaseWorker implements Auditable {
                  audit(): string {
                    return normalizeLabel("job");
                  }
                  run(): void {
                    this.start();
                  }
                }
                """);
        files.put("src/main.ts", """
                import { JobRunner } from './features/runner';

                export function bootstrap(): void {
                  const runner = new JobRunner();
                  runner.run();
                }
                """);

        ScopeGraph graph = new TreeSitterSourceFrontend().analyzeWorkspace(utf8Files(files), () -> false);

        assertFalse(graph.manifests().tsAliases().isEmpty());
        assertEquals("src/core/base.ts",
                graph.exportTable().lookup("src/core/index.ts", "BaseWorker").orElseThrow().modulePath());
        assertEquals("src/core/format.ts",
                graph.exportTable().lookup("src/core/index.ts", "normalizeLabel").orElseThrow().modulePath());

        // IMPORTS resolved through tsconfig @core/* alias + index.ts barrel re-export
        assertTrue(graph.hasEdge(EdgeKind.IMPORTS, "src/features/runner.ts",
                "src/core/base.ts", "BaseWorker"));
        assertTrue(graph.hasEdge(EdgeKind.IMPORTS, "src/features/runner.ts",
                "src/core/base.ts", "Auditable"));
        assertTrue(graph.hasEdge(EdgeKind.IMPORTS, "src/features/runner.ts",
                "src/core/format.ts", "formatLabel"));

        // INHERITS (extends + implements)
        assertTrue(graph.hasEdge(EdgeKind.INHERITS, "src/features/runner.ts",
                "src/core/base.ts", "BaseWorker"));
        assertTrue(graph.hasEdge(EdgeKind.INHERITS, "src/features/runner.ts",
                "src/core/base.ts", "Auditable"));

        // CALLS_SYMBOL across files (re-exported function, inherited method, constructor, typed receiver method)
        assertTrue(graph.hasEdge(EdgeKind.CALLS_SYMBOL, "src/features/runner.ts",
                "src/core/format.ts", "formatLabel"));
        assertTrue(graph.hasEdge(EdgeKind.CALLS_SYMBOL, "src/features/runner.ts",
                "src/core/base.ts", "BaseWorker.start"));
        assertTrue(graph.hasEdge(EdgeKind.CALLS_SYMBOL, "src/main.ts",
                "src/features/runner.ts", "JobRunner"));
        assertTrue(graph.hasEdge(EdgeKind.CALLS_SYMBOL, "src/main.ts",
                "src/features/runner.ts", "JobRunner.run"));

        assertTrue(graph.edges().stream()
                .allMatch(e -> e.provenance().equals(PolyglotScopeGraphResolver.PROVENANCE_POLYGLOT_IMPORT_SCOPED)
                        && e.trustTier() == TrustTier.SOURCE_SYNTAX));
    }

    @Test
    void goModPackageImportsStructEmbeddingInterfaceImplementationAndCalls() throws Exception {
        var files = new LinkedHashMap<String, String>();
        files.put("go.mod", """
                module example.com/acme/engine

                go 1.23
                """);
        files.put("internal/base/base.go", """
                package base

                type Executor interface {
                    Execute() string
                }

                type BaseComponent struct{}

                func (b *BaseComponent) Init() string {
                    return "ready"
                }

                func BuildTag(name string) string {
                    return "tag:" + name
                }
                """);
        files.put("internal/worker/worker.go", """
                package worker

                import "example.com/acme/engine/internal/base"

                type TaskWorker struct {
                    base.BaseComponent
                }

                func (w *TaskWorker) Execute() string {
                    w.Init()
                    return base.BuildTag("task")
                }

                func NewTaskWorker() *TaskWorker {
                    return &TaskWorker{}
                }
                """);
        files.put("cmd/app/main.go", """
                package main

                import "example.com/acme/engine/internal/worker"

                func main() {
                    w := worker.NewTaskWorker()
                    w.Execute()
                }
                """);

        ScopeGraph graph = new TreeSitterSourceFrontend().analyzeWorkspace(utf8Files(files), () -> false);

        assertEquals(1, graph.manifests().goModules().size());
        assertEquals("example.com/acme/engine", graph.manifests().goModules().getFirst().modulePath());

        // IMPORTS via go.mod package path
        assertTrue(graph.hasEdge(EdgeKind.IMPORTS, "internal/worker/worker.go",
                "internal/base/base.go", "<module>"));
        assertTrue(graph.hasEdge(EdgeKind.IMPORTS, "cmd/app/main.go",
                "internal/worker/worker.go", "<module>"));

        // INHERITS: struct embedding (BaseComponent) + import-scoped structural interface impl (Executor)
        assertTrue(graph.hasEdge(EdgeKind.INHERITS, "internal/worker/worker.go",
                "internal/base/base.go", "BaseComponent"), () -> "Missing struct embed: " + graph.edges());
        assertTrue(graph.hasEdge(EdgeKind.INHERITS, "internal/worker/worker.go",
                "internal/base/base.go", "Executor"), () -> "Missing interface impl: " + graph.edges());

        // CALLS_SYMBOL: embedded method, package function, constructor idiom + typed receiver call
        assertTrue(graph.hasEdge(EdgeKind.CALLS_SYMBOL, "internal/worker/worker.go",
                "internal/base/base.go", "BaseComponent.Init"), () -> "Missing embedded Init call: " + graph.edges());
        assertTrue(graph.hasEdge(EdgeKind.CALLS_SYMBOL, "internal/worker/worker.go",
                "internal/base/base.go", "BuildTag"), () -> "Missing package BuildTag call: " + graph.edges());
        assertTrue(graph.hasEdge(EdgeKind.CALLS_SYMBOL, "cmd/app/main.go",
                "internal/worker/worker.go", "NewTaskWorker"), () -> "Missing NewTaskWorker call: " + graph.edges());
        assertTrue(graph.hasEdge(EdgeKind.CALLS_SYMBOL, "cmd/app/main.go",
                "internal/worker/worker.go", "TaskWorker.Execute"), () -> "Missing Execute call: " + graph.edges());
    }

    @Test
    void rustCargoWorkspacePubUseReExportTraitImplAndCalls() throws Exception {
        var files = new LinkedHashMap<String, String>();
        files.put("Cargo.toml", """
                [workspace]
                members = ["crates/core", "crates/app"]
                """);
        files.put("crates/core/Cargo.toml", """
                [package]
                name = "acme-core"
                version = "0.1.0"
                """);
        files.put("crates/core/src/lib.rs", """
                pub mod traits;
                pub mod engine;

                pub use self::traits::Runnable;
                pub use self::engine::ComputeEngine as PublicEngine;
                """);
        files.put("crates/core/src/traits.rs", """
                pub trait Runnable {
                    fn execute(&self) -> i32;
                }
                """);
        files.put("crates/core/src/engine.rs", """
                use crate::traits::Runnable;

                pub struct ComputeEngine;

                impl ComputeEngine {
                    pub fn new() -> Self {
                        ComputeEngine
                    }
                }

                impl Runnable for ComputeEngine {
                    fn execute(&self) -> i32 {
                        7
                    }
                }
                """);
        files.put("crates/app/Cargo.toml", """
                [package]
                name = "acme-app"
                version = "0.1.0"
                [dependencies]
                acme-core = { path = "../core" }
                """);
        files.put("crates/app/src/main.rs", """
                use acme_core::PublicEngine;

                fn main() {
                    let eng = PublicEngine::new();
                    eng.execute();
                }
                """);

        ScopeGraph graph = new TreeSitterSourceFrontend().analyzeWorkspace(utf8Files(files), () -> false);

        // Passive Cargo.toml discovery (workspace + normalized hyphen->underscore crate names)
        assertEquals(3, graph.manifests().cargoCrates().size());
        assertTrue(graph.manifests().cargoCrates().stream()
                .anyMatch(c -> c.workspaceMembers().contains("crates/core") && c.workspaceMembers().contains("crates/app")));
        assertTrue(graph.manifests().cargoCrates().stream()
                .anyMatch(c -> c.crateName().equals("acme_core")));

        // pub use re-export resolution
        assertEquals("crates/core/src/engine.rs",
                graph.exportTable().lookup("crates/core/src/lib.rs", "PublicEngine").orElseThrow().modulePath());

        // Cross-crate IMPORTS via pub use
        assertTrue(graph.hasEdge(EdgeKind.IMPORTS, "crates/app/src/main.rs",
                "crates/core/src/engine.rs", "ComputeEngine"), () -> "Missing cross-crate import: " + graph.edges());

        // Trait implementation INHERITS
        assertTrue(graph.hasEdge(EdgeKind.INHERITS, "crates/core/src/engine.rs",
                "crates/core/src/traits.rs", "Runnable"), () -> "Missing trait impl INHERITS: " + graph.edges());

        // Cross-crate CALLS_SYMBOL (associated constructor + trait method on typed receiver)
        assertTrue(graph.hasEdge(EdgeKind.CALLS_SYMBOL, "crates/app/src/main.rs",
                "crates/core/src/engine.rs", "ComputeEngine.new"), () -> "Missing ComputeEngine.new call: " + graph.edges());
        assertTrue(graph.hasEdge(EdgeKind.CALLS_SYMBOL, "crates/app/src/main.rs",
                "crates/core/src/engine.rs", "ComputeEngine.execute"), () -> "Missing execute call: " + graph.edges());
    }

    @Test
    void negativeControlAmbiguousDuckTypedAndDynamicCallsEmitCapabilityGapWithoutGuessing() throws Exception {
        var files = new LinkedHashMap<String, String>();
        files.put("pkg/__init__.py", "");
        files.put("pkg/email_sender.py", """
                class EmailSender:
                    def dispatch(self, payload):
                        return payload
                """);
        files.put("pkg/sms_sender.py", """
                class SmsSender:
                    def dispatch(self, payload):
                        return payload
                """);
        files.put("pkg/router.py", """
                def route_message(sender, payload, dynamic_name):
                    sender.dispatch(payload)
                    getattr(sender, dynamic_name)(payload)
                """);

        ScopeGraph graph = new TreeSitterSourceFrontend().analyzeWorkspace(utf8Files(files), () -> false);

        // Must NOT invent any CALLS_SYMBOL edge for either the duck-typed or dynamic call
        assertTrue(graph.edgesOfKind(EdgeKind.CALLS_SYMBOL).isEmpty(),
                () -> "Ambiguous/dynamic calls must never emit guessed CALLS_SYMBOL edges: " + graph.edges());

        // Must emit explicit CapabilityGapRecord("AMBIGUOUS_POLYGLOT_CALL") entries (I3/I4)
        List<CapabilityGapRecord> gaps = graph.capabilityGaps();
        assertEquals(2, gaps.size(), () -> "Expected 2 ambiguity gaps, got: " + gaps);
        assertTrue(gaps.stream().allMatch(g -> g.reasonCode().equals("AMBIGUOUS_POLYGLOT_CALL")));
        assertTrue(gaps.stream().anyMatch(g -> g.limitations().contains("detail=duck-typed-multiple-candidates")
                && g.limitations().contains("candidate=pkg/email_sender.py::EmailSender.dispatch")
                && g.limitations().contains("candidate=pkg/sms_sender.py::SmsSender.dispatch")));
        assertTrue(gaps.stream().anyMatch(g -> g.limitations().contains("detail=unresolved-dynamic-target")));
    }

    @Test
    void deterministicBitIdenticalReplayAcrossPermutedInputOrdersAndSinglePassFactParity() throws Exception {
        var baseFiles = new LinkedHashMap<String, String>();
        baseFiles.put("tsconfig.json", "{\"compilerOptions\":{\"baseUrl\":\".\",\"paths\":{\"@app/*\":[\"src/*\"]}}}");
        baseFiles.put("src/a.ts", "export class Alpha { ping(): void {} }\n");
        baseFiles.put("src/b.ts", "import { Alpha } from '@app/a';\nexport class Beta extends Alpha { pong(): void { this.ping(); } }\n");
        baseFiles.put("src/c.ts", "import { Beta } from './b';\nexport function run() { const b = new Beta(); b.pong(); }\n");
        baseFiles.put("py/mod_a.py", "class A:\n    def op(self): pass\n");
        baseFiles.put("py/mod_b.py", "class B:\n    def op(self): pass\n");
        baseFiles.put("py/caller.py", "def call_any(x):\n    x.op()\n");

        var keys = new ArrayList<>(baseFiles.keySet());
        var frontend = new TreeSitterSourceFrontend();

        ScopeGraph first = frontend.analyzeWorkspace(utf8Files(baseFiles), () -> false);

        // Reverse insertion order and re-run multiple times -> bit-identical SHA-256 digest & DFS order
        Collections.reverse(keys);
        var reversed = new LinkedHashMap<String, String>();
        for (String k : keys) reversed.put(k, baseFiles.get(k));

        ScopeGraph second = frontend.analyzeWorkspace(utf8Files(reversed), () -> false);
        assertEquals(first.canonicalDigest(), second.canonicalDigest(), "SHA-256 digest must be bit-identical (I2)");
        assertEquals(first.dfsTraversalOrder(), second.dfsTraversalOrder(), "DFS order must be deterministic");
        assertEquals(first.edges(), second.edges());
        assertEquals(first.capabilityGaps(), second.capabilityGaps());
        // Post-order DFS visits dependencies before dependents: src/a.ts -> src/b.ts -> src/c.ts
        assertTrue(first.dfsTraversalOrder().indexOf("src/a.ts") < first.dfsTraversalOrder().indexOf("src/b.ts"));
        assertTrue(first.dfsTraversalOrder().indexOf("src/b.ts") < first.dfsTraversalOrder().indexOf("src/c.ts"));

        // Verify combined single-pass factSink forwards intra-file Facts alongside ScopeGraph
        var collectedFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        ScopeGraph withFacts = frontend.analyzeWorkspace(
                utf8Files(baseFiles), (path, fact) -> collectedFacts.add(fact), null, () -> false);
        assertEquals(first.canonicalDigest(), withFacts.canonicalDigest());
        assertFalse(collectedFacts.isEmpty());
        assertTrue(collectedFacts.stream().anyMatch(f -> f.category().equals("DECLARATION") && f.name().equals("Beta")));
    }

    @Test
    void cyclicReExportsAndAmbiguousWildcardImportsRemainCycleSafeAndSound() throws Exception {
        var files = new LinkedHashMap<String, String>();
        // Cyclic barrel re-exports in TypeScript must terminate cleanly
        files.put("src/alpha.ts", "export * from './beta';\nexport function fromAlpha(): void {}\n");
        files.put("src/beta.ts", "export * from './alpha';\nexport function fromBeta(): void {}\n");
        files.put("src/consumer.ts", "import { fromAlpha, fromBeta } from './alpha';\nexport function useBoth() { fromAlpha(); fromBeta(); }\n");

        // Ambiguous wildcard import in Python must emit AMBIGUOUS_POLYGLOT_CALL without guessing
        files.put("wild/left.py", "def collide():\n    return 1\n");
        files.put("wild/right.py", "def collide():\n    return 2\n");
        files.put("wild/main.py", "from .left import *\nfrom .right import *\ndef run():\n    collide()\n");

        ScopeGraph graph = new TreeSitterSourceFrontend().analyzeWorkspace(utf8Files(files), () -> false);

        assertTrue(graph.hasEdge(EdgeKind.CALLS_SYMBOL, "src/consumer.ts", "src/alpha.ts", "fromAlpha"));
        assertTrue(graph.hasEdge(EdgeKind.CALLS_SYMBOL, "src/consumer.ts", "src/beta.ts", "fromBeta"));
        assertTrue(graph.edgesFrom("wild/main.py").stream().noneMatch(e -> e.kind() == EdgeKind.CALLS_SYMBOL),
                () -> "Ambiguous wildcard call must not guess: " + graph.edgesFrom("wild/main.py"));
        assertTrue(graph.capabilityGaps().stream().anyMatch(g -> g.reasonCode().equals("AMBIGUOUS_POLYGLOT_CALL")
                && g.limitations().contains("detail=ambiguous-wildcard-import-call")));
    }
}
