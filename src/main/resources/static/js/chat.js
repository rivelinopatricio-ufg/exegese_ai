/*
 * Exegese AI — chat page (index.html): two-step question flow, SSE answer streaming,
 * citation chips and the session rename/delete/citation modals.
 *
 * Server data comes from the markup instead of inline scripts (CSP without 'unsafe-inline'):
 *   meta[name="_csrf"] / meta[name="_csrf_header"]  CSRF token for the POST of the question;
 *   #chat-form[data-messages-url]                   POST endpoint that returns {"streamUrl": ...};
 *   #chat-i18n[data-*]                              localized strings used by the generated DOM.
 * Untrusted content (questions, answers, citations) is always written with textContent.
 */
(function () {
    'use strict';

    var form = document.getElementById('chat-form');
    var input = document.getElementById('question-input');
    var container = document.getElementById('messages-container');
    var sessionIdInput = document.getElementById('session-id');
    var emptyState = document.getElementById('empty-state');
    var i18nElement = document.getElementById('chat-i18n');
    var i18n = i18nElement ? i18nElement.dataset : {};

    var csrfTokenMeta = document.querySelector('meta[name="_csrf"]');
    var csrfHeaderMeta = document.querySelector('meta[name="_csrf_header"]');

    function text(key, fallback) {
        var value = i18n[key];
        return (typeof value === 'string' && value) ? value : fallback;
    }

    // ------------------------------------------------------------------ chat flow

    if (form && input && container && sessionIdInput) {
        var chatMessagesUrl = form.dataset.messagesUrl;
        var sessionId = sessionIdInput.value;

        form.addEventListener('submit', function (e) {
            e.preventDefault();
            var question = input.value.trim();
            if (!question) {
                return;
            }

            if (emptyState) {
                emptyState.classList.add('hidden');
            }

            // Selected subjects
            var checkedBoxes = document.querySelectorAll('.subject-checkbox:checked');
            var subjectIds = Array.prototype.map.call(checkedBoxes, function (cb) { return cb.value; });

            // 1. User bubble
            appendMessage('USER', question);
            input.value = '';

            // 2. Assistant bubble with a blinking cursor
            var assistantBubble = appendMessage('ASSISTANT', '');
            var contentSpan = assistantBubble.querySelector('.message-content');
            var citationsContainer = assistantBubble.querySelector('.citations-container');

            var cursor = document.createElement('span');
            cursor.className = 'cursor-blink';
            contentSpan.appendChild(cursor);

            // 3. Step 1: register the question with a CSRF-protected POST and receive the stream URL
            var body = new URLSearchParams();
            body.append('sessionId', sessionId);
            body.append('question', question);
            subjectIds.forEach(function (id) { body.append('subjectIds', id); });

            var headers = { 'Accept': 'application/json' };
            if (csrfTokenMeta && csrfHeaderMeta && csrfTokenMeta.content) {
                headers[csrfHeaderMeta.content] = csrfTokenMeta.content;
            }

            fetch(chatMessagesUrl, {
                method: 'POST',
                credentials: 'same-origin',
                headers: headers,
                body: body
            })
                .then(function (response) {
                    if (!response.ok) {
                        return resolveRequestError(response).then(function (message) {
                            showStreamError(contentSpan, cursor, message);
                        });
                    }
                    return response.json().then(function (data) {
                        // 4. Step 2: open the SSE stream with the single-use ticket
                        openAnswerStream(data.streamUrl, contentSpan, cursor, citationsContainer);
                    });
                })
                .catch(function () {
                    showStreamError(contentSpan, cursor, text('errorRequestFailed', 'Request failed.'));
                });
        });
    }

    function resolveRequestError(response) {
        if (response.status === 401 || response.status === 403) {
            return Promise.resolve(text('errorSessionExpired', 'Session expired.'));
        }
        var fallback = response.status === 429
            ? text('errorRateLimited', 'Too many requests.')
            : text('errorRequestFailed', 'Request failed.');
        return response.json()
            .then(function (data) {
                return (data && typeof data.error === 'string' && data.error) ? data.error : fallback;
            })
            .catch(function () {
                return fallback;
            });
    }

    function showStreamError(contentSpan, cursor, message) {
        cursor.remove();
        var errorLine = document.createElement('span');
        errorLine.className = 'block text-red-600 dark:text-red-400';
        errorLine.textContent = message;
        contentSpan.appendChild(errorLine);
        container.scrollTop = container.scrollHeight;
    }

    function openAnswerStream(streamUrl, contentSpan, cursor, citationsContainer) {
        var eventSource = new EventSource(streamUrl);
        var finished = false;

        eventSource.addEventListener('token', function (event) {
            cursor.remove();
            var token = event.data;
            try {
                token = JSON.parse(event.data);
            } catch (_) {
                // Plain-text token
            }
            contentSpan.appendChild(document.createTextNode(token));
            contentSpan.appendChild(cursor);
            container.scrollTop = container.scrollHeight;
        });

        eventSource.addEventListener('citation', function (event) {
            try {
                var citations = JSON.parse(event.data);
                if (citations && citations.length > 0) {
                    citationsContainer.classList.remove('hidden');
                    citations.forEach(function (cit) {
                        var chip = document.createElement('button');
                        chip.type = 'button';
                        chip.className = 'citation-chip';
                        chip.textContent = '📜 ' + (cit.chunkTitle || cit.documentTitle);
                        chip.addEventListener('click', function () { openCitationModal(cit); });
                        citationsContainer.appendChild(chip);
                    });
                }
            } catch (err) {
                console.error('Erro ao interpretar citações:', err);
            }
        });

        eventSource.addEventListener('complete', function () {
            finished = true;
            cursor.remove();
            eventSource.close();
        });

        // Receives both the server "error" event (generic message + reference) and connection failures
        eventSource.addEventListener('error', function (event) {
            eventSource.close();
            if (finished) {
                return;
            }
            finished = true;
            var message = text('errorConnectionLost', 'Connection lost.');
            if (event && typeof event.data === 'string' && event.data) {
                try {
                    var payload = JSON.parse(event.data);
                    if (payload && typeof payload.message === 'string') {
                        message = payload.message;
                    }
                } catch (_) {
                    // Keep the generic message
                }
            }
            showStreamError(contentSpan, cursor, message);
        });
    }

    function appendMessage(role, content) {
        var wrapper = document.createElement('div');
        wrapper.className = 'flex ' + (role === 'USER' ? 'justify-end' : 'justify-start') + ' gap-3';

        var box = document.createElement('div');
        box.className = 'rounded-2xl p-4 max-w-2xl text-sm shadow-md transition-colors ' + (
            role === 'USER'
                ? 'bg-sky-100 border border-sky-300 text-sky-950 dark:bg-sky-950/80 dark:border-sky-800 dark:text-sky-100'
                : 'bg-white border border-slate-200 text-slate-800 dark:bg-slate-800 dark:border-slate-700 dark:text-slate-100');

        var header = document.createElement('div');
        header.className = 'font-semibold text-xs mb-1 opacity-70';
        header.textContent = role === 'USER' ? text('roleUser', 'User') : text('roleAssistant', 'Exegese AI');
        box.appendChild(header);

        var body = document.createElement('div');
        body.className = 'message-content whitespace-pre-wrap leading-relaxed';
        body.textContent = content;
        box.appendChild(body);

        if (role === 'ASSISTANT') {
            var citBox = document.createElement('div');
            citBox.className = 'citations-container hidden mt-3 pt-3 border-t border-slate-200 dark:border-slate-700/60 flex flex-wrap gap-2';
            var citHeader = document.createElement('span');
            citHeader.className = 'text-[10px] uppercase font-bold text-slate-500 dark:text-slate-400 w-full';
            citHeader.textContent = text('officialSources', 'Sources');
            citBox.appendChild(citHeader);
            box.appendChild(citBox);
        }

        wrapper.appendChild(box);
        container.appendChild(wrapper);
        container.scrollTop = container.scrollHeight;
        return box;
    }

    // ------------------------------------------------------------------ modals

    function show(id) {
        var el = document.getElementById(id);
        if (el) {
            el.classList.remove('hidden');
        }
    }

    function hide(id) {
        var el = document.getElementById(id);
        if (el) {
            el.classList.add('hidden');
        }
    }

    function setText(id, value) {
        var el = document.getElementById(id);
        if (el) {
            el.textContent = value;
        }
    }

    function openCitationModal(cit) {
        setText('modal-title', cit.chunkTitle || text('canonicalSource', 'Source'));
        setText('modal-doc', cit.documentTitle || text('unspecified', '-'));
        setText('modal-page', cit.pageNumber ? (text('page', 'Page') + ' ' + cit.pageNumber) : 'N/A');
        setText('modal-ref', cit.questionNumber
            ? (text('question', 'Question') + ' ' + cit.questionNumber)
            : (cit.articleNumber || 'Seção'));
        setText('modal-details', cit.legalBasis || text('defaultLegalBasis', ''));
        show('citation-modal');
    }

    function openCitationModalFromEl(btn) {
        var d = btn.dataset;
        openCitationModal({
            chunkTitle: d.title || null,
            documentTitle: d.doc || null,
            pageNumber: d.page || null,
            questionNumber: d.question || null,
            articleNumber: d.article || null,
            legalBasis: d.legal || null
        });
    }

    function openRenameModal(btn) {
        var renameForm = document.getElementById('rename-form');
        var renameInput = document.getElementById('rename-input');
        if (!renameForm || !renameInput) {
            return;
        }
        if (btn.dataset.renameUrl) {
            renameForm.action = btn.dataset.renameUrl;
        }
        renameInput.value = btn.dataset.sessionTitle || '';
        show('rename-modal');
        setTimeout(function () { renameInput.focus(); }, 50);
    }

    function confirmDeleteSession(btn) {
        var deleteForm = document.getElementById('delete-form');
        if (!deleteForm) {
            return;
        }
        if (btn.dataset.deleteUrl) {
            deleteForm.action = btn.dataset.deleteUrl;
        }
        show('delete-modal');
    }

    var actions = {
        'open-citation': openCitationModalFromEl,
        'close-citation-modal': function () { hide('citation-modal'); },
        'rename-session': openRenameModal,
        'close-rename-modal': function () { hide('rename-modal'); },
        'delete-session': confirmDeleteSession,
        'close-delete-modal': function () { hide('delete-modal'); }
    };

    document.addEventListener('click', function (e) {
        var target = e.target instanceof Element ? e.target.closest('[data-action]') : null;
        if (!target) {
            return;
        }
        var handler = actions[target.dataset.action];
        if (handler) {
            handler(target);
        }
    });

    window.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') {
            hide('citation-modal');
            hide('rename-modal');
            hide('delete-modal');
        }
    });
})();
