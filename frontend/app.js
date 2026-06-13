const { createApp, ref, reactive, computed, onMounted, nextTick, watch } = Vue;

const API_BASE = window.SHAREDOC_API_BASE || `${window.location.origin}/api/v1`;
const AUTH_STORAGE = window.sessionStorage;

const CODE_LANGUAGE_MAP = {
    c: 'c', h: 'c', cpp: 'cpp', cxx: 'cpp', cc: 'cpp', hpp: 'cpp',
    java: 'java', py: 'python', js: 'javascript', jsx: 'javascript',
    ts: 'typescript', tsx: 'typescript', go: 'go', rs: 'rust', php: 'php',
    rb: 'ruby', swift: 'swift', kt: 'kotlin', kts: 'kotlin', scala: 'scala',
    sh: 'bash', bash: 'bash', ps1: 'powershell', sql: 'sql', md: 'markdown',
    markdown: 'markdown', html: 'xml', htm: 'xml', xml: 'xml', svg: 'xml',
    css: 'css', scss: 'scss', less: 'less', json: 'json', yml: 'yaml',
    yaml: 'yaml', vue: 'xml', txt: 'plaintext'
};

const LANGUAGE_LABELS = {
    cpp: 'C/C++', c: 'C', java: 'Java', python: 'Python', javascript: 'JavaScript',
    typescript: 'TypeScript', markdown: 'Markdown', xml: 'HTML/XML', css: 'CSS',
    scss: 'SCSS', less: 'LESS', yaml: 'YAML', json: 'JSON', sql: 'SQL',
    bash: 'Bash', powershell: 'PowerShell', plaintext: '纯文本'
};

const LANGUAGE_COLORS = {
    javascript: '#f7df1e', typescript: '#3178c6', java: '#f89820', python: '#3776ab',
    c: '#5c6bc0', cpp: '#00599c', go: '#00add8', rust: '#dea584', ruby: '#cc342d',
    php: '#777bb4', swift: '#fa7343', kotlin: '#a97bff', css: '#2965f1', scss: '#cf649a',
    xml: '#e34c26', json: '#cbcb41', yaml: '#cb171e', markdown: '#6b7488', sql: '#dad8d8',
    bash: '#4eaa25', powershell: '#012456'
};

function escapeHtml(text) {
    return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function detectLanguage(fileName) {
    if (!fileName) return null;
    const normalized = fileName.toLowerCase();
    if (normalized === 'dockerfile') return 'dockerfile';
    if (normalized === 'makefile') return 'makefile';
    const ext = normalized.includes('.') ? normalized.split('.').pop() : '';
    return CODE_LANGUAGE_MAP[ext] || null;
}

function formatLanguageLabel(language) {
    if (!language) return '纯文本';
    return LANGUAGE_LABELS[language] || language.toUpperCase();
}

function highlightCode(content, language) {
    const safeContent = content || '';
    if (!safeContent) return '';
    if (!window.hljs) return escapeHtml(safeContent);
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
    if (position <= start) return position;
    if (position >= end) return position + replacementLength - (end - start);
    return start + Math.min(position - start, replacementLength);
}
function mapRangeByLength(range, start, end, replacementLength) {
    return {
        start: mapPositionByLength(range.start, start, end, replacementLength),
        end: mapPositionByLength(range.end, start, end, replacementLength)
    };
}
function lockServerStart(lock) { return Number.isFinite(lock?.serverStart) ? lock.serverStart : lock?.currentStart || 0; }
function lockServerEnd(lock) { return Number.isFinite(lock?.serverEnd) ? lock.serverEnd : lock?.currentEnd || 0; }

function normalizeClientLock(lock, owner) {
    if (!lock) return null;
    const serverStart = Number.isFinite(lock.serverStart) ? lock.serverStart : lock.currentStart;
    const serverEnd = Number.isFinite(lock.serverEnd) ? lock.serverEnd : lock.currentEnd;
    return {
        ...lock, owner: lock.owner || owner, serverStart, serverEnd,
        currentStart: Number.isFinite(lock.currentStart) ? lock.currentStart : serverStart,
        currentEnd: Number.isFinite(lock.currentEnd) ? lock.currentEnd : serverEnd,
        dirty: Boolean(lock.dirty)
    };
}

function getInputRange(target, inputType) {
    const selectionStart = target.selectionStart || 0;
    const selectionEnd = target.selectionEnd || selectionStart;
    if (selectionStart !== selectionEnd) return { start: selectionStart, end: selectionEnd };
    if (inputType === 'deleteContentBackward') return { start: Math.max(0, selectionStart - 1), end: selectionStart };
    if (inputType === 'deleteContentForward') return { start: selectionStart, end: Math.min(target.value.length, selectionStart + 1) };
    if (inputType && inputType.startsWith('delete')) return { start: Math.max(0, selectionStart - 1), end: selectionStart };
    return { start: selectionStart, end: selectionStart };
}

function getReplacementText(event, fallbackText) {
    const inputType = event.inputType || '';
    if (inputType.startsWith('delete')) return '';
    if (inputType === 'insertLineBreak' || inputType === 'insertParagraph') return '\n';
    if (event.dataTransfer) {
        const pastedText = event.dataTransfer.getData('text/plain');
        if (pastedText) return pastedText;
    }
    if (event.data !== null && event.data !== undefined) return event.data;
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
    let line = 1, lineStart = 0;
    for (let index = 0; index < safePosition; index += 1) {
        if (text[index] === '\n') { line += 1; lineStart = index + 1; }
    }
    return { line, column: safePosition - lineStart + 1 };
}

function getDocumentId(doc) { return doc?.documentId || doc?.id || doc?.docId || ''; }
function normalizeDocument(doc) { return doc ? { ...doc, documentId: getDocumentId(doc) } : doc; }

function fileExtLabel(fileName) {
    if (!fileName) return 'FILE';
    const lower = fileName.toLowerCase();
    if (lower === 'dockerfile') return 'DOCK';
    if (lower === 'makefile') return 'MAKE';
    if (!fileName.includes('.')) return 'FILE';
    return fileName.split('.').pop().slice(0, 4).toUpperCase();
}

function colorForFile(fileName, alpha) {
    const lang = detectLanguage(fileName);
    const base = LANGUAGE_COLORS[lang] || '#818cf8';
    if (alpha === undefined) return base;
    const n = parseInt(base.slice(1), 16);
    return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`;
}

function avatarColorFor(name) {
    const text = name || '?';
    let hash = 0;
    for (let i = 0; i < text.length; i += 1) hash = (hash * 31 + text.charCodeAt(i)) % 360;
    return `linear-gradient(135deg, hsl(${hash} 70% 58%), hsl(${(hash + 40) % 360} 70% 50%))`;
}

function initialsFor(name) {
    return (name || '?').trim().slice(0, 2).toUpperCase();
}

createApp({
    setup() {
        // ---- auth / global ----
        const user = ref(null);
        const loginForm = reactive({ username: '', password: '' });
        const showRegister = ref(false);
        const registerForm = reactive({ username: '', password: '', confirm: '' });
        const showPasswordDialog = ref(false);
        const passwordForm = reactive({ current: '', next: '', confirm: '' });
        const error = ref('');
        const toast = ref('');
        const theme = ref(localStorage.getItem('sharedoc-theme') || 'dark');

        // ---- library ----
        const currentView = ref('list');
        const documents = ref([]);
        const documentFilter = ref('');
        const dragActive = ref(false);
        const onlineUsers = ref([]);

        // ---- editor ----
        const currentDocument = ref(null);
        const documentContent = ref('');
        const revision = ref(0);
        const activeLocks = ref([]);
        const selectionRange = ref({ start: 0, end: 0 });
        const currentLock = ref(null);
        const hasEditLock = computed(() => Boolean(currentLock.value));
        const editorRef = ref(null);
        const highlightRef = ref(null);
        const gutterRef = ref(null);
        const eventSource = ref(null);
        const saveTimer = ref(null);
        const pendingAutoSave = ref(false);
        const pendingLocalEdit = ref(null);
        const pendingNativeEdit = ref(null);
        const pendingSavedPatch = ref(null);
        const isSaving = ref(false);

        // ---- versions ----
        const showVersions = ref(false);
        const versions = ref([]);
        const versionDoc = ref(null);
        const isLoadingVersions = ref(false);
        const selectedVersion = ref(null);
        const versionDiff = ref(null);
        const isLoadingVersionDiff = ref(false);

        // ---- dialogs ----
        const confirmState = reactive({ open: false, title: '', message: '', confirmText: '确认', kind: 'primary', resolve: null });
        const promptState = reactive({ open: false, title: '', label: '', value: '', resolve: null });
        const promptInput = ref(null);

        // ---- computed ----
        const detectedLanguage = computed(() => detectLanguage(currentDocument.value?.fileName || ''));
        const isCodeFile = computed(() => Boolean(detectedLanguage.value && detectedLanguage.value !== 'plaintext'));
        const languageLabel = computed(() => formatLanguageLabel(detectedLanguage.value));
        const highlightedDocumentContent = computed(() => highlightCode(documentContent.value, detectedLanguage.value));
        const highlightedEditorContent = computed(() => {
            const highlighted = highlightedDocumentContent.value || '<br>';
            return highlighted.endsWith('\n') ? `${highlighted}<br>` : highlighted;
        });
        const lineCount = computed(() => Math.max(1, (documentContent.value.match(/\n/g) || []).length + 1));
        const currentCursorLabel = computed(() => {
            const cursor = positionToLineColumn(documentContent.value, selectionRange.value.start);
            return `第 ${cursor.line} 行 · 第 ${cursor.column} 列`;
        });
        const filteredDocuments = computed(() => {
            const q = documentFilter.value.trim().toLowerCase();
            if (!q) return documents.value;
            return documents.value.filter((d) =>
                (d.fileName || '').toLowerCase().includes(q) || (d.owner || '').toLowerCase().includes(q));
        });
        const activeEditors = computed(() => (activeLocks.value || []).map((lock) => {
            const start = positionToLineColumn(documentContent.value, lock.currentStart);
            const end = positionToLineColumn(documentContent.value, lock.currentEnd);
            const samePosition = start.line === end.line && start.column === end.column;
            return {
                ...lock,
                locationLabel: samePosition
                    ? `第 ${start.line} 行 · 第 ${start.column} 列`
                    : `第 ${start.line}:${start.column} – ${end.line}:${end.column}`
            };
        }));
        const saveStatusKind = computed(() => {
            if (isSaving.value) return 'saving';
            if (currentLock.value?.dirty || pendingAutoSave.value) return 'dirty';
            if (hasEditLock.value) return 'editing';
            return 'idle';
        });
        const saveStatusLabel = computed(() => ({
            saving: '保存中…', dirty: '未保存的更改', editing: '编辑中', idle: '已同步'
        }[saveStatusKind.value]));

        // ---- theme ----
        watch(theme, (value) => {
            document.documentElement.dataset.theme = value;
            localStorage.setItem('sharedoc-theme', value);
        }, { immediate: true });
        const toggleTheme = () => { theme.value = theme.value === 'dark' ? 'light' : 'dark'; };

        // ---- helpers exposed to template ----
        const fileExt = (name) => fileExtLabel(name);
        const langColor = (name, alpha) => colorForFile(name, alpha);
        const avatarColor = (name) => avatarColorFor(name);
        const initials = (name) => initialsFor(name);

        const showToast = (msg) => {
            toast.value = msg;
            setTimeout(() => { if (toast.value === msg) toast.value = ''; }, 3000);
        };

        // ---- custom dialogs (replace native confirm/prompt) ----
        const confirmDialog = ({ title, message, confirmText = '确认', kind = 'primary' }) => new Promise((resolve) => {
            Object.assign(confirmState, { open: true, title, message, confirmText, kind, resolve });
        });
        const resolveConfirm = (value) => {
            confirmState.open = false;
            if (confirmState.resolve) confirmState.resolve(value);
            confirmState.resolve = null;
        };
        const promptDialog = ({ title, label, value = '' }) => new Promise((resolve) => {
            Object.assign(promptState, { open: true, title, label, value, resolve });
            nextTick(() => promptInput.value?.focus());
        });
        const resolvePrompt = (value) => {
            promptState.open = false;
            if (promptState.resolve) promptState.resolve(value);
            promptState.resolve = null;
        };

        const clearSession = () => {
            closeEventStream();
            AUTH_STORAGE.removeItem('token');
            user.value = null;
            loginForm.username = '';
            loginForm.password = '';
            currentView.value = 'list';
        };

        const apiCall = async (endpoint, options = {}) => {
            const token = AUTH_STORAGE.getItem('token');
            const headers = { ...options.headers };
            if (token) headers.Authorization = `Bearer ${token}`;
            if (!options.body || !(options.body instanceof FormData)) headers['Content-Type'] = 'application/json';

            try {
                const res = await fetch(`${API_BASE}${endpoint}`, { ...options, headers });
                if (res.status === 401 && endpoint !== '/auth/login') {
                    clearSession();
                    throw new Error('会话已过期，请重新登录');
                }
                if (options.isDownload) {
                    if (!res.ok) throw new Error('下载失败');
                    return res;
                }
                const data = await res.json();
                if (!data.success) throw new Error(data.message || '请求失败');
                return data.data;
            } catch (err) {
                if (!options.silent) { error.value = err.message; showToast(err.message); }
                throw err;
            }
        };

        // ---- SSE ----
        const closeEventStream = () => {
            if (eventSource.value) { eventSource.value.close(); eventSource.value = null; }
            onlineUsers.value = [];
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
            if (!token || !documentId) return;
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
                if (currentLock.value && currentLock.value.lockId === payload.lockId) currentLock.value = null;
                else syncCurrentLockFromActive();
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
                if (payload.revisionBefore !== revision.value) { await reloadCurrentDocument(true); return; }
                applyRemotePatch(payload.start, payload.end, payload.replacementText);
                revision.value = payload.revisionAfter;
                activeLocks.value = payload.activeLocks || [];
                syncCurrentLockFromActive();
            });
            source.addEventListener('document-rolled-back', async (event) => {
                const payload = JSON.parse(event.data);
                await reloadCurrentDocument(true);
                await fetchDocuments(true);
                showToast(`${payload.editor || '其他用户'} 已回滚到版本 ${payload.versionId}`);
            });
            source.addEventListener('presence-changed', (event) => {
                onlineUsers.value = JSON.parse(event.data).onlineUsers || [];
            });
            source.addEventListener('document-renamed', async (event) => {
                const payload = JSON.parse(event.data);
                if (currentDocument.value && payload.document) currentDocument.value = normalizeDocument(payload.document);
                await fetchDocuments(true);
                showToast(`${payload.editor || '其他用户'} 已重命名该文档`);
            });
            source.addEventListener('document-deleted', async (event) => {
                const payload = JSON.parse(event.data);
                showToast(`${payload.editor || '其他用户'} 已删除该文档`);
                await backToList();
            });
            source.onerror = async () => {
                closeEventStream();
                if (currentDocument.value?.documentId) {
                    await reloadCurrentDocument(true);
                    openEventStream(currentDocument.value.documentId);
                }
            };
        };

        // ---- auth actions ----
        const login = async () => {
            error.value = '';
            try {
                const data = await apiCall('/auth/login', { method: 'POST', body: JSON.stringify(loginForm) });
                AUTH_STORAGE.setItem('token', data.token);
                user.value = data.user;
                await fetchDocuments();
            } catch (err) { /* surfaced via toast */ }
        };

        const register = async () => {
            error.value = '';
            if (!registerForm.username || !registerForm.password) { error.value = '请输入用户名和密码'; return; }
            if (registerForm.password !== registerForm.confirm) { error.value = '两次输入的密码不一致'; return; }
            try {
                await apiCall('/auth/register', {
                    method: 'POST',
                    body: JSON.stringify({ username: registerForm.username, password: registerForm.password })
                });
                showToast('注册成功，请登录');
                loginForm.username = registerForm.username;
                loginForm.password = '';
                registerForm.username = registerForm.password = registerForm.confirm = '';
                showRegister.value = false;
            } catch (err) { /* surfaced via toast */ }
        };

        const toggleRegister = () => { showRegister.value = !showRegister.value; error.value = ''; };

        const logout = async () => {
            try { await apiCall('/auth/logout', { method: 'POST', silent: true }); } catch (e) { /* ignore */ }
            clearSession();
        };

        const openPasswordDialog = () => {
            passwordForm.current = passwordForm.next = passwordForm.confirm = '';
            error.value = '';
            showPasswordDialog.value = true;
        };
        const closePasswordDialog = () => { showPasswordDialog.value = false; };

        const changePassword = async () => {
            if (!passwordForm.current || !passwordForm.next) { showToast('请填写当前密码和新密码'); return; }
            if (passwordForm.next !== passwordForm.confirm) { showToast('两次输入的新密码不一致'); return; }
            try {
                await apiCall('/auth/password', {
                    method: 'POST',
                    body: JSON.stringify({ currentPassword: passwordForm.current, newPassword: passwordForm.next })
                });
                showToast('密码修改成功');
                showPasswordDialog.value = false;
            } catch (err) { showToast(err.message || '密码修改失败'); }
        };

        const checkAuth = async () => {
            if (!AUTH_STORAGE.getItem('token')) return;
            try {
                const data = await apiCall('/auth/me', { silent: true });
                user.value = data?.user || data;
                await fetchDocuments(true);
            } catch (e) { clearSession(); }
        };

        // ---- documents ----
        const fetchDocuments = async (silent = false) => {
            try {
                const docs = await apiCall('/documents', { silent });
                documents.value = (docs || []).map(normalizeDocument);
            } catch (err) {
                if (!silent) showToast(err.message || '文档列表刷新失败');
            }
        };

        const uploadFile = async (file) => {
            if (!file) return;
            const formData = new FormData();
            formData.append('file', file);
            try {
                await apiCall('/documents', { method: 'POST', body: formData });
                showToast(`已上传 ${file.name}`);
                await fetchDocuments();
            } catch (err) { showToast(err.message || '上传失败'); }
        };
        const uploadDocument = async (event) => {
            await uploadFile(event.target.files[0]);
            event.target.value = '';
        };
        const onDrop = async (event) => {
            dragActive.value = false;
            const file = event.dataTransfer?.files?.[0];
            if (file) await uploadFile(file);
        };

        const downloadBlob = async (endpoint, fileName) => {
            const res = await apiCall(endpoint, { isDownload: true });
            const blob = await res.blob();
            const url = window.URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = fileName;
            document.body.appendChild(a);
            a.click();
            window.URL.revokeObjectURL(url);
            document.body.removeChild(a);
        };

        const downloadDocument = async (doc) => {
            try {
                const docId = getDocumentId(doc);
                if (!docId) throw new Error('文档编号缺失');
                await downloadBlob(`/documents/${docId}/download`, doc.fileName);
                showToast('下载成功');
            } catch (err) { showToast(err.message || '下载失败'); }
        };

        const deleteDocument = async (doc) => {
            const docId = getDocumentId(doc);
            if (!docId) return;
            const ok = await confirmDialog({
                title: '删除文档',
                message: `确认删除「${doc.fileName}」吗？这会一并删除其全部历史版本，且不可恢复。`,
                confirmText: '删除', kind: 'danger'
            });
            if (!ok) return;
            try {
                await apiCall(`/documents/${docId}`, { method: 'DELETE' });
                showToast('文档已删除');
                await fetchDocuments();
            } catch (err) { showToast(err.message || '删除失败'); }
        };

        const renameDocument = async (doc) => {
            const docId = getDocumentId(doc);
            if (!docId) return;
            const newName = await promptDialog({ title: '重命名文档', label: '新文件名（含扩展名）', value: doc.fileName });
            if (newName === null) return;
            const trimmed = newName.trim();
            if (!trimmed || trimmed === doc.fileName) return;
            try {
                await apiCall(`/documents/${docId}`, { method: 'PATCH', body: JSON.stringify({ fileName: trimmed }) });
                showToast('文档已重命名');
                await fetchDocuments();
            } catch (err) { showToast(err.message || '重命名失败'); }
        };

        // ---- locks / editing (ported, behaviour-preserving) ----
        const syncCurrentLockFromActive = () => {
            if (!user.value) { currentLock.value = null; return; }
            const ownLock = normalizeClientLock(
                (activeLocks.value || []).find((item) => item.owner === user.value.username),
                user.value.username
            );
            if (!ownLock) { currentLock.value = null; return; }
            const previousLock = currentLock.value;
            if (previousLock?.lockId === ownLock.lockId && previousLock.dirty) {
                currentLock.value = { ...ownLock, currentStart: previousLock.currentStart, currentEnd: previousLock.currentEnd, dirty: true };
                return;
            }
            currentLock.value = ownLock;
        };

        const updateSelectionFromEditor = () => {
            const textarea = editorRef.value;
            if (!textarea) return;
            selectionRange.value = { start: textarea.selectionStart || 0, end: textarea.selectionEnd || 0 };
        };

        const restoreSelection = async () => {
            await nextTick();
            const textarea = editorRef.value;
            if (!textarea) return;
            textarea.setSelectionRange(selectionRange.value.start, selectionRange.value.end);
        };

        const syncHighlightScroll = () => {
            const textarea = editorRef.value;
            if (!textarea) return;
            if (highlightRef.value) {
                highlightRef.value.scrollTop = textarea.scrollTop;
                highlightRef.value.scrollLeft = textarea.scrollLeft;
            }
            if (gutterRef.value) gutterRef.value.scrollTop = textarea.scrollTop;
        };

        const clearAutoSaveTimer = () => {
            if (saveTimer.value) { clearTimeout(saveTimer.value); saveTimer.value = null; }
        };
        const scheduleAutoSave = () => {
            pendingAutoSave.value = true;
            clearAutoSaveTimer();
            saveTimer.value = setTimeout(() => { saveTimer.value = null; autoSaveDocument(); }, 1500);
        };

        const isRangeWithinLock = (lock, range) => Boolean(lock) && !lock.queued
            && range.start >= lock.currentStart && range.end <= lock.currentEnd;

        const isOwnSavedPatchEvent = (payload) => Boolean(
            payload && pendingSavedPatch.value
            && payload.editor === user.value?.username
            && payload.replacementText === pendingSavedPatch.value.replacementText
            && ((payload.revisionBefore === pendingSavedPatch.value.revisionBefore
                && payload.revisionAfter === pendingSavedPatch.value.revisionAfter)
                || payload.revisionBefore >= pendingSavedPatch.value.revisionBefore)
        );

        const ensureLockForRange = async (range) => {
            if (!currentDocument.value || isSaving.value) return null;
            if (currentLock.value) {
                const withinRange = range.start >= currentLock.value.currentStart && range.end <= currentLock.value.currentEnd;
                if (withinRange) return currentLock.value;
                if (currentLock.value.queued) return currentLock.value;
                if (pendingAutoSave.value) await autoSaveDocument();
                else await releaseCurrentLockSilently();
            }
            return requestEditLock(range, true);
        };

        const applyLocalEdit = (start, end, replacementText) => {
            const textarea = editorRef.value;
            if (!textarea || !currentLock.value) return;
            textarea.setRangeText(replacementText, start, end, 'end');
            documentContent.value = textarea.value;
            const delta = replacementText.length - (end - start);
            currentLock.value.currentStart = Math.min(currentLock.value.currentStart, start);
            currentLock.value.currentEnd = Math.max(currentLock.value.currentStart, currentLock.value.currentEnd + delta);
            currentLock.value.currentEnd = Math.max(currentLock.value.currentEnd, start + replacementText.length);
            currentLock.value.dirty = true;
            updateSelectionFromEditor();
            syncHighlightScroll();
            scheduleAutoSave();
        };

        const handleAutoBeforeInput = async (event) => {
            const target = event.target;
            const range = getInputRange(target, event.inputType || '');
            const replacementText = getReplacementText(event, '');
            const inputType = event.inputType || '';

            if (inputType === 'insertFromPaste' || inputType === 'insertFromDrop') { pendingNativeEdit.value = null; return; }

            if (isRangeWithinLock(currentLock.value, range)) {
                pendingNativeEdit.value = { start: range.start, end: range.end, replacementText };
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
                pendingLocalEdit.value = { lockId: lock.lockId, start: range.start, end: range.end, replacementText };
                showToast(`已排队，第 ${lock.queuePosition} 位等待编辑该行`);
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
                pendingLocalEdit.value = { lockId: lock.lockId, start: range.start, end: range.end, replacementText };
                showToast(`已排队，第 ${lock.queuePosition} 位等待编辑该行`);
                return;
            }
            applyLocalEdit(range.start, range.end, replacementText);
        };

        const releaseCurrentLockSilently = async () => {
            if (!currentLock.value || !currentDocument.value) return;
            try {
                const data = await apiCall(`/documents/${currentDocument.value.documentId}/lock`, { method: 'DELETE' });
                currentLock.value = null;
                activeLocks.value = data.activeLocks || [];
                clearPendingEdits();
            } catch (err) { /* ignore */ }
        };

        const autoSaveDocument = async () => {
            if (!currentLock.value || currentLock.value.queued || !currentDocument.value) { pendingAutoSave.value = false; return; }
            const { currentStart, currentEnd, lockId } = currentLock.value;
            const replacementText = documentContent.value.slice(currentStart, currentEnd);
            pendingSavedPatch.value = { revisionBefore: revision.value, revisionAfter: revision.value + 1, replacementText };
            try {
                isSaving.value = true;
                const data = await apiCall(`/documents/${currentDocument.value.documentId}/content`, {
                    method: 'PATCH',
                    body: JSON.stringify({ lockId, clientRevision: revision.value, replacementText, comment: '自动保存' })
                });
                revision.value = data.document.revision;
                activeLocks.value = data.activeLocks || [];
                currentLock.value = null;
                pendingLocalEdit.value = null;
                pendingNativeEdit.value = null;
                pendingAutoSave.value = false;
                clearAutoSaveTimer();
                await fetchDocuments(true);
            } catch (err) {
                clearPendingEdits();
                await reloadCurrentDocument(true);
            } finally { isSaving.value = false; }
        };

        const loadFullDocument = async (doc) => {
            const docId = getDocumentId(doc);
            if (!docId) throw new Error('文档编号缺失，无法打开');
            const data = await apiCall(`/documents/${docId}/content`);
            currentDocument.value = normalizeDocument(data.document || doc);
            documentContent.value = data.contentText || '';
            revision.value = data.revision || 0;
            activeLocks.value = data.activeLocks || [];
            syncCurrentLockFromActive();
            currentView.value = 'editor';
            await nextTick();
            updateSelectionFromEditor();
            openEventStream(docId);
        };

        const reloadCurrentDocument = async (silent = false) => {
            if (!currentDocument.value) return;
            const previousSelection = { ...selectionRange.value };
            clearPendingEdits();
            const data = await apiCall(`/documents/${currentDocument.value.documentId}/content`);
            currentDocument.value = normalizeDocument(data.document);
            documentContent.value = data.contentText || '';
            revision.value = data.revision || 0;
            activeLocks.value = data.activeLocks || [];
            syncCurrentLockFromActive();
            selectionRange.value = previousSelection;
            await restoreSelection();
            if (!silent) showToast('文档已刷新');
        };

        const viewDocument = async (doc) => {
            try { await loadFullDocument(doc); }
            catch (err) { showToast(err.message || '打开文档失败'); }
        };

        const requestEditLock = async (range = selectionRange.value, silent = false) => {
            const { start, end } = range;
            try {
                const data = await apiCall(`/documents/${currentDocument.value.documentId}/lock`, {
                    method: 'POST', body: JSON.stringify({ start, end, revision: revision.value })
                });
                currentLock.value = {
                    lockId: data.lockId, documentId: data.documentId, owner: user.value.username,
                    baseRevision: data.revision, baseStart: start, baseEnd: end,
                    serverStart: data.start, serverEnd: data.end, currentStart: data.start, currentEnd: data.end,
                    dirty: false, queued: Boolean(data.queued), queuePosition: data.queuePosition || 0
                };
                activeLocks.value = data.activeLocks || [];
                if (!silent) showToast('已锁定当前区域');
                return currentLock.value;
            } catch (err) {
                if (err.message.includes('版本已更新')) await reloadCurrentDocument(true);
                throw err;
            }
        };

        const saveDocument = async () => {
            if (!currentLock.value) { showToast('请先在文档中点选一个编辑位置'); return; }
            const { currentStart, currentEnd, lockId } = currentLock.value;
            const replacementText = documentContent.value.slice(currentStart, currentEnd);
            pendingSavedPatch.value = { revisionBefore: revision.value, revisionAfter: revision.value + 1, replacementText };
            try {
                const data = await apiCall(`/documents/${currentDocument.value.documentId}/content`, {
                    method: 'PATCH',
                    body: JSON.stringify({ lockId, clientRevision: revision.value, replacementText, comment: '区间编辑保存' })
                });
                revision.value = data.document.revision;
                activeLocks.value = data.activeLocks || [];
                currentLock.value = null;
                pendingLocalEdit.value = null;
                pendingNativeEdit.value = null;
                pendingAutoSave.value = false;
                clearAutoSaveTimer();
                showToast('保存成功');
                await fetchDocuments(true);
            } catch (err) {
                clearPendingEdits();
                if (err.message.includes('版本已更新') || err.message.includes('锁定区间已失效')) await reloadCurrentDocument(true);
            }
        };

        const applyRemotePatch = (start, end, replacementText) => {
            let patchStart = start, patchEnd = end;
            if (currentLock.value?.dirty) {
                const localStart = lockServerStart(currentLock.value);
                const localEnd = lockServerEnd(currentLock.value);
                const localReplacementLength = Math.max(0, currentLock.value.currentEnd - currentLock.value.currentStart);
                const mapped = mapRangeByLength({ start, end }, localStart, localEnd, localReplacementLength);
                patchStart = mapped.start;
                patchEnd = mapped.end;
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

        const handleEditorInput = (event) => {
            const previousContent = documentContent.value;
            documentContent.value = event.target.value;
            updateSelectionFromEditor();
            syncHighlightScroll();
            if (!currentLock.value || currentLock.value.queued) { pendingNativeEdit.value = null; return; }
            const nativeEdit = pendingNativeEdit.value;
            const delta = nativeEdit
                ? nativeEdit.replacementText.length - (nativeEdit.end - nativeEdit.start)
                : event.target.value.length - previousContent.length;
            if (nativeEdit) {
                currentLock.value.currentStart = Math.min(currentLock.value.currentStart, nativeEdit.start);
                currentLock.value.currentEnd = Math.max(currentLock.value.currentStart, currentLock.value.currentEnd + delta);
                currentLock.value.currentEnd = Math.max(currentLock.value.currentEnd, nativeEdit.start + nativeEdit.replacementText.length);
                currentLock.value.dirty = true;
            } else if (delta !== 0) {
                currentLock.value.currentEnd = Math.max(currentLock.value.currentStart, currentLock.value.currentEnd + delta);
                currentLock.value.dirty = true;
            }
            pendingNativeEdit.value = null;
            scheduleAutoSave();
        };

        const handleEditorSelect = () => { updateSelectionFromEditor(); };

        const backToList = async () => {
            if (hasEditLock.value) await autoSaveDocument();
            closeEventStream();
            currentView.value = 'list';
            currentDocument.value = null;
            documentContent.value = '';
            revision.value = 0;
            activeLocks.value = [];
            onlineUsers.value = [];
            currentLock.value = null;
            clearPendingEdits();
            await fetchDocuments(true);
        };

        const releaseLockOnUnload = () => {
            if (!currentLock.value || !currentDocument.value) return;
            const token = AUTH_STORAGE.getItem('token');
            if (!token) return;
            try {
                fetch(`${API_BASE}/documents/${currentDocument.value.documentId}/lock`, {
                    method: 'DELETE', headers: { Authorization: `Bearer ${token}` }, keepalive: true
                });
            } catch (e) { /* ignore */ }
        };

        // ---- versions ----
        const opLabel = (op) => ({ UPLOAD: '上传', EDIT: '编辑', ROLLBACK: '回滚', DOWNLOAD: '下载', VIEW: '查看' }[op] || op || '');
        const changeTypeLabel = (type) => ({ ADD: '新增', DELETE: '删除', REPLACE: '替换' }[type] || type || '修改');

        const dedupeVersionComment = (comment) => {
            if (!comment) return '';
            const parts = String(comment).split(/[；;]/).map((p) => p.trim()).filter(Boolean);
            return [...new Set(parts)].join('；');
        };

        const openVersions = async (doc) => {
            const docId = getDocumentId(doc);
            if (!docId) { showToast('文档编号缺失，无法查看历史版本'); return; }
            versionDoc.value = { documentId: docId, fileName: doc.fileName };
            versions.value = [];
            selectedVersion.value = null;
            versionDiff.value = null;
            showVersions.value = true;
            isLoadingVersions.value = true;
            try {
                const data = await apiCall(`/documents/${docId}/versions`);
                versions.value = (data || []).map((v) => ({ ...v, comment: dedupeVersionComment(v.comment) }));
            } catch (err) { showToast(err.message || '历史版本获取失败'); }
            finally { isLoadingVersions.value = false; }
        };
        const closeVersions = () => {
            showVersions.value = false;
            versionDoc.value = null;
            versions.value = [];
            selectedVersion.value = null;
            versionDiff.value = null;
        };

        const selectVersion = async (v) => {
            const docId = getDocumentId(versionDoc.value);
            if (!docId || !v?.versionId) return;
            selectedVersion.value = v;
            versionDiff.value = null;
            isLoadingVersionDiff.value = true;
            try { versionDiff.value = await apiCall(`/documents/${docId}/versions/${v.versionId}/diff`); }
            catch (err) { showToast(err.message || '版本差异获取失败'); }
            finally { isLoadingVersionDiff.value = false; }
        };

        const downloadVersion = async (v) => {
            try {
                const docId = getDocumentId(versionDoc.value);
                if (!docId) throw new Error('文档编号缺失');
                await downloadBlob(`/documents/${docId}/versions/${v.versionId}/download`, `${v.versionId}-${v.fileName}`);
                showToast('版本下载成功');
            } catch (err) { showToast(err.message || '版本下载失败'); }
        };

        const rollbackVersion = async (v) => {
            const ok = await confirmDialog({
                title: '回滚版本',
                message: `确认将文档回滚到 ${v.versionId} 吗？这会生成一条新的回滚版本。`,
                confirmText: '回滚', kind: 'primary'
            });
            if (!ok) return;
            const docId = getDocumentId(versionDoc.value);
            const fileName = versionDoc.value.fileName;
            try {
                await apiCall(`/documents/${docId}/versions/${v.versionId}/rollback`, { method: 'POST' });
                showToast('版本回滚成功');
                const data = await apiCall(`/documents/${docId}/versions`);
                versions.value = (data || []).map((item) => ({ ...item, comment: dedupeVersionComment(item.comment) }));
                selectedVersion.value = null;
                versionDiff.value = null;
                if (currentView.value === 'editor' && currentDocument.value && getDocumentId(currentDocument.value) === docId) {
                    await viewDocument({ documentId: docId, fileName });
                }
                await fetchDocuments(true);
            } catch (err) { showToast(err.message || '版本回滚失败'); }
        };

        onMounted(() => {
            checkAuth();
            window.addEventListener('beforeunload', releaseLockOnUnload);
        });

        return {
            // state
            user, loginForm, showRegister, registerForm, showPasswordDialog, passwordForm,
            error, toast, theme, currentView, documents, documentFilter, dragActive, onlineUsers,
            currentDocument, documentContent, revision, hasEditLock, activeEditors, currentLock,
            editorRef, highlightRef, gutterRef, lineCount, currentCursorLabel, languageLabel, isCodeFile,
            highlightedEditorContent, filteredDocuments, saveStatusKind, saveStatusLabel,
            showVersions, versions, versionDoc, isLoadingVersions, selectedVersion, versionDiff, isLoadingVersionDiff,
            confirmState, promptState, promptInput,
            // helpers
            fileExt, langColor, avatarColor, initials, opLabel, changeTypeLabel,
            // actions
            login, register, toggleRegister, logout, toggleTheme,
            openPasswordDialog, closePasswordDialog, changePassword,
            fetchDocuments, uploadDocument, onDrop, downloadDocument, deleteDocument, renameDocument,
            viewDocument, backToList, saveDocument,
            handleAutoBeforeInput, handleEditorPaste, handleEditorInput, handleEditorSelect, syncHighlightScroll,
            openVersions, closeVersions, selectVersion, downloadVersion, rollbackVersion,
            resolveConfirm, resolvePrompt
        };
    }
}).mount('#app');
