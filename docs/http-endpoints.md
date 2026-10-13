# HTTP endpoint analysis

`find_framework_endpoints` lists declared Spring MVC/WebFlux annotation mappings and JAX-RS
mappings from compiled bytecode. Each result preserves the JVM descriptor, paths, HTTP methods,
annotation evidence, module/source context, and bounded direct project-call evidence. It describes
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

Remaining limits:

- This implements a tested subset of Spring's merged annotation model, not every implicit or
  convention-based override, transitive implicit alias group, or invalid annotation validation rule.
  See the [AliasFor contract](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/core/annotation/AliasFor.html).
- Inherited mappings, controller/resource registration, conditional beans, profiles, placeholder
  expansion, programmatic/XML mappings, and runtime dispatch are not resolved.
- Media types, headers, params, and full JAX-RS subresource chains are not exposed. `SUBRESOURCE`
  means only a declared method `Path` without a recognized HTTP method. If a custom designator's
  definition is missing, this marker can also represent an unresolved resource method.
- The method filter is case-insensitive and describes explicit declared verbs; automatic HEAD/OPTIONS handling is not
  synthesized. Direct project calls remain static bytecode targets.

Validation includes compiled Java bytecode fixtures, comparison with real Spring merged annotation
results in the Spring fixture module, and MCP filtering/path/evidence tests. No Spring runtime
classes are added to Quill's production dependencies.
