#!/usr/bin/env python3
"""Bind templates using source/javac/packaged bytecode; no model, Quill query, application build or app startup."""
import argparse
import collections
import difflib
import gzip
import hashlib
import html
import json
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
SUITE = HERE.parent / 'suite.json'

def sha(data):
    return hashlib.sha256(data).hexdigest()

def command(argv, cwd=None):
    p = subprocess.run([str(a) for a in argv], cwd=cwd, text=True, capture_output=True)
    if p.returncode:
        raise RuntimeError(f'{argv[0]} failed ({p.returncode}): {p.stderr[-2000:]}')
    return p.stdout

def dump(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')

def collect(project, temporary):
    files = command(['git', 'ls-files', 'src/main', 'src/test'], project).splitlines()
    sources = [f for f in files if f.endswith('.java')]
    source_list = temporary / 'sources.txt'
    source_list.write_text('\n'.join(sources) + '\n')
    paths = {project / 'target/classes', project / 'target/test-classes'}
    paths.update((project / 'target/quarkus-app/lib').rglob('*.jar'))
    # The recorded Maven test classpath supplements the packaged production jars.
    # No .quill files, Quill index or Quill-generated classpath are read.
    for report in sorted((project / 'target/surefire-reports').glob('TEST-*.xml')):
        try:
            prop = ET.parse(report).getroot().find("./properties/property[@name='java.class.path']")
        except ET.ParseError:
            continue
        if prop is not None:
            paths.update(Path(p) for p in prop.attrib['value'].split(':') if Path(p).exists())
            break
    cp = ':'.join(str(p) for p in sorted(paths))
    raw = temporary / 'javac.json'
    command(['java', '--class-path', cp, HERE / 'CryostatFacts.java', project, source_list, cp, raw])
    facts = json.loads(raw.read_text())
    if facts['errors']:
        raise ValueError(f'Independent attribution incomplete: {facts["errors"][:3]}')
    return facts, cp, sorted(paths)

def bind(project, output):
    project = project.resolve()
    output.mkdir(parents=True, exist_ok=True)
    suite = json.loads(SUITE.read_text())
    with tempfile.TemporaryDirectory(prefix='cdi-cryostat-oracles-') as folder:
        temporary = Path(folder)
        facts, cp, classpath = collect(project, temporary)
        return assemble(project, output, suite, temporary, facts, cp, classpath)

def assemble(project, output, suite, temporary, facts, cp, classpath):
    revision = command(['git', 'rev-parse', 'HEAD'], project).strip()
    tracked = command(['git', 'ls-files'], project).splitlines()
    scope = list(tracked)
    manifest = {p: sha((project / p).read_bytes()) for p in scope if (project / p).is_file()}
    source_patch = command(['git', 'diff', '--binary', 'HEAD', '--', *scope], project)
    if source_patch:
        raise ValueError('Tracked source/build inputs are dirty; review and explicitly freeze the patch before binding')
    decls = facts['declarations']
    production = [d for d in decls if d['file'].startswith('src/main/')]
    by_id = {d['id']: d for d in decls if d['id']}
    refs = facts['references']
    annotations = lambda d: {a['type']: a['values'] for a in d['annotations']}
    has = lambda d, name: name in annotations(d)
    owned = lambda owner: [d for d in production if d['owner'] == owner and d['id'] != owner]
    fields = lambda owner: [d for d in owned(owner) if d['kind'] == 'FIELD']
    injections = lambda owner: [d for d in fields(owner) if has(d, 'jakarta.inject.Inject')]
    methods = lambda owner: [d for d in owned(owner) if d['kind'] == 'METHOD' and d['start'] >= 0 and d['end'] >= 0]
    uses = lambda target: sorted({(r['file'], r['line'], r['from']) for r in refs if r['to'] == target}, key=str)
    use_files = lambda target, prefix='': sorted({r[0] for r in uses(target) if r[0].startswith(prefix)})
    P = 'io.cryostat.security.rbac.PermissionMapper'
    C = 'io.cryostat.security.rbac.RbacConfig'
    cache = 'io.cryostat.security.rbac.SsarClientCache'
    prod = 'io.cryostat.Producers'
    report = 'io.cryostat.reports.Reports'
    mapper_method = P + '#resolve(java.lang.String)'
    parse_method = P + '#parse(java.lang.String)'
    source_path = lambda owner: by_id[owner]['file']
    generated = project / 'target/quarkus-app/quarkus/generated-bytecode.jar'
    names = sorted(zipfile.ZipFile(generated).namelist())
    synthetic = next(n for n in names if 'RbacConfig_' in n and n.endswith('_Synthetic_Bean.class'))
    generated_cp = str(generated) + ':' + cp
    bytecode = {}
    external = ['io.smallrye.common.annotation.Identifier', 'io.quarkus.narayana.jta.runtime.interceptor.TransactionalInterceptorRequired', 'io.quarkus.vertx.runtime.VertxProducer', 'io.quarkus.arc.InjectableBean', 'io.quarkus.arc.runtime.ConfigMappingCreator']
    for name in external + [synthetic[:-6].replace('/', '.'), 'io.cryostat.reports.AnalysisReportAggregator_Bean']:
        bytecode[name] = command(['javap', '-classpath', generated_cp, '-p', '-c', '-v', name])
    bcdir = output / 'bytecode'
    bcdir.mkdir(exist_ok=True)
    for name, text in bytecode.items():
        (bcdir / (name + '.txt')).write_text(text)
    # Track external artifact identities for exactly the inspected framework classes.
    artifact_origins = {}
    for path in classpath:
        if path.suffix != '.jar':
            continue
        with zipfile.ZipFile(path) as jar:
            contents = set(jar.namelist())
            for cls in external:
                if cls.replace('.', '/') + '.class' in contents:
                    properties = {}
                    for n in contents:
                        if n.startswith('META-INF/maven/') and n.endswith('/pom.properties'):
                            properties[n] = jar.read(n).decode(errors='replace').splitlines()
                    artifact_origins[cls] = {'jar': path.name, 'sha256': sha(path.read_bytes()), 'maven_properties': properties}
    # Persist reproducible independent facts, including every declaration used by absence proofs.
    raw_bytes = json.dumps(facts, ensure_ascii=False, sort_keys=True).encode()
    with open(output / 'javac-facts.json.gz', 'wb') as f:
        f.write(gzip.compress(raw_bytes, mtime=0))
    dump(output / 'source-manifest.json', manifest)
    dump(output / 'artifact-origins.json', artifact_origins)
    dependency_manifest=[]
    for path in classpath:
        if path.is_file():
            dependency_manifest.append({'path':str(path),'sha256':sha(path.read_bytes())})
        elif path.is_dir():
            dependency_manifest.extend({'path':str(f),'sha256':sha(f.read_bytes())} for f in sorted(path.rglob('*')) if f.is_file())
    dump(output/'dependency-manifest.json',dependency_manifest)
    dump(output / 'generated-class-names.json', names)
    # Baseline/reference patch checks use an isolated directory, not the Cryostat checkout.
    patch_checks = []
    original = (project / source_path(P)).read_text()
    candidate = original.replace('private K8sResourceVerb parse(String value)', 'private K8sResourceVerb parseMappingValue(String value)').replace('this::parse', 'this::parseMappingValue').replace('Optional.of(parse(value))', 'Optional.of(parseMappingValue(value))')
    if candidate == original or 'private K8sResourceVerb parse(String value)' in candidate:
        raise AssertionError('Reference patch did not exercise the selected method')
    patch = ''.join(difflib.unified_diff(original.splitlines(True), candidate.splitlines(True), fromfile='a/' + source_path(P), tofile='b/' + source_path(P), n=0))
    (output / 'reference-rename.patch').write_text(patch)
    for name, text in [('baseline', original), ('reference_rename', candidate)]:
        work = temporary / name
        work.mkdir()
        source = work / 'PermissionMapper.java'
        source.write_text(text)
        command(['javac', '-proc:none', '-classpath', cp, '-d', work, source, HERE / 'MapperProbe.java'])
        value = json.loads(command(['java', '-classpath', str(work) + ':' + cp, 'io.cryostat.security.rbac.MapperProbe']))
        patch_checks.append({'variant': name, 'compile': 'passed', **value})
    config_probe = json.loads(command(['java', '--class-path', cp, HERE / 'ConfigProbe.java']))
    dump(output / 'local-probes.json', {'patch': patch_checks, 'config': config_probe, 'limitations': ['Pure mapping behavior checked; Quarkus application/container and integration tests were not started.']})
    # Fully qualified annotation predicates distinguish CDI @Produces from REST @Produces.
    absent = {
        8: ('Application-owned initializer @Inject methods', [d for d in production if d['kind'] == 'METHOD' and d.get('name') != '<init>' and has(d, 'jakarta.inject.Inject')]),
        17: ('Application-owned CDI producer fields', [d for d in production if d['kind'] == 'FIELD' and has(d, 'jakarta.enterprise.inject.Produces')]),
        19: ('Application-owned disposer parameters', [d for d in production if d['kind'] == 'METHOD' and any(any(a['type'] == 'jakarta.enterprise.inject.Disposes' for a in p['annotations']) for p in d.get('parameters', []))]),
        21: ('Application-owned build-profile bean conditions', [d for d in production if set(annotations(d)) & {'io.quarkus.arc.profile.IfBuildProfile', 'io.quarkus.arc.profile.UnlessBuildProfile'}]),
        22: ('Application-owned build-property bean conditions', [d for d in production if set(annotations(d)) & {'io.quarkus.arc.properties.IfBuildProperty', 'io.quarkus.arc.properties.UnlessBuildProperty'}]),
        34: ('Application-owned MicroProfile Reactive Messaging handlers', [d for d in production if set(annotations(d)) & {'org.eclipse.microprofile.reactive.messaging.Incoming', 'org.eclipse.microprofile.reactive.messaging.Outgoing'}]),
    }
    assert all(not hits for _, hits in absent.values())
    absence = {f'CQ-{n:03}': {'predicate': label, 'matches': [], 'scope': 'All tracked src/main Java declarations and parameters, attributed with zero compiler errors; dependency/test components excluded by declared application-owned scope.', 'java_files': len({d['file'] for d in production}), 'inventory': 'javac-facts.json.gz', 'counterexamples_not_equivalent': {8: 'Four @Inject constructors are present; they are not initializer methods.', 17: 'Fifteen CDI producer methods and REST @Produces exist; neither is a producer field.', 19: '@PreDestroy callbacks exist; they are not @Disposes parameters.', 21: 'TestProfile and %profile config do not imply @IfBuildProfile bean conditions.', 22: '@LookupIfProperty is runtime programmatic lookup filtering, not @IfBuildProperty.', 34: 'Vert.x @ConsumeEvent and WebSockets exist; they are not Reactive Messaging @Incoming/@Outgoing channels.'}[n]} for n, (label, _) in absent.items()}
    # Guard the explanatory counterexamples against drift too.
    assert len([d for d in production if d['kind'] == 'METHOD' and has(d, 'jakarta.enterprise.inject.Produces')]) == 15
    assert len([d for d in production if d['kind'] == 'METHOD' and d.get('name') == '<init>' and has(d, 'jakarta.inject.Inject')]) == 4
    dump(output / 'not-applicable.json', absence)
    # All literal anchors are public task inputs; all expected answers stay in the oracle document.
    bindings = {
        1: {}, 2: {'module': 'production-пакете io.cryostat.security.rbac, только непосредственно в этом пакете, без subpackages'},
        3: {'bean': P}, 4: {'external_type': 'io.vertx.mutiny.core.eventbus.EventBus'}, 5: {'bean': P},
        6: {'field_site': P + '#config', 'profile': 'prod'},
        8: {}, 9: {'bean': 'io.cryostat.credentials.events.CredentialNotificationObserver'},
        10: {'typed_site': 'io.cryostat.credentials.Credential.Listener#credentialCreatedEvent'},
        11: {'bean': 'org.apache.commons.codec.binary.Base64, создаваемый io.cryostat.Producers#produceBase64Url()'},
        12: {'qualified_site': 'io.cryostat.events.S3TemplateService#base64Url'},
        13: {'default_site': P + '#config'},
        14: {'lookup_site': 'io.cryostat.events.S3TemplateService#metadataService'},
        15: {'alternative_site': 'io.cryostat.recordings.RecordingHelper#fs', 'profile': 'prod'},
        16: {'producer_method': prod + '#produceInterruptibleReportGenerator()'},
        18: {'producer_method': prod + '#produceWebClient(io.vertx.mutiny.core.Vertx)'},
        20: {'bean': cache},
        23: {'runtime_lookup': 'io.cryostat.events.S3TemplateService#metadataService'},
        24: {'synthetic_type': C}, 25: {'async_method': 'io.cryostat.security.rbac.SsarAuthorizer#checkSsarPermission(java.lang.String,java.lang.String)'},
        26: {'intercepted_method': 'io.cryostat.credentials.Credentials#onStart(io.quarkus.runtime.StartupEvent)'},
        27: {'transactional_method': 'io.cryostat.credentials.Credentials#onStart(io.quarkus.runtime.StartupEvent)'},
        28: {'event_site': 'io.cryostat.credentials.Credential.Listener#postPersist(io.cryostat.credentials.Credential)'},
        29: {'decorated_type': 'io.cryostat.reports.ReportsService'}, 30: {'cycle_root': P},
        31: {'endpoint': 'GET /api/v4.1/targets/{targetId}/reports'},
        32: {'secured_endpoint': 'GET /api/v4.1/targets/{targetId}/reports'},
        33: {'scheduled_method': 'io.cryostat.ws.MessagingServer#pingClients()'},
        35: {'startup_hook': 'io.cryostat.events.BucketedEventTemplateMetadataService#onStart(io.quarkus.runtime.StartupEvent)'},
        36: {'config_key': 'cryostat.services.reports.memory-cache.enabled', 'profile': 'prod'},
        37: {'config_mapping': C},
        38: {'config_key': 'cryostat.security.rbac.cache.maximum-size', 'config_context': 'prod; только application-fixture ordinal 100 (1000) и explicit-override ordinal 500 (25), без ambient env/system properties'},
        39: {'config_key': 'cryostat.services.reports.memory-cache.enabled'},
        40: {'config_key': 'cryostat.services.reports.memory-cache.enabled', 'new_config_key': 'cryostat.services.reports.memory-cache.active'},
        41: {'method': mapper_method},
        42: {'qualified_bean': prod + '#produceBase64Url()', 'new_qualifier': '@io.smallrye.common.annotation.Identifier("BASE64_URL_V2")'},
        43: {'interface': 'io.cryostat.reports.ReportsService'},
        44: {'bean': P}, 45: {'bean': cache, 'new_scope': '@RequestScoped'},
        46: {'change_target': mapper_method},
        47: {'test_class': 'io.cryostat.discovery.KubeEndpointSlicesDiscoveryTest'},
        48: {'generated_type': 'io.cryostat.security.rbac.PermissionMapper_ClientProxy'},
        49: {'rename_method': parse_method, 'new_method_name': 'parseMappingValue'},
        50: {'flow_anchor': 'GET /api/v4.1/targets/{targetId}/reports', 'profile': 'prod'},
    }
    # Generic type identity and exact qualified references are computed, not copied from Quill.
    rbac_classes = [d for d in production if d['kind'] == 'CLASS' and Path(d['file']).parent.as_posix() == 'src/main/java/io/cryostat/security/rbac' and any(a in annotations(d) for a in ['jakarta.enterprise.context.ApplicationScoped', 'jakarta.inject.Singleton'])]
    qualifier_sites = [d for d in production if d['kind'] == 'FIELD' and has(d, 'jakarta.inject.Inject') and d.get('type') == 'org.apache.commons.codec.binary.Base64' and annotations(d).get('io.smallrye.common.annotation.Identifier', {}).get('value') == '"BASE64_URL"']
    report_impl = sorted(d['id'] for d in production if d['kind'] == 'CLASS' and 'ReportsService' in d.get('implements', []))
    report_method_targets={d['id'] for owner in ['io.cryostat.reports.ReportsService']+report_impl for d in methods(owner) if d.get('name')!='<init>'}
    report_method_users=sorted({r['file'] for r in refs if r['to'] in report_method_targets})
    report_users = sorted(set(use_files('io.cryostat.reports.ReportsService')+report_method_users))
    method_users = [{'file': f, 'line': l, 'caller': owner} for f, l, owner in uses(mapper_method)]
    parse_users = [{'file': f, 'line': l, 'caller': owner} for f, l, owner in uses(parse_method)]
    cache_annotation = annotations(by_id['io.cryostat.reports.MemoryCachingReportsService#memoryCache'])['org.eclipse.microprofile.config.inject.ConfigProperty']['name'].strip('"')
    assert cache_annotation == 'cryostat.services.reports.memory-cache.enabled'
    app_config = 'src/main/resources/application.properties'
    config_text = (project / app_config).read_text()
    assert 'cryostat.services.reports.memory-cache.enabled=true' in config_text
    config_lines = lambda key: [{'file': p, 'line': i, 'text': line.strip()} for p in scope if (project / p).is_file() and (p.endswith(('.java', '.properties', '.yaml', '.yml', '.xml', '.md', '.adoc', '.sh', '.env')) or p == 'pom.xml') for i, line in enumerate((project / p).read_text(errors='replace').splitlines(), 1) if key in line and not line.lstrip().startswith(('#', '//', '*'))]
    base64_sites = sorted(d['id'] for d in qualifier_sites)
    mapping_properties = {d['name']: {'type': d['return_type'], 'annotations': d['annotations']} for d in methods(C) if d['name'] != '<init>'}
    mapping_groups = {owner: [{'name': d['name'], 'type': d['return_type'], 'annotations': d['annotations']} for d in methods(owner)] for owner in [C + '.CacheConfig', C + '.DecisionCacheConfig']}
    oracle = {}
    def add(n, expected, files, forbidden=(), notes=()):
        key = f'CQ-{n:03}'
        if n in absent:
            raise AssertionError('Do not create a scored oracle for N/A')
        fields_expected = next(t['expected_fields'] for t in suite['tasks'] if t['id'] == key)
        if set(expected) != set(fields_expected):
            raise AssertionError((key, set(fields_expected) - set(expected), set(expected) - set(fields_expected)))
        for f in files:
            assert f in manifest, f
        oracle[key] = {'expected': expected, 'evidence': [{'path': f, 'sha256': manifest[f], 'declaration_lines': sorted({d['line'] for d in decls if d['file']==f and d['line']>0})} for f in sorted(set(files))], 'forbidden_claims': list(forbidden), 'limitations': list(notes), 'review_basis': 'Independent javac-attributed declarations/references plus inspected source and packaged framework bytecode; no Quill answer used.', 'runtime_proven': False}
    sf = lambda *owners: [source_path(o) for o in owners]
    pom = ET.parse(project/'pom.xml').getroot(); ns={'m':'http://maven.apache.org/POM/4.0.0'}
    direct_extensions=[{'artifact':d.findtext('m:artifactId',namespaces=ns),'group':d.findtext('m:groupId',namespaces=ns),'scope':d.findtext('m:scope',default='compile',namespaces=ns)} for d in pom.findall('./m:dependencies/m:dependency',ns) if d.findtext('m:artifactId',default='',namespaces=ns).startswith('quarkus-')]
    add(1, dict(modules=['root application (not a reactor aggregator)', 'schema-generator/pom.xml: separate notification-schema-generator project'], build_entrypoints=['pom.xml', 'mvnw'], framework_version='3.40.1.1', extensions=direct_extensions), ['pom.xml', 'schema-generator/pom.xml'], ['schema-generator is an active root reactor module'], ['Extensions are all direct quarkus-* dependencies in the root descriptor, including test-scope entries; transitive extension inventory is not claimed.'])
    add(2, dict(beans=[d['id'] for d in sorted(rbac_classes,key=lambda d:d['id'])] + [C], bean_kind={**{d['id']:'CLASS' for d in rbac_classes}, C:'SYNTHETIC ConfigMapping'}, effective_scope={**{d['id']:('@Singleton' if has(d,'jakarta.inject.Singleton') else '@ApplicationScoped') for d in rbac_classes}, C:'@Dependent (InjectableBean default)'}, scope_boundary='Immediate production package io.cryostat.security.rbac; no subpackages, test beans or dependency beans; no producer method/field declared there'), sf(*[d['id'] for d in rbac_classes], C), ['ProxyHeaders and RbacMode are CDI beans merely because they are classes/enums'])
    add(3, dict(discovery_reasons=['jakarta.enterprise.context.ApplicationScoped'], stereotypes=[], effective_scope='@ApplicationScoped'), sf(P))
    add(4, dict(artifact=artifact_origins['io.quarkus.vertx.runtime.VertxProducer'], registration_mechanism='io.quarkus.vertx.runtime.VertxProducer#mutinyEventBus(io.vertx.mutiny.core.Vertx), @Produces @Singleton', evidence_level='Packaged generated producer Bean plus javap of runtime extension producer; not observed invocation'), sf('io.cryostat.events.EntityNotificationObserver'))
    add(5, dict(retention_roots=['SsarAuthorizer#permissionMapper injection'], removal_state='Retained in captured production augmentation: PermissionMapper_Bean and PermissionMapper_ClientProxy present', dynamic_lookup_limits=['Future configurations and dynamic/reflection consumers are not proven globally; generated artifact is hashed and not a runtime trace']), sf(P,'io.cryostat.security.rbac.SsarAuthorizer'), ['absence of direct Java new implies unused', 'retained in all possible profiles'])
    add(6, dict(required_type=C, qualifiers=['@Default','@Any on bean; @Default requirement at field'], candidates=[C+' synthetic ConfigMapping bean'], resolution='unique statically identified mapping bean in captured prod context', exclusions=['RbacConfig nested groups are not substitute root mapping beans']), sf(P,C), ['RbacConfig is missing because it has no bean scope annotation'])
    add(9, dict(declared_sites=[], inherited_sites=[d['id'] for d in injections('io.cryostat.events.EntityNotificationObserver')], override_effects='Simple<Credential> and concrete overrides change payload construction, not logger/EventBus injection declarations'), sf('io.cryostat.credentials.events.CredentialNotificationObserver','io.cryostat.events.EntityNotificationObserver'))
    add(10, dict(java_candidates=[], bean_types=['jakarta.enterprise.event.Event<io.cryostat.credentials.events.CredentialEvents.CredentialCreated> builtin CDI injection'], eligible_candidates=['ArC builtin Event provider parameterized by required event type'], resolution='builtin typed event injection; no application @Typed restriction on this site'), sf('io.cryostat.credentials.Credential.Listener'), ['all Event<T> variants are the same event type'], ['This project supplies generics but no application @Typed annotation; the @Typed subfeature is not claimed as exercised.'])
    add(11, dict(direct_sites=base64_sites, qualifier_constraints=['io.smallrye.common.annotation.Identifier(value="BASE64_URL")'], dynamic_sites=[], completeness='All application source direct Base64 injection sites with this identifier; dynamic/reflection consumers not globally proven'), sf(prod,*[d['owner'] for d in qualifier_sites]))
    add(12, dict(qualifier_fqcn='io.smallrye.common.annotation.Identifier', binding_members={'value':'BASE64_URL'}, nonbinding_members=[], matches=[prod+'#produceBase64Url()']), sf(prod,'io.cryostat.events.S3TemplateService'), ['Identifier("other") matches by short annotation name alone'])
    add(13, dict(explicit_qualifiers=[], implicit_qualifiers={'field':['@Default'], 'mapping_bean':['@Default','@Any']}, resolution='RbacConfig synthetic mapping bean; @Any does not imply @Default and @Named is absent here'), sf(P,C))
    add(14, dict(lookup_type='Instance<io.cryostat.events.BucketedEventTemplateMetadataService>', selection_constraints=['@Default', 'LookupIfProperty cryostat.storage.metadata.event-templates.storage-mode=bucket'], candidate_set=['io.cryostat.events.BucketedEventTemplateMetadataService when condition matches'], unknown_inputs=['Ambient runtime config not supplied; do not assert currently selected instance']), sf('io.cryostat.events.S3TemplateService','io.cryostat.events.BucketedEventTemplateMetadataService'))
    add(15, dict(enabled_candidates=[prod+'#produceFileSystem()'], priorities=[], fallback_rules=['@DefaultBean: suppressed by eligible non-default bean of matching types/qualifiers'], resolution='Application prod source has one matching fallback producer; test mocks are excluded'), sf(prod,'io.cryostat.recordings.RecordingHelper'), ['DefaultBean is a CDI @Alternative', 'test substitutions are enabled in prod'])
    add(16, dict(produced_types=['io.cryostat.core.reports.InterruptibleReportGenerator and CDI type closure'], qualifiers=['@Default','@Any'], product_scope='@RequestScoped', declaring_scope='@Dependent (producer-declaring class has no explicit scope)'), sf(prod), ['product is ApplicationScoped because another producer is', 'thread pool branch can be chosen without processor count'])
    add(18, dict(producer_parameter_sites=[prod+'#produceWebClient(io.vertx.mutiny.core.Vertx):vertx'], declaring_bean_dependencies=[], resolutions={'vertx':'Mutiny Vertx producer in io.quarkus.vertx.runtime.VertxProducer; distinct from core Vertx'}), sf(prod))
    add(20, dict(scope='@ApplicationScoped', lifecycle_callbacks=['@PreDestroy shutdown(); no @PostConstruct in this class'], ownership='Creates Caffeine cache; eviction/explicit close behavior in withClient/shutdown; disabled cache is null', conditional_timing='Constructor use, lazy contextual creation and shutdown occur per application lifecycle; no exact wall-clock creation proof'), sf(cache))
    add(23, dict(runtime_inputs=['cryostat.storage.metadata.event-templates.storage-mode'], possible_targets=['BucketedEventTemplateMetadataService for bucket-mode lookup'], activation_checks=['@LookupIfProperty(name=..., stringValue="bucket")', 'onStart also returns unless mode=bucket'], uncertainty=['Runtime selection property not supplied; lookup condition is not build-time removal or a rule for ordinary injection']), sf('io.cryostat.events.S3TemplateService','io.cryostat.events.BucketedEventTemplateMetadataService'))
    add(24, dict(extension_origin='Quarkus ArC ConfigMapping integration; io.quarkus.arc.runtime.ConfigMappingCreator', registration=synthetic, types=[C,'java.lang.Object'], qualifiers=['@Default','@Any'], dependencies=['builtin InjectionPoint synthetic injection; config mapping prefix cryostat.security.rbac']), sf(C), ['RbacConfig is a manually annotated ApplicationScoped class'], ['Synthetic types/creator/InjectionPoint verified from generated bytecode; no application startup.'])
    add(25, dict(async_boundary='Uni subscription shifted by runSubscriptionOn(Infrastructure.getDefaultExecutor())', required_context=['Injected collaborators are application-scoped; no injected SecurityIdentity field in SsarAuthorizer'], propagation='rawToken and permissionName are explicit arguments captured for the asynchronous check', risks=['Ambient request-context propagation and network SSAR result are not proven by source; do not infer context loss merely from async']), sf('io.cryostat.security.rbac.SsarAuthorizer',cache,'io.cryostat.security.rbac.SsarDecisionCache'))
    add(26, dict(bindings=['jakarta.transaction.Transactional(REQUIRED default)'], interceptors=['io.quarkus.narayana.jta.runtime.interceptor.TransactionalInterceptorRequired'], priorities={'TransactionalInterceptorRequired':200}, invocation_limits=['Declared observer interceptor applicability verified; actual execution not observed']), sf('io.cryostat.credentials.Credentials'))
    add(27, dict(transaction_attributes='@Transactional REQUIRED on Credentials.onStart', bounded_calls=sorted({r['to'] for r in refs if r['from']=='io.cryostat.credentials.Credentials#onStart(io.quarkus.runtime.StartupEvent)' and r['tree_kind']=='MEMBER_SELECT' and '#' in r['to'] and '(' in r['to']}), propagation='joins existing transaction or starts one around intercepted observer call; no nested propagation-changing application call established in this bounded path', conditional_paths=['walk IO failure and per-file persist exceptions handled; no runtime commit result asserted']), sf('io.cryostat.credentials.Credentials','io.cryostat.DeclarativeConfiguration'))
    add(28, dict(event_type='CredentialEvents.CredentialCreated', qualifiers=['implicit @Default event injection'], observer_sites=['CredentialNotificationObserver#onCredentialCreated(CredentialCreated)'], delivery_conditions=['synchronous Event.fire in @PostPersist', '@Observes(during=AFTER_SUCCESS)', 'transaction outcome controls transactional observer notification; actual delivery not observed']), sf('io.cryostat.credentials.Credential.Listener','io.cryostat.credentials.events.CredentialNotificationObserver'))
    add(29, dict(decorators=['io.cryostat.reports.MemoryCachingReportsService','io.cryostat.reports.StorageCachingReportsService'], delegate_site=['MemoryCachingReportsService#delegate','StorageCachingReportsService#delegate'], qualifiers=['@Inject @Delegate @Any ReportsService'], enabled_order=['MemoryCachingReportsService priority 10','StorageCachingReportsService priority 20']), sf('io.cryostat.reports.MemoryCachingReportsService','io.cryostat.reports.StorageCachingReportsService','io.cryostat.reports.ReportsServiceImpl'), ['MemoryCaching/StorageCaching are ordinary independent ReportsService target beans'])
    add(30, dict(cycles=[], edge_kinds=[P+' --field injection--> '+C], scope_proxy_effects='PermissionMapper ApplicationScoped normal proxy; RbacConfig mapping is synthetic and its builtin InjectionPoint edge is a leaf in the application-owned graph', validation_state='No cycle in the bounded application-owned dependency graph rooted here; no global container cycle claim'), sf(P,C), ['graph acyclicity proves all runtime creation paths globally valid'])
    add(31, dict(route='/api/v4.1/targets/{targetId}/reports', http_method='GET', resource_method=report+'#getCached(long)', direct_calls=['Target.getTargetById(targetId)','AnalysisReportAggregator.getEntry(target.jvmId)'], injection_sites=[report+'#reportAggregator']), sf(report,'io.cryostat.reports.AnalysisReportAggregator'), ['this GET starts report generation or directly calls ReportsService.reportFor'])
    add(32, dict(security_annotations=['@PermissionsAllowed(value="reports:read", inclusive=true)'], policy_inputs=['RBAC mode, configured permissions/defaults, authenticated SecurityIdentity'], checks=['Quarkus permission interceptor checks reports:read; non-permissive SSAR checks depend on runtime identity/server response'], unknown_runtime_identity=['No token, role membership or Kubernetes response supplied; do not claim endpoint access is granted']), sf(report,'io.cryostat.security.rbac.SsarAuthorizer','io.cryostat.security.rbac.RbacHttpAuthenticationMechanism'))
    add(33, dict(trigger='@Scheduled(every="${cryostat.websocket.ping-period:20s}")', config_keys=['cryostat.websocket.ping-period and scheduler settings'], dependencies=['sessions collection; websocket basicRemote.sendPing; Logger on IOException'], execution_conditions=['scheduled invocation depends on scheduler configuration and runtime', '20s is annotation fallback, not proof of current override']), sf('io.cryostat.ws.MessagingServer'))
    add(35, dict(lifecycle_event='StartupEvent', observer='BucketedEventTemplateMetadataService.onStart', dependencies=['injected StorageBuckets; createIfNecessary(bucket)'], ordering_constraints=['returns before bucket initialization when storageMode != bucket', 'no priority annotation or complete cross-observer order established']), sf('io.cryostat.events.BucketedEventTemplateMetadataService'))
    cfg_key='cryostat.services.reports.memory-cache.enabled'
    add(36, dict(definitions=[{'file':app_config,'value':'true'}], consumers=['MemoryCachingReportsService#memoryCache','MemoryCachingReportsListener#memoryCache'], defaults='true in application.properties, not a ConfigProperty defaultValue', profile_overrides='No explicit %prod override of this key in tracked config; ambient env/system sources unknown'), [app_config]+sf('io.cryostat.reports.MemoryCachingReportsService','io.cryostat.reports.MemoryCachingReportsListener'), ['default=true is necessarily the live production value'])
    add(37, dict(prefix='cryostat.security.rbac', property_paths={'root':mapping_properties,'nested':mapping_groups}, value_types='See exact compiler-resolved types in property_paths; Optional<List<String>> for agent-permissions', defaults={'cache.maximum-size':'1000','cache.expire-after-access':'5m','decision-cache.ttl':'1m','decision-cache.maximum-size':'10000'}, open_map_segments=['permissions.<arbitrary quoted permission key>: String']), sf(C)+[app_config])
    add(38, dict(effective_value_or_redacted=25, winning_source={'name':'explicit-override','ordinal':500}, overridden_sources=[{'name':'application-fixture','ordinal':100,'value':1000}], unknown_sources=[]), [app_config], notes=['ConfigProbe ran with two explicit in-memory sources only; no ambient sources. This is not the current value of the user process.'])
    add(39, dict(phase='Application runtime @ConfigProperty field injection; not an extension build-time config definition', extension_definition='Application-owned key, read by reports caching beans', requires_rebuild=False, version_evidence='Quarkus 3.40.1.1; change runtime configuration before bean creation/restart without recompiling; does not promise live mutation of an already-injected boolean'), sf('io.cryostat.reports.MemoryCachingReportsService','io.cryostat.reports.MemoryCachingReportsListener')+[app_config])
    literal_hits=config_lines(cfg_key)
    constant='io.cryostat.ConfigProperties#REPORTS_MEMORY_CACHE_ENABLED'
    add(40, dict(must_edit=sorted({p['file'] for p in literal_hits}), verify_only=use_files(constant), external_contracts=['Deployment/env key CRYOSTAT_SERVICES_REPORTS_MEMORY_CACHE_ENABLED and operator/user configuration must be migrated if used'], dynamic_key_limits=['External deployments not in checkout cannot be enumerated as a complete set']), sorted({p['file'] for p in literal_hits})+use_files(constant))
    add(41, dict(direct_callers=[r for r in method_users if r['file'].startswith('src/main/')], overrides=[], contracts=['public instance resolve(String) returning Optional<K8sResourceVerb>; not an interface override'], must_edit=sorted(set(sf(P)+use_files(mapper_method))), verify_only=['reflection/external consumers beyond tracked snapshot not proven absent']), sf(P)+use_files(mapper_method), ['all transitive callers must edit for a direct signature change'])
    add(42, dict(binding_sites=base64_sites, must_edit=sorted({source_path(prod)}|{d['file'] for d in qualifier_sites}), verify_only=['producer/direct consumer qualifiers, Identifier literals or dynamic lookups outside bounded source'], ambiguity_risks=['Changing only producer qualifier leaves old consumers unsatisfied; qualifier member value is binding']), sf(prod,*[d['owner'] for d in qualifier_sites]))
    add(43, dict(implementations=report_impl, di_sites=[d['id'] for d in production if d['kind']=='FIELD' and d.get('type')=='io.cryostat.reports.ReportsService' and has(d,'jakarta.inject.Inject')], producer_decorator_sites=['MemoryCachingReportsService#delegate','StorageCachingReportsService#delegate; no application @Produces returning ReportsService'], method_users=report_method_users, boundary='Tracked application/test source; generated proxies derived, external clients excluded'), report_users, ['only ReportsServiceImpl is impacted; decorators are unrelated'])
    add(44, dict(preserved_contract=['@ApplicationScoped product','@Default/@Any','RbacConfig initialization before use','resolve/toPermission behavior'], producer_dependencies=[C], must_edit=[source_path(P),'src/main/java/io/cryostat/Producers.java'], lifecycle_risks=['Produced new PermissionMapper does not automatically get its field injected', 'remove/veto old class bean or resolution becomes ambiguous', 'no lifecycle callback in PermissionMapper to transfer'], verification=['attribution/compile','prod container resolution','focused mapping behavior tests']), sf(P,prod,C))
    add(45, dict(state_risks=['Cache<String,KubernetesClient> becomes request-lived; sharing/eviction and shutdown semantics change'], proxy_constraints=['Both scopes are normal scopes; check constructability/transform settings, not source modifier guesses alone'], lifetime_changes=['@PreDestroy shutdown closes cached clients at request end, not application end'], affected_consumers=use_files(cache)), sf(cache)+use_files(cache), ['scope change alone proves thread safety or correct async lifetime'])
    mapper_tests=use_files(mapper_method,'src/test/')
    add(46, dict(direct_tests=mapper_tests, indirect_tests=['SSAR authorization and HTTP RBAC tests need review of resolution behavior'], heuristic_tests=['Tests of callers without compiler reference are heuristic until coverage/trace confirms'], coverage_gaps=['No complete runtime/HTTP coverage matrix collected; source references alone do not prove coverage']), sf(P)+mapper_tests)
    add(47, dict(test_profile='KubeEndpointSlicesDiscoveryTest.TestProfile', mock_sites=['KubernetesClient client (@InjectMock, convertScopes=true)','io.vertx.mutiny.core.eventbus.EventBus bus (@InjectMock, convertScopes=true)'], alternative_beans=[], config_overrides={'cryostat.discovery.kubernetes.enabled':'true','cryostat.discovery.kubernetes.port-numbers':'9091','cryostat.discovery.kubernetes.port-names':'jfr-jmx,other-port'}, limits=['Mocks do not validate real Kubernetes transport or event-bus delivery']), ['src/test/java/io/cryostat/discovery/KubeEndpointSlicesDiscoveryTest.java'])
    add(48, dict(generated_artifact='generated-bytecode.jar!/io/cryostat/security/rbac/PermissionMapper_ClientProxy.class', generator='Quarkus ArC augmentation', source_inputs=[source_path(P)], edit_target=source_path(P)), sf(P), ['edit generated proxy class instead of source'])
    add(49, dict(patch='Rename private parse(String) to parseMappingValue(String), including method references and direct call; no public API change', updated_references=parse_users, behavior_preserved={'cases':12,'baseline':'passed','reference_rename':'passed'}, compile_result='baseline and reference-rename source independently compiled in temporary directories', test_result='MapperProbe: 12/12 each; future tested-model patch must independently compile and pass these tests too'), sf(P)+['src/test/java/io/cryostat/security/rbac/PermissionMapperTest.java'], ['reference patch is the model result', 'pure Java probe proves complete Quarkus integration'])
    add(50, dict(step_facts={'1':'Reports#getCached(long), GET route','2':{'injection':'Reports#reportAggregator','target':'AnalysisReportAggregator','effective_scope':'@Singleton (Quarkus REST resource default; verified generated Bean.getScope)','cache_qualifier':'@CacheName("reports-aggregator")'},'3':{'cache_name':'reports-aggregator','configuration_key':'quarkus.cache.caffeine.reports-aggregator.expire-after-write','repository_value':'10m','boundary':'No @ConfigProperty fields in AnalysisReportAggregator; cache name is an injection qualifier, not a config key'},'4':'getCached -> Target.getTargetById and reportAggregator.getEntry; no on-demand report generation','5':'Public getEntry(String) signature impact limited to compiler-resolved callers/overrides; generated proxies rebuild, not hand edit','6':{'candidate_test':'src/test/java/io/cryostat/reports/AnalysisReportAggregatorTest.java','direct_getEntry_references':use_files('io.cryostat.reports.AnalysisReportAggregator#getEntry(java.lang.String)','src/test/'),'coverage':'Test relevance without runtime coverage is heuristic, not proof of full endpoint coverage'}}, consistent_bindings={'entrypoint':report+'#getCached(long)','service':'io.cryostat.reports.AnalysisReportAggregator','method':'io.cryostat.reports.AnalysisReportAggregator#getEntry(java.lang.String)'}, cross_step_consistency='Same service/method/target throughout; cached GET must not become report generation or a different overload', change_plan='Plan only; direct caller edits, regenerated artifacts and focused tests, no production mutation'), sf(report,'io.cryostat.reports.AnalysisReportAggregator')+[app_config]+use_files('io.cryostat.reports.AnalysisReportAggregator#getEntry(java.lang.String)'))
    bindings[7]={'constructor_site':cache+'#<init>(org.jboss.logging.Logger,io.cryostat.security.rbac.RbacConfig,io.cryostat.security.rbac.SsarClientFactory)'}
    add(7, dict(constructor_signature=bindings[7]['constructor_site'], parameters=by_id[bindings[7]['constructor_site']]['parameters'], resolutions={'logger':'builtin Logger producer','config':C+' synthetic mapping','clientFactory':'io.cryostat.security.rbac.SsarClientFactory @ApplicationScoped'}), sf(cache,C,'io.cryostat.security.rbac.SsarClientFactory'))
    dump(output/'not-applicable.json',absence)
    public=[];bound=[]
    for t in suite['tasks']:
        n=int(t['id'][3:]);b=bindings.get(n,{})
        if n in absent:
            bound.append({'id':t['id'],'status':'not_applicable','reason':absence[t['id']], 'bindings':None})
            continue
        assert set(b)==set(t['parameters']), (t['id'],set(t['parameters'])-set(b),set(b)-set(t['parameters']))
        item={'id':t['id'],'category':t['category'],'mode':t['mode'],'turns':t['turns'],'status':'applicable','bindings':b,'prompts':{lang:prompt.format(**b) for lang,prompt in t['prompts'].items()},'oracle_id':t['id']}
        if 'steps' in t:
            item['steps']=[{'step':s['step'],'prompt':s['prompt'].format(**b)} for s in t['steps']]
        bound.append(item)
        public.append({k:v for k,v in item.items() if k not in ['bindings','oracle_id','status']})
    assert set(oracle)=={t['id'] for t in bound if t['status']=='applicable'}
    dump(output/'bound-suite.json',{'schema_version':1,'template_sha256':sha(SUITE.read_bytes()),'project_revision':revision,'cases':bound,'oracle_file':'oracles.json','execution_status':'not_run','runner_integration':'No automatic compatibility with older runners; load public-tasks only and isolate all oracle files.'})
    public_context={'revision':revision,'profile':'prod','scope':'Enumerations are bounded to application-owned production/test source unless dependencies or generated mechanisms are explicitly requested. Packaged framework evidence supplements those questions. No ambient env/secret values are assumed.','config_probe_context':'CQ-038 uses only explicit in-memory source ordinal100=1000, ordinal500=25.','linked_change':'CQ-050 step5: change AnalysisReportAggregator.getEntry(String) signature; plan only.','safety':'Read-only except CQ-049, whose patch must be applied only in a disposable checkout. No permission to start external services or read process secrets.'}
    dump(output/'public-tasks.json',{'project_revision':revision,'context':public_context,'tasks':public})
    dump(output/'oracles.json',{'project_revision':revision,'cases':oracle,'policy':'Private, never available to tested model. Compare facts separately from answer format. Uncertainty accepted only for the explicitly bounded unknowns.'})
    counts=dict(collections.Counter(t['status'] for t in bound))
    artifact_hashes={str(generated.relative_to(project)):sha(generated.read_bytes())}
    build_properties=project/'target/quarkus-artifact.properties'
    if build_properties.exists():artifact_hashes[str(build_properties.relative_to(project))]=sha(build_properties.read_bytes())
    preflight={'project_revision':revision,'tracked_source_patch_sha256':sha(source_patch.encode()),'source_manifest_sha256':sha((output/'source-manifest.json').read_bytes()),'dependency_manifest_sha256':sha((output/'dependency-manifest.json').read_bytes()),'framework_version':'3.40.1.1','java_version':facts['java_version'],'annotation_namespace':'jakarta','language':'Java','java_files':facts['files'],'compiler_errors':facts['errors'],'counts':counts,'all_scenarios':50,'maximum_applicable_turns_per_arm':sum(t['turns'] for t in bound if t['status']=='applicable'),'new_model_calls':0,'application_startups':0,'production_mutations':0,'source_untracked_exclusions':['.quill','.claude','.codex','AGENTS.md','CLAUDE.md','.mcp.json','.mvn/extensions.xml','node_modules','yarn.lock'],'selection_policy':'Explicit representative anchors selected and frozen before any model experiment, across actual feature kinds. Deterministic inventory sorted by symbol; this binding is curated, not claimed to be a random sample. No post-result replacement.','capability_evidence':'javac-facts.json.gz and not-applicable.json; all 50 IDs accounted for','generated_artifacts':artifact_hashes,'generated_freshness':'Captured build artifacts are independent supplementary snapshot evidence; source/compiled consistency checked by javac attribution, not a fresh full augmentation. A paid runner must additionally establish native index readiness and full build-input alignment.','runtime_oracle_limits':'No application/container startup. Static applicability and known conditional rules are scored; actual request authorization, network calls, transactional commit and async context propagation remain explicitly unproven.','readiness':'offline_oracles_prepared; not authorization or claim that a model experiment ran','required_before_paid_run':['isolate private oracles/helpers from model filesystem','verify source/build/dependency hashes','verify current compiled/native readiness against pinned inputs','independent session/cache-stratum preflight and frozen schedule']}
    dump(output/'preflight.json',preflight)
    render_report(output,bound,oracle,counts,revision)
    return preflight

def render_report(output,cases,oracles,counts,revision):
    e=html.escape
    parts=['<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Cryostat — 50 сценариев</title><style>body{font:16px/1.5 system-ui;max-width:1250px;margin:32px auto;padding:0 24px;color:#19303f}details{border:1px solid #ccd8dd;padding:14px;margin:10px 0}summary{font-weight:650;cursor:pointer}pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:13px}a{color:#076394}.note{background:#fff4e2;padding:16px}</style><h1>Cryostat: привязка 50 сценариев</h1>']
    parts.append(f'<p>Revision <code>{revision}</code>. Применимы: <b>{counts.get("applicable",0)}</b>; N/A: <b>{counts.get("not_applicable",0)}</b>. Все ID сохранены.</p>')
    parts.append('<p class="note">Платных вызовов и запусков приложения нет. Эталоны основаны на независимом javac, source/resources и captured framework bytecode. Полноценный container/runtime не проверялся; границы доказательств указаны в каждом случае. Перед модельным прогоном требуется проверка alignment source/build/index и изоляция скрытых эталонов.</p><p><a href="bound-suite.json">Привязки</a> · <a href="public-tasks.json">Публичные вопросы без ответов</a> · <a href="oracles.json">Приватные эталоны</a> · <a href="not-applicable.json">Доказательства N/A</a> · <a href="preflight.json">Preflight</a> · <a href="local-probes.json">Локальные проверки</a></p>')
    for case in cases:
        parts.append(f'<details><summary>{case["id"]} — {case["status"]}</summary>')
        if case['status']=='not_applicable':
            parts.append('<pre>'+e(json.dumps(case['reason'],ensure_ascii=False,indent=2))+'</pre>')
        else:
            parts.append('<p>'+e(case['prompts']['ru'])+'</p>')
            if 'steps' in case:parts.append('<ol>'+''.join('<li>'+e(s['prompt'])+'</li>' for s in case['steps'])+'</ol>')
            parts.append('<pre>'+e(json.dumps(oracles[case['id']],ensure_ascii=False,indent=2))+'</pre>')
        parts.append('</details>')
    parts.append('<h2>Воспроизведение без inference</h2><pre>python3 benchmarks/cdi-quarkus-50/tools/bind_cryostat.py --project /path/to/cryostat --output benchmarks/cdi-quarkus-50/cryostat\npython3 benchmarks/cdi-quarkus-50/tools/validate_cryostat.py --project /path/to/cryostat\npython3 -m unittest discover -s benchmarks/cdi-quarkus-50/tools -p test_*.py -v</pre><p>Нужны JDK 25 и существующие packaged/test dependencies Cryostat. Annotation processors и приложение не запускаются. Эталонный патч и чистые Java-проверки исполняются только во временном каталоге. Это подготовка oracle, а не benchmark модели.</p>')
    parts.append('</html>');(output/'index.html').write_text('\n'.join(parts))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    result=bind(args.project,args.output)
    print(json.dumps({k:result[k] for k in ['project_revision','java_files','counts','compiler_errors','maximum_applicable_turns_per_arm','new_model_calls']},ensure_ascii=False))
