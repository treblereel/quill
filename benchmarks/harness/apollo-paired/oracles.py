"""Apollo oracles (private; never readable by a tested model).

Each oracle states the expected facts, the source evidence (path + regexes that must match at the
pinned revision; build_data.py resolves line numbers and sha256), claims that are wrong, bounded
unknowns, and whether anything was proven at runtime. Facts come from inspected source, tracked-file
inventories and javap of the packaged framework jars, never from a Quill answer.
"""

PORTAL = 'apollo-portal/src/main/java/com/ctrip/framework/apollo/portal'
OPENAPI = 'apollo-portal/src/main/java/com/ctrip/framework/apollo/openapi'
BIZ = 'apollo-biz/src/main/java/com/ctrip/framework/apollo/biz'
ADMIN = 'apollo-adminservice/src/main/java/com/ctrip/framework/apollo/adminservice'
COMMON = 'apollo-common/src/main/java/com/ctrip/framework/apollo/common'
AUTH = f'{PORTAL}/spi/configuration/AuthConfiguration.java'
OPEN_RELEASE = f'{OPENAPI}/v1/controller/ReleaseController.java'
SPRING_CONTEXT = 'org.springframework:spring-context:7.0.9'

ORACLES = {}


def oracle(case, expected, evidence, forbidden=(), limitations=(), runtime_proven=False, framework=()):
    ORACLES[case] = {'expected': expected, 'evidence': list(evidence), 'framework_evidence': list(framework),
                     'forbidden_claims': list(forbidden), 'limitations': list(limitations),
                     'runtime_proven': runtime_proven}


oracle('AQ-001', {
    'build_entrypoints': ['pom.xml (reactor root, no <parent>)', 'mvnw'],
    'modules': ['apollo-build-sql-converter', 'apollo-buildtools', 'apollo-common', 'apollo-biz',
                'apollo-configservice', 'apollo-adminservice', 'apollo-portal', 'apollo-assembly',
                'apollo-audit (aggregator: apollo-audit-annotation, apollo-audit-impl, apollo-audit-api, '
                'apollo-audit-spring-boot-starter)'],
    'spring_versions': {'spring-boot.version': '4.1.1', 'spring-cloud.version': '2025.1.3',
                        'spring-cloud-alibaba.version': '2025.1.0.0',
                        'mechanism': 'spring-boot-dependencies imported as a BOM in dependencyManagement; '
                                     'spring-boot-maven-plugin at ${spring-boot.version}'},
    'spring_entry_dependencies': {
        'apollo-common': 'spring-boot-starter-web, -actuator, -validation, -security, -data-jpa',
        'apollo-biz': 'apollo-common plus spring-cloud-starter-netflix-eureka-client, consul-discovery, '
                      'zookeeper-discovery, apollo-audit-spring-boot-starter',
        'apollo-configservice / apollo-adminservice': 'apollo-biz plus spring-cloud-starter-netflix-eureka-server, '
                                                      'archaius, ribbon, alibaba nacos-discovery',
        'apollo-portal': 'apollo-common, apollo-audit-spring-boot-starter, spring-boot-starter-oauth2-client, '
                         'oauth2-resource-server, spring-security-ldap, spring-boot-session-jdbc/-data-redis',
        'apollo-audit-spring-boot-starter': 'apollo-audit-impl plus spring-boot-autoconfigure',
    },
    'applications': ['ConfigServiceApplication (apollo-configservice)', 'AdminServiceApplication (apollo-adminservice)',
                     'PortalApplication (apollo-portal)', 'ApolloApplication (apollo-assembly)'],
}, [
    ('pom.xml', [r'<module>apollo-portal</module>', r'<spring-boot.version>4\.1\.1<',
                 r'<artifactId>spring-boot-dependencies</artifactId>', r'<spring-cloud.version>']),
    ('apollo-audit/pom.xml', [r'<module>apollo-audit-annotation</module>']),
    ('apollo-common/pom.xml', [r'spring-boot-starter-web<']),
    ('apollo-biz/pom.xml', [r'spring-cloud-starter-netflix-eureka-client<']),
    ('apollo-portal/pom.xml', [r'spring-boot-starter-oauth2-client<']),
], forbidden=['Apollo inherits from spring-boot-starter-parent',
              'apollo-buildtools is a Spring application module'])

oracle('AQ-002', {
    'package': 'com.ctrip.framework.apollo.portal.component (direct members only)',
    'component_classes': [
        {'class': 'AdminServiceAddressLocator', 'name': 'adminServiceAddressLocator', 'scope': 'singleton'},
        {'class': 'ConfigReleaseWebhookNotifier', 'name': 'configReleaseWebhookNotifier', 'scope': 'singleton'},
        {'class': 'ItemsComparator', 'name': 'itemsComparator', 'scope': 'singleton'},
        {'class': 'PortalSettings', 'name': 'portalSettings', 'scope': 'singleton'},
        {'class': 'RestTemplateFactory', 'name': '&restTemplateFactory (the FactoryBean itself)', 'scope': 'singleton'},
        {'class': 'RetryableRestTemplate', 'name': 'retryableRestTemplate', 'scope': 'singleton'},
        {'class': 'UnifiedPermissionValidator', 'name': 'unifiedPermissionValidator', 'scope': 'singleton'},
        {'class': 'UserPermissionValidator', 'name': 'userPermissionValidator', 'scope': 'singleton'},
        {'class': 'UserTokenPermissionValidator', 'name': 'userTokenPermissionValidator', 'scope': 'singleton'},
    ],
    'factory_bean_products': [{'factory': 'RestTemplateFactory', 'product_type': 'org.springframework.web.client.RestTemplate',
                               'name': 'restTemplateFactory', 'singleton': 'isSingleton() of the factory'}],
    'bean_methods': [],
    'not_beans': ['AbstractPermissionValidator (abstract)', 'PermissionValidator (interface)',
                  'UserIdentityContextHolder (final utility, static ThreadLocal)'],
    'excluded_subpackages': ['component.config (PortalConfig, SpringSessionConfig, ...)', 'component.emailbuilder',
                             'component.txtresolver'],
}, [
    (f'{PORTAL}/component/RestTemplateFactory.java', [r'^@Component', r'implements FactoryBean<RestTemplate>',
                                                     r'public boolean isSingleton']),
    (f'{PORTAL}/component/UnifiedPermissionValidator.java', [r'@Component\("unifiedPermissionValidator"\)']),
    (f'{PORTAL}/component/UserPermissionValidator.java', [r'@Component\("userPermissionValidator"\)']),
    (f'{PORTAL}/component/UserTokenPermissionValidator.java', [r'@Component\("userTokenPermissionValidator"\)']),
    (f'{PORTAL}/component/PortalSettings.java', [r'^@Component']),
    (f'{PORTAL}/component/UserIdentityContextHolder.java', [r'public final class UserIdentityContextHolder']),
], forbidden=['RestTemplateFactory is only a RestTemplate bean (the factory object is a separate &-prefixed bean)',
              'PortalConfig is declared in this package'])

oracle('AQ-003', {
    'stereotype': '@Component("unifiedPermissionValidator") directly on the class; bean name is explicit',
    'scanning': 'PortalApplication: @ComponentScan(basePackageClasses = {ApolloCommonConfig.class, '
                'PortalApplication.class, PortalOpenApiConfig.class}) covers com.ctrip.framework.apollo.portal',
    'bootstrapping': 'PortalApplication is the SpringApplication.run source (@Configuration, @EnableAutoConfiguration)',
    'conditions': 'no @Profile/@Conditional on the class: registered in every profile',
    'consumers_by_name': 'SpEL @PreAuthorize("@unifiedPermissionValidator....") depends on the explicit bean name',
}, [
    (f'{PORTAL}/component/UnifiedPermissionValidator.java', [r'@Component\("unifiedPermissionValidator"\)']),
    (f'{PORTAL}/PortalApplication.java', [r'@ComponentScan\(basePackageClasses', r'SpringApplication\.run']),
], forbidden=['UnifiedPermissionValidator is registered by a @Bean method',
              'it is only active under the auth profile'])

oracle('AQ-004', {
    'injection_site': 'openapi ReleaseController constructor parameter ApplicationEventPublisher publisher',
    'provider': 'the ApplicationContext itself: AbstractApplicationContext#prepareBeanFactory calls '
                'beanFactory.registerResolvableDependency(ApplicationEventPublisher.class, this)',
    'artifact': SPRING_CONTEXT,
    'bean_definition': 'none; a resolvable dependency is not a bean definition and is not returned by getBeansOfType',
    'delivery': 'publishEvent multicasts synchronously through the context ApplicationEventMulticaster',
}, [
    (OPEN_RELEASE, [r'final ApplicationEventPublisher publisher\)', r'publisher\.publishEvent\(event\)']),
], framework=[{'artifact': SPRING_CONTEXT, 'class': 'org.springframework.context.support.AbstractApplicationContext',
               'method': 'prepareBeanFactory', 'fact': 'registerResolvableDependency(ApplicationEventPublisher.class, this)'}],
    forbidden=['a @Bean method in Apollo produces the ApplicationEventPublisher',
               'Spring Boot auto-configuration defines an ApplicationEventPublisher bean'])

oracle('AQ-005', {
    'declaration': 'AuthConfiguration.SpringSecurityAuthAutoConfiguration (static nested @Configuration, '
                   '@Profile("auth")) #springSecurityUserInfoHolder(ObjectProvider<UserService>)',
    'conditions': ['@Profile("auth") on the declaring class: satisfied by github,auth',
                   '@ConditionalOnMissingBean(UserInfoHolder.class): no other UserInfoHolder bean definition in '
                   'application-owned production source is active under github,auth'],
    'excluded_competitors': [
        'DefaultAuthAutoConfiguration#defaultUserInfoHolder: @ConditionalOnMissingProfile({"ctrip","auth","ldap","oidc"})',
        'SpringSecurityLDAPAuthAutoConfiguration#springSecurityUserInfoHolder: @Profile("ldap")',
        'OidcAuthAutoConfiguration#oidcUserInfoHolder: @Profile("oidc")'],
    'verdict': 'registered under github,auth unless an extra UserInfoHolder bean is contributed from outside the '
               'application source',
    'guard': 'AuthConfiguration#portalAuthenticationValidator (SmartInitializingSingleton, @Lazy(false)) fails '
             'startup if the resolved UserInfoHolder is DefaultUserInfoHolder',
}, [
    (AUTH, [r'@Profile\("auth"\)', r'@ConditionalOnMissingBean\(UserInfoHolder\.class\)',
            r'public UserInfoHolder springSecurityUserInfoHolder', r'@ConditionalOnMissingProfile\(\{"ctrip", "auth", "ldap", "oidc"\}\)',
            r'public SmartInitializingSingleton portalAuthenticationValidator', r'instanceof DefaultUserInfoHolder']),
    (f'{COMMON}/condition/ConditionalOnMissingProfile.java', [r'@Conditional\(OnProfileCondition\.class\)']),
], limitations=['@ConditionalOnMissingBean in a non-auto-configuration class depends on bean definition '
                'registration order; a UserInfoHolder registered later or by an external jar is not proven absent'],
    forbidden=['the bean is always skipped because DefaultUserInfoHolder exists'])

oracle('AQ-006', {
    'required_type': 'com.ctrip.framework.apollo.portal.spi.UserInfoHolder',
    'candidates_under_github_auth': ['springSecurityUserInfoHolder: SpringSecurityUserInfoHolder from '
                                     'SpringSecurityAuthAutoConfiguration (@Profile("auth"), @ConditionalOnMissingBean)'],
    'inactive_candidates': ['defaultUserInfoHolder (excluded by @ConditionalOnMissingProfile incl. auth)',
                            'ldap springSecurityUserInfoHolder (@Profile("ldap"))', 'oidcUserInfoHolder (@Profile("oidc"))'],
    'resolution': 'single active candidate by type; constructor autowiring, no qualifier needed',
    'runtime_behaviour': 'SpringSecurityUserInfoHolder#getUser reads SecurityContextHolder and looks the user up '
                         'through ObjectProvider<UserService>',
}, [
    (OPEN_RELEASE, [r'final UserInfoHolder userInfoHolder, final ApplicationEventPublisher publisher\)']),
    (AUTH, [r'public UserInfoHolder springSecurityUserInfoHolder', r'public DefaultUserInfoHolder defaultUserInfoHolder',
            r'public UserInfoHolder oidcUserInfoHolder']),
    (f'{PORTAL}/spi/springsecurity/SpringSecurityUserInfoHolder.java', [r'SecurityContextHolder\.getContext\(\)']),
], forbidden=['DefaultUserInfoHolder is injected under github,auth', 'the injection is ambiguous between three beans'])

oracle('AQ-007', {
    'creation': 'constructor injection: single public constructor, no @Autowired needed; @Component("userTokenPermissionValidator") singleton',
    'parameters': {
        'UserPermissionValidator': 'userPermissionValidator (@Component, concrete class, unique by type)',
        'UserTokenService': 'userTokenService (@Service)',
        'UserTokenAuthUtil': 'userTokenAuthUtil (@Service)',
        'RolePermissionService': 'rolePermissionService: RoleConfiguration#rolePermissionService() @Bean returning '
                                 'DefaultRolePermissionService (DefaultRolePermissionService itself is not a component)',
        'UserInfoHolder': 'springSecurityUserInfoHolder under github,auth (see AQ-006)',
    },
    'order': 'each parameter bean is created (or fetched) before the constructor runs; no @Lazy parameters',
}, [
    (f'{PORTAL}/component/UserTokenPermissionValidator.java', [r'public UserTokenPermissionValidator\(final UserPermissionValidator']),
    (f'{PORTAL}/service/UserTokenService.java', [r'^@Service']),
    (f'{PORTAL}/util/UserTokenAuthUtil.java', [r'^@Service']),
    (f'{PORTAL}/spi/configuration/RoleConfiguration.java', [r'public RolePermissionService rolePermissionService\(\)']),
    (f'{PORTAL}/spi/defaultimpl/DefaultRolePermissionService.java', [r'public class DefaultRolePermissionService implements RolePermissionService']),
], forbidden=['DefaultRolePermissionService is discovered by component scanning'])

EMAIL = f'{PORTAL}/component/emailbuilder'

oracle('AQ-009', {
    'superclass': 'ConfigPublishEmailBuilder (abstract, not a bean) declares five @Autowired fields',
    'inherited_dependencies': [
        'RolePermissionService rolePermissionService (private; rolePermissionService @Bean from RoleConfiguration)',
        'ReleaseService releaseService (private; portal ReleaseService @Service)',
        'AppNamespaceService appNamespaceService (private; portal AppNamespaceService @Service)',
        'UserService userService (private; springSecurityUserService under github,auth)',
        'PortalConfig portalConfig (protected; used directly by the subclass)'],
    'injection_mechanism': 'field injection by AutowiredAnnotationBeanPostProcessor, which walks the class hierarchy, so private '
                           'superclass fields of the @Component subclass are injected',
    'direct_dependencies': [],
    'overrides': ['subject(), emailContent(Env, ReleaseHistoryBO) (delegates to renderEmailCommonContent), getTemplateFramework() '
                  'and getDiffModuleTemplate() (both read portalConfig)'],
    'inherited_behaviour': 'build(Env, ReleaseHistoryBO) and renderEmailCommonContent use releaseService, appNamespaceService and '
                           'rolePermissionService from the superclass',
}, [
    (f'{EMAIL}/NormalPublishEmailBuilder.java', [r'^@Component', r'extends ConfigPublishEmailBuilder',
                                                 r'return portalConfig\.emailTemplateFramework\(\)']),
    (f'{EMAIL}/ConfigPublishEmailBuilder.java', [r'public abstract class ConfigPublishEmailBuilder',
                                                 r'private RolePermissionService rolePermissionService;',
                                                 r'private ReleaseService releaseService;', r'private AppNamespaceService appNamespaceService;',
                                                 r'private UserService userService;', r'protected PortalConfig portalConfig;']),
], forbidden=['private superclass fields are not injected', 'NormalPublishEmailBuilder has constructor-injected dependencies'])

oracle('AQ-010', {
    'java_hierarchy': {
        'declaration': 'PermissionValidator (interface, abstract method)',
        'implementations': ['AbstractPermissionValidator#hasReleaseNamespacePermission (concrete)',
                            'ConsumerPermissionValidator (overrides, calls super)',
                            'UserTokenPermissionValidator (implements directly, delegates to UserPermissionValidator + token scope)',
                            'UnifiedPermissionValidator (implements directly, delegates by auth type)',
                            'UserPermissionValidator (inherits the AbstractPermissionValidator implementation)'],
    },
    'spring_beans_of_type_PermissionValidator': ['unifiedPermissionValidator', 'userPermissionValidator',
                                                 'userTokenPermissionValidator', 'consumerPermissionValidator'],
    'not_beans': ['AbstractPermissionValidator (abstract)'],
    'injection_consequence': 'four beans share the interface type and none is @Primary: consumers inject concrete '
                             'classes (mostly UnifiedPermissionValidator); a plain PermissionValidator injection would be ambiguous',
}, [
    (f'{PORTAL}/component/PermissionValidator.java', [r'boolean hasReleaseNamespacePermission']),
    (f'{PORTAL}/component/AbstractPermissionValidator.java', [r'public boolean hasReleaseNamespacePermission']),
    (f'{OPENAPI}/auth/ConsumerPermissionValidator.java', [r'@Component\("consumerPermissionValidator"\)',
                                                         r'public boolean hasReleaseNamespacePermission']),
    (f'{PORTAL}/component/UserTokenPermissionValidator.java', [r'public boolean hasReleaseNamespacePermission']),
    (f'{PORTAL}/component/UnifiedPermissionValidator.java', [r'public boolean hasReleaseNamespacePermission']),
    (f'{PORTAL}/component/UserPermissionValidator.java', [r'extends AbstractPermissionValidator']),
], forbidden=['UserPermissionValidator declares its own hasReleaseNamespacePermission',
              'PermissionValidator has a single bean'])

SPRING_SECURITY_CONFIG = 'org.springframework.security:spring-security-config:7.1.1'
ITEM_SERVICE = f'{PORTAL}/service/ItemService.java'
SS_HOLDER = f'{PORTAL}/spi/springsecurity/SpringSecurityUserInfoHolder.java'

oracle('AQ-011', {
    'producer': 'static @Bean passwordEncoder() in SpringSecurityAuthAutoConfiguration (@Profile("auth")), '
                '@ConditionalOnMissingBean(PasswordEncoder.class); returns ApolloPasswordEncoderFactory.createDelegatingPasswordEncoder()',
    'bean_name': 'passwordEncoder',
    'qualifiers': 'none declared; implicit bean-name qualifier "passwordEncoder"',
    'direct_injection_sites': ['AuthConfiguration.SpringSecurityAuthAutoConfiguration#springSecurityUserService(PasswordEncoder, ...) '
                               'parameter, then passed to the SpringSecurityUserService constructor (a plain object, not scanned)'],
    'dynamic_lookups': ['Spring Security InitializeUserDetailsBeanManagerConfigurer looks up a PasswordEncoder bean by type '
                        '(getBeanOrNull) for the DaoAuthenticationProvider built from the single UserDetailsService '
                        '(jdbcUserDetailsManager)'],
    'not_injection_sites': ['PasswordEncoderAdapter is constructed with new inside ApolloPasswordEncoderFactory',
                            'oidc passwordEncoder() and configservice NoOpPasswordEncoder are other producers in other profiles/modules'],
}, [
    (AUTH, [r'@ConditionalOnMissingBean\(PasswordEncoder\.class\)', r'public static PasswordEncoder passwordEncoder\(\)',
            r'public UserService springSecurityUserService\(PasswordEncoder passwordEncoder']),
    (f'{PORTAL}/spi/springsecurity/ApolloPasswordEncoderFactory.java', [r'new PasswordEncoderAdapter\(']),
], framework=[{'artifact': SPRING_SECURITY_CONFIG,
               'class': 'org.springframework.security.config.annotation.authentication.configuration.'
                        'InitializeUserDetailsBeanManagerConfigurer$InitializeUserDetailsManagerConfigurer',
               'method': 'configure / getBeanOrNull', 'fact': 'by-type lookups of UserDetailsService and PasswordEncoder beans'}],
    forbidden=['PasswordEncoder is injected with @Qualifier', 'SpringSecurityUserService is a scanned @Service'])

oracle('AQ-012', {
    'injection_site': 'ItemService constructor parameter @Qualifier("fileTextResolver") ConfigTextResolver fileTextResolver',
    'candidates_by_type': ['fileTextResolver: FileTextResolver (@Component("fileTextResolver"))',
                           'propertyResolver: PropertyResolver (@Component("propertyResolver"))'],
    'match': '@Qualifier value "fileTextResolver" equals the bean name of FileTextResolver; Spring treats a bean name as '
             'its default qualifier, so exactly FileTextResolver matches and PropertyResolver does not',
    'sibling_site': 'the propertyResolver parameter is qualified "propertyResolver" and resolves to PropertyResolver',
    'without_qualifier': 'two candidates and no @Primary; Spring would fall back to the parameter name, which also matches '
                         '(the root pom enables maven-compiler <parameters>true</parameters>)',
}, [
    ('pom.xml', [r'<parameters>true</parameters>']),
    (ITEM_SERVICE, [r'@Qualifier\("fileTextResolver"\) ConfigTextResolver fileTextResolver',
                    r'@Qualifier\("propertyResolver"\) ConfigTextResolver propertyResolver']),
    (f'{PORTAL}/component/txtresolver/FileTextResolver.java', [r'@Component\("fileTextResolver"\)']),
    (f'{PORTAL}/component/txtresolver/PropertyResolver.java', [r'@Component\("propertyResolver"\)']),
], forbidden=['the qualifier is a custom @Qualifier annotation type', 'both resolvers match the qualifier'])

oracle('AQ-013', {
    'injection_site_qualifiers': 'none explicit; parameter name "userInfoHolder"',
    'candidate': 'springSecurityUserInfoHolder (bean name = @Bean method name; implicit qualifier value is that name)',
    'primary': 'no @Primary on any UserInfoHolder producer',
    'parameter_name': 'does not equal the bean name; irrelevant because only one candidate is active',
    'inactive_candidates': 'defaultUserInfoHolder, ldap springSecurityUserInfoHolder, oidcUserInfoHolder are not '
                           'registered under github,auth, so they never take part in qualifier matching',
}, [
    (OPEN_RELEASE, [r'final UserInfoHolder userInfoHolder, final ApplicationEventPublisher publisher\)']),
    (AUTH, [r'public UserInfoHolder springSecurityUserInfoHolder']),
], forbidden=['the parameter name selects the bean', 'a @Primary UserInfoHolder exists'])

oracle('AQ-014', {
    'lookup': 'SpringSecurityUserInfoHolder(ObjectProvider<UserService>) stores userServiceProvider::getObject; every '
              'getUser() calls getObject() again',
    'candidates_under_github_auth': ['springSecurityUserService: SpringSecurityUserService (@Bean, @Profile("auth"), '
                                     '@ConditionalOnMissingBean(UserService.class), @DependsOn("jdbcUserDetailsManager"))'],
    'other_profiles': ['ldap: LdapUserService', 'oidc: OidcLocalUserServiceImpl', 'default: DefaultUserService'],
    'runtime_dependence': ['which UserService exists is fixed at startup by active profiles and the missing-bean conditions',
                           'getObject() throws if no unique UserService is available',
                           'the user looked up depends on SecurityContextHolder for the current thread'],
    'why_provider': 'defers resolution to call time instead of constructor time',
}, [
    (SS_HOLDER, [r'this\(userServiceProvider::getObject\)', r'this\.userServiceSupplier\.get\(\)\.findByUserId']),
    (AUTH, [r'@DependsOn\("jdbcUserDetailsManager"\)', r'public UserService springSecurityUserService\(PasswordEncoder',
            r'public UserService defaultUserService\(\)', r'public UserService springSecurityUserService\(LdapTemplate']),
], forbidden=['the provider resolves once in the constructor', 'getIfAvailable silently returns null'])

oracle('AQ-015', {
    'injection_site': 'ConfigPublishListener constructor parameter EmailService emailService',
    'producer': 'EmailConfiguration#defaultEmailService() @Bean with @ConditionalOnMissingBean(EmailService.class), '
                'returning DefaultEmailService',
    'selection': 'no @Primary, no @Profile: the default implementation is a fallback that applies when no other '
                 'EmailService bean is defined; none is defined in application-owned production source',
    'note': 'DefaultEmailService receives PortalConfig through field @Resource although it is created by new in a @Bean method; '
            'send() is a no-op unless portalConfig.isEmailEnabled()',
}, [
    (f'{PORTAL}/listener/ConfigPublishListener.java', [r'final EmailService emailService']),
    (f'{PORTAL}/spi/configuration/EmailConfiguration.java', [r'@ConditionalOnMissingBean\(EmailService\.class\)',
                                                            r'return new DefaultEmailService\(\)']),
    (f'{PORTAL}/spi/defaultimpl/DefaultEmailService.java', [r'@Resource', r'portalConfig\.isEmailEnabled\(\)']),
], limitations=['an EmailService contributed by a deployment jar would replace the default; not provable from the tree'],
    forbidden=['EmailService selection uses @Primary or @Profile'])

oracle('AQ-016', {
    'declaration': 'public static @Bean method in static nested @Configuration SpringSecurityAuthAutoConfiguration (@Profile("auth"))',
    'result_type': 'org.springframework.security.provisioning.JdbcUserDetailsManager (also a UserDetailsService / UserDetailsManager)',
    'bean_name': 'jdbcUserDetailsManager (method name)',
    'qualifiers': 'none declared; implicit name qualifier',
    'scope': 'singleton (no @Scope)',
    'static_effect': 'static: invoked without instantiating the declaring configuration class',
    'configuration': 'SQL queries are customised with dialect quote characters taken from the Hibernate SessionFactory',
    'consumers': ['@DependsOn("jdbcUserDetailsManager") on springSecurityUserService',
                  'Spring Security picks it up as the UserDetailsService for form login (by type)'],
}, [
    (AUTH, [r'public static JdbcUserDetailsManager jdbcUserDetailsManager\(DataSource datasource',
            r'entityManagerFactory\.unwrap\(SessionFactoryImplementor\.class\)', r'@DependsOn\("jdbcUserDetailsManager"\)']),
], forbidden=['its scope is inherited from the declaring configuration class', 'it is conditional on a missing bean'])

oracle('AQ-018', {
    'parameters': {
        'PasswordEncoder': 'passwordEncoder (static @Bean in the same class, conditional on missing PasswordEncoder)',
        'UserRepository': 'Spring Data JPA repository bean userRepository (interface extends JpaRepository)',
        'AuthorityRepository': 'Spring Data JPA repository bean authorityRepository',
    },
    'repository_registration': 'JPA repositories are found by Spring Boot JPA repository auto-configuration in the '
                               'auto-configuration packages registered by @EnableAutoConfiguration on PortalApplication, '
                               'PortalOpenApiConfig and ApolloCommonConfig; there is no @EnableJpaRepositories in source',
    'explicit_depends_on': 'jdbcUserDetailsManager (no injection, ordering only)',
    'declaring_class_dependencies': 'SpringSecurityAuthAutoConfiguration has no fields or constructor parameters; the '
                                    'non-static method needs the configuration instance itself',
    'conditions': ['@Profile("auth") on the class', '@ConditionalOnMissingBean(UserService.class) on the method'],
}, [
    (AUTH, [r'@DependsOn\("jdbcUserDetailsManager"\)', r'@ConditionalOnMissingBean\(UserService\.class\)',
            r'UserRepository userRepository, AuthorityRepository authorityRepository\)']),
    (f'{PORTAL}/repository/UserRepository.java', [r'extends JpaRepository<UserPO, Long>']),
    (f'{PORTAL}/repository/AuthorityRepository.java', [r'extends JpaRepository<Authority, Long>']),
    (f'{PORTAL}/PortalApplication.java', [r'@EnableAutoConfiguration']),
], forbidden=['@DependsOn injects jdbcUserDetailsManager into the method', 'repositories are @Repository-annotated classes'])

LISTENER = f'{PORTAL}/listener/ConfigPublishListener.java'
REGISTRY_CFG = f'{BIZ}/registry/configuration'

oracle('AQ-020', {
    'bean': '@Component singleton configPublishListener, created eagerly during context refresh (not lazy)',
    'creation': 'constructor injection of 9 dependencies, then @PostConstruct init() creates a single-thread executor '
                '(ApolloThreadFactory "ConfigPublishNotify", daemon flag true)',
    'destruction_callbacks': 'none: no @PreDestroy, no DisposableBean, no destroy method; a @Component gets no inferred '
                             'destroy method',
    'consequence': 'the executor is never shut down by the context; queued notify tasks may be lost at shutdown; daemon '
                   'threads do not block JVM exit',
    'static_limits': ['whether tasks are still queued at shutdown', 'thread daemon behaviour is inside apollo-core '
                      'ApolloThreadFactory (external jar)', 'executor exceptions are swallowed by submit()'],
}, [
    (LISTENER, [r'^@Component', r'@PostConstruct', r'Executors\.newSingleThreadExecutor\(ApolloThreadFactory\.create\("ConfigPublishNotify", true\)\)',
                r'executorService\.submit\(new ConfigPublishNotifyTask']),
], forbidden=['the executor is shut down in a @PreDestroy method', 'the listener is request scoped'])

oracle('AQ-023', {
    'known_before_startup': ['candidate UserService producers and their conditions (auth: SpringSecurityUserService; '
                             'ldap: LdapUserService; oidc: OidcLocalUserServiceImpl; none of them: DefaultUserService)'],
    'decided_at_startup': ['spring.profiles.active (apollo_profile, here github,auth) selects the @Profile configuration',
                           '@ConditionalOnMissingBean(UserService.class) yields to any UserService defined earlier',
                           'the bean set is fixed after refresh; nothing re-evaluates the profiles at runtime'],
    'decided_per_call': ['ObjectProvider#getObject() on every getUser() returns the single UserService bean',
                         'the user id comes from SecurityContextHolder for the current thread',
                         'the returned UserInfo comes from the Users table (falls back to a UserInfo with only the id)'],
}, [
    (SS_HOLDER, [r'this\(userServiceProvider::getObject\)', r'String userId = this\.getCurrentUsername\(\)',
                 r'userInfo\.setUserId\(userId\)']),
    (AUTH, [r'@Profile\("auth"\)', r'@ConditionalOnMissingBean\(UserService\.class\)']),
    ('apollo-portal/src/main/resources/application.yml', [r'active: \$\{apollo_profile\}']),
], forbidden=['the UserService can change after a profile switch at runtime'])

oracle('AQ-024', {
    'registrar': '@EnableConfigurationProperties({ApolloServiceDiscoveryProperties.class}) on '
                 'ApolloServiceDiscoveryAutoConfiguration (registers the type through EnableConfigurationPropertiesRegistrar)',
    'bean_name': 'apollo.service.discovery-com.ctrip.framework.apollo.biz.registry.configuration.support.ApolloServiceDiscoveryProperties '
                 '(prefix-FQCN convention)',
    'types': ['ApolloServiceDiscoveryProperties (plain class, no interfaces)'],
    'binding': 'values bound from the Environment under prefix apollo.service.discovery: enabled (default false), '
               'healthCheckIntervalInSecond (default 61)',
    'conditions': ['ApolloServiceDiscoveryAutoConfiguration is a plain @Configuration found by component scanning of '
                   'com.ctrip.framework.apollo.biz (ConfigServiceApplication/AdminServiceApplication scan ApolloBizConfig), '
                   'not an AutoConfiguration.imports entry',
                   '@ConditionalOnProperty(prefix="apollo.service.discovery", value="enabled") on that class'],
    'where_enabled': 'apollo.service.discovery.enabled=true only in application-database-discovery.properties of '
                     'apollo-configservice and apollo-assembly; under github alone the bean is absent',
    'consumers': ['ApolloServiceDiscoveryAutoConfiguration#databaseDiscoveryClient(ApolloServiceDiscoveryProperties, ...)'],
}, [
    (f'{REGISTRY_CFG}/ApolloServiceDiscoveryAutoConfiguration.java', [
        r'@ConditionalOnProperty\(prefix = ApolloServiceDiscoveryProperties\.PREFIX, value = "enabled"\)',
        r'@EnableConfigurationProperties\(\{ApolloServiceDiscoveryProperties\.class,\}\)']),
    (f'{REGISTRY_CFG}/support/ApolloServiceDiscoveryProperties.java', [r'PREFIX = "apollo\.service\.discovery"',
                                                                       r'private boolean enabled = false',
                                                                       r'healthCheckIntervalInSecond = 61']),
    ('apollo-configservice/src/main/resources/application-database-discovery.properties', [r'apollo\.service\.discovery\.enabled=true']),
    ('apollo-configservice/src/main/java/com/ctrip/framework/apollo/configservice/ConfigServiceApplication.java', [r'ApolloBizConfig\.class']),
], forbidden=['the class is annotated @Component', 'it is registered via spring.factories or AutoConfiguration.imports',
              'it is active in apollo-adminservice with github'])

oracle('AQ-025', {
    'boundary': 'onConfigPublish runs synchronously on the publishing (request) thread but only submits '
                'ConfigPublishNotifyTask to a single-thread executor; run() executes on the "ConfigPublishNotify" thread',
    'context_not_propagated': ['UserIdentityContextHolder is a plain ThreadLocal set and cleared by UserTypeResolverFilter '
                               'around the request: null in the task', 'SecurityContextHolder (thread-local strategy by '
                               'default) is not copied, so UserInfoHolder#getUser would fail there',
                               'no request attributes / request scope on the worker thread'],
    'what_the_task_uses': 'only the captured ConfigPublishInfo (env, appId, cluster, namespace, release ids) plus singleton '
                          'beans: ReleaseHistoryService, ConfigReleaseWebhookNotifier, EmailService, email builders, MQService, PortalConfig',
    'verdict': 'the async path does not rely on request context today; adding a permission or current-user call there would '
               'break silently or throw',
    'runtime_checks': ['transitive calls inside email builders / admin API clients', 'any custom EmailService or MQService '
                       'implementation reading request context'],
}, [
    (LISTENER, [r'public void onConfigPublish\(ConfigPublishEvent event\)', r'ConfigPublishNotifyTask\(ConfigPublishEvent\.ConfigPublishInfo publishInfo\)']),
    (f'{PORTAL}/component/UserIdentityContextHolder.java', [r'private static final ThreadLocal<String> AUTH_TYPE_HOLDER']),
    (f'{PORTAL}/filter/UserTypeResolverFilter.java', [r'UserIdentityContextHolder\.setAuthType\(authType\)', r'UserIdentityContextHolder\.clear\(\)']),
], limitations=['transitive behaviour of email builders and admin-service HTTP calls was not executed'],
    forbidden=['the listener is @Async', 'UserIdentityContextHolder is an InheritableThreadLocal'])

oracle('AQ-026', {
    'proxying': 'AdminServiceApplication @EnableAspectJAutoProxy; ItemController is proxied (class proxy, no interface)',
    'applicable_advice': [
        'NamespaceAcquireLockAspect#requireLockAdvice(String,String,String,ItemDTO): @Before, pointcut '
        '"@annotation(PreAcquireNamespaceLock) && args(appId, clusterName, namespaceName, item, ..)"',
        'NamespaceUnlockAspect#requireLockAdvice(String,String,String,ItemDTO): @After with the same pointcut shape'],
    'not_matching_overloads': ['args(..., itemId, item, ..) needs a long 4th argument',
                               'args(..., changeSet, ..) needs ItemChangeSets', 'args(itemId, operator, ..) needs long first'],
    'not_applicable_aspects': ['RepositoryAspect: execution(public * Repository+.*(..)), applies to repository calls inside, '
                               'not to the controller method', 'ApolloAuditSpanAspect: @annotation(auditLog), create has no @ApolloAuditLog'],
    'order': 'no @Order on either aspect; lock @Before runs before the method, unlock @After runs after it (also on exceptions); '
             'their relative precedence is undefined but they never compete in the same phase',
    'runtime_switch': 'both advices return immediately when bizConfig.isNamespaceLockSwitchOff()',
}, [
    (f'{ADMIN}/controller/ItemController.java', [r'@PreAcquireNamespaceLock', r'public ItemDTO create\(@PathVariable\("appId"\) String appId']),
    (f'{ADMIN}/aop/NamespaceAcquireLockAspect.java', [r'^@Aspect', r'args\(appId, clusterName, namespaceName, item, \.\.\)',
                                                     r'if \(bizConfig\.isNamespaceLockSwitchOff\(\)\)']),
    (f'{ADMIN}/aop/NamespaceUnlockAspect.java', [r'@After\(', r'args\(appId, clusterName, namespaceName, item, \.\.\)']),
    (f'{COMMON}/aop/RepositoryAspect.java', [r'execution\(public \* org\.springframework\.data\.repository\.Repository\+\.\*\(\.\.\)\)']),
    (f'{ADMIN}/AdminServiceApplication.java', [r'@EnableAspectJAutoProxy']),
], forbidden=['all four requireLockAdvice overloads apply', 'RepositoryAspect wraps the controller method',
              'the aspects are ordered by @Order'])

oracle('AQ-027', {
    'start': 'transaction proxy around adminservice ReleaseController#publish (@Transactional, default REQUIRED, rollback on '
             'unchecked exceptions); @EnableTransactionManagement on AdminServiceApplication',
    'end': 'commit or rollback when the controller method returns or throws, before the HTTP response is written',
    'joining_calls': ['biz ReleaseService#publish (@Transactional REQUIRED) joins',
                      'DatabaseMessageSender#sendMessage (@Transactional REQUIRED) joins: the ReleaseMessage row commits '
                      'atomically with the release', 'NamespaceService/Repository reads join the same transaction'],
    'propagation_changes': 'no REQUIRES_NEW, NOT_SUPPORTED or explicit propagation in application source',
    'non_transactions': ['Tracer.newTransaction / RepositoryAspect "CatTransaction" are tracing spans, not database transactions',
                         'DatabaseMessageSender queues the id for asynchronous cleanup (toClean) outside the request transaction'],
    'self_invocation': 'private masterRelease/createRelease/branchRelease run inside the caller transaction; their own annotations, if any, are not proxied',
}, [
    (f'{ADMIN}/controller/ReleaseController.java', [r'@Transactional', r'public ReleaseDTO publish\(@PathVariable\("appId"\) String appId',
                                                   r'messageSender\.sendMessage\(']),
    (f'{BIZ}/service/ReleaseService.java', [r'public Release publish\(Namespace namespace, String releaseName']),
    (f'{BIZ}/message/DatabaseMessageSender.java', [r'public void sendMessage\(String message, String channel\)',
                                                  r'toClean\.offer\(newMessage\.getId\(\)\)']),
    (f'{COMMON}/aop/RepositoryAspect.java', [r'invokeWithCatTransaction']),
], forbidden=['the release message is sent in a separate transaction', 'RepositoryAspect starts a database transaction'])

oracle('AQ-028', {
    'event': 'ConfigPublishEvent extends ApplicationEvent, published through ApplicationEventPublisher (the context)',
    'listeners': ['ConfigPublishListener#onConfigPublish(ConfigPublishEvent): @EventListener, synchronous on the request thread, '
                  'then hands work to its own executor'],
    'not_listeners': ['DeletionListener, CreationListener, AppInfoChangedListener (other event types)',
                      'ApolloAuditLogDataInfluenceEventListener (ApolloAuditLogDataInfluenceEvent)',
                      'OidcAuthenticationSuccessEventListener (AuthenticationSuccessEvent, oidc profile only)'],
    'transaction_phase': 'no @TransactionalEventListener and no portal-side transaction around createRelease: delivery is immediate',
    'multicaster': 'default SimpleApplicationEventMulticaster without executor (no custom applicationEventMulticaster bean)',
}, [
    (OPEN_RELEASE, [r'publisher\.publishEvent\(event\)']),
    (f'{PORTAL}/listener/ConfigPublishEvent.java', [r'public class ConfigPublishEvent extends ApplicationEvent']),
    (LISTENER, [r'@EventListener', r'public void onConfigPublish\(ConfigPublishEvent event\)']),
], forbidden=['the listener runs after transaction commit', 'admin service receives the Spring event'])

oracle('AQ-029', {
    'mechanism': 'manual decoration inside the @Bean method ApolloServiceDiscoveryAutoConfiguration#databaseDiscoveryClient; '
                 'no Spring decorator/BeanPostProcessor',
    'chain_outer_to_inner': ['DatabaseDiscoveryClientMemoryCacheDecoratorImpl (cache, init() starts a refresh scheduler)',
                             'DatabaseDiscoveryClientAlwaysAddSelfInstanceDecoratorImpl (adds selfServiceInstance)',
                             'DatabaseDiscoveryClientImpl (reads ServiceRegistryService)'],
    'order_source': 'nesting in code: wrapMemoryCache(wrapAlwaysAddSelfInstance(discoveryClient, selfServiceInstance))',
    'delegate': 'each decorator receives the inner client through its constructor (field delegate)',
    'override': '@ConditionalOnMissingBean on the @Bean: a user-defined DatabaseDiscoveryClient bean replaces the whole chain '
                '(DatabaseDiscoveryWithoutDecoratorIntegrationTest does this)',
    'active_when': 'apollo.service.discovery.enabled=true (database-discovery profile)',
    'consumer': 'configservice DatabaseDiscoveryService (@Profile("database-discovery"))',
}, [
    (f'{REGISTRY_CFG}/ApolloServiceDiscoveryAutoConfiguration.java', [
        r'return wrapMemoryCache\(wrapAlwaysAddSelfInstance\(discoveryClient, selfServiceInstance\)\)', r'decorator\.init\(\)',
        r'public DatabaseDiscoveryClient databaseDiscoveryClient\(']),
    (f'{BIZ}/registry/DatabaseDiscoveryClientMemoryCacheDecoratorImpl.java', [r'private final DatabaseDiscoveryClient delegate']),
    (f'{BIZ}/registry/DatabaseDiscoveryClientAlwaysAddSelfInstanceDecoratorImpl.java', [r'private final DatabaseDiscoveryClient delegate']),
    ('apollo-biz/src/test/java/com/ctrip/framework/apollo/biz/registry/DatabaseDiscoveryWithoutDecoratorIntegrationTest.java',
     [r'@AutoConfigureBefore\(ApolloServiceDiscoveryAutoConfiguration\.class\)']),
], forbidden=['Spring applies the decorators automatically', 'the self-instance decorator is outermost'])

oracle('AQ-030', {
    'graph_cycles_through_NamespaceService': [
        'NamespaceService <-> ItemService (both edges @Lazy)', 'NamespaceService <-> AppNamespaceService (both @Lazy)',
        'NamespaceService <-> ClusterService (both @Lazy)',
        'NamespaceService -@Lazy-> ReleaseService -> NamespaceService',
        'NamespaceService -@Lazy-> NamespaceBranchService -> NamespaceService',
        'NamespaceService -@Lazy-> ReleaseService -> ItemSetService -> NamespaceService',
        'AdminService -@Lazy-> NamespaceService (not a cycle; one-way)'],
    'creation': 'every cycle contains at least one @Lazy constructor parameter; Spring injects a lazy-resolution proxy there, '
                'so constructor instantiation is acyclic and needs no spring.main.allow-circular-references (not set)',
    'risk': 'calling a lazy proxy inside a constructor would resolve the target early; constructors only assign fields',
    'related_cycle_not_through_NamespaceService': 'ReleaseService -> NamespaceBranchService -@Lazy-> ReleaseService',
}, [
    (f'{BIZ}/service/NamespaceService.java', [r'final @Lazy AppNamespaceService appNamespaceService, final MessageSender messageSender',
                                             r'final @Lazy ItemService itemService', r'final @Lazy ReleaseService releaseService, final @Lazy ClusterService clusterService',
                                             r'final @Lazy NamespaceBranchService namespaceBranchService']),
    (f'{BIZ}/service/ItemService.java', [r'final @Lazy NamespaceService namespaceService']),
    (f'{BIZ}/service/ReleaseService.java', [r'final NamespaceService namespaceService, final NamespaceBranchService namespaceBranchService']),
    (f'{BIZ}/service/NamespaceBranchService.java', [r'final @Lazy ReleaseService releaseService', r'final NamespaceService namespaceService']),
    (f'{BIZ}/service/ItemSetService.java', [r'final NamespaceService namespaceService']),
], forbidden=['startup fails with BeanCurrentlyInCreationException', 'Apollo enables allow-circular-references'])

SPRING_BOOT_SECURITY = 'org.springframework.boot:spring-boot-security:4.1.1'
PORTAL_CONFIG = f'{PORTAL}/component/config/PortalConfig.java'
REFRESHABLE = f'{COMMON}/config/RefreshableConfig.java'
GENERATED_RELEASE_API = ('apollo-portal/target/generated-sources/openapi/src/main/java/com/ctrip/framework/apollo/openapi/api/'
                         'ReleaseManagementApi.java (generated, not tracked)')

oracle('AQ-031', {
    'filters_in_order': ['springSecurityFilterChain (order -100): /openapi/** is in BY_PASS_URLS -> permitAll; '
                         'UserTokenAuthenticationFilter runs inside the chain',
                         'PortalUserSessionFilter (order -99, /openapi/*)', 'ConsumerAuthenticationFilter (order -98, /openapi/*)',
                         'UserTypeResolverFilter (order -97, /*): sets UserIdentityContextHolder auth type'],
    'handler_mapping': 'the @RequestMapping (path constant PATH_CREATE_RELEASE) is declared on the generated interface method '
                       'ReleaseManagementApi#createRelease; ReleaseController ("openapiReleaseController") implements it',
    'entrypoint': 'com.ctrip.framework.apollo.openapi.v1.controller.ReleaseController#createRelease(String,String,String,String,NamespaceReleaseDTO,String)',
    'before_body': 'method-security proxy evaluates @PreAuthorize("@unifiedPermissionValidator.hasReleaseNamespacePermission(...)")',
    'business_path': ['toReleaseModel + resolveOperator (current user or consumer operator)',
                      'checkEmergencyPublishAllowedForUser', 'portal ReleaseService#publish(NamespaceReleaseModel) (@Service)',
                      'AdminServiceAPI.ReleaseAPI#createRelease: HTTP call to apollo-adminservice',
                      'publishEvent(ConfigPublishEvent) after the remote publish succeeds'],
    'direct_dependency': 'ReleaseService injected by constructor; it depends on AdminServiceAPI.ReleaseAPI',
}, [
    (OPEN_RELEASE, [r'@RestController\("openapiReleaseController"\)', r'implements ReleaseManagementApi',
                    r'public ResponseEntity<OpenReleaseDTO> createRelease\(String appId', r'ReleaseDTO createdRelease = releaseService\.publish\(releaseModel\);']),
    (f'{PORTAL}/service/ReleaseService.java', [r'public ReleaseDTO publish\(NamespaceReleaseModel model\)',
                                              r'releaseAPI\.createRelease\(appId, env, clusterName, namespaceName']),
    (f'{PORTAL}/spi/configuration/AuthFilterConfiguration.java', [r'OPEN_API_AUTH_ORDER = -98', r'setOrder\(OPEN_API_AUTH_ORDER \+ 1\)',
                                                                 r'setOrder\(OPEN_API_AUTH_ORDER - 1\)']),
    (AUTH, [r'"/openapi/\*\*"']),
], framework=[{'artifact': SPRING_BOOT_SECURITY, 'class': 'org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties',
               'fact': 'DEFAULT_FILTER_ORDER = -100'},
              {'artifact': 'generated', 'class': GENERATED_RELEASE_API, 'fact': 'PATH_CREATE_RELEASE and @RequestMapping on createRelease'}],
    forbidden=['the route is mapped by the deprecated portal web ReleaseController',
               'the portal writes the release to its own database'])

oracle('AQ-032', {
    'layers': [
        'Spring Security chain (auth profile): /openapi/** permitAll, so no login is forced here; a session login or user '
        'token still populates SecurityContext',
        'PortalUserSessionFilter: authenticated portal user -> PORTAL_USER_AUTHENTICATED; SESSION cookie without auth -> '
        'session-expired response',
        'ConsumerAuthenticationFilter: lets portal users and user tokens through, otherwise requires a consumer token in '
        'Authorization (401), applies the token rate limit and stores the consumer id',
        'UserTypeResolverFilter: auth type USER_TOKEN, CONSUMER, USER or ANONYMOUS',
        '@PreAuthorize("@unifiedPermissionValidator.hasReleaseNamespacePermission(#appId, #env, #clusterName, #namespaceName)") '
        'via @EnableMethodSecurity(prePostEnabled = true) on SpringSecurityConfigurer'],
    'permission_source': {
        'USER': 'UserPermissionValidator -> RolePermissionService#hasAnyPermission: super admin, or any of RELEASE_NAMESPACE '
                '(app+namespace), RELEASE_NAMESPACE (app+namespace+env), RELEASE_NAMESPACES_IN_CLUSTER (app+env+cluster) from '
                'the Role/RolePermission/UserRole/Permission tables',
        'USER_TOKEN': 'same user permission AND token scope allows CONFIG_RELEASE and the namespace',
        'CONSUMER': 'ConsumerPermissionValidator: app-level CREATE_NAMESPACE consumer permission short-circuits, otherwise '
                    'the same release permissions for the consumer',
        'ANONYMOUS': 'getDelegate() throws IllegalStateException'},
    'configuration_dependent': ['emergency publish allowed only for envs in emergencyPublish.supported.envs (USER and USER_TOKEN)',
                                'super admins from PortalConfig superAdmin', 'env normalisation via Env.transformEnv',
                                'adminservice namespace lock: with namespace.lock.switch=true the last modifier cannot publish '
                                'unless emergency publish'],
}, [
    (OPEN_RELEASE, [r'value = "@unifiedPermissionValidator\.hasReleaseNamespacePermission\(#appId, #env, #clusterName, #namespaceName\)"',
                    r'private void checkEmergencyPublishAllowedForUser']),
    (AUTH, [r'@EnableMethodSecurity\(prePostEnabled = true\)', r'requestMatchers\(BY_PASS_URLS\)\.permitAll\(\)\.anyRequest\(\)\.hasAnyRole\(USER_ROLE\)']),
    (f'{OPENAPI}/filter/ConsumerAuthenticationFilter.java', [r'consumerAuthUtil\.getConsumerToken\(token\)', r'SC_UNAUTHORIZED']),
    (f'{PORTAL}/filter/PortalUserSessionFilter.java', [r'isAuthenticatedPortalUser\(request\)']),
    (f'{PORTAL}/component/AbstractPermissionValidator.java', [r'PermissionType\.RELEASE_NAMESPACES_IN_CLUSTER']),
    (f'{PORTAL}/spi/defaultimpl/DefaultRolePermissionService.java', [r'public boolean hasAnyPermission\(String userId, List<Permission> permissions\)']),
    (f'{BIZ}/service/ReleaseService.java', [r'Config can not be published by yourself']),
], forbidden=['the security filter chain requires the user role for /openapi/**', 'consumers are checked with UserPermissionValidator'])

oracle('AQ-033', {
    'bean': 'not a component: ConfigServiceAutoConfiguration#releaseMessageScanner(...) @Bean in apollo-configservice creates it '
            'with new and registers six ReleaseMessageListener beans (ReleaseMessageServiceWithCache, GrayReleaseRulesHolder, '
            'ConfigService, ConfigFileController, NotificationControllerV2, NotificationController) in that order',
    'trigger': 'InitializingBean#afterPropertiesSet schedules scheduleWithFixedDelay on its own single-thread executor '
               '("ReleaseMessageScanner", daemon); no @Scheduled',
    'dependencies': ['BizConfig (constructor)', 'ReleaseMessageRepository (constructor)'],
    'schedule_setting': 'apollo.message-scan.interval via BizConfig#releaseMessageScanIntervalInMilli: default 1000 ms, '
                        'below 100 falls back to the default; read once in afterPropertiesSet',
    'source_of_setting': 'BizConfig is a RefreshableConfig backed by the ConfigDB ServerConfig table, but the interval is '
                         'captured once, so changing it needs a restart',
    'not_present_in': 'apollo-adminservice and apollo-portal (declared only in apollo-configservice, which apollo-assembly embeds)',
}, [
    (f'{BIZ}/message/ReleaseMessageScanner.java', [r'implements InitializingBean', r'databaseScanInterval = bizConfig\.releaseMessageScanIntervalInMilli\(\)',
                                                  r'executorService\.scheduleWithFixedDelay\(']),
    (f'{BIZ}/config/BizConfig.java', [r'getIntProperty\("apollo\.message-scan\.interval", DEFAULT_RELEASE_MESSAGE_SCAN_INTERVAL_IN_MS\)',
                                     r'DEFAULT_RELEASE_MESSAGE_SCAN_INTERVAL_IN_MS = 1000']),
    ('apollo-configservice/src/main/java/com/ctrip/framework/apollo/configservice/ConfigServiceAutoConfiguration.java',
     [r'public ReleaseMessageScanner releaseMessageScanner\(', r'releaseMessageScanner\.addMessageListener\(releaseMessageServiceWithCache\)']),
], forbidden=['ReleaseMessageScanner uses @Scheduled', 'the interval is re-read on every refresh'])

oracle('AQ-035', {
    'registration': 'ApolloServiceRegistryAutoConfiguration#apolloServiceRegistryHeartbeatApplicationRunner @Bean, '
                    '@ConditionalOnMissingBean; the configuration is @ConditionalOnProperty apollo.service.registry.enabled',
    'active_when': 'apollo.service.registry.enabled=true (database-discovery profile of configservice/adminservice/assembly); '
                   'not active under github alone',
    'startup': 'SpringApplication calls ApplicationRunner#run after the context is refreshed; run logs and schedules heartbeat() '
               'at fixed rate (initial delay 0, period heartbeatIntervalInSecond, default 10 s) on its own daemon executor',
    'beans_touched': ['ApolloServiceRegistryProperties (also the ServiceInstance; @PostConstruct resolves serviceName from '
                      'spring.application.name and uri from InetUtils/server.port/ServletContext)',
                      'DatabaseServiceRegistry (DatabaseServiceRegistryImpl -> ServiceRegistryService -> ServiceRegistryRepository)'],
    'shutdown': 'the runner has no stop logic; ApolloServiceRegistryDeregisterApplicationListener deregisters on ContextClosedEvent; '
                'the heartbeat executor is never shut down explicitly',
    'unknown_order': ['relative order with ApolloServiceRegistryClearApplicationRunner (no @Order on either)',
                      'whether a heartbeat can race with deregistration during shutdown'],
}, [
    (f'{REGISTRY_CFG}/ApolloServiceRegistryAutoConfiguration.java', [r'@ConditionalOnProperty\(prefix = ApolloServiceRegistryProperties\.PREFIX, value = "enabled"\)',
                                                                     r'apolloServiceRegistryHeartbeatApplicationRunner\(']),
    (f'{REGISTRY_CFG}/support/ApolloServiceRegistryHeartbeatApplicationRunner.java', [r'public void run\(ApplicationArguments args\)',
                                                                                     r'scheduleAtFixedRate\(this::heartbeat, 0']),
    (f'{REGISTRY_CFG}/support/ApolloServiceRegistryDeregisterApplicationListener.java', [r'implements ApplicationListener<ContextClosedEvent>']),
    (f'{REGISTRY_CFG}/support/ApolloServiceRegistryProperties.java', [r'private long heartbeatIntervalInSecond = 10',
                                                                     r'getRequiredProperty\("spring\.application\.name"\)']),
    ('apollo-adminservice/src/main/resources/application-database-discovery.properties', [r'apollo\.service\.registry\.enabled=true']),
], forbidden=['the runner deregisters on shutdown itself', 'it runs before the context refresh completes'])

oracle('AQ-036', {
    'reader': 'PortalConfig#isEmergencyPublishAllowed(Env): getArrayProperty("emergencyPublish.supported.envs", EMPTY_STRING_ARRAY), '
              'trims entries, normalises each with Env.transformEnv and compares with the normalised env',
    'default': 'empty: emergency publish is allowed in no environment',
    'sources_under_github_auth': ['any Spring Environment source (application.properties/yml, profile files, env vars, system '
                                  'properties) - none in the tracked portal resources define it',
                                  'PortalDBPropertySource "DBConfig": rows of the PortalDB ServerConfig table, added with addLast '
                                  '(lowest precedence) by RefreshableConfig#setup'],
    'usages': ['openapi ReleaseController#checkEmergencyPublishAllowedForUser (USER and USER_TOKEN only)',
               'deprecated portal web ReleaseController#createRelease / #createGrayRelease'],
    'documentation': 'docs/en and docs/zh deployment guides section 3.1.8',
}, [
    (PORTAL_CONFIG, [r'getArrayProperty\("emergencyPublish\.supported\.envs", EMPTY_STRING_ARRAY\)', r'public boolean isEmergencyPublishAllowed\(Env env\)']),
    (REFRESHABLE, [r'environment\.getPropertySources\(\)\.addLast\(propertySource\)']),
    (OPEN_RELEASE, [r'portalConfig\.isEmergencyPublishAllowed\(Env\.valueOf\(env\)\)']),
    (f'{PORTAL}/controller/ReleaseController.java', [r'portalConfig\.isEmergencyPublishAllowed\(Env\.valueOf\(env\)\)']),
    ('docs/en/deployment/distributed-deployment-guide.md', [r'emergencyPublish\.supported\.envs']),
], forbidden=['the key is bound with @Value or @ConfigurationProperties', 'emergency publish is enabled by default'])

oracle('AQ-038', {
    'answer': 'dev',
    'reason': 'classpath application.properties is a Spring Boot config-data source; PortalDBPropertySource ("DBConfig") is '
              'appended with addLast in RefreshableConfig#setup, after every existing source, so it has the lowest precedence '
              'and only supplies keys that no other source defines',
    'order_mechanism': 'MutablePropertySources iteration order; PortalConfig reads through ConfigurableEnvironment#getProperty',
    'consequence': 'the database value pro is shadowed while application.properties defines the key',
}, [
    (REFRESHABLE, [r'environment\.getPropertySources\(\)\.addLast\(propertySource\)', r'return environment\.getProperty\(key\)']),
    (f'{PORTAL}/service/PortalDBPropertySource.java', [r'super\("DBConfig", Maps\.newConcurrentMap\(\)\)']),
    (PORTAL_CONFIG, [r'return Collections\.singletonList\(portalDBPropertySource\)']),
], forbidden=['the database value wins because it is refreshed later', 'the result is pro'])

oracle('AQ-039', {
    'answer': 'yes, through the PortalDB ServerConfig table, if no higher-precedence source defines the key',
    'mechanism': ['RefreshableConfig#setup schedules a refresh every 60 s (CONFIG_REFRESH_INTERVAL) on a "ConfigRefresher" executor',
                  'PortalDBPropertySource#refresh reloads all rows into its map',
                  'isEmergencyPublishAllowed reads the Environment on every call, so the new value applies within about a minute'],
    'caveats': ['refresh only puts keys: deleting the row keeps the old value until restart',
                'a value from application.properties, env vars or system properties shadows the database and needs a restart to change',
                'no @RefreshScope / Spring Cloud refresh is involved'],
}, [
    (REFRESHABLE, [r'CONFIG_REFRESH_INTERVAL = 60', r'propertySources\.forEach\(RefreshablePropertySource::refresh\)']),
    (f'{PORTAL}/service/PortalDBPropertySource.java', [r'this\.source\.put\(key, value\)', r'serverConfigRepository\.findAll\(\)']),
    (PORTAL_CONFIG, [r'getArrayProperty\("emergencyPublish\.supported\.envs"']),
], forbidden=['a rebuild is required', 'removing the database row restores the default immediately'])

oracle('AQ-040', {
    'code': ['PortalConfig#isEmergencyPublishAllowed key literal (single production reader)'],
    'tests': ['PortalConfigTest stubs getArrayProperty("emergencyPublish.supported.envs", ...) in two places'],
    'configuration_and_sql': 'no tracked properties/yml or SQL script defines the key; existing deployments keep it as a row '
                             'in the PortalDB ServerConfig table, so a data migration (or reading both keys) is needed',
    'documentation': ['docs/en/deployment/distributed-deployment-guide.md section 3.1.8',
                      'docs/zh/deployment/distributed-deployment-guide.md section 3.1.8'],
    'not_affected': 'controllers call isEmergencyPublishAllowed and never use the key string',
}, [
    (PORTAL_CONFIG, [r'"emergencyPublish\.supported\.envs"']),
    ('apollo-portal/src/test/java/com/ctrip/framework/apollo/portal/component/config/PortalConfigTest.java', [r'"emergencyPublish\.supported\.envs"']),
    ('docs/en/deployment/distributed-deployment-guide.md', [r'3\.1\.8 emergencyPublish\.supported\.envs']),
    ('docs/zh/deployment/distributed-deployment-guide.md', [r'3\.1\.8 emergencyPublish\.supported\.envs']),
], limitations=['ServerConfig rows in real databases are outside the repository'],
    forbidden=['the SQL init scripts insert this key', 'controllers must be edited'])

UNIFIED = f'{PORTAL}/component/UnifiedPermissionValidator.java'
UPV = f'{PORTAL}/component/UserPermissionValidator.java'
PORTAL_TEST = 'apollo-portal/src/test/java/com/ctrip/framework/apollo'

oracle('AQ-037', {
    'prefix': 'apollo.service.registry',
    'keys': [
        {'key': 'apollo.service.registry.enabled', 'type': 'boolean', 'default': 'false (field default); also the '
         '@ConditionalOnProperty switch of ApolloServiceRegistryAutoConfiguration'},
        {'key': 'apollo.service.registry.service-name', 'type': 'String', 'default': 'null, resolved in @PostConstruct from '
         'spring.application.name (required)'},
        {'key': 'apollo.service.registry.uri', 'type': 'URI bound through setUri(String)', 'default': 'null, resolved in '
         '@PostConstruct to http://<first non-loopback ip>:<server.port><context path or />'},
        {'key': 'apollo.service.registry.cluster', 'type': 'String', 'default': 'null (database-discovery profile files set default)'},
        {'key': 'apollo.service.registry.metadata', 'type': 'Map<String,String>', 'default': 'empty HashMap'},
        {'key': 'apollo.service.registry.heartbeat-interval-in-second', 'type': 'long', 'default': '10'}],
    'not_keys': ['@Autowired PropertyResolver, InetUtils and ServletContext fields are injected collaborators, not properties'],
    'nested_groups': 'none',
    'profile_values': 'application-database-discovery.properties sets enabled=true, cluster=default, heartbeat-interval-in-second=10',
}, [
    (f'{REGISTRY_CFG}/support/ApolloServiceRegistryProperties.java', [
        r'PREFIX = "apollo\.service\.registry"', r'private boolean enabled;', r'private String serviceName;', r'private URI uri;',
        r'private String cluster;', r'private Map<String, String> metadata = new HashMap<>\(8\)',
        r'private long heartbeatIntervalInSecond = 10', r'public void setUri\(String uri\)', r'private InetUtils inetUtils']),
    ('apollo-configservice/src/main/resources/application-database-discovery.properties', [r'apollo\.service\.registry\.cluster=default']),
], forbidden=['servlet-context is a configuration key', 'cluster defaults to "default" in code'])

oracle('AQ-041', {
    'contract': 'UnifiedPermissionValidator#hasReleaseNamespacePermission overrides PermissionValidator#hasReleaseNamespacePermission; '
                'changing its signature changes or breaks the interface contract',
    'java_callers_on_unified': [
        'openapi.v1.controller.ReleaseController (rollback, checkReleaseNamespaceReadAllowed, checkReleaseReadAllowed)',
        'openapi.v1.controller.NamespaceBranchController (2 calls)', 'openapi.v1.controller.InstanceController (1 call)',
        'portal.controller.NamespaceBranchController (1 call)', 'portal.controller.ReleaseController (getReleaseWithPermissionCheck)'],
    'non_java_references': ['@PreAuthorize SpEL "@unifiedPermissionValidator.hasReleaseNamespacePermission(#appId, #env, #clusterName, '
                            '#namespaceName)": 3 in openapi ReleaseController (createGrayDelRelease, createGrayRelease, createRelease), '
                            '2 in portal ReleaseController (createRelease, createGrayRelease); resolved at runtime, invisible to javac'],
    'indirect': ['PermissionValidator#hasOperateNamespacePermission default method calls it; reached through '
                 'unifiedPermissionValidator.hasOperateNamespacePermission (openapi NamespaceBranchController, '
                 'portal NamespaceBranchController @PreAuthorize SpEL)'],
    'other_implementations_of_the_contract': ['AbstractPermissionValidator', 'ConsumerPermissionValidator', 'UserTokenPermissionValidator'],
    'tests': ['UnifiedPermissionValidatorTest (direct calls)', 'ReleaseBranchInstanceControllerTest and '
              'ApolloOpenApiJavaClientCompatibilityTest (Mockito stubs of the method)'],
}, [
    (UNIFIED, [r'public boolean hasReleaseNamespacePermission']),
    (OPEN_RELEASE, [r'@unifiedPermissionValidator\.hasReleaseNamespacePermission', r'if \(!unifiedPermissionValidator\.hasReleaseNamespacePermission\(release\.getAppId\(\), env,']),
    (f'{PORTAL}/controller/ReleaseController.java', [r'@unifiedPermissionValidator\.hasReleaseNamespacePermission']),
    (f'{OPENAPI}/v1/controller/NamespaceBranchController.java', [r'unifiedPermissionValidator\.hasReleaseNamespacePermission\(appId, env, clusterName,']),
    (f'{OPENAPI}/v1/controller/InstanceController.java', [r'\.hasReleaseNamespacePermission\(appId, env, clusterName, namespaceName\)\)']),
    (f'{PORTAL}/controller/NamespaceBranchController.java', [r'unifiedPermissionValidator\.hasReleaseNamespacePermission\(appId,',
                                                            r'@unifiedPermissionValidator\.hasOperateNamespacePermission']),
    (f'{PORTAL}/component/PermissionValidator.java', [r'\|\| hasReleaseNamespacePermission\(appId, env, clusterName, namespaceName\)']),
    (f'{PORTAL_TEST}/portal/component/UnifiedPermissionValidatorTest.java', [r'unifiedPermissionValidator\.hasReleaseNamespacePermission']),
], forbidden=['the compiler finds every caller', 'it is only called from Java code'])

oracle('AQ-042', {
    'declaration_to_change': '@Component("fileTextResolver") on FileTextResolver',
    'injection_sites': ['ItemService constructor @Qualifier("fileTextResolver") ConfigTextResolver fileTextResolver: must change '
                        'to "fileTextResolverV2" or startup fails with no qualifying bean (two ConfigTextResolver candidates)'],
    'unaffected': ['ItemService propertyResolver parameter and PropertyResolver bean',
                   'ItemServiceTest builds ItemService with mocks by constructor; the field name fileTextResolver is not a qualifier'],
    'other_by_name_lookups': 'no getBean("fileTextResolver"), SpEL @fileTextResolver or XML references in the tree',
}, [
    (f'{PORTAL}/component/txtresolver/FileTextResolver.java', [r'@Component\("fileTextResolver"\)']),
    (ITEM_SERVICE, [r'@Qualifier\("fileTextResolver"\)']),
    ('apollo-portal/src/test/java/com/ctrip/framework/apollo/portal/service/ItemServiceTest.java', [r'new ItemService\(namespaceAPI, itemAPI, releaseAPI, fileTex']),
], forbidden=['the parameter name keeps the injection working after the rename'])

oracle('AQ-043', {
    'contract': 'interface UserInfoHolder { UserInfo getUser(); }',
    'implementations': ['SpringSecurityUserInfoHolder (spi.springsecurity)', 'OidcUserInfoHolder (spi.oidc)',
                        'DefaultUserInfoHolder (spi.defaultimpl)'],
    'bean_declarations': ['AuthConfiguration: auth springSecurityUserInfoHolder, ldap springSecurityUserInfoHolder, '
                          'oidcUserInfoHolder, defaultUserInfoHolder (all @ConditionalOnMissingBean(UserInfoHolder))',
                          'AuthConfiguration#portalAuthenticationValidator consumes it and checks instanceof DefaultUserInfoHolder',
                          'test configs (e.g. ControllableAuthorizationConfiguration) provide @Primary mocks'],
    'injection_sites_production': '25 application files inject UserInfoHolder: openapi v1 controllers (App, Cluster, Item, '
                                  'NamespaceBranch, Namespace, PortalManagement, Release, User), OpenApiOperatorResolver, '
                                  'UserPermissionValidator, UserTokenPermissionValidator, 13 portal web controllers and AuthConfiguration',
    'method_users': 'getUser() is the only method; every injection site calls getUser().getUserId() or similar',
    'tests': '31 test files reference UserInfoHolder (mocks and stubs of getUser)',
}, [
    (f'{PORTAL}/spi/UserInfoHolder.java', [r'public interface UserInfoHolder', r'UserInfo getUser\(\);']),
    (f'{PORTAL}/spi/oidc/OidcUserInfoHolder.java', [r'implements UserInfoHolder']),
    (f'{PORTAL}/spi/defaultimpl/DefaultUserInfoHolder.java', [r'implements UserInfoHolder']),
    (SS_HOLDER, [r'implements UserInfoHolder']),
    (AUTH, [r'public SmartInitializingSingleton portalAuthenticationValidator\(UserInfoHolder userInfoHolder\)']),
    (f'{PORTAL_TEST}/ControllableAuthorizationConfiguration.java', [r'public UserInfoHolder userInfoHolder\(\)']),
], forbidden=['UserInfoHolder implementations are discovered by component scanning'])

oracle('AQ-044', {
    'replacement': '@Bean("unifiedPermissionValidator") or a @Bean method named unifiedPermissionValidator returning '
                   'UnifiedPermissionValidator, in a @Configuration scanned by PortalApplication, and remove @Component',
    'name_must_stay': 'five @PreAuthorize SpEL expressions reference @unifiedPermissionValidator by bean name '
                      '(plus hasOperateNamespacePermission SpEL in portal NamespaceBranchController and other @PreAuthorize uses)',
    'return_type': 'declare UnifiedPermissionValidator (not PermissionValidator): 24 production classes inject the concrete class, '
                   'and a PermissionValidator return type would also hide the extra overloads',
    'scope_and_lifecycle': 'singleton by default as now; no lifecycle callbacks to carry over; keep constructor parameters '
                           '(UserPermissionValidator, UserTokenPermissionValidator, ConsumerPermissionValidator) as @Bean method parameters',
    'conditions': 'none today; adding a @Profile or condition would break every consumer',
    'tests': ['ApolloOpenApiJavaClientCompatibilityTest uses @MockitoBean(name = "unifiedPermissionValidator")',
              'UnifiedPermissionValidatorTest constructs it with @InjectMocks and is unaffected'],
}, [
    (UNIFIED, [r'@Component\("unifiedPermissionValidator"\)', r'public UnifiedPermissionValidator\(UserPermissionValidator userPermissionValidator']),
    (OPEN_RELEASE, [r'@unifiedPermissionValidator\.hasReleaseNamespacePermission']),
    (f'{PORTAL_TEST}/openapi/v1/controller/ApolloOpenApiJavaClientCompatibilityTest.java', [r'@MockitoBean\(name = "unifiedPermissionValidator"\)']),
], forbidden=['the bean name may change freely', 'consumers inject PermissionValidator by interface'])

oracle('AQ-045', {
    'state': 'UserPermissionValidator holds only final references to singletons; it has no per-request state, so request scope '
             'adds overhead without a behavioural reason',
    'proxy': 'singleton consumers (UnifiedPermissionValidator, UserTokenPermissionValidator, UserTokenService) inject it by '
             'constructor; @RequestScope uses proxyMode TARGET_CLASS, so they would get a CGLIB scoped proxy (class is not final, '
             'public methods OK); a plain @Scope("request") without proxy fails at startup',
    'outside_requests': ['any call without an active request (startup, schedulers, executor threads such as ConfigPublishListener, '
                         'tests without a request context) throws ScopeNotActiveException / IllegalStateException',
                         'UserTokenService and the token validators call it from the request thread today'],
    'user_context': 'it already reads the current user through UserInfoHolder -> SecurityContextHolder per call; request scope '
                    'does not change identity resolution',
    'tests': 'unit tests construct it directly (UserPermissionValidatorTest) and are unaffected; Spring context tests need a request scope',
}, [
    (UPV, [r'@Component\("userPermissionValidator"\)', r'private final UserInfoHolder userInfoHolder;', r'userInfoHolder\.getUser\(\)\.getUserId\(\)']),
    (UNIFIED, [r'private final UserPermissionValidator userPermissionValidator;']),
    (f'{PORTAL}/component/UserTokenPermissionValidator.java', [r'private final UserPermissionValidator userPermissionValidator;']),
    (f'{PORTAL}/service/UserTokenService.java', [r'final PortalConfig portalConfig, final UserPermissionValidator userPermissionValidator\)']),
], forbidden=['request scope is needed to see the current user', 'singletons can inject a request-scoped bean without a proxy'])

oracle('AQ-046', {
    'direct_coverage': ['UnifiedPermissionValidatorTest: calls hasReleaseNamespacePermission for USER and CONSUMER delegation '
                        '(12 tests in the class)'],
    'compile_dependent_stubs': ['ReleaseBranchInstanceControllerTest (stubs the method 11 times; 53 tests)',
                                'ApolloOpenApiJavaClientCompatibilityTest (@MockitoBean unifiedPermissionValidator stub, Spring context)'],
    'heuristic': ['OpenApiControllerAnnotationParityTest (inspects @PreAuthorize expressions on openapi controllers incl. ReleaseController)',
                  'UserPermissionValidatorTest, AbstractPermissionValidatorTest, ConsumerPermissionValidatorTest, '
                  'UserTokenPermissionValidatorTest (delegate implementations)', 'portal ReleaseControllerTest (mocks the validator; '
                  'covers shouldHideConfigToCurrentUser, not this method)'],
    'gap': 'no test evaluates the @PreAuthorize SpEL against the real bean on the release endpoints; Mockito-based controller tests '
           'bypass method security',
    'reference_run': 'UnifiedPermissionValidatorTest and ReleaseBranchInstanceControllerTest pass at the pinned revision (12 + 53)',
}, [
    (f'{PORTAL_TEST}/portal/component/UnifiedPermissionValidatorTest.java', [r'assertTrue\(unifiedPermissionValidator\.hasReleaseNamespacePermission']),
    (f'{PORTAL_TEST}/openapi/v1/controller/ReleaseBranchInstanceControllerTest.java', [r'when\(unifiedPermissionValidator\.hasReleaseNamespacePermission\(anyString\(\)']),
    (f'{PORTAL_TEST}/openapi/v1/controller/OpenApiControllerAnnotationParityTest.java', [r'openApiPreAuthorizeExpressionsShouldNotEmbedAuthTypeBranching']),
], runtime_proven=True, forbidden=['controller unit tests verify the @PreAuthorize expression at runtime'])

oracle('AQ-047', {
    'context': '@SpringBootTest(classes = {PortalApplication.class, ControllableAuthorizationConfiguration.class}, RANDOM_PORT) with '
               'SpringJUnit4ClassRunner: the full portal context on an embedded server',
    'substitutions': ['@Primary @Bean userInfoHolder(): Mockito mock (coexists with the default holder; @Primary wins)',
                      '@Primary @Bean rolePermissionService() and itemService(): Mockito mocks whose names equal the production '
                      'beans, so they override those definitions (test application.properties sets '
                      'spring.main.allow-bean-definition-overriding=true)',
                      'controllableUserAuthenticationFilter (order -98, /*): puts an authenticated "apollo" ROLE_USER token into '
                      'SecurityContextHolder for every non-/openapi request'],
    'configuration': ['test-classpath application.properties: H2 in-memory mysql-mode datasource, spring.session.store-type=none, '
                      'Spring Cloud discovery disabled', 'test application.yml: apollo.portal.envs=local, port 8070',
                      'no auth profile: DefaultAuthAutoConfiguration and DefaultWebSecurityConfig (method security still enabled)',
                      'System.setProperty("spring.profiles.active", "test") runs in @PostConstruct, after the context exists, so it '
                      'does not affect this context'],
    'what_it_checks': '@PreAuthorize on the portal web item endpoint denies (403) or allows depending on the mocked hasAnyPermission',
    'not_checked': ['real login, Spring Security auth profile, JdbcUserDetailsManager', 'real permission tables and '
                    'DefaultRolePermissionService', 'ItemService and admin-service calls (mocked)', 'OpenAPI consumer/user-token filters'],
}, [
    (f'{PORTAL_TEST}/portal/controller/ItemControllerAuthIntegrationTest.java', [
        r'@SpringBootTest\(classes = \{PortalApplication\.class, ControllableAuthorizationConfiguration\.class\}',
        r'System\.setProperty\("spring\.profiles\.active", "test"\)', r'HttpStatus\.FORBIDDEN']),
    (f'{PORTAL_TEST}/ControllableAuthorizationConfiguration.java', [r'public RolePermissionService rolePermissionService\(\)',
                                                                   r'public ItemService itemService\(\)', r'filter\.setOrder\(-98\)']),
    ('apollo-portal/src/test/resources/application.properties', [r'spring\.main\.allow-bean-definition-overriding=true',
                                                                 r'spring\.session\.store-type=none']),
], forbidden=['the test runs with the auth profile', 'it uses @MockBean/@MockitoBean for these substitutions'])

oracle('AQ-048', {
    'origin': 'generated by openapi-generator-maven-plugin 7.20.0 (generatorName spring, interfaceOnly, useTags, useSpringBoot4) '
              'in apollo-portal generate-sources into target/generated-sources/openapi; build-helper-maven-plugin adds it to sources',
    'input': 'apollo.openapi.spec.url = https://raw.githubusercontent.com/apolloconfig/apollo-openapi/v0.3.12/apollo-openapi.yaml '
             '(external repository apolloconfig/apollo-openapi; this fixture pins a local copy through .mvn/maven.config)',
    'handwritten_side': 'openapi.v1.controller.ReleaseController implements ReleaseManagementApi and carries the behaviour, '
                        '@PreAuthorize and validation',
    'change_instead': ['the OpenAPI spec (new version in apolloconfig/apollo-openapi) and the spec URL/version in apollo-portal/pom.xml',
                       'plugin configOptions in apollo-portal/pom.xml', 'the implementing controller'],
    'not_tracked': 'target/ is git-ignored; the file carries "Do not edit the class manually"',
}, [
    ('apollo-portal/pom.xml', [r'<apollo\.openapi\.spec\.url>https://raw\.githubusercontent\.com/apolloconfig/apollo-openapi/v0\.3\.12/',
                               r'<artifactId>openapi-generator-maven-plugin</artifactId>', r'<generatorName>spring</generatorName>',
                               r'<interfaceOnly>true</interfaceOnly>', r'<apiPackage>com\.ctrip\.framework\.apollo\.openapi\.api</apiPackage>',
                               r'<id>add-openapi-sources</id>']),
    ('pom.xml', [r'<openapi-generator\.version>7\.20\.0<']),
    (OPEN_RELEASE, [r'implements ReleaseManagementApi']),
], framework=[{'artifact': 'generated', 'class': GENERATED_RELEASE_API, 'fact': 'header "auto generated by OpenAPI Generator (7.20.0) ... Do not edit"'}],
    forbidden=['ReleaseManagementApi is a tracked source file', 'edit the generated interface to change the route'])

oracle('AQ-049', {
    'patch': 'rename private getDelegate() to resolveDelegate() in UnifiedPermissionValidator: 1 declaration and 18 call sites, '
             'all in that file; no other class references it (private)',
    'behavior_preserved': 'delegation by UserIdentityContextHolder auth type (USER, USER_TOKEN, CONSUMER) and IllegalStateException '
                          'for unknown types unchanged',
    'narrow_tests': ['UnifiedPermissionValidatorTest (existing; may be extended to assert the unknown-type exception)'],
    'reference_result': {'baseline': 'UnifiedPermissionValidatorTest 12/12, ReleaseBranchInstanceControllerTest 53/53 passed',
                         'reference_rename': 'same 12/12 and 53/53 passed after the rename (mvnw -o -pl apollo-portal -am test)',
                         'diff': '1 file, 19 lines changed'},
}, [
    (UNIFIED, [r'private PermissionValidator getDelegate\(\)', r'throw new IllegalStateException\("Unknown authentication type: " \+ type\)']),
    (f'{PORTAL_TEST}/portal/component/UnifiedPermissionValidatorTest.java', [r'@InjectMocks']),
], runtime_proven=True, forbidden=['reference patch is the model result', 'callers outside UnifiedPermissionValidator need edits'])

oracle('AQ-050', {
    'step_facts': {
        '1': 'POST /openapi/v1/envs/{env}/apps/{appId}/clusters/{clusterName}/namespaces/{namespaceName}/releases -> '
             'openapi.v1.controller.ReleaseController#createRelease (route declared on generated ReleaseManagementApi)',
        '2': {'releaseService': 'portal ReleaseService (@Service, unique)', 'userInfoHolder': 'springSecurityUserInfoHolder (auth profile)',
              'unifiedPermissionValidator': 'by bean name in @PreAuthorize SpEL and by type in the constructor; delegates by auth type',
              'publisher': 'the ApplicationContext (resolvable dependency)', 'userService': 'springSecurityUserService (only used for '
              'consumer operators)'},
        '3': {'emergencyPublish.supported.envs': 'PortalConfig, default empty, PortalDB ServerConfig via DBConfig (lowest precedence)',
              'superAdmin': 'PortalConfig super admins short-circuit permission checks',
              'spring.profiles.active': 'github,auth decides the security chain and UserInfoHolder/UserService beans'},
        '4': {'direct_calls': 'createRelease -> toReleaseModel/resolveOperator -> checkEmergencyPublishAllowedForUser -> '
                              'ReleaseService#publish -> AdminServiceAPI.ReleaseAPI#createRelease (HTTP) -> publishEvent',
              'di_links': 'constructor-injected ReleaseService, UserService, NamespaceBranchService, UnifiedPermissionValidator, '
                          'PortalConfig, UserInfoHolder, ApplicationEventPublisher',
              'conditional_runtime': ['auth type from UserTypeResolverFilter selects the permission delegate and operator resolution',
                                      'emergency flag + env list', 'ConfigPublishListener runs asynchronously after the event']},
        '5': 'portal ReleaseService#publish(NamespaceReleaseModel) is public; direct Java callers: openapi ReleaseController '
             '(createRelease, createGrayRelease) and deprecated portal ReleaseController (createRelease, createGrayRelease); '
             'tests that stub it; plan only',
        '6': {'candidates': ['ReleaseBranchInstanceControllerTest (stubs ReleaseService#publish for the openapi release endpoints)',
                             'ApolloOpenApiJavaClientCompatibilityTest (Spring context, stubs publish)', 'portal ReleaseControllerTest'],
              'absent': 'there is no portal ReleaseServiceTest (apollo-biz ReleaseServiceTest covers the adminservice-side service)',
              'gap': 'no test drives the full route through filters, method security and the admin HTTP call'},
    },
    'consistent_bindings': {'entrypoint': 'com.ctrip.framework.apollo.openapi.v1.controller.ReleaseController#createRelease',
                            'service': 'com.ctrip.framework.apollo.portal.service.ReleaseService',
                            'method': 'com.ctrip.framework.apollo.portal.service.ReleaseService#publish(NamespaceReleaseModel)'},
    'cross_step_consistency': 'same controller/service/method throughout; must not drift to the adminservice ReleaseController or '
                              'the publish(NamespaceGrayDelReleaseModel, String) overload',
    'change_plan': 'plan only; direct callers, both controllers, stubs in tests; no production mutation',
}, [
    (OPEN_RELEASE, [r'public ResponseEntity<OpenReleaseDTO> createRelease\(String appId', r'ReleaseDTO createdRelease = releaseService\.publish\(releaseModel\);']),
    (f'{PORTAL}/controller/ReleaseController.java', [r'ReleaseDTO createdRelease = releaseService\.publish\(model\);']),
    (f'{PORTAL}/service/ReleaseService.java', [r'public ReleaseDTO publish\(NamespaceReleaseModel model\)',
                                              r'public ReleaseDTO publish\(NamespaceGrayDelReleaseModel model, String releaseBy\)']),
    (PORTAL_CONFIG, [r'public List<String> superAdmins\(\)']),
], forbidden=['the operation writes the release in the portal database', 'adminservice ReleaseController#publish is the entry point'])
