import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
const root=path.resolve(import.meta.dirname,'..');
const excluded=new Set(['.git','.deps','build','private','node_modules','data']);
function walk(dir){return fs.readdirSync(dir,{withFileTypes:true}).flatMap(e=>excluded.has(e.name)?[]:e.isDirectory()?walk(path.join(dir,e.name)):[path.join(dir,e.name)]);}
const listing=spawnSync('git',['-C',root,'ls-files','-z'],{encoding:'utf8',windowsHide:true});
const tracked=listing.status===0&&listing.stdout?listing.stdout.split('\0').filter(Boolean).map(p=>path.join(root,p)):walk(root);
const findings=[];
for(const file of tracked){
 const rel=path.relative(root,file).replaceAll('\\','/');
 if(/(^|\/)(private|research|artifacts|recordings|data)(\/|$)|\.(apk|ipa|dex|jar|so|p12|pfx|der|key|keystore|jks|wav|pcm|mp3|log)$/i.test(rel)){findings.push({file:rel,rule:'non-public-file'});continue;}
 const s=fs.readFileSync(file,'utf8');
 const rules=[['private-key',/-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----/],['provider-token',/\b(?:sk|ghp|gho|github_pat)-?[A-Za-z0-9_]{24,}\b/],['private-network',/\b192\.168\.\d{1,3}\.\d{1,3}\b/],['unchecked-tls',/(?:NODE_TLS_REJECT_UNAUTHORIZED\s*=\s*['"]?0|rejectUnauthorized\s*:\s*false|setHostnameVerifier\([^\n]*return true)/],['shell-execution',/(?:Runtime\.getRuntime\(\)\.exec\(|new ProcessBuilder\()/]];
 // Match only assembled patterns at runtime so this checker does not match its own literals.
 for(const [rule,re]of rules)if(re.test(s))findings.push({file:rel,rule});
}
if(findings.length){console.error(JSON.stringify({passed:false,findings},null,2));process.exitCode=1;}else console.log(JSON.stringify({passed:true,files:tracked.length,scope:'tracked or export files; no claim of exhaustive secret detection'}));
