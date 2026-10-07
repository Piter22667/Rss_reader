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
