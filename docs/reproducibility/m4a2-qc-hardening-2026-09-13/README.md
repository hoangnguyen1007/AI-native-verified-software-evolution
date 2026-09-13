# M4A.2 QC Hardening — 2026-09-13

## Scope

This record evaluates the four findings in the supplied post-delivery Gemini audit against the M4A.2 implementation. It covers only passive source/type/callable detection and JavaParser integration. It does not change the M4A.2 schema, provider, gap catalog, activation boundary or G3 state.

## Adjudication

| Finding | Decision | Evidence and action |
|---|---|---|
| Outer class containing nested `@interface` is treated as an annotation | **CONFIRMED; fixed** | Integration RED lost the outer class's implicit constructor (`0` instead of `1`). Type kind is now selected from the outer declaration header rather than keyword presence in the complete body. |
| Concrete `ApplicationContext`/`BeanFactory` declaring types can hide container lookups | **CONFIRMED in part; fixed at the exact callable boundary** | Calls resolving to `AbstractApplicationContext`, `AbstractBeanFactory` and `DefaultListableBeanFactory` produced `0/3` lookup obligations. These and the related core context/factory owner types are now in the exact lookup-owner catalog. The suggested addition of `ConfigurableApplicationContext` to programmatic-registration owners was rejected because none of the registered mutation methods is declared by that API. |
| `@AliasFor` lacks JavaParser integration coverage | **CONFIRMED test gap; coverage added** | A compiler-built `AliasFor` stub and real frontend fixture now establish the attribute declaration, default and exact alias observation. The behavior already worked; the original aggregate count assertion was refined because `@AliasFor` itself legitimately contributes a composed-mechanism obligation. |
| Outer interface containing a nested class is treated as instantiable | **CONFIRMED; fixed with the same root-cause repair** | Integration RED classified the interface from a nested `class` token. The new header classifier keeps it non-instantiable and emits no constructor-set gap for the interface. |

The audit's proposed raw `indexOf('{')` remediation was not adopted. Java annotation arguments can contain array braces before the type body, for example `@SuppressWarnings({"unused"})`. The implementation instead tokenizes while ignoring comments/strings and tracks parenthesis/bracket nesting before selecting the outer declaration keyword. The regression fixture includes this brace-bearing header.

## TDD and verification

Initial integration run after adding the QC fixtures: 5 tests, 3 failures. Two failures reproduced the product defects above; the third showed that the old count assertion did not distinguish the custom composed annotation from the newly integrated `@AliasFor` catalog row.

Final commands:

```powershell
$env:MAVEN_USER_HOME='C:\Users\Admin\.m2'
.\mvnw.cmd test -pl analyzer-javaparser -am "-Dtest=SpringMechanismM4A2IntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.repo.local=C:\Users\Admin\.m2\repository" -q

$env:MAVEN_USER_HOME='C:\Users\Admin\.m2'
.\mvnw.cmd test -pl analyzer -am "-Dtest=SpringMechanismInventoryTest,SpringMechanismM4A2Test" "-Dmaven.repo.local=C:\Users\Admin\.m2\repository" -q
```

| Test class | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| `SpringMechanismM4A2IntegrationTest` | 5 | 0 | 0 | 0 |
| `SpringMechanismInventoryTest` | 12 | 0 | 0 | 0 |
| `SpringMechanismM4A2Test` | 1 | 0 | 0 | 0 |
| **Total** | **18** | **0** | **0** | **0** |

No full reactor rerun was performed: the production change is private scanner classification logic with no public-contract or dependency change, and the affected analyzer contracts plus JavaParser integration were exercised directly. No Git inspection was performed, following the user's explicit request to avoid that overhead.

## SHA-256 evidence

```text
f858d541803def95153c101113a702e960b8adddc94fe63b27dcd1b84353cf3b  analyzer/src/main/java/com/evolution/analysis/spring/SpringMechanismScanner.java
955b437df88b73058e3420e2050eaf0d302e8b7d5e47b844818e82ac3e672b78  analyzer-javaparser/src/test/java/com/evolution/analysis/javaparser/SpringMechanismM4A2IntegrationTest.java
```

## Result and limits

**CONFIRMED:** both semantic false classifications and the concrete lookup omission are fixed; `@AliasFor` now has real frontend-to-scanner integration evidence; all affected focused tests pass deterministically against the final source bytes above.

**LIMITATION:** the lookup-owner set remains a versioned exact API catalog rather than proof of complete Spring runtime lookup behavior. M4A.2 still detects calls only and does not infer their runtime target or registration effect.
