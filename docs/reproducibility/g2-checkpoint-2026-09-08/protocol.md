# Gate G2 Evaluation Protocol

## 1. Corpus Identity
- **Repository**: PC-Shop (pinned at commit `818c4136ea971c21674525f9053de0d9c7ad8cfe`)
- **Status**: Must be a clean working tree.
- **Root POM**: `pom.xml`

## 2. Resource Limits
- Max files: 10,000
- Max file size: 10,000,000 bytes
- Max total size: 50,000,000 bytes
- Max depth: 10
- Max discovered entries: 10,000

## 3. Toolchain & Provider Configuration
- **Platform**: Java 21, acquired via `FilesystemJdkPlatformProvider` (m3.5)
- **Filesystem**: `FilesystemRepositoryAcquirer` (m3.3)
- **Maven**: `MavenBuildModelProvider` (3.9.16-m3.3) and `MavenLocalClasspathProvider` (3.9.16-m3.5)
- **Frontend**: `JavaParserFrontend` (m3.6)
- **Schema**: `analysis.manifest` (v2), `analysis.configuration` (v2)

## 4. Semantic Categories
Registered categories and expected denominator tracking:
- `java.calls`
- `java.instantiates`
- `java.extends`
- `java.implements`
- `java.declares`
- `java.returns`
- `java.has_type`
- `java.has_annotation`

## 5. Metric Formulas
- **Resolved Rate**: `Resolved / Attempted`
- **Degraded Rate**: `(Unresolved + Ambiguous + Unmapped) / Attempted`
- **Error Rate**: `Error / Attempted`
- `Attempted` MUST match the total denominator for a category.

## 6. Execution Flow
The evaluation runs through all 10 stages:
1. Filesystem Repository Acquisition
2. Passive Build Model
3. Source Plan Projection
4. Candidate Ownership Accounting
5. Exact Classpath Resolution
6. Source Decoding
7. Platform Symbol Acquisition
8. Frontend Assembly
9. Semantic Frontend
10. Capability Gap Normalization

## 7. Reporting & Adjudication
- Results are reported mechanically from the raw JSON output.
- Adjudication will review degraded outputs based on `target/g2-output/reason-metrics.json`.
