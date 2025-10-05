// Scoped enhancements for Jira target date fields to enforce 4-digit year (<= 9999)
(function () {
  function clampYearStr(yyyy) {
    if (!yyyy) return yyyy;
    // Keep only digits
    yyyy = (yyyy + '').replace(/\D+/g, '');
    // Truncate to 4 digits
    if (yyyy.length > 4) yyyy = yyyy.slice(0, 4);
    // Clamp to max 9999 (min handled by browser as 0001)
    var n = parseInt(yyyy, 10);
    if (!isNaN(n) && n > 9999) yyyy = '9999';
    return yyyy;
  }

  function sanitizeDateValue(el) {
    if (!el || !el.value) return;
    // Expect yyyy-mm-dd; if not, try to normalize the year part only
    var parts = el.value.split('-');
    if (parts.length >= 1) {
      parts[0] = clampYearStr(parts[0]);
      // Recompose keeping month/day as-is; browser will validate against min/max
      var next = parts.join('-');
      if (next !== el.value) el.value = next;
    }
    // If value violates max, browser will set validity; we can optionally clear invalid
    if (el.max && el.value && el.value > el.max) {
      el.value = el.max;
    }
  }

  function attach(id) {
    var el = document.getElementById(id);
    if (!el) return;
    // Ensure constraints present even if HTML changes in future
    if (!el.getAttribute('max')) el.setAttribute('max', '9999-12-31');
    if (!el.getAttribute('min')) el.setAttribute('min', '0001-01-01');

    el.addEventListener('change', function () { sanitizeDateValue(el); });
    el.addEventListener('blur', function () { sanitizeDateValue(el); });
    // On paste, sanitize after paste occurs
    el.addEventListener('paste', function () {
      setTimeout(function () { sanitizeDateValue(el); }, 0);
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () {
      attach('jira-target-start');
      attach('jira-target-end');
    });
  } else {
    attach('jira-target-start');
    attach('jira-target-end');
  }
})();

// Field visibility logic for Jira Management tab
(function(){
  function q(sel){ return document.querySelector(sel); }
  function qa(sel){ return Array.from(document.querySelectorAll(sel)); }
  function setHidden(el, hide){ if (!el) return; el.classList.toggle('is-hidden', !!hide); }

  // Track last selected action and the user's Type selection before entering TEM Support
  var lastAction = null;
  var savedTypeBeforeSupport = null;

  function normalizeType(val){
    return String(val || '').trim().toLowerCase();
  }

  function getTypeValue(){
    var sel = q('#jira-type');
    if (!sel) return 'bug';
    var v = sel.value || (sel.options[sel.selectedIndex] && sel.options[sel.selectedIndex].text) || '';
    return normalizeType(v);
  }

  function getAction(){
    var checked = qa('input[name="jiraAction"]').find(r => r.checked);
    return checked ? checked.value : 'CREATE_MODIFY';
  }

  function enableType(enabled){
    var sel = q('#jira-type');
    if (!sel) return;
    sel.disabled = !enabled;
  }

  function selectType(val){
    var sel = q('#jira-type');
    if (!sel) return;
    var target = String(val || '').toLowerCase();
    for (var i=0;i<sel.options.length;i++){
      var opt = sel.options[i];
      var txt = String(opt.value || opt.text).toLowerCase();
      if (txt === target) { sel.selectedIndex = i; break; }
    }
  }

  function showAllowed(allowed){
    // Toggle all registered fields
    qa('[data-field]').forEach(function(el){
      var key = el.getAttribute('data-field');
      if (!key) return;
      setHidden(el, !allowed.has(key));
    });
  }

  function applyVisibility(){
    var action = getAction();
    var type = getTypeValue();

    var allowed = new Set();

    if (action === 'REQUEST_SUPPORT') {
      // On entering TEM Support, remember user's current Type to restore later
      if (lastAction !== 'REQUEST_SUPPORT') {
        savedTypeBeforeSupport = type;
      }
      // TEM Support: force Type to Task (hidden) and disable selector
      // Keep selecting internally to preserve payload semantics, but do not show the field
      selectType('task');
      enableType(false);
      // Allowed fields for TEM Support (remove 'type' from UI)
      ['summary','description','typeSupport','priority','primaryApplication','environment','issueType','reporterDup']
        .forEach(k => allowed.add(k));
      // Sync Reporter value into the TEM Support duplicate on entry
      var baseRep = document.getElementById('jira-reporter');
      var supRep  = document.getElementById('jira-reporter-support');
      if (baseRep && supRep && supRep.value !== baseRep.value) {
        supRep.value = baseRep.value;
      }
      // Hide target row entirely in this mode
      // Note: included by not adding 'targetRow' to allowed
    } else {
      // Create TTS Jira
      enableType(true);
      // If coming back from TEM Support, restore previously selected Type
      if (lastAction === 'REQUEST_SUPPORT' && savedTypeBeforeSupport) {
        selectType(savedTypeBeforeSupport);
      }
      // When leaving TEM Support, propagate Reporter value back to the base field if needed
      if (lastAction === 'REQUEST_SUPPORT') {
        var baseRep2 = document.getElementById('jira-reporter');
        var supRep2  = document.getElementById('jira-reporter-support');
        if (baseRep2 && supRep2 && baseRep2.value !== supRep2.value) {
          baseRep2.value = supRep2.value || '';
          try { baseRep2.dispatchEvent(new Event('input', { bubbles: true })); } catch {}
          try { baseRep2.dispatchEvent(new Event('change', { bubbles: true })); } catch {}
        }
      }
      // Re-evaluate type after potential restore
      type = getTypeValue();
      // Always allowed base fields
      ['summary','description','type','epicLink','priority','fixVersions','scrumTeam','labels','assignee','participants','reporter','targetDates']
        .forEach(k => allowed.add(k));
      // Type-specific
      if (type === 'bug') {
        // Bug also includes QA/testing related fields
        ['testCaseType','regression','detectedEnvironment'].forEach(k => allowed.add(k));
        // Do NOT add storyPoints
      } else if (type === 'task' || type === 'story') {
        // Add Story Points for Task/Story
        allowed.add('storyPoints');
      }
    }

    showAllowed(allowed);
    // Track last action for next transition
    lastAction = action;
  }

  function setupJiraVisibility(){
    // initial apply
    applyVisibility();
    // listeners
    qa('input[name="jiraAction"]').forEach(r => r.addEventListener('change', applyVisibility));
    var typeSel = q('#jira-type');
    if (typeSel) typeSel.addEventListener('change', applyVisibility);
  }
  // run on load
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', setupJiraVisibility);
  } else {
    setupJiraVisibility();
  }
})();

// Enforce single-line input for Jira Summary field
(function(){
  function enforceSingleLine(el){
    if (!el) return;
    function sanitize(){
      var v = el.value || '';
      var next = v.replace(/[\r\n]+/g, ' ');
      if (next !== v) el.value = next;
    }
    // Block Enter key
    el.addEventListener('keydown', function(e){
      if (e && (e.key === 'Enter' || e.keyCode === 13)) {
        e.preventDefault();
        return false;
      }
    });
    // Strip any pasted or programmatic newlines
    el.addEventListener('input', sanitize);
    el.addEventListener('paste', function(){ setTimeout(sanitize, 0); });
  }

  function init(){
    enforceSingleLine(document.getElementById('jira-summary'));
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();

// Mirror 'Reporter' value between Create TTS Jira and TEM Support duplicate
(function(){
  function bindMirror(){
    var base = document.getElementById('jira-reporter');
    var dup  = document.getElementById('jira-reporter-support');
    if (!base || !dup) return;

    var guard = false;
    function sync(src, dst){
      if (guard) return;
      guard = true;
      if (dst.value !== src.value) dst.value = src.value || '';
      guard = false;
    }
    ['input','change','blur'].forEach(function(evt){
      base.addEventListener(evt, function(){ sync(base, dup); });
      dup.addEventListener(evt, function(){ sync(dup, base); });
    });

    // Initial alignment: prefer existing base value if present
    if (base.value && base.value !== dup.value) dup.value = base.value;
    else if (dup.value && dup.value !== base.value) base.value = dup.value;
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bindMirror);
  } else {
    bindMirror();
  }
})();
