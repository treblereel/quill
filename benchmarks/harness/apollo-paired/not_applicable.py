"""Cryostat cases with no application-owned Spring counterpart in Apollo (frozen as N/A).

Each entry names the Spring predicate, the empty match set over all tracked src/main Java files at the
pinned revision, and nearby constructs that are not equivalent.
"""

SCOPE = 'All tracked */src/main Java sources at the pinned revision (530 files); dependencies and tests excluded.'
INVENTORY = 'git grep over the pinned revision; constructs cross-checked by reading the matching declarations'


def na(predicate, counterexamples):
    return {'predicate': predicate, 'matches': [], 'scope': SCOPE, 'java_files': 530, 'inventory': INVENTORY,
            'counterexamples_not_equivalent': counterexamples}


NOT_APPLICABLE = {
    'AQ-008': na('Application-owned @Autowired/@Inject methods (setter or config-method injection; CQ-008 initializer methods)',
                 'Field injection exists (@Autowired fields in RefreshableConfig and ApolloServiceRegistryProperties, @Resource in '
                 'DefaultEmailService) and @Autowired constructors exist (BizDBPropertySource, PortalDBPropertySource); neither is '
                 'method injection.'),
    'AQ-017': na('Application-owned @Lookup method injection (Spring stand-in for CQ-017 producer fields)',
                 'ObjectProvider<UserService>/ObjectProvider<UserInfoHolder> lookups and ApplicationContext#getBean in PortalSettings '
                 'are programmatic lookups, not @Lookup methods.'),
    'AQ-019': na('Application-owned explicit @Bean destroyMethod declarations (CQ-019 disposer parameters)',
                 '@PreDestroy/DisposableBean callbacks exist (e.g. RefreshableConfig#destroy) and @Bean methods rely on default '
                 'destroy-method inference; none declares destroyMethod.'),
    'AQ-021': na('Application-owned @ConditionalOnExpression bean conditions (CQ-021 build-profile conditions)',
                 '@Profile, @ConditionalOnMissingProfile and @ConditionalOnProperty conditions exist; they are not expression conditions.'),
    'AQ-022': na('Application-owned @ConditionalOnClass / @ConditionalOnResource bean conditions (CQ-022 build-property conditions)',
                 '@ConditionalOnMissingBean and @ConditionalOnProperty are widely used; none checks classpath classes '
                 'or resources.'),
    'AQ-034': na('Application-owned message-broker listeners (@KafkaListener, @RabbitListener, @JmsListener, @StreamListener; '
                 'CQ-034 reactive messaging handlers)',
                 'ReleaseMessageListener callbacks (database-polled release messages) and Spring @EventListener/ApplicationListener '
                 'beans are in-process mechanisms, not broker listeners; DefaultMQService is a no-op SPI.'),
}
