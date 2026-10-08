/*
 * Exegese AI — early theme initialization.
 *
 * Loaded synchronously in <head> (no defer/async) so the saved theme is applied before the first
 * paint, avoiding a flash of the wrong theme (FOUC). Kept as an external file so the
 * Content-Security-Policy does not need 'unsafe-inline' for scripts.
 */
(function () {
    'use strict';
    var saved = null;
    try {
        saved = window.localStorage.getItem('exegese_theme');
    } catch (_) {
        // Storage may be blocked (private mode, disabled cookies); fall back to the dark theme
    }
    if (saved === 'light') {
        document.documentElement.classList.remove('dark');
    } else {
        document.documentElement.classList.add('dark');
    }
})();
