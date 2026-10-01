import fs from 'node:fs/promises';
import {replay} from './replay.mjs';
import {LabError} from './protocol.mjs';
try {
  const [command,file,...rest]=process.argv.slice(2);
  if(command!=='replay'||!file||rest.length) throw new LabError('USAGE_NODE_CLI_REPLAY_FILE');
  if((await fs.stat(file)).size>2*1024*1024) throw new LabError('FILE_LIMIT');
  const report=replay(JSON.parse(await fs.readFile(file,'utf8')));
  console.log(JSON.stringify(report,null,2));if(!report.ok) process.exitCode=1;
} catch(e) { console.error(JSON.stringify({ok:false,code:e instanceof LabError?e.code:'INPUT_READ_OR_PARSE_FAILED'}));process.exitCode=1; }
