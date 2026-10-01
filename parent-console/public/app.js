const $ = id => document.getElementById(id);
const state = { settings: null, commands: [], date: '', today: '', events: [], settingsDirty: false, localAccess: false, mode: 'active' };
let dayRequest = 0, attentionRows = [];
const kindLabels = {
  session_start: '开始会话', session_end: '结束会话', turn_start: '开始一轮对话', answer_complete: '回答完成', answer_partial: '实际播过的部分回答',
  tool_requested: '发起工具查询', tool_result: '工具返回结果', asr_error: '听取出错', tts_error: '播放出错',
  interrupted: '对话被打断', cancelled: '对话已取消',
};
const commandLabels = { set_limits: '更新使用规则', pause: '暂停新会话', resume: '恢复新会话' };
const statusLabels = { queued: '本地排队', sent: '发送中，待确认', acknowledged: '玩具已确认' };
const evidenceLabels = { demo: '演示记录', manual_import: '手动导入', device_event: '玩具事件', device_receipt: '玩具真实回执' };

async function api(path, options = {}) {
  const response = await fetch(path, { credentials: 'same-origin', ...options, headers: options.body ? { 'Content-Type': 'application/json' } : undefined });
  const payload = await response.json();
  if (!response.ok) throw new Error(payload.error || '请求失败');
  return payload;
}
const elem = (tag, className = '', content = '') => { const el = document.createElement(tag); if (className) el.className = className; el.textContent = content; return el; };
function showDashboard(show) { $('login').classList.toggle('hidden', show); $('dashboard').classList.toggle('hidden', !show); $('logout').classList.toggle('hidden', !show || state.localAccess); }
function formatTime(iso) { return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Hong_Kong', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(iso)); }

async function refresh() {
  const data = await api('/api/state');
  state.localAccess = data.access === 'local';
  state.mode = data.mode || 'active';
  state.settings = data.settings; state.commands = data.commands; state.events = data.events;
  const lastSeen = data.device?.lastSeenAt;
  const sync = data.device?.sync;
  const diagnostics = data.device?.syncDiagnostics || {};
  $('sync-diagnostics').replaceChildren(...[['pull', '聊天记录同步'], ['command', '远程指令']].map(([key, label]) => {
    const item = diagnostics[key];
    const time = at => new Date(at).toLocaleString('zh-CN');
    let text = `${label}：尚无检查记录。`;
    if (item) {
      text = item.status === 'error'
        ? `${label}：最近尝试失败，连续 ${item.consecutiveFailures} 次；${time(item.lastErrorAt)}，${item.lastErrorCode}。`
        : `${label}：最近成功于 ${time(item.lastSuccessAt)}。`;
      if (item.status === 'ok' && item.lastErrorAt) text += ` 上次异常 ${time(item.lastErrorAt)}（${item.lastErrorCode}），已于 ${time(item.recoveredAt)}恢复。`;
    }
    return elem('span', 'sync-diagnostic-line', text);
  }));
  const gaps = data.device?.gaps || [];
  const missingEvents = gaps.reduce((sum, gap) => sum + gap.count, 0);
  const battery = data.device?.battery;
  const batteryAge = battery?.observedAt ? Date.now() - Date.parse(battery.observedAt) : Infinity;
  $('battery-status').textContent = Number.isInteger(battery?.percent)
    ? `电量：${battery.percent}%${battery.charging ? ' · 充电中' : ''}${batteryAge > 120000 ? ' · 上次读数' : ''}` : '电量：暂未获取';
  $('battery-status').title = battery?.observedAt ? `读取时间：${new Date(battery.observedAt).toLocaleString('zh-CN')}；离线时不会更新` : '等待玩具上报真实电量；需要新版玩具程序支持';
  const recent = data.transport === 'device_recent';
  const storageNearlyFull = sync && (sync.journalBytes >= sync.capacityBytes * 0.9 || sync.pendingEvents >= sync.capacityEvents * 0.9);
  $('sync-status').textContent = missingEvents > 0 ? `聊天记录有 ${missingEvents} 条缺失`
    : sync?.droppedEvents > 0 ? '记录曾达到缓存上限，请留意缺失'
    : recent && storageNearlyFull ? '玩具缓存接近上限，正在补传'
    : recent && sync?.pendingEvents > 0 ? `正在补传，还有 ${sync.pendingEvents} 条事件`
    : recent ? '玩具记录已同步' : (lastSeen ? '等待玩具再次同步' : '家长记录尚未同步');
  const pending = data.commands.filter(c => c.status !== 'acknowledged' && Date.parse(c.expiresAt) > Date.now()).length;
  $('sync-detail').textContent = lastSeen
    ? `最近同步：${formatTime(lastSeen)}。${pending ? `${pending} 项使用规则等待玩具确认。` : '使用规则以玩具确认状态为准。'}`
    : `正在通过家庭 Wi-Fi 读取。${pending ? `${pending} 项使用规则等待玩具确认。` : ''}`;
  $('sync-detail').textContent += sync?.droppedEvents > 0
    ? ` 缓存满时有 ${sync.droppedEvents} 条新事件未保存，现有记录已保留。`
    : ' 电脑关机时玩具暂存聊天文字，服务恢复且玩具联网后自动补传。';
  if (missingEvents > 0) $('sync-detail').textContent += ` 本地同步事故造成序号 ${gaps.map(g => `${g.first}–${g.last}`).join('、')} 的记录缺失；后续真实记录会继续显示。`;
  if (state.mode === 'standby') {
    $('sync-status').textContent = '备用后台：只读查看记录';
    $('sync-detail').textContent = `打包时的记录快照，序号 ${data.device?.lastEventSeq || 0}。Windows 继续同步；正式切换时再补齐最新记录。`;
  }
  for (const element of $('settings-form').querySelectorAll('input,button')) element.disabled = state.mode === 'standby';
  $('generate-summary').disabled = state.mode === 'standby';
  $('generate-summary-inline').disabled = state.mode === 'standby';
  if (!state.date) state.date = data.today;
  state.today = data.today;
  $('next-day').disabled = state.date >= state.today;
  $('date').value = state.date;
  $('export').href = `/api/export?date=${encodeURIComponent(state.date)}`;
  $('export-raw').href = $('export').href;
  $('export').href += '&format=txt';
  if (!state.settingsDirty) renderSettings();
  renderCommands();
  await renderDay();
}

async function renderDay() {
  const request = ++dayRequest;
  const requestedDate = state.date;
  const day = await api(`/api/day?date=${encodeURIComponent(requestedDate)}`);
  if (request !== dayRequest || requestedDate !== state.date) return;
  const items = [
    ['使用时长', `${day.minutes}`, '分钟'], ['会话次数', `${day.sessions}`, '次'],
    ['播报完成', `${day.answers}`, '轮'], ['部分回答', `${day.partialAnswers}`, '轮'], ['需留意', `${day.failures}`, '次'],
  ];
  attentionRows = day.attention || [];
  $('stats').replaceChildren(...items.map(([label, value, unit]) => {
    const attention = label === '需留意';
    const card = elem(attention ? 'button' : 'div', attention ? 'stat attention-stat' : 'stat');
    if (attention) { card.type='button'; card.title='查看每条记录的时间与原因';card.setAttribute('aria-label',`查看需留意的 ${value} 条记录`);card.addEventListener('click',()=>{renderAttention();$('attention-dialog').showModal();}); }
    card.append(elem('span', '', attention ? '需留意 ›' : label)); const strong = elem('strong', '', value); strong.append(elem('small', '', unit)); card.append(strong); return card;
  }));
  if ($('attention-dialog').open) renderAttention();
  const activities = [...day.topics.map(t => `主题：${t}`), ...day.heardQuestions.map(t => `听到孩子说：${t}`)];
  $('topics').replaceChildren(...(activities.length ? activities.map(t => elem('span', 'topic', t))
    : [elem('p', 'subtext', '当天还没有可展示的提问。')]));
  $('empty-demo').classList.toggle('hidden', day.events.length > 0);
  const rows = [
    ['发起查询', day.toolRequests],
    ['收到真实成功回执', day.toolSuccesses],
    ['异常结束或失败', day.failures],
  ];
  $('execution').replaceChildren(...rows.map(([label, count]) => { const row = elem('div', 'exec-row'); row.append(elem('span', '', label), elem('strong', '', String(count))); return row; }));
  const query = $('search').value.trim().toLowerCase();
  $('clear-search').classList.toggle('hidden', !query);
  const filtered = day.events.filter(e => !query || [e.topic, e.childText, e.replyText, e.sessionId, e.turnId, e.toolName, e.reason, kindLabels[e.kind]].some(v => String(v || '').toLowerCase().includes(query)));
  renderConversations(day.events, filtered, query);
  void loadSummary(requestedDate);
  if (!filtered.length) { $('timeline').replaceChildren(elem('p', 'subtext', day.events.length ? '没有匹配的记录。' : '当天暂无记录。')); return; }
  $('timeline').replaceChildren(...filtered.map(e => {
    const row = elem('div', 'event');
    row.append(elem('div', 'event-time', formatTime(e.at)), elem('div', 'event-rail'));
    const body = elem('div', 'event-body');
    const title = elem('strong', '', kindLabels[e.kind] || e.kind);
    const badge = elem('span', `badge ${e.kind.endsWith('error') || e.toolStatus === 'failed' ? 'bad' : ''}`, evidenceLabels[e.evidence] || '来源未知');
    body.append(title, badge);
    const details = [e.topic, e.toolName, e.kind === 'tool_result' ? ({ success: '成功', failed: '失败', unknown: '结果未知' }[e.toolStatus]) : '', e.reason].filter(Boolean).join(' · ');
    if (details) body.append(elem('p', '', details));
    if (e.childText) body.append(elem('p', '', `孩子：${e.childText}`));
    if (e.replyText) body.append(elem('p', '', `蜘蛛侠：${e.replyText}`));
    body.append(elem('small', '', `${e.sessionId}${e.turnId ? ` · 第 ${e.turnId} 轮` : ''}`));
    row.append(body); return row;
  }));
}

function renderConversations(events, filtered, query) {
  const container = $('conversations');
  const context = `${state.date}|${query}`;
  const signature = JSON.stringify([context, events]);
  if (container.dataset.renderSignature === signature) return;
  const sameContext = container.dataset.context === context;
  const previousTop = container.scrollTop;
  const nearBottom = container.scrollHeight - container.clientHeight - previousTop < 60;
  container.dataset.context = context;
  container.dataset.renderSignature = signature;
  const matched = new Set(filtered.map(e => e.sessionId));
  const groups = new Map();
  for (const event of events) {
    if (query && !matched.has(event.sessionId)) continue;
    if (!groups.has(event.sessionId)) groups.set(event.sessionId, []);
    groups.get(event.sessionId).push(event);
  }
  const sessions = [...groups.values()].filter(rows => rows.some(e => e.childText || e.replyText || ['answer_complete', 'answer_partial'].includes(e.kind)));
  const count = sessions.reduce((n, rows) => n + rows.filter(e => e.childText || e.replyText).length, 0);
  $('chat-count').textContent = `${state.date} · ${count} 轮${query ? ' · 搜索结果' : ''}`;
  if (!sessions.length) {
    $('conversations').replaceChildren(elem('p', 'chat-empty', query ? '没有找到相关对话。' : '这一天还没有聊天记录。聊过之后会自动显示在这里。'));
    return;
  }
  $('conversations').replaceChildren(...sessions.map(rows => {
    const section = elem('section', 'chat-session');
    if (rows.some(e => e.evidence === 'demo')) section.append(elem('span', 'badge', '演示对话'));
    let messages = 0;
    for (const event of rows) {
      if (!event.childText && !event.replyText && !['answer_complete', 'answer_partial'].includes(event.kind)) continue;
      messages++;
      if (event.childText) section.append(chatMessage('child', '孩子', event.childText, event.at));
      if (event.replyText) {
        const message = chatMessage('toy', '蜘蛛侠', event.replyText, event.at);
        if (event.kind === 'answer_partial') message.append(elem('small', 'chat-note', '回答未完成 · 这里只显示已确认播出的部分'));
        section.append(message);
      } else {
        section.append(elem('p', 'chat-notice', event.kind === 'answer_partial'
          ? '这次回答未完成，暂无可确认的回答文字。'
          : '这轮没有保存回答文字。'));
      }
    }
    if (!messages) section.append(elem('p', 'chat-notice', '本次会话暂无已保存的问答文字。'));
    return section;
  }));
  requestAnimationFrame(() => {
    if (container.dataset.renderSignature !== signature) return;
    container.scrollTop = !sameContext ? (query ? 0 : container.scrollHeight)
      : nearBottom ? container.scrollHeight : previousTop;
  });
}

function chatMessage(role, name, text, at) {
  const message = elem('div', `chat-message ${role}`);
  const bubble = elem('div', 'chat-bubble');
  bubble.append(elem('div', 'chat-text', text));
  const time = elem('time', 'chat-time', formatTime(at));
  time.dateTime = at;
  time.title = `${state.date} ${formatTime(at)}（香港时间）。原日志保存的是整轮记录时间，未单独保存孩子开口与回答开始时间。`;
  bubble.append(time);
  message.append(elem('span', 'chat-speaker', name), bubble);
  return message;
}

function renderSettings() {
  const s = state.settings;
  $('daily-minutes').value = s.dailyMinutes; $('daily-sessions').value = s.dailySessions;
  $('quiet-start').value = s.quietStart; $('quiet-end').value = s.quietEnd;
  $('retention').value = s.retentionDays; $('paused').checked = s.paused; $('save-transcript').checked = s.saveTranscript;
}
function renderCommands() {
  const commands = state.commands.slice(-10).reverse();
  if (!commands.length) { $('commands').replaceChildren(elem('p', 'subtext', '尚无设置或暂停指令。')); return; }
  $('commands').replaceChildren(...commands.map(c => {
    const row = elem('div', 'command'); const left = elem('div');
    left.append(elem('strong', '', commandLabels[c.type] || c.type), elem('small', '', `${formatTime(c.createdAt)} 创建 · ${new Date(c.expiresAt).toLocaleDateString('zh-CN')} 到期`));
    const expired = Date.parse(c.expiresAt) < Date.now() && c.status !== 'acknowledged';
    row.append(left, elem('span', `status ${c.status}`, expired ? '已过期' : statusLabels[c.status] || c.status)); return row;
  }));
}

$('login-form').addEventListener('submit', async event => {
  event.preventDefault(); $('login-error').textContent = '';
  try { await api('/api/login', { method: 'POST', body: JSON.stringify({ code: $('code').value }) }); $('code').value = ''; showDashboard(true); await refresh(); }
  catch (error) { $('login-error').textContent = error.message; }
});
$('logout').addEventListener('click', async () => { await api('/api/logout', { method: 'POST' }); showDashboard(false); });
document.querySelectorAll('.tab').forEach(button => button.addEventListener('click', () => {
  document.querySelectorAll('.tab').forEach(b => b.classList.toggle('active', b === button));
  $('overview').classList.toggle('hidden', button.dataset.tab !== 'overview');
  $('settings').classList.toggle('hidden', button.dataset.tab !== 'settings');
}));
async function selectDate(date) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return;
  state.date = date; $('date').value = date;
  $('summary-content').replaceChildren();
  $('summary-status').textContent = summaryBusy ? '另一日期的总结正在生成，请稍等。' : '';
  $('next-day').disabled = date >= state.today;
  $('export').href = `/api/export?date=${encodeURIComponent(date)}`;
  $('export-raw').href = $('export').href;
  $('export').href += '&format=txt';
  await renderDay();
}
$('date').addEventListener('change', () => selectDate($('date').value));
for (const [id, offset] of [['previous-day', -1], ['next-day', 1]]) {
  $(id).addEventListener('click', () => {
    const date = new Date(`${state.date}T12:00:00Z`); date.setUTCDate(date.getUTCDate() + offset);
    selectDate(date.toISOString().slice(0, 10));
  });
}
$('today').addEventListener('click', async () => { await refresh(); await selectDate(state.today); });
$('refresh').addEventListener('click', async () => {
  $('refresh').disabled = true; $('refresh').textContent = '刷新中…';
  try { await refresh(); } catch { $('sync-status').textContent = '刷新失败，请稍后重试'; }
  finally { $('refresh').disabled = false; $('refresh').textContent = '刷新'; }
});
$('chat-toggle').addEventListener('click', () => {
  const collapsed = $('chat-toggle').getAttribute('aria-expanded') === 'true';
  const top = $('conversations').scrollTop;
  if (collapsed) $('chat-panel').dataset.scrollTop = String(top);
  $('chat-toggle').setAttribute('aria-expanded', String(!collapsed));
  $('chat-content').classList.toggle('hidden', collapsed);
  $('chat-panel').classList.toggle('collapsed', collapsed);
  if (!collapsed) requestAnimationFrame(() => { $('conversations').scrollTop = Number($('chat-panel').dataset.scrollTop || 0); });
});
$('clear-search').addEventListener('click', async () => { $('search').value = ''; await renderDay(); });
$('latest').addEventListener('click', async () => { $('search').value = ''; await renderDay(); $('conversations').scrollTop = $('conversations').scrollHeight; });
$('search').addEventListener('input', renderDay);
let summaryBusy = false;
const summaryErrors = new Map();
const summaryLabels = {overview:'今天总体情况',interests:'聊了什么',parentAttention:'家长需要留意',nextSteps:'可以怎样陪伴',robotIssues:'机器人需要改进',limitations:'记录范围与不确定性'};
function displaySummary(value) {
  $('summary-content').replaceChildren();
  if (!value) return;
  for (const [key,label] of Object.entries(summaryLabels)) {
    const section=elem('section','summary-section');section.append(elem('h3','',label),elem('p','',value.content[key]));$('summary-content').append(section);
  }
  $('summary-status').textContent=`${value.date} · 基于 ${value.recordCount} 条已同步事件 · ${value.model} · ${formatTime(value.generatedAt)} 生成`;
  if(value.stale)$('summary-status').textContent+=' · 记录或总结方式已有更新，点击一键总结可重新生成。';
}
async function loadSummary(date) {
  if(summaryBusy)return;
  try { const data=await api(`/api/summary?date=${encodeURIComponent(date)}`);if(date!==state.date||summaryBusy)return;
    displaySummary(data.summary);if(!data.summary)$('summary-status').textContent=data.generating?'正在分析已同步记录，请稍等…':summaryErrors.get(date)||'点击生成，了解当天聊天和需要留意的事情。';
  } catch { if(date===state.date&&!summaryBusy)$('summary-status').textContent='总结功能等待服务更新。'; }
}
async function generateSummary(){
  if(summaryBusy)return;
  $('summary-panel').open=true;
  const date=state.date;summaryBusy=true;
  for(const id of ['generate-summary','generate-summary-inline']){$(id).disabled=true;$(id).textContent='正在生成…';}
  summaryErrors.delete(date);
  $('summary-status').textContent='正在分析已同步记录，请稍等…';
  try {const result=await api('/api/summary',{method:'POST',body:JSON.stringify({date})});if(date===state.date)displaySummary(result.summary);}
  catch(error){summaryErrors.set(date,error.message);if(date===state.date)$('summary-status').textContent=error.message;}
  finally{summaryBusy=false;
    $('generate-summary').disabled=false;$('generate-summary').textContent='一键总结';
    $('generate-summary-inline').disabled=false;$('generate-summary-inline').textContent='生成当天总结';
    if(date!==state.date)void loadSummary(state.date);
  }
}
$('generate-summary').addEventListener('click',generateSummary);
$('generate-summary-inline').addEventListener('click',generateSummary);
const attentionReasons = {
  ASR_RESULT_WAIT_TIMEOUT:'等待语音识别结果超时，这轮未能正常继续。',
  ASR_PROVIDER_RESULT_TIMEOUT:'语音识别服务未及时返回结果。',
  ASR_OPEN_TIMEOUT:'连接语音识别服务超时。',
  SESSION_CLOSE_FAILED:'会话结束时清理未正常完成，需要结合详细日志确认原因。',
  INPUT_ENDPOINT_LIMIT:'收音达到时长上限；仅凭这条记录不能判断是持续说话还是环境声音。',
  TURN_CANCELLED:'会话被取消；这条记录没有说明是谁或什么触发了取消。',
  HTTP_TRANSFER_FAILED:'网络传输失败，具体服务和原因需结合详细日志。',
  NATIVE_REPLY_ENVELOPE_REQUIRED:'模型返回的回答格式不符合要求，回答未能正常继续。',
  NATIVE_REPLY_FORMAT:'模型回答格式不完整，回答未能正常继续。',
  NATIVE_RESPONSE_FAILED:'模型服务报告本次回答失败。',
  NATIVE_STREAM_INCOMPLETE:'模型回答传输未完整结束。',
  NATIVE_NO_ANSWER:'模型服务未返回可播放的回答。',
  ANSWER_FALLBACK:'播报虽然完成，但内容是固定的回答失败提示；这轮没有答成。',
  TTS_FLUSH_INCOMPLETE:'语音播放未完整结束。',
  TTS_QUEUE_BOUND:'语音播放缓存达到上限；旧版未区分具体是哪项上限。',
  TTS_MESSAGE_BOUND:'语音服务返回的单条消息过大，播放已停止。',
  TTS_METADATA_MESSAGE_BOUND:'语音服务单条附加信息过大，播放已停止。',
  TTS_METADATA_QUEUE_BOUND:'等待处理的语音附加信息达到缓存上限。',
  TTS_PCM_QUEUE_BOUND:'等待播放的音频达到缓存上限。',
  TTS_MESSAGE_COUNT_BOUND:'等待处理的语音消息数量达到上限。',
  TTS_AUDIO_TYPE:'语音服务返回的音频格式不符合约定。',
  TTS_HANDSHAKE_AUDIO:'语音服务在准备阶段提前返回了音频。',
  TTS_EVENT_JSON:'语音服务返回的消息无法解析。',
  MINIMAX_AUDIO_HEX_INVALID:'语音服务返回的音频编码无效。',
};
function renderAttention() {
  $('attention-heading').textContent=`${state.date} · 需留意 ${attentionRows.length} 条`;
  $('attention-list').replaceChildren(...attentionRows.map(e=>{
    const row=elem('section','attention-row');row.append(elem('strong','',`${formatTime(e.at)} · ${kindLabels[e.kind] || '运行异常'}`));
    row.append(elem('p','',attentionReasons[e.reason] || (e.kind==='tool_result'?'这次查询返回失败，未收到成功结果。':e.kind==='asr_error'?'这次听取或识别出现问题。':e.kind==='tts_error'?'这次语音播放出现问题。':'记录了未正常完成的会话，需要进一步核对原因。')));
    if(e.childText)row.append(elem('p','',`孩子：${e.childText}`));if(e.replyText)row.append(elem('p','',`玩具：${e.replyText}`));
    const details=elem('details');details.append(elem('summary','','查看原始原因'),elem('p','',e.reason || e.toolStatus || '未记录原因'));row.append(details);
    const view=elem('button','small-button','查看这段聊天');view.addEventListener('click',async()=>{$('attention-dialog').close();$('search').value=e.sessionId;$('chat-toggle').setAttribute('aria-expanded','true');$('chat-content').classList.remove('hidden');$('chat-panel').classList.remove('collapsed');await renderDay();});row.append(view);return row;
  }));
  if(!attentionRows.length)$('attention-list').append(elem('p','','这一天没有需留意的记录。'));
}
$('attention-close').addEventListener('click',()=>$('attention-dialog').close());
$('settings-form').addEventListener('input', () => { state.settingsDirty = true; $('settings-message').textContent = '有未保存的修改'; });
$('settings-form').addEventListener('submit', async event => {
  event.preventDefault(); $('settings-message').textContent = '';
  const input = { dailyMinutes: Number($('daily-minutes').value), dailySessions: Number($('daily-sessions').value), quietStart: $('quiet-start').value, quietEnd: $('quiet-end').value, retentionDays: Number($('retention').value), paused: $('paused').checked, saveTranscript: $('save-transcript').checked };
  try { await api('/api/settings', { method: 'PUT', body: JSON.stringify(input) }); state.settingsDirty = false; await refresh(); $('settings-message').textContent = '已保存；玩具确认后生效。'; }
  catch (error) { $('settings-message').textContent = error.message; }
});
$('demo').addEventListener('click', async () => { if (!confirm('载入一组标明“演示”的虚构记录？')) return; await api('/api/demo', { method: 'POST', body: JSON.stringify({ confirm: true }) }); await refresh(); });
$('delete-day').addEventListener('click', async () => { if (!confirm(`确定删除 ${state.date} 的所有本地记录？此操作无法撤销。`)) return; await api(`/api/day?date=${encodeURIComponent(state.date)}`, { method: 'DELETE' }); await refresh(); });
// Enlarge existing content in place: no copies, extra API calls or lost scroll state.
let expandedPanel = null;
function restoreReadingPanel() {
  if (!expandedPanel) return;
  const {panel,button}=expandedPanel;
  panel.classList.remove('reading-expanded');panel.style.removeProperty('height');
  button.textContent='放大';button.setAttribute('aria-expanded','false');
  expandedPanel=null;button.focus();
}
for (const [selector,label] of [['#summary-panel','家长总结'],['#chat-panel','聊天记录'],['.overview-details','当天概览'],['.timeline-panel','运行记录']]) {
  const panel=document.querySelector(selector);
  const head=panel.querySelector(':scope > summary, :scope > .panel-head');
  const button=elem('button','small-button panel-enlarge','放大');button.type='button';
  button.setAttribute('aria-label',`放大或还原${label}`);button.setAttribute('aria-expanded','false');
  button.title='放大阅读后，可拖动框的右下角调整高度；按 Esc 还原';
  button.addEventListener('click',event=>{
    event.preventDefault();event.stopPropagation();
    if(expandedPanel?.panel===panel){restoreReadingPanel();return;}
    restoreReadingPanel();
    if(panel.tagName==='DETAILS')panel.open=true;
    if(panel.id==='chat-panel'){$('chat-toggle').setAttribute('aria-expanded','true');$('chat-content').classList.remove('hidden');panel.classList.remove('collapsed');}
    panel.classList.add('reading-expanded');button.textContent='还原';button.setAttribute('aria-expanded','true');expandedPanel={panel,button};
  });
  head.append(button);
}
document.addEventListener('keydown',event=>{if(event.key==='Escape'&&expandedPanel){event.preventDefault();restoreReadingPanel();}});
refresh().then(() => showDashboard(true)).catch(() => showDashboard(false));
setInterval(() => { if (!$('dashboard').classList.contains('hidden')) refresh().catch(() => { $('sync-status').textContent = '暂时无法刷新，保留上次记录'; }); }, 15000);
