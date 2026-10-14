# API compatibility review

`compare_index` adds an `api_compatibility` object to the existing structural,
dependency, bean and injection deltas. No new MCP tool is required.
The baseline remains a retained local generation selected by index ID/commit, or
the previous generation by default.

The report compares current main-source-set classes and legacy classes without a
source-set label. Explicit test/custom source sets and orphan outputs are excluded.
Owners are scoped by binary class name, module and source set. Members use kind,
JVM name and full JVM descriptor, including return types; constructor names are
normalized to `<init>`. Public/protected declared members participate in the review.
Missing/malformed descriptors, unknown modifier encodings and ambiguous owner
occurrences produce unknown evidence rather than a compatibility assertion.

## Findings and verdict

`potentially_breaking` findings include class removal, class-kind/hierarchy changes,
declared member removal or descriptor change, visibility narrowing, static/instance
changes, newly final/abstract members and newly added abstract methods.

Kotlin metadata declarations with the same JVM identity and a public/protected
baseline JVM member are also compared. Removing
a default argument, changing a parameter name or vararg marker, changing the
extension/suspend call convention, making a property read-only, widening a result
to nullable or narrowing a nullable input are `potentially_breaking` for Kotlin
source callers. Other retained source-type changes and newly added defaults request
review. A changed default contract with missing parameter metadata is `unknown`.
The report includes the before/after Kotlin declarations for these findings.

`review_required` findings include ordinary member/class additions, visibility
widening, generic/source signature changes visible in indexed types, annotations,
and other modifier changes. An added overload may keep an old binary running while
making an old source call ambiguous. A removed declared descriptor may still link
through an inherited method or a synthetic bridge, so even a removal is a potential
problem rather than a proven linkage failure.

The aggregate `verdict` is `potentially_breaking`, `unknown`, `review_required` or
`no_detected_changes`. `compatibility_proven` is always false. Schema 29 retains
additional JVM API evidence described below. Kotlin checks require retained metadata
on both sides and a matching JVM declaration. Kotlin source visibility, inline
bodies and missing metadata are not analyzed. Class annotation
values are not retained; member annotation values are compared when available.
Even an unchanged declaration surface therefore does not prove compatibility.

## Baseline impact and bounds

Each returned finding contains `baseline_impact`. For a known old member, callers
match the baseline JVM declaration, member name and descriptor, including resolved
inherited method owners. Field accesses retain exact symbolic-owner matching.
Class-level changes/additions use class dependency evidence. Direct subclasses and
implementors are shown separately because final/abstract changes can matter even
without a call instruction. Consumers retain baseline class IDs, module/source-set
labels and method descriptors, avoiding overload and same-name record conflation.

Impact describes the baseline even if a caller has disappeared from the current
index. Added owners have no baseline impact and report unavailable. Counts remain
complete for the queried static evidence while consumer/subtype arrays are bounded
by the existing `limit`; every truncated array has an explicit marker. Findings are
ordered deterministically, and total finding/category counts precede truncation.
`baseline_impact.complete` is false: external consumers, reflection, transitive
subtypes and unresolved inherited references remain unknown. An empty
consumer list is not proof that no consumer exists.

The structural `classes.modified` fingerprint also compares JVM descriptors and
member annotation details, so descriptor-only deltas are not hidden by an identical
human-readable signature.

## Validation

Tests exercise overload identity, field accesses, `<init>` calls, public/protected
visibility, static/final/abstract changes, additions, type/annotation changes,
order-only differences, missing/malformed descriptors, duplicate owners, test/orphan
filtering, baseline-only consumers, truncation, deterministic ordering and impact
cache isolation when baseline/current database IDs coincide. Kotlin tests cover
default removal/addition, named parameters, nullability direction, call convention,
property mutability, duplicate accessor suppression, private-member exclusion and
incomplete parameter data.

Independent javac fixtures run clients compiled against the old API with the new
API: removal produces `NoSuchMethodError`, static-to-instance change produces
`IncompatibleClassChangeError`, a generated bridge preserves covariant-return binary
linkage, and an added overload preserves a binary client but prevents recompiling
its `null` call. These cases verify the limits of the conservative classification.
The existing `QuillToolsTest` checks that the new object is present without losing
the prior structural/resolution response fields.

## Retained JVM API evidence

Index schema 29 captures class/inner-class access, final and abstract flags, raw
class and member `Signature` attributes, declared exceptions, ConstantValue
attributes and synthetic/bridge member flags from the same immutable class-file
snapshot as the call graph. Fresh and incremental writes publish this evidence
atomically with the index. Earlier indexes must be rebuilt. Duplicate output
owners are explicitly ambiguous rather than merged.

The report emits `CLASS_VISIBILITY_NARROWED`, `CLASS_FINAL_ADDED` and
`CLASS_ABSTRACT_ADDED` as potentially breaking changes. Generic signatures and
`THROWS_CHANGED` are source-contract review findings; unchanged descriptors do
not prove behavioral or source compatibility. `CONSTANT_VALUE_CHANGED` warns
that compiled consumers retain their old inlined values until recompiled; an
empty baseline usage count cannot identify all such consumers. Floating-point
constants retain raw bits, including signed zero, NaN and infinity.

Public/protected synthetic JVM members are included in API comparisons, while
ordinary source navigation hides compiler bridges. A preserved bridge descriptor
is no longer reported as a removed member. Its source contract still requires
review. Baseline method impact includes indexed inherited symbolic owners that
resolve to the changed declaration; runtime override selection is not inferred.

`jvm_api_coverage` reports captured and missing owner counts. One-sided missing
or malformed API capture emits `JVM_API_EVIDENCE_INCOMPLETE`; missing evidence on
both sides still limits the result. Class-loader/module access, enclosing-class
accessibility, reflective consumers, annotation defaults and full consumer
recompilation remain outside this declaration comparison. `compatibility_proven`
remains false.
