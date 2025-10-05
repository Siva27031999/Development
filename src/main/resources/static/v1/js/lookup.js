"use strict";

function initializeLookups(contextPath) {
  const CTX = (typeof contextPath === 'string' && contextPath.length > 0) ? contextPath : '/portal';

  // Helpers
  const debounce = (fn, ms = 150) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; };
  const show = el => el && (el.style.display = 'block');
  const hide = el => el && (el.style.display = 'none');
  const esc = s => (s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  function initLookup(inputEl) {
    if (!inputEl || inputEl._lookupBound) return; // idempotent
    inputEl._lookupBound = true;

    const wrap = inputEl.closest('.lookup-wrap');
    const menu = wrap.querySelector('.lookup-menu');
    const toast = wrap.querySelector('.lookup-toast');

    const key = (inputEl.dataset.key || 'default').trim();
    const strict = inputEl.hasAttribute('data-lookup-strict');
    const limit = Math.max(1, Math.min(50, parseInt(inputEl.dataset.limit || '8', 10)));
    const base = `${CTX}/api/lookup/${encodeURIComponent(key)}`;

    let lastQuery = '';
    let hideTimer;
    let lastItems = [];
    let suppressOpenOnce = false; // prevent menu reopen immediately after programmatic selection

    const setToast = (msg) => {
      if (!toast) return;
      toast.textContent = msg;
      show(toast);
      clearTimeout(hideTimer);
      hideTimer = setTimeout(() => hide(toast), 2500);
    };

    const render = (q, items) => {
      menu.innerHTML = '';
      if (!items || items.length === 0) {
        const li = document.createElement('li');
        li.className = 'list-group-item d-flex justify-content-between align-items-center';
        li.innerHTML = `<span>No matches</span>` + (strict ? '' : `
                        <button class="btn btn-sm btn-primary">Add '${esc(q)}'</button>`);
        if (!strict) {
          li.querySelector('button').onclick = () => addValue(q);
        }
        menu.appendChild(li);
      } else {
        items.forEach(v => {
          const li = document.createElement('li');
          li.className = 'list-group-item d-flex justify-content-between align-items-center';
          li.innerHTML = `<span class="me-2 flex-grow-1">${esc(v)}</span>
                          <div class="btn-group btn-group-sm">
                            <button class="btn btn-outline-secondary select-btn">Select</button>` + (strict ? '' : `
                            <button class="btn btn-outline-danger delete-btn" title="Delete">Delete</button>`) + `
                          </div>`;
          li.querySelector('.select-btn').onclick = () => {
            suppressOpenOnce = true;
            inputEl.value = v;
            // Notify any listeners (e.g., mirror logic) that value changed programmatically
            try { inputEl.dispatchEvent(new Event('input', { bubbles: true })); } catch {}
            try { inputEl.dispatchEvent(new Event('change', { bubbles: true })); } catch {}
            hide(menu);
          };
          if (!strict) {
            const delBtn = li.querySelector('.delete-btn');
            if (delBtn) delBtn.onclick = () => deleteValue(v);
          }
          menu.appendChild(li);
        });
        if (!strict && q && !items.some(x => x.toLowerCase() === q.toLowerCase())) {
          const li = document.createElement('li');
          li.className = 'list-group-item d-flex justify-content-between align-items-center';
          li.innerHTML = `<em class="text-muted">Not found</em>
                          <button class="btn btn-sm btn-outline-primary">Add '${esc(q)}'</button>`;
          li.querySelector('button').onclick = () => addValue(q);
          menu.appendChild(li);
        }
      }
      show(menu);
    };

    const search = async (q) => {
      lastQuery = q;
      try {
        const r = await fetch(`${base}?q=${encodeURIComponent(q)}&limit=${limit}&contains=true`);
        if (!r.ok) return;
        const data = await r.json();
        lastItems = Array.isArray(data) ? data : [];
        if (q === lastQuery) render(q, data);
      } catch {}
    };

    async function validateStrictValue() {
      if (!strict) return;
      const v = (inputEl.value || '').trim();
      if (!v) return; // allow empty (user cleared)
      try {
        // Keep strict validation against prefix-based results to preserve legacy behavior
        const r = await fetch(`${base}?q=${encodeURIComponent(v)}&limit=${limit}`);
        const data = r.ok ? (await r.json()) : [];
        const ok = (data || []).some(x => (x || '').toLowerCase() === v.toLowerCase());
        if (!ok) inputEl.value = '';
      } catch {}
    }

    const addValue = async (v) => {
      if (!v || !v.trim()) return;
      setToast(`Saved locally to '${key}': '${v}' (DB sync async).`);
      try {
        await fetch(base, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ value: v })
        });
      } catch {}
      suppressOpenOnce = true;
      inputEl.value = v;
      try { inputEl.dispatchEvent(new Event('input', { bubbles: true })); } catch {}
      try { inputEl.dispatchEvent(new Event('change', { bubbles: true })); } catch {}
      hide(menu);
    };

    const deleteValue = async (v) => {
      if (!v || !v.trim()) return;
      setToast(`Removed locally from '${key}': '${v}' (DB sync async).`);
      try {
        await fetch(`${base}?value=${encodeURIComponent(v)}`, { method: 'DELETE' });
      } catch {}
      search(inputEl.value || '');
    };

    // Events
    inputEl.addEventListener('focus', () => search(inputEl.value || ''));
    inputEl.addEventListener('input', debounce(() => {
      if (suppressOpenOnce) { suppressOpenOnce = false; return; }
      search(inputEl.value || '');
    }, 150));
    inputEl.addEventListener('blur', () => { validateStrictValue(); });
    document.addEventListener('click', (e) => {
      if (!wrap.contains(e.target)) hide(menu);
    });
  }

  // Multi-select lookup (adds to field only; does not persist new values)
  function initLookupMulti(inputEl) {
    if (!inputEl || inputEl._lookupBound) return; // idempotent
    inputEl._lookupBound = true;

    const wrap  = inputEl.closest('.lookup-wrap');
    const chips = wrap.querySelector('.selected-chips');
    const menu  = wrap.querySelector('.lookup-menu');
    const toast = wrap.querySelector('.lookup-toast');

    const key   = (inputEl.dataset.key || 'default').trim();
    const limit = Math.max(1, Math.min(50, parseInt(inputEl.dataset.limit || '8', 10)));
    const base  = `${CTX}/api/lookup/${encodeURIComponent(key)}`;
    const name  = inputEl.dataset.name || key;

    // Hidden input to hold selected values (CSV)
    let hidden = wrap.querySelector('input[type="hidden"][data-selected]');
    if (!hidden) {
      hidden = document.createElement('input');
      hidden.type = 'hidden';
      hidden.setAttribute('data-selected', '');
      hidden.name = name;
      wrap.appendChild(hidden);
    }

    const selected = new Map(); // norm -> original
    let suppressOpenOnce = false; // prevent menu reopen right after adding a chip

    function updateHidden() {
      hidden.value = Array.from(selected.values()).join(',');
    }

    function setToast(msg) {
      if (!toast) return;
      toast.textContent = msg;
      show(toast);
      setTimeout(() => hide(toast), 2000);
    }

    function addChip(v) {
      const norm = v.trim().toLowerCase();
      if (!norm || selected.has(norm)) return;
      selected.set(norm, v);

      const badge = document.createElement('span');
      badge.className = 'badge text-bg-primary d-inline-flex align-items-center';
      badge.dataset.norm = norm;
      badge.innerHTML = `${v} <button type="button" class="btn-close btn-close-white btn-sm ms-2" aria-label="Remove"></button>`;
      badge.querySelector('button').onclick = () => removeChip(norm, badge);
      chips.appendChild(badge);
      updateHidden();
    }

    function removeChip(norm, badgeEl) {
      selected.delete(norm);
      if (badgeEl && badgeEl.remove) badgeEl.remove();
      updateHidden();
    }

    async function persistAndAdd(v) {
      if (!v || !v.trim()) return;
      try {
        await fetch(base, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ value: v })
        });
        setToast(`Saved to lookup and added: '${v}'`);
      } catch {}
      addChip(v);
      inputEl.value = '';
      hide(menu);
    }

    function deleteFromLookup(v) {
      if (!v || !v.trim()) return;
      setToast(`Removed from lookup: '${v}'`);
      fetch(`${base}?value=${encodeURIComponent(v)}`, { method: 'DELETE' })
        .catch(() => {})
        .finally(() => search(inputEl.value || ''));
    }

    function render(q, items) {
      menu.innerHTML = '';
      const lowerSel = new Set(selected.keys());
      const list = (items || []);
      if (!list || list.length === 0) {
        const li = document.createElement('li');
        li.className = 'list-group-item d-flex justify-content-between align-items-center';
        li.innerHTML = `<span>No matches</span>
                        <div class="btn-group btn-group-sm">
                          <button class="btn btn-primary add-local">Add '${q}'</button>
                          <button class="btn btn-outline-primary add-save">Add to lookup</button>
                        </div>`;
        li.querySelector('.add-local').onclick = () => { if (q && q.trim()) { suppressOpenOnce = true; addChip(q); inputEl.value=''; hide(menu); } };
        li.querySelector('.add-save').onclick  = () => { if (q && q.trim()) { suppressOpenOnce = true; persistAndAdd(q); } };
        menu.appendChild(li);
      } else {
        list.forEach(v => {
          const norm = v.trim().toLowerCase();
          const isSelected = lowerSel.has(norm);
          const li = document.createElement('li');
          li.className = 'list-group-item d-flex justify-content-between align-items-center';
          li.innerHTML = `<span class="me-2 flex-grow-1">${v}</span>
                          <div class="btn-group btn-group-sm">
                            ${isSelected ? '<span class="badge text-bg-secondary">Selected</span>' : '<button class="btn btn-outline-secondary select-btn">Add</button>'}
                            <button class="btn btn-outline-danger delete-btn" title="Delete from lookup">Delete</button>
                          </div>`;
          if (!isSelected) {
            li.querySelector('.select-btn').onclick = () => { suppressOpenOnce = true; addChip(v); inputEl.value=''; hide(menu); };
          }
          li.querySelector('.delete-btn').onclick = () => deleteFromLookup(v);
          menu.appendChild(li);
        });
        if (q) {
          const qLower = q.toLowerCase();
          const lowerItems = new Set(list.map(x => x.toLowerCase()));
          if (!lowerItems.has(qLower)) {
          const li2 = document.createElement('li');
          li2.className = 'list-group-item d-flex justify-content-between align-items-center';
          li2.innerHTML = `<em class="text-muted">Not found</em>
                          <div class="btn-group btn-group-sm">
                            <button class="btn btn-outline-primary add-local">Add '${q}'</button>
                            <button class="btn btn-outline-success add-save">Add to lookup</button>
                          </div>`;
          li2.querySelector('.add-local').onclick = () => { addChip(q); inputEl.value=''; hide(menu); };
          li2.querySelector('.add-save').onclick  = () => { persistAndAdd(q); };
          menu.appendChild(li2);
          }
        }
      }
      show(menu);
    }

    function search(q) {
      fetch(`${base}?q=${encodeURIComponent(q)}&limit=${limit}&contains=true`)
        .then(r => r.ok ? r.json() : [])
        .then(data => render(q, data))
        .catch(() => render(q, []));
    }

    // Events
    inputEl.addEventListener('focus', () => search(inputEl.value || ''));
    inputEl.addEventListener('input', debounce(() => {
      if (suppressOpenOnce) { suppressOpenOnce = false; return; }
      search(inputEl.value || '');
    }, 150));
    inputEl.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ',' || e.key === 'Tab') {
        const v = (inputEl.value || '').trim();
        if (v) {
          e.preventDefault();
          suppressOpenOnce = true;
          addChip(v); inputEl.value=''; hide(menu);
        }
      } else if (e.key === 'Backspace' && !inputEl.value) {
        // Remove last chip
        const last = chips.lastElementChild;
        if (last) removeChip(last.dataset.norm, last);
      }
    });
    document.addEventListener('click', (evt) => { if (!wrap.contains(evt.target)) hide(menu); });
  }

  // Auto-bind every input with data-lookup / data-lookup-multi
  const bindAll = () => {
    document.querySelectorAll('[data-lookup]').forEach(initLookup);
    document.querySelectorAll('[data-lookup-multi]').forEach(initLookupMulti);
  };
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bindAll);
  } else {
    bindAll();
  }
}
// Initialize with default context path (override if needed)
window.addEventListener('DOMContentLoaded', () => initializeLookups('/portal'));
