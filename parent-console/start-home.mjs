import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createConsoleServer} from './server.mjs';
import {startToyPolling} from './toy-pull.mjs';
import {recordSyncDiagnostic} from './sync-diagnostics.mjs';
if(process.argv[2]!=='--run'){console.log('{"enabled":false,"deviceIo":false}');process.exit(0);}
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const configFile=path.join(root,'private','parent-sync','home.json');
const config=JSON.parse(fs.readFileSync(configFile,'utf8'));
if(config.enabled!==true||config.pairingConsent!==true)throw Error('EXPLICIT_LOCAL_PAIRING_REQUIRED');
if(!/^[a-zA-Z0-9_-]{8,64}$/.test(config.deviceId)||!/^[a-f0-9]{64}$/.test(config.deviceKey)||!/^[a-zA-Z0-9]{16,128}$/.test(config.parentCode))throw Error('LOCAL_PAIRING_CONFIG_INVALID');
if(typeof config.toyHost!=='string'||!config.toyHost||typeof config.toyCertificateFile!=='string')throw Error('TOY_AND_CERTIFICATE_REQUIRED');
const certPath=path.resolve(root,'private','parent-sync',config.toyCertificateFile);
if(!certPath.startsWith(path.resolve(root,'private','parent-sync')+path.sep))throw Error('LOCAL_CERTIFICATE_PATH_REQUIRED');
const {server,store}=createConsoleServer({file:path.join(root,'parent-console','data','console.json'),code:config.parentCode,deviceKey:config.deviceKey,deviceId:config.deviceId,allowedHost:['127.0.0.1:8787','localhost:8787'],requireTls:true});
server.listen(8787,'127.0.0.1',()=>console.log('Local parent console: http://127.0.0.1:8787/'));
// Exactly one durable console must own the acknowledgement cursor for this toy.
startToyPolling({store,host:config.toyHost,deviceId:config.deviceId,deviceKey:config.deviceKey,certificateDer:fs.readFileSync(certPath),onError:(error,channel)=>recordSyncDiagnostic(store,channel,error),onSuccess:channel=>recordSyncDiagnostic(store,channel,null)});
