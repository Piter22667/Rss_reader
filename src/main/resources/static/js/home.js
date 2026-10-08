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

  if (!textarea || !olderBtn || !newerBtn) return;

  let versions = Array.isArray(window.initialPreferences) ? [...window.initialPreferences] : [];
  let cursor = versions.length > 0 ? versions.length - 1 : -1;

  function updateUI() {
    if (cursor >= 0 && cursor < versions.length) {
      textarea.value = versions[cursor].content || '';
      hint.textContent = `Версія ${cursor + 1} з ${versions.length}. ← старіша, → новіша. Нове збереження створює нову версію.`;
    } else {
      hint.textContent = 'Додайте вподобання і натисніть «Зберегти».';
    }
    olderBtn.disabled = cursor <= 0;
    newerBtn.disabled = cursor >= versions.length - 1;
  }

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

  if (form && window.currentFeedId) {
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      const content = textarea.value.trim();
      if (!content) {
        alert('Введіть текст вподобань перед збереженням.');
        textarea.focus();
        return;
      }

      saveBtn.disabled = true;
      const originalText = saveBtn.textContent;
      saveBtn.textContent = 'Збереження…';

      try {
        const response = await fetch(`/api/feeds/${window.currentFeedId}/preferences`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({ content })
        });

        if (!response.ok) {
          throw new Error('Помилка сервера');
        }

        const newVersion = await response.json();
        versions.push(newVersion);
        cursor = versions.length - 1;
        updateUI();

        saveBtn.textContent = 'Збережено ✓';
        setTimeout(() => {
          saveBtn.textContent = originalText;
          saveBtn.disabled = false;
        }, 1200);
      } catch (err) {
        // Fallback to standard form submission
        form.submit();
      }
    });
  }

  if (improveBtn) {
    improveBtn.addEventListener('click', () => {
      const text = textarea.value.trim();
      if (!text) {
        alert('Спершу введіть ваші вподобання для структурування та покращення.');
        textarea.focus();
        return;
      }

      improveBtn.classList.add('spin');
      improveBtn.disabled = true;

      setTimeout(() => {
        // Structure the preferences neatly into clear bullet points
        const lines = text.split('\n').map(l => l.trim()).filter(Boolean);
        const structured = [
          '1. Мова матеріалів: Українська (або якісний переклад).',
          '2. Формат: Лаконічний зміст, ключові тези та висновки статті.',
          '3. Пріоритетні теми: ' + (lines.length > 0 ? lines[0] : 'технології, аналітика, важливі новини.'),
          '4. Стиль: Інформативний, нейтральний, без клікбейту.',
          '5. Що ігнорувати: Рекламу, повторювані новини, плітки.'
        ].join('\n');

        textarea.value = structured;
        improveBtn.classList.remove('spin');
        improveBtn.disabled = false;
        textarea.focus();
      }, 350);
    });
  }
})();
