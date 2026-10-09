/*
 * Exegese AI — behaviour shared by every page (theme toggle and dismissible alerts).
 *
 * Elements opt in through data-action attributes instead of inline event handlers, which keeps the
 * Content-Security-Policy free of 'unsafe-inline':
 *   data-action="toggle-theme"   toggles the dark class and stores the choice in localStorage.
 *                                Optional children [data-theme-icon] / [data-theme-text] and the
 *                                attributes data-label-light / data-label-dark describe the next mode.
 *   data-action="dismiss-alert"  removes the closest [data-alert] ancestor (or the parent element).
 */
(function () {
    'use strict';

    function storeTheme(isDark) {
        try {
            window.localStorage.setItem('exegese_theme', isDark ? 'dark' : 'light');
        } catch (_) {
            // Storage unavailable: the choice only lasts for the current page
        }
    }

    function updateThemeControls(button, isDark) {
        var icon = button.querySelector('[data-theme-icon]');
        var text = button.querySelector('[data-theme-text]');
        if (icon) {
            icon.textContent = isDark ? '☀️' : '🌙';
        }
        if (text) {
            var label = isDark ? button.dataset.labelLight : button.dataset.labelDark;
            if (label) {
                text.textContent = label;
            }
        }
    }

    function init() {
        var isDark = document.documentElement.classList.contains('dark');
        document.querySelectorAll('[data-action="toggle-theme"]').forEach(function (button) {
            updateThemeControls(button, isDark);
            button.addEventListener('click', function () {
                var nowDark = document.documentElement.classList.toggle('dark');
                storeTheme(nowDark);
                document.querySelectorAll('[data-action="toggle-theme"]').forEach(function (other) {
                    updateThemeControls(other, nowDark);
                });
            });
        });

        document.querySelectorAll('[data-action="dismiss-alert"]').forEach(function (button) {
            button.addEventListener('click', function () {
                var alert = button.closest('[data-alert]') || button.parentElement;
                if (alert) {
                    alert.remove();
                }
            });
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
