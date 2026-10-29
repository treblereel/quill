"""Explicit trust of already-reviewed project hooks for isolated CLI automation.

Equivalent to reviewing these exact definitions in /hooks. Never call on unreviewed
configuration or against a real user's CODEX_HOME.
"""
import json
from pathlib import Path

def trust_reviewed_hooks(rpc, root, expected):
    root=Path(root).absolute();assert json.loads((root/'.codex/hooks.json').read_text())==expected,'Hook file changed after review';before=rpc.request('hooks/list',{'cwds':[str(root)]})
    names={'SessionStart':'sessionStart','UserPromptSubmit':'userPromptSubmit'}
    allowed=sorted((names[event],handler['command'],group.get('matcher'),handler.get('timeout',600))
                   for event,groups in expected['hooks'].items() for group in groups for handler in group['hooks'])
    hooks=[h for entry in before['data'] for h in entry['hooks']]
    assert all(not entry.get('errors') and not entry.get('warnings') for entry in before['data']),before
    observed=sorted((h['eventName'],h['command'],h.get('matcher'),h['timeoutSec']) for h in hooks)
    assert observed==allowed,'Discovered hook definitions differ from the reviewed project configuration'
    assert all(h['enabled'] and h['handlerType']=='command' and h['source']=='project'
               and Path(h['sourcePath']).resolve()==(root/'.codex/hooks.json').resolve() for h in hooks)
    edits=[{'keyPath':'hooks.state.'+json.dumps(h['key'])+'.trusted_hash',
            'value':h['currentHash'],'mergeStrategy':'upsert'} for h in hooks if h['trustStatus']!='trusted']
    if edits:rpc.request('config/batchWrite',{'edits':edits,'reloadUserConfig':True})
    after=rpc.request('hooks/list',{'cwds':[str(root)]})
    assert all(h['trustStatus']=='trusted' for entry in after['data'] for h in entry['hooks']),after
    return {'before':before,'after':after,'trust_updates':len(edits)}
