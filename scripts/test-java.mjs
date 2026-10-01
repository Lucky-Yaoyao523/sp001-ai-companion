import fs from 'node:fs';
import path from 'node:path';
import https from 'node:https';
import {spawnSync} from 'node:child_process';
import {createHash} from 'node:crypto';
process.chdir(path.resolve(import.meta.dirname,'..'));
const args=process.argv.slice(2);
if(args.some(a=>a!=='--fetch-deps')||args.length>1)throw Error('ONLY_OPTION_IS_FETCH_DEPS');
const deps=[
 {name:'okhttp-3.12.13.jar',url:'https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp/3.12.13/okhttp-3.12.13.jar',sha:'508234e024ef7e270ab1a6d5b356f5b98e786511239ca986d684fd1e2cf7bc82'},
 {name:'okio-1.15.0.jar',url:'https://repo.maven.apache.org/maven2/com/squareup/okio/okio/1.15.0/okio-1.15.0.jar',sha:'693fa319a7e8843300602b204023b7674f106ebcb577f2dd5807212b66118bd2'},
 {name:'json-20240303.jar',url:'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',sha:'3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'}
];
const sha=b=>createHash('sha256').update(b).digest('hex');
function download(url){return new Promise((resolve,reject)=>{const req=https.get(url,res=>{if(res.statusCode!==200){res.resume();reject(Error('DEPENDENCY_HTTP_'+res.statusCode));return;}let n=0,chunks=[];res.on('data',c=>{n+=c.length;if(n>8000000)req.destroy(Error('DEPENDENCY_SIZE_BOUND'));else chunks.push(c);});res.on('end',()=>resolve(Buffer.concat(chunks)));res.on('error',reject);});req.on('error',reject);req.setTimeout(15000,()=>req.destroy(Error('DEPENDENCY_TIMEOUT')));});}
fs.mkdirSync('.deps',{recursive:true});
for(const d of deps){const file=path.join('.deps',d.name);if(!fs.existsSync(file)){if(!args.includes('--fetch-deps'))throw Error('DEPENDENCIES_MISSING_USE_EXPLICIT_FETCH_DEPS');const b=await download(d.url);if(sha(b)!==d.sha)throw Error('DEPENDENCY_HASH_MISMATCH');fs.writeFileSync(file,b,{flag:'wx'});}if(sha(fs.readFileSync(file))!==d.sha)throw Error('DEPENDENCY_HASH_MISMATCH');}
const sdk=process.env.ANDROID_JAR||(process.env.ANDROID_HOME?path.join(process.env.ANDROID_HOME,'platforms','android-22','android.jar'):null);
if(!sdk||!fs.existsSync(sdk))throw Error('SET_ANDROID_JAR_TO_API22');
const suffix=process.platform==='win32'?'.exe':'';
const exe=name=>process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',name+suffix):name;
const run=(name,args)=>{const p=spawnSync(exe(name),args,{encoding:'utf8',windowsHide:true,timeout:60000,maxBuffer:4000000});if(p.stdout)process.stdout.write(p.stdout);if(p.stderr)process.stderr.write(p.stderr);if(p.error||p.status!==0)throw Error('JAVA_STEP_FAILED_'+name);};
const dest=path.join('build','java-'+Date.now()),core=path.join(dest,'core'),test=path.join(dest,'test');fs.mkdirSync(core,{recursive:true});fs.mkdirSync(test,{recursive:true});
const source='core-patch/src/org/sp001/core';
const sources=fs.readdirSync(source).filter(n=>n.endsWith('.java')).map(n=>path.join(source,n));
const libraries=deps.slice(0,2).map(d=>path.join('.deps',d.name));
run('javac',['-source','8','-target','8','-encoding','UTF-8','-bootclasspath',sdk,'-cp',libraries.join(path.delimiter),'-d',core,...sources]);
const stubs='core-patch/test/host-stubs/android/os';
run('javac',['--release','8','-encoding','UTF-8','-d',test,...fs.readdirSync(stubs).filter(n=>n.endsWith('.java')).map(n=>path.join(stubs,n))]);
const tests=['ActionLifecycleRegressionTest','NativeFeedbackRegressionTest','DeviceToolExecutionTest','NativeReplyStreamTest','NativeReplyCompletenessTest','ModelTurnControlTest','ApiOwnedDialogueTest','ApiFirstDialogueAcceptanceTest','NativeDialogueTest'];
const cp=[test,core,path.join('.deps',deps[2].name),sdk,...libraries].join(path.delimiter);
run('javac',['--release','8','-encoding','UTF-8','-cp',cp,'-d',test,...fs.readdirSync('core-patch/test').filter(n=>n.endsWith('.java')).map(n=>'core-patch/test/'+n)]);
for(const n of tests)run('java',['-Dfile.encoding=UTF-8','-cp',cp,'org.sp001.core.'+n]);
console.log(JSON.stringify({passed:true,compiledSources:sources.length,testGroups:tests.length,deviceIo:false,modelCalls:0,output:dest}));
