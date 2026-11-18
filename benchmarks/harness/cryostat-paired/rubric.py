"""Cryostat answer rubric: the core facts a correct answer names, per CQ case.

Copied unchanged from results/cryostat-full-paired-v17-20261106/analysis/accuracy_rubric_all.py, the rubric every
Cryostat comparison since V17 used; kept here so scoring no longer depends on that run's analysis directory.
A session is strict when every regex of its case matches the joined answer turns (case-insensitive).
"""
import re  # noqa: F401  (the rubric regexes are plain strings; kept for parity with the original module)

LIMIT = r'not proven|not guaranteed|not confirmed|not observed|not verified|cannot be (proven|verified|determined)|unknown|runtime|at run time|static|dynamic|reflection'
R = {
 'CQ-001': {'version': r'3\.40\.1\.1', 'pom': r'pom\.xml', 'mvnw': r'mvnw', 'schema_generator': r'schema-generator|notification-schema', 'arc': r'(quarkus-)?arc\b', 'quinoa': r'[Qq]uinoa', 'graphql': r'graphql|GraphQL'},
 'CQ-003': {'application_scoped': r'ApplicationScoped', 'stereotype_none': r'stereotype'},
 'CQ-004': {'jar': r'quarkus-vertx', 'producer': r'VertxProducer', 'method': r'mutinyEventBus', 'singleton': r'Singleton'},
 'CQ-005': {'root': r'SsarAuthorizer', 'retained': r'retain|not removed|kept|keeps|present|remains|stays', 'proxy_or_bean': r'ClientProxy|_Bean|client proxy|proxy', 'limit': LIMIT},
 'CQ-006': {'type': r'RbacConfig', 'default': r'@?Default', 'any': r'@?Any', 'synthetic': r'[Ss]ynthetic|ConfigMapping|mapping'},
 'CQ-007': {'logger': r'\bLogger\b', 'config': r'RbacConfig', 'factory': r'SsarClientFactory', 'synthetic': r'[Ss]ynthetic|ConfigMapping|mapping', 'app_scoped': r'ApplicationScoped'},
 'CQ-009': {'owner': r'EntityNotificationObserver', 'logger': r'\blogger\b|Logger', 'bus': r'\bbus\b|EventBus', 'none_declared': r'no (declared|own|direct)|not declare|declares no|none of its own|of its own|inherit'},
 'CQ-010': {'event': r'Event<', 'builtin': r'built-?in|ArC|container', 'created': r'CredentialCreated', 'no_app_bean': r'no (application|java|candidate|implementation)|not (declared|defined|a bean)|is not an? |without |does not exist|there is no |absent|none of'},
 'CQ-011': {'diagnostics': r'DiagnosticsHelper', 'bucketed': r'BucketedEventTemplateMetadataService', 's3': r'S3TemplateService', 'download': r'ActiveRecordingsDownload', 'helper': r'RecordingHelper', 'identifier': r'Identifier', 'value': r'BASE64_URL'},
 'CQ-012': {'fqcn': r'io\.smallrye\.common\.annotation\.Identifier', 'value': r'BASE64_URL', 'producer': r'produceBase64Url', 'binding': r'value'},
 'CQ-013': {'default': r'@?Default', 'any': r'@?Any', 'mapping': r'RbacConfig', 'synthetic': r'[Ss]ynthetic|ConfigMapping|mapping'},
 'CQ-015': {'producer': r'produceFileSystem', 'default_bean': r'DefaultBean', 'no_priority': r'priorit|alternative'},
 'CQ-016': {'type': r'InterruptibleReportGenerator', 'request': r'RequestScoped', 'dependent': r'Dependent', 'default': r'@?Default', 'any': r'@?Any'},
 'CQ-018': {'method': r'produceWebClient', 'vertx': r'[Vv]ertx', 'producer': r'VertxProducer', 'mutiny': r'[Mm]utiny'},
 'CQ-023': {'key': r'storage-mode', 'service': r'BucketedEventTemplateMetadataService', 'lookup': r'LookupIfProperty', 'bucket': r'bucket', 'limit': LIMIT},
 'CQ-024': {'creator': r'ConfigMappingCreator|ConfigMapping', 'synthetic': r'[Ss]ynthetic', 'prefix': r'cryostat\.security\.rbac', 'default': r'@?Default', 'any': r'@?Any', 'injection_point': r'InjectionPoint'},
 'CQ-025': {'run_on': r'runSubscriptionOn', 'executor': r'[Ee]xecutor|Infrastructure', 'token': r'rawToken', 'permission': r'permissionName', 'limit': LIMIT},
 'CQ-026': {'transactional': r'Transactional', 'required': r'REQUIRED|Required', 'interceptor': r'TransactionalInterceptorRequired', 'priority': r'\b200\b', 'limit': LIMIT},
 'CQ-027': {'transactional': r'Transactional', 'on_start': r'onStart', 'persist': r'persistAndFlush|persist', 'walk': r'\bwalk\b', 'read_value': r'readValue', 'required': r'REQUIRED|Required|join'},
 'CQ-028': {'event': r'CredentialCreated', 'observer': r'CredentialNotificationObserver', 'method': r'onCredentialCreated', 'post_persist': r'PostPersist', 'after_success': r'AFTER_SUCCESS', 'default': r'@?Default|qualifier'},
 'CQ-029': {'memory': r'MemoryCachingReportsService', 'storage': r'StorageCachingReportsService', 'delegate': r'[Dd]elegate', 'any': r'@?Any', 'p10': r'\b10\b', 'p20': r'\b20\b'},
 'CQ-031': {'route': r'/api/v4\.1/targets/\{targetId\}/reports|targets/\{targetId\}/reports', 'get': r'\bGET\b', 'method': r'getCached', 'target': r'getTargetById', 'entry': r'getEntry', 'injection': r'reportAggregator|AnalysisReportAggregator'},
 'CQ-033': {'scheduled': r'Scheduled', 'key': r'cryostat\.websocket\.ping-period', 'fallback': r'20s', 'ping': r'sendPing|[Pp]ing', 'sessions': r'sessions?', 'limit': LIMIT + r'|scheduler'},
 'CQ-035': {'event': r'StartupEvent', 'observer': r'BucketedEventTemplateMetadataService', 'on_start': r'onStart', 'storage': r'StorageBuckets|createIfNecessary', 'mode': r'storageMode|storage-mode|storage mode|\bmode\b|bucket', 'order': r'priority|order'},
 'CQ-036': {'file': r'application\.properties', 'true': r'\btrue\b', 'service': r'MemoryCachingReportsService', 'listener': r'MemoryCachingReportsListener', 'prod': r'%prod|prod'},
 'CQ-038': {'value': r'\b25\b', 'source': r'explicit-override', 'ordinal': r'\b500\b', 'overridden': r'application-fixture', 'overridden_value': r'\b1000\b'},
 'CQ-041': {'caller': r'SsarAuthorizer', 'caller_method': r'checkSsarPermission', 'line': r'\b138\b', 'mapper': r'PermissionMapper', 'no_default': r'PermissionMapperNoDefaultTest', 'mapper_test': r'PermissionMapperTest', 'verb': r'PermissionMapperVerbDefaultTest', 'optional': r'Optional'},
 'CQ-042': {'producers': r'Producers', 'diagnostics': r'DiagnosticsHelper', 'bucketed': r'BucketedEventTemplateMetadataService', 's3': r'S3TemplateService', 'download': r'ActiveRecordingsDownload', 'helper': r'RecordingHelper', 'value': r'BASE64_URL'},
 'CQ-044': {'mapper': r'PermissionMapper', 'producers': r'Producers', 'config': r'RbacConfig', 'scoped': r'ApplicationScoped', 'ambiguous': r'ambiguous|Veto|remove', 'injection': r'inject'},
 'CQ-045': {'cache': r'cache', 'predestroy': r'PreDestroy', 'authorizer': r'SsarAuthorizer', 'request_end': r'per.request|\brequests?\b', 'test': r'SsarClientCacheTest'},
 'CQ-046': {'no_default': r'PermissionMapperNoDefaultTest', 'mapper_test': r'PermissionMapperTest', 'verb': r'PermissionMapperVerbDefaultTest', 'indirect': r'SsarAuthorizer|RbacHttpAuthenticationMechanism|indirect', 'gap': LIMIT + r'|coverage|covered'},
 'CQ-047': {'profile': r'TestProfile', 'client': r'KubernetesClient', 'bus': r'EventBus', 'inject_mock': r'InjectMock', 'enabled': r'cryostat\.discovery\.kubernetes\.enabled', 'ports': r'port-numbers', 'names': r'port-names'},
 'CQ-048': {'proxy': r'PermissionMapper_ClientProxy', 'arc': r'ArC|Arc\b', 'source': r'PermissionMapper\.java', 'no_edit': r'do not edit|not edit|don.t edit|not be (edited|changed|modified)|no need to|source|would not (provide|persist|survive)|not durable|recreated|regenerated|overwritten'},
 'CQ-049': {'rename': r'parseMappingValue', 'lines': r'PermissionMapper', 'resolve': r'resolve', 'compile': r'compil|build', 'tests': r'test'},
 'CQ-050': {'method': r'getCached', 'aggregator': r'AnalysisReportAggregator', 'singleton': r'Singleton', 'cache': r'reports-aggregator', 'key': r'quarkus\.cache\.caffeine\.reports-aggregator\.expire-after-write', 'value': r'\b10m\b', 'entry': r'getEntry'},
}
FORBIDDEN = {'CQ-010': r'CredentialCreatedEventProducer', 'CQ-025': r'context is lost|context will be lost'}
