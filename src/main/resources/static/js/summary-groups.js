// Topic overviews are generated from already saved per-article AI summaries.
(function initSummaryGroups() {
  const panel = document.getElementById('summaryGroupsReader');
  const button = document.getElementById('summaryGroupBtn');
  const stopButton = document.getElementById('summaryGroupStop');
  const status = document.getElementById('summaryGroupProgress');
  const counts = document.getElementById('summaryGroupCounts');
  const list = document.getElementById('summaryGroupList');
  const notice = document.getElementById('summaryGroupNotice');
  const details = document.getElementById('summaryGroupDetails');
  const message = document.getElementById('summaryGroupMessage');
  const groupsTab = document.getElementById('groupsTab');
  if (!panel || !button || !status || !list || !window.currentFeedId) return;

  let version = null;
  let progress = null;
  let results = null;
  let running = false;
  let starting = false;
  let loading = false;
  let allowed = false;
  let timer = null;
  let generation = 0;
  let lastProgressKey = '';

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  }

  function csrfHeaders() {
    const headers = {};
    const token = document.querySelector('meta[name="_csrf"]');
    const header = document.querySelector('meta[name="_csrf_header"]');
    if (token && header) headers[header.content] = token.content;
    return headers;
  }

  function updateButton() {
    const ready = results ? results.readySummaries : (progress ? progress.readySummaries : 0);
    const hasGroups = results ? results.groups.length > 0 : !!(progress && progress.hasGroups);
    button.disabled = !version || !allowed || running || starting || loading || ready < 2;
    if (running || starting) button.textContent = 'Створюємо…';
    else if (hasGroups) button.textContent = 'Оновити огляди';
    else button.textContent = 'Створити огляди';
    stopButton.hidden = !running;
    stopButton.disabled = !running || !!(progress && progress.stopRequested);
    stopButton.textContent = stopButton.disabled ? 'Зупиняємо…' : 'Зупинити';
  }

  function notify() {
    updateButton();
    if (window.summaryGroups && window.summaryGroups.onStateChange) {
      window.summaryGroups.onStateChange();
    }
  }

  function clearList() {
    list.replaceChildren();
  }

  function renderResults(data) {
    results = data;
    clearList();
    counts.textContent = `Версія ${data.preferenceVersion} · Оглядів: ${data.totalGroups}`
      + ` · Статей в оглядах: ${data.groupedArticles} · Готових підсумків: ${data.readySummaries}`;
    if (!data.groups.length) {
      const text = data.readySummaries < 2
        ? 'Спочатку обробіть хоча б дві статті у вкладці «AI-підсумки».'
        : 'Тематичних оглядів ще немає. Натисніть «Створити огляди».';
      list.append(element('p', 'summary-empty', text));
    } else {
      data.groups.forEach(renderGroup);
    }
    notice.hidden = true;
    notice.textContent = '';
  }

  function renderGroup(item) {
    const card = element('article', 'article-card summary-group-card');
    const header = element('div', 'summary-group-header');
    header.append(element('span', 'summary-group-category', item.category));
    if (item.dateRangeLabel) header.append(element('span', 'article-date', item.dateRangeLabel));
    card.append(header);
    card.append(element('h3', 'article-title', item.title));

    const text = element('p', 'summary-text', item.text);
    const actions = element('div', 'summary-actions');
    const expand = element('button', '', 'Розгорнути огляд');
    expand.type = 'button';
    expand.setAttribute('aria-expanded', 'false');
    expand.addEventListener('click', () => {
      const expanded = text.classList.toggle('expanded');
      expand.textContent = expanded ? 'Згорнути огляд' : 'Розгорнути огляд';
      expand.setAttribute('aria-expanded', String(expanded));
    });
    actions.append(expand);
    actions.append(element('span', 'summary-group-meta', `Статей: ${item.articleCount}`));
    card.append(text, actions);

    if (item.articles && item.articles.length) {
      const articleDetails = element('details', 'summary-group-articles');
      articleDetails.append(element('summary', '', `Статті в огляді (${item.articles.length})`));
      for (const article of item.articles) {
        const row = element('div', 'summary-group-article');
        if (article.publishedAtLabel) {
          row.append(element('span', 'article-date', article.publishedAtLabel));
        }
        if (article.link) {
          const link = element('a', 'article-original', article.title);
          link.href = article.link;
          link.target = '_blank';
          link.rel = 'noopener noreferrer';
          row.append(link);
        } else {
          row.append(element('span', 'summary-group-article-title', article.title));
        }
        articleDetails.append(row);
      }
      card.append(articleDetails);
    }
    list.append(card);
  }

  function phaseLabel(phase) {
    switch (phase) {
      case 'clustering': return 'Визначення категорій';
      case 'summaries': return 'Створення оглядів';
      case 'saving': return 'Збереження';
      case 'done': return 'Завершено';
      case 'stopped': return 'Зупинено';
      case 'error': return 'Помилка';
      default: return 'Очікування';
    }
  }

  function groupWord(count) {
    const mod10 = count % 10;
    const mod100 = count % 100;
    if (mod10 === 1 && mod100 !== 11) return 'огляд';
    if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return 'огляди';
    return 'оглядів';
  }

  function renderProgress(data) {
    progress = data;
    running = data.running;
    status.classList.toggle('summary-preview-loading', running);
    status.setAttribute('aria-busy', String(running));

    if (data.running) {
      status.textContent = `${phaseLabel(data.phase)} · ${data.completedGroups}/${data.totalGroups || '?'}`;
    } else if (data.phase === 'done') {
      status.textContent = `Завершено · ${data.completedGroups} ${groupWord(data.completedGroups)}`;
    } else if (data.phase === 'stopped') {
      status.textContent = 'Зупинено';
    } else if (data.phase === 'error') {
      status.textContent = 'Помилка';
    } else {
      status.textContent = data.hasGroups ? 'Огляди готові' : '';
    }

    details.hidden = false;
    document.getElementById('groupSelectedVersion').textContent = `Версія ${version.versionNumber}`;
    document.getElementById('groupPhase').textContent = phaseLabel(data.phase);
    document.getElementById('groupTotalArticles').textContent = data.totalArticles ? String(data.totalArticles) : '—';
    document.getElementById('groupClassified').textContent = data.classifiedArticles ? String(data.classifiedArticles) : '—';
    document.getElementById('groupCount').textContent = data.totalGroups ? String(data.totalGroups) : '—';
    document.getElementById('groupCompleted').textContent = data.totalGroups
      ? `${data.completedGroups} із ${data.totalGroups}` : '—';
    document.getElementById('groupFailed').textContent = String(data.failedGroups || 0);
    message.textContent = data.message || '';

    const key = `${data.preferenceId}:${data.phase}:${data.completedGroups}:${data.running}:${data.stopRequested}`;
    if (key !== lastProgressKey) {
      lastProgressKey = key;
      if (!data.running && data.preferenceId === version.id) loadResults();
    }
  }

  function showError(text) {
    status.textContent = 'Помилка — див. деталі';
    message.textContent = text;
  }

  async function loadResults() {
    if (!version) return;
    const request = generation;
    try {
      const response = await fetch(`/api/feeds/${window.currentFeedId}/summary-groups?preferenceId=${version.id}`);
      const data = await response.json();
      if (request !== generation) return;
      if (!response.ok) throw new Error(data.error || 'Не вдалося завантажити тематичні огляди.');
      renderResults(data);
    } catch (error) {
      if (request !== generation) return;
      clearList();
      list.append(element('p', 'summary-empty', error.message));
    } finally {
      if (request === generation) notify();
    }
  }

  function schedule(delay) {
    if (timer) clearTimeout(timer);
    timer = setTimeout(readStatus, delay);
  }

  async function readStatus() {
    if (!version) return;
    const request = generation;
    loading = true;
    updateButton();
    try {
      const response = await fetch(`/api/feeds/${window.currentFeedId}/summary-groups/status?preferenceId=${version.id}`);
      const data = await response.json();
      if (request !== generation) return;
      if (!response.ok || typeof data.running !== 'boolean') {
        throw new Error(data.error || 'Не вдалося прочитати стан тематичних оглядів.');
      }
      renderProgress(data);
      if (data.running) schedule(2000);
    } catch (error) {
      if (request !== generation) return;
      showError(`${error.message} Повторюємо перевірку…`);
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
    starting = true;
    if (timer) clearTimeout(timer);
    notify();
    if (window.summaryView && window.summaryView.showGroups) window.summaryView.showGroups();
    try {
      const response = await fetch(`/api/feeds/${window.currentFeedId}/summary-groups?preferenceId=${version.id}`, {
        method: 'POST', headers: csrfHeaders()
      });
      const data = await response.json();
      if (request !== generation) return;
      if (!response.ok || typeof data.running !== 'boolean') {
        throw new Error(data.error || 'Не вдалося запустити створення оглядів.');
      }
      renderProgress(data);
      if (data.running) schedule(2000);
    } catch (error) {
      if (request === generation) {
        showError(error.message);
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
    stopButton.disabled = true;
    try {
      const response = await fetch(`/api/feeds/${window.currentFeedId}/summary-groups/stop?preferenceId=${version.id}`, {
        method: 'POST', headers: csrfHeaders()
      });
      const data = await response.json();
      if (request !== generation) return;
      if (!response.ok || typeof data.running !== 'boolean') {
        throw new Error(data.error || 'Не вдалося зупинити створення оглядів.');
      }
      renderProgress(data);
      if (data.running) schedule(2000);
    } catch (error) {
      if (request === generation) {
        showError(`${error.message} Перевіряємо фактичний стан…`);
        schedule(2000);
      }
    } finally {
      if (request === generation) notify();
    }
  });

  window.summaryGroups = {
    changeVersion(selected) {
      generation++;
      if (timer) clearTimeout(timer);
      version = selected;
      progress = null;
      results = null;
      running = false;
      starting = false;
      loading = false;
      lastProgressKey = '';
      clearList();
      counts.textContent = '';
      notice.hidden = true;
      notice.textContent = '';
      if (!version) {
        status.textContent = '';
        details.hidden = true;
        message.textContent = 'Спочатку збережіть вподобання.';
        notify();
        return;
      }
      details.hidden = true;
      message.textContent = 'Завантажуємо тематичні огляди…';
      readStatus();
    },
    load() {
      if (version) readStatus();
    },
    refresh() {
      return loadResults();
    },
    isRunning() { return running || starting; },
    setProcessingAllowed(value) {
      allowed = value;
      updateButton();
    },
    onStateChange: null
  };

  if (groupsTab) {
    groupsTab.addEventListener('click', () => window.summaryGroups.load());
    if (groupsTab.classList.contains('selected')) window.summaryGroups.load();
  }
})();
