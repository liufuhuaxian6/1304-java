const { createApp, ref, reactive, onMounted, computed, nextTick } = Vue;

const API_BASE = 'http://localhost:8082/api/v1';
const AUTH_STORAGE = window.sessionStorage;
const CODE_LANGUAGE_MAP = {
    c: 'c',
    h: 'c',
    cpp: 'cpp',
    cxx: 'cpp',
    cc: 'cpp',
    hpp: 'cpp',
    java: 'java',
    py: 'python',
    js: 'javascript',
    jsx: 'javascript',
    ts: 'typescript',
    tsx: 'typescript',
    go: 'go',
    rs: 'rust',
    php: 'php',
    rb: 'ruby',
    swift: 'swift',
    kt: 'kotlin',
    kts: 'kotlin',
    scala: 'scala',
    sh: 'bash',
    bash: 'bash',
    ps1: 'powershell',
    sql: 'sql',
    md: 'markdown',
    markdown: 'markdown',
    html: 'xml',
    htm: 'xml',
    xml: 'xml',
    svg: 'xml',
    css: 'css',
    scss: 'scss',
    less: 'less',
    json: 'json',
    yml: 'yaml',
    yaml: 'yaml',
    vue: 'xml',
    txt: 'plaintext'
};

function escapeHtml(text) {
    return text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function detectLanguage(fileName) {
    if (!fileName) {
        return null;
    }

    const normalized = fileName.toLowerCase();
    if (normalized === 'dockerfile') {
        return 'dockerfile';
    }
    if (normalized === 'makefile') {
        return 'makefile';
    }

    const ext = normalized.includes('.') ? normalized.split('.').pop() : '';
    return CODE_LANGUAGE_MAP[ext] || null;
}

function formatLanguageLabel(language) {
    if (!language) {
        return '文本预览';
    }

    const labels = {
        cpp: 'C/C++',
        c: 'C',
        java: 'Java',
        python: 'Python',
        javascript: 'JavaScript',
        typescript: 'TypeScript',
        markdown: 'Markdown',
        xml: 'HTML/XML',
        css: 'CSS',
        scss: 'SCSS',
        less: 'LESS',
        yaml: 'YAML',
        json: 'JSON',
        sql: 'SQL',
        bash: 'Bash',
        powershell: 'PowerShell',
        plaintext: '文本'
    };

    return labels[language] || language.toUpperCase();
}

function highlightCode(content, language) {
    const safeContent = content || '';
    if (!safeContent) {
        return '';
    }

    if (!window.hljs) {
        return escapeHtml(safeContent);
    }

    try {
        if (language && window.hljs.getLanguage(language)) {
            return window.hljs.highlight(safeContent, { language }).value;
        }
        return window.hljs.highlightAuto(safeContent).value;
    } catch (error) {
        return escapeHtml(safeContent);
    }
}

function mapPosition(position, start, end, replacementText) {
    return mapPositionByLength(position, start, end, (replacementText || '').length);
}

function mapPositionByLength(position, start, end, replacementLength) {
    if (position <= start) {
        return position;
    }
    if (position >= end) {
        return position + replacementLength - (end - start);
    }
    return start + Math.min(position - start, replacementLength);
}

function mapRangeByLength(range, start, end, replacementLength) {
    return {
        start: mapPositionByLength(range.start, start, end, replacementLength),
        end: mapPositionByLength(range.end, start, end, replacementLength)
    };
}

function lockServerStart(lock) {
    return Number.isFinite(lock?.serverStart) ? lock.serverStart : lock?.currentStart || 0;
}

function lockServerEnd(lock) {
    return Number.isFinite(lock?.serverEnd) ? lock.serverEnd : lock?.currentEnd || 0;
}

function normalizeClientLock(lock, owner) {
    if (!lock) {
        return null;
    }
    const serverStart = Number.isFinite(lock.serverStart) ? lock.serverStart : lock.currentStart;
    const serverEnd = Number.isFinite(lock.serverEnd) ? lock.serverEnd : lock.currentEnd;
    return {
        ...lock,
        owner: lock.owner || owner,
        serverStart,
        serverEnd,
        currentStart: Number.isFinite(lock.currentStart) ? lock.currentStart : serverStart,
        currentEnd: Number.isFinite(lock.currentEnd) ? lock.currentEnd : serverEnd,
        dirty: Boolean(lock.dirty)
    };
}

function getInputRange(target, inputType, data) {
    const selectionStart = target.selectionStart || 0;
    const selectionEnd = target.selectionEnd || selectionStart;

    if (selectionStart !== selectionEnd) {
        return { start: selectionStart, end: selectionEnd };
    }
    if (inputType === 'deleteContentBackward') {
        return { start: Math.max(0, selectionStart - 1), end: selectionStart };
    }
    if (inputType === 'deleteContentForward') {
        return { start: selectionStart, end: Math.min(target.value.length, selectionStart + 1) };
    }
    if (inputType && inputType.startsWith('delete')) {
        return { start: Math.max(0, selectionStart - 1), end: selectionStart };
    }

    return { start: selectionStart, end: selectionStart };
}

function getReplacementText(event, fallbackText) {
    const inputType = event.inputType || '';
    if (inputType.startsWith('delete')) {
        return '';
    }
    if (inputType === 'insertLineBreak' || inputType === 'insertParagraph') {
        return '\n';
    }
    if (event.dataTransfer) {
        const pastedText = event.dataTransfer.getData('text/plain');
        if (pastedText) {
            return pastedText;
        }
    }
    if (event.data !== null && event.data !== undefined) {
        return event.data;
    }
    return fallbackText || '';
}

function expandRangeToLines(content, range) {
    const text = content || '';
    const safeStart = Math.max(0, Math.min(range.start, text.length));
    const safeEnd = Math.max(safeStart, Math.min(range.end, text.length));
    const lineStart = text.lastIndexOf('\n', Math.max(0, safeStart - 1)) + 1;
    const nextBreak = text.indexOf('\n', safeEnd);
    const lineEnd = nextBreak === -1 ? text.length : nextBreak;
    return { start: lineStart, end: lineEnd };
}

function positionToLineColumn(content, position) {
    const text = content || '';
    const safePosition = Math.max(0, Math.min(position, text.length));
    let line = 1;
    let lineStart = 0;
    for (let index = 0; index < safePosition; index += 1) {
        if (text[index] === '\n') {
            line += 1;
            lineStart = index + 1;
        }
    }
    return { line, column: safePosition - lineStart + 1 };
}

function getDocumentId(doc) {
    return doc?.documentId || doc?.id || doc?.docId || '';
}

function normalizeDocument(doc) {
    if (!doc) {
        return doc;
    }
    return {
        ...doc,
        documentId: getDocumentId(doc)
    };
}

createApp({
    setup() {
        const user = ref(null);
        const loginForm = reactive({ username: '', password: '' });
        const error = ref('');
        const toast = ref('');

        const currentView = ref('list');
        const documents = ref([]);

        const currentDocument = ref(null);
        const documentContent = ref('');
        const isTextFile = ref(false);
        const isTruncated = ref(false);
        const revision = ref(0);
        const activeLocks = ref([]);
        const selectionRange = ref({ start: 0, end: 0 });
        const currentLock = ref(null);
        const hasEditLock = computed(() => Boolean(currentLock.value));
        const editorRef = ref(null);
        const highlightRef = ref(null);
        const eventSource = ref(null);
        const saveTimer = ref(null);
        const pendingAutoSave = ref(false);
        const pendingLocalEdit = ref(null);
        const pendingNativeEdit = ref(null);
        const pendingSavedPatch = ref(null);
        const isSaving = ref(false);

        const showVersions = ref(false);
        const versions = ref([]);
        const versionDoc = ref(null);
        const isLoadingVersions = ref(false);

        const detectedLanguage = computed(() => detectLanguage(currentDocument.value?.fileName || ''));
        const isCodeFile = computed(() => Boolean(detectedLanguage.value && detectedLanguage.value !== 'plaintext'));
        const languageLabel = computed(() => formatLanguageLabel(detectedLanguage.value));
        const highlightedDocumentContent = computed(() => highlightCode(documentContent.value, detectedLanguage.value));
        const highlightedEditorContent = computed(() => {
            const highlighted = highlightedDocumentContent.value || '<br>';
            return highlighted.endsWith('\n') ? `${highlighted}<br>` : highlighted;
        });
        const lockRangeLabel = computed(() => {
            if (!currentLock.value) {
                return '未锁定区间';
            }
            return `[${currentLock.value.currentStart}, ${currentLock.value.currentEnd})`;
        });
        const currentCursorLabel = computed(() => {
            const cursor = positionToLineColumn(documentContent.value, selectionRange.value.start);
            return `第 ${cursor.line} 行，第 ${cursor.column} 列`;
        });
        const activeEditors = computed(() => (activeLocks.value || []).map((lock) => {
            const start = positionToLineColumn(documentContent.value, lock.currentStart);
            const end = positionToLineColumn(documentContent.value, lock.currentEnd);
            const samePosition = start.line === end.line && start.column === end.column;
            return {
                ...lock,
                startLine: start.line,
                startColumn: start.column,
                endLine: end.line,
                endColumn: end.column,
                locationLabel: samePosition
                    ? `第 ${start.line} 行，第 ${start.column} 列`
                    : `第 ${start.line} 行第 ${start.column} 列 - 第 ${end.line} 行第 ${end.column} 列`,
                statusLabel: lock.queued ? `等待中，第 ${lock.queuePosition} 位` : '正在编辑'
            };
        }));

        const showToast = (msg) => {
            toast.value = msg;
            setTimeout(() => {
                if (toast.value === msg) {
                    toast.value = '';
                }
            }, 3000);
        };

        const clearSession = () => {
            closeEventStream();
            AUTH_STORAGE.removeItem('token');
            user.value = null;
            loginForm.username = '';
            loginForm.password = '';
        };

        const apiCall = async (endpoint, options = {}) => {
            const token = AUTH_STORAGE.getItem('token');
            const headers = { ...options.headers };

            if (token) {
                headers.Authorization = `Bearer ${token}`;
            }
            if (!options.body || !(options.body instanceof FormData)) {
                headers['Content-Type'] = 'application/json';
            }

            try {
                const res = await fetch(`${API_BASE}${endpoint}`, { ...options, headers });
                if (res.status === 401 && endpoint !== '/auth/login') {
                    clearSession();
                    throw new Error('会话已过期，请重新登录');
                }

                if (options.isDownload) {
                    if (!res.ok) {
                        throw new Error('下载失败');
                    }
                    return res;
                }

                const data = await res.json();
                if (!data.success) {
                    throw new Error(data.message || '请求失败');
                }
                return data.data;
            } catch (err) {
                if (!options.silent) {
                    error.value = err.message;
                    showToast(err.message);
                }
                throw err;
            }
        };

        const closeEventStream = () => {
            if (eventSource.value) {
                eventSource.value.close();
                eventSource.value = null;
            }
        };

        const clearPendingEdits = () => {
            pendingLocalEdit.value = null;
            pendingNativeEdit.value = null;
            pendingSavedPatch.value = null;
            pendingAutoSave.value = false;
            clearAutoSaveTimer();
        };

        const openEventStream = (documentId) => {
            closeEventStream();
            const token = AUTH_STORAGE.getItem('token');
            if (!token || !documentId) {
                return;
            }

            const url = `${API_BASE}/documents/${documentId}/events?token=${encodeURIComponent(token)}`;
            const source = new EventSource(url, { withCredentials: false });
            eventSource.value = source;

            source.addEventListener('lock-acquired', (event) => {
                const payload = JSON.parse(event.data);
                activeLocks.value = payload.activeLocks || [];
                syncCurrentLockFromActive();
                if (currentLock.value && pendingLocalEdit.value && currentLock.value.lockId === pendingLocalEdit.value.lockId) {
                    const edit = pendingLocalEdit.value;
                    pendingLocalEdit.value = null;
                    applyLocalEdit(edit.start, edit.end, edit.replacementText);
                }
            });

            source.addEventListener('lock-queued', (event) => {
                const payload = JSON.parse(event.data);
                activeLocks.value = payload.activeLocks || [];
                syncCurrentLockFromActive();
            });

            source.addEventListener('lock-released', (event) => {
                const payload = JSON.parse(event.data);
                activeLocks.value = payload.activeLocks || [];
                if (currentLock.value && currentLock.value.lockId === payload.lockId) {
                    currentLock.value = null;
                } else {
                    syncCurrentLockFromActive();
                }
            });

            source.addEventListener('content-updated', async (event) => {
                const payload = JSON.parse(event.data);
                if (isOwnSavedPatchEvent(payload)) {
                    pendingSavedPatch.value = null;
                    isSaving.value = false;
                    pendingAutoSave.value = false;
                    revision.value = payload.revisionAfter;
                    activeLocks.value = payload.activeLocks || [];
                    syncCurrentLockFromActive();
                    return;
                }
                if (payload.revisionBefore !== revision.value) {
                    await reloadCurrentDocument(true);
                    return;
                }

                applyRemotePatch(payload.start, payload.end, payload.replacementText);
                revision.value = payload.revisionAfter;
                activeLocks.value = payload.activeLocks || [];
                syncCurrentLockFromActive();
            });

            source.onerror = async () => {
                closeEventStream();
                if (currentDocument.value?.documentId) {
                    await reloadCurrentDocument(true);
                    openEventStream(currentDocument.value.documentId);
                }
            };
        };

        const login = async () => {
            error.value = '';
            try {
                const data = await apiCall('/auth/login', {
                    method: 'POST',
                    body: JSON.stringify(loginForm)
                });
                AUTH_STORAGE.setItem('token', data.token);
                user.value = data.user;
                await fetchDocuments();
            } catch (err) {
            }
        };

        const logout = async () => {
            try {
                await apiCall('/auth/logout', { method: 'POST', silent: true });
            } catch (e) {
            }
            clearSession();
        };

        const checkAuth = async () => {
            if (AUTH_STORAGE.getItem('token')) {
                try {
                    const data = await apiCall('/auth/me', { silent: true });
                    user.value = data?.user || data;
                    await fetchDocuments(true);
                } catch (e) {
                    clearSession();
                }
            }
        };

        const fetchDocuments = async (silent = false) => {
            try {
                const docs = await apiCall('/documents', { silent });
                documents.value = (docs || []).map(normalizeDocument);
            } catch (err) {
                if (!silent) {
                    showToast(err.message || '文档列表刷新失败');
                }
            }
        };

        const uploadDocument = async (event) => {
            const file = event.target.files[0];
            if (!file) {
                return;
            }

            const formData = new FormData();
            formData.append('file', file);

            try {
                await apiCall('/documents', {
                    method: 'POST',
                    body: formData
                });
                showToast('上传成功');
                await fetchDocuments();
            } catch (err) {
                showToast(err.message || '上传失败');
            }
            event.target.value = '';
        };

        const downloadDocument = async (doc) => {
            try {
                const docId = getDocumentId(doc);
                if (!docId) {
                    throw new Error('文档编号缺失，无法下载');
                }
                const res = await apiCall(`/documents/${docId}/download`, { isDownload: true });
                const blob = await res.blob();
                const url = window.URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = doc.fileName;
                document.body.appendChild(a);
                a.click();
                window.URL.revokeObjectURL(url);
                document.body.removeChild(a);
                showToast('下载成功');
            } catch (err) {
                showToast(err.message || '下载失败');
            }
        };

        const syncCurrentLockFromActive = () => {
            if (!user.value) {
                currentLock.value = null;
                return;
            }
            const ownLock = normalizeClientLock(
                (activeLocks.value || []).find((item) => item.owner === user.value.username),
                user.value.username
            );
            if (!ownLock) {
                currentLock.value = null;
                return;
            }

            const previousLock = currentLock.value;
            if (previousLock?.lockId === ownLock.lockId && previousLock.dirty) {
                currentLock.value = {
                    ...ownLock,
                    currentStart: previousLock.currentStart,
                    currentEnd: previousLock.currentEnd,
                    dirty: true
                };
                return;
            }

            currentLock.value = ownLock;
        };

        const updateSelectionFromEditor = () => {
            const textarea = editorRef.value;
            if (!textarea) {
                return;
            }
            selectionRange.value = {
                start: textarea.selectionStart || 0,
                end: textarea.selectionEnd || 0
            };
        };

        const restoreSelection = async () => {
            await nextTick();
            const textarea = editorRef.value;
            if (!textarea) {
                return;
            }
            textarea.setSelectionRange(selectionRange.value.start, selectionRange.value.end);
        };

        const syncHighlightScroll = () => {
            const textarea = editorRef.value;
            const highlight = highlightRef.value;
            if (!textarea || !highlight) {
                return;
            }
            highlight.scrollTop = textarea.scrollTop;
            highlight.scrollLeft = textarea.scrollLeft;
        };

        const clearAutoSaveTimer = () => {
            if (saveTimer.value) {
                clearTimeout(saveTimer.value);
                saveTimer.value = null;
            }
        };

        const scheduleAutoSave = () => {
            pendingAutoSave.value = true;
            clearAutoSaveTimer();
            saveTimer.value = setTimeout(() => {
                saveTimer.value = null;
                autoSaveDocument();
            }, 1500);
        };

        const isRangeWithinLock = (lock, range) => Boolean(lock)
            && !lock.queued
            && range.start >= lock.currentStart
            && range.end <= lock.currentEnd;

        const isOwnSavedPatchEvent = (payload) => Boolean(
            payload
            && pendingSavedPatch.value
            && payload.editor === user.value?.username
            && payload.replacementText === pendingSavedPatch.value.replacementText
            && (
                (
                    payload.revisionBefore === pendingSavedPatch.value.revisionBefore
                    && payload.revisionAfter === pendingSavedPatch.value.revisionAfter
                )
                || payload.revisionBefore >= pendingSavedPatch.value.revisionBefore
            )
        );

        const ensureLockForRange = async (range) => {
            if (!currentDocument.value || isSaving.value) {
                return null;
            }

            if (currentLock.value) {
                const withinRange = range.start >= currentLock.value.currentStart
                    && range.end <= currentLock.value.currentEnd;
                if (withinRange) {
                    return currentLock.value;
                }
                if (currentLock.value.queued) {
                    return currentLock.value;
                }

                if (pendingAutoSave.value) {
                    await autoSaveDocument();
                } else {
                    await releaseCurrentLockSilently();
                }
            }

            return requestEditLock(range, true);
        };

        const applyLocalEdit = (start, end, replacementText) => {
            const textarea = editorRef.value;
            if (!textarea || !currentLock.value) {
                return;
            }

            textarea.setRangeText(replacementText, start, end, 'end');
            documentContent.value = textarea.value;

            const delta = replacementText.length - (end - start);
            currentLock.value.currentStart = Math.min(currentLock.value.currentStart, start);
            currentLock.value.currentEnd = Math.max(
                currentLock.value.currentStart,
                currentLock.value.currentEnd + delta
            );
            currentLock.value.currentEnd = Math.max(
                currentLock.value.currentEnd,
                start + replacementText.length
            );
            currentLock.value.dirty = true;

            updateSelectionFromEditor();
            syncHighlightScroll();
            scheduleAutoSave();
        };

        const handleAutoBeforeInput = async (event) => {
            const target = event.target;
            const range = getInputRange(target, event.inputType || '', event.data || '');
            const replacementText = getReplacementText(event, '');
            const inputType = event.inputType || '';

            if (inputType === 'insertFromPaste' || inputType === 'insertFromDrop') {
                pendingNativeEdit.value = null;
                return;
            }

            if (isRangeWithinLock(currentLock.value, range)) {
                pendingNativeEdit.value = {
                    start: range.start,
                    end: range.end,
                    replacementText
                };
                clearAutoSaveTimer();
                return;
            }

            event.preventDefault();
            clearAutoSaveTimer();

            const lockRange = expandRangeToLines(documentContent.value, range);
            const lock = await ensureLockForRange(lockRange);
            if (!lock) {
                const cursor = positionToLineColumn(documentContent.value, range.start);
                showToast(`第 ${cursor.line} 行正在被其他用户编辑，请稍后重试`);
                return;
            }
            if (lock.queued) {
                pendingLocalEdit.value = {
                    lockId: lock.lockId,
                    start: range.start,
                    end: range.end,
                    replacementText
                };
                showToast(`第 ${lock.queuePosition} 位等待编辑该行`);
                return;
            }

            pendingNativeEdit.value = null;
            applyLocalEdit(range.start, range.end, replacementText);
        };

        const handleEditorPaste = async (event) => {
            event.preventDefault();
            clearAutoSaveTimer();
            pendingNativeEdit.value = null;

            const target = event.target;
            const range = { start: target.selectionStart || 0, end: target.selectionEnd || 0 };
            const replacementText = event.clipboardData?.getData('text/plain') || '';
            const lockRange = expandRangeToLines(documentContent.value, range);
            const lock = await ensureLockForRange(lockRange);
            if (!lock) {
                const cursor = positionToLineColumn(documentContent.value, range.start);
                showToast(`第 ${cursor.line} 行正在被其他用户编辑，请稍后重试`);
                return;
            }
            if (lock.queued) {
                pendingLocalEdit.value = {
                    lockId: lock.lockId,
                    start: range.start,
                    end: range.end,
                    replacementText
                };
                showToast(`第 ${lock.queuePosition} 位等待编辑该行`);
                return;
            }

            applyLocalEdit(range.start, range.end, replacementText);
        };

        const releaseCurrentLockSilently = async () => {
            if (!currentLock.value || !currentDocument.value) {
                return;
            }

            try {
                const data = await apiCall(`/documents/${currentDocument.value.documentId}/lock`, { method: 'DELETE' });
                currentLock.value = null;
                activeLocks.value = data.activeLocks || [];
                clearPendingEdits();
            } catch (err) {
            }
        };

        const autoSaveDocument = async () => {
            if (!currentLock.value || currentLock.value.queued || !currentDocument.value) {
                pendingAutoSave.value = false;
                return;
            }

            const { currentStart, currentEnd, lockId } = currentLock.value;
            const replacementText = documentContent.value.slice(currentStart, currentEnd);
            pendingSavedPatch.value = {
                revisionBefore: revision.value,
                revisionAfter: revision.value + 1,
                replacementText
            };

            try {
                isSaving.value = true;
                const data = await apiCall(`/documents/${currentDocument.value.documentId}/content`, {
                    method: 'PATCH',
                    body: JSON.stringify({
                        lockId,
                        clientRevision: revision.value,
                        replacementText,
                        comment: '自动保存'
                    })
                });
                revision.value = data.document.revision;
                activeLocks.value = data.activeLocks || [];
                currentLock.value = null;
                pendingLocalEdit.value = null;
                pendingNativeEdit.value = null;
                pendingAutoSave.value = false;
                clearAutoSaveTimer();
                await fetchDocuments();
            } catch (err) {
                clearPendingEdits();
                await reloadCurrentDocument(true);
            } finally {
                isSaving.value = false;
            }
        };

        const loadFullDocument = async (doc) => {
            const docId = getDocumentId(doc);
            if (!docId) {
                throw new Error('文档编号缺失，无法打开');
            }
            const data = await apiCall(`/documents/${docId}/content`);
            currentDocument.value = normalizeDocument(data.document || doc);
            documentContent.value = data.contentText || '';
            revision.value = data.revision || 0;
            activeLocks.value = data.activeLocks || [];
            isTextFile.value = true;
            isTruncated.value = false;
            syncCurrentLockFromActive();
            currentView.value = 'editor';
            await nextTick();
            updateSelectionFromEditor();
            openEventStream(docId);
        };

        const reloadCurrentDocument = async (silent = false) => {
            if (!currentDocument.value) {
                return;
            }
            const previousSelection = { ...selectionRange.value };
            clearPendingEdits();
            const data = await apiCall(`/documents/${currentDocument.value.documentId}/content`);
            currentDocument.value = data.document;
            documentContent.value = data.contentText || '';
            revision.value = data.revision || 0;
            activeLocks.value = data.activeLocks || [];
            syncCurrentLockFromActive();
            selectionRange.value = previousSelection;
            await restoreSelection();
            if (!silent) {
                showToast('文档已刷新');
            }
        };

        const viewDocument = async (doc) => {
            try {
                await loadFullDocument(doc);
            } catch (err) {
                showToast(err.message || '打开文档失败');
            }
        };

        const requestEditLock = async (range = selectionRange.value, silent = false) => {
            const start = range.start;
            const end = range.end;

            try {
                const data = await apiCall(`/documents/${currentDocument.value.documentId}/lock`, {
                    method: 'POST',
                    body: JSON.stringify({ start, end, revision: revision.value })
                });
                currentLock.value = {
                    lockId: data.lockId,
                    documentId: data.documentId,
                    owner: user.value.username,
                    baseRevision: data.revision,
                    baseStart: start,
                    baseEnd: end,
                    serverStart: data.start,
                    serverEnd: data.end,
                    currentStart: data.start,
                    currentEnd: data.end,
                    dirty: false,
                    queued: Boolean(data.queued),
                    queuePosition: data.queuePosition || 0
                };
                activeLocks.value = data.activeLocks || [];
                if (silent) {
                    return currentLock.value;
                }
                return currentLock.value;
                showToast('已锁定当前区域');
            } catch (err) {
                if (err.message.includes('正在被其他用户编辑') || err.message.includes('版本已更新')) {
                    await reloadCurrentDocument(true);
                }
                throw err;
            }
        };

        const releaseEditLock = async () => {
            try {
                const data = await apiCall(`/documents/${currentDocument.value.documentId}/lock`, { method: 'DELETE' });
                currentLock.value = null;
                activeLocks.value = data.activeLocks || [];
                showToast('已释放编辑区域');
            } catch (err) {
            }
        };

        const saveDocument = async (silent = false) => {
            if (!currentLock.value) {
                showToast('请先锁定一个编辑区域');
                return;
            }

            const { currentStart, currentEnd, lockId } = currentLock.value;
            const replacementText = documentContent.value.slice(currentStart, currentEnd);
            pendingSavedPatch.value = {
                revisionBefore: revision.value,
                revisionAfter: revision.value + 1,
                replacementText
            };

            try {
                const data = await apiCall(`/documents/${currentDocument.value.documentId}/content`, {
                    method: 'PATCH',
                    body: JSON.stringify({
                        lockId,
                        clientRevision: revision.value,
                        replacementText,
                        comment: '区间编辑保存'
                    })
                });
                revision.value = data.document.revision;
                activeLocks.value = data.activeLocks || [];
                currentLock.value = null;
                pendingLocalEdit.value = null;
                pendingNativeEdit.value = null;
                pendingAutoSave.value = false;
                clearAutoSaveTimer();
                showToast('保存成功');
                await fetchDocuments();
            } catch (err) {
                clearPendingEdits();
                if (err.message.includes('版本已更新') || err.message.includes('锁定区间已失效')) {
                    await reloadCurrentDocument(true);
                }
            }
        };

        const applyRemotePatch = (start, end, replacementText) => {
            let patchStart = start;
            let patchEnd = end;
            if (currentLock.value?.dirty) {
                const localStart = lockServerStart(currentLock.value);
                const localEnd = lockServerEnd(currentLock.value);
                const localReplacementLength = Math.max(0, currentLock.value.currentEnd - currentLock.value.currentStart);
                const mappedRange = mapRangeByLength(
                    { start, end },
                    localStart,
                    localEnd,
                    localReplacementLength
                );
                patchStart = mappedRange.start;
                patchEnd = mappedRange.end;
            }

            const currentText = documentContent.value;
            documentContent.value = currentText.slice(0, patchStart) + replacementText + currentText.slice(patchEnd);

            selectionRange.value = {
                start: mapPosition(selectionRange.value.start, patchStart, patchEnd, replacementText),
                end: mapPosition(selectionRange.value.end, patchStart, patchEnd, replacementText)
            };

            if (currentLock.value) {
                currentLock.value = {
                    ...currentLock.value,
                    serverStart: mapPosition(lockServerStart(currentLock.value), start, end, replacementText),
                    serverEnd: mapPosition(lockServerEnd(currentLock.value), start, end, replacementText),
                    currentStart: mapPosition(currentLock.value.currentStart, patchStart, patchEnd, replacementText),
                    currentEnd: mapPosition(currentLock.value.currentEnd, patchStart, patchEnd, replacementText)
                };
            }
            if (pendingLocalEdit.value) {
                pendingLocalEdit.value = {
                    ...pendingLocalEdit.value,
                    start: mapPosition(pendingLocalEdit.value.start, patchStart, patchEnd, replacementText),
                    end: mapPosition(pendingLocalEdit.value.end, patchStart, patchEnd, replacementText)
                };
            }

            restoreSelection();
        };

        const handleBeforeInput = (event) => {
            if (!currentLock.value) {
                return;
            }

            const target = event.target;
            const inputStart = target.selectionStart;
            const inputEnd = target.selectionEnd;
            const withinRange = inputStart >= currentLock.value.currentStart
                && inputEnd <= currentLock.value.currentEnd;

            if (!withinRange) {
                event.preventDefault();
                showToast('请先释放并重新锁定目标位置');
            }
        };

        const handleEditorInput = (event) => {
            const previousContent = documentContent.value;
            documentContent.value = event.target.value;
            updateSelectionFromEditor();
            syncHighlightScroll();

            if (!currentLock.value || currentLock.value.queued) {
                pendingNativeEdit.value = null;
                return;
            }

            const nativeEdit = pendingNativeEdit.value;
            const delta = nativeEdit
                ? nativeEdit.replacementText.length - (nativeEdit.end - nativeEdit.start)
                : event.target.value.length - previousContent.length;

            if (nativeEdit) {
                currentLock.value.currentStart = Math.min(currentLock.value.currentStart, nativeEdit.start);
                currentLock.value.currentEnd = Math.max(
                    currentLock.value.currentStart,
                    currentLock.value.currentEnd + delta
                );
                currentLock.value.currentEnd = Math.max(
                    currentLock.value.currentEnd,
                    nativeEdit.start + nativeEdit.replacementText.length
                );
                currentLock.value.dirty = true;
            } else if (delta !== 0) {
                currentLock.value.currentEnd = Math.max(
                    currentLock.value.currentStart,
                    currentLock.value.currentEnd + delta
                );
                currentLock.value.dirty = true;
            }

            pendingNativeEdit.value = null;
            scheduleAutoSave();
        };

        const handleEditorSelect = () => {
            updateSelectionFromEditor();
        };

        const backToList = async () => {
            if (hasEditLock.value) {
                await autoSaveDocument();
            }
            closeEventStream();
            currentView.value = 'list';
            currentDocument.value = null;
            documentContent.value = '';
            revision.value = 0;
            activeLocks.value = [];
            currentLock.value = null;
            clearPendingEdits();
            await fetchDocuments();
        };

        const opLabel = (op) => ({
            UPLOAD: '上传',
            EDIT: '编辑',
            ROLLBACK: '回滚',
            DOWNLOAD: '下载',
            VIEW: '查看'
        }[op] || op || '');

        const openVersions = async (doc) => {
            const docId = getDocumentId(doc);
            if (!docId) {
                showToast('文档编号缺失，无法查看历史版本');
                return;
            }
            versionDoc.value = { documentId: docId, fileName: doc.fileName };
            versions.value = [];
            showVersions.value = true;
            isLoadingVersions.value = true;
            try {
                const data = await apiCall(`/documents/${docId}/versions`);
                versions.value = data || [];
            } catch (err) {
                showToast(err.message || '历史版本获取失败');
            } finally {
                isLoadingVersions.value = false;
            }
        };

        const closeVersions = () => {
            showVersions.value = false;
            versionDoc.value = null;
            versions.value = [];
        };

        const downloadVersion = async (v) => {
            try {
                const docId = getDocumentId(versionDoc.value);
                if (!docId) {
                    throw new Error('文档编号缺失，无法下载版本');
                }
                const res = await apiCall(`/documents/${docId}/versions/${v.versionId}/download`, { isDownload: true });
                const blob = await res.blob();
                const url = window.URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = `${v.versionId}-${v.fileName}`;
                document.body.appendChild(a);
                a.click();
                window.URL.revokeObjectURL(url);
                document.body.removeChild(a);
                showToast('版本下载成功');
            } catch (err) {
                showToast(err.message || '版本下载失败');
            }
        };

        const rollbackVersion = async (v) => {
            if (!confirm(`确认将文档回滚到版本 ${v.versionId} 吗？此操作会生成一条新的回滚版本。`)) {
                return;
            }
            const docId = getDocumentId(versionDoc.value);
            const fileName = versionDoc.value.fileName;
            try {
                await apiCall(`/documents/${docId}/versions/${v.versionId}/rollback`, { method: 'POST' });
                showToast('版本回滚成功');
                const data = await apiCall(`/documents/${docId}/versions`);
                versions.value = data || [];
                if (currentView.value === 'editor' && currentDocument.value && getDocumentId(currentDocument.value) === docId) {
                    await viewDocument({ documentId: docId, fileName });
                }
                await fetchDocuments();
            } catch (err) {
                showToast(err.message || '版本回滚失败');
            }
        };

        onMounted(() => {
            checkAuth();
        });

        return {
            user,
            loginForm,
            error,
            toast,
            currentView,
            documents,
            currentDocument,
            documentContent,
            isTextFile,
            isTruncated,
            hasEditLock,
            activeLocks,
            activeEditors,
            selectionRange,
            currentLock,
            lockRangeLabel,
            currentCursorLabel,
            editorRef,
            highlightRef,
            showVersions,
            versions,
            versionDoc,
            isLoadingVersions,
            revision,
            isCodeFile,
            languageLabel,
            highlightedDocumentContent,
            highlightedEditorContent,
            login,
            logout,
            fetchDocuments,
            uploadDocument,
            downloadDocument,
            viewDocument,
            releaseEditLock,
            saveDocument,
            backToList,
            opLabel,
            openVersions,
            closeVersions,
            downloadVersion,
            rollbackVersion,
            handleAutoBeforeInput,
            handleEditorPaste,
            handleBeforeInput,
            handleEditorInput,
            handleEditorSelect,
            syncHighlightScroll
        };
    }
}).mount('#app');
