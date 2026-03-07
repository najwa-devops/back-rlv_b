const API_BASE = '/api/v2/centre-monetique';

const uploadForm = document.getElementById('upload-form');
const uploadStatus = document.getElementById('upload-status');
const historyEl = document.getElementById('history');
const detailEl = document.getElementById('detail');
const jsonResultEl = document.getElementById('json-result');
const refreshBtn = document.getElementById('refresh-history');

uploadForm.addEventListener('submit', onUpload);
refreshBtn.addEventListener('click', loadHistory);

loadHistory();

async function onUpload(event) {
  event.preventDefault();
  uploadStatus.textContent = 'Upload en cours...';

  const fileInput = document.getElementById('file');
  const yearInput = document.getElementById('year');
  const file = fileInput.files[0];

  if (!file) {
    uploadStatus.textContent = 'Veuillez sélectionner un fichier.';
    return;
  }

  const formData = new FormData();
  formData.append('file', file);
  if (yearInput.value) {
    formData.append('year', yearInput.value);
  }

  try {
    const response = await fetch(`${API_BASE}/upload`, {
      method: 'POST',
      body: formData
    });
    const data = await response.json();

    if (!response.ok) {
      throw new Error(data.error || 'Erreur upload');
    }

    uploadStatus.textContent = 'Extraction terminée.';
    renderDetail(data.batch);
    jsonResultEl.textContent = JSON.stringify(data.rows || [], null, 2);
    await loadHistory();
  } catch (error) {
    uploadStatus.textContent = `Erreur: ${error.message}`;
  }
}

async function loadHistory() {
  historyEl.innerHTML = 'Chargement...';
  try {
    const response = await fetch(`${API_BASE}?limit=50`);
    const data = await response.json();

    if (!response.ok) {
      throw new Error(data.error || 'Erreur de chargement');
    }

    const batches = data.batches || [];
    if (!batches.length) {
      historyEl.innerHTML = '<p>Aucun batch.</p>';
      return;
    }

    historyEl.innerHTML = '';
    batches.forEach(batch => {
      const item = document.createElement('article');
      item.className = 'history-item';
      item.innerHTML = `
        <div class="row">
          <strong>#${batch.id}</strong>
          <span class="badge">${batch.status}</span>
          <span>${escapeHtml(batch.originalName || '')}</span>
        </div>
        <div class="row">
          <span>Tx: ${batch.transactionCount ?? 0}</span>
          <span>Montant: ${batch.totalMontant || '-'}</span>
          <span>Débit: ${batch.totalDebit || '-'}</span>
          <span>Crédit: ${batch.totalCredit || '-'}</span>
          <span>Créé: ${batch.createdAt || '-'}</span>
        </div>
        <div class="row">
          <button data-action="open" data-id="${batch.id}">Ouvrir</button>
          <button data-action="reprocess" data-id="${batch.id}">Reprocess</button>
          <button data-action="file" data-id="${batch.id}">Fichier</button>
          <button data-action="delete" data-id="${batch.id}">Supprimer</button>
        </div>
      `;
      historyEl.appendChild(item);
    });

    historyEl.querySelectorAll('button').forEach(btn => {
      btn.addEventListener('click', onHistoryAction);
    });
  } catch (error) {
    historyEl.innerHTML = `<p>Erreur: ${escapeHtml(error.message)}</p>`;
  }
}

async function onHistoryAction(event) {
  const btn = event.currentTarget;
  const id = btn.dataset.id;
  const action = btn.dataset.action;

  if (action === 'open') {
    await openBatch(id);
    return;
  }

  if (action === 'reprocess') {
    await reprocessBatch(id);
    return;
  }

  if (action === 'file') {
    window.open(`${API_BASE}/${id}/file`, '_blank');
    return;
  }

  if (action === 'delete') {
    if (!confirm(`Supprimer batch #${id} ?`)) {
      return;
    }
    await fetch(`${API_BASE}/${id}`, { method: 'DELETE' });
    await loadHistory();
  }
}

async function openBatch(id) {
  const response = await fetch(`${API_BASE}/${id}`);
  const data = await response.json();
  if (!response.ok) {
    throw new Error(data.error || 'Erreur détail');
  }
  renderDetail(data);
  jsonResultEl.textContent = JSON.stringify(data.rows || [], null, 2);
}

async function reprocessBatch(id) {
  const year = prompt('Année optionnelle (laisser vide pour auto):', '');
  const params = new URLSearchParams();
  if (year && year.trim()) {
    params.set('year', year.trim());
  }
  const url = params.toString() ? `${API_BASE}/${id}/reprocess?${params}` : `${API_BASE}/${id}/reprocess`;

  const response = await fetch(url, { method: 'POST' });
  const data = await response.json();
  if (!response.ok) {
    throw new Error(data.error || 'Erreur retraitement');
  }
  renderDetail(data.batch);
  jsonResultEl.textContent = JSON.stringify(data.rows || [], null, 2);
  await loadHistory();
}

function renderDetail(batch) {
  if (!batch) {
    detailEl.innerHTML = '<p>Aucun détail.</p>';
    return;
  }

  const rows = batch.rows || [];
  detailEl.innerHTML = `
    <p><strong>ID:</strong> ${batch.id} | <strong>Statut:</strong> ${escapeHtml(batch.status || '')}</p>
    <p><strong>Fichier:</strong> ${escapeHtml(batch.originalName || '')}</p>
    <p><strong>Transactions:</strong> ${batch.transactionCount ?? 0} | <strong>Total:</strong> ${batch.totalMontant || '-'}</p>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Section</th>
            <th>Date</th>
            <th>Reference</th>
            <th>Montant</th>
            <th>Debit</th>
            <th>Credit</th>
          </tr>
        </thead>
        <tbody>
          ${rows.map(row => `
            <tr>
              <td>${escapeHtml(row.section || '')}</td>
              <td>${escapeHtml(row.date || '')}</td>
              <td>${escapeHtml(row.reference || '')}</td>
              <td>${escapeHtml(row.montant || '')}</td>
              <td>${escapeHtml(row.debit || '')}</td>
              <td>${escapeHtml(row.credit || '')}</td>
            </tr>
          `).join('')}
        </tbody>
      </table>
    </div>
  `;
}

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}
