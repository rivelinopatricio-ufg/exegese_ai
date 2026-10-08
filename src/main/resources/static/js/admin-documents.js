/*
 * Exegese AI — admin document catalog page (admin/documents.html).
 *
 * Shows the "please wait" modal and disables the submit button while a PDF upload is being sent.
 * The form submission itself is not intercepted: the browser posts the multipart form normally.
 */
(function () {
    'use strict';

    function init() {
        var form = document.getElementById('uploadForm');
        if (!form) {
            return;
        }
        form.addEventListener('submit', function () {
            var fileInput = form.querySelector('input[name="file"]');
            if (!fileInput || !fileInput.files || fileInput.files.length === 0) {
                return;
            }
            if (!form.checkValidity()) {
                return;
            }

            var modal = document.getElementById('uploadLoadingModal');
            if (modal) {
                modal.classList.remove('hidden');
                modal.classList.add('flex');
            }

            var submitBtn = document.getElementById('uploadSubmitBtn');
            if (submitBtn) {
                submitBtn.disabled = true;
                submitBtn.classList.add('opacity-60', 'cursor-not-allowed');
            }
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
