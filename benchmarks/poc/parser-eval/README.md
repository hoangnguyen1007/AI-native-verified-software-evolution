# R1 Parser Viability PoC

This directory contains the Proof of Concept evaluator for testing JavaParser + JavaSymbolSolver on Spring PetClinic (Task 1.1 / R1).

## Provenance
- **Target Repository**: Spring PetClinic
- **Commit**: `818c4136ea971c21674525f9053de0d9c7ad8cfe`
- **Java Runtime**: JDK 17 (or JDK 21)
- **Compiler**: Java 21
- **Parser Level**: `JAVA_21`
- **PoC Version**: 1.0

## Directory Contents
- `src/` - Evaluator source code
- `pom.xml` - Evaluator dependencies and Java 21 compiler configuration
- `ground-truth.json` - 14 manually verified relationships for PetClinic
- `configA-java21-results.json` - Raw results from Config A execution
- `configB-java21-results.json` - Raw results from Config B execution

## Reproducibility Instructions

### 1. Preparation
Clone Spring PetClinic into a local directory inside this folder (ignored by git):
```bash
git clone --depth 1 https://github.com/spring-projects/spring-petclinic.git target-repo
cd target-repo
git checkout 818c4136ea971c21674525f9053de0d9c7ad8cfe
```

### 2. Fetch Dependencies (For Config B)
```bash
cd target-repo
mvn dependency:copy-dependencies "-DoutputDirectory=../dependencies"
cd ..
```

### 3. Build and Test the Evaluator
Ensure you are using a JDK 21 compiler.
```bash
mvn clean test package
```

### 4. Run Configuration A (Source + JDK Only)
```bash
java -cp target/parser-eval-1.0-SNAPSHOT-jar-with-dependencies.jar com.evolution.poc.ParserEvalApp target-repo/src/main/java my-configA-results.json
```

### 5. Run Configuration B (Source + Dependencies)
```bash
java -cp target/parser-eval-1.0-SNAPSHOT-jar-with-dependencies.jar com.evolution.poc.ParserEvalApp target-repo/src/main/java my-configB-results.json dependencies
```
