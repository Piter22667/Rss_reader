// Starting a batch is an explicit POST. Polling its status only reads progress.
(function initSummaryBatch() {
  const button = document.getElementById('summaryBatchBtn');
  const stopButton = document.getElementById('summaryBatchStop');
  const status = document.getElementById('summaryBatchProgress');
  const details = document.getElementById('summaryBatchDetails');
  const message = document.getElementById('summaryBatchMessage');
  if (!button || !status || !window.currentFeedId) return;

  let version = null;
  let progress = null;
  let running = false;
  let starting = false;
  let loading = false;
  let allowed = false;
  let timer = null;
  let generation = 0;
  let lastProgress = '';
  let requestingStop = false;

  function updateButton() {
    button.disabled = !version || !allowed || running || starting || loading || !progress || progress.remaining === 0;
    if (running || starting) button.textContent = 'Обробка…';
    else if (progress && progress.remaining === 0) button.textContent = 'Усі статті оброблено';
    else button.textContent = progress && progress.hasResults ? 'Обробити наступні 20' : 'Обробити перші 20';
    stopButton.hidden = !running;
    stopButton.disabled = requestingStop || !!(progress && progress.stopRequested);
    stopButton.textContent = stopButton.disabled ? 'Зупиняємо…' : 'Зупинити';
  }

  function notify() {
    updateButton();
    if (window.summaryBatch.onStateChange) window.summaryBatch.onStateChange();
  }

  function render(data) {
    // An earlier poll must not undo a stop request already confirmed by the server.
    if (progress && progress.running && progress.stopRequested && data.running
        && progress.preferenceId === data.preferenceId) {
      data.stopRequested = true;
      data.message = progress.message;
    }
    progress = data;
    running = data.running;
    status.classList.toggle('summary-preview-loading', running);
    status.setAttribute('aria-busy', String(running));
    const stopped = data.stopRequested && data.processed < data.total;
    const state = data.running ? (data.stopRequested ? 'Зупиняється' : 'Обробляється')
      : (data.total === 0 ? 'Не запущена' : (stopped ? 'Зупинена' : 'Завершена'));
    details.hidden = false;
    document.getElementById('batchSelectedVersion').textContent = `Версія ${version.versionNumber}`;
    document.getElementById('batchState').textContent = `${state} · версія ${data.preferenceVersion}`;
    document.getElementById('batchCompleted').textContent = data.total ? `${data.processed} із ${data.total} статей` : '—';
    document.getElementById('batchActive').textContent = `${data.active} · одночасно до ${data.parallelism}`;
    document.getElementById('batchResults').textContent = `Готово: ${data.ok} · приховано: ${data.hidden} · помилок: ${data.failed}`;
    document.getElementById('batchRemaining').textContent = `${data.remaining} для вибраної версії (включно з поточними)`;
    if (data.running && data.stopRequested) {
      message.textContent = 'Нові статті не запускаються. Чекаємо завершення вже розпочатих; їхні результати буде збережено.';
    } else if (data.running) {
      message.textContent = 'Отримуємо відсутні повні тексти та створюємо підсумки. Готові результати з’являються поступово.';
    } else {
      message.textContent = '';
    }
    status.title = `${state}. Завершено ${data.processed} із ${data.total}; активних: ${data.active}.`;
    if (data.running) {
      status.textContent = `${data.stopRequested ? 'Зупиняємо' : 'Обробка'} · ${data.processed}/${data.total}`;
    } else if (data.total > 0) {
      const stopped = data.stopRequested && data.processed < data.total;
      status.textContent = `${stopped ? 'Зупинено' : 'Завершено'} · ${data.processed}/${data.total}`;
    } else {
      status.textContent = `Очікують обробки: ${data.remaining}`;
    }
    if (data.preferenceId !== version.id) status.textContent += ` (версія ${data.preferenceVersion})`;

    const key = `${data.preferenceId}:${data.processed}:${data.running}:${data.stopRequested}`;
    if (key !== lastProgress && data.preferenceId === version.id && window.summaryView) {
      window.summaryView.refreshCurrent();
    }
    lastProgress = key;
  }

  function schedule(delay) {
    if (timer) clearTimeout(timer);
    timer = setTimeout(readStatus, delay);
  }

  function showError(message) {
    status.textContent = 'Помилка — див. деталі';
    status.title = message;
    document.getElementById('summaryBatchMessage').textContent = message;
  }

  async function readStatus() {
    const request = generation;
    if (!version) return;
    loading = true;
    updateButton();
    try {
      const response = await fetch(`/api/feeds/${window.currentFeedId}/summary-batch?preferenceId=${version.id}`);
      const data = await response.json();
      if (request !== generation) return;
      if (!response.ok || typeof data.running !== 'boolean') throw new Error(data.error || 'Не вдалося прочитати прогрес. Перевірте вхід у застосунок.');
      render(data);
      if (data.running) schedule(2000);
    } catch (error) {
      if (request !== generation) return;
      showError(`${error.message} Повторюємо перевірку прогресу…`);
      schedule(5000);
    } finally {
      if (request === generation) {
        loading = false;
        notify();
      }
    }
  }

  button.addEventListener('click', async () => {
    if (button.disabled || !version) return;
    const request = generation;
    const selectedId = version.id;
    starting = true;
    if (timer) clearTimeout(timer);
    notify();
    if (window.summaryView) window.summaryView.show();
    const headers = {};
    const token = document.querySelector('meta[name="_csrf"]');
    const header = document.querySelector('meta[name="_csrf_header"]');
    if (token && header) headers[header.content] = token.content;
    try {
      const response = await fetch(`/api/feeds/${window.currentFeedId}/summary-batch?preferenceId=${selectedId}`, {
        method: 'POST', headers
      });
      const data = await response.json();
      if (request !== generation) return;
      if (!response.ok || typeof data.running !== 'boolean') throw new Error(data.error || 'Не вдалося запустити порцію. Перевірте вхід у застосунок.');
      render(data);
      if (data.running) schedule(2000);
    } catch (error) {
      if (request === generation) {
        showError(error.message);
        // The server may have started work even if the response was lost.
        schedule(2000);
      }
    } finally {
      starting = false;
      notify();
      if (request !== generation) readStatus();
    }
  });

  stopButton.addEventListener('click', async () => {
    if (stopButton.disabled || !running || !version) return;
    const request = generation;
    const selectedId = version.id;
    requestingStop = true;
    updateButton();
    const headers = {};
    const token = document.querySelector('meta[name="_csrf"]');
    const header = document.querySelector('meta[name="_csrf_header"]');
    if (token && header) headers[header.content] = token.content;
    try {
      const response = await fetch(`/api/feeds/${window.currentFeedId}/summary-batch/stop?preferenceId=${selectedId}`, {
        method: 'POST', headers
      });
      const data = await response.json();
      if (request !== generation) return;
      if (!response.ok || typeof data.running !== 'boolean') throw new Error(data.error || 'Не вдалося зупинити порцію.');
      render(data);
      if (data.running) schedule(2000);
    } catch (error) {
      if (request === generation) {
        showError(`${error.message} Перевіряємо фактичний стан обробки…`);
        schedule(2000);
      }
    } finally {
      requestingStop = false;
      notify();
      if (request !== generation) readStatus();
    }
  });

  window.summaryBatch = {
    changeVersion(selected) {
      generation++;
      if (timer) clearTimeout(timer);
      version = selected;
      progress = null;
      lastProgress = '';
      loading = false;
      if (!version) {
        running = false;
        status.textContent = '';
        details.hidden = true;
        message.textContent = 'Спочатку збережіть вподобання.';
        status.classList.remove('summary-preview-loading');
        notify();
        return;
      }
      readStatus();
    },
    isRunning() { return running || starting; },
    setProcessingAllowed(value) { allowed = value; updateButton(); },
    onStateChange: null
  };
})();
