const $ = id => document.getElementById(id);
let state; let settingsLoaded = false; let refreshing = false; let chatBusy = false; let renderedMessages = '';
const titles = { chat: '和你的代码聊一聊', reviews: '看见每一次进步', files: '你的代码，独立保存', settings: '连接你的训练空间' };
function switchView(view) { document.querySelectorAll('.view').forEach(el => { el.hidden = el.id !== `view-${view}`; }); document.querySelectorAll('.nav').forEach(el => el.classList.toggle('active', el.dataset.view === view)); $('page-title').textContent = titles[view]; }
document.querySelectorAll('[data-view]').forEach(button => button.addEventListener('click', () => switchView(button.dataset.view)));
$('open-settings').addEventListener('click', () => switchView('settings'));
function notify(message) { $('notice').textContent = message; $('notice').hidden = !message; }
async function api(route, data) {
  const response = await fetch(`/api/${route}`, data === undefined ? {} : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) });
  const result = await response.json(); if (!response.ok) throw new Error(result.error || '请求失败'); return result;
}
function el(tag, text, className) { const node = document.createElement(tag); if (text !== undefined) node.textContent = text; if (className) node.className = className; return node; }
function time(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'; }
function sourceButton(ref, label) { const button = el('button', label || `${ref.path}:${ref.startLine}–${ref.endLine}`, 'source-chip'); button.addEventListener('click', () => openSource(ref.path, ref.hash)); return button; }
async function openSource(path, hash) { try { const result = await api(`source?path=${encodeURIComponent(path)}${hash ? `&hash=${hash}` : ''}`); $('source-title').textContent = `${path}${hash ? ' · 历史快照 ' + hash.slice(0, 8) : ''}`; $('source-content').textContent = result.content.split('\n').map((line, i) => `${String(i + 1).padStart(4)}  ${line}`).join('\n'); $('source-dialog').showModal(); } catch (error) { notify(error.message); } }
$('close-source').addEventListener('click', () => $('source-dialog').close());
function renderMessages() {
  const signature = JSON.stringify(state.messages) + chatBusy;
  if (signature === renderedMessages) return; renderedMessages = signature;
  const container = $('messages'); container.replaceChildren();
  if (!state.messages.length) {
    const empty = el('div', undefined, 'empty'); empty.append(el('div', '◎', 'empty-mark'), el('h2', '从你写过的代码开始'), el('p', state.configured ? '代码已建立索引。问一个问题，让已有的经验帮你走下一步。' : '连接模型，选择代码目录，建立属于你的训练记忆。'));
    const suggestions = el('div', undefined, 'suggestions');
    for (const text of ['我写过哪些算法？', '最近的代码有哪些边界风险？', '根据我的代码，下一步该练什么？']) { const button = el('button', text); button.addEventListener('click', () => { $('question').value = text; $('question').focus(); }); suggestions.append(button); }
    empty.append(suggestions); container.append(empty);
  }
  for (const message of state.messages) {
    const row = el('article', undefined, `message ${message.role}`); row.append(el('div', message.role === 'user' ? '你' : '循练 · 代码教练', 'message-label'), el('div', message.content, 'message-text'));
    if (message.sources?.length) { const refs = el('div', undefined, 'sources'); refs.append(el('span', '本次检索：', 'muted')); message.sources.forEach(ref => refs.append(sourceButton(ref))); row.append(refs); }
    container.append(row);
  }
  if (chatBusy) container.append(el('p', '正在检索代码与记忆，等待模型回答…', 'muted'));
  container.scrollTop = container.scrollHeight;
}
function render() {
  $('root').textContent = state.settings.root || '尚未选择目录'; $('file-count').textContent = state.files.length;
  $('done-count').textContent = state.counts.currentDone || 0;
  $('review-count').textContent = state.counts.total;
  $('activity').textContent = state.modelError ? '审查暂停 · 请重试' : state.scanning ? '正在扫描' : state.processing ? '模型审查中' : state.pending ? '等待文件稳定' : state.settings.autoReview ? '自动审查已开启' : state.configured ? '手动扫描模式' : '等待配置';
  $('scan').disabled = !state.configured || state.scanning;
  $('send').disabled = !state.configured || chatBusy;
  if (state.scanError || state.modelError) notify(state.scanError || `${state.modelError}。修正设置后点击“立即扫描”重试。`);
  if (!settingsLoaded) { $('setting-root').value = state.settings.root; $('base-url').value = state.settings.baseUrl; $('model').value = state.settings.model; $('auto-review').checked = state.configured ? state.settings.autoReview : true; $('api-key').placeholder = state.settings.hasApiKey ? '已保存密钥；留空保持不变' : '输入 API Key（本机免密模型可留空）'; settingsLoaded = true; }
  renderMessages();
  const files = $('files'); files.replaceChildren();
  for (const file of state.files) { const row = el('tr'); row.append(el('td', file.path), el('td', String(file.lines)), el('td', `${(file.bytes / 1024).toFixed(1)} KB`), el('td', time(file.updated_at))); const cell = el('td'); cell.append(sourceButton(file, '预览')); row.append(cell); files.append(row); }
  if (!state.files.length) { const row = el('tr'); const cell = el('td', '暂无代码。配置目录后开始扫描。'); cell.colSpan = 5; row.append(cell); files.append(row); }
  $('scan-detail').textContent = `上次扫描：${time(state.lastScan)}${state.skipped.length ? '；跳过：' + state.skipped.join('；') : ''}`;
  const reviews = $('reviews'); reviews.replaceChildren();
  if (!state.reviews.length) reviews.append(el('div', '还没有审查记录。保存设置后，工具会开始审查指定目录中的源码。', 'empty panel'));
  const status = { done: '已完成', queued: '排队中', running: '审查中', failed: '失败' };
  for (const review of state.reviews) {
    const card = el('article', undefined, 'panel review-card'); const head = el('div', undefined, 'review-head'); const title = el('div'); title.append(el('h3', review.path), el('div', `${time(review.created_at)} · ${review.hash.slice(0, 8)}${review.current ? ' · 当前版本' : ' · 历史版本'}`, 'review-meta')); head.append(title, el('span', status[review.status], `badge ${review.status}`)); card.append(head);
    if (review.error) card.append(el('p', review.error, 'review-summary'));
    if (review.report) {
      card.append(el('p', review.report.summary, 'review-summary')); const tags = el('div'); review.report.topics.forEach(topic => tags.append(el('span', topic, 'badge'))); card.append(tags);
      for (const issue of review.report.issues) { const item = el('div', undefined, 'issue'); item.append(el('span', `${issue.severity === 'high' ? '高风险' : issue.severity === 'medium' ? '需确认' : '建议'} · 第 ${issue.line} 行`, `badge ${issue.severity}`), el('p', issue.message), el('p', `建议：${issue.suggestion}`, 'muted')); card.append(item); }
      if (!review.report.issues.length) card.append(el('p', '本次未报告明显问题；这不代表代码已通过编译或判题。', 'muted'));
    }
    card.append(sourceButton(review, '查看该版本代码')); reviews.append(card);
  }
}
async function refresh() { if (refreshing) return; refreshing = true; try { state = await api('state'); render(); } catch (error) { notify(`工作台连接失败：${error.message}`); } finally { refreshing = false; } }
function settingsInput() { return { root: $('setting-root').value, baseUrl: $('base-url').value, model: $('model').value, apiKey: $('api-key').value, clearApiKey: $('clear-key').checked, autoReview: $('auto-review').checked }; }
$('settings-form').addEventListener('submit', async event => { event.preventDefault(); $('save-settings').disabled = true; try { await api('settings', settingsInput()); $('api-key').value = ''; $('clear-key').checked = false; settingsLoaded = false; renderedMessages = ''; notify('设置已保存，已开始扫描和审查。'); await refresh(); switchView('reviews'); } catch (error) { notify(error.message); } finally { $('save-settings').disabled = false; } });
$('test-api').addEventListener('click', async () => { $('test-api').disabled = true; notify('正在测试模型连接…'); try { await api('test', settingsInput()); notify('API 连接成功，可以保存设置。'); } catch (error) { notify(error.message); } finally { $('test-api').disabled = false; } });
$('scan').addEventListener('click', async () => { $('scan').disabled = true; try { const result = await api('scan', {}); notify(`扫描完成：当前索引 ${result.indexed} 个文件。新增及变更内容已加入审查队列。`); await refresh(); } catch (error) { notify(error.message); } });
$('chat-form').addEventListener('submit', async event => { event.preventDefault(); const message = $('question').value.trim(); if (!message || chatBusy) return; chatBusy = true; render(); try { await api('chat', { message }); $('question').value = ''; notify(''); } catch (error) { notify(error.message); } finally { chatBusy = false; await refresh(); } });
$('question').addEventListener('keydown', event => { if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); $('chat-form').requestSubmit(); } });
await refresh(); setInterval(refresh, 2500);
