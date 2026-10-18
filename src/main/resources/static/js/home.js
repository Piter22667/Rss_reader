document.querySelectorAll('[data-import-form]').forEach(form => {
  const button = form.querySelector('button');
  form.addEventListener('submit', () => {
    button.disabled = true;
    button.textContent = 'Оновлення…';
    form.setAttribute('aria-busy', 'true');
  });
  window.addEventListener('pageshow', () => {
    button.disabled = false;
    button.textContent = '⟳ Оновити';
    form.removeAttribute('aria-busy');
  });
});

// Preferences management
(function initPreferences() {
  const olderBtn = document.getElementById('prefOlder');
  const newerBtn = document.getElementById('prefNewer');
  const improveBtn = document.getElementById('prefImprove');
  const textarea = document.getElementById('prefTextarea');
  const saveBtn = document.getElementById('prefSaveBtn');
  const hint = document.getElementById('prefHint');
  const form = document.getElementById('prefsForm');
  const toast = document.getElementById('prefToast');
  const languageSelect = document.getElementById('prefLanguage');
  const summaryLengthSelect = document.getElementById('prefSummaryLength');
  const styleSelect = document.getElementById('prefStyle');

  const previewStatus = document.getElementById('summaryPreviewStatus');
  const inputWarning = document.getElementById('summaryInputWarning');
  let previewBusy = false;




  if (!textarea || !olderBtn || !newerBtn) return;

  const csrfToken = document.querySelector('meta[name="_csrf"]');
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]');

  function requestHeaders(json) {
    const headers = {};
    if (json) headers['Content-Type'] = 'application/json';
    if (csrfToken && csrfHeader) headers[csrfHeader.content] = csrfToken.content;
    return headers;
  }

  let toastTimer = null;
  function showToast(message) {
    if (!toast) return;
    toast.textContent = message;
    toast.classList.add('visible');
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toast.classList.remove('visible'), 1000);
  }

  let versions = Array.isArray(window.initialPreferences) ? [...window.initialPreferences] : [];
  let cursor = versions.length > 0 ? versions.length - 1 : -1;
  const preferenceKey = `rss-preference-${window.currentFeedId}`;
  try {
    const savedId = sessionStorage.getItem(preferenceKey);
    for (let i = 0; i < versions.length; i++) {
      if (String(versions[i].id) === savedId) cursor = i;
    }
  } catch (ignored) { }

  function applySelect(select, value, fallback) {
    if (!select) return;
    const target = value || fallback;
    if (Array.from(select.options).some(option => option.value === target)) select.value = target;
  }

  function currentOptions() {
    return {
      language: languageSelect ? languageSelect.value : 'UK',
      summaryLength: summaryLengthSelect ? summaryLengthSelect.value : 'MEDIUM',
      style: styleSelect ? styleSelect.value : 'NEUTRAL'
    };
  }

  function updateUI() {
    if (cursor >= 0 && cursor < versions.length) {
      const version = versions[cursor];
      textarea.value = version.content || '';
      applySelect(languageSelect, version.language, 'UK');
      applySelect(summaryLengthSelect, version.summaryLength, 'MEDIUM');
      applySelect(styleSelect, version.style, 'NEUTRAL');
      hint.textContent = `Версія ${cursor + 1} з ${versions.length}. ← старіша, → новіша. Нове збереження створює нову версію.`;
    } else {
      hint.textContent = 'Додайте вподобання і натисніть «Зберегти».';
    }
    olderBtn.disabled = cursor <= 0;
    newerBtn.disabled = cursor >= versions.length - 1;
    if (previewStatus) previewStatus.textContent = '';

    let version = null;
    if (cursor >= 0) {
      version = versions[cursor];
      try { sessionStorage.setItem(preferenceKey, String(version.id)); } catch (ignored) { }
    }
    if (window.summaryView) window.summaryView.changeVersion(version);
    updateProcessingControls();
    if (window.summaryBatch) window.summaryBatch.changeVersion(version);
  }

  function hasUnsavedPreferences() {
    if (cursor < 0) return true;
    const version = versions[cursor];
    const options = currentOptions();
    return textarea.value.trim() !== (version.content || '').trim()
      || options.language !== version.language
      || options.summaryLength !== version.summaryLength
      || options.style !== version.style;
  }

  function updateProcessingControls() {
    const unsaved = hasUnsavedPreferences();
    inputWarning.hidden = cursor >= 0 && !unsaved;
    inputWarning.textContent = cursor < 0 ? 'Спочатку збережіть вподобання.' : 'Є незбережені зміни — спочатку натисніть «Зберегти».';
    const batchRunning = window.summaryBatch && window.summaryBatch.isRunning();
    const saving = saveBtn.disabled || improveBtn.disabled;
    const allowed = !previewBusy && !unsaved && !saving;
    if (window.summaryView) {
      window.summaryView.setBusy(previewBusy || batchRunning);
      window.summaryView.setProcessingAllowed(allowed && !batchRunning);
    }
    if (window.summaryBatch) window.summaryBatch.setProcessingAllowed(allowed);
  }

  function showSummary(data) {
    const label = `Стаття №${data.articleId} · версія ${data.preferenceVersion}.`;
    if (data.status === 'OK') {
      previewStatus.textContent = `Готово · ${label} Підсумок збережено в БД.`;
    } else if (data.status === 'HIDDEN') {
      previewStatus.textContent = `Приховано · ${label} ${data.hiddenReason}`;
    } else {
      previewStatus.textContent = `FAILED · ${label} ${data.message || 'Обробка не вдалася. Якщо текст має FAILED, Bright Data повторно не викликається.'}`;
    }
  }
  textarea.addEventListener('input', updateProcessingControls);
  [languageSelect, summaryLengthSelect, styleSelect].forEach(select => {
    if (select) select.addEventListener('change', updateProcessingControls);
  });

  async function runSummary(articleId) {
    if (previewBusy || hasUnsavedPreferences() || saveBtn.disabled || improveBtn.disabled) return;
    if (window.summaryBatch && window.summaryBatch.isRunning()) return;
    const version = versions[cursor];
    previewBusy = true;
    updateProcessingControls();
    if (window.summaryView) window.summaryView.show();
    previewStatus.classList.add('summary-preview-loading');
    previewStatus.setAttribute('aria-busy', 'true');
    previewStatus.textContent = `Повторна обробка статті №${articleId}…`;
    const controls = [olderBtn, newerBtn, textarea, languageSelect, summaryLengthSelect, styleSelect, saveBtn, improveBtn];
    controls.forEach(control => { if (control) control.disabled = true; });
    try {
      const response = await fetch(`/api/feeds/${window.currentFeedId}/summary-preview?preferenceId=${version.id}&retry=true&articleId=${articleId}`, {
        method: 'POST', headers: requestHeaders(false)
      });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(data.error || 'Не вдалося повторити обробку.');
      showSummary(data);
      if (window.summaryView) await window.summaryView.refresh(data.status === 'OK' ? data.articleId : null);
      if (window.summaryBatch) window.summaryBatch.changeVersion(version);
    } catch (error) {
      previewStatus.textContent = error.message;
    } finally {
      previewBusy = false;
      controls.forEach(control => { if (control) control.disabled = false; });
      olderBtn.disabled = cursor <= 0;
      newerBtn.disabled = cursor >= versions.length - 1;
      previewStatus.classList.remove('summary-preview-loading');
      previewStatus.removeAttribute('aria-busy');
      updateProcessingControls();
    }
  }

  if (window.summaryView) window.summaryView.retryArticle = runSummary;
  if (window.summaryBatch) window.summaryBatch.onStateChange = updateProcessingControls;
  updateUI();

  olderBtn.addEventListener('click', () => {
    if (cursor > 0) {
      cursor--;
      updateUI();
    }
  });

  newerBtn.addEventListener('click', () => {
    if (cursor < versions.length - 1) {
      cursor++;
      updateUI();
    }
  });

  async function persist(content) {
    const response = await fetch(`/api/feeds/${window.currentFeedId}/preferences`, {
      method: 'POST',
      headers: requestHeaders(true),
      body: JSON.stringify({ content, ...currentOptions() })
    });
    if (!response.ok) throw new Error('Помилка сервера');
    return response.json();
  }

  if (form && window.currentFeedId) {
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      const content = textarea.value.trim();
      if (!content) {
        textarea.focus();
        showToast('Введіть текст вподобань перед збереженням.');
        return;
      }

      saveBtn.disabled = true;
      const originalText = saveBtn.textContent;
      saveBtn.textContent = 'Збереження…';
      updateProcessingControls();

      try {
        const newVersion = await persist(content);
        versions.push(newVersion);
        cursor = versions.length - 1;
        updateUI();
        showToast('Вподобання збережено ✓');
      } catch (err) {
        // Fallback to a standard form submission when AJAX is unavailable.
        form.submit();
        return;
      } finally {
        saveBtn.textContent = originalText;
        saveBtn.disabled = false;
        updateProcessingControls();
      }
    });
  }

  if (improveBtn) {
    improveBtn.addEventListener('click', async () => {
      const text = textarea.value.trim();
      if (!text) {
        textarea.focus();
        showToast('Спершу введіть ваші вподобання для покращення.');
        return;
      }
      if (!window.currentFeedId) return;

      improveBtn.classList.add('spin');
      improveBtn.disabled = true;
      updateProcessingControls();

      try {
        const response = await fetch(`/api/feeds/${window.currentFeedId}/preferences/improve`, {
          method: 'POST',
          headers: requestHeaders(true),
          body: JSON.stringify({ content: text, ...currentOptions() })
        });
        if (!response.ok) throw new Error('Не вдалося покращити вподобання');
        const data = await response.json();
        if (!data.content) throw new Error('Порожня відповідь');

        textarea.value = data.content;
        const newVersion = await persist(data.content.trim());
        versions.push(newVersion);
        cursor = versions.length - 1;
        updateUI();
        showToast('Вподобання покращено ✓');
      } catch (err) {
        showToast('Не вдалося покращити вподобання. Спробуйте пізніше.');
      } finally {
        improveBtn.classList.remove('spin');
        improveBtn.disabled = false;
        updateProcessingControls();
      }
    });
  }
})();

