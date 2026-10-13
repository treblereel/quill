# HTTP endpoint analysis

`find_framework_endpoints` lists declared Spring MVC/WebFlux annotation mappings and JAX-RS
mappings from compiled bytecode. Each result preserves the JVM descriptor, paths, HTTP methods,
annotation evidence, module/source context, and bounded direct project-call evidence.
`declaring_class` identifies the method body owner; `mapping_sources` identifies the retained
class and method declarations that supplied the route annotations. It describes
candidate declarations; it does not prove that a route is registered or reachable at runtime.

Spring supports standard `RequestMapping` and method-specific mappings, custom meta-annotations,
constant paths and request-method arrays, annotation defaults, and explicit `AliasFor` overrides.
Aliases can target `RequestMapping` directly or another annotation in the meta chain. Explicit
overrides for the same meta attribute share a supplied non-empty value; conflicting
non-empty values are omitted. An alias's empty array default overrides a fixed meta path,
matching Spring's merged annotation behavior. Overriding `path` also replaces the root `value`
alias, and vice versa. Class and
method paths form combinations; their declared HTTP method sets form a union, as in Spring's
[RequestMethodsRequestCondition](https://github.com/spring-projects/spring-framework/blob/v6.2.0/spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/condition/RequestMethodsRequestCondition.java).

Annotation definitions are looked up in project and indexed dependency bytecode. Dependency
classes are not promoted to endpoint owners. Missing definitions leave standard mappings available
but make custom annotations unresolved. Traversal stops at 32 annotation levels and 512 visits per
root annotation; cycles terminate. Multiple Spring mappings on one element and conflicting
`path`/`value` aliases are omitted instead of inventing their runtime precedence.

Both `jakarta.ws.rs` and `javax.ws.rs` standard verbs and `Path` are supported. Custom request
method designators use one direct `HttpMethod` meta-annotation and a valid HTTP token, as specified
by [Jakarta REST](https://jakarta.ee/specifications/restful-ws/3.1/apidocs/jakarta.ws.rs/jakarta/ws/rs/HttpMethod.html).
A custom annotation's own `value` attribute is not interpreted as a route path. Mixed Spring and
JAX-RS declarations produce separate records and preserve each framework's paths and verbs.

## Inherited declarations

Spring type and method mappings follow the indexed type hierarchy: direct mappings take precedence,
then interface contracts, then superclass mappings. An unannotated override can inherit its mapping.
For an inherited body, Spring's method annotation search starts at the body's declaring type;
adding a new interface only on the resource subclass does not fabricate annotations on that method.
Multiple conflicting interface branches are conservatively omitted instead of selecting a route
based on their declaration order. A shared diamond ancestor is processed once.

JAX-RS method and parameter annotations are inherited only when the implementation method and its
parameters have no JAX-RS annotations of their own. A direct `Produces`, `QueryParam`, or `Path`
therefore suppresses all inherited method metadata; a non-JAX-RS annotation does not. Superclass
annotations precede interface annotations. Conflicting unrelated interfaces are omitted, while a
more specific interface overrides its ancestor. JAX-RS interface lookup uses the resource class,
even when an unannotated body is inherited from a base class. Type-level `Path` is read from the resource class
itself, as required by the [Jakarta REST inheritance rules](https://jakarta.ee/specifications/restful-ws/4.0/jakarta-restful-ws-spec-4.0.html#annotation_inheritance).

Method matching substitutes class generic arguments through the hierarchy, including arrays and
method-variable bounds, and ignores covariant return differences when identifying overrides.
Bridge methods do not create extra endpoints. An inherited body keeps its original erased JVM
descriptor and declaration owner. Private and static methods are not inherited; package-private
inheritance is interrupted by a different package. Interface default bodies are supported. JAX-RS
candidate resource methods must be public.

Hierarchy traversal is limited to 64 levels and 128 indexed types, with cycle detection. Missing
ancestors make inherited evidence unavailable. A direct Spring type mapping plus a direct method
mapping remain analyzable; without a direct type mapping, an incomplete hierarchy cannot establish
an inherited Spring prefix and the candidate is omitted. Missing JDK ancestors other than Object
also count as incomplete. This is conservative and can omit legitimate controller declarations.

The response separates the resource owner from the body declaration. Direct calls are queried
using the declaration class ID and original method descriptor. For a body in an external dependency
without a project class ID, `direct_call_targets_indexed` is false and `direct_project_call_count`
is null. An empty details array in this case does not prove an empty call set. Older index metadata
without declaration fields keeps its previous owner-based interpretation; refresh the index to
obtain inherited routes and their provenance.

Validation compares representative inherited Spring mappings with the actual
[TYPE_HIERARCHY annotation stream used by Spring's handler mapping](https://github.com/spring-projects/spring-framework/blob/v6.2.19/spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/RequestMappingHandlerMapping.java).
JAX-RS inheritance cases are bytecode fixtures checked against the specification; they are not
acceptance tests against every JAX-RS runtime.

Remaining limits:

- This implements a tested subset of Spring's merged annotation model, not every implicit or
  convention-based override, transitive implicit alias group, or invalid annotation validation rule.
  See the [AliasFor contract](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/core/annotation/AliasFor.html).
- Incomplete or ambiguous hierarchy contracts, controller/resource registration, conditional beans,
  profiles, placeholder expansion, programmatic/XML mappings, and runtime dispatch are not resolved.
- Media types, headers, params, and full JAX-RS subresource chains are not exposed. `SUBRESOURCE`
  means only a declared method `Path` without a recognized HTTP method. If a custom designator's
  definition is missing, this marker can also represent an unresolved resource method.
- The method filter is case-insensitive and describes explicit declared verbs; automatic HEAD/OPTIONS handling is not
  synthesized. Direct project calls remain static bytecode targets.

Validation includes compiled Java bytecode fixtures, comparison with real Spring merged annotation
results in the Spring fixture module, and MCP filtering/path/evidence tests. No Spring runtime
classes are added to Quill's production dependencies.
