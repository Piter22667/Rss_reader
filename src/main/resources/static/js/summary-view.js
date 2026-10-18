// Reading saved summaries and changing the view never call Bright Data or OpenRouter.
(function initSummaryView() {
  const articlesPanel = document.getElementById('articlesReader');
  const summariesPanel = document.getElementById('summariesReader');
  if (!articlesPanel || !summariesPanel || !window.currentFeedId) return;

  const articlesTab = document.getElementById('articlesTab');
  const summariesTab = document.getElementById('summariesTab');
  const list = document.getElementById('summaryList');
  const counts = document.getElementById('summaryCounts');
  const pagination = document.getElementById('summaryPagination');
  const previous = document.getElementById('summaryPrevious');
  const next = document.getElementById('summaryNext');
  let preference = null;
  let currentPage = 0;
  let requestNumber = 0;
  let busy = false;
  let processingAllowed = false;
  const modeKey = `rss-view-${window.currentFeedId}`;

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  }

  function show(mode) {
    const ai = mode === 'ai';
    articlesPanel.hidden = ai;
    summariesPanel.hidden = !ai;
    document.getElementById('summaryBatchActions').hidden = !ai;
    articlesTab.classList.toggle('selected', !ai);
    summariesTab.classList.toggle('selected', ai);
    articlesTab.setAttribute('aria-pressed', String(!ai));
    summariesTab.setAttribute('aria-pressed', String(ai));
    try { sessionStorage.setItem(modeKey, mode); } catch (ignored) { }
  }

  function clearBadges() {
    articlesPanel.querySelectorAll('.article-ai-state').forEach(slot => slot.replaceChildren());
  }

  function renderBadges(badges) {
    clearBadges();
    for (const badge of badges) {
      const card = articlesPanel.querySelector(`[data-article-id="${badge.articleId}"]`);
      if (!card) continue;
      const slot = card.querySelector('.article-ai-state');
      if (badge.status === 'OK') {
        const button = element('button', 'ai-badge', 'AI');
        button.type = 'button';
        button.title = 'Відкрити готовий підсумок для вибраної версії вподобань';
        button.setAttribute('aria-label', button.title);
        button.addEventListener('click', () => {
          show('ai');
          loadPage(0, badge.articleId);
        });
        slot.append(button);
      } else if (badge.status === 'HIDDEN') {
        const label = element('span', 'ai-state-label', 'Приховано в AI');
        label.title = badge.hiddenReason || 'Приховано для вибраної версії вподобань';
        slot.append(label);
      } else {
        const button = element('button', 'reader-tab ai-state-error', 'Помилка AI');
        button.type = 'button';
        button.addEventListener('click', () => { show('ai'); loadPage(0); });
        slot.append(button);
      }
    }
  }

  function renderCard(item, focusId) {
    const card = element('article', 'article-card');
    card.dataset.summaryArticleId = item.articleId;
    const header = element('div', 'article-card-header');
    header.append(element('span', 'ai-badge', 'AI'));
    if (item.publishedAtLabel) header.append(element('p', 'article-date', item.publishedAtLabel));
    const text = element('p', 'summary-text', item.text);
    const actions = element('div', 'summary-actions');
    const expand = element('button', '', 'Розгорнути підсумок');
    expand.type = 'button';
    expand.setAttribute('aria-expanded', 'false');
    expand.addEventListener('click', () => {
      const expanded = text.classList.toggle('expanded');
      expand.textContent = expanded ? 'Згорнути підсумок' : 'Розгорнути підсумок';
      expand.setAttribute('aria-expanded', String(expanded));
    });
    actions.append(expand);
    if (item.link) {
      const link = element('a', 'article-original', 'Читати оригінал ↗');
      link.href = item.link;
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
      actions.append(link);
    }
    card.append(header, element('h3', 'article-title', item.title), text, actions);
    if (item.articleId === focusId) {
      text.classList.add('expanded');
      expand.textContent = 'Згорнути підсумок';
      expand.setAttribute('aria-expanded', 'true');
      card.classList.add('summary-highlight');
    }
    list.append(card);
    return card;
  }

  function renderFailures(failures) {
    if (!failures.length) return;
    const details = element('details', 'summary-errors');
    details.open = true;
    details.append(element('summary', '', `Помилки обробки: ${failures.length}`));
    for (const item of failures) {
      const row = element('div', 'summary-error-row');
      const retry = element('button', '', 'Повторити обробку');
      retry.type = 'button';
      retry.disabled = busy || !processingAllowed;
      retry.addEventListener('click', () => {
        if (window.summaryView.retryArticle) window.summaryView.retryArticle(item.articleId);
      });
      row.append(element('p', '', item.title), retry);
      details.append(row);
    }
    list.append(details);
  }

  async function loadPage(page, focusId = null) {
    const request = ++requestNumber;
    list.replaceChildren();
    pagination.hidden = true;
    if (!preference) {
      counts.textContent = '';
      list.append(element('p', 'summary-empty', 'Спочатку збережіть вподобання. Підсумки з’являться після окремого запуску обробки.'));
      clearBadges();
      return;
    }
    counts.textContent = `Завантаження результатів версії ${preference.versionNumber}…`;
    const ids = Array.from(articlesPanel.querySelectorAll('[data-article-id]')).map(card => card.dataset.articleId);
    let url = `/api/feeds/${window.currentFeedId}/summaries?preferenceId=${preference.id}&page=${page}`;
    if (ids.length) url += `&articleIds=${ids.join(',')}`;
    if (focusId != null) url += `&focusArticleId=${focusId}`;
    try {
      const response = await fetch(url);
      const data = await response.json();
      if (request !== requestNumber) return;
      if (!response.ok) throw new Error(data.error || 'Не вдалося завантажити підсумки.');
      currentPage = data.page;
      counts.textContent = `Версія ${data.preferenceVersion} · Готово: ${data.ready} · Приховано: ${data.hidden} · Помилок: ${data.failed}`;
      renderBadges(data.badges);
      renderFailures(data.failures);
      let focused = null;
      for (const item of data.items) {
        const card = renderCard(item, focusId);
        if (item.articleId === focusId) focused = card;
      }
      if (!data.items.length) list.append(element('p', 'summary-empty', 'Готових підсумків для цієї версії ще немає. Приховані статті тут не показуються.'));
      pagination.hidden = data.totalPages <= 1;
      previous.hidden = data.page === 0;
      next.hidden = data.page + 1 >= data.totalPages;
      document.getElementById('summaryPageLabel').textContent = `Сторінка ${data.page + 1} з ${data.totalPages}`;
      if (focused && !summariesPanel.hidden) {
        focused.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
        setTimeout(() => focused.classList.remove('summary-highlight'), 2000);
      }
    } catch (error) {
      if (request !== requestNumber) return;
      counts.textContent = error.message;
      clearBadges();
    }
  }

  window.summaryView = {
    changeVersion(version) {
      preference = version;
      clearBadges();
      return loadPage(0);
    },
    show() { show('ai'); },
    refresh(focusId = null) { return loadPage(0, focusId); },
    async refreshCurrent() {
      const preferenceId = preference ? preference.id : null;
      const page = currentPage;
      const scroll = list.scrollTop;
      const expanded = Array.from(list.querySelectorAll('.summary-text.expanded'))
        .map(text => text.closest('[data-summary-article-id]').dataset.summaryArticleId);
      await loadPage(page);
      if (!preference || preference.id !== preferenceId || currentPage !== page) return;
      for (const id of expanded) {
        const card = list.querySelector(`[data-summary-article-id="${id}"]`);
        if (!card) continue;
        card.querySelector('.summary-text').classList.add('expanded');
        const button = card.querySelector('.summary-actions button');
        button.textContent = 'Згорнути підсумок';
        button.setAttribute('aria-expanded', 'true');
      }
      list.scrollTop = scroll;
    },
    setBusy(value) {
      busy = value;
      list.querySelectorAll('.summary-error-row button').forEach(button => { button.disabled = busy || !processingAllowed; });
    },
    setProcessingAllowed(value) {
      processingAllowed = value;
      list.querySelectorAll('.summary-error-row button').forEach(button => { button.disabled = busy || !processingAllowed; });
    },
    retryArticle: null
  };
  articlesTab.addEventListener('click', () => show('articles'));
  summariesTab.addEventListener('click', () => show('ai'));
  previous.addEventListener('click', () => loadPage(Math.max(0, currentPage - 1)));
  next.addEventListener('click', () => loadPage(currentPage + 1));
  let mode = 'articles';
  try { mode = sessionStorage.getItem(modeKey) || 'articles'; } catch (ignored) { }
  show(mode);
})();
