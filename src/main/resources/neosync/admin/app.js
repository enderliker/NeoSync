/* Copyright (c) NeoSync contributors; SPDX-License-Identifier: LGPL-2.1-only */
'use strict';
const byId = id => document.getElementById(id);
let snapshot, csrf = '', rows = [];
const status = text => { byId('status').textContent = text; };
async function api(path, body) {
  const response = await fetch(path, {method: body === undefined ? 'GET' : 'POST', credentials: 'same-origin',
    headers: body === undefined ? {} : {'Content-Type': 'application/json', 'X-NeoSync-CSRF': csrf},
    body: body === undefined ? undefined : JSON.stringify(body)});
  const data = await response.json();
  if (!response.ok) {
    if (response.status === 401) showLogin();
    throw new Error(data.error || 'The request failed. Reload and try again.');
  }
  return data;
}
function showLogin() { csrf = ''; byId('login').hidden = false; byId('panel').hidden = true; byId('logout').hidden = true; }
function element(tag, text, parent) { const e = document.createElement(tag); if (text) e.textContent = text; parent.append(e); return e; }
function check(parent, text) { const label = element('label', '', parent); const input = element('input', '', label); input.type = 'checkbox'; label.append(document.createTextNode(text)); return input; }
function totals() { const chosen = rows.filter(row => row.selected.checked); byId('totals').textContent = chosen.length + (chosen.length === 1 ? ' file selected · ' : ' files selected · ') + chosen.reduce((sum, row) => sum + row.file.size, 0).toLocaleString() + ' bytes'; }
async function reload() {
  const data = await api('/api/state'); snapshot = data; csrf = data.csrf;
  byId('login').hidden = true; byId('panel').hidden = false; byId('logout').hidden = false;
  byId('enabled').checked = data.enabled; byId('display-name').value = data.displayName;
  byId('pending').textContent = data.restartRequired ? 'Saved changes are pending. Restart the server to apply them.' : 'Showing the saved selection. Check the server log for discovery startup results.';
  byId('missing').textContent = data.missingSelections.length ? 'These saved files are no longer loaded and will be removed from the selection when you save: ' + data.missingSelections.join(', ') : '';
  byId('files').replaceChildren(); rows = [];
  for (const file of data.files) {
    const card = element('article', '', byId('files'));
    const selected = check(card, ' ' + file.description); selected.checked = file.selected;
    element('p', file.fileName + ' · ' + file.size.toLocaleString() + ' bytes', card);
    const label = element('label', 'Download source ', card); const source = element('select', '', label);
    for (const [value, text] of [['modrinth', 'Modrinth — exact file'], ['server', 'Provided by this server'], ...(file.source === 'configured' ? [['configured', 'Keep configured external source']] : [])]) {
      const option = element('option', text, source); option.value = value;
    }
    source.value = file.source;
    const declarations = element('fieldset', '', card); element('legend', 'Hosting eligibility — confirm for these exact bytes', declarations);
    element('p', 'Hosting is only for mods you wrote for this server and have not published or distributed elsewhere. Third-party mods and provider outages do not qualify.', declarations);
    const authoredByAdministrator = check(declarations, ' I wrote this mod.');
    const exclusiveToServer = check(declarations, ' It is unique to this server and not distributed anywhere else.');
    const distributionRights = check(declarations, ' I have the rights to distribute it to these players.');
    element('code', file.sha256, declarations);
    const update = () => { source.disabled = !selected.checked; declarations.hidden = source.value !== 'server' || !selected.checked; totals(); };
    selected.addEventListener('change', update); source.addEventListener('change', update);
    rows.push({file, card, selected, source, authoredByAdministrator, exclusiveToServer, distributionRights}); update();
  }
  totals(); filter();
}
function filter() { const query = byId('filter').value.toLowerCase(); rows.forEach(row => { row.card.hidden = !(row.file.description + ' ' + row.file.fileName).toLowerCase().includes(query); }); }
byId('filter').addEventListener('input', filter);
byId('login-form').addEventListener('submit', async event => {
  event.preventDefault(); const button = event.submitter; button.disabled = true;
  try { await api('/api/login', {password: byId('password').value}); byId('password').value = ''; await reload(); status('Signed in.'); }
  catch (error) { byId('password').value = ''; status(error.message); }
  finally { button.disabled = false; }
});
byId('selection').addEventListener('submit', async event => {
  event.preventDefault(); byId('save').disabled = true;
  try {
    const files = rows.filter(row => row.selected.checked).map(row => {
      const file = {fileName: row.file.fileName, sha256: row.file.sha256, source: row.source.value};
      if (file.source === 'server') for (const key of ['authoredByAdministrator', 'exclusiveToServer', 'distributionRights']) {
        if (!row[key].checked) throw new Error('Confirm every hosting declaration for ' + file.fileName + '.');
        file[key] = true;
      }
      return file;
    });
    await api('/api/selection', {revision: snapshot.revision, enabled: byId('enabled').checked, displayName: byId('display-name').value, files});
    await reload(); status('Selection saved. Restart the server to apply it.');
  } catch (error) { status(error.message); } finally { byId('save').disabled = false; }
});
byId('reload').addEventListener('click', () => reload().then(() => status('Selection reloaded.')).catch(error => status(error.message)));
byId('logout').addEventListener('click', () => api('/api/logout', {}).then(() => { showLogin(); status('Signed out.'); }).catch(error => status(error.message)));
reload().catch(error => { if (!byId('panel').hidden) status(error.message); });
