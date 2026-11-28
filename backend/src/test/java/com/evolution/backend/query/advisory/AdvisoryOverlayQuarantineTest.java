package com.evolution.backend.query.advisory;

import com.evolution.analysis.assessment.v2.ArchitectureAssessmentV2Service;
import com.evolution.analysis.assessment.v2.ArchitectureScoreModelV2;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.AnalysisIdentity;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.identity.EntityScope;
import com.evolution.analysis.contract.semantic.Entity;
import com.evolution.analysis.contract.semantic.EntityKind;
import com.evolution.analysis.contract.semantic.EntityOrigin;
import com.evolution.analysis.frontend.JavaSymbolName;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.filesystem.FileGraphStore;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.metrics.ArchitectureMetric;
import com.evolution.analysis.metrics.ArchitectureMetricsService;
import com.evolution.analysis.policy.PolicyCompiler;
import com.evolution.analysis.policy.PolicyEvaluation;
import com.evolution.analysis.policy.PolicyEvaluator;
import com.evolution.analysis.policy.PolicyGraphMapper;
import com.evolution.analysis.query.ArchitectureQuery.StopReason;
import com.evolution.backend.analysis.JsWorkspaceAliasResolver;
import com.evolution.backend.query.ArchitectureQueryApi;
import com.evolution.backend.query.ArchitectureWikiGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mandatory Wave 3 contract &amp; quarantine test suite proving:
 * <ul>
 *   <li>All 4 deterministic pure-Java non-code extractors (Markdown/ADR, SQL DDL &lt;-&gt; JPA,
 *       Terraform/HCL with recursive secret redaction, JS/TS workspace aliases &amp; .mcp.json env-name-only) work accurately.</li>
 *   <li>Every advisory node/link carries {@code schema=advisory.doc-link:v1}, {@code derivation=INFERRED},
 *       and {@code trust=ADVISORY_UNVERIFIED}.</li>
 *   <li>Advisory extraction &amp; non-code file mutations change <strong>ZERO bits</strong> of canonical
 *       {@link KnowledgeGraph#identity()}, {@link PolicyEvaluator} findings, and {@link ArchitectureAssessmentV2Service} Health Score v2!</li>
 * </ul>
 */
class AdvisoryOverlayQuarantineTest {

    @Test
    void sqlDdlExtractorParsesMultiDialectDdlAndMapsJpaEntitiesByTableAndCamelSnake() {
        String sql = """
                -- Flyway migration V1
                CREATE TABLE IF NOT EXISTS "public"."customers" (
                    id BIGSERIAL PRIMARY KEY,
                    email VARCHAR(255) NOT NULL
                );
                CREATE TABLE [dbo].[order_line_items] (
                    id BIGINT PRIMARY KEY,
                    customer_id BIGINT REFERENCES customers(id)
                );
                ALTER TABLE order_line_items ADD CONSTRAINT fk_cust FOREIGN KEY (customer_id) REFERENCES customers(id);
                CREATE MATERIALIZED VIEW active_orders AS SELECT * FROM order_line_items;
                CREATE TRIGGER trg_audit AFTER INSERT ON order_line_items EXECUTE FUNCTION audit_fn();
                CREATE OR REPLACE FUNCTION audit_fn() RETURNS trigger AS $$ BEGIN RETURN NEW; END; $$ LANGUAGE plpgsql;
                """;

        var jpaCustomer = SqlDdlExtractor.parseJpaEntityFromSource(
                "type:Customer", "com.acme.domain.CustomerAccount", "src/main/java/com/acme/domain/CustomerAccount.java",
                "@Entity\n@Table(name = \"customers\")\npublic class CustomerAccount {}").orElseThrow();
        var jpaLineItem = SqlDdlExtractor.parseJpaEntityFromSource(
                "type:OrderLineItem", "com.acme.domain.OrderLineItem", "src/main/java/com/acme/domain/OrderLineItem.java",
                "@jakarta.persistence.Entity\npublic class OrderLineItem {}").orElseThrow();

        var out = SqlDdlExtractor.extract("db/migration/V1__init.sql", "sha256:sql1", sql, List.of(jpaCustomer, jpaLineItem));
        assertTrue(out.nodes().stream().anyMatch(n -> "SQL_TABLE".equals(n.kind()) && n.label().contains("customers")));
        assertTrue(out.nodes().stream().anyMatch(n -> "SQL_TABLE".equals(n.kind()) && n.label().contains("order_line_items")));
        assertTrue(out.nodes().stream().anyMatch(n -> "SQL_VIEW".equals(n.kind())));
        assertTrue(out.nodes().stream().anyMatch(n -> "SQL_TRIGGER".equals(n.kind())));
        assertTrue(out.nodes().stream().anyMatch(n -> "SQL_ROUTINE".equals(n.kind())));
        assertTrue(out.links().stream().anyMatch(l -> "FOREIGN_KEY".equals(l.kind())));
        assertTrue(out.links().stream().anyMatch(l -> "SQL_TABLE_MAPS_JPA_ENTITY".equals(l.kind())
                && "type:Customer".equals(l.targetId()) && l.matchBasis().contains("@Table")));
        assertTrue(out.links().stream().anyMatch(l -> "SQL_TABLE_MAPS_JPA_ENTITY".equals(l.kind())
                && "type:OrderLineItem".equals(l.targetId()) && l.matchBasis().contains("camelCase<->snake_case")));
    }

    @Test
    void terraformAndMcpConfigExtractorsRedactSecretsAndKeepOnlyEnvVarNames() {
        String tf = """
                resource "aws_db_instance" "orders_db" {
                  engine   = "postgres"
                  password = "UltraSecretDbPassword99!"
                  api_key  = "AKIAIOSFODNN7EXAMPLE"
                }
                module "network" {
                  source = "./modules/vpc"
                }
                data "aws_ami" "ubuntu" {
                  most_recent = true
                }
                output "db_endpoint" {
                  value = aws_db_instance.orders_db.endpoint
                }
                """;
        var tfOut = TerraformHclExtractor.extract("infra/main.tf", "sha256:tf1", tf);
        assertEquals(4, tfOut.nodes().size());
        assertTrue(tfOut.redactedSecretsCount() >= 2);
        assertTrue(tfOut.links().stream().anyMatch(l -> "TF_REFERENCES".equals(l.kind())));
        String tfJson = CanonicalJson.write(tfOut.nodes().stream().map(AdvisoryOverlay.AdvisoryNode::toView).toList());
        assertFalse(tfJson.contains("UltraSecretDbPassword99"), "Raw TF password must NEVER survive parse-time redaction");
        assertFalse(tfJson.contains("AKIAIOSFODNN7EXAMPLE"), "Raw AWS key must NEVER survive parse-time redaction");

        String mcpJson = """
                {
                  "mcpServers": {
                    "evo-local": {
                      "command": "java",
                      "args": ["-jar", "evo.jar"],
                      "env": {
                        "EVO_SESSION_TOKEN": "super-secret-live-token-xyz",
                        "GITHUB_TOKEN": "ghp_1234567890abcdef1234567890abcdef"
                      }
                    }
                  }
                }
                """;
        var mcpOut = McpConfigExtractor.extract(".mcp.json", "sha256:mcp1", mcpJson);
        assertTrue(mcpOut.nodes().stream().anyMatch(n -> "MCP_SERVER".equals(n.kind()) && "evo-local".equals(n.label())));
        assertTrue(mcpOut.nodes().stream().anyMatch(n -> "MCP_COMMAND".equals(n.kind()) && "java".equals(n.label())));
        assertTrue(mcpOut.nodes().stream().anyMatch(n -> "MCP_ENV_VAR".equals(n.kind()) && "EVO_SESSION_TOKEN".equals(n.label())));
        assertTrue(mcpOut.nodes().stream().anyMatch(n -> "MCP_ENV_VAR".equals(n.kind()) && "GITHUB_TOKEN".equals(n.label())));
        String serializedMcp = CanonicalJson.write(mcpOut.nodes().stream().map(AdvisoryOverlay.AdvisoryNode::toView).toList());
        assertFalse(serializedMcp.contains("super-secret-live-token-xyz"), "MCP env values must NEVER be stored");
        assertFalse(serializedMcp.contains("ghp_1234567890abcdef"), "MCP env token values must NEVER be stored");
    }

    @Test
    void jsWorkspaceAliasResolverParsesJsoncTsconfigPnpmWorkspacePackageExportsAndVueSvelteScripts() {
        String tsconfig = """
                {
                  // JSONC comment with trailing commas
                  "compilerOptions": {
                    "baseUrl": ".",
                    "paths": {
                      "@/*": ["src/*"],
                      "@shared/*": ["packages/shared/src/*"],
                    },
                  },
                }
                """;
        String pnpmWs = "packages:\n  - 'packages/*'\n  - 'apps/*'\n";
        String pkgJson = """
                {
                  "name": "@acme/ui",
                  "exports": { ".": { "import": "./dist/index.js" }, "./button": "./src/Button.tsx" },
                  "imports": { "#utils/*": "./src/utils/*" }
                }
                """;
        var model = JsWorkspaceAliasResolver.build(Map.of(
                "tsconfig.json", tsconfig,
                "pnpm-workspace.yaml", pnpmWs,
                "packages/ui/package.json", pkgJson));

        var known = Set.of("src/api/orders.ts", "packages/shared/src/types.ts", "packages/ui/src/Button.tsx", "packages/ui/src/utils/date.ts");
        assertEquals(Optional.of("src/api/orders.ts"), model.resolveImport("src/app.ts", "@/api/orders", known));
        assertEquals(Optional.of("packages/shared/src/types.ts"), model.resolveImport("src/app.ts", "@shared/types", known));
        assertEquals(Optional.of("packages/ui/src/Button.tsx"), model.resolveImport("apps/web/main.ts", "@acme/ui/button", known));
        assertEquals(Optional.of("packages/ui/src/utils/date.ts"), model.resolveImport("packages/ui/src/index.ts", "#utils/date", known));
        assertTrue(model.workspaceGlobs().containsAll(List.of("packages/*", "apps/*")));

        String vueFile = "<template><div/></template>\n<script setup lang=\"ts\">\nimport { fetchOrders } from '@/api/orders';\n</script>\n";
        var scripts = JsWorkspaceAliasResolver.extractSfcScripts(vueFile);
        assertEquals(1, scripts.size());
        assertEquals("typescript", scripts.getFirst().language());
        assertEquals(2, scripts.getFirst().startLine());
        assertTrue(scripts.getFirst().code().contains("fetchOrders"));
    }

    @Test
    void jsWorkspaceAliasResolverResolvesMonorepoRelativeAliasesAndBlocksRepoRootEscape() {
        String webTsconfig = """
                {
                  "compilerOptions": {
                    "baseUrl": ".",
                    "paths": {
                      "@shared/*": ["../shared/*"],
                      "@core/*": ["../../packages/core/src/*"],
                      "@escape/*": ["../../../etc/*"]
                    }
                  }
                }
                """;
        String adminTsconfig = """
                {
                  "compilerOptions": {
                    "baseUrl": "..",
                    "paths": {
                      "@common/*": ["shared/*"]
                    }
                  }
                }
                """;
        var model = JsWorkspaceAliasResolver.build(Map.of(
                "apps/web/tsconfig.json", webTsconfig,
                "apps/admin/tsconfig.json", adminTsconfig));
        var known = Set.of(
                "apps/shared/utils/date.ts",
                "packages/core/src/pricing.ts",
                "etc/passwd.ts");

        // Relative monorepo aliases ../shared/* and ../../packages/core/src/* resolve accurately
        assertEquals(Optional.of("apps/shared/utils/date.ts"),
                model.resolveImport("apps/web/src/App.tsx", "@shared/utils/date", known));
        assertEquals(Optional.of("packages/core/src/pricing.ts"),
                model.resolveImport("apps/web/src/App.tsx", "@core/pricing", known));
        // Relative baseUrl: ".." in apps/admin/tsconfig.json resolves to apps/
        assertEquals(Optional.of("apps/shared/utils/date.ts"),
                model.resolveImport("apps/admin/src/Main.tsx", "@common/utils/date", known));
        // Escape above repo root via ../../../etc/* or via wildcard traversal MUST be blocked
        assertEquals(Optional.empty(),
                model.resolveImport("apps/web/src/App.tsx", "@escape/passwd", known));
        assertEquals(Optional.empty(),
                model.resolveImport("apps/web/src/App.tsx", "@shared/../../../etc/passwd", known));
    }

    @Test
    void advisoryOverlayIsStrictlyQuarantinedFromCanonicalDigestScoreV2PolicyAndHttpEndpointsWork(@TempDir Path tempDir) throws Exception {
        // Build Baseline A: pure Java/Spring graph
        var baseline = buildCanonicalGraph(Map.of());
        // Build Variant B: exact same Java/Spring structure, PLUS non-code advisory docs/SQL/TF/.mcp.json captured in source.bytes
        var withAdvisoryFiles = buildCanonicalGraph(Map.of(
                "docs/decisions/ADR-001-orders.md", "# ADR-001 Orders Architecture\n\n## Domain Boundary\nThe `com.acme.order.OrderService` coordinates `OrderEntity`.\nSee [[README]].\n",
                "README.md", "# Acme Platform\nUses `OrderService`.\n",
                "db/V1__orders.sql", "CREATE TABLE order_entities (id BIGINT PRIMARY KEY);",
                "infra/prod.tf", "resource \"aws_s3_bucket\" \"orders\" {\n  bucket = \"acme-orders\"\n  secret_key = \"top-secret-tf-val\"\n}\n",
                ".mcp.json", "{\"mcpServers\":{\"evo\":{\"command\":\"java\",\"env\":{\"SECRET_KEY\":\"never-leak-this\"}}}}"));

        // 1. Inspecting advisory overlay on baseline NEVER alters canonical graph identity, Score v2, or Policy
        var beforeDigest = baseline.identity();
        var baselineQueries = new ArchitectureMetricsService(baseline, ArchitectureMetric.Limits.defaults(),
                () -> StopReason.NONE, Optional.empty(), List.of()).queries();
        var scorer = new ArchitectureAssessmentV2Service();
        String ctx = baseline.contexts().stream().filter(c -> c.analysis().isPresent()).findFirst().orElseThrow().identity();
        var scoreBefore = scorer.assess(baselineQueries, ctx, Optional.empty(), Optional.empty(), ArchitectureScoreModelV2.standard());
        var policyYaml = """
                policyVersion: architecture-policy:v1
                name: quarantine-check
                rules:
                  - id: no-cycles
                    kind: NO_CYCLES
                    severity: ERROR
                    scope: TYPE
                    relationships: [graph.depends-on]
                """;
        var compiled = new PolicyCompiler().compile(
                policyYaml.getBytes(StandardCharsets.UTF_8),
                EvidenceContext.forSnapshot(baseline.snapshot()),
                PolicyCompiler.Limits.defaults());
        var planBefore = new PolicyGraphMapper().bind(compiled, baselineQueries, ctx, PolicyGraphMapper.Limits.defaults());
        var evalBefore = new PolicyEvaluator().evaluate(compiled, planBefore, baselineQueries, PolicyEvaluation.Limits.defaults());

        // Run AdvisoryOverlayService on baseline AND on withAdvisoryFiles
        var advBaseline = AdvisoryOverlayService.forGraph(baseline);
        var advRich = AdvisoryOverlayService.forGraph(withAdvisoryFiles);

        // Verify strict quarantine markers on every single node and link
        assertEquals(AdvisoryOverlay.SCHEMA, advRich.schema());
        assertEquals(AdvisoryOverlay.DERIVATION, advRich.derivation());
        assertEquals(AdvisoryOverlay.TRUST, advRich.trust());
        assertTrue(advRich.nodes().size() >= 6, "Expected MD/ADR, SQL, TF, MCP advisory nodes");
        assertTrue(advRich.links().stream().anyMatch(l -> "DOC_MENTIONS_SYMBOL".equals(l.kind())));
        assertTrue(advRich.links().stream().anyMatch(l -> "SQL_TABLE_MAPS_JPA_ENTITY".equals(l.kind())));
        assertTrue(advRich.nodes().stream().allMatch(n -> "ADVISORY_UNVERIFIED".equals(n.trust()) && "INFERRED".equals(n.derivation())));
        assertTrue(advRich.links().stream().allMatch(l -> "ADVISORY_UNVERIFIED".equals(l.trust()) && "INFERRED".equals(l.derivation())));

        // Verify Score v2 & Policy findings are IDENTICAL bit-for-bit between baseline (0 non-code files) and withAdvisoryFiles!
        var richQueries = new ArchitectureMetricsService(withAdvisoryFiles, ArchitectureMetric.Limits.defaults(),
                () -> StopReason.NONE, Optional.empty(), List.of()).queries();
        String richCtx = withAdvisoryFiles.contexts().stream().filter(c -> c.analysis().isPresent()).findFirst().orElseThrow().identity();
        var scoreAfter = scorer.assess(richQueries, richCtx, Optional.empty(), Optional.empty(), ArchitectureScoreModelV2.standard());
        var compiledRich = new PolicyCompiler().compile(
                policyYaml.getBytes(StandardCharsets.UTF_8),
                EvidenceContext.forSnapshot(withAdvisoryFiles.snapshot()),
                PolicyCompiler.Limits.defaults());
        var planAfter = new PolicyGraphMapper().bind(compiledRich, richQueries, richCtx, PolicyGraphMapper.Limits.defaults());
        var evalAfter = new PolicyEvaluator().evaluate(compiledRich, planAfter, richQueries, PolicyEvaluation.Limits.defaults());

        assertEquals(beforeDigest, baseline.identity(), "Advisory overlay must NEVER mutate KnowledgeGraph identity");
        assertEquals(scoreBefore.score(), scoreAfter.score(), "Adding .md/.sql/.tf/.mcp.json MUST change 0 bits of Health Score v2");
        assertEquals(scoreBefore.status(), scoreAfter.status());
        assertEquals(scoreBefore.healthBand(), scoreAfter.healthBand());
        assertEquals(evalBefore.findings().size(), evalAfter.findings().size(), "Adding .md/.sql/.tf/.mcp.json MUST change 0 Policy findings");
        assertEquals(0, advBaseline.links().size());

        // Verify quarantined SemRef cluster refinement entry point also preserves 0 canonical leakage
        var semRefOut = AdvisoryOverlayService.refineClustersAdvisory(baseline, ctx);
        assertEquals(com.evolution.analysis.contract.semantic.TrustTier.ADVISORY_UNVERIFIED, semRefOut.trustTier());
        assertTrue(semRefOut.quarantined());
        assertEquals(beforeDigest, baseline.identity());
        assertEquals(scoreBefore.score(), scorer.assess(baselineQueries, ctx, Optional.empty(), Optional.empty(), ArchitectureScoreModelV2.standard()).score());

        // 2. Verify Web App HTTP endpoints (/advisory-links, /markdown-section, /wiki-dossier json|zip|markdown, /mcp/setup)
        var graphsDir = tempDir.resolve("graphs");
        java.nio.file.Files.createDirectories(graphsDir);
        var store = new FileGraphStore(graphsDir);
        store.save(withAdvisoryFiles);
        var api = new ArchitectureQueryApi(store);
        String gid = withAdvisoryFiles.identity().value();

        var advResp = api.handle("GET", "/api/v1/graphs/" + gid + "/advisory-links", "limit=50", null, new byte[0], () -> false);
        assertEquals(200, advResp.status());
        String advJson = new String(advResp.body(), StandardCharsets.UTF_8);
        assertTrue(advJson.contains("ADVISORY_UNVERIFIED"));
        assertFalse(advJson.contains("top-secret-tf-val"));
        assertFalse(advJson.contains("never-leak-this"));

        var mdResp = api.handle("POST", "/api/v1/graphs/" + gid + "/markdown-section", null, "application/json",
                CanonicalJson.write(Map.of("path", "docs/decisions/ADR-001-orders.md", "heading", "Domain Boundary")).getBytes(StandardCharsets.UTF_8), () -> false);
        assertEquals(200, mdResp.status());
        assertTrue(new String(mdResp.body(), StandardCharsets.UTF_8).contains("OrderService"));

        var wikiJsonResp = api.handle("GET", "/api/v1/graphs/" + gid + "/wiki-dossier", "format=json", null, new byte[0], () -> false);
        assertEquals(200, wikiJsonResp.status());
        assertTrue(new String(wikiJsonResp.body(), StandardCharsets.UTF_8).contains("suggested-questions.md"));

        var wikiZip1 = api.handle("GET", "/api/v1/graphs/" + gid + "/wiki-dossier", "format=zip", null, new byte[0], () -> false);
        var wikiZip2 = api.handle("GET", "/api/v1/graphs/" + gid + "/wiki-dossier", "format=zip", null, new byte[0], () -> false);
        assertEquals(200, wikiZip1.status());
        assertEquals("application/zip", wikiZip1.headers().get("Content-Type").getFirst());
        assertEquals(ContentDigest.sha256(wikiZip1.body()), ContentDigest.sha256(wikiZip2.body()), "Wiki ZIP must be 100% deterministic");

        // Atomic write-if-changed + orphan sweep check
        var wikiBundle = ArchitectureWikiGenerator.generate(richQueries, richCtx, null);
        var write1 = ArchitectureWikiGenerator.writeToDirectoryAtomic(wikiBundle, tempDir.resolve("wiki"));
        var write2 = ArchitectureWikiGenerator.writeToDirectoryAtomic(wikiBundle, tempDir.resolve("wiki"));
        assertTrue(((Number) write1.get("written")).intValue() >= 5);
        assertEquals(0, ((Number) write2.get("written")).intValue(), "Second atomic wiki export should rewrite 0 unchanged files");

        var mcpSetupResp = api.handle("GET", "/api/v1/mcp/setup", null, null, new byte[0], () -> false);
        assertEquals(200, mcpSetupResp.status());
        String mcpSetupJson = new String(mcpSetupResp.body(), StandardCharsets.UTF_8);
        assertTrue(mcpSetupJson.contains("evo://score-v2"));
        assertTrue(mcpSetupJson.contains("evo://spring-summary"));
    }

    @Test
    void sqlAndTerraformEdgeCasesHandleStringLiteralsNestedAnnotationsCrossFileLinksAndHeredocs() {
        // 1. SQL string literal must NOT produce a fake table, and @Table with nested @Index(name=...) must capture top-level name
        String sqlV1 = """
                INSERT INTO audit_log (msg) VALUES ('CREATE TABLE fake_from_string (id INT);');
                CREATE TABLE users (id BIGINT PRIMARY KEY);
                """;
        String sqlV2 = """
                CREATE TABLE real_orders (
                    id BIGINT PRIMARY KEY,
                    user_id BIGINT REFERENCES users(id)
                );
                CREATE TRIGGER trg_orders AFTER INSERT ON real_orders EXECUTE FUNCTION fn();
                """;
        var jpaNested = SqlDdlExtractor.parseJpaEntityFromSource(
                "type:OrderRec", "com.acme.OrderRec", "src/OrderRec.java",
                """
                @Entity
                @Table(indexes = {@Index(name = "idx_orders_created", columnList = "created_at")}, name = "real_orders")
                public class OrderRec {}
                """).orElseThrow();
        assertEquals(List.of("real_orders"), jpaNested.explicitTableNames(),
                "Nested @Index(name=...) must not hijack @Table(name=...)");

        var v1Out = SqlDdlExtractor.extract("db/V1__users.sql", "sha256:v1", sqlV1, List.of(jpaNested));
        assertTrue(v1Out.nodes().stream().noneMatch(n -> n.label().contains("fake_from_string")),
                "DDL keywords inside SQL string literals must be ignored");
        assertTrue(v1Out.nodes().stream().anyMatch(n -> "SQL_TABLE".equals(n.kind()) && "users".equals(n.label())));

        var v2Out = SqlDdlExtractor.extract("db/V2__orders.sql", "sha256:v2", sqlV2, List.of(jpaNested));
        String realOrdersNodeId = v2Out.nodes().stream()
                .filter(n -> "SQL_TABLE".equals(n.kind()) && "real_orders".equals(n.label()))
                .findFirst().orElseThrow().id();
        var trigLink = v2Out.links().stream().filter(l -> "SQL_TRIGGER_ON".equals(l.kind())).findFirst().orElseThrow();
        assertEquals(realOrdersNodeId, trigLink.targetId(), "Intra-file SQL_TRIGGER_ON must target real SQL_TABLE node ID");

        // Cross-file resolution in AdvisoryOverlayService: V2__orders.sql FK -> V1__users.sql SQL_TABLE node ID!
        var multiSqlGraph = buildCanonicalGraph(Map.of(
                "db/V1__users.sql", sqlV1,
                "db/V2__orders.sql", sqlV2));
        var multiAdv = AdvisoryOverlayService.forGraph(multiSqlGraph);
        String usersNodeId = multiAdv.nodes().stream()
                .filter(n -> "SQL_TABLE".equals(n.kind()) && "users".equals(n.label()))
                .findFirst().orElseThrow().id();
        var fkLink = multiAdv.links().stream().filter(l -> "FOREIGN_KEY".equals(l.kind())).findFirst().orElseThrow();
        assertEquals(usersNodeId, fkLink.targetId(), "Cross-file FOREIGN_KEY must resolve to real SQL_TABLE node ID");

        // 2. Terraform data.aws_ami.ubuntu.id must NOT emit spurious TF_RESOURCE aws_ami.ubuntu;
        //    /* ... */ comments & sensitive heredoc with '}' inside must preserve brace depth & redact body!
        String tfComplex = """
                /* block comment with { unmatched brace and
                   resource "aws_fake" "commented" {
                   } */
                data "aws_ami" "ubuntu" {
                  most_recent = true
                }
                resource "aws_instance" "web" {
                  ami = data.aws_ami.ubuntu.id
                  password = <<-EOF
                    HeredocSecretValue777
                    closing brace inside heredoc } should not break block!
                  EOF
                  name = "web-prod"
                }
                """;
        var tfOut = TerraformHclExtractor.extract("infra/compute.tf", "sha256:tfc", tfComplex);
        assertEquals(2, tfOut.nodes().size(), "Commented resource block must be ignored; only data + resource extracted");
        var webNode = tfOut.nodes().stream().filter(n -> "aws_instance.web".equals(n.label())).findFirst().orElseThrow();
        assertTrue(webNode.endLine() >= 14, "Heredoc brace must not prematurely close resource block; endLine=" + webNode.endLine());
        @SuppressWarnings("unchecked")
        var webAttrs = (Map<String, Object>) webNode.metadata().get("attributes");
        assertNotNull(webAttrs);
        assertEquals("web-prod", webAttrs.get("name"), "Attributes after sensitive heredoc must still be extracted");
        assertTrue(tfOut.redactedSecretsCount() >= 1);
        String serializedTf = CanonicalJson.write(tfOut.nodes().stream().map(AdvisoryOverlay.AdvisoryNode::toView).toList());
        assertFalse(serializedTf.contains("HeredocSecretValue777"), "Sensitive heredoc body must never leak");

        // Verify data.aws_ami.ubuntu emits ONLY TF_DATA link, never bogus TF_RESOURCE aws_ami.ubuntu
        var tfRefs = tfOut.links().stream().filter(l -> "TF_REFERENCES".equals(l.kind())).toList();
        assertEquals(1, tfRefs.size(), "Expected single TF_DATA reference, got " + tfRefs);
        assertEquals("TF_DATA", tfRefs.getFirst().targetKind());
        assertEquals("data.aws_ami.ubuntu", tfRefs.getFirst().targetLabel());
    }

    @Test
    void vue3GenericSfcAndHtmlCommentAndRealStaticPolyglotScanPreserveLineNumbersAndTsconfigAliases(@TempDir Path tempDir) throws Exception {
        // 1. Vue 3 generic="T extends Record<string, any>" + commented-out <script>
        String vueSfc = String.join("\n",
                "<template>",
                "  <!-- <script lang=\"ts\">const fakeCommented = 1;</script> -->",
                "  <div>{{ title }}</div>",
                "</template>",
                "<script setup lang=\"ts\" generic=\"T extends Record<string, any>\">",
                "import { calcTotal } from '@core/pricing';",
                "export function renderTotal(x: T) { return calcTotal(x); }",
                "</script>");
        var scripts = JsWorkspaceAliasResolver.extractSfcScripts(vueSfc);
        assertEquals(1, scripts.size(), "Commented-out script must be ignored and Vue 3 generic attr parsed quote-aware");
        assertEquals("typescript", scripts.getFirst().language());
        assertEquals(5, scripts.getFirst().startLine());
        assertTrue(scripts.getFirst().code().contains("calcTotal"));

        // 2. Real StaticPolyglotScan end-to-end on a temp repo with tsconfig.json @core/* alias + .vue SFC + .ts target!
        var repoDir = tempDir.resolve("vue-repo");
        java.nio.file.Files.createDirectories(repoDir.resolve("packages/core/src"));
        java.nio.file.Files.createDirectories(repoDir.resolve("src/components"));
        java.nio.file.Files.writeString(repoDir.resolve("tsconfig.json"), """
                {
                  "compilerOptions": {
                    "baseUrl": ".",
                    "paths": { "@core/*": ["packages/core/src/*"] }
                  }
                }
                """);
        java.nio.file.Files.writeString(repoDir.resolve("packages/core/src/pricing.ts"),
                "export function calcTotal(v: unknown) { return 42; }\n");
        java.nio.file.Files.writeString(repoDir.resolve("src/components/Cart.vue"), vueSfc);
        java.nio.file.Files.writeString(repoDir.resolve("src/components/App.ts"),
                "import { calcTotal } from '@core/pricing';\nexport const out = calcTotal({});\n");

        var repoId = RepositoryIdentity.fromCanonicalCoordinate("urn:test:vue-tsconfig-scan");
        KnowledgeGraph graph;
        try (var intake = new com.evolution.backend.analysis.RepositoryIntake(tempDir.resolve("vue-intake"))) {
            intake.localComplete(repoDir, "vue",
                    new com.evolution.analysis.acquisition.streaming.StreamingAcquisitionRequest(
                            repoId, Optional.empty(), false, List.of()), () -> false);
            var readiness = intake.readiness("vue",
                    com.evolution.analysis.readiness.ReadinessPlanner.Policy.comprehensive(), () -> false);
            var snapshot = com.evolution.analysis.contract.source.RepositorySnapshot.create(
                    repoId, Optional.empty(), false, List.of(), List.of());
            var scan = new com.evolution.backend.analysis.PolyglotScan();
            var result = scan.run("vue", intake, readiness, EvidenceContext.forSnapshot(snapshot.identity()),
                    Optional.empty(), tempDir.resolve("vue-work"), () -> false);
            var input = new com.evolution.analysis.graph.CanonicalGraphInput(
                    new com.evolution.analysis.ingestion.RepositoryInputs(snapshot, Map.of()),
                    Optional.empty(), Optional.empty(), List.of(), List.of(), result.gaps());
            var pages = new com.evolution.analysis.filesystem.PartitionedGraphStore(tempDir.resolve("vue-pages"));
            var snapshots = tempDir.resolve("vue-snapshots");
            var published = pages.build(input, snapshots, () -> false,
                    store -> scan.project(result, store, () -> false));
            graph = FileGraphStore.readSnapshot(
                    snapshots.resolve(published.graph().value().substring(7) + ".graph.json"), 64_000_000,
                    new com.evolution.analysis.graph.GraphJson.StreamLimits(64_000_000, 128, 64_000_000));
        }

        // Verify .vue SFC fact carries exact 1-based line number >= 6 (not shifted down to line 1-2!)
        // AND both Cart.vue (via langGroup("vue") -> "js-ts") and App.ts link @core/pricing via tsconfig.json!
        boolean foundVueFactAtRealLine = false;
        boolean foundVueImportLink = false;
        boolean foundTsImportLink = false;
        for (var ev : graph.evidence()) {
            if ("polyglot.source-syntax".equals(ev.family())) {
                var obj = com.evolution.analysis.graph.GraphJson.object(
                        com.evolution.analysis.graph.GraphJson.read(ev.canonicalJson(),
                                com.evolution.analysis.graph.GraphJson.Limits.defaults()));
                Map<?, ?> fields = obj.get("fields") instanceof Map<?, ?> f ? f : obj;
                if ("vue".equals(fields.get("language")) && fields.get("fact") instanceof Map<?, ?> fact
                        && fact.get("span") instanceof Map<?, ?> span
                        && span.get("startLine") instanceof java.math.BigDecimal sl
                        && sl.intValue() >= 6) {
                    foundVueFactAtRealLine = true;
                }
            } else if ("polyglot.static-symbol-link".equals(ev.family())) {
                String json = ev.canonicalJson();
                if (json.contains("@core/pricing") && json.contains("\"language\":\"vue\"")) {
                    foundVueImportLink = true;
                }
                if (json.contains("@core/pricing") && json.contains("\"language\":\"typescript\"")) {
                    foundTsImportLink = true;
                }
            }
        }
        assertTrue(foundVueFactAtRealLine, "Vue SFC syntax facts must retain exact 1-based line numbers (>=6), not 1-based block-relative lines");
        assertTrue(foundVueImportLink, "Vue SFC import of @core/pricing must link to TypeScript declaration via js-ts langGroup + tsconfig alias");
        assertTrue(foundTsImportLink, "StaticPolyglotScan must resolve @core/pricing via tsconfig.json aliases in TypeScript");
    }

    @Test
    void liveHttpServerAcceptsRawGraphDigestAndRejectsPercentEncodedColon(@TempDir Path tempDir) throws Exception {
        var graph = buildCanonicalGraph(Map.of(
                "docs/ADR-001.md", "# ADR 001\n\n## Core\nUses `OrderService`.\n",
                "db/V1__orders.sql", "CREATE TABLE order_entities (id BIGINT PRIMARY KEY);",
                "infra/prod.tf", "resource \"aws_s3_bucket\" \"orders\" { bucket = \"acme\" }"));
        var graphsDir = tempDir.resolve("graphs");
        java.nio.file.Files.createDirectories(graphsDir);
        var store = new FileGraphStore(graphsDir);
        store.save(graph);
        var api = new ArchitectureQueryApi(store);
        var http = java.net.http.HttpClient.newHttpClient();
        String gid = graph.identity().value();

        try (var server = new com.evolution.backend.query.ArchitectureHttpServer(api, 0)) {
            server.start();
            var base = server.uri();

            // Percent-encoded %3A is rejected with HTTP 400 (proving why community-advisory.js graphPath() must keep ':' raw)
            var bad = http.send(java.net.http.HttpRequest.newBuilder(
                    base.resolve("/api/v1/graphs/" + gid.replace(":", "%3A") + "/advisory-links")).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(400, bad.statusCode());

            // Raw sha256:<hex> succeeds 200 across every new Web App route!
            var adv = http.send(java.net.http.HttpRequest.newBuilder(
                    base.resolve("/api/v1/graphs/" + gid + "/advisory-links?limit=50")).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, adv.statusCode());
            assertTrue(adv.body().contains("SQL_TABLE_MAPS_JPA_ENTITY"));

            var md = http.send(java.net.http.HttpRequest.newBuilder(
                    base.resolve("/api/v1/graphs/" + gid + "/markdown-section"))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"path\":\"docs/ADR-001.md\",\"heading\":\"Core\"}"))
                    .build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, md.statusCode());
            assertTrue(md.body().contains("OrderService"));

            var zip = http.send(java.net.http.HttpRequest.newBuilder(
                    base.resolve("/api/v1/graphs/" + gid + "/wiki-dossier?format=zip")).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, zip.statusCode());
            assertTrue(zip.body().length > 4 && zip.body()[0] == 'P' && zip.body()[1] == 'K', "Expected valid ZIP magic bytes PK");

            // community-evolution with omitted context/otherContext defaults to largest analysis context!
            var commEvo = http.send(java.net.http.HttpRequest.newBuilder(
                    base.resolve("/api/v1/graphs/" + gid + "/community-evolution"))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"otherGraph\":\"" + gid + "\"}"))
                    .build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, commEvo.statusCode(), commEvo.body());
            assertTrue(commEvo.body().contains("stabilityIndex"));

            // Static Web App assets served with zero-https CSP
            var caJs = http.send(java.net.http.HttpRequest.newBuilder(base.resolve("/community-advisory.js")).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, caJs.statusCode());
            assertFalse(caJs.headers().firstValue("Content-Security-Policy").orElse("").contains("https:"));
            assertTrue(caJs.body().contains("function graphPath"));
        }
    }

    @Test
    void runtimeDriftOverlayIsStrictlyQuarantinedInRuntimeObservedTierWithZeroCanonicalLeakage() {
        var graph = buildCanonicalGraph(Map.of(
                "docs/ADR-001.md", "# ADR 001\nUses `OrderService`.\n"));
        var digestBefore = graph.identity();
        var queriesBefore = new ArchitectureMetricsService(graph, ArchitectureMetric.Limits.defaults(),
                () -> StopReason.NONE, Optional.empty(), List.of()).queries();
        String ctx = graph.contexts().stream().filter(c -> c.analysis().isPresent()).findFirst().orElseThrow().identity();
        var scorer = new ArchitectureAssessmentV2Service();
        var scoreBefore = scorer.assess(queriesBefore, ctx, Optional.empty(), Optional.empty(), ArchitectureScoreModelV2.standard());

        var docAdvisory = AdvisoryOverlayService.forGraph(graph);
        var csvDrift = RuntimeDriftOverlayExtractor.extractFromCsv(graph,
                "src,dst,weight\napi-gateway,order-service,42\norder-service,payment-service,7\n");
        var jsonDrift = RuntimeDriftOverlayExtractor.extractFromJson(graph,
                "{\"edges\":[{\"source\":\"api-gateway\",\"target\":\"order-service\",\"callCount\":42}]}");

        assertEquals(AdvisoryOverlay.TRUST, docAdvisory.trust());
        assertEquals("RUNTIME_OBSERVED", csvDrift.trust());
        assertEquals("RUNTIME_OBSERVED", jsonDrift.trust());
        assertTrue(csvDrift.quarantined());
        assertTrue(jsonDrift.quarantined());
        assertEquals(2, csvDrift.shadowDependencies().size());
        assertEquals(1, jsonDrift.shadowDependencies().size());
        assertTrue(csvDrift.findings().stream().allMatch(f -> "RUNTIME_OBSERVED".equals(f.trust()) && f.quarantined()));

        // Zero mutation to canonical KnowledgeGraph digest or Architecture Score v2 (canonicalLeakage = 0)
        assertEquals(digestBefore, graph.identity());
        var queriesAfter = new ArchitectureMetricsService(graph, ArchitectureMetric.Limits.defaults(),
                () -> StopReason.NONE, Optional.empty(), List.of()).queries();
        var scoreAfter = scorer.assess(queriesAfter, ctx, Optional.empty(), Optional.empty(), ArchitectureScoreModelV2.standard());
        assertEquals(scoreBefore.score(), scoreAfter.score());
        assertEquals(scoreBefore.healthBand(), scoreAfter.healthBand());
    }

    private static KnowledgeGraph buildCanonicalGraph(Map<String, String> extraNonCodeFiles) {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/quarantine.git");
        var snap = SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("quarantine-snap-" + extraNonCodeFiles.size()));
        var analysis = AnalysisIdentity.fromCanonicalManifestInputs("quarantine-mod");
        String ctx = analysis.value();
        var mod = ModuleIdentity.from(repo, ".");

        var snapEv = GraphEvidence.capture("repository.snapshot", Map.of("snapshot", snap));
        var manEv = GraphEvidence.capture("analysis.manifest", Map.of("modules", List.of(Map.of("identity", mod, "path", "."))));
        var modEv = GraphEvidence.capture("module.descriptor", Map.of("identity", mod, "path", "."));
        var evidence = new ArrayList<GraphEvidence>(List.of(snapEv, manEv, modEv));
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        var edges = new ArrayList<KnowledgeGraph.Edge>();
        nodes.add(new KnowledgeGraph.Node(mod.value(), ctx, "MODULE", List.of(modEv.identity())));

        // Add OrderService -> OrderEntity
        String svcCode = "package com.acme.order;\npublic class OrderService { OrderEntity entity; }\n";
        String entCode = "package com.acme.order;\n@jakarta.persistence.Entity\n@jakarta.persistence.Table(name = \"order_entities\")\npublic class OrderEntity {}\n";
        var codeMap = new LinkedHashMap<String, String>();
        codeMap.put("src/main/java/com/acme/order/OrderService.java", svcCode);
        codeMap.put("src/main/java/com/acme/order/OrderEntity.java", entCode);
        codeMap.putAll(extraNonCodeFiles);

        var docIds = new HashMap<String, String>();
        for (var entry : codeMap.entrySet()) {
            byte[] bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
            var digest = ContentDigest.sha256(bytes);
            var ev = GraphEvidence.capture("source.bytes", Map.of(
                    "document", Map.of("path", entry.getKey(), "contentDigest", digest.value()),
                    "size", bytes.length,
                    "bytesBase64", Base64.getEncoder().encodeToString(bytes)));
            evidence.add(ev);
            String docId = KnowledgeGraph.derivedIdentity("source-doc", List.of(entry.getKey()));
            nodes.add(new KnowledgeGraph.Node(docId, snap.value(), "SOURCE_DOCUMENT", List.of(ev.identity())));
            docIds.put(entry.getKey(), docId);
        }

        var pkgEntity = Entity.create(EntityOrigin.PROJECT, EntityScope.project(mod), EntityKind.PACKAGE,
                JavaSymbolName.packageName("com.acme.order").canonicalName(), Optional.empty());
        var pkgDecl = GraphEvidence.capture("java.declaration", Map.of("entity", pkgEntity, "spelling", "com.acme.order", "status", "RESOLVED"));
        evidence.add(pkgDecl);
        nodes.add(new KnowledgeGraph.Node(pkgEntity.identity().value(), ctx, "PACKAGE", List.of(pkgDecl.identity())));

        var svcEntity = Entity.create(EntityOrigin.PROJECT, EntityScope.project(mod), EntityKind.TYPE,
                JavaSymbolName.topLevelType("com.acme.order", "OrderService").canonicalName(), Optional.empty());
        var entEntity = Entity.create(EntityOrigin.PROJECT, EntityScope.project(mod), EntityKind.TYPE,
                JavaSymbolName.topLevelType("com.acme.order", "OrderEntity").canonicalName(), Optional.empty());
        String svcId = svcEntity.identity().value();
        String entId = entEntity.identity().value();

        var svcEv = GraphEvidence.capture("java.declaration", Map.of(
                "entity", svcEntity,
                "spelling", "OrderService",
                "status", "RESOLVED",
                "declaration", Map.of("document", docIds.get("src/main/java/com/acme/order/OrderService.java"))));
        var svcShape = GraphEvidence.capture("java.type-shape", Map.of("type", svcId, "kind", "CLASS", "abstractType", false));
        var entEv = GraphEvidence.capture("java.declaration", Map.of(
                "entity", entEntity,
                "spelling", "OrderEntity",
                "status", "RESOLVED",
                "declaration", Map.of("document", docIds.get("src/main/java/com/acme/order/OrderEntity.java"))));
        var entShape = GraphEvidence.capture("java.type-shape", Map.of("type", entId, "kind", "CLASS", "abstractType", false));
        var depEv = GraphEvidence.capture("java.reference", Map.of("from", svcId, "to", entId));
        evidence.addAll(List.of(svcEv, svcShape, entEv, entShape, depEv));

        nodes.add(new KnowledgeGraph.Node(svcId, ctx, "TYPE", List.of(svcEv.identity(), svcShape.identity())));
        nodes.add(new KnowledgeGraph.Node(entId, ctx, "TYPE", List.of(entEv.identity(), entShape.identity())));
        edges.add(new KnowledgeGraph.Edge(
                KnowledgeGraph.derivedIdentity("contains", List.of(pkgEntity.identity().value(), svcId)),
                ctx, "graph.contains", pkgEntity.identity().value(),
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(svcId), Optional.empty()),
                List.of(svcEv.identity()), Optional.empty()));
        edges.add(new KnowledgeGraph.Edge(
                KnowledgeGraph.derivedIdentity("contains", List.of(pkgEntity.identity().value(), entId)),
                ctx, "graph.contains", pkgEntity.identity().value(),
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(entId), Optional.empty()),
                List.of(entEv.identity()), Optional.empty()));
        edges.add(new KnowledgeGraph.Edge(
                KnowledgeGraph.derivedIdentity("dep", List.of("OrderService", "OrderEntity")),
                ctx, "graph.depends-on", svcId,
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(entId), Optional.empty()),
                List.of(depEv.identity()), Optional.empty()));

        return new KnowledgeGraph(snap, List.of(
                new KnowledgeGraph.Context(snap.value(), snap, Optional.empty(), snapEv.identity()),
                new KnowledgeGraph.Context(ctx, snap, Optional.of(analysis), manEv.identity())),
                nodes, edges, evidence);
    }
}
