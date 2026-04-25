/**
 * Disables the submit button and shows a spinner while a note-auth form submission
 * is in flight. The forms target the server via a plain browser POST (hx-boost is
 * off for them), so the feedback window is "click submit → browser navigation
 * completes". Keeps the inputs enabled so their values still serialise into the
 * POST body.
 */
{
  const init = (root) => {
    const forms = root.querySelectorAll('form.note-login-form');
    forms.forEach(form => {
      if (form.dataset.noteFormLoadingInit) return;
      form.dataset.noteFormLoadingInit = '1';
      form.addEventListener('submit', () => {
        const button = form.querySelector('button[type="submit"]');
        if (button && !button.disabled) {
          button.disabled = true;
          button.classList.add('is-loading');
        }
      });
    });
  };

  const run = () => init(document);
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', run);
  } else {
    run();
  }
  // The site uses hx-boost on <body>, which replaces the body on navigation.
  // Re-scan after every swap so freshly rendered forms get their listener.
  document.body.addEventListener('htmx:afterSwap', (e) => init(e.target));
}
