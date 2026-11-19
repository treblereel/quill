"""Apollo questions (public). Each AQ-NNN mirrors Cryostat CQ-NNN with a Spring counterpart.

The prompt text is what the tested model sees; oracles live in oracles.py (private).
"""

CONTEXT = (
    "Context: Enumerations are bounded to application-owned production/test source of the tracked "
    "Apollo modules unless dependencies or generated mechanisms are explicitly requested. Packaged "
    "framework evidence supplements those questions. No ambient env/secret values are assumed.\n"
    "Profile: apollo-portal runs with spring.profiles.active=github,auth (github also activates the "
    "mysql profile group); apollo-adminservice and apollo-configservice run with github.\n"
    "Read-only except AQ-049, whose patch must be applied only in a disposable checkout. No permission "
    "to start external services or read process secrets.")

P = 'com.ctrip.framework.apollo.portal'
ROUTE = 'POST /openapi/v1/envs/{env}/apps/{appId}/clusters/{clusterName}/namespaces/{namespaceName}/releases'

# id -> (track, [prompts]); track as in Cryostat: independent | patch | linked.
QUESTIONS = {
    'AQ-001': ('independent', [
        'Which modules make up Apollo, where is its build entry point, and which dependencies bring in Spring Boot '
        'and Spring Cloud?']),
    'AQ-002': ('independent', [
        f'Which beans are declared in the production package {P}.component, directly in that package only, without '
        'subpackages? Separate component classes, @Bean methods and beans produced by a FactoryBean, and give '
        'their names and scope.']),
    'AQ-003': ('independent', [
        f'Why does {P}.component.UnifiedPermissionValidator end up in the Spring context of apollo-portal: which '
        'annotations, stereotypes or component scanning rules make that happen?']),
    'AQ-004': ('independent', [
        'Where does the dependency of type org.springframework.context.ApplicationEventPublisher that '
        'com.ctrip.framework.apollo.openapi.v1.controller.ReleaseController receives come from: which dependency '
        'or Spring mechanism provides it, and is there a bean definition for it?']),
    'AQ-005': ('independent', [
        f'Can Spring skip registering the bean {P}.spi.configuration.AuthConfiguration.SpringSecurityAuthAutoConfiguration'
        '#springSecurityUserInfoHolder(org.springframework.beans.factory.ObjectProvider) under github,auth? Show '
        'the grounds for registering or skipping it and the limits of that conclusion.']),
    'AQ-006': ('independent', [
        'What is injected into the userInfoHolder parameter of the constructor of '
        'com.ctrip.framework.apollo.openapi.v1.controller.ReleaseController under github,auth? Show the candidates '
        'and why this particular bean is chosen.']),
    'AQ-007': ('independent', [
        f'How does Spring create {P}.component.UserTokenPermissionValidator#<init>({P}.component.UserPermissionValidator,'
        f'{P}.service.UserTokenService,{P}.util.UserTokenAuthUtil,{P}.service.RolePermissionService,'
        f'{P}.spi.UserInfoHolder), and which beans satisfy each constructor parameter?']),
    'AQ-009': ('independent', [
        f'Which dependencies does {P}.component.emailbuilder.NormalPublishEmailBuilder get from its superclass, and '
        'which are declared directly? Take overrides into account.']),
    'AQ-010': ('independent', [
        f'Which implementations actually qualify for {P}.component.PermissionValidator#hasReleaseNamespacePermission'
        '(java.lang.String,java.lang.String,java.lang.String,java.lang.String)? Separate Java inheritance from '
        'the bean types of the apollo-portal Spring context.']),
    'AQ-011': ('independent', [
        'Where is org.springframework.security.crypto.password.PasswordEncoder, created by '
        f'{P}.spi.configuration.AuthConfiguration.SpringSecurityAuthAutoConfiguration#passwordEncoder(), injected, '
        'and through which qualifiers? Show direct injection sites and dynamic lookups separately.']),
    'AQ-012': ('independent', [
        f'Why does the qualifier on the fileTextResolver parameter of the constructor of {P}.service.ItemService '
        'match or not match the qualifiers of the candidates of type ConfigTextResolver? Take bean names and the '
        '@Qualifier value into account.']),
    'AQ-013': ('independent', [
        'Which explicit and implicit qualifiers apply to the userInfoHolder parameter of the constructor of '
        'com.ctrip.framework.apollo.openapi.v1.controller.ReleaseController and to its candidates? Explain the '
        'effect of the bean name, the parameter name, @Primary and @Qualifier.']),
    'AQ-014': ('independent', [
        f'Which beans can {P}.spi.springsecurity.SpringSecurityUserInfoHolder return through its '
        'ObjectProvider<UserService>? Trace the lookup and the conditions that make the result depend on runtime.']),
    'AQ-015': ('independent', [
        f'How is the implementation for {P}.listener.ConfigPublishListener#emailService chosen under github,auth: '
        '@Primary, conditions, or a fallback/default bean?']),
    'AQ-016': ('independent', [
        f'What does {P}.spi.configuration.AuthConfiguration.SpringSecurityAuthAutoConfiguration#jdbcUserDetailsManager'
        '(javax.sql.DataSource,jakarta.persistence.EntityManagerFactory) create, and what are the type, name, '
        'qualifiers and scope of the result? Do not mix them up with the properties of the declaring class.']),
    'AQ-018': ('independent', [
        'Which dependencies does '
        f'{P}.spi.configuration.AuthConfiguration.SpringSecurityAuthAutoConfiguration#springSecurityUserService('
        'org.springframework.security.crypto.password.PasswordEncoder,'
        f'{P}.repository.UserRepository,{P}.repository.AuthorityRepository) need to create its result? Show the '
        'parameters of the @Bean method, explicit depends-on and the dependencies of its declaring class.']),
    'AQ-020': ('independent', [
        f'When is {P}.listener.ConfigPublishListener created and destroyed, and which lifecycle callbacks are '
        'declared or inherited? Where is static analysis not enough?']),
    'AQ-023': ('independent', [
        f'How does runtime configuration affect which UserService {P}.spi.springsecurity.SpringSecurityUserInfoHolder'
        '#getUser() returns? What is known in advance, and what is decided only at startup?']),
    'AQ-024': ('independent', [
        'Who registers the bean com.ctrip.framework.apollo.biz.registry.configuration.support.ApolloServiceDiscoveryProperties, '
        'what are its name, types and dependencies, and under which conditions does it appear?']),
    'AQ-025': ('independent', [
        f'Can {P}.listener.ConfigPublishListener#onConfigPublish({P}.listener.ConfigPublishEvent) rely on the '
        'context of the current request (authentication type, current user) in its asynchronous path? '
        'Show the context boundaries and what needs a runtime check.']),
    'AQ-026': ('independent', [
        'Which aspects apply to com.ctrip.framework.apollo.adminservice.controller.ItemController#create('
        'java.lang.String,java.lang.String,java.lang.String,com.ctrip.framework.apollo.common.dto.ItemDTO), in which '
        'order, and through which pointcuts or annotations?']),
    'AQ-027': ('independent', [
        'Where does the transaction begin and end on the path through '
        'com.ctrip.framework.apollo.adminservice.controller.ReleaseController#publish(java.lang.String,java.lang.String,'
        'java.lang.String,java.lang.String,java.lang.String,java.lang.String,boolean), and which calls can change '
        'propagation?']),
    'AQ-028': ('independent', [
        'Which listeners can receive the event published in '
        'com.ctrip.framework.apollo.openapi.v1.controller.ReleaseController#createRelease? Take the event type, '
        'sync/async and the transaction phase into account.']),
    'AQ-029': ('independent', [
        'Which decorators apply to com.ctrip.framework.apollo.biz.registry.DatabaseDiscoveryClient, how is the '
        'delegate chosen, and what determines the order?']),
    'AQ-030': ('independent', [
        'Are there cycles in the dependencies of com.ctrip.framework.apollo.biz.service.NamespaceService? Separate '
        'a cycle in the dependency graph from object creation that is actually impossible.']),
    'AQ-031': ('independent', [
        f'How does the request {ROUTE} in apollo-portal reach the business method and its Spring dependency? Show '
        'one direct path, without adding every transitive dependency.']),
    'AQ-032': ('independent', [
        f'Which access checks apply to {ROUTE} in apollo-portal under github,auth, where do the roles or '
        'permissions come from, and where does configuration change the behaviour?']),
    'AQ-033': ('independent', [
        'What starts the scanning in com.ctrip.framework.apollo.biz.message.ReleaseMessageScanner, which '
        'Spring dependencies are used, and which settings control the schedule?']),
    'AQ-035': ('independent', [
        'What does com.ctrip.framework.apollo.biz.registry.configuration.support.'
        'ApolloServiceRegistryHeartbeatApplicationRunner#run(org.springframework.boot.ApplicationArguments) do at '
        'startup or shutdown, and which beans does it touch? State the proven order and the unknown relations.']),
    'AQ-036': ('independent', [
        'Where is the apollo-portal setting emergencyPublish.supported.envs set and read? Show the default, the '
        'sources of the value under github,auth and the places where it is used.']),
    'AQ-037': ('independent', [
        'Which keys does com.ctrip.framework.apollo.biz.registry.configuration.support.'
        'ApolloServiceRegistryProperties describe, including nested values, collections and default values?']),
    'AQ-038': ('independent', [
        'AQ-038 uses only explicit sources: classpath application.properties of apollo-portal sets '
        'emergencyPublish.supported.envs=dev, and the portal database table ServerConfig holds '
        'emergencyPublish.supported.envs=pro.\n'
        'Which value of emergencyPublish.supported.envs does PortalConfig see under github,auth, without ambient '
        'env/system properties and command-line arguments? Show the precedence of the sources and how it is '
        'established.']),
    'AQ-039': ('independent', [
        'Can emergencyPublish.supported.envs be changed on a running apollo-portal without a rebuild and a '
        'restart? Show the evidence of the behaviour for this version.']),
    'AQ-040': ('independent', [
        'What has to change when the setting emergencyPublish.supported.envs is renamed to '
        'emergencyPublish.allowed.envs? Separate code, configuration and SQL scripts, documentation and tests.']),
    'AQ-041': ('independent', [
        f'What is the direct blast radius of changing the signature of {P}.component.UnifiedPermissionValidator'
        '#hasReleaseNamespacePermission(java.lang.String,java.lang.String,java.lang.String,java.lang.String)? '
        'Separate Java callers, references outside Java code, overrides and interfaces, tests and indirect '
        'dependencies.']),
    'AQ-042': ('independent', [
        f'If the bean {P}.component.txtresolver.FileTextResolver is renamed from "fileTextResolver" to '
        '"fileTextResolverV2", which injection sites and declarations have to be checked or changed?']),
    'AQ-043': ('independent', [
        f'What does a change to the contract of {P}.spi.UserInfoHolder affect: implementations, bean declarations, '
        'injection sites and direct users of its methods?']),
    'AQ-044': ('independent', [
        f'How can the component class {P}.component.UnifiedPermissionValidator be replaced by a @Bean method '
        'without losing the bean name, scope, lifecycle and compatibility for its consumers?']),
    'AQ-045': ('independent', [
        f'What changes if {P}.component.UserPermissionValidator is moved to request scope? Check its state, the '
        'proxy, singleton consumers and the context of calls made outside an HTTP request.']),
    'AQ-046': ('independent', [
        f'Which tests should be run after a change to {P}.component.UnifiedPermissionValidator'
        '#hasReleaseNamespacePermission(java.lang.String,java.lang.String,java.lang.String,java.lang.String), and '
        'why? Separate direct coverage from heuristically relevant tests.']),
    'AQ-047': ('independent', [
        f'How does {P}.controller.ItemControllerAuthIntegrationTest replace Spring dependencies and configuration? '
        'What differs from production, and what does such a test not verify?']),
    'AQ-048': ('independent', [
        'Where does com.ctrip.framework.apollo.openapi.api.ReleaseManagementApi come from, which handwritten input '
        'or plugin controls it, and what should be changed instead of the generated file?']),
    'AQ-049': ('patch', [
        f'Rename {P}.component.UnifiedPermissionValidator#getDelegate() to resolveDelegate, update every place '
        'that needs it, and add or adapt a narrow test. Keep the behaviour and show the verification.\n'
        'The apollo-portal tests run on H2 without external services; run only narrow tests, not the whole reactor.']),
    'AQ-050': ('linked', [
        f'Analyse one user operation {ROUTE} in apollo-portal: the entry point, the DI selection, the settings, '
        'the blast radius of a change and the fitting tests. Work step by step in one session.\n'
        f'Find the entry point of the operation {ROUTE} and its immediate handler.',
        'Trace the selection of Spring dependencies in this operation under github,auth.',
        'Find the related settings and their definitions for this profile.',
        'Separate direct calls, DI links and conditional runtime transitions on this path.',
        f'Draw up a minimal plan for changing the signature of {P}.service.ReleaseService#publish({P}.entity.model.'
        'NamespaceReleaseModel) and the direct verification radius for this operation. A plan only, no code changes.',
        'Choose narrow tests for the plan; note the gaps in evidence and do not repeat the whole earlier analysis.']),
}

# Cryostat CQ cases whose Spring counterpart has no application-owned instance in Apollo.
NOT_APPLICABLE_IDS = ['AQ-008', 'AQ-017', 'AQ-019', 'AQ-021', 'AQ-022', 'AQ-034']
